package com.oruke.tagloom.crypto.libsodium

import com.oruke.tagloom.core.crypto.Argon2idParameters
import com.oruke.tagloom.core.crypto.CryptoException
import com.oruke.tagloom.core.crypto.CryptoProtocol
import com.oruke.tagloom.core.model.KeyId
import com.oruke.tagloom.core.model.WorkspaceId
import com.oruke.tagloom.core.workspace.WorkspaceManifestService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlinx.coroutines.runBlocking

/**
 * 使用真实 libsodium 原语验证工作区 manifest 的创建、解锁、重包和公开头认证。
 */
class WorkspaceManifestServiceTest {
    /**
     * 验证密码与 Recovery Key 都能恢复同一 Workspace Master Key。
     */
    @Test
    fun passwordAndRecoveryUnlockSameWorkspaceMasterKey() = runBlocking {
        val provider = LibsodiumCryptoProvider()
        val service = WorkspaceManifestService(provider)
        val creation = service.createWorkspace(
            workspaceId = WorkspaceId("workspace-a"),
            keyId = KeyId("key-a"),
            password = "initial-password",
            argon2idParameters = testParameters(provider, 1),
            passwordSlotId = "password-a",
            recoverySlotId = "recovery-a",
        )
        try {
            val passwordUnlocked = service.unlockWithPassword(creation.manifestBytes, "initial-password")
            val recoveryUnlocked = service.unlockWithRecovery(creation.manifestBytes, creation.recoveryKey)
            try {
                assertEquals(0L, passwordUnlocked.payload.revision)
                assertEquals(
                    passwordUnlocked.file.publicHeader.workspaceId,
                    recoveryUnlocked.file.publicHeader.workspaceId,
                )
                assertEquals(passwordUnlocked.payload, recoveryUnlocked.payload)
            } finally {
                passwordUnlocked.destroy()
                recoveryUnlocked.destroy()
            }
        } finally {
            creation.destroySecrets()
        }
    }

    /**
     * 验证换密码只更新 manifest，旧密码失效且原 Recovery Key 继续有效。
     */
    @Test
    fun passwordChangeKeepsRecoverySlotAndInvalidatesOldPassword() = runBlocking {
        val provider = LibsodiumCryptoProvider()
        val service = WorkspaceManifestService(provider)
        val creation = service.createWorkspace(
            workspaceId = WorkspaceId("workspace-b"),
            keyId = KeyId("key-b"),
            password = "old-password",
            argon2idParameters = testParameters(provider, 2),
            passwordSlotId = "password-old",
            recoverySlotId = "recovery-b",
        )
        try {
            val changed = service.changePassword(
                manifestBytes = creation.manifestBytes,
                oldPassword = "old-password",
                newPassword = "new-password",
                newParameters = testParameters(provider, 3),
                newPasswordSlotId = "password-new",
            )

            assertNotEquals(creation.manifestBytes.toList(), changed.toList())
            assertFailsWith<CryptoException> {
                service.unlockWithPassword(changed, "old-password")
            }
            val passwordUnlocked = service.unlockWithPassword(changed, "new-password")
            val recoveryUnlocked = service.unlockWithRecovery(changed, creation.recoveryKey)
            try {
                assertEquals(1L, passwordUnlocked.payload.revision)
                assertEquals(
                    passwordUnlocked.file.publicHeader.activeKeyId,
                    recoveryUnlocked.file.publicHeader.activeKeyId,
                )
                assertEquals(passwordUnlocked.payload, recoveryUnlocked.payload)
            } finally {
                passwordUnlocked.destroy()
                recoveryUnlocked.destroy()
            }
        } finally {
            creation.destroySecrets()
        }
    }

    /**
     * 验证公开密码槽头被修改后不能解锁对应主密钥包。
     */
    @Test
    fun tamperedPublicSlotHeaderFailsAuthentication() = runBlocking {
        val provider = LibsodiumCryptoProvider()
        val service = WorkspaceManifestService(provider)
        val creation = service.createWorkspace(
            workspaceId = WorkspaceId("workspace-c"),
            keyId = KeyId("key-c"),
            password = "password-c",
            argon2idParameters = testParameters(provider, 4),
            passwordSlotId = "password-c",
            recoverySlotId = "recovery-c",
        )
        try {
            val source = creation.manifestBytes.decodeToString()
            val tampered = source.replace("password-c", "password-x").encodeToByteArray()

            assertNotEquals(source, tampered.decodeToString())
            assertFailsWith<CryptoException> {
                service.unlockWithPassword(tampered, "password-c")
            }
            Unit
        } finally {
            creation.destroySecrets()
        }
    }

    /**
     * 构造快速单元测试使用的最小合法 Argon2id 参数。
     *
     * @param provider 提供随机 salt 的真实密码学适配器。
     * @param discriminator 防止不同测试意外复用同一参数语义。
     * @return 含独立随机 salt 的低成本测试参数。
     */
    private suspend fun testParameters(
        provider: LibsodiumCryptoProvider,
        discriminator: Int,
    ): Argon2idParameters {
        provider.initialize()
        val salt = provider.secureRandom(CryptoProtocol.ARGON2_SALT_BYTES)
        salt[0] = (salt[0].toInt() xor discriminator).toByte()
        return Argon2idParameters(
            salt = salt,
            memoryKiB = 8,
            iterations = 1,
            parallelism = 1,
        )
    }
}
