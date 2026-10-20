package com.oruke.tagloom

import com.oruke.tagloom.sync.server.configureSyncServer
import com.oruke.tagloom.sync.server.syncServerConfiguration
import io.ktor.server.application.Application
import io.ktor.server.netty.EngineMain

/**
 * 从 YAML 配置及命令行覆盖启动自部署盲同步服务，阻塞至服务关闭。
 * @param args Ktor 启动参数，支持通过 `-config` 指定外部 YAML 文件。
 */
fun main(args: Array<String>) {
    EngineMain.main(args)
}

/**
 * 从已加载的 YAML 配置组装密文存储、持久化设备登记与受控引导配置。
 *
 * 首次接入必须显式配置引导凭据摘要或旧凭据文件，避免开放注册或默认口令。
 */
fun Application.module() {
    configureSyncServer(environment.config.syncServerConfiguration())
}
