package com.oruke.tagloom

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.oruke.tagloom.capability.AndroidTagloomEnvironment
import com.oruke.tagloom.capability.TagloomCapabilities
import com.oruke.tagloom.capability.createAndroidTagloomCapabilities
import com.oruke.tagloom.core.index.SearchIndexQuery
import com.oruke.tagloom.core.model.BaseTableBlockContent
import com.oruke.tagloom.core.model.BaseTableColumnTypes
import com.oruke.tagloom.core.model.BaseTableTextCell
import com.oruke.tagloom.core.model.RichTextBlockContent
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.editor.pasteMarkdownAtBlock
import com.oruke.tagloom.editor.updateBaseTableCellText
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 在 Android 真实能力上验证共享粘贴到加密持久化的链路，不代替系统剪贴板 UI 验收。 */
@RunWith(AndroidJUnit4::class)
class AndroidMarkdownTablePasteTest {
    /** 粘贴并编辑 Markdown 表格和后续正文，重建能力容器后核对确认快照与搜索。 */
    @Test
    fun persistsPastedTableAndFollowingParagraphOnAndroid(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val area = AndroidTestArea()
        var capabilities: TagloomCapabilities? = null
        try {
            AndroidTagloomEnvironment.initialize(area.context)
            val first = createAndroidTagloomCapabilities(area.documents)
            capabilities = first
            assertNotNull(first.workspace.prepareCreation(null))
            val opened = first.workspace.confirmCreation()
            assertFalse(opened.readOnly)
            val editor = TagloomEditorState(first.workspace::createOpaqueId, first.workspace::currentUtcTimestamp)
            editor.loadWorkspaceDocuments(
                loadedDocuments = emptyList(),
                loadedTagDefinitions = opened.tagDefinitions.map { it.definition },
                loadedPropertyDefinitions = opened.propertyDefinitions.map { it.definition },
            )
            editor.createDocument()
            editor.updateTitle("Android 表格粘贴示例")
            val original = assertNotNull(editor.selectedDocument).note.blocks.single()

            // 与文本输入框调用相同的共享入口，粘贴必须生成表格并返回后续段落光标。
            val cursor = assertNotNull(editor.pasteMarkdownAtBlock(original.id, 0, 0, MARKDOWN))
            val pasted = assertNotNull(editor.selectedDocument).note
            val tableBlock = pasted.blocks.single { it.content is BaseTableBlockContent }
            val table = tableBlock.content as BaseTableBlockContent
            val columns = table.columns.filterNot { it.deleted }.sortedBy { it.orderKey }
            val row = table.rows.single()
            assertEquals(listOf("项目", "描述"), columns.map { it.name.plainText })
            assertTrue(columns.all { it.type == BaseTableColumnTypes.TEXT })
            assertTrue(editor.updateBaseTableCellText(tableBlock.id, row.rowId, columns[1].columnId, EDITED_CELL))
            assertEquals("", (pasted.blocks.single { it.id == cursor.blockId }.content as RichTextBlockContent).richText.plainText)
            editor.updateBlockText(cursor.blockId, FOLLOWING_TEXT)

            // 保存必须经过工作区网关的真实事务，磁盘不能出现粘贴正文或单元格明文。
            val expected = assertNotNull(editor.selectedDocument).note
            val saved = first.workspace.saveNote(null, expected)
            val envelope = Files.readAllBytes(area.documents.root.resolve("notes/${saved.id.value}.note.enc"))
            assertFalse(envelope.decodeToString().contains(EDITED_CELL))
            assertFalse(envelope.decodeToString().contains(FOLLOWING_TEXT))
            first.workspace.lock()
            first.syncEngine.close()

            // 重建能力容器后由 Keystore 重新取密钥，确认结果不依赖旧会话或 Compose 状态。
            val reopened = createAndroidTagloomCapabilities(area.documents)
            capabilities = reopened
            val recovered = reopened.workspace.openRecentWorkspace().notes.single()
            assertEquals(saved.id, recovered.id)
            assertEquals(expected, recovered.note)
            val recoveredTable = recovered.note.blocks.single { it.content is BaseTableBlockContent }.content as BaseTableBlockContent
            val recoveredCell = recoveredTable.rows.single().cells[columns[1].columnId] as BaseTableTextCell
            assertEquals(EDITED_CELL, recoveredCell.richText.plainText)
            val following = recovered.note.blocks.single { it.id == cursor.blockId }.content as RichTextBlockContent
            assertEquals(FOLLOWING_TEXT, following.richText.plainText)
            assertEquals(saved.id, reopened.searchEngine.search(SearchIndexQuery(text = FOLLOWING_TEXT)).single().noteId)
        } finally {
            capabilities?.workspace?.lock()
            capabilities?.syncEngine?.close()
            AndroidTagloomEnvironment.initialize(instrumentation.targetContext)
            area.close()
        }
    }

    private companion object {
        /** 单元格编辑后应跨会话保留的脱敏文本。 */
        const val EDITED_CELL = "android-table-edited"
        /** 用于校验后续段落持久化及实际全文索引的独立脱敏词。 */
        const val FOLLOWING_TEXT = "androidtablefollowup"
        /** 不带尾段落的 Markdown 表格，要求共享粘贴显式创建可编辑出口。 */
        val MARKDOWN = """
            | 项目 | 描述 |
            | --- | --- |
            | 示例 | android-table-cell |
        """.trimIndent()
    }
}
