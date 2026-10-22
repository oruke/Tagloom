package com.oruke.tagloom.core.model

/** 只提取有界 HTML 头中的标题与描述；页面脚本和正文从不执行或保存。 */
object WebPageMetadataParser {
    /**
     * 提取 OpenGraph 优先的网页信息，并解码常见和数字 HTML 实体。
     * @param html 客户端已限制体积并解码的页面。
     * @return 缺失字段为空的净化元数据。
     */
    fun parse(html: String): WebPageMetadata {
        val bounded = html.take(MAX_HEAD)
        val head = bounded.take(Regex("</head>", RegexOption.IGNORE_CASE).find(bounded)?.range?.first ?: bounded.length)
            .replace(COMMENTS, "").replace(SCRIPTS, "")
        val metadata = mutableMapOf<String, String>()
        META.findAll(head).forEach { tag ->
            val attrs = ATTR.findAll(tag.value).associate { match ->
                match.groupValues[1].lowercase() to match.groupValues.drop(2).firstOrNull(String::isNotEmpty).orEmpty()
            }
            val key = attrs["property"] ?: attrs["name"]
            if (key != null && key.lowercase() !in metadata) attrs["content"]?.let { metadata[key.lowercase()] = it }
        }
        val title = clean(metadata["og:title"], 512) ?: clean(TITLE.find(head)?.groupValues?.get(1), 512)
        return WebPageMetadata(title, clean(metadata["og:description"], 2048) ?: clean(metadata["description"], 2048))
    }

    /**
     * 去除标签、实体与多余空白并限制长度，不把 HTML 混入笔记字段。
     * @param value 原始元数据。
     * @param limit 字段最大字符数。
     * @return 可展示文本或空值。
     */
    private fun clean(value: String?, limit: Int): String? = value?.replace(TAGS, " ")?.let { text ->
        ENTITIES.replace(text) { match -> decodeEntity(match.groupValues[1]) ?: match.value }
    }?.replace(WHITESPACE, " ")?.trim()?.take(limit)?.ifEmpty { null }

    /**
     * 解码有限命名实体与有效 Unicode 数字实体；未知实体原样保留。
     * @param entity 不含边界符的实体名。
     * @return 字符文本，未知时为空。
     */
    private fun decodeEntity(entity: String): String? {
        val named = mapOf(
            "amp" to "&",
            "lt" to "<",
            "gt" to ">",
            "quot" to "\"",
            "apos" to "'",
            "nbsp" to " ",
            "ndash" to "–",
            "mdash" to "—",
            "hellip" to "…"
        )
        named[entity.lowercase()]?.let { return it }
        val code = when {
            entity.startsWith("#x", true) -> entity.drop(2).toIntOrNull(16)
            entity.startsWith('#') -> entity.drop(1).toIntOrNull()
            else -> null
        } ?: return null
        if (code !in 1..0x10ffff || code in 0xd800..0xdfff) return null
        return if (code <= 0xffff) code.toChar().toString() else {
            val point = code - 0x10000
            "${(0xd800 + (point shr 10)).toChar()}${(0xdc00 + (point and 0x3ff)).toChar()}"
        }
    }

    /** 最多检查前 1,048,576 个字符，覆盖网络层 1 MiB 前缀，避免大型内联资源挤掉后置元数据。 */
    private const val MAX_HEAD = 1048576

    /** 元数据标签允许引号内出现大于号。 */
    private val META = Regex("<meta\\b(?:\"[^\"]*\"|'[^']*'|[^'\">])*?>", RegexOption.IGNORE_CASE)

    /** HTML 属性名和值，兼容单双引号与无引号内容。 */
    private val ATTR = Regex("([A-Za-z_:][A-Za-z0-9_:.-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))")

    /** 标题允许换行。 */
    private val TITLE = Regex("<title\\b[^>]*>([\\s\\S]*?)</title\\s*>", RegexOption.IGNORE_CASE)

    /** 不解析注释中的示例标签。 */
    private val COMMENTS = Regex("<!--[\\s\\S]*?(?:-->|$)")

    /** 脚本内容中的伪标签不是页面元数据。 */
    private val SCRIPTS = Regex("<script\\b[^>]*>[\\s\\S]*?</script\\s*>", RegexOption.IGNORE_CASE)

    /** 删除嵌入标记。 */
    private val TAGS = Regex("<[^>]+>")

    /** 支持常见命名实体和数字实体。 */
    private val ENTITIES = Regex("&([A-Za-z]+|#[0-9]+|#[xX][0-9a-fA-F]+);")

    /** 标题和描述统一为连续阅读文本。 */
    private val WHITESPACE = Regex("\\s+")
}
