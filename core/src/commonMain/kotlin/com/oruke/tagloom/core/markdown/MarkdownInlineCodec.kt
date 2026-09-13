package com.oruke.tagloom.core.markdown

import com.oruke.tagloom.core.model.AnnotationId
import com.oruke.tagloom.core.model.InlineMark
import com.oruke.tagloom.core.model.InlineMarkTypes
import com.oruke.tagloom.core.model.InlineRun
import com.oruke.tagloom.core.model.RichText
import com.oruke.tagloom.core.model.RichTextNormalizer

/**
 * 负责 Markdown 行内标记的解析、规范化与纯文本转义。
 */
internal object MarkdownInlineCodec {
    /**
     * 递归解析由本编码器产生的常见嵌套行内 Mark 与扩展加注。
     *
     * @param text Markdown 行内文本。
     * @param definitions 可引用的加注定义。
     * @param warnings 可追加缺失定义警告。
     * @return 规范化 RichText。
     */
    fun parse(
        text: String,
        definitions: Map<AnnotationId, String>,
        warnings: MutableList<String>,
    ): RichText {
        val runs = mutableListOf<InlineRun>()
        var remaining = text
        while (remaining.isNotEmpty()) {
            val token = nextInlineToken(remaining)
            if (token == null) {
                runs += InlineRun(unescapePlain(remaining))
                break
            }
            if (token.start > 0) {
                runs += InlineRun(unescapePlain(remaining.substring(0, token.start)))
            }
            val inner = parse(token.innerText, definitions, warnings).runs
            runs += when (token.kind) {
                InlineTokenKind.CODE -> listOf(
                    InlineRun(
                        text = unescapePlain(token.innerText),
                        marks = listOf(InlineMark(InlineMarkTypes.CODE)),
                    ),
                )
                InlineTokenKind.BOLD -> inner.map { run -> run.withMark(InlineMark(InlineMarkTypes.BOLD)) }
                InlineTokenKind.ITALIC -> inner.map { run -> run.withMark(InlineMark(InlineMarkTypes.ITALIC)) }
                InlineTokenKind.STRIKETHROUGH -> inner.map { run ->
                    run.withMark(InlineMark(InlineMarkTypes.STRIKETHROUGH))
                }
                InlineTokenKind.LINK -> inner.map { run ->
                    run.withMark(InlineMark(InlineMarkTypes.LINK, mapOf("href" to token.attribute.orEmpty())))
                }
                InlineTokenKind.ANNOTATION -> applyAnnotation(token, inner, definitions, warnings)
            }
            remaining = remaining.substring(token.endExclusive)
        }
        return RichTextNormalizer.normalize(RichText(runs))
    }

    /**
     * 转义 Markdown 行内控制字符。
     *
     * @param text 原始纯文本。
     * @return 可安全拼入行内 Markdown 的文本。
     */
    fun escapePlain(text: String): String = buildString {
        text.forEach { character ->
            if (character in MARKDOWN_SPECIAL_CHARACTERS) append('\\')
            append(character)
        }
    }

    /**
     * 移除本编码器使用的单字符反斜杠转义。
     *
     * @param text 已转义行内文本。
     * @return 恢复后的纯文本。
     */
    fun unescapePlain(text: String): String = ESCAPED_CHARACTER.replace(text) { match -> match.groupValues[1] }

    /**
     * 校验加注引用并把合法 AnnotationId 应用到内部 Run。
     *
     * @param token 当前加注 token。
     * @param inner 已解析内部 Run。
     * @param definitions 文档加注定义。
     * @param warnings 可追加警告。
     * @return 保留文本且按需附加加注 ID 的 Run。
     */
    private fun applyAnnotation(
        token: InlineToken,
        inner: List<InlineRun>,
        definitions: Map<AnnotationId, String>,
        warnings: MutableList<String>,
    ): List<InlineRun> {
        val annotationId = runCatching { AnnotationId(token.attribute.orEmpty()) }.getOrNull()
        if (annotationId == null || annotationId !in definitions) {
            warnings += "加注锚点 ${token.attribute.orEmpty()} 缺少合法定义，已保留文字"
            return inner
        }
        return inner.map { run -> run.copy(annotationIds = run.annotationIds + annotationId) }
    }

