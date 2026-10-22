package com.oruke.tagloom.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import com.oruke.tagloom.App
import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import com.oruke.tagloom.editor.BlockTreeReducer
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.math.abs
import kotlin.test.*

/** 从真实 App 的指针事件到加密快照验证手柄命中、菜单迁移、拖动动画和取消。 */
@OptIn(ExperimentalTestApi::class)
class BlockHandleFlowTest {
    /** 宽窄窗口从真实 App 核对不同文本块的首行对齐、完整命中区及菜单入口。 */
    @Test
    fun alignsHandlesWithActualFirstTextLineAcrossWindowSizes() {
        val note = fixture().copy(blocks = listOf(
            paragraph("empty", "", 0).copy(content = RichTextBlockContent(RichText())),
            paragraph("plain", "Single paragraph", 1),
            paragraph("multiline", "First line\nSecond line\nThird line", 2),
            *listOf(1, 2, 3).map { level ->
                paragraph("heading-$level", "Heading $level", level + 2).copy(
                    type = BlockTypes.HEADING,
                    content = HeadingBlockContent(level, RichText(listOf(InlineRun("Heading $level"))))
                )
            }.toTypedArray(),
            paragraph("bulleted", "Bulleted item\nContinued item", 6).copy(type = BlockTypes.BULLETED_LIST),
            paragraph("numbered", "Numbered item", 7).copy(type = BlockTypes.NUMBERED_LIST),
            paragraph("todo", "Task item", 8).copy(
                type = BlockTypes.TODO,
                content = TodoBlockContent(false, RichText(listOf(InlineRun("Task item"))))
            ),
            paragraph("quote", "Quoted first line\nQuoted second line", 9).copy(type = BlockTypes.QUOTE),
        ))
        for (width in listOf(1280, 480)) {
            withEnvironment(Locale.SIMPLIFIED_CHINESE) { environment ->
                prepare(environment, note)
                runDesktopComposeUiTest(width = width, height = 900) {
                    setContent { App(capabilities = environment.capabilities) }
                    waitForBlock("empty")
                    note.blocks.forEachIndexed { index, block ->
                        // 保留前一个列表项，避免手柄向上扩展的命中区被视口边缘裁切。
                        onNodeWithTag("editor-block-list").performScrollToIndex(index)
                        onNodeWithTag("text-input-${block.id.value}").performMouseInput {
                            click(Offset(4f, 4f))
                        }
                        waitForIdle()
                        val layout = assertFirstLineHandleAlignment(block.id.value)
                        if (block.id.value in setOf("multiline", "bulleted", "quote")) {
                            assertTrue(layout.lineCount > 1, "${block.id.value} must exercise a multiline field")
                        }
                        if (block.id.value in setOf("empty", "multiline")) {
                            screenshot("first-line-$width-${block.id.value}.png")
                        }
                        val bounds = onNodeWithTag("block-handle-${block.id.value}")
                            .fetchSemanticsNode().boundsInRoot
                        root().performMouseInput { click(Offset(bounds.left + 2f, bounds.top + 2f)) }
                        onNodeWithText("在下方插入").assertIsDisplayed()
                        onNodeWithText("在下方插入").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                            .performKeyInput { pressKey(Key.Escape) }
                        onNodeWithText("在下方插入").assertDoesNotExist()
                    }
                    onNodeWithTag("editor-block-list").performScrollToIndex(0)
                    save()
                }
                val saved = runBlocking {
                    environment.gateway.lock()
                    environment.gateway.openRecentWorkspace().notes.single().note
                }
                assertEquals(note.blocks, saved.blocks, "Focusing text and opening menus must preserve blocks")
            }
        }
    }

