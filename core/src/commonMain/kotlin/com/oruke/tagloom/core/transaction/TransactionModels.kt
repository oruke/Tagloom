package com.oruke.tagloom.core.transaction

import com.oruke.tagloom.core.model.TransactionId
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * 表示工作区根目录下不含敏感名称的规范化相对路径。
 */
@Serializable
@JvmInline
value class WorkspaceRelativePath(
    /** 使用 `/` 分隔且不以 `/` 开头的路径文本。 */
    val value: String,
) {
    init {
        val segments = value.split('/')
        require(value.isNotBlank()) { "工作区相对路径不能为空" }
        require('\\' !in value && !value.startsWith('/')) { "工作区路径必须使用规范化相对格式" }
        require(segments.none { segment -> segment.isBlank() || segment == "." || segment == ".." }) {
            "工作区路径不能包含空段或目录穿越"
        }
        require(value == CONFIG_PATH || segments.first() in ALLOWED_ARTIFACT_ROOTS) {
            "业务 artifact 只能写入权威对象、Change 或历史目录"
        }
    }

    companion object {
        /** 工作区加密配置固定路径。 */
        private const val CONFIG_PATH: String = "config.enc"

        /** Transaction Coordinator 允许发布的权威数据目录。 */
        private val ALLOWED_ARTIFACT_ROOTS: Set<String> = setOf(
            "notes",
            "tags",
            "properties",
            "blobs",
            "changes",
            "queries",
            "databases",
            "views",
            "history",
        )
    }
}

/**
 * 表示事务内一个已经加密、可直接暂存的业务文件。
 */
data class EncryptedTransactionArtifact(
    /** 最终发布的工作区相对路径，仅由稳定 ID 组成。 */
    val targetPath: WorkspaceRelativePath,
    /** 完整版本化加密信封字节，不得包含明文。 */
    val encryptedBytes: ByteArray,
    /** 加密字节的 SHA-256 小写十六进制摘要。 */
    val expectedSha256: String,
) {
    init {
        require(encryptedBytes.isNotEmpty()) { "事务 artifact 不能为空" }
        require(expectedSha256.matches(SHA256_PATTERN)) { "artifact SHA-256 格式无效" }
    }

    companion object {
        /** SHA-256 小写十六进制格式。 */
        private val SHA256_PATTERN: Regex = Regex("[0-9a-f]{64}")
    }
}

/**
 * 表示一次业务提交准备写入的全部密文和目标 manifest。
 */
data class EncryptedTransactionPlan(
    /** 跨对象业务提交的稳定标识。 */
    val transactionId: TransactionId,
    /** 事务开始时已确认 manifest revision。 */
    val baseManifestRevision: Long,
    /** 本事务确认后的 manifest revision。 */
    val targetManifestRevision: Long,
    /** 至少一个待原子发布的加密业务文件。 */
    val artifacts: List<EncryptedTransactionArtifact>,
    /** 已包含本事务确认点的完整加密 manifest 文件。 */
    val encryptedManifest: ByteArray,
    /** 加密 manifest 文件的 SHA-256 小写十六进制摘要。 */
    val manifestSha256: String,
) {
    init {
        require(baseManifestRevision >= 0) { "base manifest revision 不能为负数" }
        require(targetManifestRevision == baseManifestRevision + 1) { "manifest revision 必须严格递增 1" }
        require(artifacts.isNotEmpty()) { "事务至少需要一个业务 artifact" }
        require(artifacts.map(EncryptedTransactionArtifact::targetPath).distinct().size == artifacts.size) {
            "同一事务不能重复写入相同路径"
        }
        require(encryptedManifest.isNotEmpty()) { "事务 manifest 不能为空" }
        require(manifestSha256.matches(SHA256_PATTERN)) { "manifest SHA-256 格式无效" }
    }

    companion object {
        /** SHA-256 小写十六进制格式。 */
        private val SHA256_PATTERN: Regex = Regex("[0-9a-f]{64}")
    }
}

/**
 * 描述 prepared 事务记录中单个 staging 文件的恢复信息。
 */
