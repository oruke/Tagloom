package com.oruke.tagloom.filestore.jvm

import com.oruke.tagloom.core.crypto.Argon2idParameters
import com.oruke.tagloom.core.crypto.CryptoProtocol
import com.oruke.tagloom.core.model.KeyId
import com.oruke.tagloom.core.model.WorkspaceId
import com.oruke.tagloom.core.workspace.WorkspaceManifestService
import com.oruke.tagloom.crypto.libsodium.LibsodiumCryptoProvider
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 验证 JVM 工作区只有确认 Recovery Key 后才落盘，并能完成锁定与换密码。
 */
class JvmWorkspaceLifecycleServiceTest {
    /**
     * 验证把普通目录误选为工作区时返回可执行的恢复说明，而不是底层文件异常。
     */
    @Test
    fun rejectsDirectoryWithoutManifestWithActionableMessage() = withWorkspace { root ->
        val lifecycle = JvmWorkspaceLifecycleService(
            root,
            WorkspaceManifestService(LibsodiumCryptoProvider()),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            lifecycle.unlockWithPassword("irrelevant-password")
        }

        assertTrue(error.message?.contains("缺少 manifest.enc") == true)
        assertTrue(error.message?.contains("密文备份") == true)
    }

    /**
     * 验证未确认 Recovery Key 不写盘，确认后可以解锁并销毁会话密钥。
     */
    @Test
    fun requiresRecoveryConfirmationBeforePersistingWorkspace() = withWorkspace { root ->
        val provider = LibsodiumCryptoProvider()
        provider.initialize()
        val lifecycle = JvmWorkspaceLifecycleService(root, WorkspaceManifestService(provider))
        val creation = lifecycle.prepareCreation(
            workspaceId = WorkspaceId("workspace-lifecycle"),
            keyId = KeyId("key-lifecycle"),
            password = "lifecycle-password",
            argon2idParameters = parameters(provider),
            passwordSlotId = "password-lifecycle",
            recoverySlotId = "recovery-lifecycle",
        )
        try {
            assertFalse(Files.exists(root.resolve("manifest.enc")))
            assertFailsWith<IllegalArgumentException> {
                lifecycle.confirmCreation(creation, recoveryKeyConfirmed = false)
            }
            assertFalse(Files.exists(root.resolve("manifest.enc")))

            lifecycle.confirmCreation(creation, recoveryKeyConfirmed = true)
            assertTrue(Files.isRegularFile(root.resolve("manifest.enc")))
            val unlocked = lifecycle.unlockWithPassword("lifecycle-password")
            assertEquals(0L, unlocked.payload.revision)
            lifecycle.lock(unlocked)
            assertTrue(unlocked.workspaceMasterKey.isDestroyed)
        } finally {
            creation.destroySecrets()
        }
    }

    /**
     * 验证文件级换密码后旧密码失效且新密码可用。
     */
    @Test
    fun changesPasswordByAtomicallyReplacingManifest() = withWorkspace { root ->
        val provider = LibsodiumCryptoProvider()
        provider.initialize()
        val lifecycle = JvmWorkspaceLifecycleService(root, WorkspaceManifestService(provider))
        val creation = lifecycle.prepareCreation(
            workspaceId = WorkspaceId("workspace-password-change"),
            keyId = KeyId("key-password-change"),
            password = "old-password",
            argon2idParameters = parameters(provider),
            passwordSlotId = "password-old",
            recoverySlotId = "recovery-password-change",
        )
        try {
            lifecycle.confirmCreation(creation, recoveryKeyConfirmed = true)
            lifecycle.changePassword(
                oldPassword = "old-password",
                newPassword = "new-password",
                newParameters = parameters(provider),
                newPasswordSlotId = "password-new",
            )

            assertFailsWith<RuntimeException> { lifecycle.unlockWithPassword("old-password") }
            val unlocked = lifecycle.unlockWithPassword("new-password")
            try {
                assertEquals(1L, unlocked.payload.revision)
            } finally {
                lifecycle.lock(unlocked)
            }
        } finally {
            creation.destroySecrets()
        }
    }

    /**
     * 构造低成本测试 KDF 参数。
     *
     * @param provider 真实随机数提供者。
     * @return 带独立 salt 的合法 Argon2id 参数。
     */
    private fun parameters(provider: LibsodiumCryptoProvider): Argon2idParameters = Argon2idParameters(
        salt = provider.secureRandom(CryptoProtocol.ARGON2_SALT_BYTES),
        memoryKiB = 8,
        iterations = 1,
        parallelism = 1,
    )

    /**
     * 在独立临时目录执行挂起测试并始终清理。
     *
     * @param block 使用临时工作区的测试主体。
     */
    private fun withWorkspace(block: suspend (Path) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("tagloom-lifecycle-test-").toAbsolutePath().normalize()
        try {
            block(root)
        } finally {
            deleteTestTree(root)
        }
    }

    /**
     * 只删除本测试创建的固定前缀临时目录。
     *
     * @param root 待清理根目录。
     */
    private fun deleteTestTree(root: Path) {
        val normalized = root.toAbsolutePath().normalize()
        check(normalized.fileName.toString().startsWith("tagloom-lifecycle-test-")) {
            "拒绝删除非测试目录"
        }
        Files.walk(normalized).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
