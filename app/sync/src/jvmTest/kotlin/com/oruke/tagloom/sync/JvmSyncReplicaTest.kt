package com.oruke.tagloom.sync

import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.WorkspaceId
import com.oruke.tagloom.core.sync.SyncArtifactIntegrityService
import com.oruke.tagloom.core.sync.SyncArtifactKind
import com.oruke.tagloom.filesystem.jvm.JvmDirectoryDurability
import com.oruke.tagloom.filesystem.jvm.crypto.LibsodiumCryptoProvider
import com.oruke.tagloom.sync.http.jvm.JvmBase64SyncPayloadCodec
import com.oruke.tagloom.sync.http.jvm.JvmFileBlindArtifactReplica
import com.oruke.tagloom.sync.http.jvm.JvmFileChangeReplica
import com.oruke.tagloom.sync.http.jvm.ChangeReplicaCorruptionException
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 验证 JVM 同步副本的内容寻址、幂等落盘、heads 计算和损坏拒绝。 */
class JvmSyncReplicaTest {
    /** 验证 Change 父边决定 heads，重复保存幂等且损坏文件不会被静默接受。 */
    @Test
    fun storesChangeDagAndRejectsCorruption() = runBlocking {
        val provider = LibsodiumCryptoProvider()
        provider.initialize()
        val integrity = SyncArtifactIntegrityService(provider, JvmBase64SyncPayloadCodec)
        val workspaceRoot = Files.createTempDirectory("tagloom-sync-change")
        val replica = JvmFileChangeReplica(
            workspaceRoot = workspaceRoot,
            workspaceId = WORKSPACE_ID,
            integrityService = integrity,
            directoryDurability = NoOpDirectoryDurability,
        )
        val root = integrity.create(
            workspaceId = WORKSPACE_ID,
            kind = SyncArtifactKind.CHANGE,
            objectId = OBJECT_ID,
            parentArtifactIds = emptyList(),
            actorId = ACTOR_ID,
            actorSequence = 1,
            encryptedPayload = "cipher-root".encodeToByteArray(),
        )
        val child = integrity.create(
            workspaceId = WORKSPACE_ID,
            kind = SyncArtifactKind.CHANGE,
            objectId = OBJECT_ID,
            parentArtifactIds = listOf(root.artifactId),
            actorId = ACTOR_ID,
            actorSequence = 2,
            encryptedPayload = "cipher-child".encodeToByteArray(),
        )

        replica.saveLocalChange(root)
        replica.saveLocalChange(root)
        replica.saveLocalChange(child)
        assertEquals(listOf(child.artifactId), replica.listChangeHeads().single().headArtifactIds)
        assertEquals(root, replica.loadChange(root.artifactId))

        val rootPath = workspaceRoot.resolve("changes").resolve(root.artifactId.take(2)).resolve("${root.artifactId}.json")
        Files.writeString(rootPath, "corrupted")
        assertFailsWith<ChangeReplicaCorruptionException> {
            runBlocking { replica.containsChange(root.artifactId) }
        }
        Unit
    }

    /** 验证 Snapshot、Blob 和 Manifest 副本按类型隔离且拒绝 Change 混入。 */
    @Test
    fun storesNonChangeArtifactsByKind() = runBlocking {
        val provider = LibsodiumCryptoProvider()
        provider.initialize()
        val integrity = SyncArtifactIntegrityService(provider, JvmBase64SyncPayloadCodec)
        val workspaceRoot = Files.createTempDirectory("tagloom-sync-artifact")
        val replica = JvmFileBlindArtifactReplica(
            workspaceRoot = workspaceRoot,
            workspaceId = WORKSPACE_ID,
            integrityService = integrity,
            directoryDurability = NoOpDirectoryDurability,
        )
        val snapshot = integrity.create(
            workspaceId = WORKSPACE_ID,
            kind = SyncArtifactKind.SNAPSHOT,
            objectId = OBJECT_ID,
            parentArtifactIds = emptyList(),
            actorId = ACTOR_ID,
            actorSequence = 3,
            encryptedPayload = "cipher-snapshot".encodeToByteArray(),
        )
        val change = integrity.create(
            workspaceId = WORKSPACE_ID,
            kind = SyncArtifactKind.CHANGE,
            objectId = OBJECT_ID,
            parentArtifactIds = emptyList(),
            actorId = ACTOR_ID,
            actorSequence = 4,
            encryptedPayload = "cipher-change".encodeToByteArray(),
        )

        replica.saveLocalArtifact(snapshot)
        replica.saveLocalArtifact(snapshot)
        assertEquals(listOf(snapshot.artifactId), replica.listArtifactIds(SyncArtifactKind.SNAPSHOT))
        assertEquals(snapshot, replica.loadArtifact(SyncArtifactKind.SNAPSHOT, snapshot.artifactId))
        assertFailsWith<IllegalArgumentException> {
            runBlocking { replica.saveLocalArtifact(change) }
        }
        Unit
    }

    /** 验证无上下文 JVM expect 工厂不会猜测工作区认证材料。 */
    @Test
    fun unconfiguredFactoryFailsAtUse() = runBlocking {
        val engine = createSyncEngine()
        assertFailsWith<IllegalStateException> {
            runBlocking { engine.synchronize(SyncRequest(WORKSPACE_ID, ACTOR_ID)) }
        }
        engine.close()
    }

    private companion object {
        /** 测试工作区随机身份。 */
        val WORKSPACE_ID: WorkspaceId = WorkspaceId("workspace-jvm-test")

        /** 测试对象随机身份。 */
        val OBJECT_ID: ObjectId = ObjectId("note-jvm-test")

        /** 测试设备身份。 */
        val ACTOR_ID: ActorId = ActorId("actor-jvm-test")
    }
}

/** 测试中避免依赖操作系统目录句柄 API 的目录持久化替身。 */
private object NoOpDirectoryDurability : JvmDirectoryDurability {
    /**
     * 测试不需要验证平台目录 force；原子文件测试在文件内容和命名层完成。
     *
     * @param directory 待持久化目录。
     */
    override fun force(directory: Path) = Unit
}
