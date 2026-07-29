package com.oruke.tagloom.index.sqlite

import com.oruke.tagloom.core.crypto.SecretKeyKind
import com.oruke.tagloom.core.crypto.SecretKeyMaterial
import com.oruke.tagloom.core.index.NoteIndexDocument
import com.oruke.tagloom.core.index.SearchIndexCheckpoint
import com.oruke.tagloom.core.index.SearchIndexQuery
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.PropertyId
import com.oruke.tagloom.core.model.TagId
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 在设计稿参考负载上记录加密 SQLite 全量重建和搜索延迟。
 */
class EncryptedSqlitePerformanceBenchmarkTest {
    /**
     * 验证 10,000 篇笔记、100,000 个 Block 等价正文的重建与搜索设计预算。
     */
    @Test
    fun rebuildsReferenceWorkloadWithinMvpBudget() = runBlocking {
        val directory = Files.createTempDirectory(TEST_DIRECTORY_PREFIX).toAbsolutePath().normalize()
        val index = EncryptedSqliteSearchIndex.open(directory.resolve("local.sqlite"), indexKey())
        try {
            val documents = List(NOTE_COUNT, ::benchmarkDocument)
            val rebuildStarted = System.nanoTime()
            index.rebuild(documents, SearchIndexCheckpoint("benchmark-manifest", NOTE_COUNT))
            val rebuildMillis = elapsedMillis(rebuildStarted)

            val searchLatencies = List(SEARCH_SAMPLE_COUNT) { sample ->
                val noteIndex = sample * 337 % NOTE_COUNT
                val searchStarted = System.nanoTime()
                val hits = index.search(SearchIndexQuery(text = searchTerm(noteIndex), limit = 10))
                val searchMillis = elapsedMillis(searchStarted)
                assertEquals(ObjectId(noteId(noteIndex)), hits.single().noteId)
                searchMillis
            }.sorted()
            val searchP95Millis = searchLatencies[p95Index(searchLatencies.size)]

            println(
                "TAGLOOM_INDEX_BENCHMARK notes=$NOTE_COUNT blocks=$BLOCK_COUNT " +
                    "rebuild_ms=$rebuildMillis search_p95_ms=$searchP95Millis",
            )
            assertTrue(rebuildMillis < REBUILD_BUDGET_MILLIS, "100,000 Block 索引重建超过 10 分钟预算")
            assertTrue(searchP95Millis < SEARCH_P95_BUDGET_MILLIS, "全文搜索 p95 超过 300ms 预算")
        } finally {
            index.close()
            deleteTestDirectory(directory)
        }
    }

    /**
     * 构造含 10 个 Block 等价物、标签和属性的索引文档。
     *
     * @param index Note 序号。
     * @return 参考负载索引文档。
     */
    private fun benchmarkDocument(index: Int): NoteIndexDocument = NoteIndexDocument(
        noteId = ObjectId(noteId(index)),
        revision = "benchmark:1",
        title = "Benchmark ${searchTerm(index)}",
        bodyText = List(BLOCKS_PER_NOTE) { block ->
            "block$block ${searchTerm(index)} reference workload content"
        }.joinToString("\n"),
        updatedAt = "2026-09-12T00:00:00Z",
        directTagIds = listOf(TagId("benchmark-tag-${index % 100}")),
        propertyValues = mapOf(PropertyId("benchmark-property") to "\"value-${index % 100}\""),
        outboundLinks = emptyList(),
        deleted = false,
    )

    /**
     * 生成固定宽度 Note ID。
     *
     * @param index Note 序号。
     * @return 不透明测试 ID。
     */
    private fun noteId(index: Int): String = "benchmark-note-${index.toString().padStart(5, '0')}"

    /**
     * 生成每篇文档唯一的 FTS 查询词。
     *
     * @param index Note 序号。
     * @return 不含分词标点的唯一词。
     */
    private fun searchTerm(index: Int): String = "benchmarkterm${index.toString().padStart(5, '0')}"

    /**
     * 计算从给定纳秒起点到当前的整毫秒时长。
     *
     * @param startedAtNanos `System.nanoTime` 起点。
     * @return 至少为零的整毫秒。
     */
    private fun elapsedMillis(startedAtNanos: Long): Long =
        ((System.nanoTime() - startedAtNanos) / NANOS_PER_MILLI).coerceAtLeast(0L)

    /**
     * 返回有序样本的 nearest-rank p95 下标。
     *
     * @param size 样本数量。
     * @return 从零开始的 p95 下标。
     */
    private fun p95Index(size: Int): Int {
        require(size > 0) { "p95 样本不能为空" }
        return ((size * 95 + 99) / 100 - 1).coerceIn(0, size - 1)
    }

    /**
     * 构造测试专用 256 位 INDEX 密钥。
     *
     * @return 由索引取得所有权的密钥材料。
     */
    private fun indexKey(): SecretKeyMaterial = SecretKeyMaterial.from(
        SecretKeyKind.INDEX,
        ByteArray(32) { offset -> (offset + 71).toByte() },
    )

    /**
     * 仅删除带固定测试前缀的基准目录。
     *
     * @param directory 待删除目录。
     */
    private fun deleteTestDirectory(directory: Path) {
        check(directory.fileName.toString().startsWith(TEST_DIRECTORY_PREFIX)) { "拒绝删除非基准目录" }
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private companion object {
        /** 参考负载 Note 数。 */
        const val NOTE_COUNT: Int = 10_000

        /** 每篇 Note 的 Block 等价行数。 */
        const val BLOCKS_PER_NOTE: Int = 10

        /** 参考负载总 Block 数。 */
        const val BLOCK_COUNT: Int = NOTE_COUNT * BLOCKS_PER_NOTE

        /** 搜索 p95 样本数。 */
        const val SEARCH_SAMPLE_COUNT: Int = 40

        /** 10 分钟重建预算。 */
        const val REBUILD_BUDGET_MILLIS: Long = 600_000L

        /** 300ms 搜索 p95 预算。 */
        const val SEARCH_P95_BUDGET_MILLIS: Long = 300L

        /** 纳秒到毫秒换算。 */
        const val NANOS_PER_MILLI: Long = 1_000_000L

        /** 受控临时目录前缀。 */
        const val TEST_DIRECTORY_PREFIX: String = "tagloom-index-benchmark-"
    }
}
