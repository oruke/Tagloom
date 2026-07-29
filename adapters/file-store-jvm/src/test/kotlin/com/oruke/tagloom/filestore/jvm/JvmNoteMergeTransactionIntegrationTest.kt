package com.oruke.tagloom.filestore.jvm

import com.oruke.tagloom.core.crypto.Argon2idParameters
import com.oruke.tagloom.core.crypto.CryptoProtocol
import com.oruke.tagloom.core.crypto.EncryptionEnvelopeCodec
import com.oruke.tagloom.core.crypto.EnvelopeCryptoService
import com.oruke.tagloom.core.crypto.EnvelopeDecryptionContext
import com.oruke.tagloom.core.crypto.EnvelopeEncryptionRequest
import com.oruke.tagloom.core.crypto.EnvelopeIdentity
import com.oruke.tagloom.core.crypto.SecretKeyMaterial
import com.oruke.tagloom.core.crypto.WrappingKeyPurpose
import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.ChangeId
import com.oruke.tagloom.core.model.InlineRun
import com.oruke.tagloom.core.model.KeyId
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.ObjectRevision
import com.oruke.tagloom.core.model.ObjectSnapshot
import com.oruke.tagloom.core.model.ObjectType
import com.oruke.tagloom.core.model.RichText
import com.oruke.tagloom.core.model.RichTextBlockContent
import com.oruke.tagloom.core.model.SnapshotHeader
import com.oruke.tagloom.core.model.TransactionId
import com.oruke.tagloom.core.model.WorkspaceId
import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.sync.ChangeKind
import com.oruke.tagloom.core.sync.ChangeOperation
import com.oruke.tagloom.core.sync.ConflictRecord
import com.oruke.tagloom.core.sync.HybridLogicalTime
import com.oruke.tagloom.core.sync.NoteMergeTransactionRequest
import com.oruke.tagloom.core.sync.NoteMergeTransactionService
import com.oruke.tagloom.core.sync.NoteMergeVersion
import com.oruke.tagloom.core.sync.ObjectChange
import com.oruke.tagloom.core.transaction.EncryptedTransactionArtifact
import com.oruke.tagloom.core.transaction.EncryptedTransactionPlan
import com.oruke.tagloom.core.transaction.TransactionCoordinator
import com.oruke.tagloom.core.transaction.WorkspaceRelativePath
import com.oruke.tagloom.core.workspace.ManifestObjectKind
import com.oruke.tagloom.core.workspace.ManifestObjectRecord
import com.oruke.tagloom.core.workspace.UnlockedWorkspaceManifest
import com.oruke.tagloom.core.workspace.WorkspaceManifestCodec
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
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonPrimitive

