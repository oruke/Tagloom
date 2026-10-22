package com.oruke.tagloom

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 用真实源站和转发代理验证桌面启动策略同时作用于网页请求与 Ktor CIO。 */
class DesktopSystemProxyIntegrationTest {
    /** 外部目标必须经过代理，localhost 与回环 IP 必须直达源站，响应内容均来自真实 TCP 转发。 */
    @Test
    fun forwardsExternalTrafficAndBypassesLoopbackForBothClients() = runBlocking {
        val previousSelector = ProxySelector.getDefault()
        ProxyRoutingFixture().use { fixture ->
            try {
                ProxySelector.setDefault(fixture.systemSelector)
                enableDesktopSystemProxy()
                val remote = URI("http://proxy-probe.invalid/metadata")
                val client = HttpClient(CIO) {
                    install(HttpTimeout) {
                        connectTimeoutMillis = 5000
                        requestTimeoutMillis = 5000
                    }
                }
                try {
                    // 第一阶段：保留宿主代理选择，两个真实客户端只能经代理访问不可解析的样例域名。
                    assertEquals(fixture.responseText, readProxyTestText(remote))
                    assertEquals(fixture.responseText, client.get(remote.toString()).bodyAsText())
                    assertEquals(2, fixture.proxiedRequests.get())

                    // 第二阶段：宿主故意对所有地址返回代理，桌面策略仍保证两种回环请求直连。
                    assertEquals(fixture.responseText, readProxyTestText(fixture.directUri))
                    val localhost = URI("http", null, "localhost", fixture.directUri.port, "/local", null, null)
                    assertEquals(fixture.responseText, client.get(localhost.toString()).bodyAsText())
                    assertEquals(2, fixture.proxiedRequests.get())
                    assertEquals(4, fixture.originRequests.get())

                    val selector = ProxySelector.getDefault()
                    assertTrue(selector.select(URI("http://[::1]/")).all { it.type() == Proxy.Type.DIRECT })
                    assertTrue(selector.select(URI("http://editor.localhost/")).all { it.type() == Proxy.Type.DIRECT })
                    assertEquals(Proxy.Type.HTTP, selector.select(URI("http://127.example.invalid/")).single().type())
                } finally {
                    client.close()
                }
            } finally {
                ProxySelector.setDefault(previousSelector)
            }
        }
    }
}

/** 真实回环源站和仅供当前测试使用的 HTTP 转发代理，不向公网发送测试请求。 */
private class ProxyRoutingFixture : AutoCloseable {
    /** 源站实际返回并由两个客户端核对的响应正文。 */
    val responseText = "system-proxy-route-verified"
    /** 源站实际收到的请求数量，包含代理转发和回环直连。 */
    val originRequests = AtomicInteger()
    /** 转发代理实际收到的请求数量。 */
    val proxiedRequests = AtomicInteger()
    /** 用动态端口提供真实响应的源站。 */
    private val origin = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    /** 把样例外部地址转发到当前源站的真实代理。 */
    private val proxy = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    /** 按真实动态地址构造回环请求，不依赖本机固定端口。 */
    val directUri: URI
        get() = URI("http", null, origin.address.address.hostAddress, origin.address.port, "/local", null, null)
    /** 模拟宿主对所有目标返回代理；网络请求及转发均使用真实连接。 */
    val systemSelector = object : ProxySelector() {
        /**
         * 提供当前测试实际监听的代理。
         * @param uri 待访问地址；本场景由桌面策略决定是否绕过。
         * @return 当前转发代理。
         */
        override fun select(uri: URI): List<Proxy> = listOf(Proxy(Proxy.Type.HTTP, proxy.address))

        /**
         * 网络故障由客户端直接报告，测试宿主没有额外失败恢复动作。
         * @param uri 请求目标。
         * @param address 失败代理地址。
         * @param error 连接失败原因。
         */
        override fun connectFailed(uri: URI, address: SocketAddress, error: IOException) = Unit
    }

    init {
        origin.createContext("/") { exchange ->
            originRequests.incrementAndGet()
            val bytes = responseText.encodeToByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        proxy.createContext("/") { exchange ->
            proxiedRequests.incrementAndGet()
            val target = URI("http", null, origin.address.address.hostAddress, origin.address.port, exchange.requestURI.path, null, null)
            // 代理到源站使用明确直连，防止测试选择器让转发再次进入自身。
            val forwarded = target.toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
            forwarded.connectTimeout = 5000
            forwarded.readTimeout = 5000
            try {
                val bytes = forwarded.inputStream.use { it.readBytes() }
                exchange.sendResponseHeaders(forwarded.responseCode, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } finally {
                forwarded.disconnect()
                exchange.close()
            }
        }
        origin.start()
        proxy.start()
    }

    /** 关闭当前测试创建的两台真实服务器。 */
    override fun close() {
        proxy.stop(0)
        origin.stop(0)
    }
}

/**
 * 用网页元数据客户端相同的 Java HTTP 连接读取真实响应。
 * @param uri 本次请求的完整地址。
 * @return 服务器返回的 UTF-8 文本。
 */
private fun readProxyTestText(uri: URI): String {
    val connection = uri.toURL().openConnection() as HttpURLConnection
    connection.connectTimeout = 5000
    connection.readTimeout = 5000
    return try {
        connection.inputStream.use { it.readBytes().decodeToString() }
    } finally {
        connection.disconnect()
    }
}
