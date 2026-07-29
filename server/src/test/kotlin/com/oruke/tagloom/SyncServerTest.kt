package com.oruke.tagloom

import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.WorkspaceId
import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.crypto.DeviceSigningKeyPair
import com.oruke.tagloom.core.sync.BlindSyncArtifact
import com.oruke.tagloom.core.sync.CURRENT_SYNC_PROTOCOL_VERSION
import com.oruke.tagloom.core.sync.DeviceArtifactSignatureService
import com.oruke.tagloom.core.sync.SyncArtifactDescriptor
import com.oruke.tagloom.core.sync.SyncArtifactKind
import com.oruke.tagloom.core.sync.SyncFetchRequest
import com.oruke.tagloom.core.sync.SyncFetchResponse
import com.oruke.tagloom.core.sync.SyncHeadsResponse
import com.oruke.tagloom.core.sync.SyncPushRequest
import com.oruke.tagloom.core.sync.SyncPushResponse
import com.oruke.tagloom.sync.server.DeviceTokenAuthenticator
import com.oruke.tagloom.sync.server.JvmBlindSyncStore
import com.oruke.tagloom.sync.server.JvmSyncArtifactVerifier
import com.oruke.tagloom.sync.server.SyncDeviceIdentity
import com.oruke.tagloom.sync.server.installSyncServer
import com.oruke.tagloom.crypto.libsodium.LibsodiumCryptoProvider
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** 验证自部署盲同步 HTTP 协议、认证、持久化和篡改拒绝。 */
class SyncServerTest {
    /**
     * 验证认证后的 Change 可以幂等 push、交换 heads 并按内容 ID fetch。
     */
    @Test
    fun pushesFetchesAndDeduplicatesEncryptedChanges() = testApplication {
        val provider = LibsodiumCryptoProvider()
        provider.initialize()
        val signingKey = provider.generateDeviceSigningKeyPair()
        val store = JvmBlindSyncStore(Files.createTempDirectory("tagloom-sync-http"))
        application {
            installSyncServer(store, authenticator(signingKey.copyPublicKey()))
        }
        try {
            val artifact = artifact(actorId = ACTOR_ID, provider = provider, signingKey = signingKey)
            val pushRequest = SyncPushRequest(listOf(artifact))

            val firstPush = client.post(pushUrl()) {
                authenticate()
                contentType(ContentType.Application.Json)
                setBody(CanonicalJson.encodeToString(SyncPushRequest.serializer(), pushRequest))
            }
            val duplicatePush = client.post(pushUrl()) {
                authenticate()
                contentType(ContentType.Application.Json)
                setBody(CanonicalJson.encodeToString(SyncPushRequest.serializer(), pushRequest))
            }
            val headsResponse = client.get(headsUrl()) { authenticate() }
            val fetchResponse = client.post(fetchUrl()) {
                authenticate()
                contentType(ContentType.Application.Json)
                setBody(
                    CanonicalJson.encodeToString(
                        SyncFetchRequest.serializer(),
                        SyncFetchRequest(listOf(artifact.artifactId)),
                    ),
                )
            }

            assertEquals(HttpStatusCode.OK, firstPush.status)
            assertEquals(
                SyncPushResponse(storedCount = 1, duplicateCount = 0),
                CanonicalJson.decodeFromString(SyncPushResponse.serializer(), firstPush.bodyAsText()),
            )
            assertEquals(
                SyncPushResponse(storedCount = 0, duplicateCount = 1),
                CanonicalJson.decodeFromString(SyncPushResponse.serializer(), duplicatePush.bodyAsText()),
            )
            val heads = CanonicalJson.decodeFromString(SyncHeadsResponse.serializer(), headsResponse.bodyAsText())
            assertEquals(listOf(artifact.artifactId), heads.objects.single().headArtifactIds)
            val fetched = CanonicalJson.decodeFromString(SyncFetchResponse.serializer(), fetchResponse.bodyAsText())
            assertEquals(listOf(artifact), fetched.artifacts)
        } finally {
            signingKey.destroyPrivateKey()
        }
    }

    /**
     * 验证缺少认证、跨工作区载荷和密文篡改分别被稳定拒绝。
     */
    @Test
    fun rejectsUnauthenticatedCrossWorkspaceAndTamperedPushes() = testApplication {
        val provider = LibsodiumCryptoProvider()
        provider.initialize()
        val signingKey = provider.generateDeviceSigningKeyPair()
        val store = JvmBlindSyncStore(Files.createTempDirectory("tagloom-sync-reject"))
        application {
            installSyncServer(store, authenticator(signingKey.copyPublicKey()))
        }
        try {
            val valid = artifact(ACTOR_ID, provider, signingKey)
            val crossWorkspace = valid.copy(
                descriptor = valid.descriptor.copy(workspaceId = WorkspaceId("workspace-other")),
            )
            val tampered = valid.copy(payloadBase64 = Base64.getEncoder().encodeToString("tampered".encodeToByteArray()))
            val replacementPrefix = if (valid.authorSignatureHex!!.startsWith("00")) "ff" else "00"
            val forgedSignature = valid.copy(
                authorSignatureHex = valid.authorSignatureHex!!.replaceRange(0, 2, replacementPrefix),
            )

            val unauthenticated = client.post(pushUrl()) {
                contentType(ContentType.Application.Json)
                setBody(CanonicalJson.encodeToString(SyncPushRequest.serializer(), SyncPushRequest(listOf(valid))))
            }
            val crossWorkspaceResponse = client.post(pushUrl()) {
                authenticate()
                contentType(ContentType.Application.Json)
                setBody(CanonicalJson.encodeToString(SyncPushRequest.serializer(), SyncPushRequest(listOf(crossWorkspace))))
            }
            val tamperResponse = client.post(pushUrl()) {
                authenticate()
                contentType(ContentType.Application.Json)
                setBody(CanonicalJson.encodeToString(SyncPushRequest.serializer(), SyncPushRequest(listOf(tampered))))
            }
            val signatureResponse = client.post(pushUrl()) {
                authenticate()
                contentType(ContentType.Application.Json)
                setBody(CanonicalJson.encodeToString(SyncPushRequest.serializer(), SyncPushRequest(listOf(forgedSignature))))
            }

            assertEquals(HttpStatusCode.Unauthorized, unauthenticated.status)
            assertEquals(HttpStatusCode.Forbidden, crossWorkspaceResponse.status)
            assertEquals(HttpStatusCode.BadRequest, tamperResponse.status)
            assertEquals(HttpStatusCode.Forbidden, signatureResponse.status)
            assertFalse(store.listChangeHeads(WORKSPACE_ID).isNotEmpty())
        } finally {
            signingKey.destroyPrivateKey()
        }
    }

