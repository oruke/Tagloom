package com.oruke.tagloom

import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.sync.*
import com.oruke.tagloom.crypto.libsodium.LibsodiumCryptoProvider
import com.oruke.tagloom.sync.server.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlin.test.*

/** 从生产服务装配入口验证设备授权、密文传输、持久化撤销和独立恢复。 */
class SyncDeviceRecoveryFlowTest {
    /** 两台设备真实 HTTP 交换后轮换凭据、撤销并重启；无活动设备时仍能凭恢复认证找回。 */
    @Test
    fun enrollmentRotationRevocationAndRecoverySurviveRestart() = ManagementServerFixture().use { f ->
        val replacement = UUID.randomUUID().toString()
        val archive = ByteArray(256) { (it * 7).toByte() }
        val recoveryId = f.digest(UUID.randomUUID().toString())
        val recoveryToken = UUID.randomUUID().toString()
        val signing = runBlocking { f.crypto.initialize(); f.crypto.generateDeviceSigningKeyPair() }
        val signed = runBlocking {
            val payload = ByteArray(96) { (it * 13).toByte() }
            val descriptor = SyncArtifactDescriptor(CURRENT_SYNC_PROTOCOL_VERSION, f.workspace, SyncArtifactKind.CHANGE,
                ObjectId("sample-note"), emptyList(), f.actor, 1, JvmSyncArtifactVerifier.sha256(payload), payload.size)
            DeviceArtifactSignatureService(f.crypto).sign(BlindSyncArtifact(
                artifactId = JvmSyncArtifactVerifier.descriptorContentId(descriptor), descriptor = descriptor,
                payloadBase64 = Base64.getEncoder().encodeToString(payload),
            ), signing.privateKeySeed)
        }
        try {
            f.server {
                assertEquals(HttpStatusCode.OK, f.create(client, signing.copyPublicKey()).status)
                assertEquals(HttpStatusCode.OK, f.create(client, signing.copyPublicKey()).status)
                val second = SyncDeviceRegistration(f.digest(f.secondaryToken), f.hex(signing.copyPublicKey()))
                assertEquals(HttpStatusCode.OK, f.request(client, "/devices/device-b", HttpMethod.Put, body = CanonicalJson.format.encodeToString(second)).status)
                val page = f.request(client, "/devices?limit=1").body<SyncDevicePage>()
                assertEquals(listOf(f.actor), page.devices.map { it.actorId })
                assertEquals("device-b", f.request(client, "/devices?limit=1&cursor=${page.nextCursor}").body<SyncDevicePage>().devices.single().actorId.value)
                assertEquals(HttpStatusCode.OK, f.request(client, "/sync/changes/push", HttpMethod.Post,
                    body = CanonicalJson.format.encodeToString(SyncPushRequest(listOf(signed)))).status)
                val fetched = f.request(client, "/sync/changes/fetch", HttpMethod.Post, actor = "device-b", token = f.secondaryToken,
                    body = CanonicalJson.format.encodeToString(SyncFetchRequest(listOf(signed.artifactId)))).body<SyncFetchResponse>()
                assertEquals(listOf(signed), fetched.artifacts)
                val update = SyncCredentialUpdate(f.digest(f.primaryToken), f.digest(replacement))
                assertEquals(HttpStatusCode.OK, f.request(client, "/devices/device-a/credentials", HttpMethod.Put,
                    body = CanonicalJson.format.encodeToString(update)).status)
                assertEquals(HttpStatusCode.Unauthorized, f.request(client, "/devices").status)
                assertEquals(HttpStatusCode.NoContent, f.request(client, "/devices/device-a", HttpMethod.Delete,
                    actor = "device-b", token = f.secondaryToken).status)
                // 已撤销作者的已入库历史仍可幂等转发，但不能借活动设备上传新签名载荷。
                assertEquals(HttpStatusCode.OK, f.request(client, "/sync/changes/push", HttpMethod.Post, actor = "device-b", token = f.secondaryToken,
                    body = CanonicalJson.format.encodeToString(SyncPushRequest(listOf(signed)))).status)
                val later = signed.copy(descriptor = signed.descriptor.copy(actorSequence = 2), authorSignatureHex = null)
                val newlySigned = DeviceArtifactSignatureService(f.crypto).sign(later.copy(artifactId = JvmSyncArtifactVerifier.descriptorContentId(later.descriptor)), signing.privateKeySeed)
                assertEquals(HttpStatusCode.Forbidden, f.request(client, "/sync/changes/push", HttpMethod.Post, actor = "device-b", token = f.secondaryToken,
                    body = CanonicalJson.format.encodeToString(SyncPushRequest(listOf(newlySigned)))).status)
                assertEquals(HttpStatusCode.Conflict, f.request(client, "/devices/device-b", HttpMethod.Delete, actor = "device-b", token = f.secondaryToken).status)
                val uploaded = f.upload(client, archive, recoveryId, recoveryToken).body<SyncRecoverySnapshot>()
                assertEquals(1L, uploaded.revision)
                assertEquals(1L, f.upload(client, archive, recoveryId, recoveryToken).body<SyncRecoverySnapshot>().revision)
                assertEquals(HttpStatusCode.Conflict, f.upload(client, archive + 1, recoveryId, recoveryToken).status)
                assertEquals(HttpStatusCode.NoContent, f.request(client, "/devices/device-b", HttpMethod.Delete, actor = "device-b", token = f.secondaryToken).status)
            }
            f.server {
                assertEquals(HttpStatusCode.Unauthorized, f.request(client, "/devices", token = replacement).status)
                assertEquals(HttpStatusCode.Unauthorized, f.request(client, "/devices", actor = "device-b", token = f.secondaryToken).status)
                val registration = CanonicalJson.format.encodeToString(SyncDeviceRegistration(f.digest(replacement), f.hex(signing.copyPublicKey())))
                val wrong = client.put("/v1/recoveries/$recoveryId/devices/device-c") { bearerAuth(f.primaryToken); contentType(ContentType.Application.Json); setBody(registration) }
                assertEquals(HttpStatusCode.Unauthorized, wrong.status)
                val recovered = client.put("/v1/recoveries/$recoveryId/devices/device-c") { bearerAuth(recoveryToken); contentType(ContentType.Application.Json); setBody(registration) }.body<SyncRecoveredWorkspace>()
                assertEquals(f.workspace, recovered.workspaceId)
                assertEquals(1L, recovered.snapshot.revision)
                val downloaded = f.request(client, "/recovery-snapshot", actor = "device-c", token = replacement)
                assertContentEquals(archive, downloaded.bodyAsBytes())
                val devices = f.request(client, "/devices", actor = "device-c", token = replacement).body<SyncDevicePage>().devices
                assertEquals(2, devices.count { it.revokedAt != null })
                assertEquals(f.hex(signing.copyPublicKey()), devices.first { it.actorId == f.actor }.signingPublicKeyHex)
            }
        } finally { signing.destroyPrivateKey() }
    }

