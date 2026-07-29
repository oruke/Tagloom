package com.oruke.tagloom.filestore.jvm

import com.oruke.tagloom.core.crypto.Argon2idParameters
import com.oruke.tagloom.core.crypto.CryptoProtocol
import com.oruke.tagloom.core.crypto.EncryptionEnvelopeCodec
import com.oruke.tagloom.core.crypto.EnvelopeCryptoService
import com.oruke.tagloom.core.crypto.EnvelopeEncryptionRequest
import com.oruke.tagloom.core.crypto.EnvelopeIdentity
import com.oruke.tagloom.core.crypto.WrappingKeyPurpose
import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.model.KeyId
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.ObjectRevision
import com.oruke.tagloom.core.model.ObjectSnapshot
import com.oruke.tagloom.core.model.ObjectType
import com.oruke.tagloom.core.model.SnapshotHeader
import com.oruke.tagloom.core.model.TransactionId
import com.oruke.tagloom.core.model.WorkspaceId
import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.transaction.DerivedStateUpdater
import com.oruke.tagloom.core.transaction.EncryptedTransactionArtifact
import com.oruke.tagloom.core.transaction.EncryptedTransactionPlan
import com.oruke.tagloom.core.transaction.TransactionCoordinator
import com.oruke.tagloom.core.transaction.WorkspaceRelativePath
import com.oruke.tagloom.core.workspace.ManifestObjectKind
import com.oruke.tagloom.core.workspace.ManifestObjectRecord
import com.oruke.tagloom.core.workspace.WorkspaceManifestService
import com.oruke.tagloom.crypto.libsodium.LibsodiumCryptoProvider
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 在真实 Windows 文件系统和真实 libsodium 密文上验证 manifest 确认事务垂直切片。
 */
class JvmEncryptedWorkspaceTransactionTest {
    /**
     * 验证 Note 快照只有通过 AEAD、schema、manifest 摘要和原子发布后才成为权威状态。
     */
    @Test
    fun commitsAuthenticatedNoteAndManifest() = withWorkspace { root ->
        val provider = LibsodiumCryptoProvider()
        provider.initialize()
        val manifestService = WorkspaceManifestService(provider)
        val envelopeService = EnvelopeCryptoService(provider)
        val workspaceId = WorkspaceId("workspace-integration")
        val keyId = KeyId("key-integration")
        val creation = manifestService.createWorkspace(
            workspaceId = workspaceId,
            keyId = keyId,
            password = "integration-password",
            argon2idParameters = Argon2idParameters(
                salt = provider.secureRandom(CryptoProtocol.ARGON2_SALT_BYTES),
                memoryKiB = 8,
                iterations = 1,
                parallelism = 1,
            ),
            passwordSlotId = "password-integration",
            recoverySlotId = "recovery-integration",
        )
        try {
            Files.write(root.resolve("manifest.enc"), creation.manifestBytes)
            val transactionId = TransactionId("transaction-integration")
            val targetPath = WorkspaceRelativePath("notes/note-integration.note.enc")
            val revision = "actor-integration:1"
            val noteBytes = encryptedNote(
                provider = provider,
                envelopeService = envelopeService,
                workspaceId = workspaceId,
                keyId = keyId,
                revision = revision,
                workspaceMasterKey = creation.workspaceMasterKey,
            )
            val noteSha256 = provider.sha256(noteBytes).toHex()
            val unlocked = manifestService.unlockWithPassword(creation.manifestBytes, "integration-password")
            val nextManifest = try {
                manifestService.confirmTransaction(
                    unlocked = unlocked,
                    transactionId = transactionId,
                    upserts = listOf(
                        ManifestObjectRecord(
                            kind = ManifestObjectKind.NOTE,
                            objectId = "note-integration",
                            revision = revision,
                            path = targetPath,
                            encryptedSha256 = noteSha256,
                            keyId = keyId,
                        ),
                    ),
                    removedPaths = emptySet(),
                )
            } finally {
                unlocked.destroy()
            }
            val plan = EncryptedTransactionPlan(
                transactionId = transactionId,
                baseManifestRevision = 0L,
                targetManifestRevision = 1L,
                artifacts = listOf(
                    EncryptedTransactionArtifact(
                        targetPath = targetPath,
                        encryptedBytes = noteBytes,
                        expectedSha256 = noteSha256,
                    ),
                ),
                encryptedManifest = nextManifest,
                manifestSha256 = provider.sha256(nextManifest).toHex(),
            )
            val verifier = JvmWorkspaceContentVerifier(
                workspaceId = workspaceId,
                activeKeyId = keyId,
                workspaceMasterKey = creation.workspaceMasterKey,
                envelopeCryptoService = envelopeService,
                workspaceManifestService = manifestService,
            )
            val result = TransactionCoordinator(
                storage = JvmTransactionStorage(root, verifier),
                derivedStateUpdater = IntegrationNoOpDerivedStateUpdater,
            ).commit(plan)

            assertTrue(result.derivedStateUpdated)
            assertFalse(Files.exists(root.resolve("staging/transaction-integration")))
            val confirmed = manifestService.inspectWithMasterKey(
                Files.readAllBytes(root.resolve("manifest.enc")),
                creation.workspaceMasterKey,
            )
            assertEquals(1L, confirmed.revision)
            assertEquals(transactionId, confirmed.lastCommittedTransactionId)
            assertEquals(noteSha256, confirmed.objects.single().encryptedSha256)
        } finally {
            creation.destroySecrets()
        }
    }

