package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.ai.WorkspaceConfig
import com.oruke.tagloom.core.model.ObjectSnapshot
import com.oruke.tagloom.core.model.TransactionId
import kotlinx.coroutines.sync.withLock

/**
 * 在当前已解锁会话锁内读取认证后的活动 `WorkspaceConfig` 快照。
 *
 * @return 已认证的活动工作区配置快照；尚未创建时为空。
 */
suspend fun DesktopWorkspaceSession.loadWorkspaceConfig(): ObjectSnapshot<WorkspaceConfig>? =
    sessionMutex.withLock {
        requireOpen()
        configStore.load(unlocked).snapshot
    }

/**
 * 在当前已解锁会话锁内原子保存更新后的 `WorkspaceConfig` 快照并刷新内存 manifest。
 *
 * @param config 待确认的完整配置载荷。
 * @return 新确认的工作区配置权威快照。
 */
suspend fun DesktopWorkspaceSession.saveWorkspaceConfig(
    config: WorkspaceConfig,
): ObjectSnapshot<WorkspaceConfig> = sessionMutex.withLock {
    requireWritable()
    val actorSequence = nextActorSequence++
    val transactionId = TransactionId(idFactory())
    val hlc = hybridClock.next()
    val committedAt = timestampProvider()
    val result = try {
        configStore.save(
            unlocked = unlocked,
            config = config,
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
