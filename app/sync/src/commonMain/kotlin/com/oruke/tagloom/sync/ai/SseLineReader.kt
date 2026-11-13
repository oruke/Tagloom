package com.oruke.tagloom.sync.ai

import com.oruke.tagloom.core.ai.AiErrorKind
import com.oruke.tagloom.core.ai.AiProviderException
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readLineStrict

/**
 * 表示从 SSE（Server-Sent Events）字节流中解析出的一帧完整事件。
 */
internal data class SseEventFrame(
    /** 可选 `event:` 字段声明的事件类型名称。 */
    val event: String?,
    /** 合并多行 `data:` 后的载荷文本。 */
    val data: String,
)

/**
 * 从 Ktor [ByteReadChannel] 按行解析受容量上限保护的 SSE 事件流。
 *
 * 严格限制单行长度与单帧累积字符数，防止异常服务端通过无限长无换行响应耗尽客户端内存；
 * 当外层协程取消时，底层通道读取立即挂起取消并释放连接。
 */
internal object SseLineReader {
    /** 单行 SSE 文本允许的最大 UTF-16 字符数（512 KiB 级别）。 */
    private const val MAX_LINE_CHARS: Int = 512 * 1024

    /** 单帧 SSE 事件允许的最大累计字符数（2 MiB 级别）。 */
    private const val MAX_EVENT_DATA_CHARS: Int = 2 * 1024 * 1024

    /** OpenAI 兼容协议约定的流结束哨兵。 */
    const val DONE_SENTINEL: String = "[DONE]"

    /**
     * 逐帧读取 SSE 事件并回调 [onFrame]，遇到 `[DONE]` 或 EOF 时正常返回。
     *
     * @param channel HTTP 响应字节通道。
     * @param onFrame 每解析出一帧非空事件时触发的挂起回调。
     */
    suspend fun readFrames(
        channel: ByteReadChannel,
        onFrame: suspend (SseEventFrame) -> Unit,
    ) {
        var currentEvent: String? = null
        val dataBuilder = StringBuilder()
        var hasDataLine = false

        suspend fun flushCurrent(): Boolean {
            if (!hasDataLine) {
                currentEvent = null
                return false
            }
            val payload = dataBuilder.toString()
            val eventType = currentEvent
            currentEvent = null
            dataBuilder.setLength(0)
            hasDataLine = false
            if (payload.trim() == DONE_SENTINEL) {
                return true
            }
            onFrame(SseEventFrame(event = eventType, data = payload))
            return false
        }

        while (!channel.isClosedForRead) {
            val line = try {
                channel.readLineStrict(limit = MAX_LINE_CHARS.toLong())
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                throw AiProviderException(AiErrorKind.PROTOCOL, diagnosticCode = "sse_line_invalid_or_too_large")
            } ?: break

            when {
                line.isEmpty() -> {
                    if (flushCurrent()) return
                }

                line.startsWith(':') -> Unit

                line.startsWith("event:") -> {
                    currentEvent = line.substringAfter("event:").trim()
                }

                line.startsWith("data:") -> {
                    val value = line.substringAfter("data:").let { raw ->
                        if (raw.startsWith(' ')) raw.substring(1) else raw
                    }
                    val addedLength = value.length + if (hasDataLine) 1 else 0
                    if (dataBuilder.length + addedLength > MAX_EVENT_DATA_CHARS) {
                        throw AiProviderException(AiErrorKind.PROTOCOL, diagnosticCode = "sse_frame_too_large")
                    }
                    if (hasDataLine) dataBuilder.append('\n')
                    dataBuilder.append(value)
                    hasDataLine = true
                }
            }
        }
        flushCurrent()
    }
}
