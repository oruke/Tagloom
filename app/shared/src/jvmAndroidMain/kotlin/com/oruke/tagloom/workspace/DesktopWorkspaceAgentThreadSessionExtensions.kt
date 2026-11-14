package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.ai.AgentThread
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.ObjectSnapshot
import com.oruke.tagloom.core.model.TransactionId
import kotlinx.coroutines.sync.withLock

/**
 * 在当前已解锁会话锁内读取全部未删除的持久化 `AgentThread` 快照（纯只读，绝不改写任何时间戳或触发落盘）。
 *
 * @return 按 `lastMessageAt` 降序稳定排列的活动持久化会话快照列表。
 */
suspend fun DesktopWorkspaceSession.loadAgentThreads(): List<ObjectSnapshot<AgentThread>> =
    sessionMutex.withLock {
        requireOpen()
        threadStore.loadAll(unlocked).threads
    }

/**
 * 在当前已解锁会话锁内按 ID 读取单条未删除持久化 `AgentThread` 快照。
 *
 * @param threadId 会话对象 ID。
 * @return 匹配的活动会话快照；不存在或已删除时为空。
 */
suspend fun DesktopWorkspaceSession.loadAgentThread(
    threadId: ObjectId,
): ObjectSnapshot<AgentThread>? = sessionMutex.withLock {
    requireOpen()
    threadStore.loadAll(unlocked).threads.singleOrNull { it.header.objectId == threadId }
}

/**
 * 在当前已解锁会话锁内原子提交一条持久化 `AgentThread` 的新建或更新事务，并刷新内存 manifest。
 *
 * @param thread 待确认的完整会话载荷（禁止 `incognito = true`）。
 * @return 新确认的会话权威快照。
 */
suspend fun DesktopWorkspaceSession.saveAgentThread(
    thread: AgentThread,
): ObjectSnapshot<AgentThread> = sessionMutex.withLock {
    requireWritable()
    val actorSequence = nextActorSequence++
    val transactionId = TransactionId(idFactory())
    val hlc = hybridClock.next()
    val committedAt = timestampProvider()
    val result = try {
        threadStore.save(
            unlocked = unlocked,
            thread = thread,
            actorId = actorId,
            actorSequence = actorSequence,
            transactionId = transactionId,
            hybridLogicalTime = hlc,
            committedAt = committedAt,
        )
    } catch (error: Throwable) {
        runCatching { refreshManifest() }
        throw error
    }
    refreshManifest(result.encryptedManifest)
    result.snapshot
}

/**
 * 在当前已解锁会话锁内将指定持久化 `AgentThread` 写为 tombstone，并刷新内存 manifest。
 *
 * @param threadId 待删除的持久化会话对象 ID。
 */
suspend fun DesktopWorkspaceSession.deleteAgentThread(
    threadId: ObjectId,
): Unit = sessionMutex.withLock {
    requireWritable()
    val actorSequence = nextActorSequence++
    val transactionId = TransactionId(idFactory())
    val hlc = hybridClock.next()
    val committedAt = timestampProvider()
    val result = try {
        threadStore.delete(
            unlocked = unlocked,
            threadId = threadId,
            actorId = actorId,
            actorSequence = actorSequence,
            transactionId = transactionId,
            hybridLogicalTime = hlc,
            committedAt = committedAt,
        )
    } catch (error: Throwable) {
        runCatching { refreshManifest() }
        throw error
    }
    refreshManifest(result.encryptedManifest)
}