    /** 校验越权、非法分页、请求体上限、损坏归档与更新冲突均不发布坏状态。 */
    @Test
    fun rejectsInvalidRequestsAndPreservesConfirmedArchive() = ManagementServerFixture(maximumBytes = 256).use { f ->
        f.server {
            f.crypto.initialize()
            val key = f.crypto.generateDeviceSigningKeyPair()
            try {
                assertEquals(HttpStatusCode.OK, f.create(client, key.copyPublicKey()).status)
                assertEquals(HttpStatusCode.BadRequest, f.request(client, "/devices?limit=0").status)
                assertEquals(HttpStatusCode.BadRequest, f.request(client, "/devices?cursor=broken").status)
                val oversized = f.request(client, "/devices/device-b", HttpMethod.Put, body = " ".repeat(8193))
                assertEquals(HttpStatusCode.PayloadTooLarge, oversized.status)
                assertEquals("application/problem+json", oversized.contentType()?.withoutParameters().toString())
                assertEquals(413, oversized.body<SyncApiError>().status)
                assertEquals(HttpStatusCode.BadRequest, f.request(client, "/devices/device-b", HttpMethod.Put, body = "{").status)
                val foreign = client.get("/v1/workspaces/unrelated/devices") { bearerAuth(f.primaryToken); header("X-Tagloom-Actor", f.actor.value) }
                assertEquals(HttpStatusCode.Unauthorized, foreign.status)
                val id = f.digest("recovery-locator-sample")
                val upload = suspend { bytes: ByteArray, hash: String ->
                    client.put("/v1/workspaces/${f.workspace.value}/recovery-snapshot") {
                        bearerAuth(f.primaryToken); header("X-Tagloom-Actor", f.actor.value)
                        header("X-Tagloom-Recovery-Revision", 0); header("X-Tagloom-Recovery-Id", id)
                        header("X-Tagloom-Recovery-Token-Sha256", id); header("X-Tagloom-Archive-Sha256", hash)
                        setBody(bytes)
                    }
                }
                assertEquals(HttpStatusCode.BadRequest, upload(byteArrayOf(1, 2), id).status)
                assertEquals(HttpStatusCode.PayloadTooLarge, upload(ByteArray(257), id).status)
                assertEquals(HttpStatusCode.NotFound, f.request(client, "/recovery-snapshot").status)
                assertEquals(HttpStatusCode.OK, upload(byteArrayOf(1, 2), f.digest(byteArrayOf(1, 2))).status)
                assertContentEquals(byteArrayOf(1, 2), f.request(client, "/recovery-snapshot").bodyAsBytes())
            } finally { key.destroyPrivateKey() }
        }
    }
}

