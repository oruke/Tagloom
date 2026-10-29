package com.oruke.tagloom.workspace

import com.oruke.tagloom.capability.TagloomCapabilities
import com.oruke.tagloom.core.crypto.Argon2idParameters
import com.oruke.tagloom.filesystem.jvm.JvmWorkspaceFileSystem
import com.oruke.tagloom.filesystem.jvm.crypto.LibsodiumCryptoProvider
import com.oruke.tagloom.sync.createSyncEngine
import com.oruke.tagloom.sync.http.jvm.ServerRecoveryClient
import com.oruke.tagloom.sync.server.SyncServerConfiguration
import com.oruke.tagloom.sync.server.configureSyncServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID

/** 独立临时目录和真实回环 Netty 服务，用于客户端恢复及 Compose 流程验收。 */
internal class ServerRecoveryTestEnvironment : AutoCloseable {
    /** 所有测试文件的唯一受控根目录。 */
    val root: Path = Files.createTempDirectory("tagloom-recovery-e2e-").toAbsolutePath().normalize()
    /** 真实服务端保存数据的目录。 */
    val serverRoot: Path = root.resolve("server")
    /** 测试时动态生成的登记访问码，禁止写入报告。 */
    val bootstrap: String = UUID.randomUUID().toString()
    /** 仅测试使用的真实 HTTP 请求计数与单次阻塞点，默认不阻塞任何请求。 */
    val requests = ServerRecoveryRequestProbe()
    /** 随机端口服务实例，使用正式装配入口。 */
    private var server = startServer(0)
    /** 实际绑定的回环地址；生产客户端仍要求 HTTPS。 */
    val address: String = runBlocking {
        val port = server.engine.resolvedConnectors().single().port
        "http://localhost:$port"
    }
    /** 所有真实客户端，退出时逐一销毁会话。 */
    private val devices = mutableListOf<ServerRecoveryTestDevice>()

    /** 停止真实 TCP 服务，验证断网不会影响本地写入。 */
    fun disconnect() { server.stop(100, 2000) }

    /** 在同一地址用原持久化数据重新启动服务，模拟网络恢复。 */
    fun reconnect() { server = startServer(java.net.URI(address).port) }

    /**
     * 使用正式服务装配启动测试服务，重启复用原设备登记和密文存储。
     * @param port 零表示选择可用端口，重启时使用原端口。
     * @return 已启动的真实服务。
     */
    private fun startServer(port: Int) = embeddedServer(Netty, host = InetAddress.getLoopbackAddress().hostAddress, port = port) {
        install(createApplicationPlugin("ServerRecoveryTestProbe") {
            onCall { call -> requests.observe(call.request.httpMethod.value, call.request.path()) }
        })
        configureSyncServer(SyncServerConfiguration(serverRoot, MessageDigest.getInstance("SHA-256").digest(bootstrap.encodeToByteArray()).serverRecoveryHex()))
    }.start(wait = false)

    /**
     * 创建与其他设备完全隔离的本机登记和工作区目录。
     * @param name 脱敏设备代号。
     * @param currentTimeMillis 当前设备的物理时钟，默认使用系统；可验证恢复后的时钟回拨。
     * @return 使用正式网关、DPAPI、libsodium、SQLite 和 HTTP 传输的客户端。
     */
    fun device(name: String, currentTimeMillis: () -> Long = System::currentTimeMillis): ServerRecoveryTestDevice =
        ServerRecoveryTestDevice(Files.createDirectory(root.resolve(name)), currentTimeMillis)
        .also { devices += it }

    /** 关闭客户端、网络和数据库后只删除本测试明确创建的目录。 */
    override fun close() {
        requests.releaseAll()
        devices.forEach { it.close() }
        server.stop(100, 2000)
        check(root.parent == Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize())
        check(root.fileName.toString().startsWith("tagloom-recovery-e2e-"))
        Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}

/**
 * 真实独立设备装配；仅目录选择、测试 KDF 与可控物理时钟允许注入。
 * @param root 当前设备独占的测试目录。
 * @param currentTimeMillis HLC 使用的物理时间，不影响实际网关、密码学和存储路径。
 */
internal class ServerRecoveryTestDevice(
    /** 当前设备私有的测试数据根目录。 */
    val root: Path,
    currentTimeMillis: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    /** 新建或恢复目标，始终从空目录开始。 */
    val workspaceRoot: Path = root.resolve("workspace")
    /** 测试明确选择的既有目录，可指向另一台已关闭设备的工作区。 */
    var recoveryRoot: Path = workspaceRoot
    /** 记录本地新建的选择次数，服务器模式必须保持为零。 */
    var creationSelections: Int = 0
    /** 记录本地恢复的选择次数，证明实际走了文件夹入口。 */
    var recoverySelections: Int = 0
    /** 正式密码学实现。 */
    private val crypto = LibsodiumCryptoProvider()
    /** 与正式入口相同的会话管理器。 */
    val manager = DesktopWorkspaceManager(cryptoProvider = crypto, currentTimeMillis = currentTimeMillis, argon2idFactory = ::testRecoveryKdf)
    /** 真实受保护的本机登记。 */
    private val access = DesktopWorkspaceAccessStore(root.resolve("access.json"), cryptoProvider = crypto, argon2idFactory = ::testRecoveryKdf)
    /** 复用生产恢复路径；测试只放开字面回环 HTTP。 */
    val gateway = DesktopWorkspaceGateway(manager, DesktopWorkspaceLocalAccessService(access), object : WorkspaceDocumentAccess {
        /** @return 独立的新建或恢复目标。 */
        override suspend fun creationDirectory(): Path { creationSelections++; return workspaceRoot }
        /** @return 同一测试本地工作区。 */
        override suspend fun recoveryDirectory(): Path { recoverySelections++; return recoveryRoot }
        /** @param writeArchive 正式密文导出函数。 @return 导出成功。 */
        override suspend fun exportBackup(writeArchive: suspend (Path) -> Unit): Boolean { writeArchive(root.resolve("backup.zip")); return true }
        /** @param restore 正式恢复函数。 @return 此测试不触发本地文件导入。 */
        override suspend fun <T> importBackup(restore: suspend (Path, Path) -> T): T? = null
    }, recoveryAccessDirectory = root.resolve("remote"), recoveryClientFactory = { ServerRecoveryClient(it, allowLoopbackHttp = true) })
    /** 使用正式会话索引，验证恢复后的真实可查询状态。 */
    private val index = JvmWorkspaceIndexEngine(gateway)
    /** 生产 App 根节点使用的能力集合。 */
    val capabilities = TagloomCapabilities(gateway, JvmWorkspaceFileSystem(workspaceRoot), index, index, createSyncEngine())

    /** 关闭会话与网络能力，保证测试退出时不留下写入者或 SQLite 文件锁。 */
    override fun close() = runBlocking { gateway.lock(); capabilities.syncEngine.close() }
}

/** @param salt 实际随机 salt。 @return 仅测试使用的低成本 KDF 参数。 */
private fun testRecoveryKdf(salt: ByteArray) = Argon2idParameters(salt, memoryKiB = 8, iterations = 1, parallelism = 1)
