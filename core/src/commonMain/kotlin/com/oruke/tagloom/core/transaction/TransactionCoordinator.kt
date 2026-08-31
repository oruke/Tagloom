package com.oruke.tagloom.core.transaction

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/**
 * 作为单个工作区唯一写入者，编排密文 staging、权威 manifest 确认和启动恢复。
 */
class TransactionCoordinator(
    /** 实现 fsync、原子替换、旧版本备份和事务记录的存储端口。 */
    private val storage: TransactionStoragePort,
    /** 事务确认后更新可重建派生状态的端口。 */
    private val derivedStateUpdater: DerivedStateUpdater,
) {
    /** 串行化 UI、CLI、MCP、Agent 和后台任务的全部工作区写入。 */
    private val writeMutex: Mutex = Mutex()

    /**
     * 提交一组已完成业务校验和加密的文件。
     *
     * manifest 原子替换是唯一权威确认点；其后的索引失败只返回落后状态，不回滚新快照。
     *
     * @param plan 完整密文提交计划。
     * @return 已确认事务及派生状态更新结果。
     */
    suspend fun commit(plan: EncryptedTransactionPlan): TransactionCommitResult = writeMutex.withLock {
        // 步骤 1：所有业务校验与加密必须由调用方在进入协调器前完成。
        val prepared = storage.stage(plan)

        // 步骤 2：prepared 记录落盘后，任何异常都必须由相同恢复算法完成或回滚。
        storage.persistPrepared(prepared)
        storage.publishArtifacts(prepared)
        storage.confirmManifest(prepared)
        storage.markCommitted(plan.transactionId)

        // 步骤 3：索引是派生状态，失败只标记落后，不能破坏 manifest 已确认的业务状态。
        val derivedStateUpdated = try {
            derivedStateUpdater.update(plan.transactionId)
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            false
        }

        // 步骤 4：只有 manifest 已确认且派生更新已尝试后才清理恢复材料。
        storage.cleanupAfterCommit(plan.transactionId)
        TransactionCommitResult(
            transactionId = plan.transactionId,
            derivedStateUpdated = derivedStateUpdated,
        )
    }

    /**
     * 在工作区允许读取和写入前处理所有 prepared 事务与孤立 staging。
     *
     * @return 恢复动作和是否必须进入只读安全模式。
     */
    suspend fun recoverOnStartup(): TransactionRecoveryReport = writeMutex.withLock {
        storage.cleanupOrphanStaging()
        val loaded = storage.loadPendingPrepared()
        val entries = loaded.quarantinedTransactionIds
            .map { transactionId ->
                TransactionRecoveryEntry(transactionId, TransactionRecoveryAction.QUARANTINED)
            }
            .toMutableList()
        var requiresReadOnlyMode = loaded.quarantinedTransactionIds.isNotEmpty()

        loaded.records.forEach { record ->
            val action = try {
                recoverPrepared(record)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // 恢复阶段的再次失败意味着无法证明新旧状态完整，必须保留证据并停止后续写入。
                try {
                    storage.quarantine(record)
                } catch (quarantineFailure: CancellationException) {
                    throw quarantineFailure
                } catch (_: Throwable) {
                    // 隔离失败仍保持只读结果；下一次启动会再次发现原始 staging。
                }
                TransactionRecoveryAction.QUARANTINED
            }
            entries += TransactionRecoveryEntry(record.transactionId, action)
            if (action == TransactionRecoveryAction.QUARANTINED) {
                requiresReadOnlyMode = true
            }
        }

        TransactionRecoveryReport(
            entries = entries,
            requiresReadOnlyMode = requiresReadOnlyMode,
        )
    }

    /**
     * 根据 manifest 确认点和 staging 完整性恢复单个 prepared 事务。
     *
     * @param record 待恢复事务。
     * @return 完成、回滚或隔离动作。
     */
    private suspend fun recoverPrepared(record: PreparedTransactionRecord): TransactionRecoveryAction {
        if (storage.isManifestCommitted(record.transactionId)) {
            storage.markCommitted(record.transactionId)
            storage.cleanupAfterCommit(record.transactionId)
            return TransactionRecoveryAction.CONFIRMED_AND_CLEANED
        }

        return when (storage.validatePrepared(record)) {
            PreparedValidationResult.VALID -> {
                storage.publishArtifacts(record)
                storage.confirmManifest(record)
                storage.markCommitted(record.transactionId)
                try {
                    derivedStateUpdater.update(record.transactionId)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    // 派生索引可从权威快照重建，失败不影响 manifest 已确认状态。
                }
                storage.cleanupAfterCommit(record.transactionId)
                TransactionRecoveryAction.COMPLETED
            }

            PreparedValidationResult.INVALID -> {
                if (storage.rollback(record)) {
                    storage.cleanupAfterRollback(record)
                    TransactionRecoveryAction.ROLLED_BACK
                } else {
                    storage.quarantine(record)
                    TransactionRecoveryAction.QUARANTINED
                }
            }
        }
    }
}
