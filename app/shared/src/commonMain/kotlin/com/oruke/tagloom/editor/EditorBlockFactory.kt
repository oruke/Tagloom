package com.oruke.tagloom.editor

import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.HeadingBlockContent
import com.oruke.tagloom.core.model.RichText
import com.oruke.tagloom.core.model.RichTextBlockContent
import com.oruke.tagloom.core.model.TodoBlockContent

/**
 * 构造编辑器新增的已知文本 Block，并统一生成初始排序键。
 */
internal object EditorBlockFactory {
    /**
     * 构造一个空的 paragraph、heading 或 todo Block。
     *
     * @param type 目标 Block 类型。
     * @param index 在同级列表中的排序位置。
     * @param idFactory 稳定不透明 ID 生成函数。
     * @return 新 Block。
     */
    fun create(
        type: String,
        index: Int,
        idFactory: (String) -> String,
    ): Block {
        val content = when (type) {
            BlockTypes.HEADING -> HeadingBlockContent(level = 2, richText = RichText())
            BlockTypes.TODO -> TodoBlockContent(checked = false, richText = RichText())
            else -> RichTextBlockContent(RichText())
        }
        return Block(
            id = BlockId(idFactory("block")),
            type = type,
            orderKey = orderKey(index),
            content = content,
        )
    }

    /**
     * 生成按词法排序的固定宽度 Block 顺序键。
     *
     * @param index Block 序号。
     * @return 十位十进制顺序键。
     */
    fun orderKey(index: Int): String = index.toString().padStart(10, '0')
}
