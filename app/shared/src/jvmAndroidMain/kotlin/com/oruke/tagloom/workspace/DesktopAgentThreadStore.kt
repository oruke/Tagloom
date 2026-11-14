package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.ai.AgentThread
import com.oruke.tagloom.core.crypto.CryptoProvider
import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.ObjectSnapshot
import com.oruke.tagloom.core.model.TransactionId
import com.oruke.tagloom.core.sync.HybridLogicalTime
import com.oruke.tagloom.core.transaction.AgentThreadSnapshotEnvelopeService
import com.oruke.tagloom.core.transaction.AgentThreadSnapshotTransactionRequest
import com.oruke.tagloom.core.transaction.AgentThreadSnapshotTransactionResult
import com.oruke.tagloom.core.transaction.AgentThreadSnapshotTransactionService
import com.oruke.tagloom.core.workspace.ManifestObjectKind
import com.oruke.tagloom.core.workspace.ManifestObjectRecord
import com.oruke.tagloom.core.workspace.UnlockedWorkspaceManifest
import com.oruke.tagloom.filesystem.jvm.JvmWorkspacePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * 从工作区权威加密快照加载、认证并提交持久化 `AgentThread` 会话对象。
 *
 * 无痕会话仅驻留内存，绝不经过本存储写入工作区目录、manifest 或备份包。
 *
 * @param paths 当前工作区受控路径。
 * @param cryptoProvider 计算密文 SHA-256 的密码学端口。
 * @param envelopeService 解密并认证 `AgentThread` 信封的服务。
 * @param transactionService `AgentThread` 统一原子事务服务。
 */
