package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.model.DatabaseDefinition
import com.oruke.tagloom.core.model.TransactionId
import com.oruke.tagloom.core.model.ViewDefinition
import com.oruke.tagloom.core.sync.HybridLogicalTime
import com.oruke.tagloom.core.transaction.WorkspaceDatabaseWriteContext
import kotlinx.coroutines.sync.withLock

/**
 * 原子创建 Database 与首个 View，并刷新桌面会话确认点。
 *
 * @param database Database 完整定义。
 * @param initialView 首个 View。
 * @return 两个已确认权威快照。
 */
suspend fun DesktopWorkspaceSession.createDatabaseCatalog(
    database: DatabaseDefinition,
    initialView: ViewDefinition,
): Pair<DesktopStoredDatabaseDefinition, DesktopStoredViewDefinition> = sessionMutex.withLock {
    requireWritable()
    val context = reserveDatabaseWriteContext(2L)
    val result = try {
        databaseStore.create(unlocked, database, initialView, context)
    } catch (error: Throwable) {
        runCatching { refreshManifest() }
        throw error
    }
    refreshManifest(result.encryptedManifest)
    DesktopStoredDatabaseDefinition(requireNotNull(result.databaseSnapshot)) to
        DesktopStoredViewDefinition(requireNotNull(result.viewSnapshot))
}
/**
 * 更新一个已确认 DatabaseDefinition。
 *
 * @param database 新完整定义。
 * @return 新确认 Database 快照。
 */
suspend fun DesktopWorkspaceSession.saveDatabaseDefinition(
    database: DatabaseDefinition,
): DesktopStoredDatabaseDefinition = sessionMutex.withLock {
    requireWritable()
    val result = try {
        databaseStore.saveDatabase(unlocked, database, reserveDatabaseWriteContext(1L))
    } catch (error: Throwable) {
        runCatching { refreshManifest() }
        throw error
    }
    refreshManifest(result.encryptedManifest)
    DesktopStoredDatabaseDefinition(requireNotNull(result.databaseSnapshot))
}

/**
 * 在当前 Database 下创建一个新的独立 ViewDefinition。
 *
 * @param view 新视图完整定义。
 * @return 新确认 View 快照。
 */
suspend fun DesktopWorkspaceSession.createViewDefinition(
    view: ViewDefinition,
): DesktopStoredViewDefinition = sessionMutex.withLock {
    requireWritable()
    val result = try {
        databaseStore.createView(unlocked, view, reserveDatabaseWriteContext(1L))
    } catch (error: Throwable) {
        runCatching { refreshManifest() }
        throw error
    }
    refreshManifest(result.encryptedManifest)
    DesktopStoredViewDefinition(requireNotNull(result.viewSnapshot))
}

/**
 * 更新一个已确认 ViewDefinition。
 *
 * @param view 新完整定义。
 * @return 新确认 View 快照。
 */
suspend fun DesktopWorkspaceSession.saveViewDefinition(
    view: ViewDefinition,
): DesktopStoredViewDefinition = sessionMutex.withLock {
    requireWritable()
    val result = try {
        databaseStore.saveView(unlocked, view, reserveDatabaseWriteContext(1L))
    } catch (error: Throwable) {
        runCatching { refreshManifest() }
        throw error
    }
    refreshManifest(result.encryptedManifest)
    DesktopStoredViewDefinition(requireNotNull(result.viewSnapshot))
}

/**
 * 预留不会因失败而复用的连续 Actor 序号，并创建目录事务上下文。
 *
 * @param sequenceCount 本事务需要的序号数量。
 * @return 以预留首序号开始的写入上下文。
 */
private fun DesktopWorkspaceSession.reserveDatabaseWriteContext(sequenceCount: Long): WorkspaceDatabaseWriteContext {
    require(sequenceCount > 0L) { "Database 事务必须消耗至少一个 Actor 序号" }
    val start = nextActorSequence
    nextActorSequence += sequenceCount
    return WorkspaceDatabaseWriteContext(
        actorId = actorId,
        actorSequenceStart = start,
        transactionId = TransactionId(idFactory()),
        hybridLogicalTime = HybridLogicalTime(currentTimeMillis(), 0, actorId),
        committedAt = timestampProvider(),
    )
}
