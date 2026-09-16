package com.oruke.tagloom.ui

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.oruke.tagloom.App
import com.oruke.tagloom.core.model.DefaultValuePolicy
import com.oruke.tagloom.core.model.PropertyValueSource
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.test.*

/** 从真实 App、加密事务与独立工作区验证超级标签搜索、维护和跨会话保存。 */
@OptIn(ExperimentalTestApi::class)
class SupertagWorkspaceFlowTest {
    /** 验证居中搜索、取消不创建、保存失败后的可见反馈与重试、键盘创建以及重开后的业务值。 */
    @Test
    fun createsEditsAndUsesSupertagsThroughProductionApp() = withEnvironment { environment ->
        runDesktopComposeUiTest(width = 1280, height = 820) {
            setContent { App(capabilities = environment.capabilities) }
            waitForTag("supertag-navigation")
            createNoteEntry()
            onNodeWithTag("supertag-picker-search").assertIsFocused()
            val picker = onNodeWithTag("supertag-picker").fetchSemanticsNode().boundsInWindow
            val root = onAllNodes(isRoot())[0].fetchSemanticsNode().boundsInWindow
            assertTrue(kotlin.math.abs(picker.center.x - root.center.x) < 2f)
            assertTrue(kotlin.math.abs(picker.center.y - root.center.y) < 2f)
            screenshot("picker-desktop.png")
            onNodeWithTag("supertag-picker-search").performKeyInput { pressKey(Key.Escape) }
            onNode(hasSetTextAction() and hasText("未命名笔记")).assertDoesNotExist()

            onNodeWithTag("supertag-navigation").performClick()
            onNodeWithTag("supertag-create").performClick()
            onNodeWithTag("supertag-name").performTextReplacement("Project Example")
            onNodeWithText("选择图标").performClick()
            onNodeWithTag("supertag-icon-search").performTextReplacement("book")
            onNodeWithTag("supertag-icon-book").performClick()
            onNodeWithText("添加属性").performScrollTo().performClick()
            onNodeWithText("新建属性", substring = false).performClick()
            onNode(hasSetTextAction() and hasText("属性名称")).performTextReplacement("Review")
            screenshot("property-create-desktop.png")
            onNode(hasClickAction() and hasAnyAncestor(isDialog()) and hasText("创建到工作区")).performClick()
            onNodeWithText("Review").performScrollTo().performClick()
            onNodeWithText("默认值：不设置").performScrollTo().performClick()
            onNodeWithText("创建时填入").performClick()
            // 在表单下方故意遗漏默认值，确认固定提示可见且草稿保持，然后修正并重试真实保存。
            onNodeWithText("取消", substring = false).performScrollTo()
            onNodeWithTag("supertag-name").assertIsNotDisplayed()
            onNodeWithTag("supertag-save").performClick()
            onNodeWithTag("supertag-save-error").assertIsDisplayed()
            onNodeWithTag("supertag-name").assertTextContains("Project Example")
            onNodeWithText("默认值：创建时填入").assertExists()
            screenshot("editor-error-desktop.png")
            onNode(hasSetTextAction() and hasText("默认值")).performScrollTo().performTextReplacement("Draft")
            onNodeWithTag("supertag-save-error").assertDoesNotExist()
            screenshot("editor-desktop.png")
            onNodeWithTag("supertag-save").performClick()
            waitForTag("supertag-library-search")
            screenshot("library-desktop.png")

            // 取消编辑只丢弃草稿；重开仍显示之前确认的图标。
            onNodeWithText("Project Example").performClick()
            onNodeWithText("清除").performClick()
            onNodeWithContentDescription("返回超级标签").performClick()
            onNodeWithText("Project Example").performClick()
            onNodeWithText("更换图标").assertIsDisplayed().performClick()
            onNodeWithTag("supertag-icon-folder").performClick()
            onNodeWithTag("supertag-name").performTextReplacement("Project Revised")
            onNodeWithTag("supertag-save").performClick()
            waitForTag("supertag-library-search")
            createNoteEntry()
            onNodeWithTag("supertag-picker-search").performTextReplacement("unmatched")
            onNodeWithText("没有匹配的超级标签").assertIsDisplayed()
            onNodeWithTag("supertag-picker-search").performTextReplacement("PROJECT")
            onNode(hasText("Project Revised") and hasAnyAncestor(isDialog())).assertIsDisplayed()
            screenshot("picker-filtered.png")
            onNodeWithTag("supertag-picker-search").performKeyInput { pressKey(Key.Enter) }
            onNode(hasSetTextAction() and hasText("未命名笔记")).assertIsFocused()
                .performTextReplacement("Supertag sample")
            onNodeWithText("Draft").assertIsDisplayed()
            onNodeWithText("状态").assertDoesNotExist()
            onNodeWithText("日期").assertDoesNotExist()
            waitForText("已保存")
            screenshot("note-desktop.png")
            onNodeWithTag("supertag-navigation").performClick()
            onNodeWithText("Project Revised").performClick()
            onNodeWithText("使用情况").performScrollTo()
            onNodeWithText("仍被笔记或子超级标签使用，无法删除。").assertIsDisplayed()

            // 重开走真实认证及解密，核对最终持久化，不能只以界面成功提示作为完成。
            runBlocking {
                environment.gateway.lock()
                val reopened = environment.gateway.openRecentWorkspace()
                val tag = reopened.tagDefinitions.single { it.definition.name == "Project Revised" }.definition
                assertEquals("folder", tag.iconKey)
                assertEquals(DefaultValuePolicy.SNAPSHOT, tag.propertyBindings.single().defaultPolicy)
                assertEquals(1, reopened.notes.size)
                val note = reopened.notes.single().note
                assertTrue(tag.id in note.directTagIds)
                assertEquals(
                    PropertyValueSource.TAG_SNAPSHOT,
                    note.propertyValues.getValue(tag.propertyBindings.single().propertyId).source
                )
            }
        }
    }

