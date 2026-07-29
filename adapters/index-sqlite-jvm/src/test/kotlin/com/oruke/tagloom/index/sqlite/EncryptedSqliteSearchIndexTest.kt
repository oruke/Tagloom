package com.oruke.tagloom.index.sqlite

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
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 验证加密 SQLite 派生索引的查询、恢复与密钥生命周期。 */
class EncryptedSqliteSearchIndexTest {
    /**
     * 验证全文、标签、属性和双链查询，并确认敏感内容未以明文落盘。
     */
    @Test
    fun indexesQueriesAndEncryptsSensitiveDiskContent() = runBlocking {
        val directory = Files.createTempDirectory("tagloom-index-test")
        val database = directory.resolve("local.sqlite")
        val key = indexKey(11)
        val index = EncryptedSqliteSearchIndex.open(database, key)
        val source = sourceDocument()
        val target = targetDocument()

        index.rebuild(
            documents = listOf(source, target),
            checkpoint = SearchIndexCheckpoint("manifest-2", 2),
        )

        val fullTextHits = index.search(SearchIndexQuery(text = "不可泄漏词"))
        val shortTextHits = index.search(SearchIndexQuery(text = "词"))
        val structuredHits = index.search(
            SearchIndexQuery(
                tagIds = setOf(TagId("secret-tag")),
                propertyFilters = listOf(
                    PropertyIndexFilter(PropertyId("status"), "\"active-secret\""),
                ),
            ),
        )
        val backlinks = index.backlinks(ObjectId("target-note"), BlockId("target-block"))

        assertEquals(listOf(ObjectId("source-note")), fullTextHits.map { it.noteId })
        assertEquals(listOf(ObjectId("source-note")), shortTextHits.map { it.noteId })
        assertEquals(listOf(ObjectId("source-note")), structuredHits.map { it.noteId })
        assertEquals(listOf(ObjectId("source-note")), backlinks.map { it.sourceNoteId })
        assertEquals(SearchIndexCheckpoint("manifest-2", 2), index.readCheckpoint())

        index.close()
        assertTrue(key.isDestroyed)
        val diskBytes = Files.readAllBytes(database)
        assertFalse(diskBytes.containsUtf8("绝密标题"))
        assertFalse(diskBytes.containsUtf8("不可泄漏词"))
        assertFalse(diskBytes.containsUtf8("secret-tag"))
        assertFalse(diskBytes.containsUtf8("active-secret"))
        assertFalse(diskBytes.containsUtf8("SQLite format 3"))
    }

    /**
     * 验证错误密钥无法读取已有索引，并且失败路径销毁传入密钥。
     */
    @Test
    fun rejectsWrongKeyAndDestroysFailedSessionKey() = runBlocking {
        val database = Files.createTempDirectory("tagloom-index-wrong-key").resolve("local.sqlite")
        val originalKey = indexKey(21)
        val index = EncryptedSqliteSearchIndex.open(database, originalKey)
        index.replace(sourceDocument())
        index.close()

        val wrongKey = indexKey(99)
        assertFailsWith<Throwable> {
            EncryptedSqliteSearchIndex.open(database, wrongKey)
        }
        assertTrue(wrongKey.isDestroyed)
    }

    /**
     * 验证失败的全量重建回滚旧索引与旧 checkpoint，避免暴露半成品状态。
     */
    @Test
    fun rollsBackInterruptedRebuild() = runBlocking {
        val database = Files.createTempDirectory("tagloom-index-rollback").resolve("local.sqlite")
        val index = EncryptedSqliteSearchIndex.open(database, indexKey(31))
        index.rebuild(
            documents = listOf(sourceDocument()),
            checkpoint = SearchIndexCheckpoint("manifest-old", 1),
        )

        assertFailsWith<RebuildInterruptedException> {
            index.rebuild(
                documents = listOf(targetDocument()),
                checkpoint = SearchIndexCheckpoint("manifest-new", 1),
                onProgress = { progress ->
                    if (progress.completed == 1) {
                        throw RebuildInterruptedException()
                    }
                },
            )
        }

        assertEquals(
            listOf(ObjectId("source-note")),
            index.search(SearchIndexQuery(text = "不可泄漏词")).map { it.noteId },
        )
        assertEquals(SearchIndexCheckpoint("manifest-old", 1), index.readCheckpoint())
        index.close()
    }

    /**
     * 验证索引文件可删除并仅凭投影文档恢复，同时关闭后不再允许查询。
     */
    @Test
    fun rebuildsAfterIndexDeletionAndFailsClosed() = runBlocking {
        val database = Files.createTempDirectory("tagloom-index-rebuild").resolve("local.sqlite")
        val first = EncryptedSqliteSearchIndex.open(database, indexKey(41))
        first.rebuild(listOf(sourceDocument()), SearchIndexCheckpoint("manifest-1", 1))
        first.close()
        database.deleteIfExists()

        val secondKey = indexKey(41)
        val second = EncryptedSqliteSearchIndex.open(database, secondKey)
        second.rebuild(listOf(sourceDocument()), SearchIndexCheckpoint("manifest-1", 1))
        assertEquals(1, second.search(SearchIndexQuery(tagIds = setOf(TagId("secret-tag")))).size)
        second.close()

        assertTrue(secondKey.isDestroyed)
        val error = assertFailsWith<IllegalStateException> {
            second.search(SearchIndexQuery())
        }
        assertContains(error.message.orEmpty(), "已经关闭")
    }

    /**
     * 构造包含全文、标签、属性和 Block 级出链的源文档。
     *
     * @return 确定性源 Note 索引文档。
     */
    private fun sourceDocument(): NoteIndexDocument = NoteIndexDocument(
        noteId = ObjectId("source-note"),
        revision = "device-a:1",
        title = "绝密标题",
        bodyText = "这里包含不可泄漏词与检索正文",
        updatedAt = "2026-07-13T01:00:00Z",
        directTagIds = listOf(TagId("secret-tag")),
        propertyValues = mapOf(PropertyId("status") to "\"active-secret\""),
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

    /**
     * 构造反向链接目标文档。
     *
     * @return 确定性目标 Note 索引文档。
     */
    private fun targetDocument(): NoteIndexDocument = NoteIndexDocument(
        noteId = ObjectId("target-note"),
        revision = "device-a:2",
        title = "目标笔记",
        bodyText = "普通正文",
        updatedAt = "2026-07-13T00:00:00Z",
        directTagIds = emptyList(),
        propertyValues = emptyMap(),
        outboundLinks = emptyList(),
        deleted = false,
    )

    /**
     * 构造测试会话专用的确定性 256 位 INDEX 密钥。
     *
     * @param seed 区分测试密钥的非秘密种子。
     * @return 可由索引实例取得所有权的密钥材料。
     */
    private fun indexKey(seed: Int): SecretKeyMaterial = SecretKeyMaterial.from(
        SecretKeyKind.INDEX,
        ByteArray(32) { offset -> (seed + offset).toByte() },
    )

    /** 表示测试主动中止重建，而非 JDBC 故障。 */
    private class RebuildInterruptedException : RuntimeException()

    /**
     * 检查二进制文件是否包含指定 UTF-8 明文片段。
     *
     * @param text 不应泄漏到磁盘的测试文本。
     * @return 找到完整连续片段时为真。
     */
    private fun ByteArray.containsUtf8(text: String): Boolean {
        val needle = text.encodeToByteArray()
        if (needle.isEmpty() || needle.size > size) {
            return false
        }
        return indices.any { start ->
            start + needle.size <= size && needle.indices.all { offset -> this[start + offset] == needle[offset] }
        }
    }
}
