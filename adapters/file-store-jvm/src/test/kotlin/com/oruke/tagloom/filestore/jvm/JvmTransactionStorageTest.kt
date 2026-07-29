package com.oruke.tagloom.filestore.jvm

import com.oruke.tagloom.core.model.TransactionId
import com.oruke.tagloom.core.transaction.DerivedStateUpdater
import com.oruke.tagloom.core.transaction.EncryptedTransactionArtifact
import com.oruke.tagloom.core.transaction.EncryptedTransactionPlan
import com.oruke.tagloom.core.transaction.PreparedArtifactRecord
import com.oruke.tagloom.core.transaction.PreparedTransactionRecord
import com.oruke.tagloom.core.transaction.TransactionCoordinator
import com.oruke.tagloom.core.transaction.TransactionRecoveryAction
import com.oruke.tagloom.core.transaction.WorkspaceRelativePath
import java.nio.file.Files
import java.nio.file.Path
import java.io.IOException
import java.security.MessageDigest
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

/**
 * 在真实临时目录上验证 JVM staging、原子替换和重启恢复。
 */
class JvmTransactionStorageTest {
    /**
     * 验证正常提交会发布全部密文、确认 manifest 并清理 staging。
     */
    @Test
    fun commitPublishesEncryptedFilesAndCleansStaging() = withWorkspace { root ->
        val fixture = WorkspaceFixture(root)
        val storage = fixture.storage()

        val result = TransactionCoordinator(storage, NoOpDerivedStateUpdater).commit(fixture.plan())

        assertTrue(result.derivedStateUpdated)
        assertContentEquals(NEW_NOTE, Files.readAllBytes(root.resolve("notes/note-a.note.enc")))
        assertContentEquals(NEW_TAG, Files.readAllBytes(root.resolve("tags/tag-a.tag.enc")))
        assertContentEquals(fixture.nextManifest, Files.readAllBytes(root.resolve("manifest.enc")))
        assertFalse(Files.exists(root.resolve("staging/transaction-a")))
    }

    /**
     * 验证发布部分文件后重启会从 prepared staging 完成整个新状态。
     */
    @Test
    fun restartCompletesValidPreparedTransaction() = withWorkspace { root ->
        val fixture = WorkspaceFixture(root)
        val firstProcess = fixture.storage()
        val prepared = firstProcess.stage(fixture.plan())
        firstProcess.persistPrepared(prepared)
        firstProcess.publishArtifacts(prepared)

        val report = TransactionCoordinator(fixture.storage(), NoOpDerivedStateUpdater).recoverOnStartup()

        assertEquals(TransactionRecoveryAction.COMPLETED, report.entries.single().action)
        assertFalse(report.requiresReadOnlyMode)
        assertContentEquals(NEW_NOTE, Files.readAllBytes(root.resolve("notes/note-a.note.enc")))
        assertContentEquals(NEW_TAG, Files.readAllBytes(root.resolve("tags/tag-a.tag.enc")))
        assertContentEquals(fixture.nextManifest, Files.readAllBytes(root.resolve("manifest.enc")))
    }

    /**
     * 验证一个 staging 文件损坏时会从加密备份恢复全部旧状态。
     */
    @Test
    fun restartRollsBackWhenStagingIsCorrupted() = withWorkspace { root ->
        val fixture = WorkspaceFixture(root)
        val firstProcess = fixture.storage()
        val prepared = firstProcess.stage(fixture.plan())
        firstProcess.persistPrepared(prepared)
        firstProcess.publishArtifacts(prepared)
        val corrupted = prepared.artifacts.first()
        Files.write(
            root.resolve("staging/transaction-a/files/${corrupted.stagedFileName}"),
            byteArrayOf(0),
        )

        val report = TransactionCoordinator(fixture.storage(), NoOpDerivedStateUpdater).recoverOnStartup()

        assertEquals(TransactionRecoveryAction.ROLLED_BACK, report.entries.single().action)
        assertContentEquals(OLD_NOTE, Files.readAllBytes(root.resolve("notes/note-a.note.enc")))
        assertFalse(Files.exists(root.resolve("tags/tag-a.tag.enc")))
        assertContentEquals(fixture.initialManifest, Files.readAllBytes(root.resolve("manifest.enc")))
    }

