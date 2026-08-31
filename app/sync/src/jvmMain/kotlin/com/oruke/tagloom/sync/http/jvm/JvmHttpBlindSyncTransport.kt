package com.oruke.tagloom.sync.http.jvm

import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.model.WorkspaceId
import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.sync.BlindArtifactTransportPort
import com.oruke.tagloom.core.sync.BlindSyncTransportPort
import com.oruke.tagloom.core.sync.SyncArtifactFetchRequest
import com.oruke.tagloom.core.sync.SyncArtifactFetchResponse
import com.oruke.tagloom.core.sync.SyncArtifactInventoryResponse
import com.oruke.tagloom.core.sync.SyncFetchRequest
import com.oruke.tagloom.core.sync.SyncFetchResponse
import com.oruke.tagloom.core.sync.SyncHeadsResponse
import com.oruke.tagloom.core.sync.SyncPushRequest
import com.oruke.tagloom.core.sync.SyncPushResponse
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException

/**
 * 按需提供当前解锁同步会话的高熵 Bearer Token。
 *
 * @return 只供一次请求构造使用的 Token。
 */
fun interface SyncAccessTokenProvider {
    /**
     * 返回只供一次 HTTP 请求使用的 Token。
     *
     * @return 不得记录或写入 URL 的 Bearer Token。
     */
    fun accessToken(): String
}

/** 描述脱敏后的同步 HTTP 失败。 */
class SyncTransportException(
    /** HTTP 状态；客户端侧尺寸或连接失败时为空。 */
    val statusCode: Int? = null,
    /** 稳定错误分类。 */
    val code: String,
    /** 不含响应正文、Token 或 URL 的底层异常。 */
    cause: Throwable? = null,
) : IllegalStateException(code, cause)

/**
 * 使用 JDK HttpClient 实现 JVM 已认证盲同步传输。
 *
 * 默认只允许 HTTPS，测试或明确受信任的本机反向代理场景必须显式开启不安全传输。Token 仅在构造单次请求 Header 时读取，不进入 URL、日志或异常。
 *
 * @param serverBaseUri 不含凭据、查询和 fragment 的同步服务根 URI。
 * @param tokenProvider 当前解锁会话 Token 提供者。
 * @param allowInsecureTransport 是否显式允许 HTTP。
 * @param httpClient 禁止自动重定向的 JDK HTTP 客户端。
 * @param dispatcher 执行阻塞网络调用的协程调度器。
 */