class DesktopAgentThreadStore(
    private val paths: JvmWorkspacePaths,
    internal val cryptoProvider: CryptoProvider,
    private val envelopeService: AgentThreadSnapshotEnvelopeService,
    internal val transactionService: AgentThreadSnapshotTransactionService,
) {
    /**
     * 读取并认证当前工作区的全部持久化 `AgentThread` 快照，按稳定活动顺序排列。
     *
     * @param unlocked 当前已认证 manifest 与主密钥。
     * @return 非删除会话快照、包含 tombstone 的原始快照映射以及逻辑隔离问题。
     */
    suspend fun loadAll(unlocked: UnlockedWorkspaceManifest): DesktopAgentThreadLoadResult =
        withContext(Dispatchers.IO) {
            val issues = mutableListOf<DesktopObjectIntegrityIssue>()
            val active = mutableListOf<ObjectSnapshot<AgentThread>>()
            val rawById = linkedMapOf<ObjectId, ObjectSnapshot<AgentThread>>()
            val records = unlocked.payload.objects
                .filter { candidate -> candidate.kind == ManifestObjectKind.AGENT_THREAD }
                .sortedBy { candidate -> candidate.objectId }

            for (record in records) {
                val file = paths.target(record.path)
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    issues += record.toIssue(DesktopIntegrityFailure.MISSING_FILE)
                    continue
                }
                val encrypted = Files.readAllBytes(file)
                if (cryptoProvider.sha256(encrypted).toHex() != record.encryptedSha256) {
                    issues += record.toIssue(DesktopIntegrityFailure.HASH_MISMATCH)
                    continue
                }
                val objectId = ObjectId(record.objectId)
                val decrypted = runCatching {
                    envelopeService.decrypt(
                        encryptedBytes = encrypted,
                        workspaceId = unlocked.file.publicHeader.workspaceId,
                        objectId = objectId,
                        revision = record.revision,
                        keyId = record.keyId,
                        workspaceMasterKey = unlocked.workspaceMasterKey,
                    )
                }.getOrElse {
                    issues += record.toIssue(DesktopIntegrityFailure.AUTHENTICATION_FAILED)
                    null
                } ?: continue
                rawById[objectId] = decrypted
                if (!decrypted.header.deleted) {
                    active += decrypted
                }
            }
            val sortedActive = active.sortedWith(
                compareByDescending<ObjectSnapshot<AgentThread>> { it.payload.lastMessageAt }
                    .thenByDescending { it.payload.createdAt }
                    .thenBy { it.header.objectId.value },
            )
            DesktopAgentThreadLoadResult(
                threads = sortedActive,
                rawById = rawById,
                issues = issues,
            )
        }

    /**
     * 提交一次持久化 `AgentThread` 的新建或更新快照事务（拒绝任何标记为 `incognito` 的会话）。
     *
     * @param unlocked 当前已认证 manifest。
     * @param thread 待确认的完整会话载荷。
     * @param actorId 当前会话 Actor。
     * @param actorSequence 严格递增 Actor 序号。
     * @param transactionId 事务 ID。
     * @param hybridLogicalTime 因果时钟。
     * @param committedAt UTC 确认时间。
     * @return 确认后的事务结果。
     */
    suspend fun save(
        unlocked: UnlockedWorkspaceManifest,
        thread: AgentThread,
        actorId: ActorId,
        actorSequence: Long,
        transactionId: TransactionId,
        hybridLogicalTime: HybridLogicalTime,
        committedAt: String,
    ): AgentThreadSnapshotTransactionResult {
        val loaded = loadAll(unlocked)
        check(loaded.issues.none { it.objectId == thread.id.value }) { "agent_thread_corrupted" }
        return transactionService.commit(
            unlocked = unlocked,
            request = AgentThreadSnapshotTransactionRequest(
                objectId = thread.id,
                thread = thread,
                previousSnapshot = loaded.rawById[thread.id],
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
     * 将一条已存在的持久化 `AgentThread` 写为 tombstone。
     *
     * @param unlocked 当前已认证 manifest。
     * @param threadId 待软删除的会话对象 ID。
     * @param actorId 当前会话 Actor。
     * @param actorSequence 严格递增 Actor 序号。
     * @param transactionId 事务 ID。
     * @param hybridLogicalTime 因果时钟。
     * @param committedAt UTC 确认时间。
     * @return 确认后的删除事务结果。
     */
    suspend fun delete(
        unlocked: UnlockedWorkspaceManifest,
        threadId: ObjectId,
        actorId: ActorId,
        actorSequence: Long,
        transactionId: TransactionId,
        hybridLogicalTime: HybridLogicalTime,
        committedAt: String,
    ): AgentThreadSnapshotTransactionResult {
        val loaded = loadAll(unlocked)
        val previous = loaded.rawById[threadId]?.takeUnless { it.header.deleted }
            ?: throw IllegalArgumentException("Agent 会话不存在或已删除")
        return transactionService.commit(
            unlocked = unlocked,
            request = AgentThreadSnapshotTransactionRequest(
                objectId = threadId,
                thread = previous.payload,
                previousSnapshot = previous,
                actorId = actorId,
                actorSequence = actorSequence,
                transactionId = transactionId,
                hybridLogicalTime = hybridLogicalTime,
                committedAt = committedAt,
                deleted = true,
            ),
        )
    }

    /**
     * 构造不含会话标题或消息正文的脱敏完整性问题。
     *
     * @param failure 失败分类。
     * @return 脱敏完整性记录。
     */
    private fun ManifestObjectRecord.toIssue(failure: DesktopIntegrityFailure): DesktopObjectIntegrityIssue =
        DesktopObjectIntegrityIssue(kind, objectId, failure)

    /**
     * 将摘要字节编码为小写十六进制字符串。
     *
     * @return 64 位小写十六进制摘要。
     */
    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
    }
}

/**
 * 保存工作区持久化 `AgentThread` 扫描与认证结果。
 */
data class DesktopAgentThreadLoadResult(
    /** 当前全部未删除的持久化会话快照（按 `lastMessageAt` 降序稳定排列）。 */
    val threads: List<ObjectSnapshot<AgentThread>>,
    /** 包含 tombstone 的最新原始快照映射，用于再次提交时串联 revision 与父 Change。 */
    val rawById: Map<ObjectId, ObjectSnapshot<AgentThread>>,
    /** 扫描过程中逻辑隔离的完整性异常。 */
    val issues: List<DesktopObjectIntegrityIssue>,
)
