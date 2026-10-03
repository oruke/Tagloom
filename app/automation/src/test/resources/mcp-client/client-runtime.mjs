import { createRequire } from 'node:module';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { createInterface } from 'node:readline';

/** npm 依赖只安装在独立验收目录，不进入生产分发。 */
export const clientHome = process.env.TAGLOOM_TEST_MCP_CLIENT_HOME;
/** 真实生产 CLI/MCP 分发的 Java 启动器。 */
export const java = process.env.TAGLOOM_TEST_JAVA;
/** 真实生产 CLI/MCP 分发的启动参数，凭据通过环境传递。 */
export const javaArgs = ['-cp', join(process.env.TAGLOOM_TEST_CLI_HOME, 'lib', '*'), 'com.oruke.tagloom.automation.MainKt', 'mcp'];
/** 客户端运行中失败的检查名称，只输出名称，不打印原始异常或凭据。 */
let stage = 'load-official-sdk';
/** 验收创建的长驻客户端，在任何失败分支也必须关闭。 */
const clients = [];
/** Kotlin 宿主检查点响应，不作为生产外部入口暴露。 */
const hostReplies = createInterface({ input: process.stdin })[Symbol.asyncIterator]();
/** 从隔离目录解析官方 SDK，避免依赖全局 npm 配置。 */
const require = createRequire(join(clientHome, 'package.json'));
const { Client } = await import(pathToFileURL(require.resolve('@modelcontextprotocol/sdk/client/index.js')).href);
const { StdioClientTransport } = await import(pathToFileURL(require.resolve('@modelcontextprotocol/sdk/client/stdio.js')).href);

/**
 * 核对可观察结果并写安全验收事件。
 * @param {boolean} condition 条件是否成立。
 * @param {string} name 业务检查名称。
 * @returns {void} 不成立时终止当前场景。
 */
export function check(condition, name) {
    stage = name;
    if (!condition) throw new Error(name);
    console.log(JSON.stringify({ event: 'check', name }));
}

/**
 * 用独立 HTTP 请求访问真实宿主；只读返回和实际业务结果分开核对。
 * @param {string} path 本机相对路由。
 * @param {string} method HTTP 方法。
 * @param {object | undefined} body 结构化请求。
 * @param {string} token 本次身份凭据。
 * @param {number} expected 预期 HTTP 状态。
 * @returns {Promise<object | undefined>} 已解析响应，无内容时为空。
 */
export async function api(path, method = 'GET', body, token = process.env.TAGLOOM_API_TOKEN, expected = 200) {
    stage = `http-${method}-${path.split('/')[2]}`;
    const response = await fetch(process.env.TAGLOOM_API_URL + path, {
        method,
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: body === undefined ? undefined : JSON.stringify(body),
        signal: AbortSignal.timeout(15000),
    });
    if (response.status !== expected) throw new Error('unexpected-http-status');
    return response.status === 204 ? undefined : response.json();
}

/**
 * 授予当前会话的测试 Agent，不把凭据保存到配置文件。
 * @param {object} options 授权范围和笔记 allow-list。
 * @returns {Promise<object>} 当前随机身份、token 与 sessionId。
 */
export async function grant(options = {}) {
    return api('/v1/grants', 'POST', options, undefined, 201);
}

/**
 * 启动独立官方 SDK 客户端和安装分发的 MCP 进程。
 * @param {string} token 当前 Agent 或 owner 的凭据。
 * @returns {Promise<object>} 已完成 initialize 的官方客户端。
 */
export async function connect(token) {
    stage = 'sdk-connect';
    const client = new Client({ name: 'tagloom-independent-acceptance', version: '1.0.0' });
    const transport = new StdioClientTransport({
        command: java,
        args: javaArgs,
        env: { TAGLOOM_API_URL: process.env.TAGLOOM_API_URL, TAGLOOM_API_TOKEN: token },
        stderr: 'pipe',
    });
    clients.push(client);
    // 排空 SDK 子进程 stderr，禁止协议日志阻塞或污染 JSON-RPC stdout。
    transport.stderr?.resume();
    await client.connect(transport, { timeout: 15000 });
    return client;
}

