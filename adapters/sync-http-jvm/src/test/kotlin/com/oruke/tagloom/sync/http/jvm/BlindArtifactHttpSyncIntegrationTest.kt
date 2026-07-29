package com.oruke.tagloom.sync.http.jvm

import com.oruke.tagloom.core.crypto.DeviceSigningKeyPair
import com.oruke.tagloom.core.crypto.EncryptionEnvelopeCodec
import com.oruke.tagloom.core.crypto.EnvelopeCryptoService
import com.oruke.tagloom.core.crypto.EnvelopeDecryptionContext
import com.oruke.tagloom.core.crypto.EnvelopeEncryptionRequest
import com.oruke.tagloom.core.crypto.EnvelopeIdentity
import com.oruke.tagloom.core.crypto.SecretKeyKind
import com.oruke.tagloom.core.crypto.SecretKeyMaterial
import com.oruke.tagloom.core.crypto.WrappingKeyPurpose
import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.model.KeyId
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.WorkspaceId
import com.oruke.tagloom.core.sync.BlindArtifactSyncService
import com.oruke.tagloom.core.sync.BlindSyncArtifact
import com.oruke.tagloom.core.sync.DeviceArtifactSignatureService
import com.oruke.tagloom.core.sync.SyncArtifactIntegrityService
import com.oruke.tagloom.core.sync.SyncArtifactKind
import com.oruke.tagloom.core.sync.SyncDevicePublicKeyResolver
import com.oruke.tagloom.crypto.libsodium.LibsodiumCryptoProvider
import com.oruke.tagloom.sync.server.DeviceTokenAuthenticator
import com.oruke.tagloom.sync.server.JvmBlindSyncStore
import com.oruke.tagloom.sync.server.SyncDeviceIdentity
import com.oruke.tagloom.sync.server.installSyncServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.net.InetAddress
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/** 验证 Snapshot 与 Blob 通过通用内容清单接口完成双向端到端密文同步。 */
class BlindArtifactHttpSyncIntegrationTest {
    /** 验证两台设备用各自签名上传并下载非 Change 密文，服务端始终无法读取明文。 */
    @Test
    fun synchronizesSignedSnapshotsAndBlobsByInventoryDifference() = runBlocking {
        val provider = LibsodiumCryptoProvider()
        provider.initialize()
        val integrity = SyncArtifactIntegrityService(provider, JvmBase64SyncPayloadCodec)
        val signatures = DeviceArtifactSignatureService(provider)
        val envelopeService = EnvelopeCryptoService(provider)
        val workspaceKey = workspaceKey()
        val signingKeyA = provider.generateDeviceSigningKeyPair()
        val signingKeyB = provider.generateDeviceSigningKeyPair()
        val publicKeys = mapOf(ACTOR_A to signingKeyA.copyPublicKey(), ACTOR_B to signingKeyB.copyPublicKey())
        val loopback = InetAddress.getLoopbackAddress().hostAddress
        val engine = embeddedServer(Netty, port = 0, host = loopback) {
            installSyncServer(
                store = JvmBlindSyncStore(Files.createTempDirectory("tagloom-artifact-server")),
                authenticator = authenticator(publicKeys),
            )
        }.start(wait = false)

        try {
            val port = engine.engine.resolvedConnectors().single().port
            val baseUri = URI("http", null, loopback, port, null, null, null)
            val replicaA = replica("tagloom-artifact-device-a", integrity)
            val replicaB = replica("tagloom-artifact-device-b", integrity)
            val resolver = SyncDevicePublicKeyResolver { workspaceId, actorId ->
                publicKeys[actorId]?.takeIf { workspaceId == WORKSPACE_ID }?.copyOf()
            }
            val syncA = BlindArtifactSyncService(
                replicaA,
                transport(baseUri, TOKEN_A),
                integrity,
                signatures,
                resolver,
            )
            val syncB = BlindArtifactSyncService(
                replicaB,
                transport(baseUri, TOKEN_B),
                integrity,
                signatures,
                resolver,
            )
            val snapshot = encryptedArtifact(
                kind = SyncArtifactKind.SNAPSHOT,
                objectId = OBJECT_ID,
                actorId = ACTOR_A,
                actorSequence = 1L,
                objectType = "note-snapshot",
                envelopeObjectId = OBJECT_ID.value,
                purpose = WrappingKeyPurpose.SNAPSHOT,
                plaintext = "设备 A 的加密快照".encodeToByteArray(),
                signingKey = signingKeyA,
                provider = provider,
                integrity = integrity,
                signatures = signatures,
                envelopeService = envelopeService,
                workspaceKey = workspaceKey,
            )
            replicaA.saveLocalArtifact(snapshot)

            assertEquals(1, syncA.synchronize(WORKSPACE_ID, ACTOR_A).uploadedCount)
            assertEquals(1, syncB.synchronize(WORKSPACE_ID, ACTOR_B).downloadedCount)
            assertContentEquals(
                "设备 A 的加密快照".encodeToByteArray(),
                decryptPayload(snapshot, "note-snapshot", OBJECT_ID.value, WrappingKeyPurpose.SNAPSHOT, envelopeService, workspaceKey),
            )

            val blob = encryptedArtifact(
                kind = SyncArtifactKind.BLOB,
                objectId = null,
                actorId = ACTOR_B,
                actorSequence = 1L,
                objectType = "blob",
                envelopeObjectId = "blob-a",
                purpose = WrappingKeyPurpose.BLOB,
                plaintext = "设备 B 的加密附件".encodeToByteArray(),
                signingKey = signingKeyB,
                provider = provider,
                integrity = integrity,
                signatures = signatures,
                envelopeService = envelopeService,
                workspaceKey = workspaceKey,
            )
            replicaB.saveLocalArtifact(blob)

            assertEquals(1, syncB.synchronize(WORKSPACE_ID, ACTOR_B).uploadedCount)
            assertEquals(1, syncA.synchronize(WORKSPACE_ID, ACTOR_A).downloadedCount)
            assertEquals(blob, replicaA.loadArtifact(SyncArtifactKind.BLOB, blob.artifactId))
        } finally {
            signingKeyA.destroyPrivateKey()
            signingKeyB.destroyPrivateKey()
            workspaceKey.destroy()
            engine.stop(gracePeriodMillis = 1_000, timeoutMillis = 5_000)
        }
    }

