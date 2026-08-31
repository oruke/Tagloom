package com.oruke.tagloom.filesystem.jvm

import com.oruke.tagloom.core.crypto.EncryptionEnvelopeCodec
import com.oruke.tagloom.core.crypto.EnvelopeCryptoService
import com.oruke.tagloom.core.crypto.EnvelopeDecryptionContext
import com.oruke.tagloom.core.crypto.EnvelopeEncryptionRequest
import com.oruke.tagloom.core.crypto.SecretKeyMaterial
import com.oruke.tagloom.core.transaction.DerivedStateUpdater
import com.oruke.tagloom.core.transaction.TransactionCoordinator
import com.oruke.tagloom.core.transaction.TransactionRecoveryAction
import com.oruke.tagloom.core.transaction.TransactionStoragePort
import com.oruke.tagloom.core.workspace.WorkspaceManifestService
import com.oruke.tagloom.filesystem.EncryptedFileReadRequest
import com.oruke.tagloom.filesystem.EncryptedFileWriteRequest
import com.oruke.tagloom.filesystem.EncryptedFileWriteResult
import com.oruke.tagloom.filesystem.FileRecoveryReport
import com.oruke.tagloom.filesystem.WorkspaceFileSystem
import com.oruke.tagloom.filesystem.WorkspacePath
import com.oruke.tagloom.filesystem.WorkspaceWriteLease
import com.oruke.tagloom.filesystem.jvm.crypto.LibsodiumCryptoProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.stream.Collectors

/**
 * 在 JVM 上实现工作区逻辑文件、加密信封和单写入者租约能力。
 *
 * 该类只接受 [WorkspacePath] 作为业务路径，并把实际 [Path] 限制在工作区根目录内。
 * 原子替换、目录持久化和符号链接拒绝集中复用 [JvmAtomicFileOperations]，避免文件提交
 * 在不同调用入口出现不一致的崩溃语义。
 *
 * @param workspaceRoot 工作区根目录；构造时会创建缺失目录。
 * @param cryptoProvider JVM 密码学实现；调用方负责其全局初始化生命周期。
 * @param recoveryContext 可选的已解锁恢复上下文；缺失时只能执行普通文件操作。
 * @param directoryDurability 目录元数据持久化实现。
 * @param dispatcher 执行阻塞文件操作的协程调度器。
 */
