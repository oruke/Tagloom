package com.oruke.tagloom.automation

import com.oruke.tagloom.core.agent.AgentAuditEvent
import com.oruke.tagloom.core.crypto.*
import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.workspace.WorkspaceManifestService
import com.oruke.tagloom.core.workspace.ManifestObjectKind
import com.oruke.tagloom.core.sync.ObjectChange
import com.oruke.tagloom.filesystem.jvm.JvmWorkspaceLifecycleService
import com.oruke.tagloom.filesystem.jvm.crypto.LibsodiumCryptoProvider
import com.oruke.tagloom.workspace.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** 在独立临时目录装配真实加密工作区、SQLite 和回环 API，不读取本机登记或用户数据。 */
internal class AutomationTestWorkspace private constructor(
    /** 测试创建且独占的临时目录。 */
    val root: Path,
    /** 实际生产工作区生命周期服务。 */
    val manager: DesktopWorkspaceManager,
    /** 仅此测试内使用的随机密码，不进入日志或持久化明文。 */
    private val password: String,
    /** 本轮随机 owner 凭据。 */
    val ownerToken: String,
    /** 生产回环服务器。 */
    private val server: LocalApiServer,
    /** 实际系统分配端口的回环 URL。 */
    val url: String,
) {
    /** 与当前 owner 认证的真实 HTTP 客户端。 */
    val owner = LocalApiClient(url, ownerToken)

    /** @return 当前真实工作区会话；锁定阶段不能获取。 */
    fun session(): DesktopWorkspaceSession = requireNotNull(manager.sessionOrNull())

    /**
     * 装配新的外部 HTTP 客户端。
     * @param token 测试内随机授权凭据。
     * @return 只通过回环 API 访问的外部客户端。
     */
    fun client(token: String): LocalApiClient = LocalApiClient(url, token)

    /**
     * 从已认证磁盘产物证明 Note、Change、成功审计与 manifest 使用同一确认事务。
     * @param noteId 已确认 Note 身份。
     * @param callId 对应外部调用 ID。
     */
    suspend fun assertAuditedTransaction(noteId: ObjectId, callId: String) {
        val crypto = LibsodiumCryptoProvider()
        val unlocked = JvmWorkspaceLifecycleService(root, WorkspaceManifestService(crypto)).unlockWithPassword(password)
        try {
            val changeId = requireNotNull(session().loadNotes().single { it.id == noteId }.snapshot.header.lastChangeId)
            val record = unlocked.payload.objects.single { it.kind == ManifestObjectKind.CHANGE && it.objectId == changeId.value }
            val envelopes = EnvelopeCryptoService(crypto)
            val bytes = envelopes.decrypt(EncryptionEnvelopeCodec.decode(Files.readAllBytes(root.resolve(record.path.value))),
                EnvelopeDecryptionContext(EnvelopeIdentity(unlocked.file.publicHeader.workspaceId, "object-change", record.objectId, record.revision),
                    record.keyId, WrappingKeyPurpose.CHANGE), unlocked.workspaceMasterKey)
            val change = try { CanonicalJson.decodeFromString(ObjectChange.serializer(), bytes.decodeToString()) }
                finally { bytes.fill(0) }
            assertTrue(change.transactionId in unlocked.payload.committedTransactionIds)
            assertEquals(change.transactionId, unlocked.payload.lastCommittedTransactionId)
            val auditRecord = unlocked.payload.objects.single { it.path.value == "history/agents/${change.transactionId.value}.audit.enc" }
            val auditBytes = envelopes.decrypt(EncryptionEnvelopeCodec.decode(Files.readAllBytes(root.resolve(auditRecord.path.value))),
                EnvelopeDecryptionContext(EnvelopeIdentity(unlocked.file.publicHeader.workspaceId, "audit-record", auditRecord.objectId, auditRecord.revision),
                    auditRecord.keyId, WrappingKeyPurpose.AUDIT), unlocked.workspaceMasterKey)
            val audit = try { CanonicalJson.decodeFromString(AgentAuditEvent.serializer(), auditBytes.decodeToString()) }
                finally { auditBytes.fill(0) }
            assertEquals(callId, audit.requestId)
            assertEquals(setOf(noteId), audit.targetNoteIds)
            assertEquals(com.oruke.tagloom.core.agent.AgentAuditOutcome.EXECUTED, audit.outcome)
        } finally { unlocked.destroy() }
    }

    /** 从已确认的磁盘密文独立认证审计记录，不复用 API 返回内容作为验证依据。 */
    suspend fun audits(): List<AgentAuditEvent> {
        val crypto = LibsodiumCryptoProvider()
        val manifests = WorkspaceManifestService(crypto)
        val unlocked = JvmWorkspaceLifecycleService(root, manifests).unlockWithPassword(password)
        return try {
            val envelopes = EnvelopeCryptoService(crypto)
            unlocked.payload.objects.filter { it.path.value.startsWith("history/agents/") }.map { record ->
                val plaintext = envelopes.decrypt(
                    EncryptionEnvelopeCodec.decode(Files.readAllBytes(root.resolve(record.path.value))),
                    EnvelopeDecryptionContext(
                        EnvelopeIdentity(unlocked.file.publicHeader.workspaceId, "audit-record", record.objectId, record.revision),
                        record.keyId, WrappingKeyPurpose.AUDIT,
                    ), unlocked.workspaceMasterKey,
                )
                try { CanonicalJson.decodeFromString(AgentAuditEvent.serializer(), plaintext.decodeToString()) }
                finally { plaintext.fill(0) }
            }
        } finally { unlocked.destroy() }
    }

    /** 验证关闭、重新认证和从密文快照重建真实业务状态的流程。 */
    suspend fun reopen() { manager.lock(); manager.unlock(root, password) }

    /** 停止真实 API 监听，供长驻 MCP 验证宿主离线时不会继续返回缓存正文。 */
    suspend fun stopApi() { server.close() }

    /**
     * 检查权威密文文件没有包含业务正文的明文字节。
     * @param texts 仅测试使用的脱敏样例文本。
     */
    fun assertEncrypted(vararg texts: String) {
        Files.walk(root).use { files -> files.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".enc") }.forEach { path ->
            val bytes = Files.readAllBytes(path)
            texts.forEach { text -> assertFalse(bytes.containsSequence(text.toByteArray(Charsets.UTF_8)), "authoritative artifact must be encrypted") }
        } }
    }

    /** 关闭真实依赖并只删除本测试创建的临时目录。 */
    suspend fun close() {
        try { owner.close(); server.close(); manager.lock() }
        finally {
            val normalized = root.toAbsolutePath().normalize()
            check(normalized.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()))
            check(normalized.fileName.toString().startsWith("tagloom-automation-test-"))
            Files.walk(normalized).use { files -> files.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    companion object {
        /**
         * 创建真实依赖测试容器，KDF 仅在测试使用低成本配置。
         * @param now API 单调时钟；有效期场景可以注入推进时钟。
         * @return 已认证空工作区和运行中的生产 API。
         */
        suspend fun create(now: () -> Long = { System.nanoTime() / 1_000_000 }): AutomationTestWorkspace {
            val root = Files.createTempDirectory("tagloom-automation-test-")
            val password = UUID.randomUUID().toString()
            val ownerToken = UUID.randomUUID().toString() + UUID.randomUUID().toString()
            val manager = DesktopWorkspaceManager(argon2idFactory = { salt -> Argon2idParameters(salt, 8192, 1, 1) })
            manager.prepareCreation(root, password); manager.confirmCreation()
            var cached: DesktopAgentWorkspaceService? = null
            var cachedSession: DesktopWorkspaceSession? = null
            val server = LocalApiServer({
                val session = manager.sessionOrNull()
                if (session == null) { cached = null; null }
                else {
                    // 测试提供者同样每次解锁只装配一个受控业务服务。
                    if (cachedSession !== session) { cachedSession = session; cached = DesktopAgentWorkspaceService(session) }
                    cached
                }
            }, ownerToken, 0, now)
            server.start()
            return AutomationTestWorkspace(root, manager, password, ownerToken, server, "http://127.0.0.1:${server.port()}")
        }
    }
}

/**
 * 在测试内检查短字节序列，不在失败输出中打印正文或密文。
 * @param target 待检查样例字节。
 * @return 存在连续序列时为真。
 */
private fun ByteArray.containsSequence(target: ByteArray): Boolean = target.isNotEmpty() && size >= target.size &&
    (0..size - target.size).any { offset -> target.indices.all { this[offset + it] == target[it] } }

/** 启动实际安装分发目录里的 JVM CLI/MCP 主入口，使用环境传递随机凭据。 */
internal class AutomationTestProcess(
    /** 生产 API 测试容器。 */
    fixture: AutomationTestWorkspace,
    /** 本进程身份凭据，不出现在命令行。 */
    token: String,
    /** 传给实际主入口的命令。 */
    vararg arguments: String,
) : AutoCloseable {
    /** 安装分发物提供的实际 CLI 进程。 */
    private val process = ProcessBuilder(buildList {
        val isWindows = System.getProperty("os.name").startsWith("Windows")
        add(Path.of(System.getProperty("java.home"), "bin", if (isWindows) "java.exe" else "java").toString())
        add("-cp"); add(Path.of(System.getProperty("tagloom.cli.home"), "lib").toString() + java.io.File.separator + "*")
        add("com.oruke.tagloom.automation.MainKt")
        addAll(if (isWindows) arguments.map { it.replace("\"", "\\\"") } else arguments.toList())
    }).apply { environment()["TAGLOOM_API_URL"] = fixture.url; environment()["TAGLOOM_API_TOKEN"] = token }.start()
    /** 标准输入，MCP 只写 JSON-RPC；CLI 审批写人工确认文本。 */
    private val input = process.outputStream.bufferedWriter(Charsets.UTF_8)
    /** UTF-8 标准输出。 */
    private val output = process.inputStream.bufferedReader(Charsets.UTF_8)
    /** 持续排空 stderr，防止协议日志或预览阻塞子进程。 */
    private val errors = CompletableFuture.supplyAsync { process.errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() } }

    /**
     * 给实际子进程写入一行协议或人工确认。
     * @param value 不含内嵌换行的内容。
     */
    fun send(value: String) { input.write(value); input.newLine(); input.flush() }

    /** @return 有限时读取的一行标准输出，必须是有效 JSON。 */
    fun jsonLine(): JsonObject {
        val line = CompletableFuture.supplyAsync { output.readLine() }.get(20, TimeUnit.SECONDS)
        return Json.parseToJsonElement(requireNotNull(line)) as JsonObject
    }

    /**
     * 请求实际 MCP 会话并验证响应关联。
     * @param id 本次 JSON-RPC ID。
     * @param method 协议方法。
     * @param params 方法参数。
     * @return 真实 stdio 响应。
     */
    fun rpc(id: Int, method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject {
        send(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params) }.toString())
        val response = jsonLine()
        assertEquals(id, response["id"]?.jsonPrimitive?.int)
        return response
    }

    /**
     * 关闭 stdin 并验证实际程序退出，没有把异常当成功。
     * @param expectedCode 预期退出码。
     */
    fun finish(expectedCode: Int = 0) {
        input.close()
        assertTrue(process.waitFor(20, TimeUnit.SECONDS), "entry process must exit")
        assertEquals(expectedCode, process.exitValue(), "entry process exit code")
        errors.get(5, TimeUnit.SECONDS)
    }

    /** 失败场景同样释放子进程，不在最终报告中输出凭据或预览正文。 */
    override fun close() { if (process.isAlive) process.destroyForcibly(); output.close() }
}