    /**
     * 验证 staging 流程出现磁盘满时不触碰旧确认文件，重启只清理孤立密文。
     */
    @Test
    fun diskFullDuringStagingPreservesConfirmedState() = withWorkspace { root ->
        val fixture = WorkspaceFixture(root)
        val failingFiles = DiskFullAtomicFilePort(
            delegate = JvmAtomicFileOperations(),
            successfulWritesBeforeFailure = 1,
        )

        assertFailsWith<IOException> {
            TransactionCoordinator(
                fixture.storage(failingFiles),
                NoOpDerivedStateUpdater,
            ).commit(fixture.plan())
        }

        val report = TransactionCoordinator(fixture.storage(), NoOpDerivedStateUpdater).recoverOnStartup()
        assertTrue(report.entries.isEmpty())
        assertContentEquals(OLD_NOTE, Files.readAllBytes(root.resolve("notes/note-a.note.enc")))
        assertFalse(Files.exists(root.resolve("tags/tag-a.tag.enc")))
        assertContentEquals(fixture.initialManifest, Files.readAllBytes(root.resolve("manifest.enc")))
        Files.list(root.resolve("staging")).use { entries -> assertFalse(entries.findAny().isPresent) }
    }

    /**
     * 在独立临时工作区执行挂起测试并始终安全清理。
     *
     * @param block 使用临时根目录的测试主体。
     */
    private fun withWorkspace(block: suspend (Path) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("tagloom-file-store-test-").toAbsolutePath().normalize()
        try {
            block(root)
        } finally {
            deleteTestTree(root)
        }
    }

    /**
     * 递归删除本测试创建且位于系统临时目录中的工作区。
     *
     * @param root 测试创建的绝对临时目录。
     */
    private fun deleteTestTree(root: Path) {
        val normalized = root.toAbsolutePath().normalize()
        check(normalized.fileName.toString().startsWith("tagloom-file-store-test-")) { "拒绝删除非测试目录" }
        if (!Files.exists(normalized)) {
            return
        }
        Files.walk(normalized).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    companion object {
        /** 原有 note 密文。 */
        private val OLD_NOTE: ByteArray = "ENC:old-note".encodeToByteArray()

        /** 新 note 密文。 */
        private val NEW_NOTE: ByteArray = "ENC:new-note".encodeToByteArray()

        /** 新 tag 密文。 */
        private val NEW_TAG: ByteArray = "ENC:new-tag".encodeToByteArray()
    }
}

/**
 * 准备真实文件存储测试所需的初始文件、manifest 与验证器。
 *
 * @param root 独立临时工作区根目录。
 */
private class WorkspaceFixture(
    /** 独立临时工作区根目录。 */
    private val root: Path,
) {
    /** 初始 revision 0 manifest。 */
    val initialManifest: ByteArray = manifestBytes(0, emptySet())

    /** 包含 transaction-a 的 revision 1 manifest。 */
    val nextManifest: ByteArray = manifestBytes(1, setOf(TransactionId("transaction-a")))

    init {
        Files.createDirectories(root.resolve("notes"))
        Files.write(root.resolve("notes/note-a.note.enc"), "ENC:old-note".encodeToByteArray())
        Files.write(root.resolve("manifest.enc"), initialManifest)
    }

    /**
     * 创建指向同一工作区的新文件存储实例，模拟进程重启。
     *
     * @return 使用测试 manifest 协议验证器的存储。
     */
    fun storage(atomicFiles: JvmAtomicFilePort = JvmAtomicFileOperations()): JvmTransactionStorage = JvmTransactionStorage(
        workspaceRoot = root,
        contentVerifier = TestPreparedContentVerifier,
        atomicFiles = atomicFiles,
    )

    /**
     * 创建 revision 0 到 1 的两对象密文事务。
     *
     * @return 摘要与真实字节一致的事务计划。
     */
    fun plan(): EncryptedTransactionPlan = EncryptedTransactionPlan(
        transactionId = TransactionId("transaction-a"),
        baseManifestRevision = 0,
        targetManifestRevision = 1,
        artifacts = listOf(
            EncryptedTransactionArtifact(
                targetPath = WorkspaceRelativePath("notes/note-a.note.enc"),
                encryptedBytes = "ENC:new-note".encodeToByteArray(),
                expectedSha256 = sha256("ENC:new-note".encodeToByteArray()),
            ),
            EncryptedTransactionArtifact(
                targetPath = WorkspaceRelativePath("tags/tag-a.tag.enc"),
                encryptedBytes = "ENC:new-tag".encodeToByteArray(),
                expectedSha256 = sha256("ENC:new-tag".encodeToByteArray()),
            ),
        ),
        encryptedManifest = nextManifest,
        manifestSha256 = sha256(nextManifest),
    )
}

/**
 * 在指定次数的完整原子写入后模拟操作系统磁盘空间不足。
 *
 * @param delegate 执行未注入故障的真实原子文件操作。
 * @param successfulWritesBeforeFailure 允许成功的 write 次数。
 */
private class DiskFullAtomicFilePort(
    private val delegate: JvmAtomicFilePort,
    private val successfulWritesBeforeFailure: Int,
) : JvmAtomicFilePort {
    /** 已成功执行的完整写入次数。 */
    private var successfulWrites: Int = 0

    /**
     * 达到注入点后在创建下一个临时文件前抛出磁盘满错误。
     *
     * @param target 最终目标。
     * @param bytes 完整内容。
     */
    override fun writeAtomically(
        target: Path,
        bytes: ByteArray,
    ) {
        if (successfulWrites >= successfulWritesBeforeFailure) {
            throw IOException("模拟磁盘空间不足")
        }
        delegate.writeAtomically(target, bytes)
        successfulWrites += 1
    }

    /**
     * 委托原子复制。
     *
     * @param source 来源文件。
     * @param target 最终目标。
     */
    override fun copyAtomically(
        source: Path,
        target: Path,
    ) = delegate.copyAtomically(source, target)

    /**
     * 委托流式文件发布。
     *
     * @param source 来源文件。
     * @param target 最终目标。
     */
    override fun publishPreparedFileAtomically(
        source: Path,
        target: Path,
    ) = delegate.publishPreparedFileAtomically(source, target)

    /**
     * 委托持久化删除。
     *
     * @param target 待删除文件。
     */
    override fun deleteDurably(target: Path) = delegate.deleteDurably(target)

    /**
     * 委托原子目录发布。
     *
     * @param source 来源目录。
     * @param target 最终目录。
     */
    override fun moveDirectoryAtomically(
        source: Path,
        target: Path,
    ) = delegate.moveDirectoryAtomically(source, target)

    /**
     * 委托目录元数据持久化。
     *
     * @param directory 目标目录。
     */
    override fun forceDirectory(directory: Path) = delegate.forceDirectory(directory)
}

/**
 * 用非敏感测试格式模拟解密后的 AEAD、schema 和 manifest 语义验证。
 */
private object TestPreparedContentVerifier : JvmPreparedContentVerifier {
    /**
     * 仅接受带测试密文前缀的 artifact。
     *
     * @param record prepared artifact 记录。
     * @param encryptedBytes 测试密文字节。
     * @return 格式有效为 `true`。
     */
    override suspend fun verifyArtifact(
        record: PreparedArtifactRecord,
        encryptedBytes: ByteArray,
    ): Boolean = encryptedBytes.decodeToString().startsWith("ENC:")

