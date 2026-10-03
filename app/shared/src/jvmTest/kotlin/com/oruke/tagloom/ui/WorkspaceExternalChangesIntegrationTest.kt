package com.oruke.tagloom.ui

import com.oruke.tagloom.core.agent.*
import com.oruke.tagloom.core.external.*
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.platform.WorkspaceGateway
import com.oruke.tagloom.platform.WorkspaceOpenResult
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.UUID
import kotlin.test.*

/** 以真实桌面网关、加密快照和生产 UI 控制器验证外部刷新与草稿冲突保护。 */
class WorkspaceExternalChangesIntegrationTest {
    /** 旧会话刷新在锁定后延迟返回时，不能覆盖重新解锁并已保存的新会话文档。 */
    @Test
    fun discardsSnapshotReturnedAfterLockAndReopen() = runBlocking<Unit> {
        WorkspaceSettingsTestEnvironment().use { environment ->
            val reloadReady = CompletableDeferred<Unit>()
            val resumeReload = CompletableDeferred<Unit>()
            val gateway = PausedWorkspaceReloadGateway(environment.gateway, reloadReady, resumeReload)
            val editor = TagloomEditorState({ UUID.randomUUID().toString() }, environment.gateway::currentUtcTimestamp)
            val controller = WorkspaceUiController(gateway, environment.capabilities.searchEngine, editor)
            controller.initialize()
            val principal = AgentPrincipal("reload-test-principal", AgentPermissionScope.entries.toSet())
            val created = confirmExternalCall(requireNotNull(environment.gateway.externalSessionOrNull()), principal,
                "create_note", buildJsonObject { put("title", "Previous session sample") })
            controller.lock()
            controller.openRecentWorkspace()
            val observer = launch(start = CoroutineStart.UNDISPATCHED) { controller.observeExternalChanges() }
            try {
                // 阶段 1：真实外部事务已确认，但它的 UI 重新加载故意暂停在返回之前。
                confirmExternalCall(requireNotNull(environment.gateway.externalSessionOrNull()), principal,
                    "update_note", buildJsonObject {
                        put("noteId", created["noteId"]!!); put("expectedRevision", created["revision"]!!)
                        put("title", "Delayed external sample")
                    })
                withTimeout(5_000) { reloadReady.await() }
                // 阶段 2：关闭原会话并通过生产入口重新解锁，保存一个更新的桌面版本。
                controller.lock()
                assertTrue(editor.documents.isEmpty())
                controller.openRecentWorkspace()
                editor.selectDocument(editor.documents.single().id)
                editor.updateTitle("Reopened desktop sample")
                controller.saveSelected()
                assertNull(controller.errorMessage)
                val savedRevision = editor.selectedDocument!!.revision
                // 阶段 3：放行旧读取；同一 UI 调度器下一次让出后，它必须已被丢弃。
                resumeReload.complete(Unit)
                yield()
                assertEquals("Reopened desktop sample", editor.selectedDocument!!.note.title)
                assertEquals(savedRevision, editor.selectedDocument!!.revision)
                assertFalse(editor.selectedDocument!!.dirty)
                assertEquals("Reopened desktop sample", environment.gateway.reload().notes.single().note.title)
            } finally { resumeReload.complete(Unit); observer.cancelAndJoin() }
        }
    }

    /** 外部结果刷新干净文档，已有脏草稿保持旧 revision，保存不能覆盖外部确认版本。 */
    @Test
    fun preservesDirtyDraftAndRejectsItsStaleRevision() = runBlocking<Unit> {
        WorkspaceSettingsTestEnvironment().use { environment ->
            val editor = TagloomEditorState({ UUID.randomUUID().toString() }, environment.gateway::currentUtcTimestamp)
            val controller = WorkspaceUiController(environment.gateway, environment.capabilities.searchEngine, editor)
            controller.initialize()
            val observer = launch(start = CoroutineStart.UNDISPATCHED) { controller.observeExternalChanges() }
            try {
                val service = requireNotNull(environment.gateway.externalSessionOrNull())
                val principal = AgentPrincipal("external-test-principal", AgentPermissionScope.entries.toSet())
                val created = confirmExternalCall(service, principal, "create_note", buildJsonObject { put("title", "Original sample") })
                withTimeout(5_000) { while (editor.documents.size != 1) yield() }
                editor.selectDocument(editor.documents.single().id)
                val id = editor.selectedDocument!!.id
                val originalRevision = editor.selectedDocument!!.revision
                editor.updateTitle("Unsaved desktop sample")
                confirmExternalCall(service, principal, "update_note", buildJsonObject {
                    put("noteId", created["noteId"]!!); put("expectedRevision", created["revision"]!!); put("title", "Confirmed external sample")
                })
                confirmExternalCall(service, principal, "create_note", buildJsonObject { put("title", "Another external sample") })
                withTimeout(5_000) { while (editor.documents.size != 2) yield() }
                assertEquals(id, editor.selectedDocumentId)
                assertEquals("Unsaved desktop sample", editor.selectedDocument!!.note.title)
                assertEquals(originalRevision, editor.selectedDocument!!.revision)
                assertTrue(editor.selectedDocument!!.dirty)
                controller.saveSelected()
                assertEquals(WorkspaceUiMessage.NoteSaveFailed, controller.errorMessage)
                assertTrue(editor.selectedDocument!!.dirty)
                assertEquals("Confirmed external sample", environment.gateway.reload().notes.single { it.id == id }.note.title)
            } finally { observer.cancelAndJoin() }
        }
    }
}

/** 仅暂停一次真实快照读取的返回，保留生产文件、权限和事务实现以确定地验证会话竞态。 */
private class PausedWorkspaceReloadGateway(
    /** 提供完整真实工作区行为的生产网关。 */
    private val delegate: WorkspaceGateway,
    /** 通知测试：真实快照已读取，可以执行锁定。 */
    private val reloadReady: CompletableDeferred<Unit>,
    /** 测试放行后才能把原快照返回 UI。 */
    private val resumeReload: CompletableDeferred<Unit>,
) : WorkspaceGateway by delegate {
    /** @return 通过实际文件认证读取的快照；第一次调用等待生命周期操作完成后返回。 */
    override suspend fun reload(): WorkspaceOpenResult? {
        val snapshot = delegate.reload()
        if (!reloadReady.isCompleted) {
            reloadReady.complete(Unit)
            resumeReload.await()
        }
        return snapshot
    }
}

/**
 * 从生产外部应用服务走完整预览和确认，实际事务及桌面通知均由同一网关执行。
 * @param service 当前已解锁外部会话。
 * @param principal 仅当前隔离测试使用的可信身份。
 * @param tool 稳定工具名。
 * @param arguments 严格工具参数。
 * @return 已确认业务 payload。
 */
private suspend fun confirmExternalCall(service: AgentWorkspaceSession, principal: AgentPrincipal, tool: String, arguments: JsonObject): JsonObject {
    val request = ExternalToolRequestFactory.create(ExternalToolNames.resolve(tool), arguments, UUID.randomUUID().toString())
    val prepared = service.prepare(principal, request)
    val result = service.execute(prepared, AgentConfirmationEvidence(request.requestId, true, true, "2026-01-01T00:00:00Z"))
    assertTrue(result.succeeded)
    return result.payload!!.jsonObject
}
