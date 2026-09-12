package com.oruke.tagloom.index.sqlite

import com.oruke.tagloom.core.index.SearchIndexQuery

/** 描述已构建的 SQLite 搜索 SQL 及其参数绑定行为。 */
internal data class SqliteSearchStatement(
    /** 使用占位符且不含用户拼接内容的 SQL。 */
    val sql: String,
    /** 是否启用 FTS5 MATCH 与相关度。 */
    val usesFullText: Boolean,
    /** FTS5 可安全解释的短语查询；未启用全文时为空。 */
    val fullTextQuery: String?,
    /** 因短词无法进入 trigram MATCH 而改用 LIKE 的字面量 token。 */
    val likeTokens: List<String>,
)

/** 把平台无关查询转换为只使用参数绑定的 SQLite SQL。 */
internal object SqliteSearchQueryBuilder {
    /**
     * 构建组合查询结构，不把用户文本、标签或属性值直接拼入 SQL。
     *
     * @param query 平台无关查询条件。
     * @return SQL 结构和经过 FTS5 字面量转义的全文参数。
     */
    fun build(query: SearchIndexQuery): SqliteSearchStatement {
        val searchTokens = searchTokens(query.text)
        val usesLikeFallback = searchTokens.any { token -> token.length < MINIMUM_TRIGRAM_LENGTH }
        val fullTextQuery = if (usesLikeFallback) null else toFtsLiteralQuery(searchTokens)
        val likeTokens = if (usesLikeFallback) searchTokens else emptyList()
        val sortedTagIds = query.tagIds.sortedBy { it.value }
        val sortedPropertyFilters = query.propertyFilters.sortedBy { it.propertyId.value }
        val usesFullText = fullTextQuery != null
        val conditions = mutableListOf<String>()

        if (usesFullText) {
            conditions += "note_fts MATCH ?"
        }
        likeTokens.forEach { _ ->
            conditions += "(d.title LIKE ? ESCAPE '\\' OR d.body_text LIKE ? ESCAPE '\\')"
        }
        sortedTagIds.forEach { _ ->
            conditions += """
                EXISTS(
                    SELECT 1 FROM note_tags tag_filter
                    WHERE tag_filter.note_id = d.note_id AND tag_filter.tag_id = ?
                )
            """.trimIndent()
        }
        sortedPropertyFilters.forEach { _ ->
            conditions += """
                EXISTS(
                    SELECT 1 FROM note_properties property_filter
                    WHERE property_filter.note_id = d.note_id
                      AND property_filter.property_id = ?
                      AND property_filter.canonical_value = ?
                )
            """.trimIndent()
        }

        val projection = if (usesFullText) {
            """
            SELECT d.note_id,
                   d.title,
                   snippet(note_fts, 2, '[', ']', ' … ', 18) AS result_snippet,
                   bm25(note_fts, 8.0, 1.0) AS result_score,
                   d.updated_at
            FROM note_documents d
            JOIN note_fts ON note_fts.note_id = d.note_id
            """.trimIndent()
        } else {
            """
            SELECT d.note_id,
                   d.title,
                   substr(d.body_text, 1, 180) AS result_snippet,
                   NULL AS result_score,
                   d.updated_at
            FROM note_documents d
            """.trimIndent()
        }
        val where = conditions.takeIf(List<String>::isNotEmpty)
            ?.joinToString(separator = "\nAND ", prefix = "\nWHERE ")
            .orEmpty()
        val ordering = if (usesFullText) {
            "\nORDER BY result_score ASC, d.updated_at DESC, d.note_id ASC"
        } else {
            "\nORDER BY d.updated_at DESC, d.note_id ASC"
        }

        return SqliteSearchStatement(
            sql = projection + where + ordering + "\nLIMIT ? OFFSET ?",
            usesFullText = usesFullText,
            fullTextQuery = fullTextQuery,
            likeTokens = likeTokens,
        )
    }

    /**
     * 按 SQL 构建顺序生成绑定参数，两个平台驱动共用同一顺序。
     * @param built 已构建 SQL 描述。
     * @param query 原始查询。
     * @return 文本、标签、属性和分页参数。
     */
    fun parameters(built: SqliteSearchStatement, query: SearchIndexQuery): List<Any?> = buildList {
        built.fullTextQuery?.let(::add)
        built.likeTokens.forEach { token ->
            val pattern = "%${escapeLikeLiteral(token)}%"
            add(pattern)
            add(pattern)
        }
        query.tagIds.sortedBy { it.value }.forEach { add(it.value) }
        query.propertyFilters.sortedBy { it.propertyId.value }.forEach { filter ->
            add(filter.propertyId.value)
            add(filter.canonicalValue)
        }
        add(query.limit)
        add(query.offset)
    }

    /**
     * 清理 NUL 并按空白拆分用户查询。
     *
     * @param text 用户全文输入。
     * @return 保持输入顺序的非空搜索 token。
     */
    private fun searchTokens(text: String): List<String> = text
        .replace('\u0000', ' ')
        .split(Regex("\\s+"))
        .filter(String::isNotBlank)

    /**
     * 把已拆分 token 转换为 FTS5 引号字面量的 AND 查询。
     *
     * @param tokens 不含空白 token 的用户搜索词。
     * @return 空列表返回空，否则返回不会改变 MATCH 语法结构的查询。
     */
    private fun toFtsLiteralQuery(tokens: List<String>): String? {
        if (tokens.isEmpty()) {
            return null
        }
        return tokens.joinToString(separator = " AND ") { token ->
            "\"${token.replace("\"", "\"\"")}\""
        }
    }

    /**
     * 转义 LIKE pattern 中具有语法含义的字符。
     *
     * @param value 待按字面量搜索的 token。
     * @return 可安全包裹 `%` 的 LIKE 字面量。
     */
    private fun escapeLikeLiteral(value: String): String = value
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")

    /** trigram tokenizer 能产生 token 的最短 UTF-16 长度。 */
    private const val MINIMUM_TRIGRAM_LENGTH: Int = 3
}
