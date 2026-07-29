package com.oruke.tagloom.core.vector

import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.ObjectId

/** 描述由已确认 Block 派生且可删除重建的 embedding。 */
data class BlockEmbedding(
    /** 来源 Note ID。 */
    val noteId: ObjectId,
    /** 来源 Block ID。 */
    val blockId: BlockId,
    /** 产生 embedding 的模型或本地版本 ID。 */
    val modelId: String,
    /** 来源对象 revision，用于识别陈旧向量。 */
    val sourceRevision: String,
    /** 固定维度浮点向量；实现不得把它视为权威业务状态。 */
    val values: List<Float>,
) {
    init {
        require(modelId.isNotBlank()) { "Embedding 模型 ID 不能为空" }
        require(sourceRevision.isNotBlank()) { "Embedding 来源 revision 不能为空" }
        require(values.isNotEmpty() && values.all(Float::isFinite)) { "Embedding 向量必须非空且有限" }
    }
}

/** 描述一个 Block 级向量查询命中。 */
data class VectorSearchHit(
    /** 命中 Note ID。 */
    val noteId: ObjectId,
    /** 命中 Block ID。 */
    val blockId: BlockId,
    /** 由本地索引截断的预览文本。 */
    val snippet: String,
    /** 归一化相似度，越大越相关。 */
    val similarity: Double,
) {
    init {
        require(similarity.isFinite()) { "向量相似度必须是有限数" }
    }
}

/** 抽象可删除、可从已确认快照重建的 Block 向量索引。 */
interface VectorIndexPort {
    /**
     * 原子替换一篇 Note 的全部派生 embeddings。
     *
     * @param noteId 来源 Note。
     * @param embeddings 当前 revision 的完整 Block embeddings。
     */
    suspend fun replaceNote(
        noteId: ObjectId,
        embeddings: List<BlockEmbedding>,
    )

    /**
     * 按 query embedding 检索相似 Block。
     *
     * @param query 查询向量。
     * @param limit 最大返回数量。
     * @return 相似度降序命中。
     */
    suspend fun search(
        query: List<Float>,
        limit: Int,
    ): List<VectorSearchHit>

    /**
     * 删除一篇 Note 的全部派生向量。
     *
     * @param noteId 待删除 Note。
     */
    suspend fun removeNote(noteId: ObjectId)

    /** 删除全部派生向量与 checkpoint。 */
    suspend fun clear()
}

/** 抽象本地或明确授权远程 embedding 模型。 */
fun interface EmbeddingProviderPort {
    /**
     * 为一批已经完成权限与隐私判定的 Block 文本生成向量。
     *
     * @param texts 按 Block 顺序排列的最小文本。
     * @return 与输入等长且维度一致的向量。
     */
    suspend fun embed(texts: List<String>): List<List<Float>>
}

/** 描述关键词与向量 Reciprocal Rank Fusion 的 Note 级结果。 */
data class HybridSearchHit(
    /** 命中 Note ID。 */
    val noteId: ObjectId,
    /** 代表性向量 Block；只有关键词命中时为空。 */
    val representativeBlockId: BlockId?,
    /** 关键词结果中的一基排名；未命中为空。 */
    val keywordRank: Int?,
    /** 向量结果中的一基排名；未命中为空。 */
    val vectorRank: Int?,
    /** 加权 RRF 分数，越大越相关。 */
    val combinedScore: Double,
)
