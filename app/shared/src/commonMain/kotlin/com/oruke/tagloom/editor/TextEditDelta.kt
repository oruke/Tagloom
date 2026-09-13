package com.oruke.tagloom.editor

import androidx.compose.ui.text.input.TextFieldValue

/**
 * 描述一次文本框变更中被替换的旧区间和新插入文本。
 */
internal data class TextEditDelta(
    /** 旧文本被替换区间起点。 */
    val replacedStart: Int,
    /** 旧文本被替换区间终点。 */
    val replacedEnd: Int,
    /** 新文本在该位置插入的内容。 */
    val insertedText: String,
) {
    companion object {
        /**
         * 优先按输入前选区和输入后光标恢复实际替换，再兼容没有标准选区语义的文本事件。
         *
         * 粘贴内容可能与被选文字共享表头或尾部，不能先裁剪公共前后缀，否则会丢失完整 Markdown。
         * 只有旧选区外文本未变且新光标位于插入末尾时才使用选区；仅移动选区不会生成插入内容。
         *
         * @param previous 输入事件之前的完整文本与选区。
         * @param current 输入事件之后的完整文本与选区。
         * @return 实际选区替换；无法由选区确认时返回最小文本差异。
         */
        fun between(
            previous: TextFieldValue,
            current: TextFieldValue,
        ): TextEditDelta {
            if (previous.text != current.text && current.selection.collapsed) {
                val start = minOf(previous.selection.start, previous.selection.end)
                val end = maxOf(previous.selection.start, previous.selection.end)
                val insertedLength = current.text.length - previous.text.length + (end - start)
                val insertedEnd = start + insertedLength
                if (
                    insertedLength >= 0 && insertedEnd in start..current.text.length &&
                    current.selection.start == insertedEnd &&
                    previous.text.regionMatches(0, current.text, 0, start) &&
                    previous.text.regionMatches(end, current.text, insertedEnd, previous.text.length - end)
                ) {
                    return TextEditDelta(start, end, current.text.substring(start, insertedEnd))
                }
            }
            // 删除、输入法组合替换或外部文本同步未必符合选区替换模型，保留原有兼容路径。
            return between(previous.text, current.text)
        }

        /**
         * 在没有可靠选区信息时，通过最大公共前后缀计算一次最小文本替换。
         *
         * @param previous 变更前完整文本。
         * @param current 变更后完整文本。
         * @return 能重建新文本的最小差异；有选区信息时应使用文本框值重载保留完整粘贴内容。
         */
        fun between(
            previous: String,
            current: String,
        ): TextEditDelta {
            val prefixLimit = minOf(previous.length, current.length)
            var prefixLength = 0
            while (prefixLength < prefixLimit && previous[prefixLength] == current[prefixLength]) {
                prefixLength += 1
            }
            val suffixLimit = minOf(previous.length, current.length) - prefixLength
            var suffixLength = 0
            while (
                suffixLength < suffixLimit &&
                previous[previous.lastIndex - suffixLength] == current[current.lastIndex - suffixLength]
            ) {
                suffixLength += 1
            }
            return TextEditDelta(
                replacedStart = prefixLength,
                replacedEnd = previous.length - suffixLength,
                insertedText = current.substring(prefixLength, current.length - suffixLength),
            )
        }
    }
}