    /**
     * 创建本地非 Change 密文副本。
     *
     * @param prefix 测试临时目录前缀。
     * @param integrity 内容寻址校验服务。
     * @return 独立本地副本。
     */
    private fun replica(
        prefix: String,
        integrity: SyncArtifactIntegrityService,
    ): JvmFileBlindArtifactReplica = JvmFileBlindArtifactReplica(
        workspaceRoot = Files.createTempDirectory(prefix),
        workspaceId = WORKSPACE_ID,
        integrityService = integrity,
    )

    /**
     * 创建实际 AEAD 加密、内容寻址并由设备签名的通用同步载荷。
     *
     * @param kind 同步载荷类型。
     * @param objectId 可选业务对象 ID。
     * @param actorId 原始设备 Actor。
     * @param actorSequence 设备本地序号。
     * @param objectType 信封对象类型。
     * @param envelopeObjectId 信封对象 ID。
     * @param purpose wrapping key 用途。
     * @param plaintext 测试明文。
     * @param signingKey 设备签名私钥。
     * @param provider 真实密码学实现。
     * @param integrity 内容寻址服务。
     * @param signatures 设备签名服务。
     * @param envelopeService 信封 AEAD 服务。
     * @param workspaceKey 工作区主密钥。
     * @return 已签名密文载荷。
     */
    private suspend fun encryptedArtifact(
        kind: SyncArtifactKind,
        objectId: ObjectId?,
        actorId: ActorId,
        actorSequence: Long,
        objectType: String,
        envelopeObjectId: String,
        purpose: WrappingKeyPurpose,
        plaintext: ByteArray,
        signingKey: DeviceSigningKeyPair,
        provider: LibsodiumCryptoProvider,
        integrity: SyncArtifactIntegrityService,
        signatures: DeviceArtifactSignatureService,
        envelopeService: EnvelopeCryptoService,
        workspaceKey: SecretKeyMaterial,
    ): BlindSyncArtifact {
        val revision = "${actorId.value}:$actorSequence"
        val envelope = envelopeService.encrypt(
            EnvelopeEncryptionRequest(
                EnvelopeIdentity(WORKSPACE_ID, objectType, envelopeObjectId, revision),
                KEY_ID,
                purpose,
                plaintext,
            ),
            workspaceKey,
        )
        val encoded = EncryptionEnvelopeCodec.encode(envelope)
        return try {
            val unsigned = integrity.create(
                WORKSPACE_ID,
                kind,
                objectId,
                emptyList(),
                actorId,
                actorSequence,
                encoded,
            )
            signatures.sign(unsigned, signingKey.privateKeySeed)
        } finally {
            encoded.fill(0)
            plaintext.fill(0)
        }
    }

