package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.crypto.Argon2idParameters
import com.oruke.tagloom.core.crypto.CryptoProvider
import com.oruke.tagloom.core.index.SearchIndexQuery
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.InlineRun
import com.oruke.tagloom.core.model.KeyId
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.RichText
import com.oruke.tagloom.core.model.RichTextBlockContent
import com.oruke.tagloom.core.model.SavedQuery
import com.oruke.tagloom.core.model.TransactionId
import com.oruke.tagloom.core.workspace.WorkspaceManifestService
import com.oruke.tagloom.crypto.libsodium.LibsodiumCryptoProvider
import com.oruke.tagloom.filestore.jvm.JvmAtomicFileOperations
import com.oruke.tagloom.filestore.jvm.JvmAtomicFilePort
import com.oruke.tagloom.filestore.jvm.JvmWorkspacePaths
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 使用真实密码学和文件事务验证 Workspace Master Key 全量轮换及故障回滚。
 */
class DesktopWorkspaceKeyRotationIntegrationTest {
    /**
     * 验证轮换会重写每个权威信封，使旧密码和旧 Recovery Key 失效，并以新密钥重建索引。
     */
    @Test
    fun rotatesEveryAuthoritativeEnvelopeAndInvalidatesOldCredentials() = withWorkspace { root ->
        val ids = AtomicInteger()
        val provider = LibsodiumCryptoProvider()
        val manager = testManager(provider, ids)
        val prepared = manager.prepareCreation(root, OLD_PASSWORD)
        manager.confirmCreation()
        val session = requireNotNull(manager.sessionOrNull())
        val savedNote = session.saveNote(null, rotationNote())
        val savedQuery = session.saveQuery(SavedQuery(name = "轮换后查询", text = "轮换密文内容"))
        val before = authenticatedState(provider, root, OLD_PASSWORD)

        val rotation = manager.rotateActiveMasterKey(OLD_PASSWORD, NEW_PASSWORD)
        val after = authenticatedState(provider, root, NEW_PASSWORD)

        assertTrue(rotation.recoveryKeyText.startsWith("TLRK1-"))
        assertNotEquals(prepared.recoveryKeyText, rotation.recoveryKeyText)
        assertNotEquals(before.activeKeyId, after.activeKeyId)
        assertEquals(before.ciphertextByPath.keys, after.ciphertextByPath.keys)
        assertEquals(setOf(after.activeKeyId), after.objectKeyIds)
        before.ciphertextByPath.forEach { (path, oldBytes) ->
            assertFalse(oldBytes.contentEquals(after.ciphertextByPath.getValue(path)), "权威信封未随主密钥轮换")
        }
        assertEquals(savedNote.id, rotation.openState.notes.single().id)
        assertEquals(savedQuery.id, rotation.openState.savedQueries.single().id)
        manager.lock()

        assertFailsWith<RuntimeException> { testManager(provider, ids).unlock(root, OLD_PASSWORD) }
        assertFailsWith<RuntimeException> {
            testManager(provider, ids).unlockWithRecovery(root, prepared.recoveryKeyText)
        }

        val passwordManager = testManager(provider, ids)
        passwordManager.unlock(root, NEW_PASSWORD)
        val passwordSession = requireNotNull(passwordManager.sessionOrNull())
        assertEquals(savedNote.id, passwordSession.search(SearchIndexQuery(text = "轮换密文内容")).single().noteId)
        assertEquals(savedNote.id, passwordSession.runSavedQuery(savedQuery.id).single().noteId)
        passwordManager.lock()

        val recoveryManager = testManager(provider, ids)
        val recovered = recoveryManager.unlockWithRecovery(root, rotation.recoveryKeyText)
        assertEquals(savedNote.id, recovered.notes.single().id)
        recoveryManager.lock()
    }