    /**
     * 验证目标 revision 与事务 ID 均出现在下一个测试 manifest。
     *
     * @param record prepared 事务记录。
     * @param encryptedBytes 测试 manifest 字节。
     * @return 语义匹配为 `true`。
     */
    override suspend fun verifyNextManifest(
        record: PreparedTransactionRecord,
        encryptedBytes: ByteArray,
    ): Boolean {
        val state = inspectManifest(encryptedBytes)
        return state.revision == record.targetManifestRevision &&
            record.transactionId in state.committedTransactionIds
    }

    /**
     * 解析 `revision|tx,tx` 测试格式。
     *
     * @param encryptedBytes 测试 manifest 字节。
     * @return 最小事务状态。
     */
    override suspend fun inspectManifest(encryptedBytes: ByteArray): ManifestTransactionState {
        val parts = encryptedBytes.decodeToString().split('|', limit = 2)
        val transactions = parts.getOrElse(1) { "" }
            .split(',')
            .filter(String::isNotBlank)
            .map(::TransactionId)
            .toSet()
        return ManifestTransactionState(parts.first().toLong(), transactions)
    }
}

/**
 * 表示测试中成功但不产生文件的派生状态更新器。
 */
private object NoOpDerivedStateUpdater : DerivedStateUpdater {
    /**
     * 接受已确认事务但不创建派生文件。
     *
     * @param transactionId 已确认事务。
     */
    override suspend fun update(transactionId: TransactionId) = Unit
}

/**
 * 生成测试 manifest 的非敏感明文协议。
 *
 * @param revision manifest revision。
 * @param transactionIds 已确认事务集合。
 * @return `revision|tx,tx` UTF-8 字节。
 */
private fun manifestBytes(
    revision: Long,
    transactionIds: Set<TransactionId>,
): ByteArray = "$revision|${transactionIds.joinToString(",") { it.value }}".encodeToByteArray()

/**
 * 计算测试事务计划使用的 SHA-256。
 *
 * @param bytes 待摘要字节。
 * @return 小写十六进制摘要。
 */
private fun sha256(bytes: ByteArray): String = MessageDigest
    .getInstance("SHA-256")
    .digest(bytes)
    .joinToString(separator = "") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0') }
