package com.oruke.tagloom.filestore.jvm

import com.oruke.tagloom.core.model.TransactionId
import com.oruke.tagloom.core.transaction.PreparedArtifactRecord
import com.oruke.tagloom.core.transaction.PreparedTransactionRecord

/**
 * 表示解密 manifest 后得到的最小事务确认状态。
 */
data class ManifestTransactionState(
    /** 当前已确认 manifest revision。 */
    val revision: Long,
    /** 尚在恢复保留窗口内的已确认事务标识。 */
    val committedTransactionIds: Set<TransactionId>,
) {
    init {
        require(revision >= 0) { "manifest revision 不能为负数" }
    }
}

/**
 * 抽象需要会话主密钥参与的 staging AEAD、schema 和 manifest 语义验证。
 *
 * JVM 文件适配器只处理密文字节与原子文件操作，不持有 Workspace Master Key。
 */
interface JvmPreparedContentVerifier {
    /**
     * 验证单个 staging artifact 的 AEAD、对象身份、schema、revision 和快照哈希。
     *
     * @param record artifact 恢复记录。
     * @param encryptedBytes 完整加密文件。
     * @return 可以安全发布为 `true`。
     */
    suspend fun verifyArtifact(
        record: PreparedArtifactRecord,
        encryptedBytes: ByteArray,
    ): Boolean

    /**
     * 验证待确认 manifest 的 AEAD、目标 revision 和事务 ID。
     *
     * @param record prepared 事务记录。
     * @param encryptedBytes 完整加密 manifest 文件。
     * @return 可以作为确认点发布为 `true`。
     */
    suspend fun verifyNextManifest(
        record: PreparedTransactionRecord,
        encryptedBytes: ByteArray,
    ): Boolean

    /**
     * 解密并读取当前 manifest 的最小事务状态。
     *
     * @param encryptedBytes 当前 manifest 完整加密文件。
     * @return 已认证的 revision 与确认历史。
     */
    suspend fun inspectManifest(encryptedBytes: ByteArray): ManifestTransactionState
}