    /**
     * 验证轮换 staging 中途发生磁盘写入故障时，manifest 和权威对象保持全旧状态。
     */
    @Test
    fun stagingFailureLeavesWorkspaceOnOldMasterKey() = withWorkspace { root ->
        val ids = AtomicInteger()
        val provider = LibsodiumCryptoProvider()
        val manager = testManager(provider, ids)
        manager.prepareCreation(root, OLD_PASSWORD)
        manager.confirmCreation()
        val session = requireNotNull(manager.sessionOrNull())
        val saved = session.saveNote(null, rotationNote())
        val before = authenticatedState(provider, root, OLD_PASSWORD)
        val manifestBefore = Files.readAllBytes(root.resolve("manifest.enc"))
        val failingFiles = FailingAtomicFiles(failOnWrite = 3)

        assertFailsWith<IOException> {
            session.rotateMasterKey(
                service = DesktopWorkspaceKeyRotationService(
                    paths = JvmWorkspacePaths(root),
                    cryptoProvider = provider,
                    manifestService = WorkspaceManifestService(provider),
                    atomicFiles = failingFiles,
                ),
                request = DesktopMasterKeyRotationRequest(
                    newKeyId = KeyId("fault-rotation-key"),
                    newPassword = NEW_PASSWORD,
                    newParameters = testArgon2Parameters(ByteArray(16) { index -> (index + 1).toByte() }),
                    passwordSlotId = "fault-password-slot",
                    recoverySlotId = "fault-recovery-slot",
                    transactionId = TransactionId("fault-rotation-transaction"),
                ),
            )
        }
        manager.lock()

        assertTrue(manifestBefore.contentEquals(Files.readAllBytes(root.resolve("manifest.enc"))))
        val reopenedManager = testManager(provider, ids)
        reopenedManager.unlock(root, OLD_PASSWORD)
        assertEquals(saved.id, requireNotNull(reopenedManager.sessionOrNull())
            .search(SearchIndexQuery(text = "轮换密文内容")).single().noteId)
        val afterRecovery = authenticatedState(provider, root, OLD_PASSWORD)
        assertEquals(before.ciphertextByPath.keys, afterRecovery.ciphertextByPath.keys)
        before.ciphertextByPath.forEach { (path, bytes) ->
            assertTrue(bytes.contentEquals(afterRecovery.ciphertextByPath.getValue(path)))
        }
        reopenedManager.lock()
        assertFailsWith<RuntimeException> { testManager(provider, ids).unlock(root, NEW_PASSWORD) }
    }

    /**
     * 解锁 manifest 并读取全部权威密文，用于比较轮换前后确认点。
     *
     * @param provider 与工作区算法一致的真实密码学端口。
     * @param root 工作区根目录。
     * @param password 当前有效密码。
     * @return 活动 keyId、对象 keyId 集合和路径对应密文。
     */
    private suspend fun authenticatedState(
        provider: CryptoProvider,
        root: Path,
        password: String,
    ): AuthenticatedWorkspaceState {
        val unlocked = WorkspaceManifestService(provider).unlockWithPassword(
            manifestBytes = Files.readAllBytes(root.resolve("manifest.enc")),
            password = password,
        )
        return try {
            AuthenticatedWorkspaceState(
                activeKeyId = unlocked.file.publicHeader.activeKeyId,
                objectKeyIds = unlocked.payload.objects.map { record -> record.keyId }.toSet(),
                ciphertextByPath = unlocked.payload.objects.associate { record ->
                    record.path.value to Files.readAllBytes(root.resolve(record.path.value))
                },
            )
        } finally {
            unlocked.destroy()
        }
    }

    /**
     * 构造低成本测试 KDF 参数。
     *
     * @param salt 本次槽位随机 salt。
     * @return 仅用于测试的最小 Argon2id 参数。
     */
    private fun testArgon2Parameters(salt: ByteArray): Argon2idParameters = Argon2idParameters(
        salt = salt,
        memoryKiB = 8,
        iterations = 1,
        parallelism = 1,
    )

