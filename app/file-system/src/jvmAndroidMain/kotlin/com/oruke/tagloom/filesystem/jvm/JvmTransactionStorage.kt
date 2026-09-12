package com.oruke.tagloom.filesystem.jvm

import com.oruke.tagloom.core.model.TransactionId
import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.transaction.EncryptedTransactionPlan
import com.oruke.tagloom.core.transaction.PreparedArtifactRecord
import com.oruke.tagloom.core.transaction.PreparedTransactionRecord
import com.oruke.tagloom.core.transaction.PreparedValidationResult
import com.oruke.tagloom.core.transaction.PendingPreparedTransactions
import com.oruke.tagloom.core.transaction.TransactionStoragePort
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Comparator
import java.util.UUID
import java.util.stream.Collectors
import kotlin.coroutines.cancellation.CancellationException

/**
 * 在 JVM 文件系统上实现加密 staging、原子发布、旧版本备份和崩溃恢复。
 *
 * @param workspaceRoot 用户选择的工作区根目录。
 */
class JvmTransactionStorage(
    workspaceRoot: Path,
    /** 负责需要会话密钥的 AEAD、schema 和 manifest 语义验证。 */
    private val contentVerifier: JvmPreparedContentVerifier,
    /** 提供文件 force 与同目录原子替换。 */
    private val atomicFiles: JvmAtomicFilePort = JvmAtomicFileOperations(),
) : TransactionStoragePort {
    /** 集中处理所有受控工作区路径。 */
    private val paths: JvmWorkspacePaths = JvmWorkspacePaths(workspaceRoot)

    /**
     * 写入全部新密文、旧版本备份和待确认 manifest。
     *
     * @param plan 已校验和加密的提交计划。
     * @return 引用已持久化 staging 内容的恢复记录。
     */
    override suspend fun stage(plan: EncryptedTransactionPlan): PreparedTransactionRecord {
        ensureWorkspaceDirectories()
        val transactionDirectory = paths.transactionDirectory(plan.transactionId)
        check(!Files.exists(transactionDirectory, LinkOption.NOFOLLOW_LINKS)) { "事务 ID 已存在" }
        Files.createDirectory(transactionDirectory)
        val filesDirectory = transactionDirectory.resolve(JvmWorkspacePaths.FILES_DIRECTORY_NAME)
        val previousDirectory = transactionDirectory.resolve(JvmWorkspacePaths.PREVIOUS_DIRECTORY_NAME)
        Files.createDirectory(filesDirectory)
        Files.createDirectory(previousDirectory)
        require(Files.isDirectory(filesDirectory, LinkOption.NOFOLLOW_LINKS)) { "staging files 目录无效" }
        require(Files.isDirectory(previousDirectory, LinkOption.NOFOLLOW_LINKS)) { "staging previous 目录无效" }
        atomicFiles.forceDirectory(paths.stagingRoot)
        atomicFiles.forceDirectory(transactionDirectory)

        val records = plan.artifacts.mapIndexed { index, artifact ->
            check(sha256(artifact.encryptedBytes) == artifact.expectedSha256) { "artifact 摘要不匹配" }
            val stagedFileName = "$index-${UUID.randomUUID()}.enc"
            val stagedPath = paths.stagedArtifact(plan.transactionId, stagedFileName)
            atomicFiles.writeAtomically(stagedPath, artifact.encryptedBytes)
            val target = paths.target(artifact.targetPath)
            val previousExists = Files.exists(target, LinkOption.NOFOLLOW_LINKS)
            val previousSha256 = if (previousExists) {
                requireRegularManagedFile(target)
                val hash = sha256(target)
                val provisionalRecord = PreparedArtifactRecord(
                    targetPath = artifact.targetPath,
                    stagedFileName = stagedFileName,
                    expectedSha256 = artifact.expectedSha256,
                    previousVersionExists = true,
                    previousSha256 = hash,
                )
                atomicFiles.copyAtomically(
                    source = target,
                    target = paths.previousArtifact(plan.transactionId, provisionalRecord),
                )
                hash
            } else {
                null
            }
            PreparedArtifactRecord(
                targetPath = artifact.targetPath,
                stagedFileName = stagedFileName,
                expectedSha256 = artifact.expectedSha256,
                previousVersionExists = previousExists,
                previousSha256 = previousSha256,
            )
        }

        val stagedManifestFileName = MANIFEST_NEXT_FILE_NAME
        check(sha256(plan.encryptedManifest) == plan.manifestSha256) { "manifest 摘要不匹配" }
        atomicFiles.writeAtomically(
            paths.stagedManifest(plan.transactionId, stagedManifestFileName),
            plan.encryptedManifest,
        )
        atomicFiles.forceDirectory(transactionDirectory)
        return PreparedTransactionRecord(
            transactionId = plan.transactionId,
            baseManifestRevision = plan.baseManifestRevision,
            targetManifestRevision = plan.targetManifestRevision,
            artifacts = records,
            stagedManifestFileName = stagedManifestFileName,
            manifestSha256 = plan.manifestSha256,
        )
    }

    /**
     * 原子写入 prepared JSON 并持久化事务目录元数据。
     *
     * @param record 已完成 staging 的事务记录。
     */
    override suspend fun persistPrepared(record: PreparedTransactionRecord) {
        val plaintext = CanonicalJson
            .encodeToString(PreparedTransactionRecord.serializer(), record)
            .encodeToByteArray()
        val encrypted = try {
            contentVerifier.encryptPreparedRecord(record.transactionId, plaintext)
        } finally {
            plaintext.fill(0)
        }
        try {
            atomicFiles.writeAtomically(paths.preparedRecord(record.transactionId), encrypted)
        } finally {
            encrypted.fill(0)
        }
        atomicFiles.forceDirectory(paths.transactionDirectory(record.transactionId))
    }

    /**
     * 验证 staging 摘要后逐个原子发布业务文件。
     *
     * @param record prepared 事务记录。
     */
    override suspend fun publishArtifacts(record: PreparedTransactionRecord) {
        record.artifacts.forEach { artifact ->
            val staged = paths.stagedArtifact(record.transactionId, artifact.stagedFileName)
            check(Files.isRegularFile(staged, LinkOption.NOFOLLOW_LINKS)) { "staging artifact 不存在" }
            check(sha256(staged) == artifact.expectedSha256) { "staging artifact 摘要不匹配" }
            atomicFiles.copyAtomically(staged, paths.target(artifact.targetPath))
        }
    }

    /**
     * 比较当前 manifest revision，验证新 manifest 后原子形成确认点。
     *
     * @param record prepared 事务记录。
     */
    override suspend fun confirmManifest(record: PreparedTransactionRecord) {
        if (Files.exists(paths.manifest, LinkOption.NOFOLLOW_LINKS)) {
            requireRegularManagedFile(paths.manifest)
            val currentState = contentVerifier.inspectManifest(Files.readAllBytes(paths.manifest))
            if (record.transactionId in currentState.committedTransactionIds) {
                return
            }
            check(currentState.revision == record.baseManifestRevision) { "manifest revision 已变化" }
        } else {
            check(record.baseManifestRevision == 0L) { "非初始事务缺少 manifest" }
        }

        val stagedManifest = paths.stagedManifest(record.transactionId, record.stagedManifestFileName)
        val bytes = Files.readAllBytes(stagedManifest)
        check(sha256(bytes) == record.manifestSha256) { "staging manifest 摘要不匹配" }
        check(contentVerifier.verifyNextManifest(record, bytes)) { "staging manifest 语义验证失败" }
        atomicFiles.copyAtomically(stagedManifest, paths.manifest)
    }

    /**
     * 写入不含敏感内容的 committed 诊断标记。
     *
     * @param transactionId 已由 manifest 确认的事务。
     */
    override suspend fun markCommitted(transactionId: TransactionId) {
        atomicFiles.writeAtomically(
            paths.committedMarker(transactionId),
            COMMITTED_MARKER_CONTENT,
        )
    }

    /**
     * 再次验证已发布文件和 manifest 后删除 staging 与旧版本备份。
     *
     * @param transactionId 已完成事务。
     */
    override suspend fun cleanupAfterCommit(transactionId: TransactionId) {
        val preparedPath = paths.preparedRecord(transactionId)
        if (!Files.exists(preparedPath, LinkOption.NOFOLLOW_LINKS)) {
            deleteTransactionDirectoryIfPresent(transactionId)
            return
        }
        val record = decodePrepared(preparedPath)
        check(isManifestCommitted(transactionId)) { "manifest 尚未确认事务" }
        record.artifacts.forEach { artifact ->
            check(sha256(paths.target(artifact.targetPath)) == artifact.expectedSha256) {
                "已发布 artifact 摘要不匹配"
            }
        }
        check(sha256(paths.manifest) == record.manifestSha256) { "已发布 manifest 摘要不匹配" }
        deleteTransactionDirectoryIfPresent(transactionId)
    }

    /**
     * 重新验证 manifest 与所有业务目标均为事务前状态，再删除回滚恢复材料。
     *
     * @param record 已执行成功回滚的 prepared 事务记录。
     */
    override suspend fun cleanupAfterRollback(record: PreparedTransactionRecord) {
        check(!isManifestCommitted(record.transactionId)) { "已确认事务不得按回滚状态清理" }
        if (Files.exists(paths.manifest, LinkOption.NOFOLLOW_LINKS)) {
            requireRegularManagedFile(paths.manifest)
            val currentState = contentVerifier.inspectManifest(Files.readAllBytes(paths.manifest))
            check(currentState.revision == record.baseManifestRevision) { "回滚后的 manifest revision 不匹配" }
        } else {
            check(record.baseManifestRevision == 0L) { "回滚后的 manifest 缺失" }
        }
        record.artifacts.forEach { artifact ->
            val target = paths.target(artifact.targetPath)
            if (artifact.previousVersionExists) {
                check(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) { "回滚目标文件缺失" }
                check(sha256(target) == artifact.previousSha256) { "回滚目标摘要不匹配" }
            } else {
                check(!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { "回滚后仍存在新增目标" }
            }
        }
        deleteTransactionDirectoryIfPresent(record.transactionId)
    }

    /**
     * 删除没有 prepared 记录的孤立 staging 目录。
     */
    override suspend fun cleanupOrphanStaging() {
        if (!Files.isDirectory(paths.stagingRoot, LinkOption.NOFOLLOW_LINKS)) {
            return
        }
        listDirectories(paths.stagingRoot).forEach { directory ->
            if (!Files.exists(directory.resolve(JvmWorkspacePaths.PREPARED_FILE_NAME), LinkOption.NOFOLLOW_LINKS)) {
                deleteTreeWithinStaging(directory)
            }
        }
    }

    /**
     * 读取并按目标 revision 排序全部 prepared 记录；无法认证或解析的记录先移入隔离区。
     *
     * @return 尚未清理的恢复候选和已隔离记录 ID。
     */
    override suspend fun loadPendingPrepared(): PendingPreparedTransactions {
        if (!Files.isDirectory(paths.stagingRoot, LinkOption.NOFOLLOW_LINKS)) {
            return PendingPreparedTransactions(records = emptyList())
        }
        val quarantinedTransactionIds = mutableListOf<TransactionId>()
        val records = listDirectories(paths.stagingRoot)
            .mapNotNull { directory ->
                val prepared = directory.resolve(JvmWorkspacePaths.PREPARED_FILE_NAME)
                if (!Files.exists(prepared, LinkOption.NOFOLLOW_LINKS)) {
                    return@mapNotNull null
                }
                try {
                    decodePrepared(prepared)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    quarantinedTransactionIds += quarantineMalformedDirectory(directory)
                    null
                }
            }
            .onEach { record ->
                check(paths.transactionDirectory(record.transactionId).fileName.toString() == record.transactionId.value) {
                    "prepared 事务 ID 与目录不一致"
                }
            }
            .sortedBy(PreparedTransactionRecord::targetManifestRevision)
        return PendingPreparedTransactions(
            records = records,
            quarantinedTransactionIds = quarantinedTransactionIds,
        )
    }

    /**
     * 解密 manifest 最小状态并检查事务确认历史。
     *
     * @param transactionId 待查询事务。
     * @return manifest 已确认该事务时为 `true`。
     */
    override suspend fun isManifestCommitted(transactionId: TransactionId): Boolean {
        if (!Files.isRegularFile(paths.manifest, LinkOption.NOFOLLOW_LINKS)) {
            return false
        }
        return transactionId in contentVerifier
            .inspectManifest(Files.readAllBytes(paths.manifest))
            .committedTransactionIds
    }

    /**
     * 验证全部 staging/备份摘要以及 AEAD、schema 与 manifest 语义。
     *
     * @param record prepared 事务记录。
     * @return 全部验证通过为 VALID，否则为 INVALID。
     */
    override suspend fun validatePrepared(record: PreparedTransactionRecord): PreparedValidationResult = try {
        val artifactsValid = record.artifacts.all { artifact ->
            validateStagedArtifact(record.transactionId, artifact)
        }
        val manifestPath = paths.stagedManifest(record.transactionId, record.stagedManifestFileName)
        val manifestBytes = Files.readAllBytes(manifestPath)
        val manifestValid =
            sha256(manifestBytes) == record.manifestSha256 &&
                contentVerifier.verifyNextManifest(record, manifestBytes)
        if (artifactsValid && manifestValid) PreparedValidationResult.VALID else PreparedValidationResult.INVALID
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        PreparedValidationResult.INVALID
    }

    /**
     * 使用旧版本备份恢复全部业务目标；原先不存在的目标只在仍等于本事务新摘要时删除。
     *
     * @param record 无法完成的 prepared 事务。
     * @return 所有目标恢复并验证成功时为 `true`。
     */
    override suspend fun rollback(record: PreparedTransactionRecord): Boolean = try {
        record.artifacts.forEach { artifact ->
            val target = paths.target(artifact.targetPath)
            if (artifact.previousVersionExists) {
                val previous = paths.previousArtifact(record.transactionId, artifact)
                check(sha256(previous) == artifact.previousSha256)
                atomicFiles.copyAtomically(previous, target)
            } else if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                check(sha256(target) == artifact.expectedSha256)
                atomicFiles.deleteDurably(target)
            }
        }
        record.artifacts.all { artifact ->
            val target = paths.target(artifact.targetPath)
            if (artifact.previousVersionExists) {
                Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && sha256(target) == artifact.previousSha256
            } else {
                !Files.exists(target, LinkOption.NOFOLLOW_LINKS)
            }
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        false
    }

    /**
     * 原子移动无法恢复的事务目录到密文隔离区。
     *
     * @param record 无法自动完成或回滚的事务。
     */
    override suspend fun quarantine(record: PreparedTransactionRecord) {
        val source = paths.transactionDirectory(record.transactionId)
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            return
        }
        val target = paths.quarantineDirectory(record.transactionId, UUID.randomUUID().toString())
        atomicFiles.moveDirectoryAtomically(source, target)
    }

    /**
     * 将无法解密或解析 prepared 记录的目录移入隔离区，并返回不泄露路径的诊断 ID。
     *
     * 目录名通常就是合法事务 ID；遇到外部篡改的非法目录名时使用稳定摘要构造诊断 ID，
     * 这样恢复报告仍能进入只读模式，同时不把原始路径或控制内容暴露给 UI。
     *
     * @param directory 损坏 prepared 所在的 staging 目录。
     * @return 可安全放入恢复报告的诊断事务 ID。
     */
    private fun quarantineMalformedDirectory(directory: Path): TransactionId {
        val directoryName = requireNotNull(directory.fileName?.toString()) { "损坏 staging 目录缺少名称" }
        val transactionId = runCatching { TransactionId(directoryName) }
            .getOrElse { TransactionId("quarantine-${sha256(directoryName.encodeToByteArray())}") }
        val target = paths.quarantineDirectory(transactionId, UUID.randomUUID().toString())
        atomicFiles.moveDirectoryAtomically(directory, target)
        return transactionId
    }

    /**
     * 验证一个 staging artifact 的摘要、旧版本备份和加密内容。
     *
     * @param transactionId 事务标识。
     * @param artifact prepared artifact 记录。
     * @return 全部验证通过为 `true`。
     */
    private suspend fun validateStagedArtifact(
        transactionId: TransactionId,
        artifact: PreparedArtifactRecord,
    ): Boolean {
        val staged = paths.stagedArtifact(transactionId, artifact.stagedFileName)
        val bytes = Files.readAllBytes(staged)
        if (sha256(bytes) != artifact.expectedSha256 || !contentVerifier.verifyArtifact(artifact, bytes)) {
            return false
        }
        if (artifact.previousVersionExists) {
            val previous = paths.previousArtifact(transactionId, artifact)
            if (sha256(previous) != artifact.previousSha256) {
                return false
            }
        }
        return true
    }

    /**
     * 创建工作区和 staging 根目录并持久化目录项。
     */
    private fun ensureWorkspaceDirectories() {
        require(!Files.isSymbolicLink(paths.root)) { "工作区根目录不能是符号链接" }
        Files.createDirectories(paths.root)
        require(Files.isDirectory(paths.root, LinkOption.NOFOLLOW_LINKS)) { "工作区根目录不是目录" }
        require(!Files.isSymbolicLink(paths.stagingRoot)) { "工作区 staging 目录不能是符号链接" }
        if (!Files.exists(paths.stagingRoot, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(paths.stagingRoot)
            atomicFiles.forceDirectory(paths.root)
        }
        require(Files.isDirectory(paths.stagingRoot, LinkOption.NOFOLLOW_LINKS)) { "工作区 staging 路径不是目录" }
    }

    /**
     * 读取 prepared JSON，任何解析异常均不包含原始输入。
     *
     * @param path prepared 文件路径。
     * @return 结构化恢复记录。
     */
    private suspend fun decodePrepared(path: Path): PreparedTransactionRecord = try {
        requireRegularManagedFile(path)
        val transactionName = requireNotNull(path.parent?.fileName?.toString()) { "prepared 事务目录缺失" }
        val transactionId = TransactionId(transactionName)
        val encrypted = Files.readAllBytes(path)
        val plaintext = try {
            contentVerifier.decryptPreparedRecord(transactionId, encrypted)
        } finally {
            encrypted.fill(0)
        }
        try {
            CanonicalJson.decodeFromString(
                PreparedTransactionRecord.serializer(),
                plaintext.decodeToString(),
            ).also { record ->
                check(record.transactionId == transactionId) { "prepared 事务 ID 与目录不一致" }
            }
        } finally {
            plaintext.fill(0)
        }
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Throwable) {
        throw IllegalStateException("prepared 事务记录损坏", exception)
    }

    /**
     * 要求现有工作区目标是普通文件且不是符号链接。
     *
     * @param path 待读取或备份路径。
     */
    private fun requireRegularManagedFile(path: Path) {
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
            "工作区目标不是受管普通文件"
        }
    }

    /**
     * 计算内存字节的 SHA-256 小写十六进制摘要。
     *
     * @param bytes 待摘要字节。
     * @return 64 位小写十六进制文本。
     */
    private fun sha256(bytes: ByteArray): String = digestToHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /**
     * 流式计算文件 SHA-256，避免为摘要额外复制大文件。
     *
     * @param path 待摘要文件。
     * @return 64 位小写十六进制文本。
     */
    private fun sha256(path: Path): String {
        requireRegularManagedFile(path)
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digestToHex(digest.digest())
    }

    /**
     * 将摘要字节转换为稳定小写十六进制文本。
     *
     * @param digest SHA-256 字节。
     * @return 十六进制摘要。
     */
    private fun digestToHex(digest: ByteArray): String = digest.joinToString(separator = "") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
    }

    /**
     * 列出指定受管目录的直接子目录，并拒绝符号链接。
     *
     * @param parent 受管父目录。
     * @return 直接子目录列表。
     */
    private fun listDirectories(parent: Path): List<Path> = Files.list(parent).use { stream ->
        stream
            .filter { child -> Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(child) }
            .collect(Collectors.toList())
    }

    /**
     * 删除指定事务目录；目标不存在时保持幂等。
     *
     * @param transactionId 事务标识。
     */
    private fun deleteTransactionDirectoryIfPresent(transactionId: TransactionId) {
        val directory = paths.transactionDirectory(transactionId)
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            deleteTreeWithinStaging(directory)
        }
    }

    /**
     * 递归删除已经验证位于 staging 根下的目录，并持久化 staging 目录项。
     *
     * @param directory 待删除事务目录。
     */
    private fun deleteTreeWithinStaging(directory: Path) {
        val normalized = directory.toAbsolutePath().normalize()
        check(normalized.startsWith(paths.stagingRoot) && normalized != paths.stagingRoot) { "拒绝越界删除" }
        Files.walk(normalized).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
        atomicFiles.forceDirectory(paths.stagingRoot)
    }

    companion object {
        /** staging 中待确认 manifest 固定文件名。 */
        private const val MANIFEST_NEXT_FILE_NAME: String = "manifest-next.enc"

        /** 文件摘要流式缓冲区大小。 */
        private const val HASH_BUFFER_BYTES: Int = 64 * 1_024

        /** committed 标记固定非敏感内容。 */
        private val COMMITTED_MARKER_CONTENT: ByteArray = byteArrayOf(1)
    }
}
