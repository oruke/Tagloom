package com.oruke.tagloom

import com.oruke.tagloom.automation.LocalApiServer
import com.oruke.tagloom.platform.WorkspaceGateway
import com.oruke.tagloom.workspace.DesktopWorkspaceGateway
import kotlinx.coroutines.runBlocking

/** 仅在显式配置时装配本机外部入口，复用桌面现有工作区网关与会话。 */
object DesktopAutomationRuntime {
    /**
     * 从环境装配回环 API，并在桌面进程退出时关闭网络和明文缓存。
     * @param gateway 桌面共享工作区网关，不创建第二 Manager。
     * @return 未配置时为空，已启用时返回服务器生命周期。
     */
    fun start(gateway: WorkspaceGateway): LocalApiServer? {
        val token = System.getenv("TAGLOOM_API_OWNER_TOKEN")
        val configuredPort = System.getenv("TAGLOOM_API_PORT")
        if (token == null && configuredPort == null) return null
        return try {
            val port = requireNotNull(configuredPort?.toIntOrNull()).also { require(it in 1..65535) }
            val workspace = gateway as DesktopWorkspaceGateway
            LocalApiServer(workspace::externalSessionOrNull, requireNotNull(token), port).also { server ->
                server.start()
                Runtime.getRuntime().addShutdownHook(Thread { runBlocking { server.close() } })
            }
        } catch (_: Exception) {
            // 无效本机 API 配置不能阻止桌面笔记启动，也不得把凭据或底层路径写入错误。
            System.err.println("local_api_start_failed")
            null
        }
    }
}
