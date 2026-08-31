package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.index.BacklinkIndexHit
import com.oruke.tagloom.core.index.NoteIndexDocument
import com.oruke.tagloom.core.index.SearchIndexCheckpoint
import com.oruke.tagloom.core.index.SearchIndexHit
import com.oruke.tagloom.core.index.SearchIndexQuery
import com.oruke.tagloom.core.index.SearchIndexRebuildProgress
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.index.IndexEngine
import com.oruke.tagloom.index.SearchEngine

/**
 * 把共享层暴露的索引与搜索能力绑定到当前 JVM 工作区会话。
 *
 * JVM 应用启动时工作区尚未解锁，不能提前打开一个临时 SQLite 并让 UI 误以为它代表当前
 * 工作区。因此该代理只持有工作区网关，所有操作在工作区打开后转发到会话当前的加密索引。
 * 索引与搜索共用代理也保证二者看到同一个 SQLite 连接和 checkpoint。
 *
 * @param gateway 当前 JVM 工作区生命周期网关。
 */
internal class JvmWorkspaceIndexEngine(
    private val gateway: DesktopWorkspaceGateway,
) : IndexEngine, SearchEngine {
    /**
     * 替换一篇 Note 的派生索引投影。
     *
     * @param document 从已确认快照生成的完整 Note 投影。
     */
    override suspend fun replace(document: NoteIndexDocument) {
        requireSession().replaceIndex(document)
    }

    /**
     * 从派生索引移除一篇 Note。
     *
     * @param noteId 待移除的权威 Note ID。
     */
    override suspend fun remove(noteId: ObjectId) {
        requireSession().removeIndex(noteId)
    }

    /**
     * 从权威快照集合重建整个索引。
     *
     * @param documents 当前确认点的 Note 投影集合。
     * @param checkpoint 重建成功后记录的权威边界。
     * @param onProgress 每完成一篇文档后的进度回调。
     */
    override suspend fun rebuild(
        documents: List<NoteIndexDocument>,
        checkpoint: SearchIndexCheckpoint,
        onProgress: (SearchIndexRebuildProgress) -> Unit,
    ) {
        requireSession().rebuildIndex(documents, checkpoint, onProgress)
    }

    /**
     * 读取索引覆盖的权威边界。
     *
     * @return 尚未完整重建时为空。
     */
    override suspend fun readCheckpoint(): SearchIndexCheckpoint? = requireSession().readIndexCheckpoint()

    /**
     * 清除全部派生索引数据，但保留数据库结构。
     */
    override suspend fun clear() {
        requireSession().clearIndex()
    }

    /**
     * 执行全文、标签和属性查询。
     *
     * @param query 已校验的平台无关查询条件。
     * @return 当前已确认索引中的稳定排序命中。
     */
    override suspend fun search(query: SearchIndexQuery): List<SearchIndexHit> = requireSession().search(query)

    /**
     * 查询指向目标 Note 或 Block 的反向链接。
     *
     * @param targetNoteId 目标 Note ID。
     * @param targetBlockId 可选目标 Block ID。
     * @return 当前已确认索引中的稳定排序反向链接。
     */
    override suspend fun backlinks(
        targetNoteId: ObjectId,
        targetBlockId: BlockId?,
    ): List<BacklinkIndexHit> = requireSession().backlinks(targetNoteId, targetBlockId)

    /**
     * 关闭当前 JVM 工作区会话并销毁会话密钥。
     *
     * 关闭由工作区网关统一执行，避免搜索代理和工作区网关各自持有一套生命周期。
     */
    override suspend fun close() {
        gateway.lock()
    }

    /**
     * 获取当前已解锁会话；锁定状态下必须快速失败，防止旧 UI 调用继续访问索引。
     *
     * @return 当前已认证工作区会话。
     */
    private fun requireSession(): DesktopWorkspaceSession =
        requireNotNull(gateway.sessionOrNull()) { "工作区尚未解锁" }
}
