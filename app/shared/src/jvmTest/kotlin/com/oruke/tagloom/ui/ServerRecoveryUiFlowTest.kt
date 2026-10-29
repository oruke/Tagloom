package com.oruke.tagloom.ui

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.oruke.tagloom.App
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.workspace.ServerRecoveryTestEnvironment
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** 从正式 App 根节点验证设置上传与新设备欢迎页恢复，传输与持久化均使用真实依赖。 */
@OptIn(ExperimentalTestApi::class)
class ServerRecoveryUiFlowTest {
    /** 桌面设置上传后，在窄窗口的新设备输入恢复密钥并找回相同笔记。 */
    @Test
    fun publishesAndRestoresThroughProductionUi() {
        val previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        try { ServerRecoveryTestEnvironment().use { env ->
            val source = env.device("ui-source")
            val target = env.device("ui-target")
            val recoveryKey = runBlocking {
                val key = requireNotNull(source.gateway.prepareCreation(null)).recoveryKeyText
                source.gateway.confirmCreation()
                source.gateway.saveNote(null, Note(title = "服务器恢复界面样例", directTagIds = emptyList()))
                source.gateway.lock()
                key
            }
            runDesktopComposeUiTest(width = 1280, height = 820) {
                setContent { App(capabilities = source.capabilities) }
                waitForRecoveryText("设置")
                onNode(hasText("设置") and hasClickAction()).performClick()
                onNodeWithText("管理工作区模式").performScrollTo().performClick()
                waitForRecoveryText("本地模式")
                onNodeWithTag("workspace-mode-server").performClick()
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("server-recovery-address").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("server-recovery-address").performTextReplacement(env.address)
                onNodeWithTag("server-recovery-bootstrap").performTextReplacement(env.bootstrap)
                onNodeWithTag("server-recovery-key").performTextReplacement(recoveryKey)
                onNodeWithTag("server-recovery-publish").performScrollTo().performClick()
                waitForRecoveryText("同步完成，可以在其他设备上继续使用。")
                onNodeWithTag("server-recovery-key").assertDoesNotExist()
                onNodeWithText("已解锁 · 服务器模式").assertIsDisplayed()
                recoveryScreenshot("server-recovery-settings-desktop.png")
                val firstUpload = source.gateway.serverRecovery.status()?.uploadedAt
                assertNotNull(firstUpload)
                onNodeWithText("返回设置").performScrollTo().performClick()
                onNodeWithText("返回笔记").performClick()
                onNode(hasSetTextAction() and hasText("服务器恢复界面样例")).performTextReplacement("自动上传界面样例")
                waitUntil(timeoutMillis = 30_000) {
                    runBlocking { source.gateway.serverRecovery.status()?.let { !it.pendingChanges && it.uploadedAt != firstUpload } == true }
                }
            }
            runDesktopComposeUiTest(width = 440, height = 820) {
                setContent { App(capabilities = target.capabilities) }
                waitForRecoveryText("从本地文件夹恢复")
                recoveryScreenshot("server-recovery-welcome-compact.png")
                onNodeWithTag("workspace-mode-server").performClick()
                onNodeWithTag("workspace-restore").performClick()
                onNodeWithTag("server-recovery-address").performTextReplacement(env.address)
                onNodeWithTag("server-recovery-key").performTextReplacement(recoveryKey)
                recoveryScreenshot("server-recovery-form-compact.png")
                onNodeWithTag("workspace-access-submit").performScrollTo().performClick()
                waitForRecoveryText("自动上传界面样例")
                assertEquals(source.manager.sessionOrNull()?.workspaceId, target.manager.sessionOrNull()?.workspaceId)
                assertNotNull(target.gateway.recentWorkspaceOrNull())
                assertEquals(0, target.creationSelections)
            }
        } } finally { Locale.setDefault(previousLocale) }
    }

    /**
     * 等待真实网络和文件操作完成，失败时只保存已掩码的界面。
     * @param value 应出现的最终状态文案。
     */
    private fun ComposeUiTest.waitForRecoveryText(value: String) {
        try { waitUntil(timeoutMillis = 30_000) { onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() } }
        catch (error: Throwable) { recoveryScreenshot("server-recovery-failure.png"); throw error }
    }

    /**
     * 将确定尺寸的实际 Compose 渲染保存到忽略的构建目录供人工检查。
     * @param filename 不含测试秘密的固定文件名。
     */
    private fun ComposeUiTest.recoveryScreenshot(filename: String) {
        val directory = Path.of("build", "reports", "server-recovery-ui")
        Files.createDirectories(directory)
        Image.makeFromBitmap(onAllNodes(isRoot())[0].captureToImage().asSkiaBitmap()).use { image ->
            image.encodeToData(EncodedImageFormat.PNG)!!.use { Files.write(directory.resolve(filename), it.bytes) }
        }
    }
}
