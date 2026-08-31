package com.oruke.tagloom.core.transaction

import com.oruke.tagloom.core.model.TransactionId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 验证事务每个持久化步骤发生进程终止后的新旧状态原子性。
 */
class TransactionCoordinatorTest {
    /**
     * 验证从 staging 到 cleanup 的每个崩溃点重启后只得到完整旧状态或完整新状态。
     */
    @Test
    fun recoveryNeverLeavesPartialBusinessState() = runBlocking {
        TransactionFaultPoint.entries.forEach { faultPoint ->
            val storage = InMemoryTransactionStorage()
            val updater = RecordingDerivedStateUpdater()
            val coordinator = TransactionCoordinator(storage, updater)
            val originalFiles = storage.currentFiles.mapValues { (_, bytes) -> bytes.copyOf() }
            storage.faultPoint = faultPoint

            assertFails { coordinator.commit(plan()) }
            storage.faultPoint = null
            val report = TransactionCoordinator(storage, updater).recoverOnStartup()

            assertFalse(report.requiresReadOnlyMode)
            if (faultPoint == TransactionFaultPoint.STAGE) {
                assertFileState(originalFiles, storage.currentFiles)
                assertEquals(0, storage.manifestRevision)
            } else {
                assertFileState(expectedNewFiles(), storage.currentFiles)
                assertEquals(1, storage.manifestRevision)
                assertTrue(storage.isCommitted(TransactionId("transaction-a")))
            }
            assertTrue(storage.hasNoStaging())
        }
    }

    /**
     * 验证损坏 staging 会恢复全部旧版本，而不是继续发布部分新文件。
     */
    @Test
    fun invalidPreparedTransactionRollsBackPublishedFiles() = runBlocking {
        val storage = InMemoryTransactionStorage()
        val originalFiles = storage.currentFiles.mapValues { (_, bytes) -> bytes.copyOf() }
        val prepared = storage.stage(plan())
        storage.persistPrepared(prepared)
        storage.publishArtifacts(prepared)
        storage.preparedValid = false

        val report = TransactionCoordinator(storage, RecordingDerivedStateUpdater()).recoverOnStartup()

        assertEquals(TransactionRecoveryAction.ROLLED_BACK, report.entries.single().action)
        assertFalse(report.requiresReadOnlyMode)
        assertFileState(originalFiles, storage.currentFiles)
        assertEquals(0, storage.manifestRevision)
    }

    /**
     * 验证无法完成也无法回滚的事务会被隔离并强制只读模式。
     */
    @Test
    fun unrecoverableTransactionEntersReadOnlyMode() = runBlocking {
        val storage = InMemoryTransactionStorage()
        val prepared = storage.stage(plan())
        storage.persistPrepared(prepared)
        storage.preparedValid = false
        storage.rollbackSucceeds = false

        val report = TransactionCoordinator(storage, RecordingDerivedStateUpdater()).recoverOnStartup()

        assertEquals(TransactionRecoveryAction.QUARANTINED, report.entries.single().action)
        assertTrue(report.requiresReadOnlyMode)
        assertTrue(storage.quarantined.contains(prepared.transactionId))
    }

    /**
     * 验证索引更新失败不会回滚 manifest 已确认的业务快照。
     */
    @Test
    fun derivedStateFailureDoesNotRollbackCommittedSnapshots() = runBlocking {
        val storage = InMemoryTransactionStorage()
        val updater = RecordingDerivedStateUpdater(fail = true)

        val result = TransactionCoordinator(storage, updater).commit(plan())

        assertFalse(result.derivedStateUpdated)
        assertEquals(1, storage.manifestRevision)
        assertFileState(expectedNewFiles(), storage.currentFiles)
        assertTrue(storage.hasNoStaging())
    }

