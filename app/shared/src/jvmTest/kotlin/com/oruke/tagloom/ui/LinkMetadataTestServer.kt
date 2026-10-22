package com.oruke.tagloom.ui

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 启动真实本机 HTTP 页面供生产元数据客户端访问，不替换应用服务或持久化依赖。 */
internal class LinkMetadataTestServer : AutoCloseable {
    /** 只绑定系统回环地址和随机可用端口。 */
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

    /** 后台响应线程不阻止测试进程退出。 */
    private val executor =
        Executors.newCachedThreadPool { task -> Thread(task, "metadata-fixture").apply { isDaemon = true } }

    /** 延迟页面已收到真实请求。 */
    val started = CountDownLatch(1)

    /** 控制延迟响应，用于并发编辑、旧 URL 和锁定取消。 */
    val release = CountDownLatch(1)

    /** 可重试端点的第一次状态。 */
    val fail = AtomicBoolean(true)

    init {
        server.executor = executor
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            if (path == "/redirect") {
                exchange.responseHeaders.add("Location", "/page")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            } else {
                if (path == "/slow") {
                    started.countDown(); release.await(15, TimeUnit.SECONDS)
                }
                val code = if (path == "/retry" && fail.get()) 503 else 200
                val title = if (path == "/new") "New page example" else "Metadata page example"
                val html =
                    """<html><head><title>Fallback example</title><meta property="og:title" content="$title"><meta name="description" content="A &amp; B source description"></head><body>Example body</body></html>"""
                val bytes = html.toByteArray(Charsets.UTF_8)
                runCatching {
                    exchange.responseHeaders.add("Content-Type", "text/html; charset=UTF-8")
                    exchange.sendResponseHeaders(code, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                exchange.close()
            }
        }
        server.start()
    }

    /**
     * 构造当前测试服务的地址，不在测试代码中保存本机地址常量。
     * @param path 脱敏样例页面路径。
     * @return 使用动态端口的回环网址。
     */
    fun url(path: String): String {
        val host = server.address.address.hostAddress.let { if (':' in it) "[$it]" else it }
        return "http://$host:${server.address.port}$path"
    }

    /** 释放正在等待的响应并关闭本测试创建的服务和线程。 */
    override fun close() {
        release.countDown(); server.stop(0); executor.shutdownNow()
    }
}
