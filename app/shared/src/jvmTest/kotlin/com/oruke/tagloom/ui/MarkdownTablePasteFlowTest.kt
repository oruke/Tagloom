package com.oruke.tagloom.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.asAwtTransferable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.TextRange
import com.oruke.tagloom.App
import com.oruke.tagloom.core.model.BaseTableBlockContent
import com.oruke.tagloom.core.model.BaseTableColumnTypes
import com.oruke.tagloom.core.model.BaseTableTextCell
import com.oruke.tagloom.core.model.RichTextBlockContent
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 从生产 App 的 Ctrl+V 入口验证 Markdown 表格转换、单元格编辑和加密保存。 */
@OptIn(ExperimentalTestApi::class)
class MarkdownTablePasteFlowTest {
    /** 用完整表格替换相同表头的选区，继续编辑正文，并重新解密核对最终权威快照。 */
    @Test
    fun pastesEditsAndReopensTableThroughProductionApp() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
            MarkdownTablePasteTestEnvironment().use { environment ->
                val clipboard = MarkdownPasteClipboard(MARKDOWN)
                runDesktopComposeUiTest(width = 1280, height = 900) {
                    setContent {
                        CompositionLocalProvider(LocalClipboard provides clipboard) {
                            App(capabilities = environment.capabilities)
                        }
                    }
                    waitUntil(timeoutMillis = 15_000) {
                        onAllNodes(hasText("新建笔记", substring = true) and hasClickAction())
                            .fetchSemanticsNodes().isNotEmpty()
                    }
                    onAllNodes(hasText("新建笔记", substring = true) and hasClickAction())[0].performClick()
                    onNode(hasSetTextAction() and hasText("未命名笔记")).performTextReplacement(NOTE_TITLE)

                    // 使用官方键盘粘贴路径读取隔离剪贴板，不把 SetText 当作 Ctrl+V 的替代证据。
                    val initialParagraphId = onNode(hasSetTextAction() and hasText("输入内容，按 / 选择块类型"))
                        .fetchSemanticsNode().id
                    val existingHeader = MARKDOWN.lineSequence().first()
                    // 旧选区与新文本有相同前缀时，也必须识别整段粘贴，不能误把表头排除在替换范围外。
                    val initialParagraph = onNode(SemanticsMatcher("新笔记正文输入框") { it.id == initialParagraphId })
                    initialParagraph.performClick().performTextInput(existingHeader)
                    initialParagraph.assertTextEquals(existingHeader)
                    initialParagraph.performTextInputSelection(TextRange(0, existingHeader.length))
                    initialParagraph.performKeyInput {
                            keyDown(Key.CtrlLeft)
                            pressKey(Key.V)
                            keyUp(Key.CtrlLeft)
                        }
                    waitUntil(timeoutMillis = 15_000) {
                        onAllNodes(hasSetTextAction() and hasText("样例甲")).fetchSemanticsNodes().isNotEmpty()
                    }
                    onNode(hasSetTextAction() and hasText("事项")).assertIsDisplayed()
                    onNode(hasSetTextAction() and hasText("说明")).assertIsDisplayed()

                    // 只有表格的粘贴也必须把焦点交给可写的后续段落，不能把输入吞到结构化 Block 上。
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().singleOrNull()
                            ?.config?.getOrNull(SemanticsProperties.EditableText)?.text == ""
                    }
                    onNode(hasSetTextAction() and isFocused()).performTextInput(AFTER_TABLE)
                    onNode(hasSetTextAction() and hasText("待处理")).performTextReplacement(EDITED_CELL)
                    onNode(hasSetTextAction() and hasText(AFTER_TABLE))
                        .performClick()
                        .performTextInputSelection(TextRange(AFTER_TABLE.length))
                    onNode(hasSetTextAction() and hasText(AFTER_TABLE)).performTextInput(CONTINUED_TEXT)
                    onNode(hasSetTextAction() and hasText(FINAL_PARAGRAPH)).assertIsDisplayed()
                    onNode(hasSetTextAction() and hasText(EDITED_CELL)).assertIsDisplayed()

                    onNodeWithText("⋯").performClick()
                    onNode(hasText("立即保存", substring = true) and hasClickAction()).performClick()
                    waitUntil(timeoutMillis = 15_000) {
                        onAllNodesWithText("已保存").fetchSemanticsNodes().isNotEmpty()
                    }
                    saveTableScreenshot()
                }

