package com.oruke.tagloom.editor.search

import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import kotlinx.serialization.json.JsonPrimitive

/**
 * 构造同时包含富文本、长代码、屏外页签和宽表格的脱敏笔记，供索引与生产 App 流程共同验收。
 * @return 全文共八处不区分大小写的 needle，且有独立行内样式与默认页签。
 */
internal fun noteSearchTestNote(): Note {
    val columns = (0..5).map { index ->
        BaseTableColumn(
            BaseTableColumnId("column-$index"),
            "$index",
            searchTestText(if (index == 5) "needle 列" else "列 $index"),
            BaseTableColumnTypes.TEXT,
            width = 220
        )
    }
    val row = BaseTableRow(
        BaseTableRowId("row-0"),
        "0",
        mapOf(columns.last().columnId to BaseTableTextCell(searchTestText("表格内容 ".repeat(30) + "needle")))
    )
    return Note(
        title = "查找示例 needle",
        directTagIds = listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID),
        propertyValues = mapOf(
            WorkspaceDefaults.DEFAULT_TEXT_PROPERTY_ID to NotePropertyValue(
                JsonPrimitive("草稿中的 needle 描述"),
                PropertyValueSource.USER,
                updatedAt = "2026-01-01T00:00:00Z"
            )
        ),
        blocks = buildList {
            add(
                searchTestBlock(
                    "rich",
                    "00",
                    content = RichTextBlockContent(
                        RichText(
                            listOf(
                                InlineRun(
                                    "Nee",
                                    listOf(InlineMark(InlineMarkTypes.BOLD))
                                ), InlineRun("dle needle 🌿")
                            )
                        )
                    )
                )
            )
            add(
                searchTestBlock(
                    "code",
                    "01",
                    type = BlockTypes.CODE,
                    content = CodeBlockContent(
                        text = "val text = \"" + "sample ".repeat(50) + "needle\"",
                        language = "kotlin"
                    )
                ).copy(attributes = kotlinx.serialization.json.JsonObject(mapOf("codeWrap" to JsonPrimitive(false))))
            )
            repeat(36) { index ->
                add(
                    searchTestBlock(
                        "filler-$index",
                        "10-${index.toString().padStart(2, '0')}",
                        content = RichTextBlockContent(searchTestText("正文段落 $index，用于验证屏幕外内容的自动定位。"))
                    )
                )
            }
            add(searchTestBlock("tabs", "20", type = BlockTypes.TABS, content = TabsBlockContent))
            add(searchTestBlock("first-tab", "0", "tabs", BlockTypes.TAB, TabBlockContent(searchTestText("第一页"))))
            add(
                searchTestBlock(
                    "first-text",
                    "0",
                    "first-tab",
                    content = RichTextBlockContent(searchTestText("当前页签内容"))
                )
            )
            add(searchTestBlock("second-tab", "1", "tabs", BlockTypes.TAB, TabBlockContent(searchTestText("第二页"))))
            add(searchTestBlock("nested-tabs", "0", "second-tab", BlockTypes.TABS, TabsBlockContent))
            add(
                searchTestBlock(
                    "nested-first",
                    "0",
                    "nested-tabs",
                    BlockTypes.TAB,
                    TabBlockContent(searchTestText("概览"))
                )
            )
            add(
                searchTestBlock(
                    "nested-first-text",
                    "0",
                    "nested-first",
                    content = RichTextBlockContent(searchTestText("嵌套概览内容"))
                )
            )
            add(
                searchTestBlock(
                    "nested-second",
                    "1",
                    "nested-tabs",
                    BlockTypes.TAB,
                    TabBlockContent(searchTestText("详情"))
                )
            )
            add(
                searchTestBlock(
                    "hidden",
                    "0",
                    "nested-second",
                    content = RichTextBlockContent(searchTestText("隐藏页签 needle 🌿"))
                )
            )
            add(
                searchTestBlock(
                    "table",
                    "30",
                    type = BlockTypes.BASE_TABLE,
                    content = BaseTableBlockContent(columns = columns, rows = listOf(row))
                )
            )
        },
    )
}

/**
 * 创建测试所需的单 Run 文本。
 * @param text 脱敏的显示原文。
 * @return 不带额外样式的富文本。
 */
internal fun searchTestText(text: String): RichText = RichText(listOf(InlineRun(text)))

/**
 * 创建具有固定身份的场景 Block。
 * @param id 脱敏稳定 ID。
 * @param order 同级排序键。
 * @param parent 父 Block ID；根节点为空。
 * @param type Block 的协议类型。
 * @param content 与类型匹配的内容。
 * @return 可直接进入 Note 树的 Block。
 */
internal fun searchTestBlock(
    id: String,
    order: String,
    parent: String? = null,
    type: String = BlockTypes.PARAGRAPH,
    content: BlockContent
): Block =
    Block(BlockId(id), type, parent?.let(::BlockId), order, content)
