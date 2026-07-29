package com.oruke.tagloom.editor

import com.oruke.tagloom.core.model.InlineRun
import com.oruke.tagloom.core.model.RichText
import com.oruke.tagloom.core.model.RichTextNormalizer

/**
 * 以字符偏移执行 RichText 切片、删除和拼接，同时完整保留 Mark 与 Annotation 锚点。
 */
internal object RichTextDocumentReducer {
    /**
     * 按半开字符区间提取 RichText。
     *
     * @param richText 原始规范化富文本。
     * @param startInclusive 起始字符偏移。
     * @param endExclusive 结束字符偏移。
     * @return 保留相交 Run 样式和加注的规范化切片。
     */
    fun slice(
        richText: RichText,
        startInclusive: Int,
        endExclusive: Int,
    ): RichText {
        val safeStart = startInclusive.coerceIn(0, richText.plainText.length)
        val safeEnd = endExclusive.coerceIn(safeStart, richText.plainText.length)
        var offset = 0
        val runs = buildList {
            richText.runs.forEach { run ->
                val runStart = offset
                val runEnd = offset + run.text.length
                offset = runEnd
                val localStart = (safeStart - runStart).coerceIn(0, run.text.length)
                val localEnd = (safeEnd - runStart).coerceIn(0, run.text.length)
                if (localStart < localEnd) {
                    add(run.copy(text = run.text.substring(localStart, localEnd)))
                }
            }
        }
        return RichTextNormalizer.normalize(RichText(runs))
    }

    /**
     * 删除一个字符区间，并保留区间两侧的结构化样式。
     *
     * @param richText 原始富文本。
     * @param startInclusive 删除起点。
     * @param endExclusive 删除终点。
     * @return 删除后的规范化富文本。
     */
    fun removeRange(
        richText: RichText,
        startInclusive: Int,
        endExclusive: Int,
    ): RichText {
        val safeStart = minOf(startInclusive, endExclusive).coerceIn(0, richText.plainText.length)
        val safeEnd = maxOf(startInclusive, endExclusive).coerceIn(safeStart, richText.plainText.length)
        return concat(
            slice(richText, 0, safeStart),
            slice(richText, safeEnd, richText.plainText.length),
        )
    }

    /**
     * 拼接两段 RichText，并合并边界处语义相同的 Run。
     *
     * @param first 前半段。
     * @param second 后半段。
     * @return 规范化拼接结果。
     */
    fun concat(
        first: RichText,
        second: RichText,
    ): RichText = RichTextNormalizer.normalize(RichText(first.runs + second.runs))

    /**
     * 从不带结构化样式的纯文本构造 RichText。
     *
     * @param text 原始纯文本。
     * @return 空文本对应空 RichText，否则返回单 Run 富文本。
     */
    fun fromPlainText(text: String): RichText = if (text.isEmpty()) {
        RichText()
    } else {
        RichText(listOf(InlineRun(text)))
    }
}
