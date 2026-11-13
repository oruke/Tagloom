package com.oruke.tagloom.sync.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout

/**
 * 为 JVM 桌面与 Android 客户端创建基于 CIO 引擎、默认关闭重定向的 [KtorAiStreamPort]。
 */
object JvmKtorAiStreamPortFactory {
    /**
     * 创建新的流式模型端口实例。
     *
     * @return 配置了超时保护与禁重定向策略的 [KtorAiStreamPort]。
     */
    fun create(): KtorAiStreamPort = KtorAiStreamPort(
        httpClient = HttpClient(CIO) {
            followRedirects = false
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000L
                socketTimeoutMillis = 60_000L
                requestTimeoutMillis = 360_000L
            }
        },
    )
}
