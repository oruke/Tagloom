import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

/** 官方 Inspector CLI 的隔离安装入口，不启用 Web/TUI 或写 catalog。 */
const cli = join(process.env.TAGLOOM_TEST_MCP_CLIENT_HOME, 'node_modules', '@modelcontextprotocol', 'inspector', 'clients', 'cli', 'build', 'index.js');
// CLI 的 -e 仅在本进程内存中构造，真实 OS 命令行不出现 token；其 stdio 子进程继续通过环境接收。
process.argv = [process.execPath, cli, process.env.TAGLOOM_TEST_JAVA,
    '-cp', join(process.env.TAGLOOM_TEST_CLI_HOME, 'lib', '*'), 'com.oruke.tagloom.automation.MainKt', 'mcp', '--',
    '--method', 'tools/list', '--format', 'json', '--strict',
    '-e', `TAGLOOM_API_URL=${process.env.TAGLOOM_API_URL}`, '-e', `TAGLOOM_API_TOKEN=${process.env.TAGLOOM_API_TOKEN}`];
await import(pathToFileURL(cli).href);
