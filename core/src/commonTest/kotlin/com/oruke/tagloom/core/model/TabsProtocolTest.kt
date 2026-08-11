package com.oruke.tagloom.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 验证 Tabs/Tab 的父子结构、默认页签和叶子 Block 约束。
 */
class TabsProtocolTest {
    /**
     * 验证两个页签及其正文构成合法 Note，默认页签只指向稳定 tab ID。
     */
    @Test
    fun validTabsTreePassesValidation() {
        val tabsId = BlockId("tabs-main")
        val firstTabId = BlockId("tab-first")
        val note = Note(
            title = "页签示例",
            directTagIds = emptyList(),
            defaultTabId = firstTabId,
            blocks = listOf(
                block(tabsId, BlockTypes.TABS, null, "a0", TabsBlockContent),
                block(firstTabId, BlockTypes.TAB, tabsId, "a0", TabBlockContent(richText("概览"))),
                block(BlockId("tab-second"), BlockTypes.TAB, tabsId, "a1", TabBlockContent(richText("详情"))),
                block(
                    BlockId("paragraph-first"),
                    BlockTypes.PARAGRAPH,
                    firstTabId,
                    "a0",
                    RichTextBlockContent(richText("只属于概览页签")),
                ),
            ),
        )

        assertTrue(NoteValidator.validate(note).isEmpty())
        assertEquals(firstTabId, note.defaultTabId)
    }

    /**
     * 验证非法直接子节点、游离 tab、BaseTable 子节点和失效默认页签均被明确定位。
     */
    @Test
    fun invalidStructuredTreeReportsStableCodes() {
        val tabsId = BlockId("tabs-main")
        val tableId = BlockId("table-one")
        var rowIndex = 0
        var columnIndex = 0
        val table = BaseTableEditor.createDefault(
            rowIdFactory = { BaseTableRowId("row-${rowIndex++}") },
            columnIdFactory = { BaseTableColumnId("column-${columnIndex++}") },
        )
        val note = Note(
            title = "损坏结构",
            directTagIds = emptyList(),
            defaultTabId = BlockId("missing-tab"),
            blocks = listOf(
                block(tabsId, BlockTypes.TABS, null, "a0", TabsBlockContent),
                block(
                    BlockId("invalid-direct-child"),
                    BlockTypes.PARAGRAPH,
                    tabsId,
                    "a0",
                    RichTextBlockContent(richText("错误")),
                ),
                block(BlockId("orphan-tab"), BlockTypes.TAB, null, "a1", TabBlockContent(richText("游离"))),
                block(tableId, BlockTypes.BASE_TABLE, null, "a2", table),
                block(
                    BlockId("table-child"),
                    BlockTypes.PARAGRAPH,
                    tableId,
                    "a0",
                    RichTextBlockContent(richText("错误子节点")),
                ),
            ),
        )

        val codes = NoteValidator.validate(note).map(NoteValidationIssue::code).toSet()

        assertTrue(NoteValidationCodes.INVALID_TABS_CHILD in codes)
        assertTrue(NoteValidationCodes.INVALID_TAB_PARENT in codes)
        assertTrue(NoteValidationCodes.LEAF_BLOCK_HAS_CHILDREN in codes)
        assertTrue(NoteValidationCodes.INVALID_DEFAULT_TAB in codes)
    }

    /**
     * 创建测试 Block。
     *
     * @param id 稳定 Block ID。
     * @param type 协议 Block 类型。
     * @param parentId 父 Block ID。
     * @param orderKey 同级排序键。
     * @param content 类型化内容。
     * @return 测试 Block。
     */
    private fun block(
        id: BlockId,
        type: String,
        parentId: BlockId?,
        orderKey: String,
        content: BlockContent,
    ): Block = Block(id, type, parentId, orderKey, content)

    /**
     * 创建测试用单段 RichText。
     *
     * @param value 文本内容。
     * @return 规范 RichText。
     */
    private fun richText(value: String): RichText = RichText(listOf(InlineRun(value)))
}
