package com.oruke.tagloom.filesystem

import com.oruke.tagloom.core.model.TransactionId
import com.oruke.tagloom.core.transaction.DerivedStateUpdater
import com.oruke.tagloom.core.transaction.TransactionCoordinator
import com.oruke.tagloom.core.transaction.TransactionRecoveryAction
import com.oruke.tagloom.core.transaction.TransactionRecoveryEntry
import com.oruke.tagloom.filesystem.jvm.JvmAtomicFileOperations
import com.oruke.tagloom.filesystem.jvm.JvmDirectoryDurability
import com.oruke.tagloom.filesystem.jvm.JvmPreparedContentVerifier
import com.oruke.tagloom.filesystem.jvm.JvmTransactionStorage
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 验证 JVM prepared 控制记录损坏时的密文隔离和只读恢复语义。 */
class JvmTransactionStorageTest {
    /** 验证无法解密的 prepared 目录会被移动到隔离区并要求工作区只读。 */
    @Test
    fun quarantinesUnreadablePreparedRecordAndRequiresReadOnlyMode() = runBlocking {
        val root = Files.createTempDirectory(TEST_DIRECTORY_PREFIX)
        val transactionId = TransactionId("transaction-corrupted")
        val stagingDirectory = root.resolve("staging").resolve(transactionId.value)
        try {
            Files.createDirectories(stagingDirectory)
            Files.write(stagingDirectory.resolve("prepared.json"), byteArrayOf(1, 2, 3))

            val storage = JvmTransactionStorage(
                workspaceRoot = root,
                contentVerifier = RejectingPreparedContentVerifier,
                atomicFiles = JvmAtomicFileOperations(NoOpTestDirectoryDurability),
            )
            val report = TransactionCoordinator(storage, NoOpDerivedStateUpdater).recoverOnStartup()

            assertEquals(
                listOf(TransactionRecoveryEntry(transactionId, TransactionRecoveryAction.QUARANTINED)),
                report.entries,
            )
            assertTrue(report.requiresReadOnlyMode)
            assertFalse(Files.exists(stagingDirectory))
            val quarantineRoot = root.resolve("history").resolve("quarantine")
            assertTrue(Files.isDirectory(quarantineRoot))
            assertTrue(Files.list(quarantineRoot).use { stream -> stream.findAny().isPresent })
        } finally {
            deleteTestTree(root)
        }
    }

    /** 删除测试创建的临时目录。 */
    private fun deleteTestTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private companion object {
        /** 测试目录固定前缀，避免与用户目录混淆。 */
        const val TEST_DIRECTORY_PREFIX: String = "tagloom-transaction-storage-test-"
    }
}

/** 在测试中稳定拒绝 prepared 解密，模拟控制记录损坏。 */
private object RejectingPreparedContentVerifier : JvmPreparedContentVerifier {
    /** 拒绝加密测试记录。 */
    override suspend fun encryptPreparedRecord(
        transactionId: TransactionId,
        plaintext: ByteArray,
    ): ByteArray = error("测试不执行 prepared 加密")

    /** 拒绝解密测试记录。 */
    override suspend fun decryptPreparedRecord(
        transactionId: TransactionId,
        encryptedBytes: ByteArray,
    ): ByteArray = error("测试模拟 prepared 解密失败")

    /** 拒绝测试 artifact。 */
    override suspend fun verifyArtifact(
        record: com.oruke.tagloom.core.transaction.PreparedArtifactRecord,
        encryptedBytes: ByteArray,
    ): Boolean = false

    /** 拒绝测试 manifest。 */
    override suspend fun verifyNextManifest(
        record: com.oruke.tagloom.core.transaction.PreparedTransactionRecord,
        encryptedBytes: ByteArray,
    ): Boolean = false

    /** 测试不读取当前 manifest。 */
    override suspend fun inspectManifest(encryptedBytes: ByteArray): com.oruke.tagloom.filesystem.jvm.ManifestTransactionState =
        error("测试不执行 manifest 检查")
}

/** 测试中不执行派生索引更新。 */
private object NoOpDerivedStateUpdater : DerivedStateUpdater {
    /** 忽略测试事务的派生状态更新。 */
    override suspend fun update(transactionId: TransactionId) = Unit
}

/** 测试中跳过平台目录句柄 force，只验证隔离路径和恢复报告。 */
private object NoOpTestDirectoryDurability : JvmDirectoryDurability {
    /** 忽略目录持久化请求。 */
    override fun force(directory: Path) = Unit
}
