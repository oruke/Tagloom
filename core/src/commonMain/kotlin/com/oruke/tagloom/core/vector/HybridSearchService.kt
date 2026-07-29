package com.oruke.tagloom.core.vector

import com.oruke.tagloom.core.index.SearchIndexHit
import com.oruke.tagloom.core.model.ObjectId

/** 使用加权 Reciprocal Rank Fusion 合并关键词 Note 命中与 Block 向量命中。 */
object HybridSearchService {
    /**
     * 先把同一 Note 的多个向量 Block 折叠为最高相似度项，再按两路排名融合并稳定排序。
     *
     * @param keywordHits 已按相关度排列的关键词命中。
     * @param vectorHits 已按相似度排列的 Block 向量命中。
     * @param limit 最大返回 Note 数量。
     * @param keywordWeight 关键词排名权重。
     * @param vectorWeight 向量排名权重。
     * @return 合并后 Note 级命中。
     */
    fun merge(
        keywordHits: List<SearchIndexHit>,
        vectorHits: List<VectorSearchHit>,
        limit: Int = 50,
        keywordWeight: Double = 0.6,
        vectorWeight: Double = 0.4,
    ): List<HybridSearchHit> {
        require(limit in 1..500) { "混合搜索 limit 必须在 1 到 500 之间" }
        require(keywordWeight >= 0.0 && vectorWeight >= 0.0 && keywordWeight + vectorWeight > 0.0) {
            "混合搜索权重必须非负且至少一个大于零"
        }
        val keywordRanks = keywordHits.distinctBy(SearchIndexHit::noteId)
            .mapIndexed { index, hit -> hit.noteId to index + 1 }
            .toMap()
        val bestVectorByNote = vectorHits
            .groupBy(VectorSearchHit::noteId)
            .mapValues { (_, hits) ->
                hits.sortedWith(compareByDescending<VectorSearchHit> { it.similarity }.thenBy { it.blockId.value }).first()
            }
        val vectorRanks = vectorHits.distinctBy(VectorSearchHit::noteId)
            .mapIndexed { index, hit -> hit.noteId to index + 1 }
            .toMap()
        val allNoteIds = (keywordRanks.keys + vectorRanks.keys).sortedBy(ObjectId::value)

        return allNoteIds.map { noteId ->
            val keywordRank = keywordRanks[noteId]
            val vectorRank = vectorRanks[noteId]
            val score = rankScore(keywordRank, keywordWeight) + rankScore(vectorRank, vectorWeight)
            HybridSearchHit(
                noteId = noteId,
                representativeBlockId = bestVectorByNote[noteId]?.blockId,
                keywordRank = keywordRank,
                vectorRank = vectorRank,
                combinedScore = score,
            )
        }.sortedWith(compareByDescending<HybridSearchHit>(HybridSearchHit::combinedScore).thenBy { it.noteId.value })
            .take(limit)
    }

    /**
     * 把可选一基排名转换为加权 RRF 分数。
     *
     * @param rank 一基排名；未命中为空。
     * @param weight 当前检索通道权重。
     * @return 当前通道贡献分数。
     */
    private fun rankScore(
        rank: Int?,
        weight: Double,
    ): Double = rank?.let { value -> weight / (RRF_K + value) } ?: 0.0

    /** RRF 常用平滑常量，降低单一路第一名对另一检索通道的压制。 */
    private const val RRF_K: Double = 60.0
}
