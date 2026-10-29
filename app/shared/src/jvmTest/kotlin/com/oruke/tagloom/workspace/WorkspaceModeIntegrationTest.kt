package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.platform.WorkspaceStorageMode
import com.oruke.tagloom.sync.http.ServerRecoveryException
import com.oruke.tagloom.ui.WorkspaceUiController
import com.oruke.tagloom.ui.WorkspaceUiPhase
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** 使用真实加密文件、设备保护及 TCP 服务验证模式切换、新建、恢复和断网行为。 */
class WorkspaceModeIntegrationTest {
    /** 切到本地后重启仍不上传，切回服务器保留同一工作区、密钥和笔记身份。 */
    @Test
    fun switchesPersistentlyWithoutUploadingInLocalMode() = ServerRecoveryTestEnvironment().use { env -> runBlocking {
        val source = env.device("mode-source")
        val key = requireNotNull(source.gateway.prepareCreation(null)).recoveryKeyText
        source.gateway.confirmCreation()
        assertEquals(1, source.creationSelections)
        val id = source.manager.sessionOrNull()!!.workspaceId
        val note = source.gateway.saveNote(null, Note(title = "初始模式样例", directTagIds = emptyList()))
        assertEquals(WorkspaceStorageMode.SERVER, source.gateway.serverRecovery.publish(env.address, env.bootstrap, key).mode)
        assertEquals(WorkspaceStorageMode.LOCAL, source.gateway.serverRecovery.useLocalMode()?.mode)
        val changed = source.gateway.saveNote(note.id, note.note.copy(title = "本地修改样例"), note.revision)
        source.gateway.lock()
        source.gateway.openRecentWorkspace()
        assertEquals(WorkspaceStorageMode.LOCAL, source.gateway.serverRecovery.status()?.mode)
        assertTrue(source.gateway.serverRecovery.updateIfChanged()?.pendingChanges == true)
        val untouched = env.device("mode-before").gateway.serverRecovery.restore(env.address, key, null)!!
        assertEquals(note, untouched.notes.single())

        source.gateway.serverRecovery.publish(env.address, "", "")
        val unchangedTime = source.gateway.serverRecovery.status()?.uploadedAt
        assertFalse(source.gateway.serverRecovery.updateIfChanged()!!.pendingChanges)
        assertEquals(unchangedTime, source.gateway.serverRecovery.status()?.uploadedAt)
        assertEquals(id, source.manager.sessionOrNull()!!.workspaceId)
        val restored = env.device("mode-after")
        assertEquals(changed, restored.gateway.serverRecovery.restore(env.address, key, null)?.notes?.single())
        assertEquals(WorkspaceStorageMode.SERVER, restored.gateway.serverRecovery.status()?.mode)
        assertEquals(0, restored.creationSelections)
        assertEquals(0, restored.recoverySelections)
    } }

    /** 服务器新建复用密钥确认入口；没有文件夹选择，创建完成即可凭密钥在另一台设备恢复。 */
    @Test
    fun createsServerWorkspaceThroughConfirmedClientFlow() = ServerRecoveryTestEnvironment().use { env -> runBlocking {
        val source = env.device("server-create")
        val controller = WorkspaceUiController(source.gateway, source.capabilities.searchEngine, TagloomEditorState(source.gateway::createOpaqueId, source.gateway::currentUtcTimestamp))
        controller.serverRecovery.prepareCreation(env.address, env.bootstrap, null)
        assertEquals(WorkspaceUiPhase.RECOVERY_CONFIRMATION, controller.phase)
        assertNull(source.gateway.recentWorkspaceOrNull())
        val key = requireNotNull(controller.preparedRecoveryKey)
        controller.confirmCreation()
        assertEquals(WorkspaceUiPhase.OPEN, controller.phase)
        assertNull(controller.preparedRecoveryKey)
        assertNull(controller.serverRecovery.errorCode)
        assertEquals(WorkspaceStorageMode.SERVER, source.gateway.serverRecovery.status()?.mode)
        assertEquals(0, source.creationSelections)
        val target = env.device("server-created-restore")
        target.gateway.serverRecovery.restore(env.address, key, null)
        assertEquals(source.manager.sessionOrNull()!!.workspaceId, target.manager.sessionOrNull()!!.workspaceId)
    } }

