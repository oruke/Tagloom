package com.oruke.tagloom.sync.http

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
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.coroutines.cancel
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
 * 使用 Ktor 交换密文同步协议；协议、认证和大小边界在各端完全一致。
 * 调用方必须使用关闭重定向的客户端，实例关闭时释放该客户端。
 */
class KtorBlindSyncTransport(
    /** 不含凭据、查询和 fragment 的服务根地址。 */
    private val serverBaseUri: String,
    /** 每次请求读取一次的会话 Token 提供者。 */
    private val tokenProvider: SyncAccessTokenProvider,
    /** 显式允许本机测试使用 HTTP，生产保持 false。 */
    private val allowInsecureTransport: Boolean = false,
    /** 已关闭重定向且设置超时的客户端，由此传输实例持有。 */
    private val httpClient: HttpClient,
) : BlindSyncTransportPort, BlindArtifactTransportPort {
    init {
        val url = Url(serverBaseUri)
        require(url.protocol.name == "https" || (allowInsecureTransport && url.protocol.name == "http")) {
            "同步服务默认必须使用 HTTPS"
        }
        require(url.user == null && url.password == null && url.parameters.isEmpty() && url.fragment.isEmpty()) {
            "同步服务 URI 不能包含凭据、查询或 fragment"
        }
        require(url.host.isNotBlank()) { "同步服务 URI 必须包含主机" }
    }

    /** 关闭网络客户端并取消在途请求。 */
    fun close() {
        // HttpClient.close 是优雅关闭；锁定会话还必须取消正在等待网络响应的请求。
        httpClient.coroutineContext.cancel()
        httpClient.close()
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
    ): T {
        if (requestBody != null && requestBody.encodeToByteArray().size > MAX_HTTP_BODY_BYTES) {
            throw SyncTransportException(code = "request_too_large")
        }
        val endpoint = serverBaseUri.trimEnd('/') + "/v1/workspaces/${workspaceId.value}/sync/$relativePath"
        return try {
            httpClient.prepareRequest(endpoint) {
                method = if (requestBody == null) HttpMethod.Get else HttpMethod.Post
                header("Accept", JSON_CONTENT_TYPE)
                header("X-Tagloom-Actor", actorId.value)
                header("Authorization", "Bearer ${tokenProvider.accessToken()}")
                if (requestBody != null) {
                    header("Content-Type", JSON_CONTENT_TYPE)
                    setBody(requestBody)
                }
            }.execute { response ->
                val status = response.status.value
                if (status !in 200..299) throw SyncTransportException(status, "http_failed")
                // 在读取阶段截断，不能先无界缓存响应再检查长度。
                val bytes = response.bodyAsChannel().readRemaining(MAX_HTTP_BODY_BYTES.toLong() + 1).readByteArray()
                if (bytes.size > MAX_HTTP_BODY_BYTES) throw SyncTransportException(status, "response_too_large")
                try {
                    decode(bytes.decodeToString())
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    throw SyncTransportException(status, "invalid_response")
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: SyncTransportException) {
            throw error
        } catch (_: Throwable) {
            // 不保留 Ktor 原始异常，避免请求 URL、Header 或响应正文进入错误输出。
            throw SyncTransportException(code = "network_failed")
        }
    }

    private companion object {
        /** 单次 JSON 请求或响应的 内存安全上限。 */
        const val MAX_HTTP_BODY_BYTES: Int = 64 * 1024 * 1024

        /** 同步协议固定 JSON 媒体类型。 */
        const val JSON_CONTENT_TYPE: String = "application/json"
    }
}
