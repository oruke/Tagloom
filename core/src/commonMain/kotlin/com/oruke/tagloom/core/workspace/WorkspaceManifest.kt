package com.oruke.tagloom.core.workspace

import com.oruke.tagloom.core.crypto.EncryptionEnvelope
import com.oruke.tagloom.core.crypto.MasterKeySlot
import com.oruke.tagloom.core.crypto.SecretKeyMaterial
import com.oruke.tagloom.core.crypto.UnlockSlotType
import com.oruke.tagloom.core.model.KeyId
import com.oruke.tagloom.core.model.TransactionId
import com.oruke.tagloom.core.model.WorkspaceId
import com.oruke.tagloom.core.transaction.WorkspaceRelativePath
import kotlinx.serialization.Serializable

/**
 * 区分 manifest 中受认证摘要约束的权威对象类别。
 */
@Serializable
enum class ManifestObjectKind {
    /** Note 对象快照。 */
    NOTE,

    /** Tag 对象快照。 */
    TAG,

    /** PropertyDef 对象快照。 */
    PROPERTY,

    /** 加密附件。 */
    BLOB,

    /** 尚未压缩的 Change DAG 节点。 */
    CHANGE,

    /** 工作区级保存查询对象快照。 */
    SAVED_QUERY,

    /** 工作区 Database 权威定义快照。 */
    DATABASE,

    /** 独立于 Database 保存的 View 权威定义快照。 */
    VIEW,

    /** 冲突证据或审计历史。 */
    HISTORY,
}

/**
 * 记录 manifest 对一个已确认密文文件的权威摘要与解密身份。
 */
@Serializable
data class ManifestObjectRecord(
    /** 对象所属的协议类别。 */
    val kind: ManifestObjectKind,
    /** 与文件名对应的稳定不透明对象标识。 */
    val objectId: String,
    /** 对象的逻辑 revision。 */
    val revision: String,
    /** 工作区内受控相对路径。 */
    val path: WorkspaceRelativePath,
    /** 密文文件的 SHA-256 小写十六进制摘要。 */
    val encryptedSha256: String,
    /** 解密该对象所需的 Workspace Master Key 版本。 */
    val keyId: KeyId,
) {
    init {
        require(objectId.matches(OPAQUE_ID_PATTERN)) { "manifest 对象 ID 格式无效" }
        require(revision.isNotBlank()) { "manifest 对象 revision 不能为空" }
        require(encryptedSha256.matches(SHA256_PATTERN)) { "manifest 对象 SHA-256 格式无效" }
    }

    companion object {
        /** 安全映射到工作区文件名的不透明 ID 格式。 */
        private val OPAQUE_ID_PATTERN: Regex = Regex("[A-Za-z0-9_-]{1,128}")

        /** SHA-256 小写十六进制格式。 */
        private val SHA256_PATTERN: Regex = Regex("[0-9a-f]{64}")
    }
}

/**
 * 保存解锁 manifest 密文载荷所必需且允许公开的最小信息。
 */
@Serializable
data class WorkspaceManifestPublicHeader(
    /** manifest 文件固定魔数。 */
    val magic: String = MANIFEST_MAGIC,
    /** manifest 容器和载荷协议版本。 */
    val manifestFormatVersion: Int = MANIFEST_FORMAT_VERSION,
    /** 工作区公开随机标识。 */
    val workspaceId: WorkspaceId,
    /** 新写入和 manifest 载荷当前使用的主密钥版本。 */
    val activeKeyId: KeyId,
    /** 密码与 Recovery Key 包装的主密钥槽集合。 */
    val unlockSlots: List<MasterKeySlot>,
) {
    init {
        require(magic == MANIFEST_MAGIC) { "manifest 魔数不受支持" }
        require(manifestFormatVersion == MANIFEST_FORMAT_VERSION) { "manifest 格式版本不受支持" }
        require(unlockSlots.isNotEmpty()) { "manifest 至少需要一个解锁槽" }
        require(unlockSlots.map { slot -> slot.header.slotId }.distinct().size == unlockSlots.size) {
            "manifest 解锁槽 ID 重复"
        }
        require(unlockSlots.all { slot -> slot.header.workspaceId == workspaceId }) {
            "manifest 解锁槽工作区不一致"
        }
        require(unlockSlots.any { slot ->
            slot.header.keyId == activeKeyId && slot.header.slotType == UnlockSlotType.PASSWORD
        }) { "活动主密钥缺少密码解锁槽" }
        require(unlockSlots.any { slot ->
            slot.header.keyId == activeKeyId && slot.header.slotType == UnlockSlotType.RECOVERY
        }) { "活动主密钥缺少 Recovery Key 解锁槽" }
    }

    companion object {
        /** manifest 与普通对象信封分离的固定魔数。 */
        const val MANIFEST_MAGIC: String = "TAGLOOM-MANIFEST"

        /** 当前 manifest 文件格式版本。 */
        const val MANIFEST_FORMAT_VERSION: Int = 1
    }
}

