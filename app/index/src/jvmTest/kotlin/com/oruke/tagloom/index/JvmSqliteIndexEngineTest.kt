package com.oruke.tagloom.index

import com.oruke.tagloom.core.crypto.SecretKeyKind
import com.oruke.tagloom.core.crypto.SecretKeyMaterial
import com.oruke.tagloom.core.index.IndexLinkTypes
import com.oruke.tagloom.core.index.IndexedNoteLink
import com.oruke.tagloom.core.index.NoteIndexDocument
import com.oruke.tagloom.core.index.PropertyIndexFilter
import com.oruke.tagloom.core.index.SearchIndexCheckpoint
import com.oruke.tagloom.core.index.SearchIndexQuery
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.PropertyId
import com.oruke.tagloom.core.model.TagId
import com.oruke.tagloom.index.jvm.JvmSqliteIndexEngine
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 验证 JVM 加密 SQLite 索引的查询、重建回滚和密钥生命周期。 */
class JvmSqliteIndexEngineTest {
    /** 验证全文、标签、属性和双链查询，并确认索引文件没有明文内容。 */
    @Test
    fun indexesQueriesAndEncryptsSensitiveDiskContent() = runBlocking {
        val directory = Files.createTempDirectory("tagloom-index-test")
        val database = directory.resolve("local.sqlite")
        val key = indexKey(11)
        val index = JvmSqliteIndexEngine.open(database, key)
        index.rebuild(
            documents = listOf(sourceDocument(), targetDocument()),
            checkpoint = SearchIndexCheckpoint("manifest-2", 2),
        )

        assertEquals(listOf(ObjectId("source-note")), index.search(SearchIndexQuery(text = "searchable-word")).map { it.noteId })
        assertEquals(listOf(ObjectId("source-note")), index.search(SearchIndexQuery(text = "word")).map { it.noteId })
        assertEquals(
            listOf(ObjectId("source-note")),
            index.search(
                SearchIndexQuery(
                    tagIds = setOf(TagId("placeholder-tag")),
                    propertyFilters = listOf(
                        PropertyIndexFilter(PropertyId("status"), "\"placeholder-value\""),
                    ),
                ),
            ).map { it.noteId },
        )
        assertEquals(
            listOf(ObjectId("source-note")),
            index.backlinks(ObjectId("target-note"), BlockId("target-block")).map { it.sourceNoteId },
        )
        assertEquals(SearchIndexCheckpoint("manifest-2", 2), index.readCheckpoint())

        index.close()
        assertTrue(key.isDestroyed)
        val diskBytes = Files.readAllBytes(database)
        assertFalse(diskBytes.containsUtf8("sensitive-title"))
        assertFalse(diskBytes.containsUtf8("searchable-word"))
        assertFalse(diskBytes.containsUtf8("placeholder-tag"))
        assertFalse(diskBytes.containsUtf8("placeholder-value"))
        assertFalse(diskBytes.containsUtf8("SQLite format 3"))
    }

    /** 验证错误密钥无法读取已有索引，且失败路径销毁传入密钥。 */
    @Test
    fun rejectsWrongKeyAndDestroysFailedSessionKey() = runBlocking {
        val database = Files.createTempDirectory("tagloom-index-wrong-key").resolve("local.sqlite")
        val originalKey = indexKey(21)
        JvmSqliteIndexEngine.open(database, originalKey).close()

        val wrongKey = indexKey(99)
        assertFailsWith<Throwable> {
            JvmSqliteIndexEngine.open(database, wrongKey)
        }
        assertTrue(wrongKey.isDestroyed)
    }

    /** 验证被中止的重建回滚旧索引与旧 checkpoint。 */
    @Test
    fun rollsBackInterruptedRebuild() = runBlocking {
        val database = Files.createTempDirectory("tagloom-index-rollback").resolve("local.sqlite")
        val index = JvmSqliteIndexEngine.open(database, indexKey(31))
        index.rebuild(listOf(sourceDocument()), SearchIndexCheckpoint("manifest-old", 1))

        assertFailsWith<RebuildInterruptedException> {
            index.rebuild(
                documents = listOf(targetDocument()),
                checkpoint = SearchIndexCheckpoint("manifest-new", 1),
                onProgress = { progress ->
                    if (progress.completed == 1) throw RebuildInterruptedException()
                },
            )
        }
        assertEquals(listOf(ObjectId("source-note")), index.search(SearchIndexQuery(text = "searchable-word")).map { it.noteId })
        assertEquals(SearchIndexCheckpoint("manifest-old", 1), index.readCheckpoint())
        index.close()
    }

