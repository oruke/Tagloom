package com.oruke.tagloom.editor

import com.oruke.tagloom.core.model.InlineMark
import com.oruke.tagloom.core.model.InlineMarkTypes
import com.oruke.tagloom.core.model.RichText
import com.oruke.tagloom.core.model.RichTextEditor

/**
 * 以纯函数切换选区 Mark 或替换 Link Mark。
 */
internal object RichTextMarkReducer {
    /**
     * 在选区完整带有指定 Mark 时移除，否则添加。
     *
     * @param richText 当前富文本。
     * @param start 选区起始偏移。
     * @param end 选区结束偏移。
     * @param markType 稳定 Mark 类型。
     * @return 更新后的规范 RichText。
     */
    fun toggle(
        richText: RichText,
        start: Int,
        end: Int,
        markType: String,
    ): RichText {
        val fullyMarked = RichTextSelectionReducer.intersectingRuns(richText, start, end)
            .all { run -> run.marks.any { mark -> mark.type == markType } }
        return if (fullyMarked) {
            RichTextEditor.removeMark(richText, start, end, markType)
        } else {
            RichTextEditor.addMark(richText, start, end, InlineMark(markType))
        }
    }

    /**
     * 替换选区内已有链接并设置新的 href。
     *
     * @param richText 当前富文本。
     * @param start 选区起始偏移。
     * @param end 选区结束偏移。
     * @param href 非空链接地址或 Tagloom 内部 URI。
     * @return 更新后的规范 RichText。
     */
    fun setLink(
        richText: RichText,
        start: Int,
        end: Int,
        href: String,
    ): RichText {
        require(href.isNotBlank()) { "链接地址不能为空" }
        val withoutOldLink = RichTextEditor.removeMark(richText, start, end, InlineMarkTypes.LINK)
        return RichTextEditor.addMark(
            richText = withoutOldLink,
            startInclusive = start,
            endExclusive = end,
            mark = InlineMark(InlineMarkTypes.LINK, mapOf("href" to href)),
        )
    }

    /**
     * 替换选区内同类型 Mark，并写入新的稳定参数。
     *
     * @param richText 当前富文本。
     * @param start 选区起始偏移。
     * @param end 选区结束偏移。
     * @param markType 稳定 Mark 类型。
     * @param attributes 新 Mark 参数。
     * @return 选区内同类型 Mark 唯一且参数一致的规范 RichText。
     */
    fun set(
        richText: RichText,
        start: Int,
        end: Int,
        markType: String,
        attributes: Map<String, String>,
    ): RichText {
        val withoutOldMark = RichTextEditor.removeMark(richText, start, end, markType)
        return RichTextEditor.addMark(
            richText = withoutOldMark,
            startInclusive = start,
            endExclusive = end,
            mark = InlineMark(markType, attributes),
        )
    }
}
