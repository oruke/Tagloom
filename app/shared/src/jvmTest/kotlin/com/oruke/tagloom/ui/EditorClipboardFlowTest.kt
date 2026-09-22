package com.oruke.tagloom.ui

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.TextRange
import com.oruke.tagloom.App
import com.oruke.tagloom.TagloomAppState
import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import com.oruke.tagloom.editor.*
import kotlinx.coroutines.runBlocking
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.util.Locale
import kotlin.test.*

/** 从生产 App 的键盘和右键入口验证系统剪贴板，以及保存重开的真实块结构。 */
@OptIn(ExperimentalTestApi::class)
class EditorClipboardFlowTest {
    /** 跨块复制保留中段编号、嵌套列表、行内样式和代码语言，粘贴生成独立块并持久化。 */
    @Test
    fun copiesListsStylesAndCodeThenPersistsStructuredPaste() = withEnvironment { environment ->
        prepare(environment, fixture())
        lateinit var appState: TagloomAppState
        var pastedIds = emptyList<BlockId>()
        runDesktopComposeUiTest(width = 1280, height = 1100) {
            setContent {
                val scope = rememberCoroutineScope()
                appState = remember { TagloomAppState(environment.capabilities, scope) }
                App(capabilities = environment.capabilities, appState = appState)
            }
            waitForBlock("ordered-two")
            val source = onNodeWithTag("text-input-ordered-two")
            source.performClick().performTextInputSelection(TextRange(0))
            source.performKeyInput {
                withKeyDown(Key.CtrlLeft) {
                    withKeyDown(Key.ShiftLeft) { pressKey(Key.MoveEnd) }; pressKey(
                    Key.C
                )
                }
            }
            waitForIdle()
            runOnIdle {
                val clipboard = Toolkit.getDefaultToolkit().systemClipboard.getContents(null)
                assertEquals(
                    "2. Second strong\n    - Nested item\n3. Third linked\n- [x] Done\nval value = \"<tag>&\"",
                    clipboard.getTransferData(DataFlavor.stringFlavor)
                )
                val html = clipboard.getTransferData(DataFlavor.selectionHtmlFlavor) as String
                assertTrue("<ol start=\"2\">" in html, html)
                assertTrue("<strong>strong</strong>" in html, html)
                assertTrue("<ul><li>Nested item</li></ul></li>" in html, html)
                assertTrue("href=\"https://example.com/\"" in html, html)
                assertTrue("&lt;tag&gt;&amp;" in html, html)
                val encoded =
                    clipboard.getTransferData(DataFlavor("$EDITOR_CLIPBOARD_MIME;class=java.lang.String")) as String
                assertFalse("Unselected" in encoded)
                assertFalse("ordered-one" in encoded)
            }
            onNodeWithTag("text-input-destination").performClick()
                .performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.V) } }
            waitForIdle()
            runOnIdle {
                val state = appState.controller.editorState
                val ordered = BlockTreeReducer.orderedActive(state.selectedDocument!!.note.blocks)
                val pasted = ordered.dropWhile { it.id.value != "destination" }
                pastedIds = pasted.map(Block::id)
                assertEquals(
                    listOf(
                        BlockTypes.NUMBERED_LIST,
                        BlockTypes.BULLETED_LIST,
                        BlockTypes.NUMBERED_LIST,
                        BlockTypes.TODO,
                        BlockTypes.CODE
                    ), pasted.map(Block::type)
                )
                assertEquals(pasted[0].id, pasted[1].parentId)
                assertEquals(2, state.numberedListOrdinal(pasted[0].id))
                assertEquals(3, state.numberedListOrdinal(pasted[2].id))
                assertEquals("kotlin", (pasted.last().content as CodeBlockContent).language)
                assertEquals(fixture().blocks[1].editableRichText(), pasted[0].editableRichText())
            }
            save()
        }
        val saved = reopen(environment)
        val pasted = BlockTreeReducer.orderedActive(saved.blocks).filter { it.id in pastedIds }
        assertEquals(5, pasted.size)
        assertEquals(pasted.first().id, pasted[1].parentId)
        assertTrue((pasted[3].content as TodoBlockContent).checked)
        assertTrue(pasted[2].editableRichText()!!.runs.last().marks.any { it.type == InlineMarkTypes.LINK })
        assertTrue(NoteValidator.validate(saved).isEmpty(), NoteValidator.validate(saved).toString())
    }

    /** 右键复制整项带项目符号；局部文字仅复制其样式，右键粘贴和键盘粘贴保持一致。 */
    @Test
    fun contextMenuAndPartialInlineCopyPreserveStyles() = withEnvironment { environment ->
        val rich = RichText(
            listOf(
                InlineRun("Bullet "),
                InlineRun("strong", listOf(InlineMark(InlineMarkTypes.BOLD), InlineMark(InlineMarkTypes.UNDERLINE)))
            )
        )
        prepare(
            environment, Note(
                "Clipboard example", listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID), blocks = listOf(
                    Block(
                        BlockId("bullet"),
                        BlockTypes.BULLETED_LIST,
                        orderKey = "0",
                        content = RichTextBlockContent(rich)
                    ),
                    Block(BlockId("divider"), BlockTypes.DIVIDER, orderKey = "1", content = DividerBlockContent),
                    textBlock("destination", "", "2"),
                )
            )
        )
        runDesktopComposeUiTest(width = 1100, height = 850) {
            setContent { App(capabilities = environment.capabilities) }
            waitForBlock("bullet")
            val source = onNodeWithTag("text-input-bullet")
            source.performClick().performTextInputSelection(TextRange(0, rich.plainText.length))
            source.performMouseInput { click(button = MouseButton.Secondary) }
            onNode(hasText("Copy") or hasText("复制")).performClick()
            runOnIdle {
                assertEquals(
                    "- Bullet strong",
                    Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor)
                )
            }
            val destination = onNodeWithTag("text-input-destination")
            destination.performClick().performMouseInput { click(button = MouseButton.Secondary) }
            onNode(hasText("Paste") or hasText("粘贴")).performClick()
            destination.assertTextEquals("Bullet strong")
            source.performClick().performTextInputSelection(TextRange(8, 11))
            source.assertTextEquals("Bullet strong")
            source.assert(
                SemanticsMatcher.expectValue(
                    androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange,
                    TextRange(8, 11)
                )
            )
            source.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.C) } }
            runOnIdle {
                assertEquals(
                    "tro",
                    Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor)
                )
            }
            destination.performClick().performTextInputSelection(TextRange(rich.plainText.length))
            destination.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.V) } }
            destination.assertTextEquals("Bullet strongtro")
            save()
        }
        val saved = reopen(environment)
        val destination = saved.blocks.single { it.id.value == "destination" }
        assertEquals(BlockTypes.BULLETED_LIST, destination.type)
        val pasted = destination.editableRichText()!!
        assertEquals("strongtro", pasted.runs.last().text)
        assertEquals(
            setOf(InlineMarkTypes.BOLD, InlineMarkTypes.UNDERLINE),
            pasted.runs.last().marks.map(InlineMark::type).toSet()
        )
        assertTrue(NoteValidator.validate(saved).isEmpty())
    }

    /** 剪切后可以完整恢复列表；显式纯文本粘贴和外部剪贴板替换不会错误复用旧样式。 */
    @Test
    fun cutsAndRestoresThenPastesPlainTextWithoutStaleFormatting() = withEnvironment { environment ->
        prepare(environment, fixture())
        runDesktopComposeUiTest(width = 1280, height = 1100) {
            setContent { App(capabilities = environment.capabilities) }
            waitForBlock("ordered-one")
            val first = onNodeWithTag("text-input-ordered-one")
            first.performClick().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.A); pressKey(Key.X) } }
            first.assertTextEquals("")
            first.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.V) } }
            first.assertTextEquals("Unselected first")
            val destination = onNodeWithTag("text-input-destination")
            destination.performClick()
                .performKeyInput { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.V) } } }
            destination.assertTextContains("1. Unselected first\n2. Second strong", substring = true)
            runOnIdle {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(
                    StringSelection("External plain"),
                    null
                )
            }
            destination.performTextInputSelection(TextRange(0, "1. Unselected first".length))
            destination.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.V) } }
            save()
        }
        val saved = reopen(environment)
        val destination = saved.blocks.single { it.id.value == "destination" }
        assertEquals(BlockTypes.PARAGRAPH, destination.type)
        assertTrue(destination.selectionText()!!.startsWith("External plain\n2. Second strong"))
        assertTrue(destination.editableRichText()!!.runs.all { it.marks.isEmpty() })
        assertTrue(saved.blocks.any { it.deleted && it.id.value == "nested" })
        assertTrue(NoteValidator.validate(saved).isEmpty(), NoteValidator.validate(saved).toString())
    }

    /** @return 同时覆盖列表、嵌套、粗体、链接、待办和代码的合成笔记。 */
    private fun fixture(): Note = Note(
        "Clipboard example", listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID), blocks = listOf(
            textBlock("ordered-one", "Unselected first", "0", BlockTypes.NUMBERED_LIST),
            textBlock("ordered-two", "Second ", "1", BlockTypes.NUMBERED_LIST).copy(
                content = RichTextBlockContent(
                    RichText(
                        listOf(
                            InlineRun("Second "), InlineRun("strong", listOf(InlineMark(InlineMarkTypes.BOLD)))
                        )
                    )
                )
            ),
            textBlock("nested", "Nested item", "0", BlockTypes.BULLETED_LIST).copy(parentId = BlockId("ordered-two")),
            textBlock("ordered-three", "Third linked", "2", BlockTypes.NUMBERED_LIST).copy(
                content = RichTextBlockContent(
                    RichText(
                        listOf(
                            InlineRun("Third "),
                            InlineRun(
                                "linked",
                                listOf(InlineMark(InlineMarkTypes.LINK, mapOf("href" to "https://example.com/")))
                            )
                        )
                    )
                )
            ),
            Block(
                BlockId("todo"),
                BlockTypes.TODO,
                orderKey = "3",
                content = TodoBlockContent(true, RichText(listOf(InlineRun("Done"))))
            ),
            Block(
                BlockId("code"),
                BlockTypes.CODE,
                orderKey = "4",
                content = CodeBlockContent("kotlin", "val value = \"<tag>&\"")
            ),
            Block(BlockId("divider"), BlockTypes.DIVIDER, orderKey = "5", content = DividerBlockContent),
            textBlock("destination", "", "6"),
        )
    )

    /**
     * 建立不含真实业务内容的文本块。
     * @param id 合成身份。
     * @param text 合成文字。
     * @param order 顺序。
     * @param type 文本块类型。
     * @return 合成文本块。
     */
    private fun textBlock(id: String, text: String, order: String, type: String = BlockTypes.PARAGRAPH): Block =
        Block(BlockId(id), type, orderKey = order, content = RichTextBlockContent(RichText(listOf(InlineRun(text)))))

    /**
     * 保存合成笔记，再从生产 App 重新解锁。
     * @param environment 真实测试工作区。
     * @param note 合成笔记。
     */
    private fun prepare(environment: WorkspaceSettingsTestEnvironment, note: Note) = runBlocking {
        environment.gateway.openRecentWorkspace(); environment.gateway.saveNote(
        null,
        note,
        null
    ); environment.gateway.lock()
    }

    /**
     * 丢弃会话后重新解密笔记，验证实际持久化结果。
     * @param environment 已保存的真实工作区。
     * @return 重新解密读取的权威快照。
     */
    private fun reopen(environment: WorkspaceSettingsTestEnvironment): Note = runBlocking {
        environment.gateway.lock(); environment.gateway.openRecentWorkspace().notes.single().note
    }

    /** @param id 等待 App 解锁并显示的块。 */
    private fun ComposeUiTest.waitForBlock(id: String) {
        waitUntil(timeoutMillis = 15_000) { onAllNodesWithTag("text-input-$id").fetchSemanticsNodes().isNotEmpty() }
    }

    /** 通过生产菜单保存，并等待事务确认状态。 */
    private fun ComposeUiTest.save() {
        onNodeWithText("⋯").performClick()
        onNode(hasText("立即保存", substring = true) and hasClickAction()).performClick()
        waitUntil(timeoutMillis = 15_000) { onAllNodesWithText("已保存").fetchSemanticsNodes().isNotEmpty() }
    }

    /** @param scenario 使用真实加密存储与索引的验证场景。 */
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