    /**
     * 构造经过真实信封加密的最小 Note 对象快照。
     *
     * @param provider 用于初始化和摘要的真实密码学实现。
     * @param envelopeService 对象信封加密服务。
     * @param workspaceId 工作区标识。
     * @param keyId 活动主密钥版本。
     * @param revision 快照 revision 文本。
     * @param workspaceMasterKey 创建工作区时生成的主密钥。
     * @return 可写入 Note 文件的二进制加密信封。
     */
    private suspend fun encryptedNote(
        provider: LibsodiumCryptoProvider,
        envelopeService: EnvelopeCryptoService,
        workspaceId: WorkspaceId,
        keyId: KeyId,
        revision: String,
        workspaceMasterKey: com.oruke.tagloom.core.crypto.SecretKeyMaterial,
    ): ByteArray {
        provider.initialize()
        val snapshot = ObjectSnapshot(
            header = SnapshotHeader(
                schemaVersion = 1,
                objectId = ObjectId("note-integration"),
                objectType = ObjectType.NOTE,
                revision = ObjectRevision(ActorId("actor-integration"), 1L),
                createdAt = "2026-07-13T00:00:00Z",
                updatedAt = "2026-07-13T00:00:00Z",
            ),
            payload = Note(
                title = "integration title",
                directTagIds = emptyList(),
            ),
        )
        val plaintext = CanonicalJson
            .encodeToString(ObjectSnapshot.serializer(Note.serializer()), snapshot)
            .encodeToByteArray()
        return try {
            EncryptionEnvelopeCodec.encode(
                envelopeService.encrypt(
                    request = EnvelopeEncryptionRequest(
                        identity = EnvelopeIdentity(
                            workspaceId = workspaceId,
                            objectType = "note-snapshot",
                            objectId = "note-integration",
                            revision = revision,
                        ),
                        keyId = keyId,
                        wrappingKeyPurpose = WrappingKeyPurpose.SNAPSHOT,
                        plaintext = plaintext,
                    ),
                    workspaceMasterKey = workspaceMasterKey,
                ),
            )
        } finally {
            plaintext.fill(0)
        }
    }

    /**
     * 在独立临时目录执行挂起测试并始终清理测试文件。
     *
     * @param block 使用临时工作区根目录的测试主体。
     */
    private fun withWorkspace(block: suspend (Path) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("tagloom-encrypted-workspace-test-").toAbsolutePath().normalize()
        try {
            block(root)
        } finally {
            deleteTestTree(root)
        }
    }

    /**
     * 只删除本测试创建且带固定前缀的临时目录。
     *
     * @param root 待清理临时根目录。
     */
    private fun deleteTestTree(root: Path) {
        val normalized = root.toAbsolutePath().normalize()
        check(normalized.fileName.toString().startsWith("tagloom-encrypted-workspace-test-")) {
            "拒绝删除非测试目录"
        }
        if (!Files.exists(normalized)) {
            return
        }
        Files.walk(normalized).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    /**
     * 把摘要字节转换为事务协议使用的小写十六进制文本。
     *
     * @return 小写十六进制摘要。
     */
    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
    }
}

/**
 * 集成测试使用的不产生外部派生状态更新器。
 */
private object IntegrationNoOpDerivedStateUpdater : DerivedStateUpdater {
    /**
     * 接收已确认事务但不写入派生索引。
     *
     * @param transactionId 已确认事务标识。
     */
    override suspend fun update(transactionId: TransactionId) = Unit
}
