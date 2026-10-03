package com.oruke.tagloom.automation

import com.oruke.tagloom.core.external.AgentWorkspaceSession
import io.ktor.server.engine.*
import io.ktor.server.netty.Netty

/** 管理仅绑定回环接口的本机 API 生命周期，不解锁或重新打开工作区。 */
class LocalApiServer(
    /** 当前进程唯一工作区会话提供者，锁定时为空。 */
    sessionProvider: () -> AgentWorkspaceSession?,
    /** 显式配置的 owner Token，不能由 Agent 自行获取。 */
    ownerToken: String,
    /** 显式监听端口；测试可使用 0 请求系统随机端口。 */
    port: Int,
    /** 单调毫秒源；验收可推进时钟验证有效期，而不等待真实十分钟。 */
    now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /** 统一权限、审批和请求去重控制器。 */
    private val controller = LocalAutomationController(ownerToken, sessionProvider, now)
    /** 唯一回环 HTTP 监听器。 */
    private val engine = embeddedServer(Netty, host = "127.0.0.1", port = port) { localAutomationApi(controller) }

    init { require(port in 0..65535) { "api_port_invalid" } }

    /** 启动本机监听，不等待用户打开或解锁工作区。 */
    fun start() { engine.start(wait = false) }

    /** @return 已绑定的实际端口，供随机端口集成测试使用。 */
    suspend fun port(): Int = engine.engine.resolvedConnectors().single().port

    /** 停止接收调用并清除所有 Token 摘要、审批和明文结果缓存。 */
    suspend fun close() { engine.stop(500, 2_000); controller.close() }
}