    /**
     * 创建包含两个对象文件和一个 manifest 的测试提交计划。
     *
     * @return revision 从 0 前进到 1 的密文计划。
     */
    private fun plan(): EncryptedTransactionPlan = EncryptedTransactionPlan(
        transactionId = TransactionId("transaction-a"),
        baseManifestRevision = 0,
        targetManifestRevision = 1,
        artifacts = listOf(
            EncryptedTransactionArtifact(
                targetPath = WorkspaceRelativePath("notes/note-a.note.enc"),
                encryptedBytes = NEW_NOTE_A,
                expectedSha256 = "a".repeat(64),
            ),
            EncryptedTransactionArtifact(
                targetPath = WorkspaceRelativePath("tags/tag-a.tag.enc"),
                encryptedBytes = NEW_TAG_A,
                expectedSha256 = "b".repeat(64),
            ),
        ),
        encryptedManifest = byteArrayOf(9, 9, 9),
        manifestSha256 = "c".repeat(64),
    )

    /**
     * 返回事务完整成功后预期的业务文件集合。
     *
     * @return 两个目标路径及其新密文。
     */
    private fun expectedNewFiles(): Map<WorkspaceRelativePath, ByteArray> = mapOf(
        WorkspaceRelativePath("notes/note-a.note.enc") to NEW_NOTE_A,
        WorkspaceRelativePath("tags/tag-a.tag.enc") to NEW_TAG_A,
    )

    /**
     * 逐路径比较两个密文文件集合，避免 ByteArray 引用相等陷阱。
     *
     * @param expected 预期文件集合。
     * @param actual 实际文件集合。
     */
    private fun assertFileState(
        expected: Map<WorkspaceRelativePath, ByteArray>,
        actual: Map<WorkspaceRelativePath, ByteArray>,
    ) {
        assertEquals(expected.keys, actual.keys)
        expected.forEach { (path, bytes) -> assertContentEquals(bytes, actual[path]) }
    }

    companion object {
        /** note-a 的新加密文件。 */
        private val NEW_NOTE_A: ByteArray = byteArrayOf(1, 2, 3)

        /** tag-a 的新加密文件。 */
        private val NEW_TAG_A: ByteArray = byteArrayOf(4, 5, 6)
    }
}

/**
 * 定义测试在持久化动作已经发生后模拟进程终止的位置。
 */
private enum class TransactionFaultPoint {
    /** staging 与旧版本备份已写入。 */
    STAGE,

    /** prepared 记录已持久化。 */
    PREPARED,

    /** 新业务文件已发布。 */
    ARTIFACTS_PUBLISHED,

    /** manifest 已成为权威确认点。 */
    MANIFEST_CONFIRMED,

    /** committed 诊断标记已写入。 */
    COMMITTED_MARKED,

    /** staging 已清理。 */
    CLEANED,
}

/**
 * 在内存中模拟保留 staging 副本和旧版本备份的原子文件存储。
 */
private class InMemoryTransactionStorage : TransactionStoragePort {
    /** 当前权威业务文件。 */
    val currentFiles: MutableMap<WorkspaceRelativePath, ByteArray> = mutableMapOf(
        WorkspaceRelativePath("notes/note-a.note.enc") to byteArrayOf(7, 7, 7),
        WorkspaceRelativePath("tags/tag-a.tag.enc") to byteArrayOf(8, 8, 8),
    )

    /** 当前权威 manifest revision。 */
    var manifestRevision: Long = 0

    /** 下一个动作完成后模拟进程终止的位置。 */
    var faultPoint: TransactionFaultPoint? = null

    /** prepared staging 是否能够通过完整性验证。 */
    var preparedValid: Boolean = true

    /** 旧版本备份是否足以完成回滚。 */
    var rollbackSucceeds: Boolean = true

    /** 已隔离事务。 */
    val quarantined: MutableSet<TransactionId> = mutableSetOf()

    /** staging 中保留的提交计划。 */
    private val stagedPlans: MutableMap<TransactionId, EncryptedTransactionPlan> = mutableMapOf()

