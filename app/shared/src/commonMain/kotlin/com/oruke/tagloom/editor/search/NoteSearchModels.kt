package com.oruke.tagloom.editor.search

import com.oruke.tagloom.core.model.BaseTableColumnId
import com.oruke.tagloom.core.model.BaseTableRowId
import com.oruke.tagloom.core.model.BlockId

/** 当前笔记中可直接呈现并定位的文本部位。 */
internal enum class NoteSearchField {
    /** 笔记标题。 */
    TITLE,

    /** 标题下的描述。 */
    DESCRIPTION,

    /** 正文、代码或附件说明。 */
    BODY,

    /** 页签的名称。 */
    TAB,

    /** 正文表格列名。 */
    COLUMN,

    /** 正文表格文本或标量单元格。 */
    CELL,
}

/** 用稳定对象标识定位展示字段，不把全文或布局坐标放入身份。 */
internal data class NoteSearchTarget(
    /** 字段所属的显示部位。 */
    val field: NoteSearchField,
    /** 正文部位所属 Block；标题与描述为空。 */
    val blockId: BlockId? = null,
    /** 表格列名或单元格的列标识。 */
    val columnId: BaseTableColumnId? = null,
    /** 单元格的行标识。 */
    val rowId: BaseTableRowId? = null,
)

/** 为定位隐藏正文而需要打开的会话页签。 */
internal data class NoteSearchTab(
    /** 页签所属的 Tabs 容器。 */
    val containerId: BlockId,
    /** 必须选中的页签。 */
    val tabId: BlockId,
)

/** 一个可查找字段及其在编辑器中的导航位置。 */
internal data class NoteSearchText(
    /** 稳定的字段定位。 */
    val target: NoteSearchTarget,
    /** 当前草稿中的原文。 */
    val text: String,
    /** 根 LazyColumn 中对应的 Block；页头字段为空。 */
    val rootBlockId: BlockId?,
    /** 从外向内需要选择的页签路径。 */
    val tabs: List<NoteSearchTab> = emptyList(),
)

/** 一处字面量匹配，偏移直接对应原文的 UTF-16 索引。 */
internal data class NoteSearchMatch(
    /** 匹配所在的字段。 */
    val target: NoteSearchTarget,
    /** 匹配开始偏移，包含此字符。 */
    val start: Int,
    /** 匹配结束偏移，不包含此字符。 */
    val end: Int,
)
