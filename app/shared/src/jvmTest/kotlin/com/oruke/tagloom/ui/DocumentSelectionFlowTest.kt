package com.oruke.tagloom.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import com.oruke.tagloom.App
import com.oruke.tagloom.TagloomAppState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import com.oruke.tagloom.editor.selectionText
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.test.*

/** 从生产 App 的真实鼠标键盘输入验证跨块选择、替换和最终加密快照。 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class DocumentSelectionFlowTest {
    /** 正反向拖过换行、样式与代码，复制精确原文，输入替换后保存重开。 */
    @Test
    fun dragsCopiesFormatsAndPersistsReplacement() = withEnvironment { environment ->
        prepare(environment)
        var clipboard: ClipboardManager? = null
        runDesktopComposeUiTest(width = 1280, height = 900) {
            setContent { clipboard = LocalClipboardManager.current; App(capabilities = environment.capabilities) }
            waitForBlock("first")
            val original = fixture()
            drag("text-input-first", 3, "text-input-last", 6)
            assertRange("text-input-first", TextRange(3, original.blocks[0].selectionText()!!.length))
            assertRange("code-input-code", TextRange(0, original.blocks[1].selectionText()!!.length))
            assertRange("text-input-last", TextRange(0, 6))
            assertSelectionAppearance("text-input-first", TextRange(3, original.blocks[0].selectionText()!!.length))
            onNodeWithTag("text-input-first").performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.C); keyUp(Key.CtrlLeft) }
            val copied = original.blocks.take(3).mapIndexed { index, block ->
                val text = block.selectionText()!!
                when (index) {
                    0 -> text.substring(3); 2 -> text.substring(0, 6); else -> text
                }
            }.joinToString("\n")
            runOnIdle { assertEquals(copied, clipboard!!.getText()!!.text) }
            screenshot("selection-desktop-forward.png")
            onNodeWithTag("text-input-last").performClick()
            drag("text-input-last", 6, "text-input-first", 3)
            assertRange("text-input-first", TextRange(3, original.blocks[0].selectionText()!!.length))
            onNodeWithTag("text-input-first").performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.B); keyUp(Key.CtrlLeft) }
            screenshot("selection-desktop-reverse.png")
            onNodeWithTag("text-input-first").performTextInput("替换内容")
            onNodeWithTag("text-input-first").assertTextEquals(
                original.blocks[0].selectionText()!!.take(3) + "替换内容" + original.blocks[2].selectionText()!!.drop(6)
            )
            onNodeWithTag("code-input-code").assertDoesNotExist()
            onNodeWithTag("text-input-child").assertTextEquals("Unselected child")
            save()
        }
        val saved =
            runBlocking { environment.gateway.lock(); environment.gateway.openRecentWorkspace().notes.single().note }
        assertEquals(setOf("code", "last"), saved.blocks.filter(Block::deleted).map { it.id.value }.toSet())
        assertNull(saved.blocks.single { it.id.value == "child" }.parentId)
        assertTrue(NoteValidator.validate(saved).isEmpty())
        assertTrue(saved.blocks.first().selectionText()!!.contains("替换内容"))
    }

    /** 窄屏混排选区统一行高，Shift 方向键延伸到下块并支持删除及原生继续输入。 */
    @Test
    fun extendsByKeyboardOnCompactScreen() = withEnvironment { environment ->
        prepare(environment)
        runDesktopComposeUiTest(width = 480, height = 900) {
            setContent { App(capabilities = environment.capabilities) }
            waitForBlock("first")
            val first = onNodeWithTag("text-input-first")
            first.performClick()
                .performTextInputSelection(TextRange(0, fixture().blocks.first().selectionText()!!.length))
            assertSelectionAppearance(
                "text-input-first",
                TextRange(0, fixture().blocks.first().selectionText()!!.length)
            )
            screenshot("selection-compact-mixed.png")
            first.performTextInputSelection(TextRange(fixture().blocks.first().selectionText()!!.length))
            first.performKeyInput {
                keyDown(Key.ShiftLeft); pressKey(Key.DirectionRight); pressKey(Key.DirectionRight); keyUp(
                Key.ShiftLeft
            )
            }
            assertRange("code-input-code", TextRange(0, 1))
            first.performKeyInput { pressKey(Key.Delete) }
            first.performTextInput(" appended")
            save()
        }
        val saved =
            runBlocking { environment.gateway.lock(); environment.gateway.openRecentWorkspace().notes.single().note }
        assertTrue(saved.blocks.single { it.id.value == "code" }.deleted)
        assertTrue(saved.blocks.first().selectionText()!!.contains(" appended"))
        assertTrue(NoteValidator.validate(saved).isEmpty())
    }

    /** 长文档拖出视口后连续滚动，复制包含离屏的完整中间块而非只有挂载字段。 */
    @Test
    fun selectsThroughScrollingViewport() = withEnvironment { environment ->
        val note = fixture().copy(blocks = (0 until 35).map { index ->
            Block(
                BlockId("row-$index"), BlockTypes.PARAGRAPH, orderKey = index.toString().padStart(10, '0'),
                content = RichTextBlockContent(RichText(listOf(InlineRun("Row $index synthetic text"))))
            )
        })
        prepare(environment, note)
        var clipboard: ClipboardManager? = null
        runDesktopComposeUiTest(width = 1000, height = 640) {
            setContent { clipboard = LocalClipboardManager.current; App(capabilities = environment.capabilities) }
            waitForBlock("row-0")
            val from = position("text-input-row-0", 0)
            val pane = onNodeWithTag("editor-pane").fetchSemanticsNode().boundsInRoot
            mainClock.autoAdvance = false
            onAllNodes(isRoot())[0].performMouseInput {
                moveTo(from); press(); moveTo(from + Offset(0f, 22f)); moveTo(Offset(from.x + 170f, pane.bottom - 6f))
            }
            mainClock.advanceTimeBy(900)
            onAllNodes(isRoot())[0].performMouseInput { release() }
            mainClock.autoAdvance = true
            waitForIdle()
            onNodeWithTag("text-input-row-0").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.C) } }
            runOnIdle {
                val copied = clipboard!!.getText()!!.text
                assertTrue(copied.startsWith("Row 0 synthetic text\nRow 1 synthetic text\n"))
                assertTrue("Row 10 synthetic text" in copied, copied)
            }
        }
        val saved =
            runBlocking { environment.gateway.lock(); environment.gateway.openRecentWorkspace().notes.single().note }
        assertEquals(note, saved)
    }

    /** 全选后进入查找再返回仍保留完整选区，剪切及粘贴均走跨块入口并可持久化。 */
    @Test
    fun restoresFromSearchAndCutsPastesWholeFlow() = withEnvironment { environment ->
        prepare(environment)
        var clipboard: ClipboardManager? = null
        runDesktopComposeUiTest(width = 1280, height = 900) {
            setContent { clipboard = LocalClipboardManager.current; App(capabilities = environment.capabilities) }
            waitForBlock("first")
            val first = onNodeWithTag("text-input-first")
            first.performClick().performTextInputSelection(TextRange(3))
            val shiftTarget = position("text-input-last", 6)
            first.performKeyInput { keyDown(Key.ShiftLeft) }
            onAllNodes(isRoot())[0].performMouseInput { click(shiftTarget) }
            first.performKeyInput { keyUp(Key.ShiftLeft) }
            assertRange("text-input-last", TextRange(0, 6))
            first.performClick().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.A); pressKey(Key.F) } }
            onNodeWithTag("note-search-input").assertIsFocused().performTextInput("Omega")
            onNodeWithTag("note-search-input").performKeyInput { pressKey(Key.Escape) }
            assertRange("text-input-child", TextRange(0, "Unselected child".length))
            first.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.X) } }
            first.assertTextEquals("")
            runOnIdle {
                assertEquals(
                    fixture().blocks.joinToString("\n") { (if (it.parentId != null) "    " else "") + it.selectionText()!! },
                    clipboard!!.getText()!!.text
                )
            }
            first.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.V) } }
            save()
        }
        val saved =
            runBlocking { environment.gateway.lock(); environment.gateway.openRecentWorkspace().notes.single().note }
        assertTrue(
            saved.blocks.filterNot(Block::deleted).any { it.selectionText()?.contains("println(count)") == true })
        assertTrue(NoteValidator.validate(saved).isEmpty())
    }

    /** 查找打开期间修改跨块选区后，关闭查找不会恢复已删除块的旧选区。 */
    @Test
    fun keepsEditsMadeWhileSearchIsOpen() = withEnvironment { environment ->
        prepare(environment)
        runDesktopComposeUiTest(width = 1280, height = 900) {
            setContent { App(capabilities = environment.capabilities) }
            waitForBlock("first")
            drag("text-input-first", 0, "text-input-last", 6)
            val first = onNodeWithTag("text-input-first")
            first.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.F) } }
            onNodeWithTag("note-search-input").performTextInput("text")
            first.performTextInput("replacement")
            onNodeWithContentDescription("关闭查找").performClick()
            first.performTextInput("!")
            first.assertTextEquals("replacement!" + fixture().blocks[2].selectionText()!!.drop(6))
            save()
        }
        val saved =
            runBlocking { environment.gateway.lock(); environment.gateway.openRecentWorkspace().notes.single().note }
        assertTrue(saved.blocks.single { it.id.value == "last" }.deleted)
        assertTrue(NoteValidator.validate(saved).isEmpty())
    }

    /** 回车替换跨块范围后拆分段落，保留首尾未选文字并真实保存。 */
    @Test
    fun replacesSelectionWithParagraphBreak() = withEnvironment { environment ->
        prepare(environment)
        lateinit var appState: TagloomAppState
        runDesktopComposeUiTest(width = 1280, height = 900) {
            setContent {
                val scope = rememberCoroutineScope()
                appState = remember { TagloomAppState(environment.capabilities, scope) }
                App(capabilities = environment.capabilities, appState = appState)
            }
            waitForBlock("first")
            drag("text-input-first", 3, "text-input-last", 6)
            assertRange("text-input-first", TextRange(3, fixture().blocks.first().selectionText()!!.length))
            onNodeWithTag("text-input-first").assertIsFocused()
            onNodeWithTag("text-input-first").performKeyInput { pressKey(Key.Enter) }
            runOnIdle {
                val note = appState.controller.editorState.selectedDocument!!.note
                assertEquals(
                    3, note.blocks.first().selectionText()!!.length,
                    "After Enter: " + note.blocks.map { it.id.value to (it.selectionText()?.length to it.deleted) })
            }
            onNodeWithTag("text-input-first").assertTextEquals(fixture().blocks.first().selectionText()!!.take(3))
            save()
        }
        val saved =
            runBlocking { environment.gateway.lock(); environment.gateway.openRecentWorkspace().notes.single().note }
        assertTrue(
            saved.blocks.filterNot(Block::deleted)
                .any { it.selectionText() == fixture().blocks[2].selectionText()!!.drop(6) })
        assertTrue(NoteValidator.validate(saved).isEmpty())
    }

    /**
     * 在合成笔记上建立已确认加密快照，App 再按正常入口解锁。
     * @param environment 真实临时工作区。
     * @param note 待确认的合成笔记。
     */
    private fun prepare(environment: WorkspaceSettingsTestEnvironment, note: Note = fixture()) = runBlocking {
        environment.gateway.openRecentWorkspace()
        environment.gateway.saveNote(null, note, null)
        environment.gateway.lock()
    }

    /** @return 包含中英文、粗体、行内代码、高亮及未选子块的脱敏示例。 */
    private fun fixture(): Note = Note(
        "Selection example", listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID), blocks = listOf(
            Block(
                BlockId("first"), BlockTypes.PARAGRAPH, orderKey = "0", content = RichTextBlockContent(
                    RichText(
                        listOf(
                            InlineRun("开头 Alpha，"),
                            InlineRun("加粗文字 Bold", marks = listOf(InlineMark(InlineMarkTypes.BOLD))),
                            InlineRun(" inline_call() ", marks = listOf(InlineMark(InlineMarkTypes.CODE))),
                            InlineRun(
                                "高亮文字 highlight",
                                marks = listOf(InlineMark(InlineMarkTypes.HIGHLIGHT, mapOf("color" to "yellow")))
                            ),
                            InlineRun("。换行后的文字同样连续选中，Mixed text remains readable。".repeat(2)),
                        )
                    )
                )
            ),
            Block(
                BlockId("code"),
                BlockTypes.CODE,
                orderKey = "1",
                content = CodeBlockContent("kotlin", "val count = 42\nprintln(count)")
            ),
            Block(
                BlockId("last"),
                BlockTypes.PARAGRAPH,
                orderKey = "2",
                content = RichTextBlockContent(RichText(listOf(InlineRun("最后 Omega end"))))
            ),
            Block(
                BlockId("child"),
                BlockTypes.PARAGRAPH,
                parentId = BlockId("last"),
                orderKey = "0",
                content = RichTextBlockContent(RichText(listOf(InlineRun("Unselected child"))))
            ),
        )
    )

    /**
     * 用真实字符排版定位拖选，不硬编码字体宽度。
     * @param tag 原生输入框标签。
     * @param offset UTF-16 字符偏移。
     * @return 根窗口中的字符光标中心。
     */
    private fun ComposeUiTest.position(tag: String, offset: Int): Offset {
        val node = onNodeWithTag(tag)
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val caret = layouts.single().getCursorRect(offset)
        return node.fetchSemanticsNode().boundsInRoot.topLeft + Offset(caret.left + 0.5f, caret.center.y)
    }

    /**
     * 跨字段执行完整鼠标按下、移动及松开。
     * @param from 起点输入框。
     * @param start 起点偏移。
     * @param to 终点输入框。
     * @param end 终点偏移。
     */
    private fun ComposeUiTest.drag(from: String, start: Int, to: String, end: Int) {
        val a = position(from, start)
        val b = position(to, end)
        onAllNodes(isRoot())[0].performMouseInput {
            moveTo(a); press(); moveTo(
            a + Offset(
                0f,
                if (b.y > a.y) 20f else -20f
            )
        ); moveTo(b); release()
        }
        waitForIdle()
    }

    /** @param tag 字段标签。 @param range 预期原文区间。 */
    private fun ComposeUiTest.assertRange(tag: String, range: TextRange) {
        assertEquals(range, onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(DocumentSelectedRangeKey))
    }

    /**
     * 核对实际输入框的显示 Span 和行盒，避免仅验证截图文件生成。
     * @param tag 输入字段。
     * @param range 完整字符选区。
     */
    private fun ComposeUiTest.assertSelectionAppearance(tag: String, range: TextRange) {
        val layouts = mutableListOf<TextLayoutResult>()
        onNodeWithTag(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val selectedSpans = layout.layoutInput.text.spanStyles.filter { it.start < range.max && it.end > range.min }
        assertTrue(
            selectedSpans.none { it.item.background != Color.Unspecified && it.item.background != Color.Transparent },
            selectedSpans.toString()
        )
        val rectangles = selectionLineRects(layout, range)
        assertTrue(rectangles.isNotEmpty())
        rectangles.forEach { rect ->
            val line = layout.getLineForVerticalPosition(rect.center.y)
            assertEquals(layout.getLineTop(line), rect.top)
            assertEquals(layout.getLineBottom(line), rect.bottom)
        }
    }

    /** @param id 等待生产 App 显示的块。 */
    private fun ComposeUiTest.waitForBlock(id: String) {
        waitUntil(timeoutMillis = 15_000) { onAllNodesWithTag("text-input-$id").fetchSemanticsNodes().isNotEmpty() }
    }

    /** 通过生产保存菜单等待加密快照确认。 */
    private fun ComposeUiTest.save() {
        onNodeWithText("⋯").performClick()
        onNode(hasText("立即保存", substring = true) and hasClickAction()).performClick()
        waitUntil(timeoutMillis = 15_000) { onAllNodesWithText("已保存").fetchSemanticsNodes().isNotEmpty() }
    }

    /** @param filename 忽略构建目录中的人工验收截图名。 */
    private fun ComposeUiTest.screenshot(filename: String) {
        val directory = Path.of("build", "reports", "document-selection-ui")
        Files.createDirectories(directory)
        Image.makeFromBitmap(onAllNodes(isRoot())[0].captureToImage().asSkiaBitmap()).use { image ->
            image.encodeToData(EncodedImageFormat.PNG)!!.use { Files.write(directory.resolve(filename), it.bytes) }
        }
    }

    /** @param scenario 在真实工作区上执行的完整业务场景。 */
    private fun withEnvironment(scenario: (WorkspaceSettingsTestEnvironment) -> Unit) {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE); WorkspaceSettingsTestEnvironment().use(scenario)
        } finally {
            Locale.setDefault(previous)
        }
    }
}
