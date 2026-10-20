package com.oruke.tagloom

import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.sync.SyncWorkspaceRegistration
import io.ktor.server.netty.EngineMain
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 从生产 EngineMain 入口验证默认 YAML、外部文件、覆盖参数、持久化及非法配置拒绝。 */
class SyncServerYamlConfigurationTest {
    /** 默认 YAML 加载真实模块；命令行覆盖生效，外部 YAML 重启后仍可下载已确认密文。 */
    @Test
    fun persistsRecoveryAcrossDefaultAndExternalYamlDeployments() = withYamlDirectory { root ->
        val dataRoot = root.resolve("ciphertext")
        val bootstrap = UUID.randomUUID().toString()
        val deviceToken = UUID.randomUUID().toString()
        val archive = ByteArray(64) { (it * 3).toByte() }
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        val workspacePath = "/v1/workspaces/yaml-workspace"

        // 阶段一：默认资源负责模块装配，命令行仅覆盖本次隔离部署的参数。
        withYamlServer(arrayOf(
            "-host=localhost", "-port=0",
            "-P:tagloom.sync.dataDir=$dataRoot",
            "-P:tagloom.sync.bootstrapTokenSha256=${yamlDigest(bootstrap.encodeToByteArray())}",
            "-P:tagloom.sync.credentialsFile=",
            "-P:tagloom.sync.maxRecoveryBytes=64",
        )) { base ->
            val health = client.send(HttpRequest.newBuilder(base.resolve("/")).GET().build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, health.statusCode())
            assertTrue(health.body().contains("\"status\":\"ok\""))
            val registration = SyncWorkspaceRegistration(
                ActorId("yaml-device"), yamlDigest(deviceToken.encodeToByteArray()), "01".repeat(32),
            )
            val enrollment = HttpRequest.newBuilder(base.resolve(workspacePath))
                .header("Authorization", "Bearer $bootstrap")
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(CanonicalJson.format.encodeToString(registration)))
                .build()
            assertEquals(200, client.send(enrollment, HttpResponse.BodyHandlers.ofString()).statusCode())

            val recoveryPath = base.resolve("$workspacePath/recovery-snapshot")
            val locator = yamlDigest(UUID.randomUUID().toString().encodeToByteArray())
            val upload = HttpRequest.newBuilder(recoveryPath)
                .header("Authorization", "Bearer $deviceToken")
                .header("X-Tagloom-Actor", "yaml-device")
                .header("X-Tagloom-Recovery-Revision", "0")
                .header("X-Tagloom-Recovery-Id", locator)
                .header("X-Tagloom-Recovery-Token-Sha256", locator)
                .header("X-Tagloom-Archive-Sha256", yamlDigest(archive))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(archive)).build()
            assertEquals(200, client.send(upload, HttpResponse.BodyHandlers.ofString()).statusCode())
        }
        assertTrue(Files.isRegularFile(dataRoot.resolve(".server/devices.json")))

        // 阶段二：外部 YAML 关闭新的首次登记，复用真实登记库并缩小上传上限。
        val file = writeYamlDeployment(root, maximumBytes = "32")
        withYamlServer(arrayOf("-config=$file")) { base ->
            val download = HttpRequest.newBuilder(base.resolve("$workspacePath/recovery-snapshot"))
                .header("Authorization", "Bearer $deviceToken").header("X-Tagloom-Actor", "yaml-device")
                .GET().build()
            val response = client.send(download, HttpResponse.BodyHandlers.ofByteArray())
            assertEquals(200, response.statusCode())
            assertContentEquals(archive, response.body())

            val locator = yamlDigest(UUID.randomUUID().toString().encodeToByteArray())
            val oversized = HttpRequest.newBuilder(download.uri())
                .header("Authorization", "Bearer $deviceToken").header("X-Tagloom-Actor", "yaml-device")
                .header("X-Tagloom-Recovery-Revision", "1")
                .header("X-Tagloom-Recovery-Id", locator)
                .header("X-Tagloom-Recovery-Token-Sha256", locator)
                .header("X-Tagloom-Archive-Sha256", yamlDigest(archive))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(archive)).build()
            assertEquals(413, client.send(oversized, HttpResponse.BodyHandlers.ofString()).statusCode())
            assertContentEquals(archive, client.send(download, HttpResponse.BodyHandlers.ofByteArray()).body())
        }
    }

    /** 缺少首次登记材料时拒绝启动，且不创建空登记库掩盖配置错误。 */
    @Test
    fun rejectsUnconfiguredFirstDeployment() = withYamlDirectory { root ->
        val file = writeYamlDeployment(root)
        assertFails { withYamlServer(arrayOf("-config=$file")) { } }
        assertFalse(Files.exists(root.resolve("ciphertext")))
    }

    /** 非法摘要不能进入登记服务，错误配置不产生业务目录。 */
    @Test
    fun rejectsMalformedBootstrapDigest() = withYamlDirectory { root ->
        val file = writeYamlDeployment(root, bootstrapDigest = "invalid-digest")
        assertFails { withYamlServer(arrayOf("-config=$file")) { } }
        assertFalse(Files.exists(root.resolve("ciphertext")))
    }

    /** 零、负数、非整数及 Long 溢出不能成为恢复归档容量上限。 */
    @Test
    fun rejectsInvalidRecoveryLimits() = withYamlDirectory { root ->
        for (limit in listOf("0", "-1", "not-a-number", "9223372036854775808")) {
            val file = writeYamlDeployment(root, bootstrapDigest = "01".repeat(32), maximumBytes = limit)
            assertFails { withYamlServer(arrayOf("-config=$file")) { } }
            assertFalse(Files.exists(root.resolve("ciphertext")))
        }
    }

    /** 空白数据目录不能隐式回退到进程当前目录。 */
    @Test
    fun rejectsBlankDataDirectory() = withYamlDirectory { root ->
        val file = writeYamlDeployment(root, bootstrapDigest = "01".repeat(32), dataDirectory = " ")
        assertFails { withYamlServer(arrayOf("-config=$file")) { } }
        assertFalse(Files.exists(root.resolve("ciphertext")))
    }
}

