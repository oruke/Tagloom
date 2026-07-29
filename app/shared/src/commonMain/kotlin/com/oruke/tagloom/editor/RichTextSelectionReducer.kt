package com.oruke.tagloom.editor

import com.oruke.tagloom.core.model.InlineRun
import com.oruke.tagloom.core.model.RichText

/**
 * 提取字符选区与规范 RichText Run 的稳定交集。
 */
internal object RichTextSelectionReducer {
    /**
     * 提取与字符选区相交的 RichText Run。
     *
     * @param richText 当前富文本。
     * @param start 选区起始偏移。
     * @param end 选区结束偏移。
     * @return 与选区相交的 Run。
     */
    fun intersectingRuns(
        richText: RichText,
        start: Int,
        end: Int,
    ): List<InlineRun> {
        var offset = 0
        return richText.runs.filter { run ->
            val runStart = offset
            val runEnd = offset + run.text.length
            offset = runEnd
            runStart < end && runEnd > start
        }
    }
}
