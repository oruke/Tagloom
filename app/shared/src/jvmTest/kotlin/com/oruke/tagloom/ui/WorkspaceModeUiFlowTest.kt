package com.oruke.tagloom.ui

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.oruke.tagloom.App
import com.oruke.tagloom.TagloomAppState
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.platform.WorkspaceStorageMode
import com.oruke.tagloom.workspace.ServerRecoveryTestEnvironment
import kotlinx.coroutines.runBlocking
import java.util.Locale
import kotlin.test.*

/** 从正式 App 根节点验证两种新建方式、模式切换和文件夹恢复，所有持久化及网络依赖均真实运行。 */
@OptIn(ExperimentalTestApi::class)
class WorkspaceModeUiFlowTest {
    /** 从服务器模式新建、保存密钥、切换本地后再切回，全程不选择目录或更换工作区。 */
    @Test
    fun createsServerWorkspaceAndSwitchesModesThroughSettings() = withModeEnvironment { env ->
        val source = env.device("ui-server-create")
        var state: TagloomAppState? = null
        var key: String? = null
        runDesktopComposeUiTest(width = 1280, height = 820) {
            setContent {
                val scope = rememberCoroutineScope()
                val appState = remember { TagloomAppState(source.capabilities, scope) }.also { state = it }
                App(capabilities = source.capabilities, appState = appState)
            }
            waitForModeText("全新创建")
            onNodeWithTag("workspace-mode-server").performClick()
            onNodeWithTag("workspace-create").performClick()
            onNodeWithTag("server-recovery-address").performTextReplacement(env.address)
            onNodeWithTag("server-recovery-bootstrap").performTextReplacement(env.bootstrap)
            onNodeWithTag("workspace-access-submit").performScrollTo().performClick()
            waitUntil(timeoutMillis = 30_000) { state?.controller?.phase == WorkspaceUiPhase.RECOVERY_CONFIRMATION }
            runOnIdle { key = state?.controller?.preparedRecoveryKey }
            onNode(isToggleable()).performScrollTo().performClick()
            onNodeWithText("确认并创建").performScrollTo().performClick()
            waitForModeText("设置")
            waitUntil(timeoutMillis = 30_000) { state?.controller?.busy == false }
            assertEquals(0, source.creationSelections)
            assertEquals(WorkspaceStorageMode.SERVER, source.gateway.serverRecovery.status()?.mode)
            val id = source.manager.sessionOrNull()!!.workspaceId
            onNode(hasText("设置") and hasClickAction()).performClick()
            onNodeWithText("管理工作区模式").performScrollTo().performClick()
            waitForModeText("服务器模式")
            onNodeWithTag("workspace-mode-local").performClick()
            waitUntil(timeoutMillis = 10_000) { runBlocking { source.gateway.serverRecovery.status()?.mode == WorkspaceStorageMode.LOCAL } }
            onNodeWithTag("server-recovery-address").assertDoesNotExist()
            onNodeWithTag("workspace-mode-server").performClick()
            onNodeWithTag("server-recovery-key").assertDoesNotExist()
            onNodeWithTag("server-recovery-bootstrap").assertDoesNotExist()
            onNodeWithTag("server-recovery-publish").performScrollTo().performClick()
            waitForModeText("恢复副本已上传，可以在新设备上恢复。")
            assertEquals(id, source.manager.sessionOrNull()!!.workspaceId)
            assertEquals(WorkspaceStorageMode.SERVER, source.gateway.serverRecovery.status()?.mode)
        }
        runBlocking {
            val target = env.device("ui-server-created-restore")
            target.gateway.serverRecovery.restore(env.address, requireNotNull(key), null)
            assertEquals(source.manager.sessionOrNull()!!.workspaceId, target.manager.sessionOrNull()!!.workspaceId)
        }
    }

    /** 本地模式从选择文件夹新建，并在没有本机登记的新设备上用同一文件夹和密钥恢复。 */
    @Test
    fun createsAndRestoresLocalFolderThroughWelcome() = withModeEnvironment { env ->
        val source = env.device("ui-local-create")
        var state: TagloomAppState? = null
        var key: String? = null
        runDesktopComposeUiTest(width = 440, height = 820) {
            setContent {
                val scope = rememberCoroutineScope()
                val appState = remember { TagloomAppState(source.capabilities, scope) }.also { state = it }
                App(capabilities = source.capabilities, appState = appState)
            }
            waitForModeText("全新创建")
            onNodeWithTag("workspace-mode-local").assertIsSelected()
            onNodeWithTag("workspace-create").performClick()
            onNodeWithTag("server-recovery-address").assertDoesNotExist()
            onNodeWithTag("workspace-access-submit").performScrollTo().performClick()
            waitUntil(timeoutMillis = 30_000) { state?.controller?.phase == WorkspaceUiPhase.RECOVERY_CONFIRMATION }
            runOnIdle { key = state?.controller?.preparedRecoveryKey }
            onNode(isToggleable()).performScrollTo().performClick()
            onNodeWithText("确认并创建").performScrollTo().performClick()
            waitForModeText("设置")
            assertEquals(1, source.creationSelections)
            assertNull(source.gateway.serverRecovery.status())
        }
        val saved = runBlocking {
            source.gateway.saveNote(null, Note(title = "文件夹界面恢复样例", directTagIds = emptyList())).also { source.gateway.lock() }
        }
        val target = env.device("ui-local-restore").also { it.recoveryRoot = source.workspaceRoot }
        runDesktopComposeUiTest(width = 440, height = 820) {
            setContent { App(capabilities = target.capabilities) }
            waitForModeText("从本地文件夹恢复")
            onNodeWithTag("workspace-restore").performClick()
            onNodeWithTag("server-recovery-address").assertDoesNotExist()
            onNodeWithTag("server-recovery-key").performTextReplacement(requireNotNull(key))
            onNodeWithTag("workspace-access-submit").performScrollTo().performClick()
            waitForModeText("文件夹界面恢复样例")
            assertEquals(1, target.recoverySelections)
            assertEquals(saved.id, target.manager.sessionOrNull()!!.loadActiveNotes().single().snapshot.header.objectId)
        }
    }

    /**
     * 在真实异步认证、磁盘及网络操作后等待最终 UI 状态。
     * @param text 当前步骤的可见完成文案。
     */
    private fun ComposeUiTest.waitForModeText(text: String) {
        waitUntil(timeoutMillis = 30_000) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    /**
     * 固定资源语言并在退出时恢复，测试内容及数据目录完全隔离。
     * @param block 需要完整真实环境的界面业务流程。
     */
    private fun withModeEnvironment(block: (ServerRecoveryTestEnvironment) -> Unit) {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        try { ServerRecoveryTestEnvironment().use(block) } finally { Locale.setDefault(previous) }
    }
}