    /** 不同高度的块可直接拖动，父子一起移动；Esc 恢复原位且重开仍保持确认顺序。 */
    @Test
    fun dragsSubtreesCancelsAndPersistsThroughProductionApp() =
        withEnvironment(Locale.SIMPLIFIED_CHINESE) { environment ->
            prepare(environment)
            runDesktopComposeUiTest(width = 1280, height = 900) {
                setContent { App(capabilities = environment.capabilities) }
                waitForBlock("first")
                val handle = onNodeWithTag("block-handle-first")
                val bounds = handle.fetchSemanticsNode().boundsInRoot
                assertTrue(bounds.width >= 32f && bounds.height >= 32f)
                // 从文字移到手柄外侧边缘，验证真实命中区域，不使用语义 performClick 绕过布局问题。
                onNodeWithTag("text-input-first").performMouseInput { moveTo(center) }
                root().performMouseInput { click(Offset(bounds.left + 2f, bounds.center.y)) }
                onNodeWithText("在下方插入").assertIsDisplayed()
                screenshot("handle-menu-desktop.png")
                onNodeWithText("在下方插入").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                    .performKeyInput { pressKey(Key.Escape) }
                onNodeWithText("在下方插入").assertDoesNotExist()

                val from = handle.fetchSemanticsNode().boundsInRoot.center
                val to = Offset(from.x, row("third").bottom - 3f)
                startDrag(from, to)
                onNodeWithTag("block-drop-indicator").assertExists()
                val draggingY = onNodeWithTag("text-input-first").fetchSemanticsNode().boundsInRoot.top
                assertTrue(draggingY > row("second").top)
                screenshot("drag-preview-desktop.png")
                // 冻结时钟检查中间帧：松开后应连续落位，不是一帧跳到目标。
                mainClock.autoAdvance = false
                root().performMouseInput { release() }
                mainClock.advanceTimeByFrame()
                val releasedY = onNodeWithTag("text-input-first").fetchSemanticsNode().boundsInRoot.top
                assertTrue(abs(releasedY - draggingY) < 18f, "Dropping must preserve the visual position")
                mainClock.advanceTimeBy(80)
                screenshot("drag-settling-desktop.png")
                mainClock.advanceTimeBy(260)
                mainClock.autoAdvance = true
                assertOrder("second", "child", "third", "first", "fourth")
                onNodeWithTag("block-drop-indicator").assertDoesNotExist()
                onNodeWithText("在下方插入").assertDoesNotExist()

                onNodeWithTag("text-input-first").performClick()
                val cancelFrom = onNodeWithTag("block-handle-third").fetchSemanticsNode().boundsInRoot.center
                startDrag(cancelFrom, Offset(cancelFrom.x, row("second").top + 3f))
                onNodeWithTag("text-input-first").performKeyInput { pressKey(Key.Escape) }
                root().performMouseInput { release() }
                waitForIdle()
                assertOrder("second", "child", "third", "first", "fourth")

                val parentFrom = onNodeWithTag("block-handle-second").fetchSemanticsNode().boundsInRoot.center
                val childBefore = onNodeWithTag("text-input-child").fetchSemanticsNode().boundsInRoot.top
                startDrag(parentFrom, Offset(parentFrom.x, row("fourth").bottom - 3f))
                val parentShift =
                    onNodeWithTag("text-input-second").fetchSemanticsNode().boundsInRoot.top - row("second").top
                val childShift = onNodeWithTag("text-input-child").fetchSemanticsNode().boundsInRoot.top - childBefore
                assertTrue(abs(parentShift - childShift) < 2f, "A subtree must move together")
                root().performMouseInput { release() }
                waitForIdle()
                assertOrder("third", "first", "fourth", "second", "child")
                screenshot("drag-complete-desktop.png")
                save()
            }
            val saved = runBlocking {
                environment.gateway.lock()
                environment.gateway.openRecentWorkspace().notes.single().note
            }
            assertEquals(
                listOf("third", "first", "fourth", "second", "child"),
                BlockTreeReducer.orderedActive(saved.blocks).map { it.id.value })
            assertEquals(BlockId("second"), saved.blocks.single { it.id.value == "child" }.parentId)
            assertEquals(
                fixture().blocks.associate { it.id to it.content },
                saved.blocks.associate { it.id to it.content })
        }

