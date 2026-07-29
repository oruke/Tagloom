package com.oruke.tagloom.core.model

import kotlinx.serialization.Serializable

/**
 * 定义 Tagloom 已知的行内 Mark 类型名称。
 *
 * 协议仍使用字符串保存 Mark 类型，使旧客户端能够保留未来新增的未知 Mark。
 */
object InlineMarkTypes {
    /** 粗体样式。 */
    const val BOLD: String = "bold"

    /** 斜体样式。 */
    const val ITALIC: String = "italic"

    /** 下划线样式。 */
    const val UNDERLINE: String = "underline"

    /** 删除线样式。 */
    const val STRIKETHROUGH: String = "strikethrough"

    /** 单词或短语级行内代码样式。 */
    const val CODE: String = "code"

    /** 使用语义色 token 的高亮样式。 */
    const val HIGHLIGHT: String = "highlight"

    /** 使用语义色 token 的文字颜色样式。 */
    const val TEXT_COLOR: String = "text-color"

    /** 带目标属性的链接样式。 */
    const val LINK: String = "link"
}

/**
 * 定义带参数 Inline Mark 使用的稳定属性键。
 */
object InlineMarkAttributes {
    /** 由主题解释的语义色 token。 */
    const val COLOR_TOKEN: String = "color-token"
}

/**
 * 描述一段行内文本附带的结构化样式。
 *
 * `attributes` 用于保存链接目标、高亮语义色等类型特有参数。
 */
@Serializable
data class InlineMark(
    /** Mark 类型；未知字符串必须原样保留。 */
    val type: String,
    /** Mark 参数，键值在保存前按键名稳定排序。 */
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(type.isNotBlank()) { "Inline Mark 类型不能为空" }
    }
}

/**
 * 描述具有相同样式和加注集合的一段连续文本。
 */
@Serializable
data class InlineRun(
    /** 当前 Run 的文本内容。 */
    val text: String,
    /** 应用于整段文本的结构化 Mark。 */
    val marks: List<InlineMark> = emptyList(),
    /** 锚定到整段文本的加注标识。 */
    val annotationIds: List<AnnotationId> = emptyList(),
)

/**
 * 表示文本类 Block 的规范化行内内容。
 */
@Serializable
data class RichText(
    /** 按显示顺序排列的文本 Run。 */
    val runs: List<InlineRun> = emptyList(),
) {
    /** 忽略样式和加注后拼接得到的纯文本。 */
    val plainText: String
        get() = runs.joinToString(separator = "") { it.text }
}

/**
 * 将 RichText 转换为稳定且可比较的协议表示。
 *
 * 规范化可以避免相同语义因 Mark 顺序或碎片化 Run 不同而产生不同快照。
 */
object RichTextNormalizer {
    /**
     * 规范化 RichText 的 Mark、加注顺序和相邻 Run。
     *
     * @param richText 待规范化的行内富文本。
     * @return 语义等价且序列化稳定的 RichText。
     */
    fun normalize(richText: RichText): RichText {
        val mergedRuns = mutableListOf<InlineRun>()

        richText.runs.forEach { sourceRun ->
            val normalizedRun = normalizeRun(sourceRun) ?: return@forEach
            val previousRun = mergedRuns.lastOrNull()

            if (
                previousRun != null &&
                previousRun.marks == normalizedRun.marks &&
                previousRun.annotationIds == normalizedRun.annotationIds
            ) {
                mergedRuns[mergedRuns.lastIndex] = previousRun.copy(
                    text = previousRun.text + normalizedRun.text,
                )
            } else {
                mergedRuns += normalizedRun
            }
        }

        return RichText(runs = mergedRuns)
    }

    /**
     * 规范化单个 Run，并丢弃不承载文本的空 Run。
     *
     * @param run 待规范化的文本 Run。
     * @return 规范化结果；空文本返回 `null`。
     */
    private fun normalizeRun(run: InlineRun): InlineRun? {
        if (run.text.isEmpty()) {
            return null
        }

        return run.copy(
            marks = normalizeMarks(run.marks),
            annotationIds = run.annotationIds.distinct().sortedBy(AnnotationId::value),
        )
    }

    /**
     * 对 Mark 参数和 Mark 列表执行去重与稳定排序。
     *
     * @param marks 原始 Mark 列表。
     * @return 规范化后的 Mark 列表。
     */
    private fun normalizeMarks(marks: List<InlineMark>): List<InlineMark> =
        marks
            .map { mark ->
                mark.copy(
                    attributes = buildMap {
                        mark.attributes.entries
                            .sortedBy { it.key }
                            .forEach { (key, value) -> put(key, value) }
                    },
                )
            }
            .distinct()
            .sortedBy(::canonicalMarkKey)

    /**
     * 生成只用于确定性排序的 Mark 比较键。
     *
     * @param mark 已规范化参数顺序的 Mark。
     * @return 包含类型与全部参数的稳定排序键。
     */
    private fun canonicalMarkKey(mark: InlineMark): String = buildString {
        append(mark.type)
        mark.attributes.forEach { (key, value) ->
            append('\u0000')
            append(key)
            append('=')
            append(value)
        }
    }
}
