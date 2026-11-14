package com.oruke.tagloom.core.transaction

import com.oruke.tagloom.core.ai.AgentThread
import com.oruke.tagloom.core.crypto.CryptoProvider
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.ObjectSnapshot
import com.oruke.tagloom.core.sync.ObjectChange
import com.oruke.tagloom.core.workspace.UnlockedWorkspaceManifest
import com.oruke.tagloom.core.workspace.WorkspaceManifestService

/**
 * 返回一次 Agent 修改提案确认在同一 manifest 确认点中同时发布的 `Note` 与可选持久化 `AgentThread` 结果。
 */
data class AgentProposalCommitTransactionResult(
    /** 新确认的目标笔记快照。 */
    val noteSnapshot: ObjectSnapshot<Note>,
    /** 目标笔记对应的逻辑 Change。 */
    val noteChange: ObjectChange,
    /** 新确认的持久化会话快照；无痕会话提案确认时为空。 */
    val threadSnapshot: ObjectSnapshot<AgentThread>?,
    /** 持久化会话对应的逻辑 Change；无痕会话提案确认时为空。 */
    val threadChange: ObjectChange?,
    /** 唯一确认点的加密 manifest 完整字节。 */
    val encryptedManifest: ByteArray,
    /** 底层原子事务发布结果。 */
    val commit: TransactionCommitResult,
)

/**
 * 将用户确认的 Agent 细粒度笔记修改（`Note` 快照 + `ObjectChange`）与会话提案状态更新（`AgentThread` 快照 + `ObjectChange`）
 * 合并进单次 `TransactionCoordinator.commit(EncryptedTransactionPlan)` 确认点，彻底杜绝“笔记已改但提案仍处于待确认”的半确认分叉。
 *
 * @param cryptoProvider 密码学摘要服务。
 * @param workspaceManifestService manifest 确认服务。
 * @param transactionCoordinator 原子文件事务协调器。
 * @param noteTransactionService 笔记快照加密与校验服务。
 * @param threadTransactionService 会话快照加密与校验服务。
 */
class AgentProposalCommitTransactionService(
    private val cryptoProvider: CryptoProvider,
    private val workspaceManifestService: WorkspaceManifestService,
    private val transactionCoordinator: TransactionCoordinator,
    private val noteTransactionService: NoteSnapshotTransactionService,
    private val threadTransactionService: AgentThreadSnapshotTransactionService,
) {
    /**
     * 在单次 manifest 确认点中提交目标 `Note` 更新以及可选的持久化 `AgentThread` 状态更新。
     *
     * @param unlocked 当前已解锁工作区 manifest 与主密钥。
     * @param noteRequest 目标笔记创建或更新请求。
     * @param threadRequest 持久化会话状态更新请求；无痕会话（仅驻留内存）时传入 `null`。
     * @return 同时确认后的笔记快照、会话快照、加密 manifest 与提交结果。
     */
    suspend fun commit(
        unlocked: UnlockedWorkspaceManifest,
        noteRequest: NoteSnapshotTransactionRequest,
        threadRequest: AgentThreadSnapshotTransactionRequest?,
    ): AgentProposalCommitTransactionResult {
        if (threadRequest == null) {
            val single = noteTransactionService.commit(unlocked, noteRequest)
            return AgentProposalCommitTransactionResult(
                noteSnapshot = single.snapshot,
                noteChange = single.change,
                threadSnapshot = null,
                threadChange = null,
                encryptedManifest = single.encryptedManifest,
                commit = single.commit,
            )
        }
        require(noteRequest.transactionId == threadRequest.transactionId) {
            "Agent 提案复合事务的 Note 与 AgentThread 必须共享同一 transactionId"
        }
        val preparedNote = noteTransactionService.prepare(unlocked, noteRequest)
        val preparedThread = threadTransactionService.prepare(unlocked, threadRequest)
        val combinedRecords = preparedNote.records + preparedThread.records
        val combinedArtifacts = preparedNote.artifacts + preparedThread.artifacts
        val nextManifest = workspaceManifestService.confirmTransaction(
            unlocked = unlocked,
            transactionId = noteRequest.transactionId,
            upserts = combinedRecords,
            removedPaths = emptySet(),
            minimumFormatVersion = if (noteRequest.note.linkMetadata != null) 2 else 1,
        )
        val commit = transactionCoordinator.commit(
            EncryptedTransactionPlan(
                transactionId = noteRequest.transactionId,
                baseManifestRevision = unlocked.payload.revision,
                targetManifestRevision = unlocked.payload.revision + 1L,
                artifacts = combinedArtifacts,
                encryptedManifest = nextManifest,
                manifestSha256 = sha256(nextManifest),
            ),
        )
        return AgentProposalCommitTransactionResult(
            noteSnapshot = preparedNote.snapshot,
            noteChange = preparedNote.change,
            threadSnapshot = preparedThread.snapshot,
            threadChange = preparedThread.change,
            encryptedManifest = nextManifest,
            commit = commit,
        )
    }

    /**
     * 计算字节数组的 SHA-256 小写十六进制摘要。
     *
     * @param bytes 输入字节。
     * @return 64 位小写十六进制字符串。
     */
    private fun sha256(bytes: ByteArray): String = cryptoProvider.sha256(bytes).joinToString("") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
    }
}