class JvmHttpBlindSyncTransport(
    /** 同步服务根 URI。 */
    private val serverBaseUri: URI,
    /** 当前解锁会话 Token 提供者。 */
    private val tokenProvider: SyncAccessTokenProvider,
    allowInsecureTransport: Boolean = false,
    /** 禁止自动重定向，避免 Authorization 被带到其他 Origin。 */
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(REQUEST_TIMEOUT)
        .build(),
    /** 执行阻塞网络调用的协程调度器。 */
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BlindSyncTransportPort, BlindArtifactTransportPort {
    init {
        require(serverBaseUri.scheme.equals("https", ignoreCase = true) || allowInsecureTransport) {
            "同步服务默认必须使用 HTTPS"
        }
        require(serverBaseUri.userInfo == null && serverBaseUri.query == null && serverBaseUri.fragment == null) {
            "同步服务 URI 不能包含凭据、查询或 fragment"
        }
        require(serverBaseUri.host != null) { "同步服务 URI 必须包含主机" }
    }

    /**
     * 查询远端对象 Change heads。
     *
     * @param workspaceId 随机工作区标识。
     * @param actorId 当前认证设备。
     * @return 服务端当前 heads。
     */
    override suspend fun listChangeHeads(
        workspaceId: WorkspaceId,
        actorId: ActorId,
    ): SyncHeadsResponse = sendJson(
        workspaceId = workspaceId,
        actorId = actorId,
        relativePath = "change-heads",
        requestBody = null,
        decode = { body -> CanonicalJson.decodeFromString(SyncHeadsResponse.serializer(), body) },
    )

    /**
     * 幂等推送一批加密 Change。
     *
     * @param workspaceId 随机工作区标识。
     * @param actorId 当前认证设备。
     * @param request 推送批次。
     * @return 首次存储与重复数量。
     */
    override suspend fun pushChanges(
        workspaceId: WorkspaceId,
        actorId: ActorId,
        request: SyncPushRequest,
    ): SyncPushResponse = sendJson(
        workspaceId = workspaceId,
        actorId = actorId,
        relativePath = "changes/push",
        requestBody = CanonicalJson.encodeToString(SyncPushRequest.serializer(), request),
        decode = { body -> CanonicalJson.decodeFromString(SyncPushResponse.serializer(), body) },
    )

    /**
     * 按内容 ID 精确拉取一批加密 Change。
     *
     * @param workspaceId 随机工作区标识。
     * @param actorId 当前认证设备。
     * @param request 拉取批次。
     * @return 服务端找到的 Change。
     */
    override suspend fun fetchChanges(
        workspaceId: WorkspaceId,
        actorId: ActorId,
        request: SyncFetchRequest,
    ): SyncFetchResponse = sendJson(
        workspaceId = workspaceId,
        actorId = actorId,
        relativePath = "changes/fetch",
        requestBody = CanonicalJson.encodeToString(SyncFetchRequest.serializer(), request),
        decode = { body -> CanonicalJson.decodeFromString(SyncFetchResponse.serializer(), body) },
    )

    /**
     * 查询 Snapshot、Blob 与 Manifest 的服务端内容清单。
     *
     * @param workspaceId 当前工作区。
     * @param actorId 当前认证设备。
     * @return 按类型分组的内容 ID。
     */
    override suspend fun listArtifactInventory(
        workspaceId: WorkspaceId,
        actorId: ActorId,
    ): SyncArtifactInventoryResponse = sendJson(
        workspaceId = workspaceId,
        actorId = actorId,
        relativePath = "artifacts/inventory",
        requestBody = null,
        decode = { body -> CanonicalJson.decodeFromString(SyncArtifactInventoryResponse.serializer(), body) },
    )

    /**
     * 幂等推送一批非 Change 端到端密文。
     *
     * @param workspaceId 当前工作区。
     * @param actorId 当前认证设备。
     * @param request 推送批次。
     * @return 首次存储与重复数量。
     */
    override suspend fun pushArtifacts(
        workspaceId: WorkspaceId,
        actorId: ActorId,
        request: SyncPushRequest,
    ): SyncPushResponse = sendJson(
        workspaceId = workspaceId,
        actorId = actorId,
        relativePath = "artifacts/push",
        requestBody = CanonicalJson.encodeToString(SyncPushRequest.serializer(), request),
        decode = { body -> CanonicalJson.decodeFromString(SyncPushResponse.serializer(), body) },
    )

    /**
     * 按类型与内容 ID 精确拉取非 Change 密文。
     *
     * @param workspaceId 当前工作区。
     * @param actorId 当前认证设备。
     * @param request 精确拉取请求。
     * @return 服务端实际找到的密文。
     */
    override suspend fun fetchArtifacts(
        workspaceId: WorkspaceId,
        actorId: ActorId,
        request: SyncArtifactFetchRequest,
    ): SyncArtifactFetchResponse = sendJson(
        workspaceId = workspaceId,
        actorId = actorId,
        relativePath = "artifacts/fetch",
        requestBody = CanonicalJson.encodeToString(SyncArtifactFetchRequest.serializer(), request),
        decode = { body -> CanonicalJson.decodeFromString(SyncArtifactFetchResponse.serializer(), body) },
    )

    /**
     * 构造单次认证请求、拒绝重定向或超限响应，并解码预期 JSON。
     *
     * @param workspaceId 当前工作区。
     * @param actorId 当前认证设备。
     * @param relativePath sync 路由下相对路径。
     * @param requestBody POST JSON；为空时发送 GET。
     * @param decode 成功响应解码器。
     * @return 解码后的协议响应。
     */
    private suspend fun <T> sendJson(
        workspaceId: WorkspaceId,
        actorId: ActorId,
        relativePath: String,
        requestBody: String?,
        decode: (String) -> T,
    ): T = withContext(dispatcher) {
        if (requestBody != null && requestBody.encodeToByteArray().size > MAX_HTTP_BODY_BYTES) {
            throw SyncTransportException(code = "request_too_large")
        }
        val requestBuilder = HttpRequest.newBuilder(endpoint(workspaceId, relativePath))
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", JSON_CONTENT_TYPE)
            .header("X-Tagloom-Actor", actorId.value)
            .header("Authorization", "Bearer ${tokenProvider.accessToken()}")
        val request = if (requestBody == null) {
            requestBuilder.GET().build()
        } else {
            requestBuilder
                .header("Content-Type", JSON_CONTENT_TYPE)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build()
        }
        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw SyncTransportException(code = "network_failed", cause = error)
        }
        if (response.statusCode() !in 200..299) {
            throw SyncTransportException(statusCode = response.statusCode(), code = "http_failed")
        }
        if (response.body().size > MAX_HTTP_BODY_BYTES) {
            throw SyncTransportException(statusCode = response.statusCode(), code = "response_too_large")
        }
        val body = response.body().decodeToString()
        try {
            decode(body)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw SyncTransportException(statusCode = response.statusCode(), code = "invalid_response", cause = error)
        }
    }

    /**
     * 生成不含 Token 的工作区同步 endpoint。
     *
     * @param workspaceId 随机工作区标识。
     * @param relativePath sync 路由下相对路径。
     * @return 完整请求 URI。
     */
    private fun endpoint(
        workspaceId: WorkspaceId,
        relativePath: String,
    ): URI = URI.create(
        serverBaseUri.toString().trimEnd('/') +
            "/v1/workspaces/${workspaceId.value}/sync/$relativePath",
    )

    private companion object {
        /** 单次连接与请求超时。 */
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(30)

        /** 单次 JSON 请求或响应的 JVM 内存安全上限。 */
        const val MAX_HTTP_BODY_BYTES: Int = 64 * 1024 * 1024

        /** 同步协议固定 JSON 媒体类型。 */
        const val JSON_CONTENT_TYPE: String = "application/json"
    }
}
