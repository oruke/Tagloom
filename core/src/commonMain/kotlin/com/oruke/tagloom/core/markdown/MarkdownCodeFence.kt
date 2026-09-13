package com.oruke.tagloom.core.markdown

/**
 * 保存代码围栏的标记和语言信息，统一块解析与加注扫描对围栏边界的判断。
 */
internal data class MarkdownCodeFence(
    /** 至少三个相同反引号或波浪线的起始围栏。 */
    val marker: String,
    /** 去掉外侧空格的语言信息；未指定时为空。 */
    val language: String?,
) {
    /**
     * 检查代码行是否以同类且不短于起始围栏的标记结束代码块。
     *
     * @param line 待检查的 Markdown 物理行。
     * @return 除最多三个缩进空格和末尾空白外仅包含闭合围栏时为 true。
     */
    fun closes(line: String): Boolean {
        if (line.takeWhile { it == ' ' }.length > 3 || line.startsWith('\t')) return false
        val trimmed = line.trim()
        return trimmed.length >= marker.length && trimmed.all { it == marker.first() }
    }

    companion object {
        /**
         * 识别 CommonMark 反引号或波浪线围栏，排除缩进代码和非法反引号信息串。
         *
         * @param line 待检查的 Markdown 物理行。
         * @return 合法起始围栏信息；普通行返回 null。
         */
        fun opening(line: String): MarkdownCodeFence? {
            val match = OPENING.matchEntire(line) ?: return null
            val marker = match.groupValues[1]
            val info = match.groupValues[2]
            if (marker.startsWith('`') && '`' in info) return null
            return MarkdownCodeFence(marker, info.trim().ifBlank { null })
        }

        /** 围栏允许最多三个前导空格，标记至少三字符且不可混用。 */
        private val OPENING: Regex = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")
    }
}