    /** 验证删除索引后可从同一投影恢复，关闭后所有入口都拒绝访问。 */
    @Test
    fun rebuildsAfterDeletionAndFailsClosed() = runBlocking {
        val database = Files.createTempDirectory("tagloom-index-rebuild").resolve("local.sqlite")
        val first = JvmSqliteIndexEngine.open(database, indexKey(41))
        first.rebuild(listOf(sourceDocument()), SearchIndexCheckpoint("manifest-1", 1))
        first.close()
        database.deleteIfExists()

        val secondKey = indexKey(41)
        val second = JvmSqliteIndexEngine.open(database, secondKey)
        second.rebuild(listOf(sourceDocument()), SearchIndexCheckpoint("manifest-1", 1))
        assertEquals(1, second.search(SearchIndexQuery(tagIds = setOf(TagId("placeholder-tag")))).size)
        second.close()
        assertTrue(secondKey.isDestroyed)
        assertFailsWith<IllegalStateException> { second.search(SearchIndexQuery()) }
        Unit
    }

    /** 验证无序标签集合和属性过滤列表的 SQL 占位符与参数绑定保持同一排序。 */
    @Test
    fun bindsMultipleFiltersInDeterministicOrder() = runBlocking {
        val database = Files.createTempDirectory("tagloom-index-filter-order").resolve("local.sqlite")
        val index = JvmSqliteIndexEngine.open(database, indexKey(51))
        try {
            index.rebuild(
                documents = listOf(
                    sourceDocument().copy(
                        directTagIds = listOf(TagId("z-tag"), TagId("a-tag")),
                        propertyValues = mapOf(
                            PropertyId("z-property") to "\"z-value\"",
                            PropertyId("a-property") to "\"a-value\"",
                        ),
                    ),
                ),
                checkpoint = SearchIndexCheckpoint("manifest-filter-order", 1),
            )

            val hits = index.search(
                SearchIndexQuery(
                    tagIds = linkedSetOf(TagId("z-tag"), TagId("a-tag")),
                    propertyFilters = listOf(
                        PropertyIndexFilter(PropertyId("z-property"), "\"z-value\""),
                        PropertyIndexFilter(PropertyId("a-property"), "\"a-value\""),
                    ),
                ),
            )
            assertEquals(listOf(ObjectId("source-note")), hits.map { it.noteId })
        } finally {
            index.close()
        }
    }

    /** 构造包含全文、标签、属性和 Block 级出链的源文档。 */
    private fun sourceDocument(): NoteIndexDocument = NoteIndexDocument(
        noteId = ObjectId("source-note"),
        revision = "device-a:1",
        title = "sensitive-title",
        bodyText = "body with searchable-word and other text",
        updatedAt = "2026-08-28T01:00:00Z",
        directTagIds = listOf(TagId("placeholder-tag")),
        propertyValues = mapOf(PropertyId("status") to "\"placeholder-value\""),
        outboundLinks = listOf(
            IndexedNoteLink(
                sourceBlockId = BlockId("source-block"),
                targetNoteId = ObjectId("target-note"),
                targetBlockId = BlockId("target-block"),
                linkType = IndexLinkTypes.NOTE_REFERENCE,
            ),
        ),
        deleted = false,
    )

    /** 构造反向链接目标文档。 */
    private fun targetDocument(): NoteIndexDocument = NoteIndexDocument(
        noteId = ObjectId("target-note"),
        revision = "device-a:2",
        title = "target-title",
        bodyText = "ordinary body",
        updatedAt = "2026-08-28T00:00:00Z",
        directTagIds = emptyList(),
        propertyValues = emptyMap(),
        outboundLinks = emptyList(),
        deleted = false,
    )

    /** 构造测试会话专用的确定性 256 位 INDEX 密钥。 */
    private fun indexKey(seed: Int): SecretKeyMaterial = SecretKeyMaterial.from(
        SecretKeyKind.INDEX,
        ByteArray(32) { offset -> (seed + offset).toByte() },
    )

    /**
     * 检查二进制文件是否包含指定 UTF-8 片段。
     *
     * @param text 不应以明文写入索引文件的测试文本。
     * @return 找到完整连续片段时为真。
     */
    private fun ByteArray.containsUtf8(text: String): Boolean {
        val needle = text.encodeToByteArray()
        return needle.isNotEmpty() && needle.size <= size && indices.any { start ->
            start + needle.size <= size && needle.indices.all { offset -> this[start + offset] == needle[offset] }
        }
    }

    /** 表示测试主动中止重建，而非 JDBC 故障。 */
    private class RebuildInterruptedException : RuntimeException()
}
