package com.oruke.tagloom.sync.server

import com.oruke.tagloom.core.protocol.CanonicalJson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.Serializable

/** 向旧客户端保留 code，同时提供不含请求秘密的标准 Problem Details。 */
@Serializable
data class SyncApiError(
    /** 兼容既有客户端分支的稳定错误码。 */
    val code: String,
    /** 稳定问题类型 URI，不含请求路径或身份。 */
    val type: String = "urn:tagloom:sync:error:$code",
    /** 安全的标准状态说明。 */
    val title: String = "Sync request failed",
    /** HTTP 状态值。 */
    val status: Int = 400,
    /** 不回显请求内容的恢复建议。 */
    val detail: String = "Check the request parameters and retry.",
)

/**
 * 返回可操作但不泄露正文、凭据、路径或底层异常的错误响应。
 * @param status 安全 HTTP 状态。
 * @param code 文档化的稳定错误码。
 */
internal suspend fun ApplicationCall.respondSyncProblem(status: HttpStatusCode, code: String) {
    val detail = when (status.value) {
        401, 403 -> "Check the authorization for this workspace and device."
        404 -> "Check that the resource has been registered or uploaded."
        409 -> "Refresh the resource state before retrying; do not overwrite a newer revision."
        413 -> "Reduce the request size or check the configured archive limit."
        500 -> "Retry later; if the problem persists, check server storage and restart safely."
        else -> "Check the request parameters and retry."
    }
    respondText(CanonicalJson.encodeToString(SyncApiError.serializer(), SyncApiError(code, title = status.description,
        status = status.value, detail = detail)), ContentType.parse("application/problem+json"), status)
}
