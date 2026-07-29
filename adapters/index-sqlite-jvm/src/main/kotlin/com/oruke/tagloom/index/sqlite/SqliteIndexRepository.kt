package com.oruke.tagloom.index.sqlite

import com.oruke.tagloom.core.index.BacklinkIndexHit
import com.oruke.tagloom.core.index.NoteIndexDocument
import com.oruke.tagloom.core.index.SearchIndexCheckpoint
import com.oruke.tagloom.core.index.SearchIndexHit
import com.oruke.tagloom.core.index.SearchIndexQuery
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.ObjectId
import java.sql.Connection
import java.sql.Types

/**
 * 封装单个 SQLite 连接上的索引行映射与查询。
 *
 * @param connection 由外层串行化访问的加密 JDBC 连接。
 */
internal class SqliteIndexRepository(
    private val connection: Connection,
) {
    /**
     * 删除指定 Note 的全部派生行。
     *
     * @param noteId 待删除 Note。
     */
    fun deleteDocument(noteId: ObjectId) {
        connection.prepareStatement("DELETE FROM note_fts WHERE note_id = ?").use { statement ->
            statement.setString(1, noteId.value)
            statement.executeUpdate()
        }
        connection.prepareStatement("DELETE FROM note_documents WHERE note_id = ?").use { statement ->
            statement.setString(1, noteId.value)
            statement.executeUpdate()
        }
    }

    /**
     * 删除全部文档派生行，但保留 schema 与 schema 版本。
     */
    fun deleteAllDocuments() {
        connection.createStatement().use { statement ->
            statement.executeUpdate("DELETE FROM note_fts")
            statement.executeUpdate("DELETE FROM note_links")
            statement.executeUpdate("DELETE FROM note_properties")
            statement.executeUpdate("DELETE FROM note_tags")
            statement.executeUpdate("DELETE FROM note_documents")
            statement.executeUpdate(
                "DELETE FROM index_meta WHERE meta_key IN ('checkpoint_revision', 'checkpoint_note_count')",
            )
        }
    }

    /**
     * 写入一篇非 tombstone 文档的全部索引行。
     *
     * 调用方必须先删除同 ID 旧文档，并在外层事务中调用，避免全文、标签、属性和链接出现部分状态。
     *
     * @param document 待写入的完整索引文档。
     */
    fun insertDocument(document: NoteIndexDocument) {
        require(!document.deleted) { "tombstone 不能写入 SQLite 索引" }
        connection.prepareStatement(
            """
            INSERT INTO note_documents(note_id, revision, title, body_text, updated_at)
            VALUES(?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, document.noteId.value)
            statement.setString(2, document.revision)
            statement.setString(3, document.title)
            statement.setString(4, document.bodyText)
            statement.setString(5, document.updatedAt)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            "INSERT INTO note_fts(note_id, title, body_text) VALUES(?, ?, ?)",
        ).use { statement ->
            statement.setString(1, document.noteId.value)
            statement.setString(2, document.title)
            statement.setString(3, document.bodyText)
            statement.executeUpdate()
        }

        insertTags(document)
        insertProperties(document)
        insertLinks(document)
    }

    /**
     * 执行组合搜索并映射为平台无关命中。
     *
     * @param query 已校验查询。
     * @return 按 SQL 稳定顺序排列的命中。
     */
    fun search(query: SearchIndexQuery): List<SearchIndexHit> {
        val built = SqliteSearchQueryBuilder.build(query)
        return connection.prepareStatement(built.sql).use { statement ->
            SqliteSearchQueryBuilder.bind(statement, built, query)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(
                            SearchIndexHit(
                                noteId = ObjectId(result.getString("note_id")),
                                title = result.getString("title"),
                                snippet = result.getString("result_snippet").orEmpty(),
                                score = if (built.usesFullText) result.getDouble("result_score") else null,
                                updatedAt = result.getString("updated_at"),
                            ),
                        )
                    }
                }
            }
        }
    }

    /**
     * 查询目标 Note 或 Block 的反向链接。
     *
     * @param targetNoteId 目标 Note。
     * @param targetBlockId 可选目标 Block。
     * @return 稳定排序的反向链接。
     */
    fun backlinks(
        targetNoteId: ObjectId,
        targetBlockId: BlockId?,
    ): List<BacklinkIndexHit> {
        val targetBlockCondition = if (targetBlockId == null) "" else " AND l.target_block_id = ?"
        val sql = """
            SELECT l.source_note_id,
                   d.title AS source_title,
                   l.source_block_id,
                   l.target_block_id,
                   l.link_type
            FROM note_links l
            JOIN note_documents d ON d.note_id = l.source_note_id
            WHERE l.target_note_id = ?$targetBlockCondition
            ORDER BY d.updated_at DESC, l.source_note_id ASC, l.source_block_id ASC, l.link_type ASC
        """.trimIndent()
        return connection.prepareStatement(sql).use { statement ->
            statement.setString(1, targetNoteId.value)
            targetBlockId?.let { statement.setString(2, it.value) }
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(
                            BacklinkIndexHit(
                                sourceNoteId = ObjectId(result.getString("source_note_id")),
                                sourceTitle = result.getString("source_title"),
                                sourceBlockId = result.getString("source_block_id")?.let(::BlockId),
                                targetBlockId = result.getString("target_block_id")?.let(::BlockId),
                                linkType = result.getString("link_type"),
                            ),
                        )
                    }
                }
            }
        }
    }

    /**
     * 覆盖索引 checkpoint 元数据。
     *
     * @param checkpoint 已完整投影的权威边界。
     */
    fun writeCheckpoint(checkpoint: SearchIndexCheckpoint) {
        upsertMeta(CHECKPOINT_REVISION_KEY, checkpoint.manifestRevision)
        upsertMeta(CHECKPOINT_NOTE_COUNT_KEY, checkpoint.noteCount.toString())
    }

    /**
     * 读取最后一次完整写入的 checkpoint。
     *
     * @return 两个字段任一缺失时返回空，避免把部分元数据误判为完整边界。
     */
    fun readCheckpoint(): SearchIndexCheckpoint? {
        val revision = readMeta(CHECKPOINT_REVISION_KEY) ?: return null
        val count = readMeta(CHECKPOINT_NOTE_COUNT_KEY)?.toIntOrNull() ?: return null
        return SearchIndexCheckpoint(revision, count)
    }

    /**
     * 批量写入 Note 标签关联。
     *
     * @param document 当前 Note 完整文档。
     */
    private fun insertTags(document: NoteIndexDocument) {
        connection.prepareStatement(
            "INSERT INTO note_tags(note_id, tag_id) VALUES(?, ?)",
        ).use { statement ->
            document.directTagIds.forEach { tagId ->
                statement.setString(1, document.noteId.value)
                statement.setString(2, tagId.value)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    /**
     * 批量写入 Note 属性规范值。
     *
     * @param document 当前 Note 完整文档。
     */
    private fun insertProperties(document: NoteIndexDocument) {
        connection.prepareStatement(
            "INSERT INTO note_properties(note_id, property_id, canonical_value) VALUES(?, ?, ?)",
        ).use { statement ->
            document.propertyValues.forEach { (propertyId, value) ->
                statement.setString(1, document.noteId.value)
                statement.setString(2, propertyId.value)
                statement.setString(3, value)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    /**
     * 批量写入 Note 结构化出链。
     *
     * @param document 当前 Note 完整文档。
     */
    private fun insertLinks(document: NoteIndexDocument) {
        connection.prepareStatement(
            """
            INSERT INTO note_links(
                source_note_id, source_block_id, target_note_id, target_block_id, link_type
            ) VALUES(?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            document.outboundLinks.forEach { link ->
                statement.setString(1, document.noteId.value)
                setNullableString(statement, 2, link.sourceBlockId?.value)
                statement.setString(3, link.targetNoteId.value)
                setNullableString(statement, 4, link.targetBlockId?.value)
                statement.setString(5, link.linkType)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    /**
     * 写入或覆盖单个元数据值。
     *
     * @param key 稳定元数据键。
     * @param value 元数据文本值。
     */
    private fun upsertMeta(
        key: String,
        value: String,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO index_meta(meta_key, meta_value) VALUES(?, ?)
            ON CONFLICT(meta_key) DO UPDATE SET meta_value = excluded.meta_value
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, key)
            statement.setString(2, value)
            statement.executeUpdate()
        }
    }

    /**
     * 读取单个元数据值。
     *
     * @param key 稳定元数据键。
     * @return 不存在时为空。
     */
    private fun readMeta(key: String): String? = connection.prepareStatement(
        "SELECT meta_value FROM index_meta WHERE meta_key = ?",
    ).use { statement ->
        statement.setString(1, key)
        statement.executeQuery().use { result ->
            if (result.next()) result.getString(1) else null
        }
    }

    /**
     * 给 PreparedStatement 绑定可空文本。
     *
     * @param statement 目标 JDBC 语句。
     * @param index 一基参数位置。
     * @param value 可空文本值。
     */
    private fun setNullableString(
        statement: java.sql.PreparedStatement,
        index: Int,
        value: String?,
    ) {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR)
        } else {
            statement.setString(index, value)
        }
    }

    /** checkpoint 的 manifest 修订键。 */
    private companion object {
        const val CHECKPOINT_REVISION_KEY: String = "checkpoint_revision"
        const val CHECKPOINT_NOTE_COUNT_KEY: String = "checkpoint_note_count"
    }
}
