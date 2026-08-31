package com.oruke.tagloom.index.jvm

import com.oruke.tagloom.core.crypto.SecretKeyKind
import com.oruke.tagloom.core.crypto.SecretKeyMaterial
import com.oruke.tagloom.core.index.BacklinkIndexHit
import com.oruke.tagloom.core.index.NoteIndexDocument
import com.oruke.tagloom.core.index.SearchIndexCheckpoint
import com.oruke.tagloom.core.index.SearchIndexHit
import com.oruke.tagloom.core.index.SearchIndexPort
import com.oruke.tagloom.core.index.SearchIndexQuery
import com.oruke.tagloom.core.index.SearchIndexRebuildProgress
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.index.IndexEngine
import com.oruke.tagloom.index.SearchEngine
import com.oruke.tagloom.index.sqlite.EncryptedSqliteSearchIndex
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Comparator
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 把 JVM 加密 SQLite 实现暴露为索引和搜索两项跨平台能力。
 *
 * 一个实例共享同一条受锁保护的 SQLite 会话，避免索引写入与查询分别打开连接后看到不同的派生状态。
 *
 * @param delegate 已打开并完成 schema 校验的 SQLite 索引。
 * @param cleanup 关闭后执行的临时资源清理动作。
 */
class JvmSqliteIndexEngine private constructor(
    private val delegate: SearchIndexPort,
    private val cleanup: () -> Unit = {},
) : IndexEngine, SearchEngine {
    /** 保证临时数据库目录清理动作只执行一次。 */
    private val cleanupOnce: AtomicBoolean = AtomicBoolean(false)

    /**
     * 原子替换一篇 Note 的全部派生索引。
     *
     * @param document 从已确认快照生成的完整文档。
     */
    override suspend fun replace(document: NoteIndexDocument) {
        delegate.replace(document)
    }

    /**
     * 从派生索引移除一篇 Note。
     *
     * @param noteId 待移除的权威 Note ID。
     */
    override suspend fun remove(noteId: ObjectId) {
        delegate.remove(noteId)
    }

    /**
     * 从权威快照集合重建整个索引。
     *
     * @param documents 当前确认点的 Note 文档。
     * @param checkpoint 重建完成后要记录的权威边界。
     * @param onProgress 每完成一个文档后的进度回调。
     */
    override suspend fun rebuild(
        documents: List<NoteIndexDocument>,
        checkpoint: SearchIndexCheckpoint,
        onProgress: (SearchIndexRebuildProgress) -> Unit,
    ) {
        delegate.rebuild(documents, checkpoint, onProgress)
    }

    /**
     * 读取索引覆盖的权威边界。
     *
     * @return 尚未完整重建时为空。
     */
    override suspend fun readCheckpoint(): SearchIndexCheckpoint? = delegate.readCheckpoint()

    /** 删除全部派生索引数据，但保留 schema。 */
    override suspend fun clear() {
        delegate.clear()
    }

    /**
     * 执行全文、标签、属性和双链查询。
     *
     * @param query 已校验的平台无关查询。
     * @return 按 SQLite 查询约定稳定排序的命中。
     */
    override suspend fun search(query: SearchIndexQuery): List<SearchIndexHit> = delegate.search(query)

    /**
     * 查询指向目标 Note 或 Block 的反向链接。
     *
     * @param targetNoteId 目标 Note ID。
     * @param targetBlockId 可选目标 Block ID。
     * @return 稳定排序的反向链接。
     */
    override suspend fun backlinks(
        targetNoteId: ObjectId,
        targetBlockId: BlockId?,
    ): List<BacklinkIndexHit> = delegate.backlinks(targetNoteId, targetBlockId)

    /**
     * 关闭 SQLite 会话并清理本实例持有的临时资源。
     *
     * @throws Throwable 底层连接关闭失败时抛出；清理动作仍会尽力执行。
     */
    override suspend fun close() {
        try {
            delegate.close()
        } finally {
            if (cleanupOnce.compareAndSet(false, true)) {
                cleanup()
            }
        }
    }

    companion object {
        /**
         * 打开工作区的加密 SQLite 索引。
         *
         * @param databasePath 工作区派生索引路径。
         * @param indexKey 从工作区主密钥按 INDEX 用途派生的密钥；所有权转移给返回实例。
         * @param dispatcher 执行阻塞 JDBC 操作的调度器。
         * @return 同时实现索引与搜索能力的 JVM 引擎。
         */
        suspend fun open(
            databasePath: Path,
            indexKey: SecretKeyMaterial,
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): JvmSqliteIndexEngine {
            require(indexKey.kind == SecretKeyKind.INDEX) { "SQLite 索引必须使用 INDEX 用途密钥" }
            return JvmSqliteIndexEngine(
                delegate = EncryptedSqliteSearchIndex.open(databasePath, indexKey, dispatcher),
            )
        }

        /**
         * 创建仅供无工作区宿主或预览使用的临时加密索引。
         *
         * 临时索引使用随机会话密钥，关闭时删除文件；真实工作区必须调用 [open] 传入从主密钥派生的稳定 INDEX 密钥。
         *
         * @param dispatcher 执行阻塞 JDBC 操作的调度器。
         * @return 临时加密索引引擎。
         */
        suspend fun openEphemeral(
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): JvmSqliteIndexEngine {
            val directory = Files.createTempDirectory("tagloom-index-")
            val databasePath = directory.resolve("local.sqlite")
            val keyBytes = ByteArray(32)
            SecureRandom().nextBytes(keyBytes)
            val indexKey = SecretKeyMaterial.from(SecretKeyKind.INDEX, keyBytes)
            keyBytes.fill(0)
            return try {
                JvmSqliteIndexEngine(
                    delegate = EncryptedSqliteSearchIndex.open(databasePath, indexKey, dispatcher),
                    cleanup = { deleteTemporaryDirectory(directory) },
                )
            } catch (error: Throwable) {
                deleteTemporaryDirectory(directory)
                throw error
            }
        }

        /**
         * 删除临时索引目录；关闭路径必须保持幂等。
         *
         * @param directory 临时索引目录。
         */
        private fun deleteTemporaryDirectory(directory: Path) {
            if (!Files.exists(directory)) return
            Files.walk(directory).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { path ->
                    Files.deleteIfExists(path)
                }
            }
        }
    }
}