    /** prepared 记录。 */
    private val preparedRecords: MutableMap<TransactionId, PreparedTransactionRecord> = mutableMapOf()

    /** 事务开始前的完整业务文件备份。 */
    private val backups: MutableMap<TransactionId, Map<WorkspaceRelativePath, ByteArray>> = mutableMapOf()

    /** manifest 确认历史。 */
    private val confirmedTransactions: MutableSet<TransactionId> = mutableSetOf()

    /** committed 诊断标记。 */
    private val committedMarkers: MutableSet<TransactionId> = mutableSetOf()

    /**
     * 保存 staging 计划和旧版本备份。
     *
     * @param plan 密文提交计划。
     * @return 可用于恢复的记录。
     */
    override suspend fun stage(plan: EncryptedTransactionPlan): PreparedTransactionRecord {
        stagedPlans[plan.transactionId] = plan
        backups[plan.transactionId] = currentFiles.mapValues { (_, bytes) -> bytes.copyOf() }
        val record = PreparedTransactionRecord(
            transactionId = plan.transactionId,
            baseManifestRevision = plan.baseManifestRevision,
            targetManifestRevision = plan.targetManifestRevision,
            artifacts = plan.artifacts.mapIndexed { index, artifact ->
                val previous = currentFiles[artifact.targetPath]
                PreparedArtifactRecord(
                    targetPath = artifact.targetPath,
                    stagedFileName = "artifact-$index.enc",
                    expectedSha256 = artifact.expectedSha256,
                    previousVersionExists = previous != null,
                    previousSha256 = previous?.let { "d".repeat(64) },
                )
            },
            stagedManifestFileName = "manifest-next.enc",
            manifestSha256 = plan.manifestSha256,
        )
        failAfter(TransactionFaultPoint.STAGE)
        return record
    }

    /**
     * 保存 prepared 记录。
     *
     * @param record 待保存记录。
     */
    override suspend fun persistPrepared(record: PreparedTransactionRecord) {
        preparedRecords[record.transactionId] = record
        failAfter(TransactionFaultPoint.PREPARED)
    }

    /**
     * 发布全部新业务文件，并保留 staging 副本供重放。
     *
     * @param record prepared 记录。
     */
    override suspend fun publishArtifacts(record: PreparedTransactionRecord) {
        val plan = requireNotNull(stagedPlans[record.transactionId])
        plan.artifacts.forEach { artifact -> currentFiles[artifact.targetPath] = artifact.encryptedBytes.copyOf() }
        failAfter(TransactionFaultPoint.ARTIFACTS_PUBLISHED)
    }

    /**
     * 以 revision 比较交换方式确认 manifest。
     *
     * @param record prepared 记录。
     */
    override suspend fun confirmManifest(record: PreparedTransactionRecord) {
        if (record.transactionId !in confirmedTransactions) {
            check(manifestRevision == record.baseManifestRevision)
            manifestRevision = record.targetManifestRevision
            confirmedTransactions += record.transactionId
        }
        failAfter(TransactionFaultPoint.MANIFEST_CONFIRMED)
    }

    /**
     * 保存 committed 诊断标记。
     *
     * @param transactionId 已确认事务。
     */
    override suspend fun markCommitted(transactionId: TransactionId) {
        committedMarkers += transactionId
        failAfter(TransactionFaultPoint.COMMITTED_MARKED)
    }

    /**
     * 清理事务 staging 与备份。
     *
     * @param transactionId 已完成事务。
     */
    override suspend fun cleanupAfterCommit(transactionId: TransactionId) {
        stagedPlans.remove(transactionId)
        preparedRecords.remove(transactionId)
        backups.remove(transactionId)
        failAfter(TransactionFaultPoint.CLEANED)
    }

