package com.oruke.tagloom

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.WorkspaceId
import com.oruke.tagloom.core.sync.DeviceArtifactSignatureService
import com.oruke.tagloom.core.sync.SyncArtifactIntegrityService
import com.oruke.tagloom.core.sync.SyncArtifactKind
import com.oruke.tagloom.filesystem.jvm.crypto.LibsodiumCryptoProvider
import com.oruke.tagloom.sync.LocalSyncEngineConfiguration
import com.oruke.tagloom.sync.SyncRequest
import com.oruke.tagloom.sync.createSyncEngine
import com.oruke.tagloom.sync.http.SyncTransportException
import com.oruke.tagloom.sync.http.jvm.JvmBase64SyncPayloadCodec
import com.oruke.tagloom.sync.http.jvm.JvmFileBlindArtifactReplica
import com.oruke.tagloom.sync.http.jvm.JvmFileChangeReplica
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 验证 Android 上的 Ktor CIO、设备签名、密文同步副本和网络关闭边界。 */
@RunWith(AndroidJUnit4::class)
class AndroidSyncCapabilityTest {
    /** 从显式生产工厂完成上传、去重、第二副本下载、认证失败和在途取消。 */
    @Test
    fun exchangesCiphertextThroughRealHttpAndCancelsClosedSession(): Unit = runBlocking {
        val area = AndroidTestArea()
        val provider = LibsodiumCryptoProvider()
        provider.initialize()
        val keyPair = provider.generateDeviceSigningKeyPair()
        val workspaceId = WorkspaceId(UUID.randomUUID().toString())
        val actorId = ActorId(UUID.randomUUID().toString())
        val objectId = ObjectId(UUID.randomUUID().toString())
        val integrity = SyncArtifactIntegrityService(provider, JvmBase64SyncPayloadCodec)
        val signing = DeviceArtifactSignatureService(provider)
        val key = provider.secureRandom(32)
        val nonce = provider.secureRandom(24)
        val plaintext = "sync-placeholder".encodeToByteArray()
        val ciphertext = nonce + provider.encryptXChaCha20Poly1305(plaintext, byteArrayOf(), nonce, key)
        key.fill(0)
        val change = signing.sign(integrity.create(
            workspaceId, SyncArtifactKind.CHANGE, objectId, emptyList(), actorId, 1, ciphertext,
        ), keyPair.privateKeySeed)
        val snapshot = signing.sign(integrity.create(
            workspaceId, SyncArtifactKind.SNAPSHOT, objectId, emptyList(), actorId, 2, ciphertext,
        ), keyPair.privateKeySeed)
        val firstRoot = area.root.resolve("device-one")
        val secondRoot = area.root.resolve("device-two")
        try {
            JvmFileChangeReplica(firstRoot, workspaceId, integrity).saveLocalChange(change)
            JvmFileBlindArtifactReplica(firstRoot, workspaceId, integrity).saveLocalArtifact(snapshot)
            LoopbackSyncServer(workspaceId, actorId).use { server ->
                val configuration = LocalSyncEngineConfiguration(
                    workspaceRoot = firstRoot,
                    workspaceId = workspaceId,
                    serverBaseUri = server.baseUri,
                    tokenProvider = { server.accessToken },
                    publicKeyResolver = { workspace, actor ->
                        if (workspace == workspaceId && actor == actorId) keyPair.copyPublicKey() else null
                    },
                    cryptoProvider = provider,
                    signatureProvider = provider,
                    allowInsecureTransport = true,
                )
                val first = createSyncEngine(configuration)
                val second = createSyncEngine(configuration.copy(workspaceRoot = secondRoot))
                val unauthorized = createSyncEngine(configuration.copy(tokenProvider = { UUID.randomUUID().toString() }))
                val request = SyncRequest(workspaceId, actorId)
                try {
                    assertEquals(2, first.synchronize(request).uploaded)
                    assertEquals(setOf(change.artifactId, snapshot.artifactId), server.artifacts.keys)
                    assertEquals(0, first.synchronize(request).uploaded)
                    assertEquals(2, second.synchronize(request).downloaded)
                    assertEquals(change, JvmFileChangeReplica(secondRoot, workspaceId, integrity).loadChange(change.artifactId))
                    assertEquals(snapshot, JvmFileBlindArtifactReplica(secondRoot, workspaceId, integrity)
                        .loadArtifact(SyncArtifactKind.SNAPSHOT, snapshot.artifactId))
                    assertEquals(0, second.synchronize(request).downloaded)
                    assertTrue(ciphertext.decodeToString().contains("sync-placeholder").not())
                    val rejected = assertFailsWith<SyncTransportException> { unauthorized.synchronize(request) }
                    assertEquals(401, rejected.statusCode)

                    server.pauseNextResponse.set(true)
                    val pending = async(Dispatchers.IO) { first.synchronize(request) }
                    assertTrue(server.requestReceived.await(5, TimeUnit.SECONDS))
                    first.close()
                    withTimeout(5_000) { assertFailsWith<CancellationException> { pending.await() } }
                    assertFailsWith<IllegalStateException> { first.synchronize(request) }
                } finally {
                    first.close()
                    second.close()
                    unauthorized.close()
                }
            }
        } finally {
            keyPair.destroyPrivateKey()
            area.close()
        }
    }
}
