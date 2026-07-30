package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.crypto.Argon2idParameters
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
 * 验证桌面本机访问登记只保存受保护的高熵解锁材料。
 */
class DesktopWorkspaceAccessStoreTest {
    /**
     * 验证未启用 PIN 时，重新创建 Store 仍能通过当前设备保护材料恢复同一高熵解锁输入。
     */
    @Test
    fun persistsWindowsAccountProtectedCredentialWithoutPin() = withTestArea { area ->
        val registry = area.resolve("workspace-access.json")
        val workspace = area.resolve("workspace")
        val store = testStore(registry)
        val credential = store.createCredential(workspace, pin = null)
        val expectedPassword = try {
            credential.useWorkspacePassword { password -> password }
        } finally {
            store.rememberAsActive(credential, pin = null)
            credential.destroy()
        }

        val reopenedStore = testStore(registry)
        val target = requireNotNull(reopenedStore.activeWorkspaceOrNull())
        assertEquals("workspace", target.displayName)
        assertFalse(target.requiresPin)
        val reopenedCredential = requireNotNull(reopenedStore.unlockActiveCredential(pin = null))
        val restoredPassword = try {
            reopenedCredential.useWorkspacePassword { password -> password }
        } finally {
            reopenedCredential.destroy()
        }

        val persisted = Files.readString(registry)
        assertEquals(expectedPassword, restoredPassword)
        assertFalse(persisted.contains(expectedPassword))
        assertTrue(Files.isRegularFile(registry))
    }

    /**
     * 验证同一 PIN 在应用重启后的新 Store 实例中仍能恢复同一高熵解锁材料。
     */
    @Test
    fun persistsPinProtectedCredentialAcrossStoreRecreation() = withTestArea { area ->
        val registry = area.resolve("workspace-access.json")
        val workspace = area.resolve("workspace")
        val store = testStore(registry)
        val credential = store.createCredential(workspace, TEST_PIN)
        val expectedPassword = try {
            credential.useWorkspacePassword { password -> password }
        } finally {
            store.rememberAsActive(credential, TEST_PIN)
            credential.destroy()
        }

        val reopenedStore = testStore(registry)
        assertEquals("workspace", requireNotNull(reopenedStore.activeWorkspaceOrNull()).displayName)
        val reopenedCredential = requireNotNull(reopenedStore.unlockActiveCredential(TEST_PIN))
        val restoredPassword = try {
            reopenedCredential.useWorkspacePassword { password -> password }
        } finally {
            reopenedCredential.destroy()
        }

        val persisted = Files.readString(registry)
        assertEquals(expectedPassword, restoredPassword)
        assertFalse(persisted.contains("\"$TEST_PIN\""))
        assertFalse(persisted.contains(expectedPassword))
        assertTrue(Files.isRegularFile(registry))
    }

    /**
     * 验证错误 PIN 无法解开本机材料，且失败路径不改写现有登记文件。
     */
    @Test
    fun wrongPinDoesNotChangePersistedAccessRecord() = withTestArea { area ->
        val registry = area.resolve("workspace-access.json")
        val store = testStore(registry)
        val credential = store.createCredential(area.resolve("workspace"), TEST_PIN)
        try {
            store.rememberAsActive(credential, TEST_PIN)
        } finally {
            credential.destroy()
        }
        val before = Files.readAllBytes(registry)

        assertFailsWith<RuntimeException> {
            runBlocking {
                store.unlockActiveCredential(WRONG_PIN)
            }
        }

        assertTrue(before.contentEquals(Files.readAllBytes(registry)))
    }

    /**
     * 验证在无 PIN 与 PIN 模式之间切换时，只改变本机保护层而不改变高熵工作区解锁材料。
     */
    @Test
    fun enablesAndDisablesPinWithoutChangingWorkspaceCredential() = withTestArea { area ->
        val registry = area.resolve("workspace-access.json")
        val store = testStore(registry)
        val initial = store.createCredential(area.resolve("workspace"), pin = null)
        val expectedPassword = try {
            initial.useWorkspacePassword { password -> password }
        } finally {
            store.rememberAsActive(initial, pin = null)
            initial.destroy()
        }

        val automaticCredential = requireNotNull(store.unlockActiveCredential(pin = null))
        try {
            store.rememberAsActive(automaticCredential, pin = TEST_PIN)
        } finally {
            automaticCredential.destroy()
        }
        assertTrue(requireNotNull(store.activeWorkspaceOrNull()).requiresPin)
        assertFailsWith<IllegalArgumentException> {
            runBlocking { store.unlockActiveCredential(pin = null) }
        }

        val pinCredential = requireNotNull(store.unlockActiveCredential(TEST_PIN))
        try {
            assertEquals(expectedPassword, pinCredential.useWorkspacePassword { password -> password })
            store.rememberAsActive(pinCredential, pin = null)
        } finally {
            pinCredential.destroy()
        }

        assertFalse(requireNotNull(store.activeWorkspaceOrNull()).requiresPin)
        val restoredCredential = requireNotNull(store.unlockActiveCredential(pin = null))
        try {
            assertEquals(expectedPassword, restoredCredential.useWorkspacePassword { password -> password })
        } finally {
            restoredCredential.destroy()
        }
    }

