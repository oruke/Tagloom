package com.oruke.tagloom.sync.http.jvm

import com.oruke.tagloom.core.crypto.EncryptionEnvelopeCodec
import com.oruke.tagloom.core.crypto.EnvelopeCryptoService
import com.oruke.tagloom.core.crypto.EnvelopeDecryptionContext
import com.oruke.tagloom.core.crypto.EnvelopeEncryptionRequest
import com.oruke.tagloom.core.crypto.EnvelopeIdentity
import com.oruke.tagloom.core.crypto.DeviceSigningKeyPair
import com.oruke.tagloom.core.crypto.SecretKeyKind
import com.oruke.tagloom.core.crypto.SecretKeyMaterial
import com.oruke.tagloom.core.crypto.WrappingKeyPurpose
import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.model.KeyId
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.WorkspaceId
import com.oruke.tagloom.core.sync.BlindSyncArtifact
import com.oruke.tagloom.core.sync.ChangeDagSyncService
import com.oruke.tagloom.core.sync.DeviceArtifactSignatureService
import com.oruke.tagloom.core.sync.SyncDevicePublicKeyResolver
import com.oruke.tagloom.core.sync.SyncArtifactIntegrityService
import com.oruke.tagloom.core.sync.SyncArtifactKind
import com.oruke.tagloom.crypto.libsodium.LibsodiumCryptoProvider
import com.oruke.tagloom.sync.server.DeviceTokenAuthenticator
import com.oruke.tagloom.sync.server.JvmBlindSyncStore
import com.oruke.tagloom.sync.server.SyncDeviceIdentity
import com.oruke.tagloom.sync.server.installSyncServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** 验证两个真实本地副本经 HTTP 盲存储完成端到端密文 Change DAG 同步。 */
class ChangeDagHttpSyncIntegrationTest {
    /**
     * 验证两设备先后编辑、heads 交换、内容寻址去重、递归拉取和客户端 AEAD 解密。
     */
    @Test
    fun synchronizesEncryptedChangeDagAcrossTwoDevices() = runBlocking {
        val provider = LibsodiumCryptoProvider()
        provider.initialize()
        val integrity = SyncArtifactIntegrityService(provider, JvmBase64SyncPayloadCodec)
        val signatures = DeviceArtifactSignatureService(provider)
        val envelopeService = EnvelopeCryptoService(provider)
        val workspaceKey = workspaceKey()
        val signingKeyA = provider.generateDeviceSigningKeyPair()
        val signingKeyB = provider.generateDeviceSigningKeyPair()
        val serverStore = JvmBlindSyncStore(Files.createTempDirectory("tagloom-sync-server"))
        val publicKeys = mapOf(
            ACTOR_A to signingKeyA.copyPublicKey(),
            ACTOR_B to signingKeyB.copyPublicKey(),
        )
        val authenticator = authenticator(publicKeys)
        val loopback = InetAddress.getLoopbackAddress().hostAddress
        val engine = embeddedServer(Netty, port = 0, host = loopback) {
            installSyncServer(serverStore, authenticator)
        }.start(wait = false)

        try {
            val port = engine.engine.resolvedConnectors().single().port
            val baseUri = URI("http", null, loopback, port, null, null, null)
            val replicaA = JvmFileChangeReplica(
                workspaceRoot = Files.createTempDirectory("tagloom-sync-device-a"),
                workspaceId = WORKSPACE_ID,
                integrityService = integrity,
            )
            val replicaB = JvmFileChangeReplica(
                workspaceRoot = Files.createTempDirectory("tagloom-sync-device-b"),
                workspaceId = WORKSPACE_ID,
                integrityService = integrity,
            )
            val transportA = transport(baseUri, TOKEN_A)
            val transportB = transport(baseUri, TOKEN_B)
            val publicKeyResolver = SyncDevicePublicKeyResolver { workspaceId, actorId ->
                publicKeys[actorId]?.takeIf { workspaceId == WORKSPACE_ID }?.copyOf()
            }
            val syncA = ChangeDagSyncService(replicaA, transportA, integrity, signatures, publicKeyResolver)
            val syncB = ChangeDagSyncService(replicaB, transportB, integrity, signatures, publicKeyResolver)

            val rootChange = encryptedChange(
                actorId = ACTOR_A,
                actorSequence = 1,
                parentIds = emptyList(),
                plaintext = "设备 A 的端到端加密变更",
                workspaceKey = workspaceKey,
                envelopeService = envelopeService,
                integrity = integrity,
                signatures = signatures,
                signingKey = signingKeyA,
            )
            replicaA.saveLocalChange(rootChange)
            val firstUpload = syncA.synchronize(WORKSPACE_ID, ACTOR_A)
            val firstDownload = syncB.synchronize(WORKSPACE_ID, ACTOR_B)

            assertEquals(1, firstUpload.uploadedCount)
            assertEquals(1, firstDownload.downloadedCount)
            assertContentEquals(
                "设备 A 的端到端加密变更".encodeToByteArray(),
                decryptPayload(replicaB.loadChange(rootChange.artifactId)!!, workspaceKey, envelopeService),
            )

            val childChange = encryptedChange(
                actorId = ACTOR_B,
                actorSequence = 1,
                parentIds = listOf(rootChange.artifactId),
                plaintext = "设备 B 基于 A 的后继变更",
                workspaceKey = workspaceKey,
                envelopeService = envelopeService,
                integrity = integrity,
                signatures = signatures,
                signingKey = signingKeyB,
            )
            replicaB.saveLocalChange(childChange)
            val secondUpload = syncB.synchronize(WORKSPACE_ID, ACTOR_B)
            val secondDownload = syncA.synchronize(WORKSPACE_ID, ACTOR_A)

            assertEquals(1, secondUpload.uploadedCount)
            assertEquals(1, secondDownload.downloadedCount)
            assertEquals(listOf(childChange.artifactId), replicaA.listChangeHeads().single().headArtifactIds)
            assertContentEquals(
                "设备 B 基于 A 的后继变更".encodeToByteArray(),
                decryptPayload(replicaA.loadChange(childChange.artifactId)!!, workspaceKey, envelopeService),
            )
        } finally {
            signingKeyA.destroyPrivateKey()
            signingKeyB.destroyPrivateKey()
            workspaceKey.destroy()
            engine.stop(gracePeriodMillis = 1_000, timeoutMillis = 5_000)
        }
    }

