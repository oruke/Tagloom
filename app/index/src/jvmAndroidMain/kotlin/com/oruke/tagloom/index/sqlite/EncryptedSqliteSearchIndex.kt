package com.oruke.tagloom.index.sqlite

import com.oruke.tagloom.core.crypto.SecretKeyMaterial
import com.oruke.tagloom.core.index.BacklinkIndexHit
import com.oruke.tagloom.core.index.NoteIndexDocument
import com.oruke.tagloom.core.index.SearchIndexCheckpoint
import com.oruke.tagloom.core.index.SearchIndexHit
import com.oruke.tagloom.core.index.SearchIndexPort
import com.oruke.tagloom.core.index.SearchIndexQuery
import com.oruke.tagloom.core.index.SearchIndexRebuildProgress
import com.oruke.tagloom.core.model.AiAccessPolicy
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.ObjectId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Path

/**
 * 使用加密 SQLite 文件实现全文、标签、属性和双链派生索引。
 *
 * 该对象取得 [indexKey] 的所有权：打开失败或调用 [close] 后都会销毁密钥。单连接访问由互斥锁串行化；事务内部禁止挂起，保持 Android SQLCipher 的线程绑定。
 *
 * @param connection 已验证并初始化 schema 的加密 SQLite 连接。
 * @param indexKey 当前解锁会话专用索引密钥。
 * @param dispatcher 执行阻塞 JDBC 操作的协程调度器。
 */
