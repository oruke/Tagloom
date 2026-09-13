package com.oruke.tagloom.editor.search

import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import com.oruke.tagloom.editor.editableRichText
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 从单篇草稿提取可呈现文本并匹配；不依赖 SQLite，也不读取其他笔记。 */
internal object NoteSearchIndex {
    /**
     * 按页面与 Block 树的显示顺序建立临时文本目录，包括非活动页签。
     * @param note 当前笔记草稿。
     * @return 只包含活动对象且可以在编辑器中定位的字段。
     */
    fun texts(note: Note): List<NoteSearchText> = buildList {
        add(NoteSearchText(NoteSearchTarget(NoteSearchField.TITLE), note.title, null))
        val description = (note.propertyValues[WorkspaceDefaults.DEFAULT_TEXT_PROPERTY_ID]?.value as? JsonPrimitive)
            ?.contentOrNull.orEmpty()
        add(NoteSearchText(NoteSearchTarget(NoteSearchField.DESCRIPTION), description, null))
        val children = note.blocks.filterNot(Block::deleted).groupBy(Block::parentId)
        val pending = ArrayDeque<Pair<Block, List<NoteSearchTab>>>()
        children[null].orEmpty().sortedWith(BLOCK_ORDER).reversed().forEach { pending.addLast(it to emptyList()) }
        val visited = mutableSetOf<BlockId>()
        // 迭代遍历既保持正文次序，也防止异常深度或损坏父边导致递归溢出。
        while (pending.isNotEmpty()) {
            val (block, ancestors) = pending.removeLast()
            if (!visited.add(block.id)) continue
            val parentId = block.parentId
            val tabs = if (block.content is TabBlockContent && parentId != null) {
                ancestors + NoteSearchTab(parentId, block.id)
            } else ancestors
            val root = tabs.firstOrNull()?.containerId ?: block.id
            val content = block.content
            when (content) {
                is BaseTableBlockContent -> addAll(tableTexts(block, content, root, tabs))
                is CodeBlockContent -> add(
                    NoteSearchText(
                        NoteSearchTarget(NoteSearchField.BODY, block.id),
                        content.text,
                        root,
                        tabs
                    )
                )

                is TabBlockContent -> add(
                    NoteSearchText(
                        NoteSearchTarget(NoteSearchField.TAB, block.id),
                        content.title.plainText,
                        root,
                        tabs
                    )
                )

                else -> block.editableRichText()?.let { text ->
                    add(NoteSearchText(NoteSearchTarget(NoteSearchField.BODY, block.id), text.plainText, root, tabs))
                }
            }
            children[block.id].orEmpty().sortedWith(BLOCK_ORDER).reversed().forEach { pending.addLast(it to tabs) }
        }
    }

    /**
     * 在原文上执行不重叠的字面量查找，不用大小写转换后的字符串计算偏移。
     * @param texts 按页面顺序排列的文本目录。
     * @param query 原样匹配的关键词；空串不匹配，空格仍有文本意义。
     * @param matchCase 是否区分大小写。
     * @return 偏移可直接用于输入框显示层的匹配列表。
     */
    fun find(texts: List<NoteSearchText>, query: String, matchCase: Boolean): List<NoteSearchMatch> = buildList {
        if (query.isEmpty()) return@buildList
        texts.forEach { source ->
            var start = source.text.indexOf(query, ignoreCase = !matchCase)
            while (start >= 0) {
                val end = start + query.length
                add(NoteSearchMatch(source.target, start, end))
                start = source.text.indexOf(query, startIndex = end, ignoreCase = !matchCase)
            }
        }
    }

    /**
     * 提取表格中实际可显示的列名与单元格，忽略已删除列、布尔控件和未知数据。
     * @param block 所属表格 Block。
     * @param table 当前表格内容。
     * @param root 根列表中的定位 Block。
     * @param tabs 当前表格的页签路径。
     * @return 按表头、逐行逐列顺序排列的文本。
     */
    private fun tableTexts(
        block: Block,
        table: BaseTableBlockContent,
        root: BlockId,
        tabs: List<NoteSearchTab>
    ): List<NoteSearchText> = buildList {
        val columns = table.columns.filterNot(BaseTableColumn::deleted)
            .sortedWith(compareBy({ it.orderKey }, { it.columnId.value }))
        columns.forEach { column ->
            add(
                NoteSearchText(
                    NoteSearchTarget(NoteSearchField.COLUMN, block.id, column.columnId),
                    column.name.plainText,
                    root,
                    tabs
                )
            )
        }
        table.rows.filterNot(BaseTableRow::deleted).sortedWith(compareBy({ it.orderKey }, { it.rowId.value }))
            .forEach { row ->
                columns.filter { it.type != BaseTableColumnTypes.BOOLEAN }.forEach cell@{ column ->
                    val text = when (val cell = row.cells[column.columnId]) {
                        is BaseTableTextCell -> cell.richText.plainText
                        is BaseTableScalarCell -> cell.value
                        else -> return@cell
                    }
                    add(
                        NoteSearchText(
                            NoteSearchTarget(NoteSearchField.CELL, block.id, column.columnId, row.rowId),
                            text,
                            root,
                            tabs
                        )
                    )
                }
            }
    }

    /** 与编辑器一致的同级稳定排序规则。 */
    private val BLOCK_ORDER = compareBy(Block::orderKey, { it.id.value })
}
