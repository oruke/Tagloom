package com.oruke.tagloom.sync.server

import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.model.WorkspaceId
import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.sync.BlindSyncArtifact
import com.oruke.tagloom.core.sync.DeviceArtifactSignatureService
import com.oruke.tagloom.core.sync.SyncArtifactKind
import com.oruke.tagloom.core.sync.SyncArtifactFetchRequest
import com.oruke.tagloom.core.sync.SyncArtifactFetchResponse
import com.oruke.tagloom.core.sync.SyncArtifactInventoryEntry
import com.oruke.tagloom.core.sync.SyncArtifactInventoryResponse
import com.oruke.tagloom.core.sync.SyncFetchRequest
import com.oruke.tagloom.core.sync.SyncFetchResponse
import com.oruke.tagloom.core.sync.SyncHeadsResponse
import com.oruke.tagloom.core.sync.SyncPushRequest
import com.oruke.tagloom.core.sync.SyncPushResponse
import com.oruke.tagloom.crypto.libsodium.LibsodiumCryptoProvider
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json

/** 携带安全 HTTP 状态与稳定错误码的同步协议异常。 */
class SyncHttpException(
    /** 应返回的 HTTP 状态。 */
    val status: HttpStatusCode,
    /** 不含密文、Token 或路径的稳定错误码。 */
    val errorCode: String,
) : IllegalStateException(errorCode)

/**
 * 安装 JSON、错误映射和已认证盲同步路由。
 *
 * @param store 只保存端到端密文的内容寻址存储。
 * @param authenticator 设备 Token 认证器。
 * @param signatureService 验证密文作者签名的密码学服务。
 */
fun Application.installSyncServer(
    store: BlindSyncStore,
    authenticator: SyncDeviceAuthenticator,
    signatureService: DeviceArtifactSignatureService = DeviceArtifactSignatureService(LibsodiumCryptoProvider()),
) {
    install(ContentNegotiation) {
        json(CanonicalJson.format)
    }
    install(StatusPages) {
        exception<SyncHttpException> { call, error ->
            call.respondSyncProblem(error.status, error.errorCode)
        }
        exception<IllegalArgumentException> { call, _ ->
            call.respondSyncProblem(HttpStatusCode.BadRequest, "invalid_request")
        }
        exception<BlindSyncContentConflictException> { call, _ ->
            call.respondSyncProblem(HttpStatusCode.Conflict, "content_id_conflict")
        }
        exception<Throwable> { call, _ ->
            call.respondSyncProblem(HttpStatusCode.InternalServerError, "internal_error")
        }
    }

    routing {
        get("/") {
            call.respond(mapOf("service" to "tagloom-sync", "status" to "ok"))
        }
        route("/v1/workspaces/{workspaceId}/sync") {
            get("/change-heads") {
                val identity = authenticateRequest(call, authenticator)
                call.respond(SyncHeadsResponse(store.listChangeHeads(identity.workspaceId)))
            }
            post("/changes/push") {
                val identity = authenticateRequest(call, authenticator)
                val request = call.receive<SyncPushRequest>()
                validatePushRequest(
                    store = store,
                    request = request,
                    identity = identity,
                    authenticator = authenticator,
                    signatureService = signatureService,
                    expectedKind = SyncArtifactKind.CHANGE,
                )
                var stored = 0
                var duplicates = 0
                request.artifacts.forEach { artifact ->
                    validateExistingParents(store, identity.workspaceId, artifact)
                    when (store.put(artifact)) {
                        BlindSyncPutResult.STORED -> stored += 1
                        BlindSyncPutResult.DUPLICATE -> duplicates += 1
                    }
                }
                call.respond(SyncPushResponse(stored, duplicates))
            }
            post("/changes/fetch") {
                val identity = authenticateRequest(call, authenticator)
                val request = call.receive<SyncFetchRequest>()
                call.respond(
                    SyncFetchResponse(
                        store.getChanges(identity.workspaceId, request.artifactIds),
                    ),
                )
            }
            get("/artifacts/inventory") {
                val identity = authenticateRequest(call, authenticator)
                val entries = NON_CHANGE_ARTIFACT_KINDS.map { kind ->
                    SyncArtifactInventoryEntry(kind, store.listArtifactIds(identity.workspaceId, kind))
                }
                call.respond(SyncArtifactInventoryResponse(entries))
            }
            post("/artifacts/push") {
                val identity = authenticateRequest(call, authenticator)
                val request = call.receive<SyncPushRequest>()
                validatePushRequest(
                    store = store,
                    request = request,
                    identity = identity,
                    authenticator = authenticator,
                    signatureService = signatureService,
                    expectedKind = null,
                )
                var stored = 0
                var duplicates = 0
                request.artifacts.forEach { artifact ->
                    when (store.put(artifact)) {
                        BlindSyncPutResult.STORED -> stored += 1
                        BlindSyncPutResult.DUPLICATE -> duplicates += 1
                    }
                }
                call.respond(SyncPushResponse(stored, duplicates))
            }
            post("/artifacts/fetch") {
                val identity = authenticateRequest(call, authenticator)
                val request = call.receive<SyncArtifactFetchRequest>()
                call.respond(
                    SyncArtifactFetchResponse(
                        store.getArtifacts(identity.workspaceId, request.kind, request.artifactIds),
                    ),
                )
            }
        }
    }
}

/**
 * 从路径、Actor Header 和 Bearer Token 认证同步设备。
 *
 * @param call 当前 HTTP 调用。
 * @param authenticator 设备认证器。
 * @return 已认证随机工作区与 Actor 身份。
 */
