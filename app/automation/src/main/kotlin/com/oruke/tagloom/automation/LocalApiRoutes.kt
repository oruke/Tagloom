package com.oruke.tagloom.automation

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.response.*
import io.ktor.server.routing.*
import com.oruke.tagloom.core.external.ExternalCallException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

/**
 * 安装受控本机 API；HTTP 路由仅转换协议，业务和权限由统一控制器执行。
 * @param controller 当前宿主外部会话控制器。
 */
fun Application.localAutomationApi(controller: LocalAutomationController) {
    install(ContentNegotiation) { json(localApiJson) }
    install(StatusPages) {
        exception<Throwable> { call, error ->
            if (error is CancellationException) throw error
            val failure = localApiFailure(error)
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            call.respondText(localApiJson.encodeToString(LocalApiProblem.serializer(), LocalApiProblem(
                "urn:tagloom:problem:${failure.code}", failure.code, failure.status, failure.code,
            )), ContentType.parse("application/problem+json"), HttpStatusCode.fromValue(failure.status))
        }
    }
    intercept(ApplicationCallPipeline.Call) {
        validateLocalApiOrigin(call)
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.response.headers.append("X-Content-Type-Options", "nosniff")
    }
    routing {
        route("/v1") {
            get("/tools") { call.respond(controller.tools(call.localBearer(), call.localSessionId())) }
            get("/openapi") {
                controller.tools(call.localBearer(), call.localSessionId())
                val document = requireNotNull(LocalAutomationController::class.java.getResourceAsStream("/openapi.json")).use { it.readBytes().decodeToString() }
                call.respondText(document, ContentType.Application.Json)
            }
            post("/calls") {
                val token = call.localBearer()
                val input = call.receiveLocalJson(LocalCallInput.serializer())
                call.respondCall(controller, token, input)
            }
            get("/calls/{callId}") { call.respond(controller.result(call.localBearer(), call.localId("callId"), call.localSessionId())) }
            delete("/calls/{callId}") { call.respond(controller.cancel(call.localBearer(), call.localId("callId"), call.localSessionId())) }
            get("/approvals") { call.respond(controller.approvals(
                call.localBearer(), call.pageInteger("limit", 20, 1..100), call.pageInteger("offset", 0, 0..1_000_000),
                call.localSessionId(),
            )) }
            put("/approvals/{callId}") {
                val token = call.localBearer()
                val input = call.receiveLocalJson(LocalApprovalInput.serializer())
                call.respond(withTimeout(30_000) { controller.approve(token, call.localId("callId"), input, call.localSessionId()) })
            }
            post("/grants") {
                val token = call.localBearer()
                val input = call.receiveLocalJson(LocalGrantInput.serializer())
                call.respond(HttpStatusCode.Created, controller.grant(token, input, call.localSessionId()))
            }
            delete("/grants/{principalId}") {
                controller.revoke(call.localBearer(), call.localId("principalId"), call.localSessionId()); call.respond(HttpStatusCode.NoContent)
            }
            get("/notes") { call.respondCall(controller, call.localBearer(), LocalCallInput("list_notes", call.paginationArgs())) }
            post("/notes") {
                val token = call.localBearer()
                call.respondCall(controller, token, LocalCallInput("create_note", call.receiveLocalJson(JsonObject.serializer())))
            }
            get("/notes/{noteId}") { call.respondCall(controller, call.localBearer(), LocalCallInput("get_note",
                buildJsonObject { put("noteId", call.localId("noteId")) })) }
            put("/notes/{noteId}") {
                val token = call.localBearer()
                val args = call.receiveLocalJson(JsonObject.serializer())
                call.respondCall(controller, token, LocalCallInput("update_note", JsonObject(args + ("noteId" to JsonPrimitive(call.localId("noteId"))))))
            }
            delete("/notes/{noteId}") {
                val args = buildJsonObject {
                    put("noteId", call.localId("noteId"))
                    put("expectedRevision", call.request.queryParameters["expectedRevision"] ?: throw LocalApiException(400, "invalid_arguments"))
                }
                call.respondCall(controller, call.localBearer(), LocalCallInput("delete_note", args))
            }
            get("/notes/{noteId}/blocks/{blockId}") { call.respondCall(controller, call.localBearer(), LocalCallInput("get_block",
                buildJsonObject { put("noteId", call.localId("noteId")); put("blockId", call.localId("blockId")) })) }
            get("/notes/{noteId}/backlinks") { call.respondCall(controller, call.localBearer(), LocalCallInput("get_backlinks",
                JsonObject(call.paginationArgs() + ("noteId" to JsonPrimitive(call.localId("noteId")))))) }
            get("/search") { call.respondCall(controller, call.localBearer(), LocalCallInput("search_notes", JsonObject(
                call.paginationArgs() + ("query" to JsonPrimitive(call.request.queryParameters["query"] ?: throw LocalApiException(400, "invalid_arguments"))),
            ))) }
            get("/tags") { call.respondCall(controller, call.localBearer(), LocalCallInput("get_tags", call.paginationArgs())) }
            get("/tags/{tagId}") { call.respondCall(controller, call.localBearer(), LocalCallInput("get_tag_schema",
                buildJsonObject { put("tagId", call.localId("tagId")) })) }
            get("/schema") { call.respondCall(controller, call.localBearer(), LocalCallInput("get_workspace_schema")) }
            get("/saved-queries") { call.respondCall(controller, call.localBearer(), LocalCallInput("get_saved_queries", call.paginationArgs())) }
            get("/saved-queries/{queryId}") { call.respondCall(controller, call.localBearer(), LocalCallInput("get_saved_query",
                buildJsonObject { put("queryId", call.localId("queryId")) })) }
            get("/resources") {
                val uri = call.request.queryParameters["uri"] ?: throw LocalApiException(400, "invalid_resource_uri")
                call.respondCall(controller, call.localBearer(), resourceCall(uri))
            }
        }
    }
}

/**
 * 执行统一调用并将待确认状态映射为 HTTP 202。
 * @param controller 当前工作区控制器。
 * @param token Bearer 凭据。
 * @param input 已转换的共享输入。
 */
private suspend fun ApplicationCall.respondCall(controller: LocalAutomationController, token: String, input: LocalCallInput) {
    val response = withTimeout(30_000) { controller.call(token, input, localSessionId()) }
    respond(if (response.state == "pending") HttpStatusCode.Accepted else HttpStatusCode.OK, response)
}

/** @return 已校验分页参数的工具 JSON 表示。 */
private fun ApplicationCall.paginationArgs(): JsonObject = buildJsonObject {
    put("limit", pageInteger("limit", 20, 1..100)); put("offset", pageInteger("offset", 0, 0..1_000_000))
}

/**
 * 将失败映射成可对外显示的稳定错误，不返回库异常或业务参数。
 * @param error 当前执行失败。
 * @return 标准 HTTP 状态与错误码。
 */
internal fun localApiFailure(error: Throwable): LocalApiException = when (error) {
    is LocalApiException -> error
    is ExternalCallException -> LocalApiException(when (error.code) {
        "authorization_denied", "ai_access_policy_immutable", "system_page_protected" -> 403
        "not_found" -> 404
        "revision_conflict" -> 409
        "workspace_locked", "workspace_read_only" -> 423
        else -> 400
    }, error.code)
    is SerializationException, is IllegalArgumentException -> LocalApiException(400, "invalid_arguments")
    else -> LocalApiException(500, "execution_failed")
}
