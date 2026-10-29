package com.oruke.tagloom

import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.model.WorkspaceId
import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.sync.SyncDeviceRegistration
import com.oruke.tagloom.core.sync.SyncWorkspaceRegistration
import com.oruke.tagloom.sync.http.ServerRecoveryException
import com.oruke.tagloom.sync.http.jvm.ServerRecoveryClient
import com.oruke.tagloom.sync.server.SyncServerConfiguration
import com.oruke.tagloom.sync.server.configureSyncServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** 从真实客户端和 Netty 服务验证确认点读取、权限、下载与并发覆盖保护。 */
class RecoverySnapshotMetadataIntegrationTest {
    /** 未发布返回空；已发布描述与下载一致，重启保留，撤销设备无法再读取。 */
    @Test
    fun readsConfirmedMetadataAcrossRestartAndRejectsRevokedDevices() = MetadataServerFixture().use { fixture ->
        runBlocking {
            ServerRecoveryClient(fixture.address, allowLoopbackHttp = true).use { client ->
                client.register(fixture.workspaceId, fixture.bootstrap, fixture.registration())
                assertNull(client.latestSnapshot(fixture.workspaceId, fixture.firstActor, fixture.firstToken))
                assertEquals("authentication_failed", assertFailsWith<ServerRecoveryException> {
                    client.latestSnapshot(fixture.workspaceId, fixture.firstActor, UUID.randomUUID().toString())
                }.code)
                assertEquals("authentication_failed", assertFailsWith<ServerRecoveryException> {
                    client.latestSnapshot(WorkspaceId("unregistered-workspace"), fixture.firstActor, fixture.firstToken)
                }.code)

                val archive = fixture.archive("initial", ByteArray(64) { (it * 3).toByte() })
                val uploaded = client.upload(fixture.workspaceId, fixture.firstActor, fixture.firstToken, 0,
                    fixture.recoveryId, fixture.recoveryTokenHash, archive)
                assertEquals(uploaded, client.latestSnapshot(fixture.workspaceId, fixture.firstActor, fixture.firstToken))
                val downloaded = fixture.root.resolve("downloaded.enc")
                client.download(uploaded, fixture.firstActor, fixture.firstToken, downloaded)
                assertContentEquals(Files.readAllBytes(archive), Files.readAllBytes(downloaded))
                fixture.registerSecondDevice()

                // 登记与副本来自原持久化目录，不能靠测试进程内的对象掩盖重启丢失。
                fixture.restart()
                ServerRecoveryClient(fixture.address, allowLoopbackHttp = true).use { restarted ->
                    assertEquals(uploaded, restarted.latestSnapshot(fixture.workspaceId, fixture.secondActor, fixture.secondToken))
                    fixture.revokeFirstDevice()
                    assertEquals("authentication_failed", assertFailsWith<ServerRecoveryException> {
                        restarted.latestSnapshot(fixture.workspaceId, fixture.firstActor, fixture.firstToken)
                    }.code)
                    assertEquals(uploaded, restarted.latestSnapshot(fixture.workspaceId, fixture.secondActor, fixture.secondToken))
                }
            }
        }
    }

    /** 两设备读到同一旧描述后，过期上传必须失败且新确认点和归档保持一致。 */
    @Test
    fun rejectsStaleUploadsAfterMetadataReadWithoutOverwritingNewerSnapshot() = MetadataServerFixture().use { fixture ->
        runBlocking {
            ServerRecoveryClient(fixture.address, allowLoopbackHttp = true).use { client ->
                client.register(fixture.workspaceId, fixture.bootstrap, fixture.registration())
                fixture.registerSecondDevice()
                val initial = fixture.archive("base", ByteArray(64) { it.toByte() })
                val first = client.upload(fixture.workspaceId, fixture.firstActor, fixture.firstToken, 0,
                    fixture.recoveryId, fixture.recoveryTokenHash, initial)
                val stale = requireNotNull(client.latestSnapshot(fixture.workspaceId, fixture.secondActor, fixture.secondToken))
                assertEquals(first, stale)
                val changed = fixture.archive("changed", ByteArray(64) { (it + 7).toByte() })
                val second = client.upload(fixture.workspaceId, fixture.firstActor, fixture.firstToken, first.revision,
                    fixture.recoveryId, fixture.recoveryTokenHash, changed)
                val competing = fixture.archive("competing", ByteArray(64) { (it + 9).toByte() })
                assertEquals("recovery_revision_changed", assertFailsWith<ServerRecoveryException> {
                    client.upload(fixture.workspaceId, fixture.secondActor, fixture.secondToken, stale.revision,
                        fixture.recoveryId, fixture.recoveryTokenHash, competing)
                }.code)
                assertEquals(second, client.latestSnapshot(fixture.workspaceId, fixture.secondActor, fixture.secondToken))
                val downloaded = fixture.root.resolve("latest.enc")
                client.download(second, fixture.secondActor, fixture.secondToken, downloaded)
                assertContentEquals(Files.readAllBytes(changed), Files.readAllBytes(downloaded))
            }
        }
    }
}