    /**
     * 验证低于六位或包含非数字字符的 PIN 不会生成本机解锁材料。
     */
    @Test
    fun rejectsInvalidPinBeforeCredentialGeneration() = withTestArea { area ->
        val store = testStore(area.resolve("workspace-access.json"))

        assertFailsWith<IllegalArgumentException> {
            runBlocking {
                store.createCredential(area.resolve("workspace"), "12345")
            }
        }
        assertFailsWith<IllegalArgumentException> {
            runBlocking {
                store.createCredential(area.resolve("workspace"), "12a456")
            }
        }
        assertFalse(Files.exists(area.resolve("workspace-access.json")))
    }

    /**
     * 构造使用真实 libsodium 与低成本 Argon2id 参数的独立本机访问登记。
     *
     * @param registry 测试专用登记文件路径。
     * @return 仅用于隔离测试的访问登记。
     */
    private fun testStore(registry: Path): DesktopWorkspaceAccessStore = DesktopWorkspaceAccessStore(
        registryFile = registry,
        cryptoProvider = LibsodiumCryptoProvider(),
        argon2idFactory = { salt ->
            Argon2idParameters(
                salt = salt,
                memoryKiB = 8,
                iterations = 1,
                parallelism = 1,
            )
        },
        deviceProtector = TestWorkspaceCredentialProtector(),
    )

    /**
     * 仅用于隔离 Store 路由的可逆测试替身，不代表或替代生产 Windows DPAPI。
     */
    private class TestWorkspaceCredentialProtector : DesktopWorkspaceCredentialProtector {
        /**
         * 使用测试常量和认证上下文扰动材料，避免把原始材料直接写入登记文件。
         *
         * @param plaintext 待保护的测试材料。
         * @param associatedData 与登记绑定的测试认证上下文。
         * @return 可由 [unprotect] 还原的测试保护数据。
         */
        override fun protect(
            plaintext: ByteArray,
            associatedData: ByteArray,
        ): ByteArray = transform(plaintext, associatedData)

        /**
         * 还原由 [protect] 生成的测试保护数据。
         *
         * @param protectedData 测试保护数据。
         * @param associatedData 与保护阶段一致的测试认证上下文。
         * @return 还原后的测试材料。
         */
        override fun unprotect(
            protectedData: ByteArray,
            associatedData: ByteArray,
        ): ByteArray = transform(protectedData, associatedData)

        /**
         * 对测试字节执行可逆扰动，验证 Store 不依赖某一种具体设备保护实现。
         *
         * @param source 待变换的测试字节。
         * @param associatedData 与登记绑定的测试认证上下文。
         * @return 新分配的可逆测试字节。
         */
        private fun transform(
            source: ByteArray,
            associatedData: ByteArray,
        ): ByteArray = ByteArray(source.size) { index ->
            (source[index].toInt() xor associatedData[index % associatedData.size].toInt() xor TEST_MASK).toByte()
        }
    }

    /**
     * 在独立临时目录中运行一个阻塞式密码学测试，并只清理本测试创建的路径。
     *
     * @param block 使用测试根目录的挂起测试主体。
     */
    private fun withTestArea(block: suspend (Path) -> Unit): Unit = runBlocking {
        val area = Files.createTempDirectory(TEST_DIRECTORY_PREFIX).toAbsolutePath().normalize()
        var failure: Throwable? = null
        try {
            block(area)
        } catch (error: Throwable) {
            failure = error
        } finally {
            try {
                deleteTestTree(area)
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
     * 安全删除带固定测试前缀的临时目录。
     *
     * @param root 待删除的临时测试根目录。
     */
    private fun deleteTestTree(root: Path) {
        val normalized = root.toAbsolutePath().normalize()
        check(normalized.fileName.toString().startsWith(TEST_DIRECTORY_PREFIX)) {
            "拒绝删除非测试目录"
        }
        Files.walk(normalized).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private companion object {
        /** 用于测试的六位数字 PIN，不对应任何真实设备。 */
        const val TEST_PIN: String = "246810"

        /** 错误 PIN 需要满足格式，确保测试真正进入 AEAD 认证失败路径。 */
        const val WRONG_PIN: String = "135791"

        /** 测试创建的临时目录固定前缀。 */
        const val TEST_DIRECTORY_PREFIX: String = "tagloom-workspace-access-test-"

        /** 测试保护替身使用的固定可逆掩码。 */
        const val TEST_MASK: Int = 0x5A
    }
}