    /**
     * 清理已经恢复到事务前状态的 staging 与备份。
     *
     * @param record 已完成回滚的事务记录。
     */
    override suspend fun cleanupAfterRollback(record: PreparedTransactionRecord) {
        stagedPlans.remove(record.transactionId)
        preparedRecords.remove(record.transactionId)
        backups.remove(record.transactionId)
    }

    /**
     * 删除没有 prepared 记录的 staging。
     */
    override suspend fun cleanupOrphanStaging() {
        val orphans = stagedPlans.keys - preparedRecords.keys
        orphans.forEach { transactionId ->
            stagedPlans.remove(transactionId)
            backups.remove(transactionId)
        }
    }

    /**
     * 枚举待恢复记录。
     *
     * @return 按目标 revision 排列的 prepared 记录。
     */
    override suspend fun loadPendingPrepared(): PendingPreparedTransactions =
        PendingPreparedTransactions(
            records = preparedRecords.values.sortedBy(PreparedTransactionRecord::targetManifestRevision),
        )

    /**
     * 查询 manifest 确认历史。
     *
     * @param transactionId 待查询事务。
     * @return 已确认为 `true`。
     */
    override suspend fun isManifestCommitted(transactionId: TransactionId): Boolean =
        transactionId in confirmedTransactions

    /**
     * 返回测试配置的 staging 完整性。
     *
     * @param record 待验证记录。
     * @return VALID 或 INVALID。
     */
    override suspend fun validatePrepared(record: PreparedTransactionRecord): PreparedValidationResult =
        if (preparedValid) PreparedValidationResult.VALID else PreparedValidationResult.INVALID

    /**
     * 从内存备份恢复事务开始前的完整文件集合。
     *
     * @param record 待回滚记录。
     * @return 测试配置允许且备份存在时为 `true`。
     */
    override suspend fun rollback(record: PreparedTransactionRecord): Boolean {
        val backup = backups[record.transactionId]
        if (!rollbackSucceeds || backup == null) {
            return false
        }
        currentFiles.clear()
        backup.forEach { (path, bytes) -> currentFiles[path] = bytes.copyOf() }
        return true
    }

    /**
     * 记录需要只读恢复的隔离事务。
     *
     * @param record 无法自动恢复的记录。
     */
    override suspend fun quarantine(record: PreparedTransactionRecord) {
        quarantined += record.transactionId
    }

    /**
     * 判断事务是否已写入 manifest 确认历史。
     *
     * @param transactionId 待查询事务。
     * @return 已确认状态。
     */
    fun isCommitted(transactionId: TransactionId): Boolean = transactionId in confirmedTransactions

    /**
     * 判断 staging 和 prepared 记录是否都已清空。
     *
     * @return 没有恢复材料时为 `true`。
     */
    fun hasNoStaging(): Boolean = stagedPlans.isEmpty() && preparedRecords.isEmpty()

    /**
     * 在指定动作已修改状态后抛出异常，模拟无法执行 finally 的进程终止。
     *
     * @param point 当前动作位置。
     */
    private fun failAfter(point: TransactionFaultPoint) {
        if (faultPoint == point) {
            throw SimulatedProcessTermination()
        }
    }
}

/**
 * 记录或模拟事务确认后的派生状态更新。
 *
 * @property fail 是否模拟索引更新失败。
 */
private class RecordingDerivedStateUpdater(
    private val fail: Boolean = false,
) : DerivedStateUpdater {
    /** 已收到更新的事务。 */
    val updatedTransactions: MutableList<TransactionId> = mutableListOf()

    /**
     * 记录事务，或按测试配置模拟派生索引故障。
     *
     * @param transactionId 已确认事务。
     */
    override suspend fun update(transactionId: TransactionId) {
        if (fail) {
            error("模拟派生状态失败")
        }
        updatedTransactions += transactionId
    }
}

/**
 * 模拟持久化动作已发生但调用栈无法继续执行的进程终止。
 */
private class SimulatedProcessTermination : RuntimeException("模拟进程终止")
