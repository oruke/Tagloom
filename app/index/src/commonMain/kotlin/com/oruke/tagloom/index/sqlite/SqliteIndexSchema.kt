package com.oruke.tagloom.index.sqlite


/** 管理可重建搜索索引的固定 SQLite schema。 */
internal object SqliteIndexSchema {
    /**
     * 配置数据库耐久性与安全选项，并以幂等方式初始化当前 schema。
     *
     * 索引可重建，因此首版不做原地迁移；schema 版本不一致时稳定失败，由上层删除索引后重建。
     *
     * @param connection 已通过密钥验证的加密连接。
     */
    fun initialize(connection: SqliteDriver) {
        CONFIGURATION_STATEMENTS.forEach { connection.execute(it) }
        SCHEMA_STATEMENTS.forEach { connection.execute(it) }
        connection.execute(
            "INSERT OR IGNORE INTO index_meta(meta_key, meta_value) VALUES(?, ?)",
            listOf(SCHEMA_VERSION_KEY, SCHEMA_VERSION),
        )
        val actualVersion = connection.query(
            "SELECT meta_value FROM index_meta WHERE meta_key = ?",
            listOf(SCHEMA_VERSION_KEY),
        ) { rows ->
            check(rows.next()) { "SQLite 索引缺少 schema 版本" }
            rows.text("meta_value")
        }
        if (actualVersion != SCHEMA_VERSION) {
            throw SqliteIndexSchemaMismatchException(
                "SQLite 索引 schema 不兼容，需要删除派生索引并重建",
            )
        }
    }

    /** SQLite 索引协议版本在元数据表中的键。 */
    private const val SCHEMA_VERSION_KEY: String = "schema_version"

    /** 当前 SQLite 索引协议版本。 */
    private const val SCHEMA_VERSION: String = "3"

    /** 每次打开连接必须恢复的数据库级设置。 */
    private val CONFIGURATION_STATEMENTS: List<String> = listOf(
        "PRAGMA foreign_keys = ON",
        "PRAGMA journal_mode = DELETE",
        "PRAGMA synchronous = FULL",
        "PRAGMA busy_timeout = 5000",
        "PRAGMA temp_store = MEMORY",
        "PRAGMA secure_delete = ON",
        "PRAGMA trusted_schema = OFF",
    )

    /** 当前版本完整且可从零创建的索引 schema。 */
    private val SCHEMA_STATEMENTS: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS index_meta(
            meta_key TEXT PRIMARY KEY NOT NULL,
            meta_value TEXT NOT NULL
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS note_documents(
            note_id TEXT PRIMARY KEY NOT NULL,
            revision TEXT NOT NULL,
            title TEXT NOT NULL,
            body_text TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            ai_access_policy TEXT NOT NULL DEFAULT 'ALLOW'
        )
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS note_documents_by_ai_policy
        ON note_documents(ai_access_policy, updated_at DESC, note_id ASC)
        """.trimIndent(),
        """
        CREATE VIRTUAL TABLE IF NOT EXISTS note_fts USING fts5(
            note_id UNINDEXED,
            title,
            body_text,
            tokenize = 'trigram'
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS note_tags(
            note_id TEXT NOT NULL REFERENCES note_documents(note_id) ON DELETE CASCADE,
            tag_id TEXT NOT NULL,
            PRIMARY KEY(note_id, tag_id)
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS note_tags_by_tag ON note_tags(tag_id, note_id)",
        """
        CREATE TABLE IF NOT EXISTS note_properties(
            note_id TEXT NOT NULL REFERENCES note_documents(note_id) ON DELETE CASCADE,
            property_id TEXT NOT NULL,
            canonical_value TEXT NOT NULL,
            PRIMARY KEY(note_id, property_id)
        )
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS note_properties_by_value
        ON note_properties(property_id, canonical_value, note_id)
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS note_links(
            source_note_id TEXT NOT NULL REFERENCES note_documents(note_id) ON DELETE CASCADE,
            source_block_id TEXT,
            target_note_id TEXT NOT NULL,
            target_block_id TEXT,
            link_type TEXT NOT NULL
        )
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS note_links_by_target
        ON note_links(target_note_id, target_block_id, source_note_id)
        """.trimIndent(),
    )
}