    /**
     * 创建由 XChaCha20-Poly1305 信封保护的内容寻址 Change。
     *
     * @param actorId 产生 Change 的设备。
     * @param actorSequence 设备本地序号。
     * @param parentIds 直接父 Change 内容 ID。
     * @param plaintext 仅在客户端出现的测试业务 payload。
     * @param workspaceKey 测试工作区主密钥。
     * @param envelopeService AEAD 信封服务。
     * @param integrity 同步公开完整性服务。
     * @param signatures 设备作者签名服务。
     * @param signingKey 当前 Actor 的签名密钥。
     * @return 可安全上传盲存储节点的密文 Change。
     */
    private suspend fun encryptedChange(
        actorId: ActorId,
        actorSequence: Long,
        parentIds: List<String>,
        plaintext: String,
        workspaceKey: SecretKeyMaterial,
        envelopeService: EnvelopeCryptoService,
        integrity: SyncArtifactIntegrityService,
        signatures: DeviceArtifactSignatureService,
        signingKey: DeviceSigningKeyPair,
    ): BlindSyncArtifact {
        val revision = "${actorId.value}:$actorSequence"
        val identity = EnvelopeIdentity(
            workspaceId = WORKSPACE_ID,
            objectType = "change",
            objectId = OBJECT_ID.value,
            revision = revision,
        )
        val envelope = envelopeService.encrypt(
            request = EnvelopeEncryptionRequest(
                identity = identity,
                keyId = KEY_ID,
                wrappingKeyPurpose = WrappingKeyPurpose.CHANGE,
                plaintext = plaintext.encodeToByteArray(),
            ),
            workspaceMasterKey = workspaceKey,
        )
        val encodedEnvelope = EncryptionEnvelopeCodec.encode(envelope)
        return try {
            val unsigned = integrity.create(
                workspaceId = WORKSPACE_ID,
                kind = SyncArtifactKind.CHANGE,
                objectId = OBJECT_ID,
                parentArtifactIds = parentIds,
                actorId = actorId,
                actorSequence = actorSequence,
                encryptedPayload = encodedEnvelope,
            )
            signatures.sign(unsigned, signingKey.privateKeySeed)
        } finally {
            encodedEnvelope.fill(0)
        }
    }