    /** 本地恢复要求真实目录和正确密钥；新设备无原本机凭据也能恢复笔记并保持本地模式。 */
    @Test
    fun restoresLocalFolderWithRecoveryKey() = ServerRecoveryTestEnvironment().use { env -> runBlocking {
        val original = env.device("folder-original")
        val key = requireNotNull(original.gateway.prepareCreation(null)).recoveryKeyText
        original.gateway.confirmCreation()
        val id = original.manager.sessionOrNull()!!.workspaceId
        val note = original.gateway.saveNote(null, Note(title = "本地文件夹恢复样例", directTagIds = emptyList()))
        original.gateway.lock()
        val target = env.device("folder-restored").also { it.recoveryRoot = original.workspaceRoot }
        assertFailsWith<Exception> { target.gateway.unlockWithRecovery("invalid-recovery-key", null) }
        assertNull(target.gateway.recentWorkspaceOrNull())
        assertEquals(note, target.gateway.unlockWithRecovery(key, null)?.notes?.single())
        assertEquals(id, target.manager.sessionOrNull()!!.workspaceId)
        assertNull(target.gateway.serverRecovery.status())
        assertEquals(2, target.recoverySelections)
        target.gateway.lock()
        assertEquals(note, target.gateway.openRecentWorkspace().notes.single())
    } }

    /** 真实断网后本地笔记继续保存，重连合并远端变更，并保留并发编辑的两个版本。 */
    @Test
    fun retriesAfterDisconnectAndPreservesConflictingCopies() = ServerRecoveryTestEnvironment().use { env -> runBlocking {
        val source = env.device("network-source")
        val key = requireNotNull(source.gateway.prepareCreation(null)).recoveryKeyText
        source.gateway.confirmCreation()
        val note = source.gateway.saveNote(null, Note(title = "连接样例", directTagIds = emptyList()))
        source.gateway.serverRecovery.publish(env.address, env.bootstrap, key)
        env.disconnect()
        val offline = source.gateway.saveNote(note.id, note.note.copy(title = "断网编辑样例"), note.revision)
        assertFailsWith<Exception> { source.gateway.serverRecovery.updateIfChanged() }
        source.gateway.lock()
        assertEquals(offline, source.gateway.openRecentWorkspace().notes.single())
        env.reconnect()
        assertFalse(source.gateway.serverRecovery.updateIfChanged()!!.pendingChanges)
        val other = env.device("network-other")
        assertEquals(offline, other.gateway.serverRecovery.restore(env.address, key, null)?.notes?.single())
        val remote = other.gateway.saveNote(note.id, offline.note.copy(title = "另一设备确认样例"), offline.revision)
        other.gateway.serverRecovery.updateIfChanged()
        val local = source.gateway.saveNote(note.id, offline.note.copy(title = "原设备离线样例"), offline.revision)
        assertFalse(source.gateway.serverRecovery.updateIfChanged()!!.pendingChanges)
        val conflicts = source.gateway.serverRecovery.conflicts().filter { it.objectId == note.id && !it.resolved }
        assertTrue(conflicts.isNotEmpty())
        assertTrue(conflicts.flatMap { listOf(it.localTitle, it.remoteTitle) }.containsAll(listOf(local.note.title, remote.note.title)))
        val merged = requireNotNull(source.gateway.reload()).notes.single().note
        other.gateway.serverRecovery.updateIfChanged()
        assertEquals(merged, requireNotNull(other.gateway.reload()).notes.single().note)
        assertEquals(merged, env.device("network-final").gateway.serverRecovery.restore(env.address, key, null)?.notes?.single()?.note)
    } }

    /** 首次登记失败不撤销已确认本地创建，用户可继续本地使用并在设置中重试。 */
    @Test
    fun failedServerCreationKeepsRecoverableLocalWorkspace() = ServerRecoveryTestEnvironment().use { env -> runBlocking {
        val source = env.device("registration-retry")
        val controller = WorkspaceUiController(source.gateway, source.capabilities.searchEngine, TagloomEditorState(source.gateway::createOpaqueId, source.gateway::currentUtcTimestamp))
        controller.serverRecovery.prepareCreation(env.address, "invalid-registration-code", null)
        val key = requireNotNull(controller.preparedRecoveryKey)
        controller.confirmCreation()
        assertEquals(WorkspaceUiPhase.OPEN, controller.phase)
        assertEquals("authentication_failed", controller.serverRecovery.errorCode)
        assertNotNull(source.gateway.recentWorkspaceOrNull())
        assertEquals(WorkspaceStorageMode.LOCAL, source.gateway.serverRecovery.status()?.mode)
        source.gateway.lock()
        source.gateway.openRecentWorkspace()
        assertEquals(WorkspaceStorageMode.SERVER, source.gateway.serverRecovery.publish(env.address, env.bootstrap, key).mode)
    } }