    /** 验证窄屏底部选择、从空搜索创建超级标签、图标选择和返回笔记。 */
    @Test
    fun usesCompactPickerAndMaintenanceForm() = withEnvironment { environment ->
        runDesktopComposeUiTest(width = 480, height = 900) {
            setContent { App(capabilities = environment.capabilities) }
            waitForText("超级标签")
            createNoteEntry()
            onNodeWithTag("supertag-picker-search").assertIsFocused()
            screenshot("picker-compact.png")
            onNodeWithTag("supertag-picker-search").performTextReplacement("Book Sample")
            onNodeWithText("新建超级标签").performClick()
            onNodeWithTag("supertag-name").assertTextContains("Book Sample")
            onNodeWithText("选择图标").performClick()
            screenshot("icons-compact.png")
            onNodeWithTag("supertag-icon-book").performClick()
            screenshot("editor-compact.png")
            onNodeWithTag("supertag-save").performClick()
            waitForTag("editor-pane")
            onNode(hasSetTextAction() and hasText("未命名笔记")).assertIsFocused()
            onNodeWithText("Book Sample").assertIsDisplayed()
            onNodeWithText("状态").assertDoesNotExist()
        }
    }

    /** 从当前工作区真实入口开始新建笔记。 */
    private fun ComposeUiTest.createNoteEntry() {
        onAllNodes(hasText("新建笔记", substring = true) and hasClickAction())[0].performClick()
    }

    /**
     * 等待异步磁盘操作完成后的真实页面。
     * @param tag 页面稳定语义标识。
     */
    private fun ComposeUiTest.waitForTag(tag: String) {
        waitUntil(timeoutMillis = 15_000) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    /**
     * 等待生产自动保存流程返回。
     * @param text 预期状态文案。
     */
    private fun ComposeUiTest.waitForText(text: String) {
        waitUntil(timeoutMillis = 15_000) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    /**
     * 将真实渲染保存到忽略的构建目录，保留所有弹窗根供视觉核对。
     * @param filename 脱敏截图名。
     */
    private fun ComposeUiTest.screenshot(filename: String) {
        val directory = Path.of("build", "reports", "supertags-ui")
        Files.createDirectories(directory)
        onAllNodes(isRoot()).fetchSemanticsNodes().indices.forEach { index ->
            Image.makeFromBitmap(onAllNodes(isRoot())[index].captureToImage().asSkiaBitmap()).use { image ->
                image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                    Files.write(directory.resolve(if (index == 0) filename else "$index-$filename"), data.bytes)
                }
            }
        }
    }

    /**
     * 使用独立的真实加密环境和中文资源，结束后恢复进程语言。
     * @param scenario 端到端交互流程。
     */
    private fun withEnvironment(scenario: (WorkspaceSettingsTestEnvironment) -> Unit) {
        val locale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
            WorkspaceSettingsTestEnvironment().use(scenario)
        } finally {
            Locale.setDefault(locale)
        }
    }
}
