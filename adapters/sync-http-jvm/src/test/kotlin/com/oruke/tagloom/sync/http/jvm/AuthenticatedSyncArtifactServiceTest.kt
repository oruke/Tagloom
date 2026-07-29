package com.oruke.tagloom.sync.http.jvm

import com.oruke.tagloom.core.crypto.EnvelopeCryptoService
import com.oruke.tagloom.core.crypto.SecretKeyKind
import com.oruke.tagloom.core.crypto.SecretKeyMaterial
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
import com.oruke.tagloom.core.sync.AuthenticatedSyncArtifactService
import com.oruke.tagloom.core.sync.ChangeKind
import com.oruke.tagloom.core.sync.ChangeOperation
import com.oruke.tagloom.core.sync.DeviceArtifactSignatureService
import com.oruke.tagloom.core.sync.HybridLogicalTime
import com.oruke.tagloom.core.sync.ObjectChange
import com.oruke.tagloom.core.sync.SyncArtifactIntegrityService
import com.oruke.tagloom.crypto.libsodium.LibsodiumCryptoProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive

/** 验证 typed sync codec 只返回通过签名、AEAD、schema 与父节点映射的领域对象。 */
class AuthenticatedSyncArtifactServiceTest {
    /** 验证 ObjectChange 父映射和 Note Snapshot revision 在真实密码学实现上往返。 */
    @Test
    fun authenticatesChangesParentsAndNoteSnapshot() = runBlocking {
        val provider = LibsodiumCryptoProvider()
        provider.initialize()
        val signingKey = provider.generateDeviceSigningKeyPair()
        val workspaceKey = workspaceKey()
        try {
            val service = AuthenticatedSyncArtifactService(
                envelopeCryptoService = EnvelopeCryptoService(provider),
                integrityService = SyncArtifactIntegrityService(provider, JvmBase64SyncPayloadCodec),
                signatureService = DeviceArtifactSignatureService(provider),
                payloadCodec = JvmBase64SyncPayloadCodec,
            )
            val rootChange = change("change-root", emptyList(), physicalTime = 1L)
            val rootArtifact = service.createChange(
                WORKSPACE_ID,
                KEY_ID,
                workspaceKey,
                rootChange,
                actorSequence = 1L,
                parents = emptyList(),
                privateSigningSeed = signingKey.privateKeySeed,
            )
            val openedRoot = service.openChange(
                rootArtifact.artifact,
                KEY_ID,
                workspaceKey,
                signingKey.copyPublicKey(),
                parents = emptyList(),
            )
            assertEquals(rootChange, openedRoot.change)

            val childChange = change("change-child", listOf(rootChange.changeId), physicalTime = 2L)
            val childArtifact = service.createChange(
                WORKSPACE_ID,
                KEY_ID,
                workspaceKey,
                childChange,
                actorSequence = 2L,
                parents = listOf(openedRoot),
                privateSigningSeed = signingKey.privateKeySeed,
            )
            val openedChild = service.openChange(
                childArtifact.artifact,
                KEY_ID,
                workspaceKey,
                signingKey.copyPublicKey(),
                parents = listOf(openedRoot),
            )
            assertEquals(childChange, openedChild.change)
            assertTrue(
                runCatching {
                    service.openChange(
                        childArtifact.artifact,
                        KEY_ID,
                        workspaceKey,
                        signingKey.copyPublicKey(),
                        parents = emptyList(),
                    )
                }.isFailure,
            )

            val snapshot = noteSnapshot(childChange)
            val snapshotArtifact = service.createNoteSnapshot(
                WORKSPACE_ID,
                KEY_ID,
                workspaceKey,
                snapshot,
                signingKey.privateKeySeed,
            )
            val openedSnapshot = service.openNoteSnapshot(
                snapshotArtifact.artifact,
                KEY_ID,
                workspaceKey,
                signingKey.copyPublicKey(),
            )
            assertEquals(snapshot, openedSnapshot.snapshot)
        } finally {
            signingKey.destroyPrivateKey()
            workspaceKey.destroy()
        }
    }

    /**
     * 创建同一 Actor 的测试 Change。
     *
     * @param id Change ID。
     * @param parents 逻辑父 Change IDs。
     * @param physicalTime HLC 物理时间。
     * @return 测试 Change。
     */
    private fun change(
        id: String,
        parents: List<ChangeId>,
        physicalTime: Long,
    ): ObjectChange = ObjectChange(
        changeId = ChangeId(id),
        objectId = OBJECT_ID,
        parentChangeIds = parents,
        actorId = ACTOR_ID,
        hybridLogicalTime = HybridLogicalTime(physicalTime, 0, ACTOR_ID),
        baseSnapshotRevision = null,
        transactionId = TransactionId("transaction-$id"),
        kind = ChangeKind.UPDATE,
        operations = listOf(ChangeOperation("edit", payload = JsonPrimitive(id))),
    )

    /**
     * 创建与测试 Change 对应的 Note 快照。
     *
     * @param change 快照最后 Change。
     * @return 测试 Note 快照。
     */
    private fun noteSnapshot(change: ObjectChange): ObjectSnapshot<Note> = ObjectSnapshot(
        header = SnapshotHeader(
            schemaVersion = 1,
            objectId = OBJECT_ID,
            objectType = ObjectType.NOTE,
            revision = ObjectRevision(ACTOR_ID, 2L),
            lastChangeId = change.changeId,
            createdAt = "2026-07-13T00:00:00Z",
            updatedAt = "2026-07-13T01:00:00Z",
        ),
        payload = Note(
            title = "Authenticated snapshot",
            directTagIds = emptyList(),
            blocks = listOf(
                Block(
                    id = BlockId("block-authenticated"),
                    type = BlockTypes.PARAGRAPH,
                    orderKey = "a",
                    content = RichTextBlockContent(RichText(listOf(InlineRun("encrypted body")))),
                ),
            ),
        ),
    )

    /**
     * 创建测试工作区主密钥。
     *
     * @return 可主动销毁的工作区主密钥。
     */
    private fun workspaceKey(): SecretKeyMaterial = SecretKeyMaterial.from(
        SecretKeyKind.WORKSPACE_MASTER,
        ByteArray(32) { index -> (index * 11 + 4).toByte() },
    )

    private companion object {
        /** 测试工作区。 */
        val WORKSPACE_ID: WorkspaceId = WorkspaceId("workspace-authenticated")

        /** 测试 Note。 */
        val OBJECT_ID: ObjectId = ObjectId("note-authenticated")

        /** 测试 Actor。 */
        val ACTOR_ID: ActorId = ActorId("actor-authenticated")

        /** 活动密钥版本。 */
        val KEY_ID: KeyId = KeyId("key-authenticated")
    }
}
