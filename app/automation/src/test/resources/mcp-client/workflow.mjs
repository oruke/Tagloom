import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { api, call, check, checkpoint, connect, grant, rpcFailure, seed, toolFailure } from './client-runtime.mjs';

/**
 * 完成发现、读取、受限访问、审批写入、导出和 tombstone 的真实跨 SDK 流程。
 * @returns {Promise<void>} 所有协议、权限和宿主持久化检查通过后结束。
 */
export async function workflow() {
    // 第一阶段：已确认样例和独立授权，客户端不接触工作区密钥。
    const first = await seed('Visible sample', 'shared sample body');
    const hidden = await seed('Hidden sample', 'shared sample body');
    const savedQuery = await checkpoint('seed-query');
    const identity = await grant({ scopes: ['READ_NOTES', 'SEARCH_NOTES', 'SUGGEST_CHANGES', 'WRITE_NOTES', 'EXPORT_NOTES', 'DELETE_NOTES'], includeDangerous: true });
    const client = await connect(identity.token);
    check(client.getServerVersion()?.name === 'tagloom', 'initialize-server-identity');
    const capabilities = client.getServerCapabilities();
    check(capabilities.tools && capabilities.resources && !capabilities.prompts, 'initialize-capabilities');
    const { tools } = await client.listTools();
    check(tools.length === 21 && new Set(tools.map(tool => tool.name)).size === 21, 'tools-discovery-unique');
    check(!tools.some(tool => /approve|grant|revoke/.test(tool.name)), 'agent-cannot-discover-owner-commands');
    const create = tools.find(tool => tool.name === 'create_note');
    check(create.inputSchema.type === 'object' && create.inputSchema.required.includes('title'), 'create-input-schema');
    check(create.annotations.readOnlyHint === false && create.annotations.openWorldHint === false, 'create-risk-annotations');
    check(tools.find(tool => tool.name === 'delete_note').annotations.destructiveHint === true, 'delete-risk-annotations');
    check(tools.find(tool => tool.name === 'get_note').annotations.readOnlyHint === true, 'read-risk-annotations');
    check((await client.listResources()).resources.some(resource => resource.uri === 'workspace://schema'), 'resource-discovery');
    check((await client.listResourceTemplates()).resourceTemplates.length === 3, 'resource-template-discovery');
    const loaded = await call(client, 'get_note', { noteId: first.noteId });
    const resource = await client.readResource({ uri: `note://${first.noteId}` });
    check(resource.contents[0].mimeType === 'application/json' && JSON.stringify(JSON.parse(resource.contents[0].text)) === JSON.stringify(loaded.structuredContent.result.payload), 'note-resource-matches-confirmed-tool');
    check((await client.readResource({ uri: 'workspace://schema' })).contents.length === 1, 'schema-resource');
    const tags = (await call(client, 'get_tags')).structuredContent.result.payload.items;
    check((await client.readResource({ uri: `tag://${tags[0].id}` })).contents.length === 1, 'tag-resource');
    const queryResource = await client.readResource({ uri: `query://saved/${savedQuery.queryId}` });
    check(JSON.parse(queryResource.contents[0].text).query.text === 'shared', 'saved-query-resource');
    await inspectorDiscovery(identity.token);

    // 第二阶段：异常参数和未知工具只影响当前请求，随后仍能完成合法调用。
    await rpcFailure(() => client.callTool({ name: 'unknown_tool', arguments: {} }), -32602, 'unknown-tool-protocol-error');
    await toolFailure(client, 'get_note', {}, 'invalid_arguments');
    await toolFailure(client, 'get_note', { noteId: first.noteId, unexpected: true }, 'invalid_arguments');
    await toolFailure(client, 'get_note', { noteId: '../invalid' }, 'invalid_arguments');
    await toolFailure(client, 'get_note', { noteId: 'missing-sample' }, 'not_found');
    await rpcFailure(() => client.readResource({ uri: 'note://missing-sample' }), -32002, 'missing-resource-protocol-error', 'not_found');
    check((await call(client, 'list_notes')).isError === false, 'session-survives-invalid-requests');
    const limitedIdentity = await grant({ allowedNoteIds: [first.noteId], maxItemsPerCall: 1 });
    const limited = await connect(limitedIdentity.token);
    check(!(await limited.listTools()).tools.some(tool => tool.name === 'create_note' || tool.name === 'delete_note'), 'scope-filtered-discovery');
    const page = (await call(limited, 'search_notes', { query: 'shared', limit: 1 })).structuredContent.result.payload;
    check(page.items.length === 1 && page.items[0].noteId === first.noteId && page.hasMore === false, 'authorization-before-pagination');
    await toolFailure(limited, 'get_note', { noteId: hidden.noteId }, 'target_out_of_scope');
    await rpcFailure(() => limited.readResource({ uri: `note://${hidden.noteId}` }), -32002, 'resource-respects-note-allow-list', 'target_out_of_scope');
    await rpcFailure(() => limited.readResource({ uri: `query://saved/${savedQuery.queryId}` }), -32002, 'saved-query-definition-not-exposed-to-restricted-client', 'authorization_denied');

    // 第三阶段：MCP 只能提案，独立 owner 审阅冻结内容后确认，同一调用只提交一次。
    const title = '互操作样例 🧪';
    const markdown = '1. approved sample body\n2. 第二项\n\n```kotlin\nval sample = 1\n```';
    const pending = (await call(client, 'create_note', { title, markdown })).structuredContent;
    check(pending.state === 'pending' && pending.confirmationLevel === 'SINGLE' && pending.preview.details == null, 'proposal-without-sensitive-preview');
    check((await api('/v1/notes')).result.payload.items.length === 2, 'unapproved-write-not-visible');
    const approvals = await api('/v1/approvals');
    const preview = approvals.items.find(item => item.callId === pending.callId).preview.details;
    check(preview.after.title === title, 'owner-reviews-frozen-note');
    await api(`/v1/approvals/${pending.callId}`, 'PUT', { firstConfirmed: true }, identity.token, 403);
    check(true, 'agent-cannot-approve');
    const approved = await api(`/v1/approvals/${pending.callId}`, 'PUT', { firstConfirmed: true });
    const created = (await call(client, 'tagloom_get_call_result', { callId: pending.callId })).structuredContent;
    check(created.state === 'completed' && created.result.succeeded && JSON.stringify(created) === JSON.stringify(approved), 'approved-result-cross-client');
    const note = created.result.payload;
    await checkpoint('write', { noteId: note.noteId, callId: pending.callId, title });
    check(JSON.stringify(await api(`/v1/approvals/${pending.callId}`, 'PUT', { firstConfirmed: true })) === JSON.stringify(approved), 'approval-retry-idempotent');
    const updatedTitle = '互操作样例 updated 🧪';
    const update = (await call(client, 'update_note', { noteId: note.noteId, expectedRevision: note.revision, title: updatedTitle })).structuredContent;
    const updated = (await api(`/v1/approvals/${update.callId}`, 'PUT', { firstConfirmed: true })).result.payload;
    check(updated.note.title === updatedTitle && updated.revision !== note.revision, 'approved-update-revision');
    await checkpoint('write', { noteId: note.noteId, callId: update.callId, title: updatedTitle });
    await toolFailure(client, 'update_note', { noteId: note.noteId, expectedRevision: note.revision, title: 'Stale sample' }, 'revision_conflict');

    // 第四阶段：导出需二次确认；取消和删除的结果分别核对，重开证明删除已持久化。
    const exported = (await call(client, 'export_note', { noteId: note.noteId, expectedRevision: updated.revision, mode: 'standard' })).structuredContent;
    check(exported.state === 'pending' && exported.confirmationLevel === 'SECOND', 'export-requires-second-confirmation');
    await api(`/v1/approvals/${exported.callId}`, 'PUT', { firstConfirmed: true }, undefined, 409);
    const exportResult = await api(`/v1/approvals/${exported.callId}`, 'PUT', { firstConfirmed: true, secondConfirmed: true });
    check(exportResult.result.payload.markdown.includes('1. approved sample body') && exportResult.result.payload.markdown.includes('```kotlin'), 'export-preserves-list-and-code');
    const canceled = (await call(client, 'delete_note', { noteId: note.noteId, expectedRevision: updated.revision })).structuredContent;
    check(canceled.confirmationLevel === 'SECOND', 'delete-requires-second-confirmation');
    const canceledResult = (await call(client, 'tagloom_cancel_call', { callId: canceled.callId })).structuredContent;
    check(canceledResult.state === 'canceled' && (await call(client, 'get_note', { noteId: note.noteId })).isError === false, 'cancel-keeps-confirmed-note');
    check((await call(client, 'tagloom_cancel_call', { callId: canceled.callId })).structuredContent.state === 'canceled', 'cancel-retry-idempotent');
    await toolFailure(client, 'tagloom_get_call_result', { callId: canceled.callId, extra: true }, 'invalid_arguments');
    const deletion = (await call(client, 'delete_note', { noteId: note.noteId, expectedRevision: updated.revision })).structuredContent;
    await api(`/v1/approvals/${deletion.callId}`, 'PUT', { firstConfirmed: true }, undefined, 409);
    const deleted = await api(`/v1/approvals/${deletion.callId}`, 'PUT', { firstConfirmed: true, secondConfirmed: true });
    check(deleted.result.succeeded && (await call(client, 'tagloom_get_call_result', { callId: deletion.callId })).structuredContent.result.succeeded, 'approved-delete-result');
    await toolFailure(client, 'get_note', { noteId: note.noteId }, 'not_found');
    await checkpoint('deleted', { noteId: note.noteId, callId: deletion.callId });
}

