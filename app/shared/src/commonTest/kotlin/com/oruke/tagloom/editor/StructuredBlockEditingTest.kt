package com.oruke.tagloom.editor

import com.oruke.tagloom.core.model.BaseTableBlockContent
import com.oruke.tagloom.core.model.BaseTableColumnTypes
import com.oruke.tagloom.core.model.BaseTableTextCell
import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.DatabaseBlockContent
import com.oruke.tagloom.core.model.DatabaseId
import com.oruke.tagloom.core.model.NoteValidator
import com.oruke.tagloom.core.model.TabBlockContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 验证结构化 Block 从编辑命令到 Note 快照的完整状态流转。
 */
class StructuredBlockEditingTest {
    /** 验证异步 Database 事务完成后会按原文档和 Block ID 原位绑定，不依赖当前选择。 */
    @Test
    fun bindsDatabaseReferenceToOriginalCommandBlock() {
        val state = testState()
        state.createDocument()
        val originalDocument = state.selectedDocument!!
        val originalBlockId = originalDocument.note.blocks.single().id
        state.createDocument()
        val activeDocumentId = state.selectedDocumentId

        assertTrue(
            state.bindDatabaseBlock(
                documentId = originalDocument.id,
                blockId = originalBlockId,
                databaseId = DatabaseId("database-main"),
            ),
        )

        val updated = state.documents.single { document -> document.id == originalDocument.id }
        val block = updated.note.blocks.single()
        assertEquals(BlockTypes.DATABASE, block.type)
        assertEquals(DatabaseId("database-main"), assertIs<DatabaseBlockContent>(block.content).databaseId)
        assertTrue(updated.dirty)
        assertEquals(activeDocumentId, state.selectedDocumentId)
    }

    /**
     * 验证 Slash 同源命令创建 BaseTable 后，标题、列、行和单元格编辑都落入当前 Note。
     */
    @Test
    fun editsInlineTableInsideCurrentNote() {
        val state = testState()
        state.createDocument()
        val sourceId = state.selectedDocument!!.note.blocks.single().id

        assertNotNull(state.turnBlockInto(sourceId, EditorBlockType.BASE_TABLE))
        var table = assertIs<BaseTableBlockContent>(state.selectedDocument!!.note.blocks.single().content)
        val rowId = table.rows.first().rowId
        val columnId = table.columns.first().columnId

        assertTrue(state.updateBaseTableTitle(sourceId, "里程碑"))
        assertTrue(state.updateBaseTableCellText(sourceId, rowId, columnId, "设计完成"))
        assertTrue(state.addBaseTableColumn(sourceId))
        table = assertIs(state.selectedDocument!!.note.blocks.single().content)
        val addedColumn = table.columns.last()
        assertTrue(state.changeBaseTableColumnType(sourceId, addedColumn.columnId, BaseTableColumnTypes.BOOLEAN))

        table = assertIs(state.selectedDocument!!.note.blocks.single().content)
        assertEquals("里程碑", table.title.plainText)
        assertEquals(
            "设计完成",
            assertIs<BaseTableTextCell>(table.rows.first().cells.getValue(columnId)).richText.plainText,
        )
        assertEquals(3, table.columns.count { column -> !column.deleted })
        assertTrue(NoteValidator.validate(state.selectedDocument!!.note).isEmpty())
    }

    /**
     * 验证活动页签只属于 UI 会话，默认页签显式持久化，删除时默认解包正文。
     */
    @Test
    fun switchesTabsAndUnpacksDeletedTabContent() {
        val state = testState()
        state.createDocument()
        val sourceId = state.selectedDocument!!.note.blocks.single().id
        state.updateBlockText(sourceId, "第一页正文")

        assertNotNull(state.turnBlockInto(sourceId, EditorBlockType.TABS))
        val note = state.selectedDocument!!.note
        val tabs = note.blocks.single { block -> block.type == BlockTypes.TABS }
        val tabBlocks = note.blocks
            .filter { block -> block.type == BlockTypes.TAB && !block.deleted }
            .sortedBy { block -> block.orderKey }
        val secondTab = tabBlocks.last()

        state.selectTab(tabs.id, secondTab.id)
        state.renameTab(secondTab.id, "资料")
        state.setDefaultTab(tabs.id, secondTab.id)

        assertEquals(secondTab.id, state.activeTabId(tabs.id))
        assertEquals(secondTab.id, state.selectedDocument!!.note.defaultTabId)
        assertEquals("资料", assertIs<TabBlockContent>(state.selectedDocument!!.note.blocks.single { it.id == secondTab.id }.content).title.plainText)
        assertTrue(state.visibleEditorBlocks(state.selectedDocument!!.note).none { block -> block.id == sourceId })

        state.deleteTab(tabs.id, secondTab.id, cascade = false)
        val unpacked = state.selectedDocument!!.note

        assertTrue(unpacked.blocks.single { block -> block.id == secondTab.id }.deleted)
        assertTrue(unpacked.blocks.any { block -> !block.deleted && block.parentId == tabs.parentId && block.id != tabs.id })
        assertTrue(NoteValidator.validate(unpacked).isEmpty())
    }

    /**
     * 验证明确级联删除最后一个页签后仍补回可写正文，避免页面进入不可编辑空状态。
     */
    @Test
    fun cascadingLastTabLeavesWritableParagraph() {
        val state = testState()
        state.createDocument()
        val sourceId = state.selectedDocument!!.note.blocks.single().id
        state.turnBlockInto(sourceId, EditorBlockType.TABS)
        val note = state.selectedDocument!!.note
        val tabs = note.blocks.single { block -> block.type == BlockTypes.TABS }
        val tabIds = note.blocks.filter { block -> block.type == BlockTypes.TAB }.map { block -> block.id }

        tabIds.forEach { tabId -> state.deleteTab(tabs.id, tabId, cascade = true) }

        val active = state.visibleEditorBlocks(state.selectedDocument!!.note)
        assertEquals(1, active.size)
        assertEquals(BlockTypes.PARAGRAPH, active.single().type)
        assertTrue(NoteValidator.validate(state.selectedDocument!!.note).isEmpty())
    }

    /**
     * 构造使用确定性 ID 和时间的编辑状态。
     *
     * @return 空编辑器状态。
     */
    private fun testState(): TagloomEditorState {
        var counter = 0
        return TagloomEditorState(
            idFactory = { prefix -> "$prefix-${counter++}" },
            timestampProvider = { "2026-08-10T00:00:00Z" },
        )
    }
}
