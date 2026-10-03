package com.oruke.tagloom.automation

import com.oruke.tagloom.core.agent.AgentAuditOutcome
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.SavedQuery
import com.oruke.tagloom.workspace.loadNotes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** 使用独立官方客户端访问安装分发的 MCP，磁盘、审计和会话切换由真实宿主独立核对。 */
class McpClientAcceptanceTest {
    /** 跨 SDK 验证发现、资源、参数错误、授权过滤、审批和加密 CRUD 闭环。 */
    @Test
    fun officialClientsCompleteApprovedEncryptedWorkflow() = runBlocking {
        runScenario("workflow")
    }

    /** 保持同一 stdio 进程，验证撤销、锁定、重新解锁与 API 停止均切断访问。 */
    @Test
    fun officialClientCannotReuseRevokedOrLockedSessions() = runBlocking {
        runScenario("lifecycle")
    }

    /**
     * 在隔离宿主中运行独立 Node 客户端，凭据只通过环境传递，不写配置文件或命令参数。
     * @param scenario 客户端业务场景名称。
     * @return 全部客户端和宿主断言通过后返回；阻塞进程读写限制在 IO 调度器。
     */
    private suspend fun runScenario(scenario: String) = withContext(Dispatchers.IO) {
        val fixture = AutomationTestWorkspace.create()
        val javaName = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        val script = Path.of(requireNotNull(javaClass.getResource("/mcp-client/acceptance.mjs")).toURI())
        val process = try { ProcessBuilder("node", script.toString(), scenario).apply {
            environment()["TAGLOOM_API_URL"] = fixture.url
            environment()["TAGLOOM_API_TOKEN"] = fixture.ownerToken
            environment()["TAGLOOM_TEST_JAVA"] = Path.of(System.getProperty("java.home"), "bin", javaName).toString()
            environment()["TAGLOOM_TEST_CLI_HOME"] = System.getProperty("tagloom.cli.home")
            environment()["TAGLOOM_TEST_MCP_CLIENT_HOME"] = System.getProperty("tagloom.mcp.client.home")
        }.start() } catch (error: Exception) {
            fixture.close()
            throw error
        }
        val errors = CompletableFuture.supplyAsync { process.errorStream.bufferedReader().use { it.readText() } }
        val passed = mutableListOf<String>()
        try {
            process.inputStream.bufferedReader(Charsets.UTF_8).use { output ->
                process.outputStream.bufferedWriter(Charsets.UTF_8).use { input ->
                    while (true) {
                        val line = CompletableFuture.supplyAsync { output.readLine() }.get(45, TimeUnit.SECONDS) ?: break
                        val event = Json.parseToJsonElement(line).jsonObject
                        when (event["event"]?.jsonPrimitive?.content) {
                            "check" -> passed += event.getValue("name").jsonPrimitive.content
                            "failed" -> fail("Independent MCP client failed at ${event.getValue("name").jsonPrimitive.content}")
                            "checkpoint" -> {
                                val reply = verifyCheckpoint(fixture, event)
                                input.write(reply.toString()); input.newLine(); input.flush()
                            }
                            "completed" -> break
                            else -> fail("Independent client stdout must contain only acceptance events")
                        }
                    }
                }
            }
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "independent client must exit")
            assertEquals(0, process.exitValue(), "independent client exit status")
            errors.get(5, TimeUnit.SECONDS)
            assertTrue(passed.isNotEmpty(), "client must report observable business checks")
            // 报告只保留检查名称，不输出凭据、正文、私密路径或原始 stderr。
            println("MCP $scenario: ${passed.size} checks passed: ${passed.joinToString()}")
        } finally {
            if (process.isAlive) process.destroyForcibly()
            fixture.close()
        }
    }

    /**
     * 在独立客户端请求的阶段核对真实持久化结果，生命周期控制只存在于测试中。
     * @param fixture 本轮独占的真实加密工作区和 API。
     * @param event 不含凭据的检查点身份和状态。
     * @return 安全确认和可选查询身份，不包含主密钥或访问凭据。
     */
    private suspend fun verifyCheckpoint(fixture: AutomationTestWorkspace, event: JsonObject): JsonObject {
        when (event.getValue("name").jsonPrimitive.content) {
            "seed-query" -> {
                val query = fixture.session().saveQuery(SavedQuery("Query sample", text = "shared"))
                return buildJsonObject { put("ok", true); put("queryId", query.id.value) }
            }
            "write" -> {
                val noteId = ObjectId(event.getValue("noteId").jsonPrimitive.content)
                val callId = event.getValue("callId").jsonPrimitive.content
                val expectedTitle = event.getValue("title").jsonPrimitive.content
                assertEquals(expectedTitle, fixture.session().loadNotes().single { it.id == noteId }.snapshot.payload.title)
                fixture.assertAuditedTransaction(noteId, callId)
                fixture.assertEncrypted("互操作样例", "approved sample body")
            }
            "deleted" -> {
                val deletedId = event.getValue("noteId").jsonPrimitive.content
                assertFalse(fixture.session().loadNotes().any { it.id.value == deletedId })
                assertTrue(fixture.audits().any {
                    it.requestId == event.getValue("callId").jsonPrimitive.content && it.outcome == AgentAuditOutcome.EXECUTED
                })
                fixture.reopen()
                assertFalse(fixture.session().loadNotes().any { it.id.value == deletedId })
                assertEquals(2, fixture.session().loadNotes().size)
            }
            "revoked" -> {
                val callId = event.getValue("callId").jsonPrimitive.content
                assertTrue(fixture.audits().any { it.requestId == callId && it.outcome == AgentAuditOutcome.CANCELED })
                assertEquals(1, fixture.session().loadNotes().size)
            }
            "lock" -> fixture.manager.lock()
            "reopen" -> fixture.reopen()
            "stop-api" -> fixture.stopApi()
            else -> fail("Unknown host acceptance checkpoint")
        }
        return buildJsonObject { put("ok", true) }
    }
}