    /**
     * 在客户端重新执行信封身份与 AEAD 校验。
     *
     * @param artifact 待解密密文载荷。
     * @param objectType 预期信封对象类型。
     * @param objectId 预期信封对象 ID。
     * @param purpose 预期 wrapping key 用途。
     * @param envelopeService 信封服务。
     * @param workspaceKey 工作区主密钥。
     * @return 认证明文。
     */
    private suspend fun decryptPayload(
        artifact: BlindSyncArtifact,
        objectType: String,
        objectId: String,
        purpose: WrappingKeyPurpose,
        envelopeService: EnvelopeCryptoService,
        workspaceKey: SecretKeyMaterial,
    ): ByteArray {
        val descriptor = artifact.descriptor
        val bytes = JvmBase64SyncPayloadCodec.decode(artifact.payloadBase64)
        val envelope = try {
            EncryptionEnvelopeCodec.decode(bytes)
        } finally {
            bytes.fill(0)
        }
        return envelopeService.decrypt(
            envelope,
            EnvelopeDecryptionContext(
                EnvelopeIdentity(WORKSPACE_ID, objectType, objectId, "${descriptor.actorId.value}:${descriptor.actorSequence}"),
                KEY_ID,
                purpose,
            ),
            workspaceKey,
        )
    }

    /**
     * 创建已登记两台设备 Token 与签名公钥的认证器。
     *
     * @param publicKeys Actor 到 Ed25519 公钥的映射。
     * @return 服务端认证器。
     */
    private fun authenticator(publicKeys: Map<ActorId, ByteArray>): DeviceTokenAuthenticator = DeviceTokenAuthenticator(
        tokenHashes = mapOf(
            SyncDeviceIdentity(WORKSPACE_ID, ACTOR_A) to tokenHash(TOKEN_A),
            SyncDeviceIdentity(WORKSPACE_ID, ACTOR_B) to tokenHash(TOKEN_B),
        ),
        signingPublicKeys = publicKeys.mapKeys { (actorId, _) -> SyncDeviceIdentity(WORKSPACE_ID, actorId) },
    )

    /**
     * 创建允许本机 HTTP 的测试传输。
     *
     * @param baseUri 动态环回服务地址。
     * @param token 当前设备 Token。
     * @return 已认证 HTTP 传输。
     */
    private fun transport(
        baseUri: URI,
        token: String,
    ): JvmHttpBlindSyncTransport = JvmHttpBlindSyncTransport(
        serverBaseUri = baseUri,
        tokenProvider = SyncAccessTokenProvider { token },
        allowInsecureTransport = true,
    )

    /**
     * 计算测试 Token SHA-256。
     *
     * @param token 高熵测试 Token。
     * @return 32 字节摘要。
     */
    private fun tokenHash(token: String): ByteArray = MessageDigest.getInstance("SHA-256")
        .digest(token.encodeToByteArray())

    /**
     * 创建固定测试工作区主密钥。
     *
     * @return 可主动销毁的工作区主密钥。
     */
    private fun workspaceKey(): SecretKeyMaterial = SecretKeyMaterial.from(
        SecretKeyKind.WORKSPACE_MASTER,
        ByteArray(32) { index -> (index * 7 + 2).toByte() },
    )

    private companion object {
        /** 测试工作区。 */
        val WORKSPACE_ID: WorkspaceId = WorkspaceId("workspace-artifact")

        /** 测试 Note。 */
        val OBJECT_ID: ObjectId = ObjectId("note-artifact")

        /** 活动工作区密钥。 */
        val KEY_ID: KeyId = KeyId("key-artifact")

        /** 第一台设备。 */
        val ACTOR_A: ActorId = ActorId("artifact-device-a")

        /** 第二台设备。 */
        val ACTOR_B: ActorId = ActorId("artifact-device-b")

        /** 第一台设备高熵测试 Token。 */
        const val TOKEN_A: String = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"

        /** 第二台设备高熵测试 Token。 */
        const val TOKEN_B: String = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
    }
}