/**
 * 保存工作区当前确认点、对象摘要和公开头认证摘要的加密 manifest 载荷。
 */
@Serializable
data class WorkspaceManifestPayload(
    /** 规范化公开头的 SHA-256，用于检测密钥槽集合被替换或剥离。 */
    val publicHeaderSha256: String,
    /** 每次确认跨对象事务后单调递增的 manifest revision。 */
    val revision: Long,
    /** 最近一次被 manifest 确认的事务；初始工作区为 null。 */
    val lastCommittedTransactionId: TransactionId? = null,
    /** 有界的最近确认事务历史，用于崩溃恢复幂等判断。 */
    val committedTransactionIds: List<TransactionId> = emptyList(),
    /** 当前全部权威密文对象的摘要记录。 */
    val objects: List<ManifestObjectRecord> = emptyList(),
) {
    init {
        require(publicHeaderSha256.matches(SHA256_PATTERN)) { "manifest 公开头 SHA-256 格式无效" }
        require(revision >= 0L) { "manifest revision 不能为负数" }
        require(committedTransactionIds.size <= MAX_COMMITTED_TRANSACTION_HISTORY) {
            "manifest 确认事务历史超过上限"
        }
        require(committedTransactionIds.distinct().size == committedTransactionIds.size) {
            "manifest 确认事务历史重复"
        }
        require(lastCommittedTransactionId == null || lastCommittedTransactionId in committedTransactionIds) {
            "最近确认事务必须包含在确认历史中"
        }
        require(objects.map(ManifestObjectRecord::path).distinct().size == objects.size) {
            "manifest 对象路径重复"
        }
        require(objects.map { record -> record.kind to record.objectId }.distinct().size == objects.size) {
            "manifest 对象身份重复"
        }
    }

    companion object {
        /** 启动恢复只需保留近期事务确认窗口，较旧历史由 Change checkpoint 承担。 */
        const val MAX_COMMITTED_TRANSACTION_HISTORY: Int = 256

        /** SHA-256 小写十六进制格式。 */
        private val SHA256_PATTERN: Regex = Regex("[0-9a-f]{64}")
    }
}

/**
 * 表示磁盘 `manifest.enc` 的公开头和认证加密载荷。
 */
@Serializable
data class WorkspaceManifestFile(
    /** 解锁必需的公开头。 */
    val publicHeader: WorkspaceManifestPublicHeader,
    /** 使用活动 Workspace Master Key 派生密钥保护的 manifest 载荷信封。 */
    val encryptedPayload: EncryptionEnvelope,
) {
    init {
        require(encryptedPayload.header.workspaceId == publicHeader.workspaceId) {
            "manifest 公开头与密文载荷工作区不一致"
        }
        require(encryptedPayload.header.keyId == publicHeader.activeKeyId) {
            "manifest 密文载荷未使用活动主密钥"
        }
    }
}

/**
 * 表示已认证解锁且由调用方负责主动销毁主密钥的工作区 manifest。
 */
class UnlockedWorkspaceManifest(
    /** 已通过公开头摘要和 AEAD 认证的 manifest 文件。 */
    val file: WorkspaceManifestFile,
    /** 已解密并完成结构校验的权威载荷。 */
    val payload: WorkspaceManifestPayload,
    /** 当前会话持有的 Workspace Master Key。 */
    val workspaceMasterKey: SecretKeyMaterial,
) {
    /**
     * 主动清零当前会话持有的 Workspace Master Key。
     */
    fun destroy() {
        workspaceMasterKey.destroy()
    }
}

/**
 * 返回新建工作区需要持久化和一次性展示的安全材料。
 */
class WorkspaceCreationResult(
    /** 可原子写入 `manifest.enc` 的完整字节。 */
    val manifestBytes: ByteArray,
    /** 创建流程继续写入初始对象时使用的 Workspace Master Key。 */
    val workspaceMasterKey: SecretKeyMaterial,
    /** 必须只展示一次并由用户离线保存的 Recovery Key。 */
    val recoveryKey: SecretKeyMaterial,
) {
    /**
     * 在创建流程完成或失败后清零全部返回的密钥材料。
     */
    fun destroySecrets() {
        workspaceMasterKey.destroy()
        recoveryKey.destroy()
    }
}
