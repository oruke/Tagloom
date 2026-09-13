package com.oruke.tagloom.editor.search

import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.editor.*
import kotlin.test.*

/** 验证查找通过生产编辑状态读取当前草稿，保留文本偏移和文档写入边界。 */
class NoteSearchFlowTest {
    /** 跨 Run、中文和 emoji 都按原文 UTF-16 匹配，字面量大小写与空词规则一致。 */
    @Test
    fun searchesOriginalOffsetsAndLiveDraft() {
        val editor = editor()
        val search = NoteSearchState(editor)
        search.open()
        search.updateQuery("needle")
        assertEquals(8, search.matches.size)
        assertEquals(listOf(0, 7), search.matches.filter { it.target.blockId == BlockId("rich") }.map { it.start })
        search.toggleCase()
        assertEquals(7, search.matches.size)
        search.updateQuery("🌿")
        assertEquals(2, search.matches.size)
        assertTrue(search.matches.all { it.end - it.start == 2 })
        search.updateQuery("隐藏页签")
        assertEquals(1, search.matches.size)
        editor.updateBlockText(BlockId("hidden"), "实时草稿")
        assertTrue(search.matches.isEmpty())
        search.updateQuery("实时")
        assertEquals(1, search.matches.size)
        search.updateQuery("")
        assertTrue(search.matches.isEmpty())
        search.updateQuery(" ")
        assertTrue(search.matches.isNotEmpty())
    }

    /** 选区填入、循环导航和打开嵌套页签只改变查找会话，不改变笔记或脏标记。 */
    @Test
    fun navigatesHiddenTabsWithoutChangingTheNote() {
        val editor = editor()
        val before = editor.selectedDocument
        editor.updateSelection(BlockId("rich"), 6, 0)
        val search = NoteSearchState(editor)
        search.open()
        assertEquals("Needle", search.query)
        assertEquals(0, editor.selection.start)
        assertEquals(6, editor.selection.end)
        search.move(backwards = true)
        assertEquals(search.matches.lastIndex, search.activeIndex)
        search.move()
        assertEquals(0, search.activeIndex)
        search.updateQuery("隐藏页签")
        search.revealTabs()
        assertEquals(BlockId("second-tab"), editor.activeTabId(BlockId("tabs")))
        assertEquals(BlockId("nested-second"), editor.activeTabId(BlockId("nested-tabs")))
        assertEquals(BlockId("tabs"), search.activeText?.rootBlockId)
        assertEquals(before, editor.selectedDocument)
        search.close()
        assertEquals("", search.query)
        assertTrue(search.matches.isEmpty())
        assertEquals(before, editor.selectedDocument)
    }

    /** 删除的父节点、行列以及未显示的表格标题不能产生无法跳转的结果。 */
    @Test
    fun excludesDeletedAndNonDisplayedTableContent() {
        val note = noteSearchTestNote()
        val changed = note.copy(blocks = note.blocks.map { block ->
            when (val content = block.content) {
                is BaseTableBlockContent -> block.copy(
                    content = content.copy(
                        title = searchTestText("invisible"),
                        columns = content.columns + content.columns.last().copy(
                            columnId = BaseTableColumnId("deleted"),
                            deleted = true,
                            name = searchTestText("invisible")
                        ),
                        rows = content.rows + content.rows.single().copy(
                            rowId = BaseTableRowId("deleted"),
                            deleted = true,
                            cells = mapOf(content.columns.first().columnId to BaseTableTextCell(searchTestText("invisible")))
                        ),
                    )
                )

                else -> if (block.id == BlockId("tabs")) block.copy(deleted = true) else block
            }
        })
        val texts = NoteSearchIndex.texts(changed)
        assertTrue(NoteSearchIndex.find(texts, "invisible", false).isEmpty())
        assertTrue(NoteSearchIndex.find(texts, "隐藏页签", false).isEmpty())
        assertEquals(7, NoteSearchIndex.find(texts, "needle", false).size)
    }

    /**
     * 用生产草稿操作装配本地查找场景。
     * @return 载入脱敏笔记的编辑状态。
     */
    private fun editor(): TagloomEditorState {
        var sequence = 0
        return TagloomEditorState(
            idFactory = { prefix -> "$prefix-${sequence++}" },
            timestampProvider = { "2026-01-01T00:00:00Z" },
        ).apply {
            createDocument()
            updateSelectedNote { noteSearchTestNote() }
        }
    }
}
