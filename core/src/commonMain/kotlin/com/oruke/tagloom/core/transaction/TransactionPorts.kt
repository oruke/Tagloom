package com.oruke.tagloom.core.transaction

import com.oruke.tagloom.core.model.TransactionId

/**
 * 抽象加密 staging、原子替换、manifest 确认和崩溃恢复所需的持久化能力。
 *
 * 实现必须保证每个方法返回前完成相应文件与目录的 flush/fsync；所有临时内容都必须保持加密。
 */
interface TransactionStoragePort {
    /**
     * 写入全部新密文、旧版本备份和待确认 manifest，但尚不创建 prepared 记录。
     *
     * @param plan 已完成业务校验和加密的提交计划。
     * @return 引用持久化 staging 文件的 prepared 记录。
     */
    suspend fun stage(plan: EncryptedTransactionPlan): PreparedTransactionRecord

    /**
     * 原子持久化 prepared 记录，使事务从此成为启动恢复候选。
     *
     * @param record 已 fsync 全部 staging 内容的记录。
     */
    suspend fun persistPrepared(record: PreparedTransactionRecord)

    /**
     * 从 staging 对每个业务文件执行同目录原子替换，并保留恢复副本。
     *
     * @param record prepared 事务记录。
     */
    suspend fun publishArtifacts(record: PreparedTransactionRecord)

    /**
     * 仅在当前 revision 等于 base 时原子发布 manifest，形成权威确认点。
     *
     * @param record prepared 事务记录。
     */
    suspend fun confirmManifest(record: PreparedTransactionRecord)

    /**
     * 写入 committed 标记；该标记不是权威状态，只帮助清理与诊断。
     *
     * @param transactionId 已由 manifest 确认的事务。
     */
    suspend fun markCommitted(transactionId: TransactionId)

    /**
     * 删除已经确认且验证通过的 staging 与旧版本备份。
     *
     * @param transactionId 已完成事务。
     */
    suspend fun cleanupAfterCommit(transactionId: TransactionId)

    /**
     * 在确认旧状态已经完整恢复后，删除回滚事务的 staging 与旧版本备份。
     *
     * @param record 已成功恢复到 base manifest 状态的事务记录。
     */
    suspend fun cleanupAfterRollback(record: PreparedTransactionRecord)

    /**
     * 删除没有 prepared 记录的孤立 staging 目录。
     */
    suspend fun cleanupOrphanStaging()

    /**
     * 枚举全部已持久化 prepared 且尚未清理的事务。
     *
     * @return 按目标 manifest revision 升序排列的记录，以及已经隔离的损坏记录 ID。
     */
    suspend fun loadPendingPrepared(): PendingPreparedTransactions

    /**
     * 判断 manifest 的确认历史是否已经包含指定事务。
     *
     * @param transactionId 待查询事务。
     * @return 已确认为 `true`。
     */
    suspend fun isManifestCommitted(transactionId: TransactionId): Boolean

    /**
     * 验证 staging 密文的长度、摘要、AEAD、schema 和目标 revision。
     *
     * @param record prepared 事务记录。
     * @return 是否足以安全完成发布。
     */
    suspend fun validatePrepared(record: PreparedTransactionRecord): PreparedValidationResult

    /**
     * 使用 staging 中的旧版本备份恢复 manifest 确认前的完整旧状态。
     *
     * @param record 无法安全完成的 prepared 事务。
     * @return 全部目标已恢复并验证为 `true`。
     */
    suspend fun rollback(record: PreparedTransactionRecord): Boolean

    /**
     * 隔离无法完成或回滚的事务，保留密文证据并阻止自动清理。
     *
     * @param record 需要人工恢复的事务。
     */
    suspend fun quarantine(record: PreparedTransactionRecord)
}

/**
 * 抽象事务确认后可失败、可重建的派生索引更新。
 */
interface DerivedStateUpdater {
    /**
     * 将已确认事务投影到 SQLite、双链或其他派生状态。
     *
     * @param transactionId 已由 manifest 确认的事务。
     */
    suspend fun update(transactionId: TransactionId)
}
