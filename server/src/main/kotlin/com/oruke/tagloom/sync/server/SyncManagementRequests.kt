package com.oruke.tagloom.sync.server

import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.model.WorkspaceId
import com.oruke.tagloom.core.protocol.CanonicalJson
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.*
import kotlinx.serialization.KSerializer

/**
 * 读取已限定大小的管理 JSON，分块请求同样受限制。
 * @param serializer 对应的协议序列化器。
 * @return 解析后的管理请求。
 */
internal suspend fun <T> ApplicationCall.receiveSyncManagement(serializer: KSerializer<T>): T {
    val input = receiveChannel()
    val bytes = ByteArray(8193)
    var count = 0
    while (count < bytes.size) {
        val read = input.readAvailable(bytes, count, bytes.size - count)
        if (read < 0) break
        count += read
    }
    if (count > 8192) rejectSync(HttpStatusCode.PayloadTooLarge, "management_request_too_large")
    return CanonicalJson.decodeFromString(serializer, bytes.decodeToString(0, count))
}

/** @return 请求中的 Bearer 凭据；缺失时返回稳定认证错误。 */
internal fun ApplicationCall.syncBearerToken(): String {
    val value = request.headers["Authorization"].orEmpty()
    if (!value.startsWith("Bearer ") || value.length !in 8..1031)
        rejectSync(HttpStatusCode.Unauthorized, "authentication_required")
    return value.removePrefix("Bearer ")
}

/** @return 经受控身份规则校验的工作区路径参数。 */
internal fun ApplicationCall.syncWorkspaceId(): WorkspaceId =
    WorkspaceId(requireNotNull(parameters["workspaceId"]).also(::requireSyncIdentity))

/** @return 经受控身份规则校验的设备路径参数。 */
internal fun ApplicationCall.syncActorId(): ActorId =
    ActorId(requireNotNull(parameters["actorId"]).also(::requireSyncIdentity))

/**
 * 取得当前请求的设备认证材料并立即验证，业务写入时还会再次验证。
 * @param authenticator 当前服务的持久化认证器。
 * @return 只在请求内存中存在的访问凭据。
 */
internal fun ApplicationCall.syncAccess(authenticator: SyncDeviceAuthenticator): SyncDeviceAccess {
    val actor = request.headers["X-Tagloom-Actor"] ?: rejectSync(HttpStatusCode.Unauthorized, "authentication_required")
    requireSyncIdentity(actor)
    val access = SyncDeviceAccess(SyncDeviceIdentity(syncWorkspaceId(), ActorId(actor)), syncBearerToken())
    if (!authenticator.authenticate(access.identity, access.token)) rejectSync(HttpStatusCode.Unauthorized, "authentication_failed")
    return access
}
