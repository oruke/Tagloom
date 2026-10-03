package com.oruke.tagloom.core.transaction

import com.oruke.tagloom.core.agent.AgentAuditEvent
import com.oruke.tagloom.core.crypto.*
import com.oruke.tagloom.core.model.TransactionId
import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.workspace.*

/** 保存加密审计产物及 manifest 记录，供业务写入与独立审计共用。 */
data class AgentAuditArtifact(
    /** 由原事务协调器发布的密文。 */
    val artifact: EncryptedTransactionArtifact,
    /** 与产物一起进入 manifest 的认证摘要。 */
    val record: ManifestObjectRecord,
)

/** 通过用途隔离信封和原事务协调器保存外部调用审计，不保存参数、正文或凭据。 */
class AgentAuditTransactionService(
    /** 密文摘要和安全随机数来源。 */
    private val crypto: CryptoProvider,
    /** 使用 AUDIT 包装密钥加密记录。 */
    private val envelopes: EnvelopeCryptoService,
    /** 创建包含历史记录的下一确认点。 */
    private val manifests: WorkspaceManifestService,
    /** 当前工作区唯一文件事务协调器。 */
    private val coordinator: TransactionCoordinator,
) {
    /**
     * 创建不落盘的审计产物，写入业务可以把它合入同一事务。
     * @param unlocked 当前已认证工作区。
     * @param event 不包含明文业务内容的审计事件。
     * @param transactionId 随机审计身份，与业务事务关联。
     * @return 可直接合入业务事务的密文和记录。
     */
    suspend fun encrypt(unlocked: UnlockedWorkspaceManifest, event: AgentAuditEvent, transactionId: TransactionId): AgentAuditArtifact {
        val id = transactionId.value
        val path = WorkspaceRelativePath("history/agents/$id.audit.enc")
        val plaintext = CanonicalJson.encodeToString(AgentAuditEvent.serializer(), event).encodeToByteArray()
        val encrypted = try {
            EncryptionEnvelopeCodec.encode(envelopes.encrypt(
                EnvelopeEncryptionRequest(
                    identity = EnvelopeIdentity(unlocked.file.publicHeader.workspaceId, "audit-record", id, "1"),
                    keyId = unlocked.file.publicHeader.activeKeyId,
                    wrappingKeyPurpose = WrappingKeyPurpose.AUDIT,
                    plaintext = plaintext,
                ), unlocked.workspaceMasterKey,
            ))
        } finally {
            plaintext.fill(0)
        }
        val digest = crypto.sha256(encrypted).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        return AgentAuditArtifact(
            EncryptedTransactionArtifact(path, encrypted, digest),
            ManifestObjectRecord(ManifestObjectKind.HISTORY, id, "1", path, digest, unlocked.file.publicHeader.activeKeyId),
        )
    }

    /**
     * 独立确认只读、拒绝、取消或失败事件。
     * @param unlocked 当前已认证工作区。
     * @param event 脱敏审计事件。
     * @param transactionId 本次随机事务身份。
     * @return 已由 manifest 确认的新密文 manifest。
     */
    suspend fun commit(unlocked: UnlockedWorkspaceManifest, event: AgentAuditEvent, transactionId: TransactionId): ByteArray {
        val audit = encrypt(unlocked, event, transactionId)
        val next = manifests.confirmTransaction(unlocked, transactionId, listOf(audit.record), emptySet())
        val digest = crypto.sha256(next).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        coordinator.commit(EncryptedTransactionPlan(
            transactionId, unlocked.payload.revision, unlocked.payload.revision + 1,
            listOf(audit.artifact), next, digest,
        ))
        return next
    }
}