/**
 * 使用官方 Inspector 的独立客户端核对 JSON 输出和 schema 可移植性，不写用户 catalog。
 * @param {string} token 本轮随机 Agent 凭据，仅通过环境传给子进程。
 * @returns {Promise<void>} Inspector 能发现真实分发工具后结束。
 */
async function inspectorDiscovery(token) {
    const inspector = spawn(process.execPath, [fileURLToPath(new URL('./inspector.mjs', import.meta.url))], {
        env: { ...process.env, TAGLOOM_API_TOKEN: token, MCP_INSPECTOR_SECRET_STORE: 'memory' },
        stdio: ['ignore', 'pipe', 'pipe'],
    });
    let output = '';
    inspector.stdout.on('data', chunk => { output += chunk; });
    inspector.stderr.resume();
    const timeout = setTimeout(() => inspector.kill(), 25000);
    try {
        const code = await new Promise((resolve, reject) => { inspector.once('close', resolve); inspector.once('error', reject); });
        check(code === 0, `inspector-strict-exit-${code}`);
        const report = JSON.parse(output);
        check(report.result.tools.length === 21, 'inspector-tools-json');
        check(!report.schemaFindings?.some(tool => tool.findings.some(finding => finding.severity === 'error')), 'inspector-schema-portability');
    } finally { clearTimeout(timeout); }
}