/** 在真实 libsodium、文件事务和 manifest 上验证同步冲突合并垂直切片。 */
class JvmNoteMergeTransactionIntegrationTest {
    /** 验证快照、双父 Merge Change 与 Base/Local/Remote 冲突证据只能一起被 manifest 确认。 */
    @Test
    fun commitsMergedSnapshotChangeAndConflictAtomically() = withWorkspace { root ->
        val provider = LibsodiumCryptoProvider()
        provider.initialize()
        val manifestService = WorkspaceManifestService(provider)
        val envelopeService = EnvelopeCryptoService(provider)
        val creation = manifestService.createWorkspace(
            workspaceId = WORKSPACE_ID,
            keyId = KEY_ID,
            password = "merge-test-password",
            argon2idParameters = Argon2idParameters(
                salt = provider.secureRandom(CryptoProtocol.ARGON2_SALT_BYTES),
                memoryKiB = 8,
                iterations = 1,
                parallelism = 1,
            ),
            passwordSlotId = "password-merge-test",
            recoverySlotId = "recovery-merge-test",
        )
        try {
            Files.write(root.resolve("manifest.enc"), creation.manifestBytes)
            val rootChange = change("change-root", "actor-root", 1L)
            val localChange = change("change-local", "actor-local", 10L, rootChange.changeId)
            val remoteChange = change("change-remote", "actor-remote", 11L, rootChange.changeId)
            val currentSnapshot = snapshot(note("hello earth"), localChange)
            val initialNoteSha256 = commitInitialSnapshot(
                root = root,
                provider = provider,
                manifestService = manifestService,
                envelopeService = envelopeService,
                workspaceKey = creation.workspaceMasterKey,
                snapshot = currentSnapshot,
            )
            val confirmedManifestBytes = Files.readAllBytes(root.resolve("manifest.enc"))
            val unlocked = UnlockedWorkspaceManifest(
                file = WorkspaceManifestCodec.decode(confirmedManifestBytes),
                payload = manifestService.inspectWithMasterKey(confirmedManifestBytes, creation.workspaceMasterKey),
                workspaceMasterKey = creation.workspaceMasterKey,
            )
            val coordinator = coordinator(
                root = root,
                manifestService = manifestService,
                envelopeService = envelopeService,
                workspaceKey = creation.workspaceMasterKey,
            )
            val result = NoteMergeTransactionService(
                cryptoProvider = provider,
                envelopeCryptoService = envelopeService,
                workspaceManifestService = manifestService,
                transactionCoordinator = coordinator,
            ).commit(
                unlocked = unlocked,
                request = NoteMergeTransactionRequest(
                    currentSnapshot = currentSnapshot,
                    baseNote = note("hello world"),
                    baseChangeId = rootChange.changeId,
                    localChange = localChange,
                    remoteVersion = NoteMergeVersion(note("hello moon"), remoteChange),
                    knownChanges = listOf(rootChange, localChange, remoteChange),
                    mergeActorId = ActorId("actor-merge"),
                    mergeSequence = 1L,
                    hybridLogicalTime = HybridLogicalTime(12L, 0, ActorId("actor-merge")),
                    transactionId = TransactionId("transaction-merge"),
                    mergedAt = "2026-07-13T01:00:00Z",
                ),
            )

            assertTrue(result.commit.derivedStateUpdated)
            assertEquals("hello moon", result.snapshot.payload.blocks.single().plainText())
            assertEquals(initialNoteSha256, result.snapshot.header.previousSnapshotHash)
            assertEquals(listOf(localChange.changeId, remoteChange.changeId), result.mergeChange.parentChangeIds)
            assertTrue(result.conflicts.single().conflictId.matches(Regex("[0-9a-f]{64}")))
            assertFalse(Files.exists(root.resolve("staging/transaction-merge")))

            val confirmed = manifestService.inspectWithMasterKey(
                Files.readAllBytes(root.resolve("manifest.enc")),
                creation.workspaceMasterKey,
            )
            assertEquals(2L, confirmed.revision)
            assertEquals(
                setOf(ManifestObjectKind.NOTE, ManifestObjectKind.CHANGE, ManifestObjectKind.HISTORY),
                confirmed.objects.map(ManifestObjectRecord::kind).toSet(),
            )
            assertEquals(result.snapshot, decryptSnapshot(root, result.snapshot, envelopeService, creation.workspaceMasterKey))
            assertEquals(result.mergeChange, decryptChange(root, result.mergeChange, envelopeService, creation.workspaceMasterKey))
            assertEquals(
                result.conflicts.single(),
                decryptConflict(root, result.conflicts.single(), envelopeService, creation.workspaceMasterKey),
            )
        } finally {
            creation.destroySecrets()
        }
    }