    /** 窄屏英文界面的手柄不越界，原新增功能从菜单创建代码块并真实保存。 */
    @Test
    fun insertsFromHandleOnCompactScreen() = withEnvironment(Locale.ENGLISH) { environment ->
        prepare(environment)
        runDesktopComposeUiTest(width = 480, height = 900) {
            setContent { App(capabilities = environment.capabilities) }
            waitForBlock("first")
            val handle = onNodeWithTag("block-handle-first")
            val bounds = handle.fetchSemanticsNode().boundsInRoot
            val pane = onNodeWithTag("editor-pane").fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.left >= pane.left && bounds.right < pane.right)
            handle.performMouseInput { moveTo(center); click() }
            onNodeWithText("Insert below").assertIsDisplayed()
            screenshot("handle-menu-compact.png")
            onNodeWithText("Insert below").performClick()
            onNodeWithText("Code block", substring = false).performClick()
            waitUntil(timeoutMillis = 15_000) {
                onAllNodesWithContentDescription("Code language").fetchSemanticsNodes().isNotEmpty()
            }
            screenshot("handle-insert-compact.png")
            save(english = true)
        }
        val saved = runBlocking {
            environment.gateway.lock()
            environment.gateway.openRecentWorkspace().notes.single().note
        }
        val ordered = BlockTreeReducer.orderedActive(saved.blocks)
        assertEquals("first", ordered.first().id.value)
        assertTrue(ordered[1].content is CodeBlockContent)
        assertEquals("second", ordered[2].id.value)
        assertEquals(6, ordered.size)
    }

    /** 越过视口边缘后文档持续滚动，源手柄不被回收，松开仍能确认远处落点。 */
    @Test
    fun dragsAcrossScrollingViewport() = withEnvironment(Locale.SIMPLIFIED_CHINESE) { environment ->
        prepare(environment, fixture().copy(blocks = (0 until 30).map { paragraph("row-$it", "Paragraph $it", it) }))
        runDesktopComposeUiTest(width = 1000, height = 640) {
            setContent { App(capabilities = environment.capabilities) }
            waitForBlock("row-0")
            val from = onNodeWithTag("block-handle-row-0").fetchSemanticsNode().boundsInRoot.center
            val pane = onNodeWithTag("editor-pane").fetchSemanticsNode().boundsInRoot
            mainClock.autoAdvance = false
            startDrag(from, Offset(from.x, pane.bottom - 8f))
            mainClock.advanceTimeBy(900)
            onNodeWithTag("block-handle-row-10").assertExists()
            onNodeWithTag("block-handle-row-0").assertExists()
            screenshot("drag-scroll-preview.png")
            root().performMouseInput { release() }
            mainClock.advanceTimeBy(260)
            mainClock.autoAdvance = true
            waitForIdle()
            screenshot("drag-scroll-release.png")
            onNodeWithTag("text-input-row-0").assertIsDisplayed()
            onNodeWithTag("editor-block-list").performScrollToIndex(0)
            save()
        }
        val saved = runBlocking {
            environment.gateway.lock()
            environment.gateway.openRecentWorkspace().notes.single().note
        }
        val ordered = BlockTreeReducer.orderedActive(saved.blocks)
        assertEquals(30, ordered.size)
        assertTrue(ordered.indexOfFirst { it.id.value == "row-0" } > 7)
        assertEquals((1 until 30).map { "row-$it" }, ordered.filterNot { it.id.value == "row-0" }.map { it.id.value })
    }

    /** 页签内重排只移动该页正文，父容器不重复平移，保存后父边保持一致。 */
    @Test
    fun reordersInsideTabWithoutMovingContainer() = withEnvironment(Locale.SIMPLIFIED_CHINESE) { environment ->
        val tabs = Block(BlockId("tabs"), BlockTypes.TABS, orderKey = "0", content = TabsBlockContent)
        val tab = Block(
            BlockId("page"), BlockTypes.TAB, parentId = tabs.id, orderKey = "0",
            content = TabBlockContent(RichText(listOf(InlineRun("Page example"))))
        )
        prepare(
            environment, fixture().copy(
                blocks = listOf(
                    tabs, tab,
                    paragraph("first", "First tab paragraph", 0).copy(parentId = tab.id),
                    paragraph("second", "Second tab paragraph", 1).copy(parentId = tab.id)
                )
            )
        )
        runDesktopComposeUiTest(width = 1280, height = 900) {
            setContent { App(capabilities = environment.capabilities) }
            waitForBlock("first")
            val containerTop = row("tabs").top
            val from = onNodeWithTag("block-handle-second").fetchSemanticsNode().boundsInRoot.center
            startDrag(from, Offset(from.x, row("first").top + 2f))
            assertEquals(containerTop, row("tabs").top)
            root().performMouseInput { release() }
            waitForIdle()
            assertOrder("second", "first")
            save()
        }
        val saved = runBlocking {
            environment.gateway.lock()
            environment.gateway.openRecentWorkspace().notes.single().note
        }
        assertEquals(
            listOf("tabs", "page", "second", "first"),
            BlockTreeReducer.orderedActive(saved.blocks).map { it.id.value })
        assertEquals(tab.id, saved.blocks.single { it.id.value == "first" }.parentId)
        assertEquals(tab.id, saved.blocks.single { it.id.value == "second" }.parentId)
    }

    /**
     * 从真实事务准备可复验的不同高度父子块。
     * @param environment 独立真实工作区。
     * @param note 当前场景使用的合成笔记。
     */
    private fun prepare(environment: WorkspaceSettingsTestEnvironment, note: Note = fixture()) = runBlocking {
        environment.gateway.openRecentWorkspace()
        environment.gateway.saveNote(null, note, null)
        environment.gateway.lock()
    }

    /** @return 含多行父块及一个子块的合成笔记。 */
    private fun fixture(): Note = Note(
        "Block movement example", listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID), blocks = listOf(
            paragraph("first", "First paragraph", 0),
            paragraph(
                "second",
                "Second paragraph\nA second line makes this block taller\nA third line keeps the drop geometry realistic",
                1
            ),
            paragraph("child", "Nested paragraph", 0).copy(parentId = BlockId("second")),
            paragraph("third", "Third paragraph", 2),
            paragraph("fourth", "Fourth paragraph", 3),
        )
    )

    /**
     * 构造保留稳定身份的正文块。
     * @param id 测试 ID。
     * @param text 脱敏正文。
     * @param order 同级顺序。
     * @return 可由生产 App 正常编辑的段落。
     */
    private fun paragraph(id: String, text: String, order: Int): Block = Block(
        BlockId(id), BlockTypes.PARAGRAPH,
        orderKey = order.toString().padStart(10, '0'), content = RichTextBlockContent(RichText(listOf(InlineRun(text))))
    )

    /** @return 当前应用主窗口，排除菜单 Popup 的独立根。 */
    private fun ComposeUiTest.root(): SemanticsNodeInteraction = onAllNodes(isRoot())[0]

    /** @param id 目标行的稳定 ID。 @return 未变换的行布局范围。 */
    private fun ComposeUiTest.row(id: String) = onNodeWithTag("block-row-$id").fetchSemanticsNode().boundsInRoot

    /**
     * 用原生输入框真实排版核对手柄首行中心，并确认按钮保持完整桌面命中尺寸。
     * @param id 当前文本块的稳定 ID。
     * @return 供调用方确认多行场景有效的实际文本排版。
     */
    private fun ComposeUiTest.assertFirstLineHandleAlignment(id: String): TextLayoutResult {
        val field = onNodeWithTag("text-input-$id")
        val layouts = mutableListOf<TextLayoutResult>()
        field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val firstLineCenter = field.fetchSemanticsNode().boundsInRoot.top +
            (layout.getLineTop(0) + layout.getLineBottom(0)) / 2f
        val handle = onNodeWithTag("block-handle-$id")
        val bounds = handle.fetchSemanticsNode().boundsInRoot
        assertEquals(firstLineCenter, bounds.center.y, 1f, "$id handle must align to the actual first line")
        val uncutBounds = handle.getUnclippedBoundsInRoot()
        assertTrue((uncutBounds.right - uncutBounds.left).value >= 32f &&
            (uncutBounds.bottom - uncutBounds.top).value >= 32f,
            "$id must retain a 32 dp click and drag target")
        return layout
    }

    /**
     * 使用真实鼠标按压并越过触发阈值，不模拟长按。
     * @param from 手柄中心的窗口坐标。
     * @param to 目标位置的窗口坐标。
     */
    private fun ComposeUiTest.startDrag(from: Offset, to: Offset) {
        root().performMouseInput {
            moveTo(from)
            press()
            moveTo(from + Offset(0f, if (to.y > from.y) 20f else -20f))
            moveTo(to)
        }
        waitForIdle()
    }

    /** @param ids 预期先序行序列；核对真实布局而非仅内存列表。 */
    private fun ComposeUiTest.assertOrder(vararg ids: String) {
        val positions = ids.map { row(it).top }
        assertEquals(positions.sorted(), positions)
    }

    /** @param id 等待生产 App 解锁并展示的 Block。 */
    private fun ComposeUiTest.waitForBlock(id: String) {
        waitUntil(timeoutMillis = 15_000) { onAllNodesWithTag("block-handle-$id").fetchSemanticsNodes().isNotEmpty() }
    }

    /** @param english 是否按英文资源触发真实保存。 */
    private fun ComposeUiTest.save(english: Boolean = false) {
        onNodeWithText("⋯").performClick()
        onNode(hasText(if (english) "Save now" else "立即保存", substring = true) and hasClickAction()).performClick()
        waitUntil(timeoutMillis = 15_000) {
            onAllNodesWithText(if (english) "Saved" else "已保存").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** @param filename 保存到忽略的构建目录、供人工核对的截图名称。 */
    private fun ComposeUiTest.screenshot(filename: String) {
        val directory = Path.of("build", "reports", "block-handle-ui")
        Files.createDirectories(directory)
        Image.makeFromBitmap(root().captureToImage().asSkiaBitmap()).use { image ->
            image.encodeToData(EncodedImageFormat.PNG)!!.use { Files.write(directory.resolve(filename), it.bytes) }
        }
    }

    /**
     * 使用真实加密服务运行本地化流程并恢复测试进程语言。
     * @param locale 本次场景语言。
     * @param scenario 从 App 到最终快照的验证。
     */
    private fun withEnvironment(locale: Locale, scenario: (WorkspaceSettingsTestEnvironment) -> Unit) {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(locale)
            WorkspaceSettingsTestEnvironment().use(scenario)
        } finally {
            Locale.setDefault(previous)
        }
    }
}