    /** 无旧设备配置的文件夹恢复可以凭密钥重新加入已有服务器；内容分歧时必须保留两端。 */
    @Test
    fun reconnectsLocalFolderToExistingServerWithoutBootstrap() = ServerRecoveryTestEnvironment().use { env -> runBlocking {
        val original = env.device("rejoin-original")
        val key = requireNotNull(original.gateway.prepareCreation(null)).recoveryKeyText
        original.gateway.confirmCreation()
        val saved = original.gateway.saveNote(null, Note(title = "重新连接样例", directTagIds = emptyList()))
        original.gateway.serverRecovery.publish(env.address, env.bootstrap, key)
        original.gateway.lock()

        val folderDevice = env.device("rejoin-folder").also { it.recoveryRoot = original.workspaceRoot }
        assertEquals(saved, folderDevice.gateway.unlockWithRecovery(key, null)?.notes?.single())
        val joined = folderDevice.gateway.serverRecovery.publish(env.address, "", key)
        assertEquals(WorkspaceStorageMode.SERVER, joined.mode)
        folderDevice.gateway.lock()

        // 文件夹副本与服务器分别离线修改；首次接入必须先认证并合并，不能把最新版本号当作覆盖许可。
        val diverged = env.device("rejoin-diverged").also { it.recoveryRoot = original.workspaceRoot }
        diverged.gateway.unlockWithRecovery(key, null)
        val local = diverged.gateway.saveNote(saved.id, saved.note.copy(title = "尚未核对的本地修改"), saved.revision)
        val remoteDevice = env.device("rejoin-remote")
        val remoteBase = requireNotNull(remoteDevice.gateway.serverRecovery.restore(env.address, key, null)).notes.single()
        val remote = remoteDevice.gateway.saveNote(saved.id, remoteBase.note.copy(title = "服务器独立修改样例"), remoteBase.revision)
        remoteDevice.gateway.serverRecovery.updateIfChanged()

        // 格式错误和属于另一工作区的有效恢复密钥均不能改变当前副本或加入服务器。
        assertFailsWith<Exception> { diverged.gateway.serverRecovery.publish(env.address, "", "invalid-recovery-key") }
        val unrelated = env.device("rejoin-unrelated")
        val unrelatedKey = requireNotNull(unrelated.gateway.prepareCreation(null)).recoveryKeyText
        unrelated.gateway.confirmCreation()
        assertFailsWith<Exception> { diverged.gateway.serverRecovery.publish(env.address, "", unrelatedKey) }
        assertEquals(WorkspaceStorageMode.LOCAL, diverged.gateway.serverRecovery.status()?.mode ?: WorkspaceStorageMode.LOCAL)
        assertEquals(local.note, requireNotNull(diverged.gateway.reload()).notes.single().note)

        val connected = diverged.gateway.serverRecovery.publish(env.address, "", key)
        assertEquals(WorkspaceStorageMode.SERVER, connected.mode)
        assertFalse(connected.pendingChanges)
        val conflicts = diverged.gateway.serverRecovery.conflicts().filter { it.objectId == saved.id && !it.resolved }
        assertTrue(conflicts.isNotEmpty())
        assertTrue(conflicts.flatMap { listOf(it.localTitle, it.remoteTitle) }.containsAll(listOf(local.note.title, remote.note.title)))
        val merged = requireNotNull(diverged.gateway.reload()).notes.single().note
        remoteDevice.gateway.serverRecovery.updateIfChanged()
        assertEquals(merged, requireNotNull(remoteDevice.gateway.reload()).notes.single().note)
        diverged.gateway.lock()
        remoteDevice.gateway.lock()
        assertEquals(merged, diverged.gateway.openRecentWorkspace().notes.single().note)
        assertEquals(merged, remoteDevice.gateway.openRecentWorkspace().notes.single().note)
        assertTrue(diverged.gateway.serverRecovery.conflicts().flatMap { listOf(it.localTitle, it.remoteTitle) }
            .containsAll(listOf(local.note.title, remote.note.title)))
        val verification = env.device("rejoin-verify")
        assertEquals(merged, verification.gateway.serverRecovery.restore(env.address, key, null)?.notes?.single()?.note)
        assertTrue(verification.gateway.serverRecovery.conflicts().flatMap { listOf(it.localTitle, it.remoteTitle) }
            .containsAll(listOf(local.note.title, remote.note.title)))
    } }
}