    /**
     * 提交当前设备已有的权威 Note 快照，作为后续并发合并的 manifest 基线。
     *
     * @param root 测试工作区根目录。
     * @param provider 真实密码学实现。
     * @param manifestService manifest 服务。
     * @param envelopeService 信封服务。
     * @param workspaceKey 工作区主密钥。
     * @param snapshot 当前 Note 快照。
     * @return 已确认初始快照密文 SHA-256。
     */
    private suspend fun commitInitialSnapshot(
        root: Path,
        provider: LibsodiumCryptoProvider,
        manifestService: WorkspaceManifestService,
        envelopeService: EnvelopeCryptoService,
        workspaceKey: SecretKeyMaterial,
        snapshot: ObjectSnapshot<Note>,
    ): String {
        val revision = snapshot.header.revision.protocolText()
        val path = WorkspaceRelativePath("notes/${OBJECT_ID.value}.note.enc")
        val encrypted = encryptJson(
            serializer = ObjectSnapshot.serializer(Note.serializer()),
            value = snapshot,
            identity = EnvelopeIdentity(WORKSPACE_ID, "note-snapshot", OBJECT_ID.value, revision),
            purpose = WrappingKeyPurpose.SNAPSHOT,
            envelopeService = envelopeService,
            workspaceKey = workspaceKey,
        )
        val encryptedSha256 = provider.sha256(encrypted).toHex()
        val currentManifest = Files.readAllBytes(root.resolve("manifest.enc"))
        val unlocked = UnlockedWorkspaceManifest(
            file = WorkspaceManifestCodec.decode(currentManifest),
            payload = manifestService.inspectWithMasterKey(currentManifest, workspaceKey),
            workspaceMasterKey = workspaceKey,
        )
        val transactionId = TransactionId("transaction-initial")
        val nextManifest = manifestService.confirmTransaction(
            unlocked = unlocked,
            transactionId = transactionId,
            upserts = listOf(
                ManifestObjectRecord(ManifestObjectKind.NOTE, OBJECT_ID.value, revision, path, encryptedSha256, KEY_ID),
            ),
            removedPaths = emptySet(),
        )
        coordinator(root, manifestService, envelopeService, workspaceKey).commit(
            EncryptedTransactionPlan(
                transactionId = transactionId,
                baseManifestRevision = 0L,
                targetManifestRevision = 1L,
                artifacts = listOf(EncryptedTransactionArtifact(path, encrypted, encryptedSha256)),
                encryptedManifest = nextManifest,
                manifestSha256 = provider.sha256(nextManifest).toHex(),
            ),
        )
        return encryptedSha256
    }

    /**
     * 创建连接真实文件存储和内容认证器的事务协调器。
     *
     * @param root 测试工作区根目录。
     * @param manifestService manifest 服务。
     * @param envelopeService 信封服务。
     * @param workspaceKey 工作区主密钥。
     * @return 可执行真实提交的协调器。
     */
    private fun coordinator(
        root: Path,
        manifestService: WorkspaceManifestService,
        envelopeService: EnvelopeCryptoService,
        workspaceKey: SecretKeyMaterial,
    ): TransactionCoordinator = TransactionCoordinator(
        storage = JvmTransactionStorage(
            workspaceRoot = root,
            contentVerifier = JvmWorkspaceContentVerifier(
                workspaceId = WORKSPACE_ID,
                activeKeyId = KEY_ID,
                workspaceMasterKey = workspaceKey,
                envelopeCryptoService = envelopeService,
                workspaceManifestService = manifestService,
            ),
        ),
        derivedStateUpdater = NoteMergeNoOpDerivedStateUpdater,
    )

    /**
     * 解密并解析已确认 Note 快照。
     *
     * @param root 工作区根目录。
     * @param expected 预期快照及其身份。
     * @param envelopeService 信封服务。
     * @param workspaceKey 工作区主密钥。
     * @return 认证后的 Note 快照。
     */
    private suspend fun decryptSnapshot(
        root: Path,
        expected: ObjectSnapshot<Note>,
        envelopeService: EnvelopeCryptoService,
        workspaceKey: SecretKeyMaterial,
    ): ObjectSnapshot<Note> = decryptJson(
        path = root.resolve("notes/${OBJECT_ID.value}.note.enc"),
        identity = EnvelopeIdentity(WORKSPACE_ID, "note-snapshot", OBJECT_ID.value, expected.header.revision.protocolText()),
        purpose = WrappingKeyPurpose.SNAPSHOT,
        serializer = ObjectSnapshot.serializer(Note.serializer()),
        envelopeService = envelopeService,
        workspaceKey = workspaceKey,
    )