class JvmWorkspaceFileSystem(
    workspaceRoot: Path,
    private val cryptoProvider: com.oruke.tagloom.core.crypto.CryptoProvider = LibsodiumCryptoProvider(),
    private val recoveryContext: JvmWorkspaceRecoveryContext? = null,
    directoryDurability: JvmDirectoryDurability = JvmDirectoryDurabilityFactory.create(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : WorkspaceFileSystem {
    /** 规范化后的工作区根目录；所有业务路径都必须位于此目录内。 */
    private val root: Path = workspaceRoot.toAbsolutePath().normalize()

    /** 当前能力实例绑定的规范化工作区根目录，供同一会话的事务装配校验使用。 */
    val workspaceRoot: Path
        get() = root

    /** 同目录临时文件、原子移动和目录 force 的组合原语。 */
    private val atomicFiles: JvmAtomicFileOperations = JvmAtomicFileOperations(directoryDurability)

    /** 处理加密信封的通用领域服务。 */
    private val envelopeCryptoService: EnvelopeCryptoService = EnvelopeCryptoService(cryptoProvider)

    /** 串行化当前进程内的路径操作，补充跨进程文件锁。 */
    private val accessMutex: Mutex = Mutex()

    init {
        require(!Files.isSymbolicLink(root)) { "工作区根目录不能是符号链接" }
        Files.createDirectories(root)
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) { "工作区根目录不是目录" }
    }

    /**
     * 读取一个逻辑路径的完整原始字节。
     *
     * @param path 工作区内的逻辑相对路径。
     * @return 普通文件存在时返回字节，不存在时返回 `null`。
     */
    override suspend fun read(path: WorkspacePath): ByteArray? = withContext(dispatcher) {
        accessMutex.withLock {
            val target = resolve(path)
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                null
            } else {
                require(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) { "工作区路径不是普通文件" }
                Files.readAllBytes(target)
            }
        }
    }

    /**
     * 以同目录临时文件和原子移动方式发布原始字节。
     *
     * @param path 工作区内的逻辑相对路径。
     * @param bytes 待发布的完整字节。
     */
    override suspend fun writeAtomically(
        path: WorkspacePath,
        bytes: ByteArray,
    ): Unit = withContext(dispatcher) {
        accessMutex.withLock {
            atomicFiles.writeAtomically(resolve(path), bytes)
        }
    }

    /**
     * 创建并 force 一个逻辑目录，确保目录项元数据进入稳定存储。
     *
     * @param directory 工作区内的逻辑目录。
     */
    override suspend fun forceDirectory(directory: WorkspacePath): Unit = withContext(dispatcher) {
        accessMutex.withLock {
            val target = resolve(directory)
            Files.createDirectories(target)
            require(Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) { "工作区路径不是目录" }
            atomicFiles.forceDirectory(target)
        }
    }

    /**
     * 删除一个普通文件或空目录，并 force 父目录元数据。
     *
     * @param path 待删除的逻辑路径。
     * @return 目标存在并被删除时为 true。
     */
    override suspend fun delete(path: WorkspacePath): Boolean = withContext(dispatcher) {
        accessMutex.withLock {
            val target = resolve(path)
            val existed = Files.exists(target, LinkOption.NOFOLLOW_LINKS)
            atomicFiles.deleteDurably(target)
            existed && !existsWithoutLock(path)
        }
    }

    /**
     * 列出逻辑目录的直接子路径，并按规范化文本稳定排序。
     *
     * @param directory 工作区内的逻辑目录。
     * @return 直接子路径集合。
     */
    override suspend fun list(directory: WorkspacePath): List<WorkspacePath> = withContext(dispatcher) {
        accessMutex.withLock {
            val target = resolve(directory)
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return@withLock emptyList()
            require(Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) { "工作区路径不是目录" }
            Files.list(target).use { stream ->
                stream.map { child ->
                    WorkspacePath(root.relativize(child.toAbsolutePath().normalize()).toString().replace('\\', '/'))
                }.sorted(compareBy(WorkspacePath::value)).collect(Collectors.toList())
            }
        }
    }

    /**
     * 判断逻辑路径是否存在，且不跟随符号链接。
     *
     * @param path 工作区内的逻辑相对路径。
     * @return 路径存在时为 true。
     */
    override suspend fun exists(path: WorkspacePath): Boolean = withContext(dispatcher) {
        accessMutex.withLock { existsWithoutLock(path) }
    }

    /**
     * 解析、认证并解密一个文件信封。
     *
     * @param request 读取路径、信封身份、密钥版本、用途和会话主密钥。
     * @return 文件不存在时为空，否则返回认证后的明文。
     */
    override suspend fun readEncrypted(request: EncryptedFileReadRequest): ByteArray? = withContext(dispatcher) {
        accessMutex.withLock {
            // 读取路径也必须主动初始化密码学运行时，避免首次操作依赖其他写入入口的副作用。
            cryptoProvider.initialize()
            val encrypted = readWithoutLock(request.path) ?: return@withLock null
            val envelope = EncryptionEnvelopeCodec.decode(encrypted)
            envelopeCryptoService.decrypt(
                envelope = envelope,
                context = EnvelopeDecryptionContext(
                    expectedIdentity = request.expectedIdentity,
                    expectedKeyId = request.expectedKeyId,
                    expectedWrappingKeyPurpose = request.expectedWrappingKeyPurpose,
                ),
                workspaceMasterKey = request.workspaceMasterKey,
            )
        }
    }

    /**
     * 创建版本化加密信封并以原子方式发布。
     *
     * @param request 待加密明文、信封身份和会话主密钥。
     * @return 已发布密文的路径、摘要和尺寸。
     */
    override suspend fun writeEncryptedAtomically(
        request: EncryptedFileWriteRequest,
    ): EncryptedFileWriteResult = withContext(dispatcher) {
        accessMutex.withLock {
            cryptoProvider.initialize()
            val envelope = envelopeCryptoService.encrypt(
                request = EnvelopeEncryptionRequest(
                    identity = request.identity,
                    keyId = request.keyId,
                    wrappingKeyPurpose = request.wrappingKeyPurpose,
                    plaintext = request.plaintext,
                ),
                workspaceMasterKey = request.workspaceMasterKey,
            )
            val encoded = EncryptionEnvelopeCodec.encode(envelope)
            try {
                atomicFiles.writeAtomically(resolve(request.path), encoded)
                EncryptedFileWriteResult(
                    path = request.path,
                    encryptedSha256 = encoded.sha256Hex(),
                    encryptedSize = encoded.size.toLong(),
                )
            } finally {
                encoded.fill(0)
            }
        }
    }

    /**
     * 处理已持久化的 prepared 事务，并把恢复动作汇总为脱敏统计。
     *
     * 未提供解锁上下文时，如果 staging 中存在事务会直接失败；这是为了阻止调用方在没有
     * AEAD/schema 验证能力时误删或误发布权威密文。
     *
     * @return 完成、回滚和隔离事务的数量。
     */
    override suspend fun recoverPendingTransactions(): FileRecoveryReport = withContext(dispatcher) {
        accessMutex.withLock {
            val context = recoveryContext
            if (context == null) {
                check(!hasPendingTransactions()) { "恢复 prepared 事务需要已解锁工作区上下文" }
                return@withLock FileRecoveryReport(completed = 0, rolledBack = 0, quarantined = 0)
            }
            val verifier = JvmWorkspaceContentVerifier(
                workspaceId = context.workspaceId,
                activeKeyId = context.activeKeyId,
                workspaceMasterKey = context.workspaceMasterKey,
                envelopeCryptoService = envelopeCryptoService,
                workspaceManifestService = context.manifestService,
            )
            val coordinator = TransactionCoordinator(
                storage = JvmTransactionStorage(root, verifier, atomicFiles),
                derivedStateUpdater = context.derivedStateUpdater,
            )
            val report = coordinator.recoverOnStartup()
            FileRecoveryReport(
                completed = report.entries.count { entry ->
                    entry.action == TransactionRecoveryAction.CONFIRMED_AND_CLEANED ||
                        entry.action == TransactionRecoveryAction.COMPLETED
                },
                rolledBack = report.entries.count { entry -> entry.action == TransactionRecoveryAction.ROLLED_BACK },
                quarantined = report.entries.count { entry -> entry.action == TransactionRecoveryAction.QUARANTINED },
            )
        }
    }

    /**
     * 获取跨进程单写入者文件锁。
     *
     * @return 释放时关闭锁文件通道的租约。
     */
    override suspend fun acquireWriteLease(): WorkspaceWriteLease = withContext(dispatcher) {
        accessMutex.withLock {
            val lockPath = root.resolve(WRITE_LOCK_FILE_NAME)
            require(!Files.isSymbolicLink(lockPath)) { "工作区写入租约文件不能是符号链接" }
            val channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            try {
                val lock = channel.tryLock() ?: throw IllegalStateException("工作区已被其他写入者锁定")
                JvmWorkspaceWriteLease(channel, lock)
            } catch (error: OverlappingFileLockException) {
                channel.close()
                throw IllegalStateException("当前进程已有工作区写入租约", error)
            } catch (error: Throwable) {
                channel.close()
                throw error
            }
        }
    }

    /**
     * 创建复用当前工作区路径、原子文件原语和内容验证器的事务存储端口。
     *
     * 事务协调器只应通过这个入口取得 JVM 文件存储，确保普通文件操作与 prepared
     * 恢复使用同一组根目录约束和目录持久化策略。
     *
     * @param contentVerifier 负责当前解锁会话的信封、schema 和 manifest 语义验证器。
     * @param atomicFilesOverride 可选的故障注入原子文件端口；为空时复用本能力实例的端口。
     * @return 可注入核心 TransactionCoordinator 的事务存储端口。
     */
    fun createTransactionStorage(
        contentVerifier: JvmPreparedContentVerifier,
        atomicFilesOverride: JvmAtomicFilePort? = null,
    ): TransactionStoragePort =
        JvmTransactionStorage(
            workspaceRoot = root,
            contentVerifier = contentVerifier,
            atomicFiles = atomicFilesOverride ?: atomicFiles,
        )

    /**
     * 在工作区根目录下解析并验证逻辑路径。
     *
     * @param path 业务逻辑相对路径。
     * @return 不跟随既有符号链接的绝对路径。
     */
    private fun resolve(path: WorkspacePath): Path {
        val segments = path.value.split('/', '\\')
        require(segments.none { segment -> segment.isBlank() || segment == "." || segment == ".." }) {
            "工作区路径包含非法段"
        }
        val target = segments.fold(root) { current, segment -> current.resolve(segment) }.normalize()
        require(target.startsWith(root)) { "工作区路径越界" }
        var current = root
        segments.forEach { segment ->
            current = current.resolve(segment)
            require(!Files.isSymbolicLink(current)) { "工作区路径不能经过符号链接" }
        }
        return target
    }

    /**
     * 在已持有进程内互斥锁时读取普通文件。
     *
     * @param path 待读取逻辑路径。
     * @return 文件不存在时为空。
     */
    private fun readWithoutLock(path: WorkspacePath): ByteArray? {
        val target = resolve(path)
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return null
        require(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) { "工作区路径不是普通文件" }
        return Files.readAllBytes(target)
    }

    /**
     * 在已持有进程内互斥锁时判断路径是否存在。
     *
     * @param path 待检查逻辑路径。
     * @return 路径存在时为 true。
     */
    private fun existsWithoutLock(path: WorkspacePath): Boolean =
        Files.exists(resolve(path), LinkOption.NOFOLLOW_LINKS)

    /**
     * 判断是否存在待恢复事务目录。
     *
     * @return staging 下存在 prepared 记录目录时为 true。
     */
    private fun hasPendingTransactions(): Boolean {
        val staging = root.resolve(JvmWorkspacePaths.STAGING_DIRECTORY_NAME)
        require(!Files.isSymbolicLink(staging)) { "工作区 staging 目录不能是符号链接" }
        if (!Files.isDirectory(staging, LinkOption.NOFOLLOW_LINKS)) return false
        return Files.list(staging).use { stream ->
            stream.anyMatch { directory ->
                Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) &&
                    Files.isRegularFile(directory.resolve(JvmWorkspacePaths.PREPARED_FILE_NAME), LinkOption.NOFOLLOW_LINKS)
            }
        }
    }

    /**
     * 使用当前 JVM 密码实现计算密文摘要。
     *
     * @return 小写十六进制 SHA-256。
     */
    private suspend fun ByteArray.sha256Hex(): String {
        cryptoProvider.initialize()
        val digest = cryptoProvider.sha256(this)
        return try {
            digest.joinToString(separator = "") { byte -> byte.toUByte().toString(16).padStart(2, '0') }
        } finally {
            digest.fill(0)
        }
    }

    private companion object {
        /** 跨进程写入租约文件名；其内容没有业务数据。 */
        const val WRITE_LOCK_FILE_NAME: String = ".tagloom-write.lock"
    }
}

