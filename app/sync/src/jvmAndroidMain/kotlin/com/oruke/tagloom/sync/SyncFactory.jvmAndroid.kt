package com.oruke.tagloom.sync

import com.oruke.tagloom.core.crypto.CryptoProvider
import com.oruke.tagloom.core.crypto.DeviceSignatureProvider
import com.oruke.tagloom.core.sync.BlindArtifactSyncService
import com.oruke.tagloom.core.sync.ChangeDagSyncService
import com.oruke.tagloom.core.sync.DeviceArtifactSignatureService
import com.oruke.tagloom.core.sync.SyncArtifactIntegrityService
import com.oruke.tagloom.core.sync.SyncDevicePublicKeyResolver
import com.oruke.tagloom.filesystem.jvm.JvmDirectoryDurability
import com.oruke.tagloom.filesystem.jvm.JvmDirectoryDurabilityFactory
import com.oruke.tagloom.sync.http.jvm.JvmBase64SyncPayloadCodec
import com.oruke.tagloom.sync.http.jvm.JvmFileBlindArtifactReplica
import com.oruke.tagloom.sync.http.jvm.JvmFileChangeReplica
import com.oruke.tagloom.sync.http.KtorBlindSyncTransport
import com.oruke.tagloom.sync.http.SyncAccessTokenProvider
import com.oruke.tagloom.sync.jvm.JvmSyncEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import java.net.URI
import java.nio.file.Path

/**
 * 描述 JVM 与 Android 共用的本地同步能力装配参数。
 *
 * 工作区密钥和设备私钥不属于同步传输本身；调用方通过已初始化的密码学与签名端口提供它们，并负责其会话生命周期。
 */
data class LocalSyncEngineConfiguration(
    /** 当前工作区本地同步副本根目录。 */
    val workspaceRoot: Path,
    /** 当前工作区随机身份。 */
    val workspaceId: com.oruke.tagloom.core.model.WorkspaceId,
    /** 当前同步服务根 URI；默认必须使用 HTTPS。 */
    val serverBaseUri: URI,
    /** 当前解锁会话的 Bearer Token 提供者。 */
    val tokenProvider: SyncAccessTokenProvider,
    /** 按工作区和 Actor 解析历史签名公钥。 */
    val publicKeyResolver: SyncDevicePublicKeyResolver,
    /** 提供 SHA-256 等同步公开完整性原语。 */
    val cryptoProvider: CryptoProvider,
    /** 提供设备 Ed25519 签名验证原语。 */
    val signatureProvider: DeviceSignatureProvider,
    /** 是否明确允许 HTTP；仅用于受信任的本机测试代理。 */
    val allowInsecureTransport: Boolean = false,
    /** 执行阻塞文件和网络操作的调度器。 */
    val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** 跨平台目录元数据刷新实现。 */
    val directoryDurability: JvmDirectoryDurability = JvmDirectoryDurabilityFactory.create(),
)

/**
 * JVM 与 Android 的无上下文同步工厂。
 *
 * 不猜测工作区路径、Token 或设备公钥；真实调用方通过带 [LocalSyncEngineConfiguration] 的重载传入完整配置。
 *
 * @return 未配置的同步能力，调用同步时稳定失败并提示显式装配入口。
 */
actual fun createSyncEngine(): SyncEngine = JvmSyncEngine.unconfigured()

/**
 * 使用显式配置创建 JVM/Android Change DAG 与密文 artifact 同步能力。
 *
 * @param configuration 工作区、服务端、认证和密码学配置。
 * @return 已装配的本地同步引擎。
 */
fun createSyncEngine(configuration: LocalSyncEngineConfiguration): JvmSyncEngine {
    val payloadCodec = JvmBase64SyncPayloadCodec
    val integrityService = SyncArtifactIntegrityService(configuration.cryptoProvider, payloadCodec)
    val signatureService = DeviceArtifactSignatureService(configuration.signatureProvider)
    val transport = KtorBlindSyncTransport(
        serverBaseUri = configuration.serverBaseUri.toString(),
        tokenProvider = configuration.tokenProvider,
        allowInsecureTransport = configuration.allowInsecureTransport,
        httpClient = HttpClient(CIO) {
            followRedirects = false
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 30_000
                socketTimeoutMillis = 30_000
            }
        },
    )
    val changeReplica = JvmFileChangeReplica(
        workspaceRoot = configuration.workspaceRoot,
        workspaceId = configuration.workspaceId,
        integrityService = integrityService,
        directoryDurability = configuration.directoryDurability,
        dispatcher = configuration.dispatcher,
    )
    val artifactReplica = JvmFileBlindArtifactReplica(
        workspaceRoot = configuration.workspaceRoot,
        workspaceId = configuration.workspaceId,
        integrityService = integrityService,
        directoryDurability = configuration.directoryDurability,
        dispatcher = configuration.dispatcher,
    )
    val changeSyncService = ChangeDagSyncService(
        localReplica = changeReplica,
        transport = transport,
        integrityService = integrityService,
        signatureService = signatureService,
        publicKeyResolver = configuration.publicKeyResolver,
    )
    val artifactSyncService = BlindArtifactSyncService(
        localReplica = artifactReplica,
        transport = transport,
        integrityService = integrityService,
        signatureService = signatureService,
        publicKeyResolver = configuration.publicKeyResolver,
    )
    return JvmSyncEngine.create(
        changeSyncService = changeSyncService,
        artifactSyncService = artifactSyncService,
        workspaceId = configuration.workspaceId,
        cryptoProvider = configuration.cryptoProvider,
        closeTransport = transport::close,
    )
}
