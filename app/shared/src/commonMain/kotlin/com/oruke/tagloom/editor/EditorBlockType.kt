package com.oruke.tagloom.editor

import com.oruke.tagloom.core.model.BlockTypes

/**
 * 定义编辑器可以直接创建或转换的可写 Block 类型。
 *
 * 协议仍使用 [BlockTypes] 字符串；该枚举只负责把编辑器命令收敛为有限且可测试的集合。
 */
enum class EditorBlockType(
    /** 持久化 Block 使用的协议类型。 */
    val protocolType: String,
    /** 标题层级；非标题类型为空。 */
    val headingLevel: Int? = null,
) {
    /** 普通正文。 */
    TEXT(BlockTypes.PARAGRAPH),

    /** 一级标题。 */
    HEADING_ONE(BlockTypes.HEADING, 1),

    /** 二级标题。 */
    HEADING_TWO(BlockTypes.HEADING, 2),

    /** 三级标题。 */
    HEADING_THREE(BlockTypes.HEADING, 3),

    /** 无序列表项。 */
    BULLETED_LIST(BlockTypes.BULLETED_LIST),

    /** 有序列表项。 */
    NUMBERED_LIST(BlockTypes.NUMBERED_LIST),

    /** 待办项。 */
    TODO(BlockTypes.TODO),

    /** 引用段落。 */
    QUOTE(BlockTypes.QUOTE),

    /** 多行代码块。 */
    CODE(BlockTypes.CODE),

    /** 分隔线。 */
    DIVIDER(BlockTypes.DIVIDER),
}

/**
 * 表示一次编辑命令完成后应获得焦点的 Block 与光标位置。
 */
data class EditorCursorTarget(
    /** 应获得输入焦点的稳定 Block ID。 */
    val blockId: com.oruke.tagloom.core.model.BlockId,
    /** 规范化到目标文本长度内的光标偏移。 */
    val offset: Int,
)
