package com.oruke.tagloom.sync.server

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import java.nio.file.Files
import java.nio.file.Path

/** 服务端部署配置；不把客户端工作区密钥或设备私钥带入服务端。 */
data class SyncServerConfiguration(
    /** 服务端密文与公开设备登记目录。 */
    val dataRoot: Path,
    /** 独立部署者引导访问码的 SHA-256；空值关闭首次登记。 */
    val bootstrapTokenSha256: String? = null,
    /** 仅首次创建登记库时读取的旧版设备凭据。 */
    val legacyCredentialsFile: Path? = null,
    /** 单个密文恢复归档的字节上限。 */
    val maximumRecoveryBytes: Long = 512L * 1024 * 1024,
)

/**
 * 组装生产服务端及其持久化设备管理，测试使用同一入口验证重启行为。
 * @param configuration 部署配置。
 */
fun Application.configureSyncServer(configuration: SyncServerConfiguration) {
    require(configuration.maximumRecoveryBytes > 0) { "恢复归档容量限制无效" }
    val storage = DeviceRegistryStore(configuration.dataRoot)
    val registry = try {
        DeviceRegistryService(storage, configuration.bootstrapTokenSha256, configuration.legacyCredentialsFile)
    } catch (failure: Throwable) {
        storage.close()
        throw failure
    }
    monitor.subscribe(ApplicationStopped) { registry.close() }
    try {
        installSyncServer(JvmBlindSyncStore(configuration.dataRoot), registry)
        installSyncDeviceRoutes(registry, RecoveryArchiveStore(configuration.dataRoot, configuration.maximumRecoveryBytes))
    } catch (failure: Throwable) {
        registry.close()
        throw failure
    }
}

/** @return 从环境变量读取的服务端部署配置；旧凭据只承担一次性迁移。 */
internal fun syncServerConfigurationFromEnvironment(): SyncServerConfiguration {
    val root = System.getenv("TAGLOOM_SYNC_DATA_DIR")?.takeIf(String::isNotBlank)?.let(Path::of) ?: Path.of("data")
    val legacy = System.getenv("TAGLOOM_SYNC_CREDENTIALS_FILE")?.takeIf(String::isNotBlank)?.let(Path::of)
    val bootstrap = System.getenv("TAGLOOM_SYNC_BOOTSTRAP_TOKEN_SHA256")?.takeIf(String::isNotBlank)?.also(::requireSyncDigest)
    check(legacy != null || bootstrap != null || Files.exists(root.resolve(".server/devices.json"))) {
        "首次启动需配置 TAGLOOM_SYNC_BOOTSTRAP_TOKEN_SHA256 或旧版 TAGLOOM_SYNC_CREDENTIALS_FILE"
    }
    val limit = System.getenv("TAGLOOM_SYNC_MAX_RECOVERY_BYTES")?.let {
        it.toLongOrNull()?.takeIf { number -> number > 0 } ?: error("恢复归档容量配置无效")
    } ?: 512L * 1024 * 1024
    return SyncServerConfiguration(root, bootstrap, legacy, limit)
}