@Serializable
data class PreparedArtifactRecord(
    /** 最终目标路径。 */
    val targetPath: WorkspaceRelativePath,
    /** staging 目录内不含路径分隔符的随机文件名。 */
    val stagedFileName: String,
    /** 新密文的预期 SHA-256。 */
    val expectedSha256: String,
    /** 准备事务时目标文件是否已有确认版本。 */
    val previousVersionExists: Boolean,
    /** 已有版本的 SHA-256；目标原先不存在时为空。 */
    val previousSha256: String? = null,
) {
    init {
        require(stagedFileName.isNotBlank() && '/' !in stagedFileName && '\\' !in stagedFileName) {
            "staging 文件名必须是单个不透明名称"
        }
        require(expectedSha256.matches(SHA256_PATTERN)) { "prepared artifact SHA-256 格式无效" }
        require(previousVersionExists == (previousSha256 != null)) { "旧版本存在状态与摘要不一致" }
        require(previousSha256 == null || previousSha256.matches(SHA256_PATTERN)) { "旧版本 SHA-256 格式无效" }
    }

    companion object {
        /** SHA-256 小写十六进制格式。 */
        private val SHA256_PATTERN: Regex = Regex("[0-9a-f]{64}")
    }
}

/**
 * 表示已 fsync、可以在重启后继续或回滚的 prepared 事务记录。
 */
@Serializable
data class PreparedTransactionRecord(
    /** 事务标识。 */
    val transactionId: TransactionId,
    /** 准备事务时已确认 manifest revision。 */
    val baseManifestRevision: Long,
    /** 事务成功确认后的 manifest revision。 */
    val targetManifestRevision: Long,
    /** 全部业务 staging 文件及旧版本备份信息。 */
    val artifacts: List<PreparedArtifactRecord>,
    /** staging 中加密 manifest 的文件名。 */
    val stagedManifestFileName: String,
    /** staging 中加密 manifest 的预期 SHA-256。 */
    val manifestSha256: String,
) {
    init {
        require(baseManifestRevision >= 0 && targetManifestRevision == baseManifestRevision + 1) {
            "prepared manifest revision 无效"
        }
        require(artifacts.isNotEmpty()) { "prepared 事务至少需要一个 artifact" }
        require(stagedManifestFileName.isNotBlank() && '/' !in stagedManifestFileName && '\\' !in stagedManifestFileName) {
            "staging manifest 文件名必须是单个不透明名称"
        }
        require(manifestSha256.matches(SHA256_PATTERN)) { "prepared manifest SHA-256 格式无效" }
    }

    companion object {
        /** SHA-256 小写十六进制格式。 */
        private val SHA256_PATTERN: Regex = Regex("[0-9a-f]{64}")
    }
}

/**
 * 描述启动扫描 prepared staging 后得到的可恢复记录和已隔离记录。
 *
 * 损坏控制记录无法安全构造成 [PreparedTransactionRecord]，存储实现必须先将其移动到隔离区，
 * 再通过 [quarantinedTransactionIds] 把只读要求交给事务协调器。
 */
data class PendingPreparedTransactions(
    /** 已通过控制记录解密、结构和事务 ID 校验的恢复候选。 */
    val records: List<PreparedTransactionRecord>,
    /** 已被存储实现移动到隔离区的诊断事务 ID。 */
    val quarantinedTransactionIds: List<TransactionId> = emptyList(),
)

/**
 * 描述 prepared staging 是否足以安全完成事务。
 */
enum class PreparedValidationResult {
    /** 全部密文、摘要和恢复信息完整。 */
    VALID,

    /** 至少一个文件缺失、截断、认证失败或 schema 无效。 */
    INVALID,
}

/**
 * 描述启动恢复对单个事务采取的最终动作。
 */
enum class TransactionRecoveryAction {
    /** manifest 已确认，只补齐 committed 标记并清理。 */
    CONFIRMED_AND_CLEANED,

    /** staging 完整，重放发布并确认新状态。 */
    COMPLETED,

    /** staging 损坏且已成功恢复全部旧版本。 */
    ROLLED_BACK,

    /** 无法可靠完成或回滚，事务已隔离并要求只读安全模式。 */
    QUARANTINED,
}

/**
 * 描述单个事务的启动恢复结果。
 */
data class TransactionRecoveryEntry(
    /** 被处理的事务。 */
    val transactionId: TransactionId,
    /** 恢复最终动作。 */
    val action: TransactionRecoveryAction,
)

/**
 * 汇总一次工作区启动恢复结果。
 */
data class TransactionRecoveryReport(
    /** 按 prepared 记录顺序排列的恢复结果。 */
    val entries: List<TransactionRecoveryEntry>,
    /** 是否必须阻止后续写入并进入只读安全模式。 */
    val requiresReadOnlyMode: Boolean,
)

/**
 * 描述正常提交完成后派生索引是否同步更新成功。
 */
data class TransactionCommitResult(
    /** 已由 manifest 确认的事务。 */
    val transactionId: TransactionId,
    /** SQLite、双链等派生状态是否完成更新。 */
    val derivedStateUpdated: Boolean,
)