    /**
     * 构造使用真实适配器、稳定时间和低成本 KDF 的桌面 Manager。
     *
     * @param provider 共享真实密码学端口。
     * @param ids 跨会话递增的不透明 ID 来源。
     * @return 可执行真实文件 I/O 的 Manager。
     */
    private fun testManager(
        provider: CryptoProvider,
        ids: AtomicInteger,
    ): DesktopWorkspaceManager = DesktopWorkspaceManager(
        cryptoProvider = provider,
        idFactory = { "rotation-id-${ids.incrementAndGet()}" },
        currentTimeMillis = { 1_789_200_000_000L + ids.get() },
        timestampProvider = { "2026-09-12T00:00:00Z" },
        argon2idFactory = ::testArgon2Parameters,
    )

    /**
     * 构造包含可检索明文的合法 Note。
     *
     * @return 主密钥轮换测试 Note。
     */
    private fun rotationNote(): Note = Note(
        title = "轮换测试笔记",
        directTagIds = emptyList(),
        blocks = listOf(
            Block(
                id = BlockId("rotation-block"),
                type = BlockTypes.PARAGRAPH,
                orderKey = "0000000000",
                content = RichTextBlockContent(RichText(listOf(InlineRun("轮换密文内容")))),
            ),
        ),
    )

    /**
     * 在独立临时目录执行测试并只清理本测试创建的路径。
     *
     * @param block 使用临时工作区的测试主体。
     */
    private fun withWorkspace(block: suspend (Path) -> Unit): Unit = runBlocking {
        val root = Files.createTempDirectory(TEST_DIRECTORY_PREFIX).toAbsolutePath().normalize()
        var failure: Throwable? = null
        try {
            block(root)
        } catch (error: Throwable) {
            failure = error
        } finally {
            try {
                deleteTestTree(root)
            } catch (cleanupError: Throwable) {
                val original = failure
                if (original == null) {
                    failure = cleanupError
                } else {
                    original.addSuppressed(cleanupError)
                }
            }
        }
        val finalFailure = failure
        if (finalFailure != null) {
            throw finalFailure
        }
    }

    /**
     * 安全清理带固定前缀的测试目录。
     *
     * @param root 待删除临时根目录。
     */
    private fun deleteTestTree(root: Path) {
        val normalized = root.toAbsolutePath().normalize()
        check(normalized.fileName.toString().startsWith(TEST_DIRECTORY_PREFIX)) { "拒绝删除非测试目录" }
        if (!Files.exists(normalized)) {
            return
        }
        Files.walk(normalized).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private companion object {
        /** 轮换前的脱敏测试密码。 */
        const val OLD_PASSWORD: String = "rotation-old-password"

        /** 轮换后的脱敏测试密码。 */
        const val NEW_PASSWORD: String = "rotation-new-password"

        /** 受控临时目录前缀。 */
        const val TEST_DIRECTORY_PREFIX: String = "tagloom-key-rotation-test-"
    }
}

/**
 * 保存一个认证确认点的活动密钥和全部权威密文。
 */
private data class AuthenticatedWorkspaceState(
    /** manifest 活动主密钥版本。 */
    val activeKeyId: KeyId,
    /** manifest 中全部对象声明的 keyId。 */
    val objectKeyIds: Set<KeyId>,
    /** 权威相对路径到密文字节。 */
    val ciphertextByPath: Map<String, ByteArray>,
)

/**
 * 在指定完整写入次数模拟磁盘写入失败，其余原子原语委托真实实现。
 *
 * @param failOnWrite 从 1 开始的失败写入序号。
 * @param delegate 真实原子文件实现。
 */
private class FailingAtomicFiles(
    private val failOnWrite: Int,
    private val delegate: JvmAtomicFilePort = JvmAtomicFileOperations(),
) : JvmAtomicFilePort by delegate {
    /** 已发生的完整原子写入次数。 */
    private var writes: Int = 0

    /**
     * 在目标次数抛出磁盘故障，其余写入保持真实语义。
     *
     * @param target 最终目标文件。
     * @param bytes 待写完整内容。
     */
    override fun writeAtomically(
        target: Path,
        bytes: ByteArray,
    ) {
        writes += 1
        if (writes == failOnWrite) {
            throw IOException("模拟主密钥轮换磁盘写入失败")
        }
        delegate.writeAtomically(target, bytes)
    }
}
