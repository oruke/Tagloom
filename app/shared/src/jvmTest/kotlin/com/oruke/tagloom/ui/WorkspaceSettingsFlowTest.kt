package com.oruke.tagloom.ui

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.oruke.tagloom.App
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 从生产 App 根节点验证设置导航、PIN 持久化、真实备份及宽窄屏入口。 */
@OptIn(ExperimentalTestApi::class)
class WorkspaceSettingsFlowTest {
    /** 验证笔记草稿往返、菜单迁移和 PIN 启用、更换、停用的完整闭环。 */
    @Test
    fun managesSettingsThroughProductionApp() = withEnvironment { environment ->
        runDesktopComposeUiTest(width = 1280, height = 820) {
            setContent { App(capabilities = environment.capabilities) }
            waitForText("设置")
            onAllNodes(hasText("新建笔记", substring = true) and hasClickAction())[0].performClick()
            onNodeWithTag("supertag-picker-item-default-note-tag").performClick()
            onNodeWithText("状态").assertDoesNotExist()
            onNodeWithText("日期").assertDoesNotExist()
            onNode(hasSetTextAction() and hasText("未命名笔记")).performTextReplacement(TEST_NOTE_TITLE)

            // 页头只保留一个图标入口；悬停说明用途，点击仍进入真实的笔记查找流程。
            onAllNodesWithTag("note-search-entry").assertCountEquals(1)
            onNodeWithText("⌕").assertDoesNotExist()
            onNodeWithText("查找").assertDoesNotExist()
            onNodeWithTag("note-search-entry").performMouseInput { moveTo(center) }
            waitForText("在笔记中查找 · Ctrl/Cmd+F")
            onNodeWithText("在笔记中查找 · Ctrl/Cmd+F").assertIsDisplayed()
            saveScreenshot("note-search-entry-desktop.png")
            onAllNodes(isRoot())[0].performMouseInput { moveTo(topLeft) }
            val entryBounds = onNodeWithTag("note-search-entry").fetchSemanticsNode().boundsInRoot
            val titleBounds = onNode(hasSetTextAction() and hasText(TEST_NOTE_TITLE)).fetchSemanticsNode().boundsInRoot
            onNodeWithTag("note-search-entry").performClick()
            onNodeWithTag("note-search-input").assertIsFocused()
            val toolbarBounds = onNodeWithTag("note-search-bar").fetchSemanticsNode().boundsInRoot
            assertEquals(titleBounds, onNode(hasSetTextAction() and hasText(TEST_NOTE_TITLE)).fetchSemanticsNode().boundsInRoot)
            assertEquals(entryBounds, onNodeWithTag("note-search-entry").fetchSemanticsNode().boundsInRoot)
            assertTrue(toolbarBounds.top >= entryBounds.bottom, "查找浮层应位于图标下方")
            assertTrue(kotlin.math.abs(toolbarBounds.right - entryBounds.right) <= 1f, "浮层右侧应与搜索图标对齐")
            assertTrue(toolbarBounds.width < onNodeWithTag("editor-pane").fetchSemanticsNode().boundsInRoot.width * .6f)
            saveScreenshot("note-search-floating-desktop.png")
            onNodeWithContentDescription("关闭查找").performClick()

            // 编辑页只提供笔记操作，安全与备份动作已经迁出更多菜单。
            onNodeWithText("⋯").performClick()
            onNodeWithText("创建密文备份").assertDoesNotExist()
            onNodeWithText("设置本机 PIN 码").assertDoesNotExist()
            onNodeWithText("更换本机 PIN 码").assertDoesNotExist()
            onNodeWithText("轮换主密钥").assertDoesNotExist()
            onNode(hasText("立即保存", substring = true) and hasClickAction()).performClick()
            waitForText("已保存")

            // 原工作区全文搜索仍能从更多菜单打开，图标合并不丢失已有搜索能力。
            onNodeWithText("⋯").performClick()
            onNodeWithText("搜索整个工作区").performClick()
            waitForText("仅搜索已确认快照")
            onNodeWithTag("editor-pane").performMouseInput { click(topLeft) }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("仅搜索已确认快照").fetchSemanticsNodes().isEmpty() }

            onNode(hasText("设置") and hasClickAction()).performClick()
            onNodeWithText("返回笔记").assertIsDisplayed()
            onNodeWithText("主密钥与恢复密钥").assertIsDisplayed()
            saveScreenshot("settings-desktop.png")
            onNodeWithText("设置 PIN 码").performClick()
            onNode(hasText("设置 PIN 码") and hasAnyAncestor(isDialog())).assertIsNotEnabled()
            onAllNodes(hasSetTextAction() and hasAnyAncestor(isDialog()))[0].performTextReplacement(TEST_INITIAL_PIN)
            onAllNodes(hasSetTextAction() and hasAnyAncestor(isDialog()))[1].performTextReplacement(TEST_INITIAL_PIN)
            onNode(hasText("设置 PIN 码") and hasAnyAncestor(isDialog())).performClick()
            waitForText("此设备现已启用 PIN 码")
            assertTrue(environment.gateway.recentWorkspaceOrNull()?.requiresPin == true)

            onNodeWithText("管理 PIN 码").performClick()
            onAllNodes(hasSetTextAction() and hasAnyAncestor(isDialog()))[0].performTextReplacement(TEST_INITIAL_PIN)
            onAllNodes(hasSetTextAction() and hasAnyAncestor(isDialog()))[1].performTextReplacement(TEST_CHANGED_PIN)
            onAllNodes(hasSetTextAction() and hasAnyAncestor(isDialog()))[2].performTextReplacement(TEST_CHANGED_PIN)
            onNodeWithText("确认更换").performClick()
            waitForText("本机 PIN 码已更换")

            onNodeWithText("管理 PIN 码").performClick()
            onAllNodes(hasSetTextAction() and hasAnyAncestor(isDialog()))[0].performTextReplacement(TEST_CHANGED_PIN)
            onNodeWithText("停用 PIN 码").performClick()
            waitForText("此设备将通过安全存储自动打开")
            assertFalse(environment.gateway.recentWorkspaceOrNull()?.requiresPin == true)

            // 备份使用真实文件服务，人工可观察结果必须包含 manifest 和笔记密文。
            onNodeWithText("创建密文备份").performClick()
            waitForText("密文备份已创建")
            ZipFile(environment.root.resolve("backup.zip").toFile()).use { archive ->
                assertTrue(archive.entries().asSequence().any { it.name == "manifest.enc" })
                assertTrue(archive.entries().asSequence().any { it.name.startsWith("notes/") })
            }
            onNodeWithText("轮换主密钥").performClick()
            onNodeWithText("轮换工作区主密钥").assertIsDisplayed()
            onNodeWithText("取消").performClick()
            onNodeWithText("返回笔记").performClick()
            onNode(hasSetTextAction() and hasText(TEST_NOTE_TITLE)).assertIsDisplayed()
            onNodeWithText("状态").assertDoesNotExist()
            onNodeWithText("日期").assertDoesNotExist()
            saveScreenshot("plain-note-desktop.png")
        }
    }

    /** 验证没有笔记时和窄屏隐藏侧栏后，设置仍有入口且能回到原笔记。 */
    @Test
    fun opensSettingsFromEmptyAndCompactWorkspace() = withEnvironment { environment ->
        runDesktopComposeUiTest(width = 480, height = 900) {
            setContent { App(capabilities = environment.capabilities) }
            waitForText("设置")
            onNode(hasText("设置") and hasClickAction()).performClick()
            onNodeWithText("设置 PIN 码").assertIsDisplayed()
            saveScreenshot("settings-compact.png")
            onNodeWithText("返回笔记").performClick()
            onAllNodes(hasText("新建笔记", substring = true) and hasClickAction())[0].performClick()
            onNodeWithTag("supertag-picker-item-default-note-tag").performClick()
            onNode(hasSetTextAction() and hasText("未命名笔记")).performTextReplacement(TEST_NOTE_TITLE)
            onNode(hasText("设置") and hasClickAction()).performClick()
            onNodeWithText("返回笔记").performClick()
            onNode(hasSetTextAction() and hasText(TEST_NOTE_TITLE)).assertIsDisplayed()
        }
    }

    /**
     * 等待真实文件 I/O 驱动的 UI 状态完成，避免仅断言已启动的协程。
     * @param text 状态完成后应出现的本地化文本。
     */
    private fun ComposeUiTest.waitForText(text: String) {
        try {
            waitUntil(timeoutMillis = 15_000) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        } catch (error: Throwable) {
            runCatching { saveScreenshot("settings-failure.png") }
            throw AssertionError("未出现预期界面状态：$text", error)
        }
    }

    /**
     * 保存真实 Compose 渲染结果供人工视觉检查，输出仅进入忽略的构建目录。
     * @param filename 固定的测试截图文件名。
     */
    private fun ComposeUiTest.saveScreenshot(filename: String) {
        val directory = Path.of("build", "reports", "settings-ui")
        Files.createDirectories(directory)
        Image.makeFromBitmap(onAllNodes(isRoot())[0].captureToImage().asSkiaBitmap()).use { image ->
            image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                Files.write(directory.resolve(filename), data.bytes)
            }
        }
    }

    /**
     * 固定中文界面并创建隔离工作区，结束后恢复进程语言与文件环境。
     * @param scenario 从真实 App 根节点执行的验证流程。
     */
    private fun withEnvironment(scenario: (WorkspaceSettingsTestEnvironment) -> Unit) {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
            WorkspaceSettingsTestEnvironment().use(scenario)
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    private companion object {
        /** 仅用于隔离工作区的脱敏 PIN，不能用于实际账户。 */
        const val TEST_INITIAL_PIN = "123789"
        /** 验证更换和后续认证的脱敏 PIN。 */
        const val TEST_CHANGED_PIN = "456987"
        /** 用于验证导航后文档仍存在的脱敏标题。 */
        const val TEST_NOTE_TITLE = "设置导航验收笔记"
    }
}