    /**
     * 解密并解析已确认 Merge Change。
     *
     * @param root 工作区根目录。
     * @param expected 预期 Change 及其身份。
     * @param envelopeService 信封服务。
     * @param workspaceKey 工作区主密钥。
     * @return 认证后的 Change。
     */
    private suspend fun decryptChange(
        root: Path,
        expected: ObjectChange,
        envelopeService: EnvelopeCryptoService,
        workspaceKey: SecretKeyMaterial,
    ): ObjectChange = decryptJson(
        path = root.resolve("changes/${expected.changeId.value}.change.enc"),
        identity = EnvelopeIdentity(WORKSPACE_ID, "object-change", expected.changeId.value, "actor-merge:1"),
        purpose = WrappingKeyPurpose.CHANGE,
        serializer = ObjectChange.serializer(),
        envelopeService = envelopeService,
        workspaceKey = workspaceKey,
    )

    /**
     * 解密并解析已确认冲突证据。
     *
     * @param root 工作区根目录。
     * @param expected 预期冲突及其身份。
     * @param envelopeService 信封服务。
     * @param workspaceKey 工作区主密钥。
     * @return 认证后的冲突记录。
     */
    private suspend fun decryptConflict(
        root: Path,
        expected: ConflictRecord,
        envelopeService: EnvelopeCryptoService,
        workspaceKey: SecretKeyMaterial,
    ): ConflictRecord = decryptJson(
        path = root.resolve("history/conflicts/${expected.conflictId}.audit.enc"),
        identity = EnvelopeIdentity(WORKSPACE_ID, "audit-record", expected.conflictId, "actor-merge:1"),
        purpose = WrappingKeyPurpose.AUDIT,
        serializer = ConflictRecord.serializer(),
        envelopeService = envelopeService,
        workspaceKey = workspaceKey,
    )

    /**
     * 创建测试 Note。
     *
     * @param text 唯一段落文本。
     * @return 测试 Note。
     */
    private fun note(text: String): Note = Note(
        title = "Merge integration",
        directTagIds = emptyList(),
        blocks = listOf(
            Block(
                id = BLOCK_ID,
                type = BlockTypes.PARAGRAPH,
                orderKey = "a",
                content = RichTextBlockContent(RichText(listOf(InlineRun(text)))),
            ),
        ),
    )

    /**
     * 创建与本地 head 对应的当前快照。
     *
     * @param note 当前 Note payload。
     * @param change 当前 head Change。
     * @return 权威 Note 快照。
     */
    private fun snapshot(
        note: Note,
        change: ObjectChange,
    ): ObjectSnapshot<Note> = ObjectSnapshot(
        header = SnapshotHeader(
            schemaVersion = 1,
            objectId = OBJECT_ID,
            objectType = ObjectType.NOTE,
            revision = ObjectRevision(change.actorId, 1L),
            lastChangeId = change.changeId,
            createdAt = "2026-07-13T00:00:00Z",
            updatedAt = "2026-07-13T00:30:00Z",
        ),
        payload = note,
    )

    /**
     * 创建同一 Note 的测试 Change。
     *
     * @param id Change ID。
     * @param actor Actor ID。
     * @param time HLC 物理时间。
     * @param parent 可选父 Change。
     * @return 测试 Change。
     */
    private fun change(
        id: String,
        actor: String,
        time: Long,
        parent: ChangeId? = null,
    ): ObjectChange {
        val actorId = ActorId(actor)
        return ObjectChange(
            changeId = ChangeId(id),
            objectId = OBJECT_ID,
            parentChangeIds = listOfNotNull(parent),
            actorId = actorId,
            hybridLogicalTime = HybridLogicalTime(time, 0, actorId),
            baseSnapshotRevision = null,
            transactionId = TransactionId("transaction-$id"),
            kind = ChangeKind.UPDATE,
            operations = listOf(ChangeOperation("edit", payload = JsonPrimitive(id))),
        )
    }

