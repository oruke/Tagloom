package com.oruke.tagloom

import com.oruke.tagloom.sync.server.configureSyncServer
import com.oruke.tagloom.sync.server.syncServerConfigurationFromEnvironment
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

/** 启动自部署 Tagloom 盲同步 HTTP 服务。 */
fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

/**
 * 从部署环境加载密文存储、持久化设备登记与受控引导配置。
 *
 * 首次接入必须显式配置引导凭据摘要或旧凭据文件，避免开放注册或默认口令。
 */
fun Application.module() {
    configureSyncServer(syncServerConfigurationFromEnvironment())
}