/**
 * 通过与 main 相同的 EngineMain 配置解析和自动模块装配运行真实 Netty 服务。
 * @param arguments 本次部署的 Ktor 命令行参数。
 * @param block 使用实际监听地址执行的 HTTP 验证；返回后关闭服务并释放文件锁。
 */
private fun withYamlServer(arguments: Array<String>, block: (URI) -> Unit) {
    val server = EngineMain.createServer(arguments)
    try {
        server.start(wait = false)
        val connector = runBlocking { server.engine.resolvedConnectors().single() }
        block(URI("http", null, connector.host, connector.port, null, null, null))
    } finally {
        server.stop(0, 1_000)
    }
}

/**
 * 在测试拥有的目录写入完整外部 YAML；JSON 字符串编码同时满足 YAML 双引号转义。
 * @param root 测试拥有的配置与数据父目录。
 * @param bootstrapDigest 可选的登记摘要，空值表示关闭首次登记。
 * @param maximumBytes 用于验证数值边界的原始容量配置。
 * @param dataDirectory 数据目录配置，默认指向隔离目录中的 ciphertext。
 * @return 新写入的 YAML 文件路径。
 */
private fun writeYamlDeployment(
    root: Path,
    bootstrapDigest: String = "",
    maximumBytes: String = "64",
    dataDirectory: String = root.resolve("ciphertext").toString(),
): Path = root.resolve("deployment.yaml").also { file ->
    Files.writeString(file, """
        ktor:
          application:
            modules:
              - com.oruke.tagloom.ApplicationKt.module
          deployment:
            host: localhost
            port: 0
        tagloom:
          sync:
            dataDir: ${CanonicalJson.format.encodeToString(dataDirectory)}
            bootstrapTokenSha256: ${CanonicalJson.format.encodeToString(bootstrapDigest)}
            credentialsFile: ""
            maxRecoveryBytes: ${CanonicalJson.format.encodeToString(maximumBytes)}
    """.trimIndent())
}

/**
 * 为 HTTP 协议生成摘要，不在输出中暴露随机访问凭据。
 * @param bytes 需要校验的字节。
 * @return 小写 SHA-256 十六进制文本。
 */
private fun yamlDigest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

/**
 * 隔离真实配置与存储文件，清理前验证绝对路径只能位于本次临时目录。
 * @param block 使用隔离目录执行的完整部署验证。
 */
private fun withYamlDirectory(block: (Path) -> Unit) {
    val root = Files.createTempDirectory("tagloom-yaml-test-").toAbsolutePath().normalize()
    try {
        block(root)
    } finally {
        check(root.parent == Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize())
        check(root.fileName.toString().startsWith("tagloom-yaml-test-"))
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}
