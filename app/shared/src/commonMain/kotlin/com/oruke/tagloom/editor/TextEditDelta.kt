package com.oruke.tagloom.editor

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
         * 通过最大公共前后缀计算一次最小文本替换。
         *
         * @param previous 变更前完整文本。
         * @param current 变更后完整文本。
         * @return 可直接用于块内粘贴和选区替换的最小差异。
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