class EncryptedSqliteSearchIndex private constructor(
    private val connection: SqliteDriver,
    private val indexKey: SecretKeyMaterial,
    private val dispatcher: CoroutineDispatcher,
) : SearchIndexPort {
    /** 映射索引行与领域模型的共享 SQL Repository。 */
    private val repository: SqliteIndexRepository = SqliteIndexRepository(connection)

    /** 串行化单连接上的全部访问与关闭操作。 */
    private val accessMutex: Mutex = Mutex()

    /** 关闭后禁止任何业务操作的生命周期标记。 */
    @Volatile
    private var closed: Boolean = false

    /**
     * 原子替换一篇 Note 的全部派生索引。
     *
     * @param document 已确认快照投影出的完整文档。
     */
    override suspend fun replace(document: NoteIndexDocument): Unit = runOpenLocked {
        inTransaction {
            repository.deleteDocument(document.noteId)
            if (!document.deleted) {
                repository.insertDocument(document)
            }
        }
    }

    /**
     * 原子移除一篇 Note 的全部派生索引。
     *
     * @param noteId 待移除 Note。
     */
    override suspend fun remove(noteId: ObjectId): Unit = runOpenLocked {
        inTransaction {
            repository.deleteDocument(noteId)
        }
    }

    /**
     * 执行全文、标签和属性组合查询。
     *
     * @param query 已校验查询。
     * @return 稳定排序的命中。
     */
    override suspend fun search(query: SearchIndexQuery): List<SearchIndexHit> = runOpenLocked {
        repository.search(query)
    }

    /**
     * 统计在相同查询条件下因不满足 `allowedAiPolicies` 而在分页前被排除的笔记篇数。
     *
     * @param query 包含 `allowedAiPolicies` 约束的查询。
     * @return 被策略保护排除的命中篇数。
     */
    override suspend fun countExcludedByPolicy(query: SearchIndexQuery): Int = runOpenLocked {
        repository.countExcludedByPolicy(query)
    }

    /**
     * 查询指向目标 Note 或 Block 的反向链接。
     *
     * @param targetNoteId 目标 Note。
     * @param targetBlockId 可选目标 Block。
     * @param allowedAiPolicies 来源笔记允许的 AI 访问策略集合；为空表示人类持有者查看全部反向链接。
     * @return 稳定排序的反向链接。
     */
    override suspend fun backlinks(
        targetNoteId: ObjectId,
        targetBlockId: BlockId?,
        allowedAiPolicies: Set<AiAccessPolicy>?,
    ): List<BacklinkIndexHit> = runOpenLocked {
        repository.backlinks(targetNoteId, targetBlockId, allowedAiPolicies)
    }

    /**
     * 在单个事务中从当前确认快照全量重建索引。
     *
     * @param documents 当前权威 Note 文档集合。
     * @param checkpoint 重建成功后写入的边界。
     * @param onProgress 重建进度回调。
     */
    override suspend fun rebuild(
        documents: List<NoteIndexDocument>,
        checkpoint: SearchIndexCheckpoint,
        onProgress: (SearchIndexRebuildProgress) -> Unit,
    ): Unit = runOpenLocked {
        require(documents.map(NoteIndexDocument::noteId).distinct().size == documents.size) {
            "索引重建文档不能包含重复 Note ID"
        }
        require(checkpoint.noteCount == documents.count { document -> !document.deleted }) {
            "索引 checkpoint Note 数量必须与非 tombstone 文档数量一致"
        }

        val operationContext = currentCoroutineContext()
        inTransaction {
            repository.deleteAllDocuments()
            onProgress(SearchIndexRebuildProgress(completed = 0, total = documents.size))
            documents.forEachIndexed { index, document ->
                // 每个文档边界检查取消，保证大工作区可以中止且整个事务回滚到旧完整索引。
                operationContext.ensureActive()
                if (!document.deleted) {
                    repository.insertDocument(document)
                }
                onProgress(SearchIndexRebuildProgress(completed = index + 1, total = documents.size))
            }
            repository.writeCheckpoint(checkpoint)
        }
    }

    /**
     * 读取索引当前覆盖的权威 checkpoint。
     *
     * @return 尚未完整重建时为空。
     */
    override suspend fun readCheckpoint(): SearchIndexCheckpoint? = runOpenLocked {
        repository.readCheckpoint()
    }

    /** 删除全部派生文档与 checkpoint，同时保留可复用 schema。 */
    override suspend fun clear(): Unit = runOpenLocked {
        inTransaction(repository::deleteAllDocuments)
    }

    /**
     * 关闭数据库连接并销毁当前会话索引密钥。
     *
     * 方法幂等；即使 JDBC 关闭失败，也会先进入关闭状态并尽力清零密钥。
     */
    override suspend fun close() {
        withContext(dispatcher) {
            accessMutex.withLock {
                if (closed) {
                    return@withLock
                }
                closed = true
                try {
                    connection.close()
                } finally {
                    indexKey.destroy()
                }
            }
        }
    }

    /**
     * 在 JDBC 调度器和互斥锁内执行仅限解锁状态的操作。
     *
     * @param block 已取得连接独占访问权的业务操作。
     * @return 业务操作结果。
     */
    private suspend fun <T> runOpenLocked(block: suspend () -> T): T = withContext(dispatcher) {
        accessMutex.withLock {
            check(!closed) { "SQLite 索引已经关闭" }
            block()
        }
    }

    /**
     * 在同一线程执行 SQLite 事务，并在失败或取消时回滚到调用前完整状态。
     *
     * @param block 不得挂起、必须整体提交或整体回滚的操作。
     * @return 事务操作结果。
     */
    private fun <T> inTransaction(block: () -> T): T {
        connection.begin()
        return try {
            val result = block()
            connection.commit()
            result
        } catch (error: Throwable) {
            try {
                connection.rollback()
            } catch (rollbackError: Throwable) {
                error.addSuppressed(rollbackError)
            }
            throw error
        }
    }

    companion object {
        /**
         * 打开或创建工作区的加密派生索引。
         *
         * 调用即转移 [indexKey] 所有权；任何失败都会关闭已创建连接并销毁密钥。错误密钥和损坏文件会在 schema 初始化前稳定失败。
         *
         * @param databasePath 工作区 `index/local.sqlite` 路径。
         * @param indexKey 从 Workspace Master Key 独立派生的 INDEX 密钥。
         * @param dispatcher 执行阻塞 JDBC 操作的调度器。
         * @return 已可查询和重建的加密索引实例。
         */
        suspend fun open(
            databasePath: Path,
            indexKey: SecretKeyMaterial,
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): EncryptedSqliteSearchIndex = withContext(dispatcher) {
            var connection: SqliteDriver? = null
            try {
                connection = openSqliteDriver(databasePath, indexKey)
                SqliteIndexSchema.initialize(connection)
                EncryptedSqliteSearchIndex(connection, indexKey, dispatcher)
            } catch (error: Throwable) {
                runCatching { connection?.close() }
                indexKey.destroy()
                throw error
            }
        }
    }
}
