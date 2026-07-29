package com.oruke.tagloom.core.index

import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.ObjectId

/**
 * 定义可删除、可从已确认快照重建的本地搜索索引边界。
 *
 * 实现不得参与权威快照提交；索引失败只能降级搜索能力，不能回滚已经确认的业务事务。
 */
interface SearchIndexPort {
    /**
     * 原子替换一篇笔记的全部全文、标签、属性和出链索引。
     *
     * @param document 从已确认快照投影的完整文档；tombstone 会删除旧索引。
     */
    suspend fun replace(document: NoteIndexDocument)

    /**
     * 从派生索引移除一篇笔记。
     *
     * @param noteId 待移除的权威 Note 标识。
     */
    suspend fun remove(noteId: ObjectId)

    /**
     * 执行全文、标签和属性组合查询。
     *
     * @param query 已校验的组合查询。
     * @return 按全文相关度或更新时间排序的命中列表。
     */
    suspend fun search(query: SearchIndexQuery): List<SearchIndexHit>

    /**
     * 查询指向目标 Note 或指定 Block 的反向链接。
     *
     * @param targetNoteId 目标 Note。
     * @param targetBlockId 可选目标 Block；为空时返回指向整篇 Note 与其任意 Block 的链接。
     * @return 稳定排序的反向链接列表。
     */
    suspend fun backlinks(
        targetNoteId: ObjectId,
        targetBlockId: BlockId? = null,
    ): List<BacklinkIndexHit>

    /**
     * 在单个数据库事务中从零重建派生索引。
     *
     * 取消或异常必须回滚整个事务，保留重建前的完整索引和 checkpoint。
     *
     * @param documents 当前已确认 Note 快照投影出的文档。
     * @param checkpoint 重建成功后写入的新权威边界。
     * @param onProgress 每完成一篇文档后的进度回调。
     */
    suspend fun rebuild(
        documents: List<NoteIndexDocument>,
        checkpoint: SearchIndexCheckpoint,
        onProgress: (SearchIndexRebuildProgress) -> Unit = {},
    )

    /**
     * 读取索引当前覆盖的权威边界。
     *
     * @return 尚未完整重建时返回空，否则返回最后一次成功 checkpoint。
     */
    suspend fun readCheckpoint(): SearchIndexCheckpoint?

    /**
     * 删除全部派生数据和 checkpoint，但保留数据库结构。
     */
    suspend fun clear()

    /**
     * 关闭底层索引连接并销毁适配器持有的会话密钥。
     *
     * 关闭后所有业务操作必须稳定失败，直到工作区重新解锁并创建新实例。
     */
    suspend fun close()
}