/** 生产服务的独立临时部署；测试中动态生成访问材料，关闭后仅清理自己的目录。 */
private class ManagementServerFixture(
    /** 本次测试的恢复归档限制。 */
    val maximumBytes: Long = 4096,
) : AutoCloseable {
    /** 隔离的服务数据。 */
    val root: Path = Files.createTempDirectory("tagloom-device-http-")
    /** 真实密码实现。 */
    val crypto = LibsodiumCryptoProvider()
    /** 首次授权随机凭据。 */
    val bootstrap = UUID.randomUUID().toString()
    /** 首台设备随机凭据。 */
    val primaryToken = UUID.randomUUID().toString()
    /** 次台设备随机凭据。 */
    val secondaryToken = UUID.randomUUID().toString()
    /** 脱敏工作区身份。 */
    val workspace = WorkspaceId("workspace-sample")
    /** 脱敏首台设备身份。 */
    val actor = ActorId("device-a")

    /**
     * 启动并停止一次生产装配服务，支持相同数据目录真实重启。
     * @param block HTTP 验证流程。
     */
    fun server(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { configureSyncServer(SyncServerConfiguration(root, digest(bootstrap), maximumRecoveryBytes = maximumBytes)) }
        block()
    }

    /**
     * 从真实首次登记接口创建工作区。
     * @param client 测试 HTTP 客户端。
     * @param key 首台设备签名公钥。
     * @return HTTP 响应。
     */
    suspend fun create(client: HttpClient, key: ByteArray) = client.put("/v1/workspaces/${workspace.value}") {
        bearerAuth(bootstrap); contentType(ContentType.Application.Json)
        setBody(CanonicalJson.format.encodeToString(SyncWorkspaceRegistration(actor, digest(primaryToken), hex(key))))
    }

    /**
     * 构造设备授权请求。
     * @param client 测试 HTTP 客户端。
     * @param suffix 工作区内的目标路径。
     * @param method HTTP 方法。
     * @param actor 调用设备。
     * @param token 此设备当前凭据。
     * @param body 可选 JSON 请求体。
     * @return HTTP 响应。
     */
    suspend fun request(client: HttpClient, suffix: String, method: HttpMethod = HttpMethod.Get, actor: String = this.actor.value,
                        token: String = primaryToken, body: String? = null) = client.request("/v1/workspaces/${workspace.value}$suffix") {
        this.method = method; bearerAuth(token); header("X-Tagloom-Actor", actor)
        body?.let { contentType(ContentType.Application.Json); setBody(it) }
    }

    /**
     * 由第二台设备上传首次恢复副本。
     * @param client HTTP 客户端。
     * @param bytes 测试归档的不透明二进制内容。
     * @param recoveryId 随机恢复定位符。
     * @param recoveryToken 动态生成的恢复凭据。
     * @return HTTP 响应。
     */
    suspend fun upload(client: HttpClient, bytes: ByteArray, recoveryId: String, recoveryToken: String) =
        client.put("/v1/workspaces/${workspace.value}/recovery-snapshot") {
            bearerAuth(secondaryToken); header("X-Tagloom-Actor", "device-b")
            header("X-Tagloom-Recovery-Revision", 0); header("X-Tagloom-Recovery-Id", recoveryId)
            header("X-Tagloom-Recovery-Token-Sha256", digest(recoveryToken)); header("X-Tagloom-Archive-Sha256", digest(bytes))
            setBody(bytes)
        }

    /** @param text 待摘要的脱敏文本。 @return 协议摘要。 */
    fun digest(text: String): String = digest(text.encodeToByteArray())
    /** @param bytes 待摘要的不透明字节。 @return SHA-256 十六进制。 */
    fun digest(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    /** @param bytes 待编码的测试字节。 @return 小写十六进制。 */
    fun hex(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    /** 只清理本测试构造的临时目录，所有服务器实例已停止。 */
    override fun close() {
        check(root.fileName.toString().startsWith("tagloom-device-http-"))
        Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}

/** @return 当前 HTTP 响应经共享协议解码的类型。 */
private suspend inline fun <reified T> HttpResponse.body(): T = CanonicalJson.format.decodeFromString(bodyAsText())
