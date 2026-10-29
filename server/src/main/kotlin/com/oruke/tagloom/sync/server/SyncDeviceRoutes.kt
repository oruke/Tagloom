package com.oruke.tagloom.sync.server

import com.oruke.tagloom.core.sync.*
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 接入受控首次登记、设备生命周期及 Recovery Key 用途隔离的恢复入口。
 * @param registry 持久化设备与恢复确认点服务。
 * @param archives 不可变密文归档存储。
 */
internal fun Application.installSyncDeviceRoutes(registry: DeviceRegistryService, archives: RecoveryArchiveStore) {
    routing {
        put("/v1/workspaces/{workspaceId}") {
            val token = call.syncBearerToken()
            val workspaceId = call.syncWorkspaceId()
            val request = call.receiveSyncManagement(SyncWorkspaceRegistration.serializer())
            call.respond(withContext(Dispatchers.IO) { registry.createWorkspace(token, workspaceId, request) })
        }
        route("/v1/workspaces/{workspaceId}/devices") {
            get {
                val access = call.syncAccess(registry)
                val rawLimit = call.request.queryParameters["limit"]
                val limit = if (rawLimit == null) 50 else rawLimit.toIntOrNull() ?: throw IllegalArgumentException("分页大小无效")
                call.respond(registry.list(access, limit, call.request.queryParameters["cursor"]))
            }
            put("/{actorId}") {
                val access = call.syncAccess(registry)
                val actor = call.syncActorId()
                val request = call.receiveSyncManagement(SyncDeviceRegistration.serializer())
                call.respond(withContext(Dispatchers.IO) { registry.register(access, actor, request) })
            }
            put("/{actorId}/credentials") {
                val access = call.syncAccess(registry)
                val actor = call.syncActorId()
                val request = call.receiveSyncManagement(SyncCredentialUpdate.serializer())
                call.respond(withContext(Dispatchers.IO) { registry.rotate(access, actor, request) })
            }
            delete("/{actorId}") {
                val access = call.syncAccess(registry)
                val actor = call.syncActorId()
                withContext(Dispatchers.IO) { registry.revoke(access, actor) }
                call.respond(HttpStatusCode.NoContent)
            }
        }
        route("/v1/workspaces/{workspaceId}/recovery-snapshot") {
            get("/metadata") {
                // 先验权再读取确认点，未发布与不存在工作区不能成为匿名枚举入口。
                val workspace = registry.recoverySnapshot(call.syncAccess(registry))
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respond(requireNotNull(workspace.snapshot))
            }
            put {
                val access = call.syncAccess(registry)
                val expected = call.request.headers["X-Tagloom-Recovery-Revision"]?.toLongOrNull()
                    ?.takeIf { it >= 0 } ?: throw IllegalArgumentException("恢复版本无效")
                val recoveryId = requireNotNull(call.request.headers["X-Tagloom-Recovery-Id"]).also(::requireSyncDigest)
                val recoveryHash = requireNotNull(call.request.headers["X-Tagloom-Recovery-Token-Sha256"]).also(::requireSyncDigest)
                val digest = requireNotNull(call.request.headers["X-Tagloom-Archive-Sha256"]).also(::requireSyncDigest)
                val archive = archives.receive(access.identity.workspaceId, call.receiveChannel(), digest)
                val snapshot = withContext(Dispatchers.IO) { registry.publishRecovery(access, expected, recoveryId, recoveryHash, archive) }
                call.respond(snapshot)
            }
            get {
                val workspace = registry.recoverySnapshot(call.syncAccess(registry))
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.response.header("X-Tagloom-Archive-Sha256", requireNotNull(workspace.snapshot).sha256)
                call.response.header("X-Tagloom-Recovery-Revision", workspace.snapshot.revision)
                call.respondFile(archives.file(workspace).toFile())
            }
        }
        put("/v1/recoveries/{recoveryId}/devices/{actorId}") {
            val recoveryId = requireNotNull(call.parameters["recoveryId"]).also(::requireSyncDigest)
            val token = call.syncBearerToken()
            val actor = call.syncActorId()
            val request = call.receiveSyncManagement(SyncDeviceRegistration.serializer())
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(withContext(Dispatchers.IO) { registry.recover(recoveryId, token, actor, request) })
        }
    }
}
