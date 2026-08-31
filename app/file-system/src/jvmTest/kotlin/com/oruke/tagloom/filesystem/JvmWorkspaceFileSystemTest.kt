package com.oruke.tagloom.filesystem

import com.oruke.tagloom.core.crypto.CryptoException
import com.oruke.tagloom.core.crypto.EnvelopeIdentity
import com.oruke.tagloom.core.crypto.SecretKeyKind
import com.oruke.tagloom.core.crypto.SecretKeyMaterial
import com.oruke.tagloom.core.crypto.WrappingKeyPurpose
import com.oruke.tagloom.core.model.KeyId
import com.oruke.tagloom.core.model.WorkspaceId
import com.oruke.tagloom.filesystem.jvm.JvmDirectoryDurability
import com.oruke.tagloom.filesystem.jvm.JvmWorkspaceFileSystem
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 验证 JVM 文件能力的原子落盘、加密读写和单写入者语义。 */
class JvmWorkspaceFileSystemTest {
    /** 验证逻辑路径在不同分隔符输入下仍能原子替换、列出和删除。 */
    @Test
    fun writesListsAndDeletesLogicalFilesAtomically() = runBlocking {
        val root = Files.createTempDirectory(TEST_DIRECTORY_PREFIX)
        try {
            val fileSystem = JvmWorkspaceFileSystem(root, directoryDurability = NoOpDirectoryDurability)
            val pathWithWindowsSeparator = WorkspacePath("nested\\value.bin")
            val canonicalPath = WorkspacePath("nested/value.bin")
            fileSystem.writeAtomically(pathWithWindowsSeparator, FIRST_BYTES)
            assertEquals(FIRST_BYTES.toList(), fileSystem.read(canonicalPath)?.toList())
            assertEquals(listOf(canonicalPath), fileSystem.list(WorkspacePath("nested")))

            fileSystem.writeAtomically(canonicalPath, SECOND_BYTES)
            assertEquals(SECOND_BYTES.toList(), fileSystem.read(canonicalPath)?.toList())
            assertTrue(fileSystem.exists(canonicalPath))
            assertTrue(fileSystem.delete(canonicalPath))
            assertFalse(fileSystem.exists(canonicalPath))
            assertFalse(fileSystem.delete(canonicalPath))
        } finally {
            deleteTestTree(root)
        }
    }

    /** 验证加密文件读回明文，并拒绝身份、密钥用途不一致的有效密文调包。 */
    @Test
    fun encryptsAndAuthenticatesLogicalFiles() = runBlocking {
        val root = Files.createTempDirectory(TEST_DIRECTORY_PREFIX)
        val provider = com.oruke.tagloom.filesystem.jvm.crypto.LibsodiumCryptoProvider()
        val workspaceMasterKey = SecretKeyMaterial.from(
            SecretKeyKind.WORKSPACE_MASTER,
            ByteArray(32) { offset -> (offset + 1).toByte() },
        )
        val identity = EnvelopeIdentity(
            workspaceId = WORKSPACE_ID,
            objectType = "note-snapshot",
            objectId = "note-test",
            revision = "actor-test:1",
        )
        val keyId = KeyId("key-test")
        val path = WorkspacePath("notes/note-test.note.enc")
        try {
            val fileSystem = JvmWorkspaceFileSystem(
                workspaceRoot = root,
                cryptoProvider = provider,
                directoryDurability = NoOpDirectoryDurability,
            )
            val result = fileSystem.writeEncryptedAtomically(
                EncryptedFileWriteRequest(
                    path = path,
                    identity = identity,
                    keyId = keyId,
                    wrappingKeyPurpose = WrappingKeyPurpose.SNAPSHOT,
                    workspaceMasterKey = workspaceMasterKey,
                    plaintext = SENSITIVE_BYTES,
                ),
            )
            assertEquals(path, result.path)
            assertTrue(result.encryptedSize > SENSITIVE_BYTES.size)
            assertEquals(
                SENSITIVE_BYTES.toList(),
                fileSystem.readEncrypted(
                    EncryptedFileReadRequest(
                        path = path,
                        expectedIdentity = identity,
                        expectedKeyId = keyId,
                        expectedWrappingKeyPurpose = WrappingKeyPurpose.SNAPSHOT,
                        workspaceMasterKey = workspaceMasterKey,
                    ),
                )?.toList(),
            )
            assertFailsWith<CryptoException> {
                runBlocking {
                    fileSystem.readEncrypted(
                        EncryptedFileReadRequest(
                            path = path,
                            expectedIdentity = identity.copy(objectId = "other-note"),
                            expectedKeyId = keyId,
                            expectedWrappingKeyPurpose = WrappingKeyPurpose.SNAPSHOT,
                            workspaceMasterKey = workspaceMasterKey,
                        ),
                    )
                }
            }
            val encrypted = assertNotNull(fileSystem.read(path))
            assertFalse(encrypted.containsBytes(SENSITIVE_BYTES))
        } finally {
            workspaceMasterKey.destroy()
            deleteTestTree(root)
        }
    }

    /** 验证同一工作区的第二个 JVM 实例不能同时取得写入租约，释放后可重新取得。 */
    @Test
    fun enforcesSingleWriterLeaseAcrossInstances() = runBlocking {
        val root = Files.createTempDirectory(TEST_DIRECTORY_PREFIX)
        try {
            val first = JvmWorkspaceFileSystem(root, directoryDurability = NoOpDirectoryDurability)
            val second = JvmWorkspaceFileSystem(root, directoryDurability = NoOpDirectoryDurability)
            val firstLease = first.acquireWriteLease()
            try {
                assertFailsWith<IllegalStateException> {
                    runBlocking { second.acquireWriteLease() }
                }
            } finally {
                firstLease.release()
            }

            val secondLease = second.acquireWriteLease()
            secondLease.release()
        } finally {
            deleteTestTree(root)
        }
    }

    /**
     * 判断二进制内容是否包含完整连续字节片段。
     *
     * @param needle 待读取的连续字节片段。
     * @return 找到完整片段时为真。
     */
    private fun ByteArray.containsBytes(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        return (0..(size - needle.size)).any { start ->
            needle.indices.all { offset -> this[start + offset] == needle[offset] }
        }
    }

    /**
     * 删除当前测试创建的临时目录。
     *
     * @param root 测试专用临时目录。
     */
    private fun deleteTestTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private companion object {
        /** 测试目录固定前缀，避免清理路径超出测试范围。 */
        const val TEST_DIRECTORY_PREFIX: String = "tagloom-file-system-test-"

        /** 测试工作区身份。 */
        val WORKSPACE_ID: WorkspaceId = WorkspaceId("workspace-test")

        /** 原子写入第一次使用的脱敏字节。 */
        val FIRST_BYTES: ByteArray = "first-content".encodeToByteArray()

        /** 原子替换第二次使用的脱敏字节。 */
        val SECOND_BYTES: ByteArray = "second-content".encodeToByteArray()

        /** 不应以明文出现在加密文件中的测试内容。 */
        val SENSITIVE_BYTES: ByteArray = "private-note-body".encodeToByteArray()
    }
}

/** 测试中不依赖操作系统目录句柄 API 的目录持久化替身。 */
private object NoOpDirectoryDurability : JvmDirectoryDurability {
    /** 测试只验证文件内容和命名，不验证平台目录 force。 */
    override fun force(directory: Path) = Unit
}
