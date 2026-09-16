package com.oruke.tagloom.ui

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.App
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.editor.search.*
import com.oruke.tagloom.ui.search.NoteSearchActiveKey
import com.oruke.tagloom.ui.search.NoteSearchTargetKey
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.test.*

/** 从生产 App、真实加密快照和 SQLite 装配验证笔记内查找，所有测试数据限制在临时工作区。 */
@OptIn(ExperimentalTestApi::class)
class NoteSearchEditorFlowTest {
    /** 快捷键、隐藏页签和宽表格均可定位，重复聚焦查找后恢复原选区；加密笔记完全一致。 */
    @Test
    fun findsAndRevealsMatchesWithoutSavingChanges() = withEnvironment { environment ->
        val before = runBlocking {
            environment.gateway.openRecentWorkspace()
            environment.gateway.saveNote(null, noteSearchTestNote(), null).also { environment.gateway.lock() }
        }
        runDesktopComposeUiTest(width = 1440, height = 1000) {
            setContent { App(capabilities = environment.capabilities) }
            waitUntil(timeoutMillis = 15_000) {
                onAllNodesWithTag("text-input-rich").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithTag("text-input-rich").performMouseInput { click() }
            onNodeWithTag("text-input-rich").performTextInputSelection(TextRange(0, 6))
            val bodyBounds = onNodeWithTag("text-input-rich").fetchSemanticsNode().boundsInRoot
            onNodeWithTag("text-input-rich").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.F) } }
            onNodeWithTag("note-search-input").assertIsFocused().assertTextContains("Needle")
            assertCount("1 / 8")
            assertEquals(bodyBounds, onNodeWithTag("text-input-rich").fetchSemanticsNode().boundsInRoot)
            onNodeWithTag("note-search-entry").assertWidthIsEqualTo(36.dp)
            assertFindToolbar(compact = false)
            onNodeWithTag("note-search-input").performKeyInput { pressKey(Key.Enter) }
            assertCount("2 / 8")
            onNodeWithTag("note-search-input").performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Enter) } }
            assertCount("1 / 8")
            onNodeWithTag("note-search-input").performKeyInput { pressKey(Key.F3) }
            assertCount("2 / 8")
            onNodeWithTag("note-search-input").performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.F3) } }
            assertCount("1 / 8")
            onNodeWithTag("note-search-input").performTextReplacement("needle")
            onNodeWithTag("note-search-input").performKeyInput { pressKey(Key.Tab) }
            onNodeWithContentDescription("区分大小写").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
            onNodeWithContentDescription("区分大小写").assertIsOn()
            assertCount("1 / 7")
            onNodeWithContentDescription("区分大小写").performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
            onNodeWithTag("note-search-input").assertIsFocused()
            onNodeWithContentDescription("区分大小写").performClick().assertIsOff()
            assertEquals(bodyBounds, onNodeWithTag("text-input-rich").fetchSemanticsNode().boundsInRoot, "切换输入模式不能改变正文布局")
            repeat(2) { onNodeWithContentDescription("下一处").performClick() }
            assertCount("3 / 8")
            assertActiveHighlight(NoteSearchTarget(NoteSearchField.BODY, BlockId("rich")))
            screenshot("find-desktop.png")

            repeat(2) { onNodeWithContentDescription("下一处").performClick() }
            assertCount("5 / 8")
            onNodeWithTag("note-search-input").performTextInputSelection(TextRange(2))
            onNodeWithTag("note-search-input").performKeyInput { pressKey(Key.DirectionLeft) }
            assertCount("5 / 8")
            assertActiveHighlight(NoteSearchTarget(NoteSearchField.BODY, BlockId("code")))
            assertHorizontalScrolling(minimum = 1)
            onNodeWithTag("note-search-input").performTextReplacement("隐藏页签")
            assertCount("1 / 1")
            assertActiveHighlight(NoteSearchTarget(NoteSearchField.BODY, BlockId("hidden")))
            onNodeWithText("第二页").assertIsDisplayed()
            onNodeWithText("详情").assertIsDisplayed()
            onNodeWithTag("text-input-hidden").assertIsDisplayed()

            onNodeWithTag("note-search-input").performTextReplacement("needle")
            onNodeWithContentDescription("上一处").performClick()
            assertCount("8 / 8")
            val tableTarget = NoteSearchIndex.texts(before.note).last().target
            assertActiveHighlight(tableTarget)
            // 表格容器和长单元格都必须滚动，不能只显示目标列却仍把匹配截在单元格外。
            assertHorizontalScrolling(minimum = 2)
            screenshot("find-table.png")

            // 隐藏页签已经清空编辑器选区，查找框再次 Ctrl+F 仍须保留原正文选区供 Esc 恢复。
            onNodeWithTag("note-search-input").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.F) } }
            onNodeWithTag("note-search-input").assertIsFocused().assertTextContains("needle")
            assertCount("8 / 8")

            onNodeWithTag("note-search-input").performTextReplacement("不存在的匹配")
            assertCount("0 / 0")
            onNodeWithContentDescription("上一处").assertIsNotEnabled()
            onNodeWithContentDescription("下一处").assertIsNotEnabled()
            onNodeWithTag("note-search-input").performKeyInput { pressKey(Key.Escape) }
            onNodeWithTag("note-search-bar").assertDoesNotExist()
            onNodeWithTag("text-input-rich").assertIsFocused()
            assertEquals(
                TextRange(0, 6),
                onNodeWithTag("text-input-rich").fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
            )
            onNodeWithTag("text-input-rich").performTextInputSelection(TextRange(6))
            onAllNodes(isRoot())[0].performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.F) } }
            onNodeWithTag("note-search-input").assertIsFocused().assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.EditableText,
                    androidx.compose.ui.text.AnnotatedString("")
                )
            )
            onNodeWithContentDescription("关闭查找").performClick()
        }
        val after = runBlocking {
            environment.gateway.lock()
            environment.gateway.openRecentWorkspace().notes.single()
        }
        assertEquals(before, after)
    }

    /** 在 400dp 窄屏上从可见入口打开，编辑草稿立即刷新结果，切换笔记清除查找会话。 */
    @Test
    fun followsUnsavedDraftAndResetsOnNoteSwitch() = withEnvironment { environment ->
        runDesktopComposeUiTest(width = 400, height = 900) {
            setContent { App(capabilities = environment.capabilities) }
            waitUntil(timeoutMillis = 15_000) { onAllNodesWithText("新建笔记").fetchSemanticsNodes().isNotEmpty() }
            onAllNodesWithText("新建笔记")[0].performClick()
            onNode(hasSetTextAction() and hasText("未命名笔记")).performTextReplacement("查找测试")
            val body = SemanticsMatcher("正文输入") {
                it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("text-input-") == true
            }
            onNode(body).performTextReplacement("needle needle")
            val bodyBounds = onNode(body).fetchSemanticsNode().boundsInRoot
            onNodeWithTag("note-search-entry").performClick()
            assertEquals(bodyBounds, onNode(body).fetchSemanticsNode().boundsInRoot, "打开浮层不能挤动正文")
            val toolbar = onNodeWithTag("note-search-bar").fetchSemanticsNode().boundsInRoot
            val pane = onNodeWithTag("editor-pane").fetchSemanticsNode().boundsInRoot
            val entry = onNodeWithTag("note-search-entry").fetchSemanticsNode().boundsInRoot
            assertTrue(toolbar.top >= entry.bottom && toolbar.left >= pane.left && toolbar.right <= pane.right)
            assertFindToolbar(compact = true)
            screenshot("find-floating-compact.png")
            onNodeWithTag("note-search-input").performTextReplacement("needle")
            assertCount("1 / 2")
            onNode(body).performTextReplacement("needle changed")
            assertCount("1 / 1")
            onNodeWithContentDescription("下一处").assertIsDisplayed()
            onNodeWithContentDescription("关闭查找").assertIsDisplayed()
            val fieldBounds = onNodeWithTag("note-search-input").fetchSemanticsNode().boundsInRoot
            assertTrue(fieldBounds.width >= 180f, "窄屏查找框需要保留可输入宽度")
            screenshot("find-compact.png")
            onNode(body).performClick().performTextInputSelection(TextRange(6))
            onNode(body).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.F) } }
            onNodeWithTag("note-search-input").performKeyInput { pressKey(Key.Escape) }
            onNode(body).assertIsFocused().performTextInput("!")
            onNode(body).assertTextEquals("needle! changed")
            onNode(body).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.F) } }
            onAllNodesWithText("新建笔记")[0].performClick()
            onNodeWithTag("note-search-bar").assertDoesNotExist()
            onNodeWithTag("note-search-entry").performClick()
            onNodeWithTag("note-search-input").assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.EditableText,
                    androidx.compose.ui.text.AnnotatedString("")
                )
            )
            onNodeWithContentDescription("关闭查找").performClick()
            onNode(body).performTextReplacement("```kotlin\nval sample = 1\n```")
            val code = SemanticsMatcher("代码输入") {
                it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("code-input-") == true
            }
            onNode(code).performClick().performTextInputSelection(TextRange(0, 3))
            onNode(code).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.F) } }
            onNodeWithTag("note-search-input").assertTextContains("val").performKeyInput { pressKey(Key.Escape) }
            onNode(code).assertIsFocused().performTextInput("var")
            onNode(code).assertTextEquals("var sample = 1")
        }
    }

    /** 长标题和超过三行的描述通过各自滚动视口定位，输入框仍保留焦点与原文。 */
    @Test
    fun revealsMatchesInClippedHeaderFields() = withEnvironment { environment ->
        val note = noteSearchTestNote().let { original ->
            original.copy(
                title = "较长的笔记标题 ".repeat(30) + "标题目标",
                propertyValues = original.propertyValues.mapValues { (_, value) ->
                    value.copy(
                        value = kotlinx.serialization.json.JsonPrimitive(
                            "描述中的一般内容\n".repeat(20) + "描述目标"
                        )
                    )
                },
                blocks = original.blocks.map { block ->
                    if (block.id == BlockId("first-tab")) block.copy(
                        content = com.oruke.tagloom.core.model.TabBlockContent(
                            searchTestText("较长的页签标题 ".repeat(20) + "页签目标")
                        )
                    ) else block
                },
            )
        }
        runBlocking {
            environment.gateway.openRecentWorkspace()
            environment.gateway.saveNote(null, note, null)
            environment.gateway.lock()
        }
        runDesktopComposeUiTest(width = 1000, height = 800) {
            setContent { App(capabilities = environment.capabilities) }
            waitUntil(timeoutMillis = 15_000) {
                onAllNodesWithTag("note-search-entry").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithTag("note-search-entry").performClick()
            onNodeWithTag("note-search-input").performTextReplacement("标题目标")
            assertCount("1 / 1")
            assertActiveHighlight(NoteSearchTarget(NoteSearchField.TITLE))
            assertHorizontalScrolling(1)
            onNodeWithTag("note-search-input").performTextReplacement("描述目标")
            assertCount("1 / 1")
            assertActiveHighlight(NoteSearchTarget(NoteSearchField.DESCRIPTION))
            screenshot("find-long-description.png")
            onNodeWithTag("note-search-input").assertIsFocused()
            onNodeWithTag("note-search-input").performTextReplacement("页签目标")
            assertCount("1 / 1")
            assertActiveHighlight(NoteSearchTarget(NoteSearchField.TAB, BlockId("first-tab")))
            assertHorizontalScrolling(1)
            screenshot("find-long-tab.png")
        }
    }

    /**
     * 读取实际文本布局，确认当前匹配是显示注解且能够被定位。
     * @param target 预期当前匹配所在字段。
     */
    private fun ComposeUiTest.assertActiveHighlight(target: NoteSearchTarget) {
        val matcher = SemanticsMatcher.expectValue(NoteSearchTargetKey, target) and SemanticsMatcher.expectValue(
            NoteSearchActiveKey,
            true
        )
        waitUntil(timeoutMillis = 10_000) {
            onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNode(matcher, useUnmergedTree = true).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        onNode(matcher, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            it(
                layouts
            )
        }
        assertTrue(layouts.any {
            it.layoutInput.text.getStringAnnotations("note-search", 0, it.layoutInput.text.length)
                .any { annotation -> annotation.item == "active" }
        })
        // 直接检查匹配字符的真实屏幕位置，避免把滚动发生或整个输入框可见当成字符可见。
        val layout = layouts.first()
        val match = layout.layoutInput.text.getStringAnnotations("note-search", 0, layout.layoutInput.text.length)
            .first { it.item == "active" }
        val highlight = layout.layoutInput.text.spanStyles.last { it.start == match.start && it.end == match.end }.item
        assertEquals(Color(0xFFFCD34D), highlight.background, "当前命中应采用已确认的琥珀色")
        assertNull(highlight.fontWeight, "查找显示不能覆盖原文字重")
        assertEquals(androidx.compose.ui.unit.TextUnit.Unspecified, highlight.fontSize)
        waitUntil(timeoutMillis = 10_000) {
            val node = onNode(matcher, useUnmergedTree = true).fetchSemanticsNode()
            val bounds = layout.getBoundingBox(match.start).translate(node.positionInRoot)
            val viewport = onNodeWithTag("editor-pane").fetchSemanticsNode().boundsInRoot
            val bar = onNodeWithTag("note-search-bar").fetchSemanticsNode().boundsInRoot
            bounds.top >= viewport.top - 1f && bounds.bottom <= viewport.bottom + 1f &&
                    bounds.left >= viewport.left - 1f && bounds.right <= viewport.right + 1f && !bounds.overlaps(bar)
        }
    }

    /**
     * 在生产页面检查输入选项分组、按钮可见范围和桌面尺寸，防止工具栏挤压正文或操作溢出。
     * @param compact 当前是否使用窄屏的两行布局。
     */
    private fun ComposeUiTest.assertFindToolbar(compact: Boolean) {
        val bar = onNodeWithTag("note-search-bar").fetchSemanticsNode().boundsInRoot
        val entry = onNodeWithTag("note-search-entry").fetchSemanticsNode().boundsInRoot
        val input = onNodeWithTag("note-search-input").fetchSemanticsNode().boundsInRoot
        val case = onNodeWithContentDescription("区分大小写").fetchSemanticsNode().boundsInRoot
        assertTrue(case.left >= input.left && case.right <= input.right, "Aa 应位于输入区域内")
        listOf("区分大小写", "上一处", "下一处", "关闭查找").forEach { label ->
            val bounds = onNodeWithContentDescription(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.left >= bar.left && bounds.right <= bar.right && bounds.top >= bar.top && bounds.bottom <= bar.bottom)
        }
        if (compact) {
            val next = onNodeWithContentDescription("下一处").fetchSemanticsNode().boundsInRoot
            assertTrue(next.top >= input.bottom, "窄屏导航应使用第二行")
        } else {
            onNodeWithTag("note-search-bar").assertWidthIsEqualTo(480.dp).assertHeightIsEqualTo(52.dp)
            val density = bar.width / 480f
            assertEquals(entry.right, bar.right, 1f)
            assertEquals(entry.bottom + 8f * density, bar.top, 1f)
        }
    }

    /**
     * 核对可见窗口中发生了所需层级的水平滚动。
     * @param minimum 必须已滚动的最少容器数量。
     */
    private fun ComposeUiTest.assertHorizontalScrolling(minimum: Int) {
        waitUntil(timeoutMillis = 10_000) {
            onAllNodes(hasScrollAction(), useUnmergedTree = true).fetchSemanticsNodes().count {
                (it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)?.value?.invoke() ?: 0f) > 0f
            } >= minimum
        }
    }

    /**
     * 等待并核对可观察匹配计数。
     * @param expected 本地化后的计数文本。
     */
    private fun ComposeUiTest.assertCount(expected: String) {
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(expected).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("note-search-count").assertTextEquals(expected)
    }

    /**
     * 保存仅含脱敏场景的真实 Compose 图像，用于人工核对布局和高亮。
     * @param filename 当前构建报告目录中的截图名。
     */
    private fun ComposeUiTest.screenshot(filename: String) {
        val directory = Path.of("build", "reports", "note-search-ui")
        Files.createDirectories(directory)
        Image.makeFromBitmap(onAllNodes(isRoot())[0].captureToImage().asSkiaBitmap()).use { image ->
            image.encodeToData(EncodedImageFormat.PNG)!!.use { Files.write(directory.resolve(filename), it.bytes) }
        }
    }

    /**
     * 给业务流程提供隔离的真实文件和固定中文 Locale，结束后恢复环境。
     * @param scenario 完整 UI 流程及最终加密快照断言。
     */
    private fun withEnvironment(scenario: (WorkspaceSettingsTestEnvironment) -> Unit) {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
            WorkspaceSettingsTestEnvironment().use(scenario)
        } finally {
            Locale.setDefault(previous)
        }
    }
}
