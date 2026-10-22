package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.model.LinkUrl
import com.oruke.tagloom.core.model.WebPageMetadata
import com.oruke.tagloom.core.model.WebPageMetadataParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.Charset
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** JVM/Android 客户端有界网页元数据请求，不复用浏览器 Cookie，不经过同步服务器。 */
internal class HttpWebPageMetadataClient {
    /**
     * 获取一个用户指定地址的元数据；最多五次 HTTP(S) 重定向，取消会立即断开连接。
     * @param address 已规范化的网页 URL。
     * @return 解析后的标题与描述。
     */
    suspend fun fetch(address: String): WebPageMetadata = withContext(Dispatchers.IO) {
        var current = address
        repeat(MAX_REDIRECTS + 1) { step ->
            val response = read(current)
            if (response.location != null) {
                require(step < MAX_REDIRECTS) { "link-redirect-limit" }
                current = requireNotNull(
                    LinkUrl.parse(
                        URI(current).resolve(response.location).toString()
                    )
                ) { "link-redirect-invalid" }.value
            } else return@withContext WebPageMetadataParser.parse(requireNotNull(response.html))
        }
        error("link-redirect-limit")
    }

    /**
     * 读取一次请求，取消处理器负责释放阻塞 IO；错误不携带地址或响应正文。
     * @param url 本次 HTTP(S) 地址。
     * @return 有界页面文本或重定向位置。
     */
    private suspend fun read(url: String): MetadataHttpResponse = suspendCancellableCoroutine { continuation ->
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.setRequestProperty("User-Agent", "Tagloom/1.0")
        connection.setRequestProperty("Cookie", "")
        continuation.invokeOnCancellation { connection.disconnect() }
        try {
            val code = connection.responseCode
            val result = if (code in setOf(301, 302, 303, 307, 308)) {
                MetadataHttpResponse(location = requireNotNull(connection.getHeaderField("Location")) { "link-redirect-invalid" })
            } else {
                require(code in 200..299) { "link-http-failed" }
                val type = connection.contentType.orEmpty()
                require(
                    type.substringBefore(';').trim().lowercase() in setOf(
                        "text/html",
                        "application/xhtml+xml",
                        ""
                    )
                ) { "link-content-unsupported" }
                val bytes = connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (continuation.isActive) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        // 元数据通常在响应头部；只保留有界前缀，大页面不会因正文过长丢失已取得的标题。
                        output.write(buffer, 0, minOf(count, MAX_BYTES - output.size()))
                        if (output.size() == MAX_BYTES) break
                    }
                    output.toByteArray()
                }
                val declared = Regex("(?i)charset\\s*=\\s*[\"']?([^;\\s\"']+)").find(type)?.groupValues?.get(1)
                    ?: Regex("(?i)charset\\s*=\\s*[\"']?([A-Za-z0-9_-]+)").find(
                        bytes.take(4096).toByteArray().toString(Charsets.ISO_8859_1)
                    )?.groupValues?.get(1)
                val charset = declared?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
                MetadataHttpResponse(html = bytes.toString(charset))
            }
            if (continuation.isActive) continuation.resume(result)
        } catch (error: Throwable) {
            if (continuation.isActive) continuation.resumeWithException(IllegalStateException("link-metadata-unavailable"))
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        /** 每次连接和读取的最长等待，防止受限页面一直占据请求。 */
        const val TIMEOUT_MILLIS = 8000

        /** 允许的重定向跳数。 */
        const val MAX_REDIRECTS = 5

        /** HTML 响应最多一 MiB，不下载附件或全文归档。 */
        const val MAX_BYTES = 1048576
    }
}

/** 一次有界元数据 HTTP 读取结果。 */
private data class MetadataHttpResponse(
    /** HTML 文本，重定向时为空。 */
    val html: String? = null,
    /** 重定向位置，成功读取时为空。 */
    val location: String? = null,
)