/**
 * 描述执行 prepared 事务恢复所需的已解锁上下文。
 *
 * @param workspaceId 当前工作区公开 ID。
 * @param activeKeyId 当前 manifest 使用的主密钥版本。
 * @param workspaceMasterKey 当前解锁会话主密钥；本类不取得也不销毁其所有权。
 * @param manifestService 负责 manifest AEAD 和确认点语义校验的领域服务。
 * @param derivedStateUpdater 恢复确认后更新派生状态的端口。
 */
data class JvmWorkspaceRecoveryContext(
    /** 当前工作区公开 ID。 */
    val workspaceId: com.oruke.tagloom.core.model.WorkspaceId,
    /** 当前 manifest 使用的主密钥版本。 */
    val activeKeyId: com.oruke.tagloom.core.model.KeyId,
    /** 当前解锁会话主密钥。 */
    val workspaceMasterKey: SecretKeyMaterial,
    /** manifest 加密和确认点验证服务。 */
    val manifestService: WorkspaceManifestService,
    /** 恢复成功后更新索引等派生状态的端口。 */
    val derivedStateUpdater: DerivedStateUpdater = NoOpDerivedStateUpdater,
)

/** 恢复阶段没有配置派生投影时使用的显式空实现。 */
private object NoOpDerivedStateUpdater : DerivedStateUpdater {
    /**
     * 不执行派生状态更新；调用方仍可根据恢复报告主动重建索引。
     *
     * @param transactionId 已确认事务 ID。
     */
    override suspend fun update(transactionId: com.oruke.tagloom.core.model.TransactionId) = Unit
}

/**
 * 持有 JVM 文件锁和通道的工作区写入租约。
 *
 * @param channel 锁文件通道。
 * @param lock 已取得的跨进程文件锁。
 */
private class JvmWorkspaceWriteLease(
    private val channel: FileChannel,
    private val lock: FileLock,
) : WorkspaceWriteLease {
    /** 释放操作幂等标记。 */
    private val released: AtomicBoolean = AtomicBoolean(false)

    /**
     * 释放文件锁和通道；重复调用安全。
     */
    override suspend fun release() {
        if (released.compareAndSet(false, true)) {
            runCatching(lock::release)
            runCatching(channel::close)
        }
    }
}