    /**
     * 验证新存储实例可以仅扫描磁盘密文文件恢复 Change heads。
     */
    @Test
    fun rebuildsHeadsAfterServerRestart() = runBlocking {
        val provider = LibsodiumCryptoProvider()
        provider.initialize()
        val signingKey = provider.generateDeviceSigningKeyPair()
        val root = Files.createTempDirectory("tagloom-sync-restart")
        try {
            val artifact = artifact(ACTOR_ID, provider, signingKey)
            val firstStore = JvmBlindSyncStore(root)
            firstStore.put(artifact)

            val restartedStore = JvmBlindSyncStore(root)
            val heads = restartedStore.listChangeHeads(WORKSPACE_ID)

            assertEquals(listOf(artifact.artifactId), heads.single().headArtifactIds)
        } finally {
            signingKey.destroyPrivateKey()
        }
    }

    /**
     * 创建服务端公开完整性有效、内容不可解释的测试密文 Change。
     *
     * @param actorId 载荷声明的设备身份。
     * @param provider 真实 Ed25519 签名实现。
     * @param signingKey 当前 Actor 的测试签名密钥。
     * @return 可由服务端盲验证的内容寻址载荷。
     */
    private suspend fun artifact(
        actorId: ActorId,
        provider: LibsodiumCryptoProvider,
        signingKey: DeviceSigningKeyPair,
    ): BlindSyncArtifact {
        val encryptedPayload = ByteArray(96) { index -> (index * 17 + 3).toByte() }
        val descriptor = SyncArtifactDescriptor(
            protocolVersion = CURRENT_SYNC_PROTOCOL_VERSION,
            workspaceId = WORKSPACE_ID,
            kind = SyncArtifactKind.CHANGE,
            objectId = ObjectId("note-a"),
            parentArtifactIds = emptyList(),
            actorId = actorId,
            actorSequence = 1,
            payloadSha256 = JvmSyncArtifactVerifier.sha256(encryptedPayload),
            payloadByteLength = encryptedPayload.size,
        )
        val unsigned = BlindSyncArtifact(
            artifactId = JvmSyncArtifactVerifier.descriptorContentId(descriptor),
            descriptor = descriptor,
            payloadBase64 = Base64.getEncoder().encodeToString(encryptedPayload),
        )
        return DeviceArtifactSignatureService(provider).sign(unsigned, signingKey.privateKeySeed)
    }

    /**
     * 创建只接受测试工作区与设备的高熵 Token 认证器。
     *
     * @param publicKey 当前测试设备 Ed25519 公钥。
     * @return 已配置测试 Token 摘要的认证器。
     */
    private fun authenticator(publicKey: ByteArray): DeviceTokenAuthenticator {
        val hash = MessageDigest.getInstance("SHA-256").digest(BEARER_TOKEN.encodeToByteArray())
        val identity = SyncDeviceIdentity(WORKSPACE_ID, ACTOR_ID)
        return DeviceTokenAuthenticator(
            tokenHashes = mapOf(identity to hash),
            signingPublicKeys = mapOf(identity to publicKey),
        )
    }

    /**
     * 为 Ktor 测试请求设置随机 Actor 与 Bearer Token。
     */
    private fun io.ktor.client.request.HttpRequestBuilder.authenticate() {
        header("X-Tagloom-Actor", ACTOR_ID.value)
        header(HttpHeaders.Authorization, "Bearer $BEARER_TOKEN")
    }

    /**
     * 返回 Change 推送测试 URL。
     *
     * @return 含随机工作区标识的相对 URL。
     */
    private fun pushUrl(): String = "/v1/workspaces/${WORKSPACE_ID.value}/sync/changes/push"

    /**
     * 返回 Change 精确拉取测试 URL。
     *
     * @return 含随机工作区标识的相对 URL。
     */
    private fun fetchUrl(): String = "/v1/workspaces/${WORKSPACE_ID.value}/sync/changes/fetch"

    /**
     * 返回 Change heads 测试 URL。
     *
     * @return 含随机工作区标识的相对 URL。
     */
    private fun headsUrl(): String = "/v1/workspaces/${WORKSPACE_ID.value}/sync/change-heads"

    private companion object {
        /** 测试工作区随机标识。 */
        val WORKSPACE_ID: WorkspaceId = WorkspaceId("workspace-a")

        /** 测试设备随机标识。 */
        val ACTOR_ID: ActorId = ActorId("device-a")

        /** 测试使用的 256 位以上高熵占位 Token。 */
        const val BEARER_TOKEN: String = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
