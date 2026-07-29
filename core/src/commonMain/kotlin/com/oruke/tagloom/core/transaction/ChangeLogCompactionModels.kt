package com.oruke.tagloom.core.transaction

import com.oruke.tagloom.core.model.ChangeId
import com.oruke.tagloom.core.model.TransactionId
import com.oruke.tagloom.core.sync.ConflictRecord
import com.oruke.tagloom.core.sync.ObjectChange
import com.oruke.tagloom.core.workspace.ManifestObjectKind
import kotlinx.serialization.Serializable

/**
 * 保存 checkpoint 对一个已验证权威快照的不可变摘要。
 */
@Serializable
data class ChangeCheckpointSnapshot(
    /** manifest 对象类别。 */
    val kind: ManifestObjectKind,
    /** 与名称无关的不透明对象 ID。 */
    val objectId: String,
    /** checkpoint 覆盖的对象 revision。 */
    val revision: String,
    /** 对象信封的 SHA-256。 */
    val encryptedSha256: String,
)

/**
 * 表示 Change Log 压缩后可独立认证当前快照边界的加密 checkpoint。
 */
@Serializable
data class ChangeLogCheckpoint(
    /** checkpoint 稳定不透明 ID。 */
    val checkpointId: String,
    /** checkpoint 覆盖的最近已确认事务。 */
    val coveredTransactionId: TransactionId,
    /** 压缩前 manifest revision。 */
    val coveredManifestRevision: Long,
    /** 对快照摘要列表规范编码计算的 Merkle 风格根摘要。 */
    val snapshotHashRoot: String,
    /** 按类别和对象 ID 稳定排序的权威快照摘要。 */
    val snapshots: List<ChangeCheckpointSnapshot>,
    /** 已从 manifest 移除的 Change ID。 */
    val compactedChangeIds: List<ChangeId>,
    /** checkpoint 创建 UTC ISO-8601 时间。 */
    val createdAt: String,
) {
    init {
        require(checkpointId.matches(OPAQUE_ID_PATTERN)) { "checkpoint ID 格式无效" }
        require(coveredManifestRevision >= 0L) { "checkpoint manifest revision 不能为负数" }
        require(snapshotHashRoot.matches(SHA256_PATTERN)) { "checkpoint 快照根摘要格式无效" }
        require(snapshots.isNotEmpty()) { "checkpoint 至少覆盖一个权威快照" }
        require(compactedChangeIds.isNotEmpty()) { "checkpoint 至少压缩一个 Change" }
        require(compactedChangeIds.distinct().size == compactedChangeIds.size) { "checkpoint Change ID 重复" }
        require(createdAt.isNotBlank()) { "checkpoint 创建时间不能为空" }
    }

    companion object {
        /** 安全映射到工作区文件名的不透明 ID 格式。 */
        private val OPAQUE_ID_PATTERN: Regex = Regex("[A-Za-z0-9_-]{1,128}")

        /** SHA-256 小写十六进制格式。 */
        private val SHA256_PATTERN: Regex = Regex("[0-9a-f]{64}")
    }
}

/**
 * 描述 Change Log 压缩前必须满足的保留期和同步确认条件。
 */
data class ChangeLogCompactionPolicy(
    /** 本地撤销保留期已经结束。 */
    val undoRetentionExpired: Boolean,
    /** 待删除 Change 已进入用户启用的历史备份。 */
    val archivedToHistoryBackup: Boolean,
    /** 当前工作区是否启用跨设备同步。 */
    val syncEnabled: Boolean,
    /** 当前已知且未必仍有效的设备 ID。 */
    val knownDeviceIds: Set<String> = emptySet(),
    /** 已确认收到本 checkpoint 的设备 ID。 */
    val acknowledgedDeviceIds: Set<String> = emptySet(),
    /** 在 checkpoint 前已明确撤销的设备 ID。 */
    val revokedDeviceIds: Set<String> = emptySet(),
)

/**
 * 汇总一次压缩决策所需的认证状态和保留证据。
 */
data class ChangeLogCompactionRequest(
    /** 新 checkpoint 不透明 ID。 */
    val checkpointId: String,
    /** 压缩事务 ID。 */
    val transactionId: TransactionId,
    /** checkpoint 创建时间。 */
    val createdAt: String,
    /** 当前 manifest 中已完成 AEAD 和 schema 验证的 Change。 */
    val changes: List<ObjectChange>,
    /** 当前加密历史中仍需考虑的冲突证据。 */
    val conflicts: List<ConflictRecord>,
    /** 当前全部权威快照已经验证可读取。 */
    val snapshotsValidated: Boolean,
    /** 保留期、归档和设备确认策略。 */
    val policy: ChangeLogCompactionPolicy,
)

/**
 * 表示 planner 允许删除的 Change 与必须保留的冲突依赖闭包。
 */
data class ChangeLogCompactionDecision(
    /** 可由本次 checkpoint 覆盖并删除的 Change。 */
    val removableChanges: List<ObjectChange>,
    /** 因未解决冲突及其 DAG 祖先而保留的 Change。 */
    val retainedChanges: List<ObjectChange>,
)

/**
 * 返回已确认 checkpoint、manifest 和物理清理目标。
 */
data class ChangeLogCompactionResult(
    /** 已加密并由 manifest 引用的 checkpoint。 */
    val checkpoint: ChangeLogCheckpoint,
    /** 可刷新当前会话的下一版 manifest。 */
    val encryptedManifest: ByteArray,
    /** 原子事务提交结果。 */
    val commit: TransactionCommitResult,
    /** manifest 已移除、可在确认后物理清理的 Change 路径。 */
    val removedPaths: Set<WorkspaceRelativePath>,
)
