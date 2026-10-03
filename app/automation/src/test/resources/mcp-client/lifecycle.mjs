import { api, call, check, checkpoint, connect, grant, rpcFailure, seed, toolFailure } from './client-runtime.mjs';

/**
 * 在同一长驻 MCP 进程中验证撤销、锁定、跨解锁会话和宿主离线边界。
 * @returns {Promise<void>} 原进程全部失效而新会话可正常读取后结束。
 */
export async function lifecycle() {
    const note = await seed('Lifecycle sample', 'lifecycle sample body');
    const identity = await grant({ scopes: ['READ_NOTES', 'SEARCH_NOTES', 'WRITE_NOTES'] });
    const agent = await connect(identity.token);
    const owner = await connect(process.env.TAGLOOM_API_TOKEN);
    const cached = (await call(agent, 'get_note', { noteId: note.noteId })).structuredContent;
    const pending = (await call(agent, 'create_note', { title: 'Revoked sample' })).structuredContent;
    check(pending.state === 'pending', 'revocation-starts-with-pending-write');
    await api(`/v1/grants/${identity.principalId}`, 'DELETE', undefined, undefined, 204);
    await toolFailure(agent, 'get_note', { noteId: note.noteId }, 'unauthorized');
    await toolFailure(agent, 'tagloom_get_call_result', { callId: cached.callId }, 'unauthorized');
    await toolFailure(agent, 'tagloom_get_call_result', { callId: pending.callId }, 'unauthorized');
    await rpcFailure(() => agent.readResource({ uri: `note://${note.noteId}` }), -32002, 'revoked-resource-unavailable', 'unauthorized');
    await api(`/v1/approvals/${pending.callId}`, 'PUT', { firstConfirmed: true }, undefined, 404);
    await checkpoint('revoked', { callId: pending.callId });

    await checkpoint('lock');
    await toolFailure(owner, 'list_notes', {}, 'workspace_locked');
    await rpcFailure(() => owner.readResource({ uri: `note://${note.noteId}` }), -32002, 'locked-resource-unavailable', 'workspace_locked');
    await checkpoint('reopen');
    await toolFailure(owner, 'list_notes', {}, 'session_changed');
    await toolFailure(agent, 'list_notes', {}, 'session_changed');
    await rpcFailure(() => owner.readResource({ uri: `note://${note.noteId}` }), -32002, 'old-unlock-resource-unavailable', 'session_changed');
    await api('/v1/tools', 'GET', undefined, identity.token, 401);
    check(true, 'old-token-cannot-start-new-session');
    const fresh = await connect(process.env.TAGLOOM_API_TOKEN);
    await fresh.listTools();
    const visible = (await call(fresh, 'list_notes')).structuredContent.result.payload.items;
    check(visible.length === 1 && visible[0].noteId === note.noteId, 'new-session-reads-persisted-note');
    const freshIdentity = await grant();
    const freshAgent = await connect(freshIdentity.token);
    check((await call(freshAgent, 'get_note', { noteId: note.noteId })).isError === false, 'fresh-grant-reads-confirmed-note');

    await checkpoint('stop-api');
    await toolFailure(freshAgent, 'get_note', { noteId: note.noteId }, 'api_unavailable');
    await rpcFailure(() => fresh.readResource({ uri: `note://${note.noteId}` }), -32002, 'offline-resource-unavailable', 'api_unavailable');
    check((await fresh.ping()) !== undefined, 'protocol-remains-responsive-with-offline-host');
}
