package com.oruke.tagloom.core.vector

import com.oruke.tagloom.core.index.SearchIndexHit
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.ObjectId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 验证关键词与 Block 向量结果按 Note 折叠并确定性融合。 */
class HybridSearchServiceTest {
    /** 验证同时命中两路的 Note 优先，向量代表 Block 选择最高相似度且结果稳定。 */
    @Test
    fun fusesRanksAndCollapsesMultipleVectorBlocksPerNote() {
        val noteA = ObjectId("note-a")
        val noteB = ObjectId("note-b")
        val noteC = ObjectId("note-c")
        val keywordHits = listOf(keywordHit(noteA), keywordHit(noteB))
        val vectorHits = listOf(
            VectorSearchHit(noteB, BlockId("block-b-best"), "best", 0.95),
            VectorSearchHit(noteC, BlockId("block-c"), "semantic", 0.90),
            VectorSearchHit(noteB, BlockId("block-b-other"), "other", 0.70),
        )

        val result = HybridSearchService.merge(
            keywordHits = keywordHits,
            vectorHits = vectorHits,
            limit = 3,
            keywordWeight = 0.5,
            vectorWeight = 0.5,
        )

        assertEquals(listOf(noteB, noteA, noteC), result.map(HybridSearchHit::noteId))
        assertEquals(BlockId("block-b-best"), result.first().representativeBlockId)
        assertTrue(result[0].combinedScore > result[1].combinedScore)
    }

    /**
     * 创建关键词索引命中。
     *
     * @param noteId 命中 Note ID。
     * @return 测试关键词命中。
     */
    private fun keywordHit(noteId: ObjectId): SearchIndexHit = SearchIndexHit(
        noteId = noteId,
        title = noteId.value,
        snippet = "keyword",
        score = 1.0,
        updatedAt = "2026-07-13T00:00:00Z",
    )
}
