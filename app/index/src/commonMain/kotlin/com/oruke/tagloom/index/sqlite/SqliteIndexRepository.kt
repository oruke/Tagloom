package com.oruke.tagloom.index.sqlite

import com.oruke.tagloom.core.index.BacklinkIndexHit
import com.oruke.tagloom.core.index.NoteIndexDocument
import com.oruke.tagloom.core.index.SearchIndexCheckpoint
import com.oruke.tagloom.core.index.SearchIndexHit
import com.oruke.tagloom.core.index.SearchIndexQuery
import com.oruke.tagloom.core.model.AiAccessPolicy
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.ObjectId

/** 共用 SQLite 索引映射；JDBC 和 Android SQLCipher 仅负责执行参数化语句。 */
internal class SqliteIndexRepository(
    /** 外层已串行化访问的加密 SQL 会话。 */
    private val connection: SqliteDriver,
) {
    /**
     * 删除文档和全文行，关联数据由外键级联删除。
     * @param noteId 待删除的权威 Note ID。
     */
    fun deleteDocument(noteId: ObjectId) {
        connection.execute("DELETE FROM note_fts WHERE note_id = ?", listOf(noteId.value))
        connection.execute("DELETE FROM note_documents WHERE note_id = ?", listOf(noteId.value))
    }

    /** 在外层事务中删除所有派生行和 checkpoint，保留 schema 版本。 */
    fun deleteAllDocuments() {
        listOf("note_fts", "note_links", "note_properties", "note_tags", "note_documents").forEach { table ->
            connection.execute("DELETE FROM $table")
        }
        connection.execute("DELETE FROM index_meta WHERE meta_key IN ('checkpoint_revision', 'checkpoint_note_count')")
    }

    /**
     * 在同一事务中写入文档、全文、标签、属性和链接。
     * @param document 已确认快照生成的非 tombstone 投影。
     */
    fun insertDocument(document: NoteIndexDocument) {
        require(!document.deleted) { "tombstone 不能写入 SQLite 索引" }
        // 文档行先于关联行写入，以保留外键一致性；任何步骤失败均由外层整体回滚。
        connection.execute(
            "INSERT INTO note_documents(note_id, revision, title, body_text, updated_at, ai_access_policy) VALUES(?, ?, ?, ?, ?, ?)",
            listOf(
                document.noteId.value,
                document.revision,
                document.title,
                document.bodyText,
                document.updatedAt,
                document.aiAccessPolicy.name,
            ),
        )
        connection.execute(
            "INSERT INTO note_fts(note_id, title, body_text) VALUES(?, ?, ?)",
            listOf(document.noteId.value, document.title, document.bodyText),
        )
        document.directTagIds.forEach { tag ->
            connection.execute("INSERT INTO note_tags(note_id, tag_id) VALUES(?, ?)", listOf(document.noteId.value, tag.value))
        }
        document.propertyValues.forEach { (property, value) ->
            connection.execute(
                "INSERT INTO note_properties(note_id, property_id, canonical_value) VALUES(?, ?, ?)",
                listOf(document.noteId.value, property.value, value),
            )
        }
        document.outboundLinks.forEach { link ->
            connection.execute(
                "INSERT INTO note_links(source_note_id, source_block_id, target_note_id, target_block_id, link_type) VALUES(?, ?, ?, ?, ?)",
                listOf(document.noteId.value, link.sourceBlockId?.value, link.targetNoteId.value, link.targetBlockId?.value, link.linkType),
            )
        }
    }

    /**
     * 执行统一的全文、标签和属性查询。
     * @param query 已校验且包含分页信息的查询。
     * @return 按相关度、时间和 ID 稳定排序的结果。
     */
    fun search(query: SearchIndexQuery): List<SearchIndexHit> {
        val built = SqliteSearchQueryBuilder.build(query)
        return connection.query(built.sql, SqliteSearchQueryBuilder.parameters(built, query)) { rows ->
            buildList {
                while (rows.next()) {
                    add(SearchIndexHit(
                        noteId = ObjectId(requireNotNull(rows.text("note_id"))),
                        title = requireNotNull(rows.text("title")),
                        snippet = rows.text("result_snippet").orEmpty(),
                        score = if (built.usesFullText) rows.number("result_score") else null,
                        updatedAt = requireNotNull(rows.text("updated_at")),
                    ))
                }
            }
        }
    }

    /**
     * 统计在相同查询条件下因不满足 `allowedAiPolicies` 而在分页前被排除的笔记篇数。
     * @param query 包含 `allowedAiPolicies` 的查询。
     * @return 被策略保护排除的篇数。
     */
    fun countExcludedByPolicy(query: SearchIndexQuery): Int {
        val (sql, params) = SqliteSearchQueryBuilder.buildExcludedByPolicyCount(query) ?: return 0
        return connection.query(sql, params) { rows ->
            if (rows.next()) rows.text("excluded_count")?.toIntOrNull() ?: 0 else 0
        }
    }

    /**
     * 查询笔记或 Block 的反向链接，并在指定策略白名单时预先过滤来源笔记的 AI 访问策略。
     * @param targetNoteId 目标笔记 ID。
     * @param targetBlockId 可选目标 Block ID。
     * @param allowedAiPolicies 允许暴露反向链接摘要的来源笔记策略集合；为空表示人类持有者查看全部。
     * @return 稳定排序的反向链接。
     */
    fun backlinks(
        targetNoteId: ObjectId,
        targetBlockId: BlockId?,
        allowedAiPolicies: Set<AiAccessPolicy>? = null,
    ): List<BacklinkIndexHit> {
        val sortedPolicies = allowedAiPolicies?.map { it.name }?.sorted()
        if (sortedPolicies != null && sortedPolicies.isEmpty()) {
            return emptyList()
        }
        val blockCondition = if (targetBlockId == null) "" else " AND l.target_block_id = ?"
        val policyCondition = if (sortedPolicies == null) {
            ""
        } else {
            " AND d.ai_access_policy IN (${sortedPolicies.joinToString { "?" }})"
        }
        val sql = """
            SELECT l.source_note_id, d.title AS source_title, l.source_block_id, l.target_block_id, l.link_type
            FROM note_links l JOIN note_documents d ON d.note_id = l.source_note_id
            WHERE l.target_note_id = ?$blockCondition$policyCondition
            ORDER BY d.updated_at DESC, l.source_note_id ASC, l.source_block_id ASC, l.link_type ASC
        """.trimIndent()
        val parameters = buildList<Any?> {
            add(targetNoteId.value)
            targetBlockId?.value?.let(::add)
            sortedPolicies?.forEach(::add)
        }
        return connection.query(sql, parameters) { rows ->
            buildList {
                while (rows.next()) {
                    add(BacklinkIndexHit(
                        sourceNoteId = ObjectId(requireNotNull(rows.text("source_note_id"))),
                        sourceTitle = requireNotNull(rows.text("source_title")),
                        sourceBlockId = rows.text("source_block_id")?.let(::BlockId),
                        targetBlockId = rows.text("target_block_id")?.let(::BlockId),
                        linkType = requireNotNull(rows.text("link_type")),
                    ))
                }
            }
        }
    }

    /**
     * 在投影事务内覆盖完整 checkpoint。
     * @param checkpoint 已完成投影的权威边界。
     */
    fun writeCheckpoint(checkpoint: SearchIndexCheckpoint) {
        upsertMeta(CHECKPOINT_REVISION_KEY, checkpoint.manifestRevision)
        upsertMeta(CHECKPOINT_NOTE_COUNT_KEY, checkpoint.noteCount.toString())
    }

    /**
     * 读取最后完整提交的索引边界。
     * @return 字段缺失时为空，禁止把部分元数据解释为完整 checkpoint。
     */
    fun readCheckpoint(): SearchIndexCheckpoint? {
        val revision = readMeta(CHECKPOINT_REVISION_KEY) ?: return null
        val count = readMeta(CHECKPOINT_NOTE_COUNT_KEY)?.toIntOrNull() ?: return null
        return SearchIndexCheckpoint(revision, count)
    }

    /**
     * 通过绑定参数写入元数据。
     * @param key 固定元数据键。
     * @param value 规范化值。
     */
    private fun upsertMeta(key: String, value: String) {
        connection.execute(
            "INSERT INTO index_meta(meta_key, meta_value) VALUES(?, ?) ON CONFLICT(meta_key) DO UPDATE SET meta_value = excluded.meta_value",
            listOf(key, value),
        )
    }

    /**
     * 读取元数据值。
     * @param key 固定元数据键。
     * @return 不存在时为空。
     */
    private fun readMeta(key: String): String? =
        connection.query("SELECT meta_value FROM index_meta WHERE meta_key = ?", listOf(key)) { rows ->
            if (rows.next()) rows.text("meta_value") else null
        }

    private companion object {
        /** 已确认 manifest 修订的元数据键。 */
        const val CHECKPOINT_REVISION_KEY: String = "checkpoint_revision"
        /** 非 tombstone 笔记数量的元数据键。 */
        const val CHECKPOINT_NOTE_COUNT_KEY: String = "checkpoint_note_count"
    }
}