    /**
     * 寻找当前文本中最靠前且闭合的受支持行内 token。
     *
     * @param text 尚未解析的行内文本。
     * @return token 位置和内容；不存在时为 null。
     */
    private fun nextInlineToken(text: String): InlineToken? {
        val candidates = mutableListOf<InlineToken>()
        delimitedToken(text, "**", InlineTokenKind.BOLD)?.let(candidates::add)
        delimitedToken(text, "~~", InlineTokenKind.STRIKETHROUGH)?.let(candidates::add)
        delimitedToken(text, "`", InlineTokenKind.CODE)?.let(candidates::add)
        delimitedToken(text, "*", InlineTokenKind.ITALIC)?.let(candidates::add)
        LINK.find(text)?.let { match ->
            candidates += InlineToken(
                start = match.range.first,
                endExclusive = match.range.last + 1,
                kind = InlineTokenKind.LINK,
                innerText = match.groupValues[1],
                attribute = match.groupValues[2],
            )
        }
        ANNOTATION_ANCHOR.find(text)?.let { match ->
            candidates += InlineToken(
                start = match.range.first,
                endExclusive = match.range.last + 1,
                kind = InlineTokenKind.ANNOTATION,
                innerText = match.groupValues[2],
                attribute = match.groupValues[1],
            )
        }
        return candidates.minWithOrNull(compareBy<InlineToken> { token -> token.start }.thenByDescending { token ->
            token.endExclusive - token.start
        })
    }

    /**
     * 查找一对相同分隔符包围的 token。
     *
     * @param text 待搜索文本。
     * @param delimiter 开始与结束分隔符。
     * @param kind token 类型。
     * @return 首个闭合 token；不存在时为 null。
     */
    private fun delimitedToken(
        text: String,
        delimiter: String,
        kind: InlineTokenKind,
    ): InlineToken? {
        val start = text.indexOf(delimiter)
        if (start < 0 || (start > 0 && text[start - 1] == '\\')) {
            return null
        }
        val end = text.indexOf(delimiter, start + delimiter.length)
        if (end <= start + delimiter.length) {
            return null
        }
        return InlineToken(
            start = start,
            endExclusive = end + delimiter.length,
            kind = kind,
            innerText = text.substring(start + delimiter.length, end),
        )
    }

    /**
     * 为一个 Run 添加 Mark，后续由 RichTextNormalizer 去重排序。
     *
     * @param mark 待添加样式。
     * @return 带合并 Mark 的新 Run。
     */
    private fun InlineRun.withMark(mark: InlineMark): InlineRun = copy(marks = marks + mark)

    /** 行内 Markdown 链接。 */
    private val LINK: Regex = Regex("\\[([^]]+)]\\(([^)]+)\\)")

    /** Tagloom 扩展加注锚点；花括号显式转义以兼容 Android ICU 正则。 */
    private val ANNOTATION_ANCHOR: Regex = Regex("\\{\\{([A-Za-z0-9_-]{1,128})\\|(.+?)\\}\\}")

    /** 本编码器转义的单字符。 */
    private val ESCAPED_CHARACTER: Regex = Regex("\\\\([\\\\`*_{}\\[\\]()#+.!|~-])")

    /** 需要反斜杠转义的 Markdown 控制字符。 */
    private val MARKDOWN_SPECIAL_CHARACTERS: Set<Char> = "\\`*_{}[]()#+.!|~-".toSet()
}

/**
 * 区分行内解析器支持的 token 类型。
 */
private enum class InlineTokenKind {
    /** 单词或短语级代码。 */
    CODE,

    /** 粗体。 */
    BOLD,

    /** 斜体。 */
    ITALIC,

    /** 删除线。 */
    STRIKETHROUGH,

    /** 带 href 的链接。 */
    LINK,

    /** Tagloom 独立加注锚点。 */
    ANNOTATION,
}

/**
 * 保存一个已闭合行内 token 在源文本中的位置和语义。
 */
private data class InlineToken(
    /** token 起始字符索引。 */
    val start: Int,
    /** token 结束后的字符索引。 */
    val endExclusive: Int,
    /** token 语义类型。 */
    val kind: InlineTokenKind,
    /** 去掉外层语法后的内部文本。 */
    val innerText: String,
    /** 链接 href 或 AnnotationId。 */
    val attribute: String? = null,
)
