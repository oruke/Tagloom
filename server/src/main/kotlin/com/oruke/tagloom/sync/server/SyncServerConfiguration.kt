package com.oruke.tagloom.sync.server

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.config.ApplicationConfig
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

/**
 * 将 Ktor 已加载的部署配置映射为服务参数，并在创建存储前完成校验。
 * @receiver YAML、环境变量替换与命令行覆盖合并后的 Ktor 配置。
 * @return 可用于生产装配的服务配置；首次启动仍需受控登记材料。
 */
internal fun ApplicationConfig.syncServerConfiguration(): SyncServerConfiguration {
    // 阶段一：只读取已解析配置，默认值与环境变量替换统一由 YAML 声明。
    val rootValue = property("tagloom.sync.dataDir").getString()
    require(rootValue.isNotBlank()) { "tagloom.sync.dataDir 不能为空" }
    val root = Path.of(rootValue)
    val legacy = propertyOrNull("tagloom.sync.credentialsFile")?.getString()
        ?.takeIf(String::isNotBlank)?.let(Path::of)
    val bootstrap = propertyOrNull("tagloom.sync.bootstrapTokenSha256")?.getString()
        ?.takeIf(String::isNotBlank)?.also(::requireSyncDigest)
    val limit = property("tagloom.sync.maxRecoveryBytes").getString().toLongOrNull()
        ?.takeIf { it > 0 } ?: error("tagloom.sync.maxRecoveryBytes 必须为正整数")

    // 阶段二：已登记的部署允许关闭首次登记；新目录不能因配置缺失变成开放注册。
    check(legacy != null || bootstrap != null || Files.exists(root.resolve(".server/devices.json"))) {
        "首次启动需配置 tagloom.sync.bootstrapTokenSha256 或 tagloom.sync.credentialsFile"
    }
    return SyncServerConfiguration(root, bootstrap, legacy, limit)
}
