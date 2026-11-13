package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.ai.WorkspaceConfig
import com.oruke.tagloom.core.crypto.CryptoProvider
import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.ObjectSnapshot
import com.oruke.tagloom.core.model.TransactionId
import com.oruke.tagloom.core.sync.HybridLogicalTime
import com.oruke.tagloom.core.transaction.WorkspaceConfigSnapshotEnvelopeService
import com.oruke.tagloom.core.transaction.WorkspaceConfigSnapshotTransactionRequest
import com.oruke.tagloom.core.transaction.WorkspaceConfigSnapshotTransactionResult
import com.oruke.tagloom.core.transaction.WorkspaceConfigSnapshotTransactionService
import com.oruke.tagloom.core.workspace.ManifestObjectKind
import com.oruke.tagloom.core.workspace.ManifestObjectRecord
import com.oruke.tagloom.core.workspace.UnlockedWorkspaceManifest
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import com.oruke.tagloom.filesystem.jvm.JvmWorkspacePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * 从工作区权威加密快照加载、认证并提交单例 `WorkspaceConfig`。
 *
 * @param paths 当前工作区受控路径。
 * @param cryptoProvider 计算密文 SHA-256 的密码学端口。
 * @param envelopeService 解密并认证 `WorkspaceConfig` 信封的服务。
 * @param transactionService `WorkspaceConfig` 统一原子事务服务。
 */
class DesktopWorkspaceConfigStore(
    private val paths: JvmWorkspacePaths,
    private val cryptoProvider: CryptoProvider,
    private val envelopeService: WorkspaceConfigSnapshotEnvelopeService,
    private val transactionService: WorkspaceConfigSnapshotTransactionService,
) {
    /**
     * 读取并认证当前工作区的活动 `WorkspaceConfig` 快照（尚未创建或已删除时返回空快照）。
     *
     * @param unlocked 当前已认证 manifest 与主密钥。
     * @return 有效配置快照与被隔离的脱敏完整性问题。
     */
    suspend fun load(unlocked: UnlockedWorkspaceManifest): DesktopWorkspaceConfigLoadResult =
        withContext(Dispatchers.IO) {
            val issues = mutableListOf<DesktopObjectIntegrityIssue>()
            val record = unlocked.payload.objects.singleOrNull { candidate ->
                candidate.kind == ManifestObjectKind.WORKSPACE_CONFIG &&
                    candidate.objectId == WorkspaceDefaults.DEFAULT_WORKSPACE_CONFIG_ID.value
            } ?: return@withContext DesktopWorkspaceConfigLoadResult(snapshot = null, issues = emptyList())

            val file = paths.target(record.path)
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                issues += record.toIssue(DesktopIntegrityFailure.MISSING_FILE)
                return@withContext DesktopWorkspaceConfigLoadResult(snapshot = null, issues = issues)
            }
            val encrypted = Files.readAllBytes(file)
            if (cryptoProvider.sha256(encrypted).toHex() != record.encryptedSha256) {
                issues += record.toIssue(DesktopIntegrityFailure.HASH_MISMATCH)
                return@withContext DesktopWorkspaceConfigLoadResult(snapshot = null, issues = issues)
            }
            val decrypted = runCatching {
                envelopeService.decrypt(
                    encryptedBytes = encrypted,
                    workspaceId = unlocked.file.publicHeader.workspaceId,
                    objectId = ObjectId(record.objectId),
                    revision = record.revision,
                    keyId = record.keyId,
                    workspaceMasterKey = unlocked.workspaceMasterKey,
                )
            }.getOrElse {
                issues += record.toIssue(DesktopIntegrityFailure.AUTHENTICATION_FAILED)
                null
            }
            val activeSnapshot = decrypted?.takeUnless { it.header.deleted }
            DesktopWorkspaceConfigLoadResult(
                snapshot = activeSnapshot,
                latestRawSnapshot = decrypted,
                issues = issues,
            )
        }

    /**
     * 提交一次 `WorkspaceConfig` 的创建或更新事务。
     *
     * @param unlocked 当前已认证 manifest。
     * @param config 待确认的完整配置载荷。
     * @param actorId 当前会话 Actor。
     * @param actorSequence 预留的严格递增 Actor 序号。
     * @param transactionId 事务 ID。
     * @param hybridLogicalTime 因果时钟。
     * @param committedAt UTC 确认时间。
     * @return 确认后的事务结果。
     */
    suspend fun save(
        unlocked: UnlockedWorkspaceManifest,
        config: WorkspaceConfig,
        actorId: ActorId,
        actorSequence: Long,
        transactionId: TransactionId,
        hybridLogicalTime: HybridLogicalTime,
        committedAt: String,
    ): WorkspaceConfigSnapshotTransactionResult {
        val loaded = load(unlocked)
        check(loaded.issues.isEmpty()) { "workspace_config_corrupted" }
        return transactionService.commit(
            unlocked = unlocked,
            request = WorkspaceConfigSnapshotTransactionRequest(
                objectId = WorkspaceDefaults.DEFAULT_WORKSPACE_CONFIG_ID,
                config = config.copy(id = WorkspaceDefaults.DEFAULT_WORKSPACE_CONFIG_ID),
                previousSnapshot = loaded.latestRawSnapshot,
                actorId = actorId,
                actorSequence = actorSequence,
                transactionId = transactionId,
                hybridLogicalTime = hybridLogicalTime,
                committedAt = committedAt,
                deleted = false,
            ),
        )
    }

    /**
     * 构造不含连接名称或密钥语义的完整性隔离问题。
     *
     * @param failure 失败类别。
     * @return 脱敏完整性问题。
     */
    private fun ManifestObjectRecord.toIssue(failure: DesktopIntegrityFailure): DesktopObjectIntegrityIssue =
        DesktopObjectIntegrityIssue(kind, objectId, failure)

    /**
     * 将字节数组编码为小写十六进制字符串。
     *
     * @return 64 位小写十六进制摘要。
     */
    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
    }
}

/**
 * 保存 `WorkspaceConfig` 读取与完整性校验结果。
 */
data class DesktopWorkspaceConfigLoadResult(
    /** 当前有效的活动配置快照；尚未创建或为 tombstone 时为空。 */
    val snapshot: ObjectSnapshot<WorkspaceConfig>?,
    /** 包含可能为 tombstone 的最新已确认快照，供再次写入时串联 revision 与父 Change。 */
    val latestRawSnapshot: ObjectSnapshot<WorkspaceConfig>? = snapshot,
    /** 扫描过程中隔离的完整性异常。 */
    val issues: List<DesktopObjectIntegrityIssue>,
)
