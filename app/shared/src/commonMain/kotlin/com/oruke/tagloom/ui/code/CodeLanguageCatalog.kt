package com.oruke.tagloom.ui.code

import com.gallatinapps.syntaxmp.tokenizer.SyntaxTokenizer

/** 语言选择器中的稳定标识与惯用名称。 */
internal data class CodeLanguage(
    /** 写入 CodeBlockContent.language 的标准标识。 */
    val id: String,
    /** 面向用户展示的语言惯用名称。 */
    val label: String,
)

/** 复用不可变分词器的语言注册表，保证菜单只提供实际支持高亮的语言。 */
internal object CodeLanguageCatalog {
    /** 无正文缓存、可安全并发使用的本地分词器。 */
    val tokenizer = SyntaxTokenizer()

    /** 需要保留惯用缩写或大小写的语言名称。 */
    private val displayNames = mapOf(
        "c" to "C", "cpp" to "C++", "csharp" to "C#", "css" to "CSS", "csv" to "CSV",
        "graphql" to "GraphQL", "html" to "HTML", "ini" to "INI", "javascript" to "JavaScript",
        "json" to "JSON", "jsx" to "JSX", "php" to "PHP", "postgresql" to "PostgreSQL",
        "powershell" to "PowerShell", "sql" to "SQL", "sqlite" to "SQLite", "toml" to "TOML",
        "tsx" to "TSX", "typescript" to "TypeScript", "xml" to "XML", "yaml" to "YAML",
    )

    /** 按显示名称排序的全部内置语言，数量随锁定版本的注册表确定。 */
    val languages: List<CodeLanguage> = tokenizer.languageIds.map { language ->
        CodeLanguage(language.value, displayNames[language.value] ?: language.value.replaceFirstChar(Char::uppercase))
    }.sortedBy { it.label.lowercase() }

    /**
     * 为标准标识、Markdown 别名或未知语言生成显示名。
     * @param language 快照中保留的语言标识。
     * @return 标准显示名；未知语言返回原标识；纯文本返回空值。
     */
    fun displayName(language: String?): String? {
        val raw = language?.takeIf(String::isNotBlank) ?: return null
        val canonical = tokenizer.resolveLanguageId(raw)?.value
        return languages.firstOrNull { it.id == canonical }?.label ?: raw
    }

    /**
     * 按名称、标识或已注册别名筛选语言，便于输入 kt、js、sh 等缩写。
     * @param query 用户输入的检索词。
     * @return 与检索词匹配且确实可高亮的语言列表。
     */
    fun search(query: String): List<CodeLanguage> {
        val normalized = query.trim()
        val alias = tokenizer.resolveLanguageId(normalized)?.value
        return languages.filter {
            it.label.contains(normalized, ignoreCase = true) ||
                    it.id.contains(normalized, ignoreCase = true) || it.id == alias
        }
    }
}