/**
 * 通过标准工具入口调用并核对兼容文字与结构化结果一致。
 * @param {object} client 官方 SDK 客户端。
 * @param {string} name 已发现工具名称。
 * @param {object} args 工具参数。
 * @returns {Promise<object>} 包含 structuredContent 的 MCP 结果。
 */
export async function call(client, name, args = {}) {
    stage = `call-${name}`;
    const result = await client.callTool({ name, arguments: args }, undefined, { timeout: 15000 });
    if (!result.structuredContent) throw new Error('structured-content-required');
    const text = result.content.find(item => item.type === 'text')?.text;
    // 错误结果也应提供机器可读的稳定失败码；旧客户端可继续读取文字失败码。
    if (!result.isError && JSON.stringify(JSON.parse(text)) !== JSON.stringify(result.structuredContent)) {
        throw new Error('text-structured-content-mismatch');
    }
    return result;
}

/**
 * 核对执行错误的稳定代码，不将底层异常当成业务结果。
 * @param {object} client 官方 SDK 客户端。
 * @param {string} name 工具名。
 * @param {object} args 工具参数。
 * @param {string} code 预期稳定错误码。
 * @returns {Promise<void>} 通过时记录业务检查。
 */
export async function toolFailure(client, name, args, code) {
    const result = await call(client, name, args);
    const data = result.structuredContent;
    check(result.isError === true && (data.errorCode ?? data.result?.errorCode) === code, `${name}-${code}`);
}

/**
 * 核对标准协议错误，随后仍允许合法请求。
 * @param {() => Promise<unknown>} action 预期失败的 MCP 调用。
 * @param {number} code 预期 JSON-RPC 错误码。
 * @param {string} name 业务检查名称。
 * @param {string | undefined} message 可选稳定失败文字。
 * @returns {Promise<void>} 通过时记录检查。
 */
export async function rpcFailure(action, code, name, message) {
    let failure;
    try { await action(); } catch (error) { failure = error; }
    check(failure?.code === code && (message === undefined || failure.message.endsWith(message)), name);
}

/**
 * 请求 Kotlin 宿主独立核对磁盘，或控制真实锁定与 API 生命周期。
 * @param {string} name 检查点名称。
 * @param {object} data 不含密钥和凭据的关联身份。
 * @returns {Promise<object>} 宿主断言完成后返回不含凭据的确认和可选资源身份。
 */
export async function checkpoint(name, data = {}) {
    stage = `host-${name}`;
    console.log(JSON.stringify({ event: 'checkpoint', name, ...data }));
    const reply = await hostReplies.next();
    if (reply.done || JSON.parse(reply.value).ok !== true) throw new Error('host-check-failed');
    check(true, `host-${name}`);
    return JSON.parse(reply.value);
}

/**
 * 创建并由持有者确认脱敏样例，客户端后续只使用实际返回的稳定 ID。
 * @param {string} title 测试样例标题。
 * @param {string} markdown 测试样例正文。
 * @returns {Promise<object>} 已确认的 Note 与 revision。
 */
export async function seed(title, markdown) {
    const pending = await api('/v1/calls', 'POST', { tool: 'create_note', arguments: { title, markdown } }, undefined, 202);
    const confirmed = await api(`/v1/approvals/${pending.callId}`, 'PUT', { firstConfirmed: true });
    return confirmed.result.payload;
}

/**
 * 释放所有客户端并返回安全验收状态，禁止输出错误中的原始协议数据。
 * @param {boolean} succeeded 场景是否全部完成。
 * @returns {Promise<void>} 已关闭客户端并写出最终事件。
 */
export async function finish(succeeded) {
    await Promise.allSettled(clients.map(client => client.close()));
    console.log(JSON.stringify({ event: succeeded ? 'completed' : 'failed', name: stage }));
    process.stdin.destroy();
    process.exitCode = succeeded ? 0 : 1;
}