                // 释放原会话再从确认快照解密，避免 UI 内存状态或索引缓存掩盖保存问题。
                val saved = runBlocking {
                    environment.gateway.lock()
                    environment.gateway.openRecentWorkspace().notes.single()
                }
                assertEquals(NOTE_TITLE, saved.note.title)
                val table = saved.note.blocks.map { it.content }.filterIsInstance<BaseTableBlockContent>().single()
                val columns = table.columns.filterNot { it.deleted }.sortedBy { it.orderKey }
                val rows = table.rows.filterNot { it.deleted }.sortedBy { it.orderKey }
                assertEquals(listOf("事项", "说明"), columns.map { it.name.plainText })
                assertTrue(columns.all { it.type == BaseTableColumnTypes.TEXT })
                assertEquals(
                    listOf(listOf("样例甲", EDITED_CELL), listOf("样例乙", "已整理")),
                    rows.map { row -> columns.map { column -> (row.cells[column.columnId] as BaseTableTextCell).richText.plainText } },
                )
                val tableIndex = saved.note.blocks.indexOfFirst { it.content is BaseTableBlockContent }
                assertEquals(0, tableIndex)
                val followingParagraph = saved.note.blocks[tableIndex + 1].content as RichTextBlockContent
                assertEquals(FINAL_PARAGRAPH, followingParagraph.richText.plainText)
                val envelope = Files.readAllBytes(environment.workspaceRoot.resolve("notes/${saved.id.value}.note.enc"))
                assertFalse(envelope.decodeToString().contains(EDITED_CELL))
                assertFalse(envelope.decodeToString().contains(FINAL_PARAGRAPH))
            }
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    /** 保存最终真实渲染窗口，供人工核对表格及后续正文的可编辑布局。 */
    private fun ComposeUiTest.saveTableScreenshot() {
        val directory = Path.of("build", "reports", "markdown-table-paste")
        Files.createDirectories(directory)
        Image.makeFromBitmap(onAllNodes(isRoot())[0].captureToImage().asSkiaBitmap()).use { image ->
            checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { data ->
                Files.write(directory.resolve("markdown-table-desktop.png"), data.bytes)
            }
        }
    }

    private companion object {
        /** 用于真实存储闭环的脱敏笔记标题。 */
        const val NOTE_TITLE = "Markdown 表格粘贴示例"
        /** 表格粘贴后直接输入的正文。 */
        const val AFTER_TABLE = "表后仍可输入。"
        /** 编辑单元格后继续输入的正文。 */
        const val CONTINUED_TEXT = "继续编辑正文。"
        /** 最终快照应保留的完整正文。 */
        const val FINAL_PARAGRAPH = AFTER_TABLE + CONTINUED_TEXT
        /** 用于证明单元格真实编辑并保存的脱敏结果。 */
        const val EDITED_CELL = "已通过粘贴编辑"
        /** 只有表格、没有末尾段落的 Markdown，覆盖结构化粘贴的光标出口。 */
        val MARKDOWN = """
            | 事项 | 说明 |
            | --- | --- |
            | 样例甲 | 待处理 |
            | 样例乙 | 已整理 |
        """.trimIndent()
    }
}

/**
 * 提供只属于当前测试的剪贴板，官方文本输入仍执行真实读取与键盘粘贴流程。
 * @param markdown 用户复制的脱敏 Markdown 文本。
 */
@OptIn(ExperimentalComposeUiApi::class)
private class MarkdownPasteClipboard(markdown: String) : Clipboard {
    /** 独立 AWT 剪贴板同时支持官方输入和上下文菜单查询，绝不访问系统剪贴板。 */
    private val clipboard = java.awt.datatransfer.Clipboard("markdown-paste-test").apply {
        setContents(StringSelection(markdown), null)
    }
    /** 向 Compose 平台扩展提供同一个隔离 AWT 实例。 */
    @Suppress("OVERRIDE_DEPRECATION")
    override val nativeClipboard: Any
        get() = clipboard

    /** @return 供官方 Ctrl+V 操作读取的隔离文本条目。 */
    override suspend fun getClipEntry(): ClipEntry? = clipboard.getContents(null)?.let(::ClipEntry)

    /**
     * 保留官方文本编辑操作可能产生的新剪贴板内容。
     * @param clipEntry 新条目；空值表示清空本测试剪贴板。
     */
    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        clipboard.setContents(clipEntry?.asAwtTransferable, null)
    }
}