/** 隔离目录、随机凭据与真实 TCP 服务；重启只替换进程状态而保留持久化数据。 */
private class MetadataServerFixture : AutoCloseable {
    /** 只由本测试创建并负责清理的受控根目录。 */
    val root: Path = Files.createTempDirectory("tagloom-recovery-metadata-").toAbsolutePath().normalize()
    /** 本次随机工作区身份。 */
    val workspaceId = WorkspaceId(UUID.randomUUID().toString())
    /** 首台设备身份。 */
    val firstActor = ActorId(UUID.randomUUID().toString())
    /** 第二台独立设备身份。 */
    val secondActor = ActorId(UUID.randomUUID().toString())
    /** 首次登记凭据，只在测试作用域保留。 */
    val bootstrap: String = UUID.randomUUID().toString()
    /** 首台设备独立访问凭据。 */
    val firstToken: String = UUID.randomUUID().toString()
    /** 第二台设备独立访问凭据。 */
    val secondToken: String = UUID.randomUUID().toString()
    /** 恢复用途的随机不透明定位符。 */
    val recoveryId: String = metadataDigest(UUID.randomUUID().toString().encodeToByteArray())
    /** 恢复访问凭据摘要，与设备访问凭据隔离。 */
    val recoveryTokenHash: String = metadataDigest(UUID.randomUUID().toString().encodeToByteArray())
    /** 当前服务实例，重启复用同一密文和登记目录。 */
    private var server = startServer()
    /** 管理请求使用标准 HTTP 客户端，不绕过真实设备路由。 */
    private val managementClient = HttpClient.newHttpClient()
    /** 实际监听的动态回环地址，仅测试客户端显式放开 HTTP。 */
    val address: String get() = runBlocking {
        "http://localhost:${server.engine.resolvedConnectors().single().port}"
    }

    /** @return 当前第一台设备的公开登记材料；不向服务器发送访问凭据明文。 */
    fun registration() = SyncWorkspaceRegistration(firstActor, metadataDigest(firstToken.encodeToByteArray()), "01".repeat(32))

    /**
     * 将脱敏密文样例写入测试目录供正式流式上传使用。
     * @param name 仅由测试控制的文件代号。
     * @param bytes 待验证流式往返的样例密文字节。
     * @return 测试拥有的归档源路径。
     */
    fun archive(name: String, bytes: ByteArray): Path = root.resolve("$name.enc").also { Files.write(it, bytes) }

    /** 通过第一台设备的真实授权请求登记第二台设备。 */
    fun registerSecondDevice() {
        val registration = SyncDeviceRegistration(metadataDigest(secondToken.encodeToByteArray()), "02".repeat(32))
        val request = HttpRequest.newBuilder(URI("$address/v1/workspaces/${workspaceId.value}/devices/${secondActor.value}"))
            .header("Authorization", "Bearer $firstToken").header("X-Tagloom-Actor", firstActor.value)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(CanonicalJson.encodeToString(SyncDeviceRegistration.serializer(), registration)))
            .build()
        assertEquals(200, managementClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode())
    }

    /** 使用第二台活动设备撤销第一台，随后必须由路由重新验证访问凭据。 */
    fun revokeFirstDevice() {
        val request = HttpRequest.newBuilder(URI("$address/v1/workspaces/${workspaceId.value}/devices/${firstActor.value}"))
            .header("Authorization", "Bearer $secondToken").header("X-Tagloom-Actor", secondActor.value)
            .DELETE().build()
        assertEquals(204, managementClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode())
    }

    /** 释放旧登记库写入锁，然后从同一目录启动新服务。 */
    fun restart() {
        server.stop(0, 1_000)
        server = startServer()
    }

    /** @return 按正式配置入口装配并绑定随机端口的 Netty 服务。 */
    private fun startServer() = embeddedServer(Netty, host = InetAddress.getLoopbackAddress().hostAddress, port = 0) {
        configureSyncServer(SyncServerConfiguration(root.resolve("server"), metadataDigest(bootstrap.encodeToByteArray())))
    }.start(wait = false)

    /** 先释放网络与文件句柄，再检查测试目录归属并清理。 */
    override fun close() {
        server.stop(0, 1_000)
        check(root.parent == Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize())
        check(root.fileName.toString().startsWith("tagloom-recovery-metadata-"))
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}

/**
 * 计算协议摘要；不得输出随机凭据或请求头。
 * @param bytes 用于登记或归档完整性验证的字节。
 * @return 小写 SHA-256 十六进制文本。
 */
private fun metadataDigest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