    /**
     * 加密任意规范 JSON 值。
     *
     * @param T 值类型。
     * @param serializer 值 serializer。
     * @param value 待加密值。
     * @param identity 信封身份。
     * @param purpose wrapping key 用途。
     * @param envelopeService 信封服务。
     * @param workspaceKey 工作区主密钥。
     * @return 完整信封字节。
     */
    private suspend fun <T> encryptJson(
        serializer: KSerializer<T>,
        value: T,
        identity: EnvelopeIdentity,
        purpose: WrappingKeyPurpose,
        envelopeService: EnvelopeCryptoService,
        workspaceKey: SecretKeyMaterial,
    ): ByteArray {
        val plaintext = CanonicalJson.encodeToString(serializer, value).encodeToByteArray()
        return try {
            EncryptionEnvelopeCodec.encode(
                envelopeService.encrypt(
                    EnvelopeEncryptionRequest(identity, KEY_ID, purpose, plaintext),
                    workspaceKey,
                ),
            )
        } finally {
            plaintext.fill(0)
        }
    }

    /**
     * 认证并解析任意规范 JSON 信封。
     *
     * @param T 值类型。
     * @param path 信封文件路径。
     * @param identity 预期信封身份。
     * @param purpose 预期 wrapping key 用途。
     * @param serializer 值 serializer。
     * @param envelopeService 信封服务。
     * @param workspaceKey 工作区主密钥。
     * @return 解析后的认证值。
     */
    private suspend fun <T> decryptJson(
        path: Path,
        identity: EnvelopeIdentity,
        purpose: WrappingKeyPurpose,
        serializer: KSerializer<T>,
        envelopeService: EnvelopeCryptoService,
        workspaceKey: SecretKeyMaterial,
    ): T {
        val plaintext = envelopeService.decrypt(
            envelope = EncryptionEnvelopeCodec.decode(Files.readAllBytes(path)),
            context = EnvelopeDecryptionContext(identity, KEY_ID, purpose),
            workspaceMasterKey = workspaceKey,
        )
        return try {
            CanonicalJson.decodeFromString(serializer, plaintext.decodeToString())
        } finally {
            plaintext.fill(0)
        }
    }

    /**
     * 在独立临时目录运行测试并清理测试文件。
     *
     * @param block 使用测试工作区的挂起函数。
     */
    private fun withWorkspace(block: suspend (Path) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("tagloom-note-merge-test-").toAbsolutePath().normalize()
        try {
            block(root)
        } finally {
            check(root.fileName.toString().startsWith("tagloom-note-merge-test-")) { "拒绝删除非测试目录" }
            Files.walk(root).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    /**
     * 读取段落纯文本。
     *
     * @return 段落纯文本。
     */
    private fun Block.plainText(): String = (content as RichTextBlockContent).richText.plainText

    /**
     * 把对象 revision 转换为信封 revision 文本。
     *
     * @return `actor:sequence` 文本。
     */
    private fun ObjectRevision.protocolText(): String = "${actorId.value}:$sequence"

    /**
     * 把摘要字节转换为小写十六进制文本。
     *
     * @return 小写十六进制摘要。
     */
    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
    }

    companion object {
        /** 测试工作区 ID。 */
        private val WORKSPACE_ID: WorkspaceId = WorkspaceId("workspace-merge")

        /** 测试活动密钥 ID。 */
        private val KEY_ID: KeyId = KeyId("key-merge")

        /** 测试 Note ID。 */
        private val OBJECT_ID: ObjectId = ObjectId("note-merge")

        /** 测试段落 Block ID。 */
        private val BLOCK_ID: BlockId = BlockId("block-merge")
    }
}
