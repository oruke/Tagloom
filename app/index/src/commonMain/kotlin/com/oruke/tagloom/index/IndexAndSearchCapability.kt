package com.oruke.tagloom.index

import com.oruke.tagloom.core.index.BacklinkIndexHit
import com.oruke.tagloom.core.index.NoteIndexDocument
import com.oruke.tagloom.core.index.SearchIndexCheckpoint
import com.oruke.tagloom.core.index.SearchIndexHit
import com.oruke.tagloom.core.index.SearchIndexQuery
import com.oruke.tagloom.core.index.SearchIndexRebuildProgress
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.ObjectId

/**
 * 定义可删除、可从已确认快照重建的跨平台索引能力。
 *
 * 索引永远是派生状态；实现失败不得回滚已经确认的权威快照。
 */
interface IndexEngine {
    /**
     * 原子替换一篇 Note 的全部派生索引内容。
     *
     * @param document 从已确认快照生成的完整文档；tombstone 应移除旧投影。
     */
    suspend fun replace(document: NoteIndexDocument)

    /**
     * 从派生索引移除一篇 Note。
     *
     * @param noteId 待移除的权威 Note ID。
     */
    suspend fun remove(noteId: ObjectId)

    /**
     * 从权威快照集合重建整个索引。
     *
     * @param documents 当前确认点的 Note 文档。
     * @param checkpoint 重建完成后要记录的权威边界。
     * @param onProgress 每完成一个文档后的进度回调。
     */
    suspend fun rebuild(
        documents: List<NoteIndexDocument>,
        checkpoint: SearchIndexCheckpoint,
        onProgress: (SearchIndexRebuildProgress) -> Unit = {},
    )

    /**
     * 读取索引覆盖的权威边界。
     *
     * @return 尚未完整重建时为空。
     */
    suspend fun readCheckpoint(): SearchIndexCheckpoint?

    /**
     * 删除全部派生索引数据，但保留实现所需的结构。
     */
    suspend fun clear()

    /**
     * 关闭索引并销毁其持有的会话材料。
     */
    suspend fun close()
}

/**
 * 定义全文、标签、属性和双链查询能力，与具体数据库或搜索库无关。
 */
interface SearchEngine {
    /**
     * 执行已校验的组合查询。
     *
     * @param query 平台无关的全文、标签、属性过滤条件。
     * @return 按实现约定稳定排序的命中列表。
     */
    suspend fun search(query: SearchIndexQuery): List<SearchIndexHit>

    /**
     * 查询指向目标 Note 或 Block 的反向链接。
     *
     * @param targetNoteId 目标 Note ID。
     * @param targetBlockId 可选目标 Block ID；为空时查询整篇 Note。
     * @return 稳定排序的反向链接。
     */
    suspend fun backlinks(
        targetNoteId: ObjectId,
        targetBlockId: BlockId? = null,
    ): List<BacklinkIndexHit>

    /**
     * 关闭搜索会话并销毁其持有的会话材料。
     */
    suspend fun close()
}

/**
 * 创建当前宿主的派生索引能力。
 *
 * @return 当前宿主的索引实现。
 */
expect fun createIndexEngine(): IndexEngine

/**
 * 创建当前宿主的查询能力。
 *
 * @return 当前宿主的搜索实现。
 */
expect fun createSearchEngine(): SearchEngine
