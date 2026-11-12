package com.oruke.tagloom.workspace

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.oruke.tagloom.App
import com.oruke.tagloom.platform.WorkspaceStorageMode
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * 从生产 App 根节点验证本轮 Windows 工作区的实际 NAS 设置、同步按钮及已处理历史。
 *
 * Hot Reload 的常规入口没有独立登记目录，本入口使用官方 Compose UI API 注入本轮正式能力，
 * 防止切换或解锁用户实际工作区；HTTPS、系统代理和生产业务入口均保持原有实现。
 * @param device 已绑定本轮隔离登记目录的正式设备能力。
 * @param expectedServerUrl 已部署并受系统信任的真实 NAS HTTPS 地址。
 * @param runId 不含业务信息的本轮随机身份。
 * @param sharedNoteId Windows 与 Android 共用的随机笔记身份。
 * @param screenshot 保存设置及同步历史最终帧的位置，不包含恢复材料。
 */
@OptIn(ExperimentalTestApi::class)
internal fun verifyNasSyncDesktopUi(
    device: NasAcceptanceDesktopDevice,
    expectedServerUrl: String,
    runId: String,
    sharedNoteId: String,
    screenshot: Path,
) {
    val sharedTitle = "NAS sync acceptance $runId"
    val history = runBlocking {
        val conflict = device.gateway.serverRecovery.conflicts().single { it.objectId.value == sharedNoteId }
        check(conflict.resolved && conflict.fields.all { it.resolved }) { "ui_history_not_resolved" }
        device.gateway.lock()
        conflict
    }
    val previousLocale = Locale.getDefault()
    Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
    try {
        runDesktopComposeUiTest(width = 1280, height = 1000) {
            setContent { App(capabilities = device.capabilities) }
            // 由 App 自行从本轮 DPAPI 登记重开，不能预先提供伪造的页面状态。
            waitUntil(timeoutMillis = 30_000) {
                onAllNodesWithText(sharedTitle).fetchSemanticsNodes().isNotEmpty() &&
                    onAllNodes(hasText("设置") and hasClickAction() and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            onAllNodes(hasText(sharedTitle) and hasClickAction())[0].performClick()
            onNodeWithTag("note-title-input").assertTextContains(sharedTitle)
            waitUntil(timeoutMillis = 20_000) {
                onAllNodesWithText("nas-android-conflict", substring = true).fetchSemanticsNodes().isNotEmpty() &&
                    onAllNodesWithText("nas-android-offline-second", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            saveNasDesktopFrame(onRoot().captureToImage(), screenshot.resolveSibling("windows-ui-note.png"))

            onNode(hasText("设置") and hasClickAction()).performClick()
            onNodeWithText("管理工作区模式").performScrollTo().performClick()
            waitUntil(timeoutMillis = 30_000) {
                onAllNodes(hasTestTag("server-recovery-address")).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithTag("server-recovery-address").assertTextContains(expectedServerUrl.trim().trimEnd('/'))
            onNodeWithTag("server-recovery-publish").performScrollTo()
            waitUntil(timeoutMillis = 30_000) {
                onAllNodes(hasTestTag("server-recovery-publish") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithTag("server-recovery-publish").assertIsEnabled().performClick()
            waitUntil(timeoutMillis = 60_000) {
                onAllNodesWithText("同步完成，可以在其他设备上继续使用。").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithTag("sync-conflicts-toggle").performScrollTo().assertIsEnabled().performClick()
            waitUntil(timeoutMillis = 30_000) {
                onAllNodesWithText("已处理 · 历史已保留", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithTag("sync-conflict-toggle-${history.id}").performScrollTo().assertIsDisplayed()
            saveNasDesktopFrame(onRoot().captureToImage(), screenshot)
            runBlocking {
                val status = requireNotNull(device.gateway.serverRecovery.status())
                check(status.mode == WorkspaceStorageMode.SERVER && !status.pendingChanges && status.conflictsCount == 0) {
                    "ui_sync_status_incorrect"
                }
                val current = device.gateway.reload().notes.single { it.id.value == sharedNoteId }
                check(current.note.title == sharedTitle) { "ui_shared_note_missing" }
                check(device.gateway.serverRecovery.conflicts().single { it.id == history.id }.resolved) { "ui_history_status_changed" }
            }
        }
    } finally {
        Locale.setDefault(previousLocale)
    }
}

/**
 * 保存官方 UI API 捕获的生产 App 图像，仅包含本轮脱敏笔记和同步状态。
 * @param bitmap 已渲染的完整 Compose 内容。
 * @param target 本轮明确的截图位置。
 */
private fun saveNasDesktopFrame(bitmap: ImageBitmap, target: Path) {
    Files.createDirectories(requireNotNull(target.parent))
    val encoded = requireNotNull(Image.makeFromBitmap(bitmap.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG))
    Files.write(target, encoded.bytes)
}