private fun authenticateRequest(
    call: ApplicationCall,
    authenticator: SyncDeviceAuthenticator,
): SyncDeviceIdentity {
    val workspaceId = call.parameters["workspaceId"]?.let { value ->
        runCatching { WorkspaceId(value) }.getOrNull()
    } ?: throw SyncHttpException(HttpStatusCode.BadRequest, "invalid_workspace_id")
    val actorId = call.request.headers[ACTOR_HEADER]?.let { value ->
        runCatching { ActorId(value) }.getOrNull()
    } ?: throw SyncHttpException(HttpStatusCode.Unauthorized, "authentication_required")
    val authorization = call.request.headers["Authorization"].orEmpty()
    val token = authorization.takeIf { value -> value.startsWith(BEARER_PREFIX) }
        ?.removePrefix(BEARER_PREFIX)
        ?.takeIf(String::isNotBlank)
        ?: throw SyncHttpException(HttpStatusCode.Unauthorized, "authentication_required")
    val identity = SyncDeviceIdentity(workspaceId, actorId)
    if (!authenticator.authenticate(identity, token)) {
        throw SyncHttpException(HttpStatusCode.Unauthorized, "authentication_failed")
    }
    return identity
}

/**
 * 验证推送批次的工作区、类型、公开完整性与原作者签名。
 *
 * 已认证设备可以转发同一工作区内其他已登记设备的历史密文，不能伪造或改写其内容。
 *
 * @param request 已完成结构反序列化的批次。
 * @param store 已接受的历史密文存储，用于识别撤销后的合法幂等重传。
 * @param identity 当前认证设备身份。
 * @param authenticator 设备 Token 与历史签名公钥目录。
 * @param signatureService Ed25519 artifact 签名验证服务。
 * @param expectedKind 指定 endpoint 要求的类型；为空时只允许非 Change 类型。
 */
private suspend fun validatePushRequest(
    store: BlindSyncStore,
    request: SyncPushRequest,
    identity: SyncDeviceIdentity,
    authenticator: SyncDeviceAuthenticator,
    signatureService: DeviceArtifactSignatureService,
    expectedKind: SyncArtifactKind?,
) {
    request.artifacts.forEach { artifact ->
        if (artifact.descriptor.workspaceId != identity.workspaceId) {
            throw SyncHttpException(HttpStatusCode.Forbidden, "workspace_identity_mismatch")
        }
        if (
            expectedKind?.let { kind -> artifact.descriptor.kind != kind }
                ?: (artifact.descriptor.kind == SyncArtifactKind.CHANGE)
        ) {
            throw SyncHttpException(HttpStatusCode.BadRequest, "artifact_endpoint_kind_mismatch")
        }
        if (!JvmSyncArtifactVerifier.verify(artifact)) {
            throw SyncHttpException(HttpStatusCode.BadRequest, "artifact_integrity_failed")
        }
        val authorIdentity = SyncDeviceIdentity(identity.workspaceId, artifact.descriptor.actorId)
        if (!authenticator.isActive(authorIdentity)) {
            // 撤销后只允许重传已经接受的历史，不允许另一设备代传该作者的新签名内容。
            val existing = store.getArtifacts(identity.workspaceId, artifact.descriptor.kind, listOf(artifact.artifactId))
            if (existing.singleOrNull() != artifact) throw SyncHttpException(HttpStatusCode.Forbidden, "artifact_author_revoked")
        }
        val signingPublicKey = authenticator.signingPublicKey(authorIdentity)
            ?: throw SyncHttpException(HttpStatusCode.Forbidden, "artifact_author_unknown")
        try {
            if (!signatureService.verify(artifact, signingPublicKey)) {
                throw SyncHttpException(HttpStatusCode.Forbidden, "artifact_signature_failed")
            }
        } finally {
            signingPublicKey.fill(0)
        }
    }
}

/**
 * 拒绝父 Change 缺失或跨对象的孤儿节点，保证服务端声明的每个 head 都能完整向根遍历。
 *
 * 已授权设备可以转发其他设备历史密文，因此这里只检查 DAG 身份，不要求原作者等于当前上传者。
 *
 * @param store 当前盲同步存储。
 * @param workspaceId 当前认证工作区。
 * @param artifact 即将写入的 Change。
 */
private suspend fun validateExistingParents(
    store: BlindSyncStore,
    workspaceId: WorkspaceId,
    artifact: BlindSyncArtifact,
) {
    val parentIds = artifact.descriptor.parentArtifactIds
    if (parentIds.isEmpty()) {
        return
    }
    val parents = store.getChanges(workspaceId, parentIds)
    if (parents.size != parentIds.size) {
        throw SyncHttpException(HttpStatusCode.Conflict, "missing_parent_change")
    }
    if (parents.any { parent -> parent.descriptor.objectId != artifact.descriptor.objectId }) {
        throw SyncHttpException(HttpStatusCode.BadRequest, "cross_object_parent_change")
    }
}

/** 随机 Actor 标识请求头。 */
private const val ACTOR_HEADER: String = "X-Tagloom-Actor"

/** HTTP Bearer 认证前缀。 */
private const val BEARER_PREFIX: String = "Bearer "

/** 使用内容清单差集同步而不走 Change DAG 的密文类型。 */
private val NON_CHANGE_ARTIFACT_KINDS: List<SyncArtifactKind> = listOf(
    SyncArtifactKind.SNAPSHOT,
    SyncArtifactKind.BLOB,
    SyncArtifactKind.MANIFEST,
)