    /**
     * 在接收设备本地执行二进制信封解析、身份校验与 AEAD 解密。
     *
     * @param artifact 已通过同步公开完整性校验的密文 Change。
     * @param workspaceKey 当前解锁工作区主密钥。
     * @param envelopeService AEAD 信封服务。
     * @return 认证通过的业务 payload 明文。
     */
    private suspend fun decryptPayload(
        artifact: BlindSyncArtifact,
        workspaceKey: SecretKeyMaterial,
        envelopeService: EnvelopeCryptoService,
    ): ByteArray {
        val encodedEnvelope = JvmBase64SyncPayloadCodec.decode(artifact.payloadBase64)
        val envelope = try {
            EncryptionEnvelopeCodec.decode(encodedEnvelope)
        } finally {
            encodedEnvelope.fill(0)
        }
        val descriptor = artifact.descriptor
        return envelopeService.decrypt(
            envelope = envelope,
            context = EnvelopeDecryptionContext(
                expectedIdentity = EnvelopeIdentity(
                    workspaceId = descriptor.workspaceId,
                    objectType = "change",
                    objectId = requireNotNull(descriptor.objectId).value,
                    revision = "${descriptor.actorId.value}:${descriptor.actorSequence}",
                ),
                expectedKeyId = KEY_ID,
                expectedWrappingKeyPurpose = WrappingKeyPurpose.CHANGE,
            ),
            workspaceMasterKey = workspaceKey,
        )
    }

    /**
     * 创建默认禁止重定向、仅为本机集成测试允许 HTTP 的传输。
     *
     * @param baseUri 动态环回服务 URI。
     * @param token 当前测试设备 Token。
     * @return 已认证 JVM HTTP 传输。
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
     * 创建包含两台测试设备 Token 摘要的认证器。
     *
     * @param publicKeys 两台设备各自登记的 Ed25519 公钥。
     * @return 可由服务端恒定时间比较的设备认证器。
     */
    private fun authenticator(publicKeys: Map<ActorId, ByteArray>): DeviceTokenAuthenticator = DeviceTokenAuthenticator(
        tokenHashes = mapOf(
            SyncDeviceIdentity(WORKSPACE_ID, ACTOR_A) to tokenHash(TOKEN_A),
            SyncDeviceIdentity(WORKSPACE_ID, ACTOR_B) to tokenHash(TOKEN_B),
        ),
        signingPublicKeys = publicKeys.mapKeys { (actorId, _) -> SyncDeviceIdentity(WORKSPACE_ID, actorId) },
    )

    /**
     * 计算测试高熵 Token 摘要。
     *
     * @param token 测试 Token。
     * @return 32 字节 SHA-256。
     */
    private fun tokenHash(token: String): ByteArray = MessageDigest.getInstance("SHA-256")
        .digest(token.encodeToByteArray())

    /**
     * 创建固定测试工作区主密钥。
     *
     * @return 可主动销毁的 256 位主密钥。
     */
    private fun workspaceKey(): SecretKeyMaterial = SecretKeyMaterial.from(
        SecretKeyKind.WORKSPACE_MASTER,
        ByteArray(32) { index -> (index * 5 + 1).toByte() },
    )

    private companion object {
        /** 测试工作区随机标识。 */
        val WORKSPACE_ID: WorkspaceId = WorkspaceId("workspace-sync")

        /** 被同步对象随机标识。 */
        val OBJECT_ID: ObjectId = ObjectId("note-sync")

        /** 测试工作区密钥版本。 */
        val KEY_ID: KeyId = KeyId("key-sync-1")

        /** 第一台测试设备。 */
        val ACTOR_A: ActorId = ActorId("device-a")

        /** 第二台测试设备。 */
        val ACTOR_B: ActorId = ActorId("device-b")

        /** 第一台设备的高熵测试 Token。 */
        const val TOKEN_A: String = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

        /** 第二台设备的高熵测试 Token。 */
        const val TOKEN_B: String = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
