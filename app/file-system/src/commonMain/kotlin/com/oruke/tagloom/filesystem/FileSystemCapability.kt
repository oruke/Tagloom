package com.oruke.tagloom.filesystem

import com.oruke.tagloom.core.crypto.EnvelopeIdentity
import com.oruke.tagloom.core.crypto.SecretKeyMaterial
import com.oruke.tagloom.core.crypto.WrappingKeyPurpose
import com.oruke.tagloom.core.model.KeyId

/**
 * 定义工作区需要的跨平台文件能力。
 *
 * 这里使用逻辑相对路径，不暴露 JVM、Android 或 Apple 的路径类型。底层实现可以使用
 * `kotlinx-io` 完成基础读写，但原子发布、目录落盘、恢复和写租约必须由实现兑现本契约的语义。
 */
interface WorkspaceFileSystem {
    /**
     * 读取逻辑路径对应的原始字节。
     *
     * @param path 工作区内的逻辑相对路径。
     * @return 文件存在时返回完整字节，不存在时返回 `null`。
     */
    suspend fun read(path: WorkspacePath): ByteArray?

    /**
     * 以同目录原子替换方式发布原始字节。
     *
     * @param path 工作区内的逻辑相对路径。
     * @param bytes 待发布的完整字节；调用方仍拥有该数组的生命周期。
     */
    suspend fun writeAtomically(path: WorkspacePath, bytes: ByteArray)

    /**
     * 将逻辑目录此前的创建、重命名或删除元数据请求落到稳定存储。
     *
     * 原子替换只保证命名切换不可见；调用方在发布确认点前仍必须显式刷新父目录，
     * 才能在进程或设备断电后保留目录项。
     *
     * @param directory 工作区内的逻辑目录。
     */
    suspend fun forceDirectory(directory: WorkspacePath)

    /**
     * 删除逻辑路径对应的文件或空目录。
     *
     * @param path 工作区内的逻辑相对路径。
     * @return 目标存在并被删除时为 true，不存在时为 false。
     */
    suspend fun delete(path: WorkspacePath): Boolean

    /**
     * 列出逻辑目录的直接子路径。
     *
     * @param directory 工作区内的逻辑目录。
     * @return 按实现约定稳定排序的直接子路径。
     */
    suspend fun list(directory: WorkspacePath): List<WorkspacePath>

    /**
     * 判断逻辑路径是否存在。
     *
     * @param path 工作区内的逻辑相对路径。
     * @return 路径存在时为 true。
     */
    suspend fun exists(path: WorkspacePath): Boolean

    /**
     * 读取并认证一个加密文件，拒绝身份、密钥版本或用途不匹配的文件调包。
     *
     * @param request 解密所需的文件身份、密钥版本和会话主密钥。
     * @return 文件存在且认证成功后的明文，不存在时返回 `null`。
     */
    suspend fun readEncrypted(request: EncryptedFileReadRequest): ByteArray?

    /**
     * 加密并以原子方式发布一个文件。
     *
     * @param request 待加密明文、文件身份和会话密钥。
     * @return 已发布密文的脱敏摘要与尺寸，不返回密文字节副本。
     */
    suspend fun writeEncryptedAtomically(request: EncryptedFileWriteRequest): EncryptedFileWriteResult

    /**
     * 处理上次进程终止遗留的 prepared 事务。
     *
     * 实现必须先验证密文和确认点，再决定完成、回滚或隔离；不能因为索引可重建就跳过权威文件恢复。
     *
     * @return 本次启动恢复采取的脱敏统计。
     */
    suspend fun recoverPendingTransactions(): FileRecoveryReport

    /**
     * 获取工作区单写入者租约。
     *
     * @return 调用方负责在工作区锁定或生命周期结束时释放的租约。
     */
    suspend fun acquireWriteLease(): WorkspaceWriteLease
}

/**
 * 表示工作区内的逻辑相对路径，避免能力契约暴露平台路径对象。
 */
@JvmInline
value class WorkspacePath(val value: String) {
    init {
        require(value.isNotBlank()) { "工作区路径不能为空" }
        require(!value.startsWith('/') && !value.startsWith('\\')) { "工作区路径必须是相对路径" }
        require(value.split('/', '\\').none { segment -> segment == ".." }) { "工作区路径不能包含父级跳转" }
    }
}

/**
 * 描述一次加密文件读取所需的认证上下文。
 */
data class EncryptedFileReadRequest(
    /** 待读取的逻辑路径。 */
    val path: WorkspacePath,
    /** 路径和信封必须匹配的公开身份。 */
    val expectedIdentity: EnvelopeIdentity,
    /** manifest 为该文件选择的密钥版本。 */
    val expectedKeyId: KeyId,
    /** 文件角色对应的用途隔离域。 */
    val expectedWrappingKeyPurpose: WrappingKeyPurpose,
    /** 当前解锁会话的工作区主密钥；实现不得保存引用。 */
    val workspaceMasterKey: SecretKeyMaterial,
)

/**
 * 描述一次加密文件写入请求。
 */
data class EncryptedFileWriteRequest(
    /** 待发布的逻辑路径。 */
    val path: WorkspacePath,
    /** 路径、对象和修订必须绑定到信封公开头的身份。 */
    val identity: EnvelopeIdentity,
    /** 当前使用的主密钥版本。 */
    val keyId: KeyId,
    /** 文件角色对应的用途隔离域。 */
    val wrappingKeyPurpose: WrappingKeyPurpose,
    /** 当前解锁会话的工作区主密钥；实现不得保存引用。 */
    val workspaceMasterKey: SecretKeyMaterial,
    /** 仅在加密调用期间存在的明文。 */
    val plaintext: ByteArray,
)

/**
 * 描述加密文件成功发布后的非敏感结果。
 */
data class EncryptedFileWriteResult(
    /** 实际发布的逻辑路径。 */
    val path: WorkspacePath,
    /** 已发布密文的 SHA-256 小写摘要。 */
    val encryptedSha256: String,
    /** 已发布密文字节数。 */
    val encryptedSize: Long,
)

/**
 * 描述一次启动恢复的结果，只包含可安全展示的数量和动作统计。
 */
data class FileRecoveryReport(
    /** 成功完成并清理的事务数量。 */
    val completed: Int,
    /** 回滚到旧确认点并清理的事务数量。 */
    val rolledBack: Int,
    /** 无法证明安全而被隔离的事务数量。 */
    val quarantined: Int,
)

/**
 * 表示工作区写入租约，保证同一工作区同一设备只有一个写入协调器。
 */
interface WorkspaceWriteLease {
    /**
     * 释放写入租约；重复释放必须安全。
     */
    suspend fun release()
}

/**
 * 创建当前宿主的工作区文件能力。
 *
 * @return 当前宿主的文件系统实现。
 */
expect fun createWorkspaceFileSystem(): WorkspaceFileSystem
