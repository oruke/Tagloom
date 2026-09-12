package com.oruke.tagloom

import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.model.WorkspaceId
import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.sync.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 用设备真实回环 TCP 提供隔离的密文协议测试环境，生产 HTTP 客户端和本地副本均不替换。 */
internal class LoopbackSyncServer(
    /** 本次测试唯一工作区。 */
    private val workspaceId: WorkspaceId,
    /** 本次测试登记的设备。 */
    private val actorId: ActorId,
) : AutoCloseable {
    /** 每次测试生成的临时认证材料，不写入源码或文件。 */
    val accessToken: String = UUID.randomUUID().toString() + UUID.randomUUID().toString()
    /** 只绑定设备自身的动态端口。 */
    private val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    /** 客户端显式允许测试 HTTP 后使用的动态地址。 */
    val baseUri: URI = URI("http", null, listener.inetAddress.hostAddress, listener.localPort, null, null, null)
    /** 可观察的远端密文记录，用于人工核对上传与去重结果。 */
    val artifacts = ConcurrentHashMap<String, BlindSyncArtifact>()
    /** 独立线程执行真实阻塞 socket I/O。 */
    private val executor = Executors.newSingleThreadExecutor()
    /** 控制一次在途响应暂停，以验证会话关闭取消网络。 */
    val pauseNextResponse = AtomicBoolean(false)
    /** 通知测试客户端请求已真实到达。 */
    val requestReceived = CountDownLatch(1)
    /** 关闭测试前释放被暂停的 socket。 */
    private val resumeResponse = CountDownLatch(1)
    /** 保存线程失败，结束测试时不能静默丢失协议断言。 */
    private val worker = executor.submit {
        while (!listener.isClosed) {
            try {
                listener.accept().use(::serve)
            } catch (error: SocketException) {
                if (!listener.isClosed) throw error
            }
        }
    }

    /**
     * 处理测试协议中的单次认证请求，响应后关闭连接避免残留线程。
     * @param socket 当前设备回环连接。
     */
    private fun serve(socket: Socket) {
        socket.soTimeout = 10_000
        val input = socket.getInputStream().bufferedReader(Charsets.UTF_8)
        val request = requireNotNull(input.readLine()).split(' ')
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = requireNotNull(input.readLine())
            if (line.isEmpty()) break
            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
        }
        check(request[1].startsWith("/v1/workspaces/${workspaceId.value}/sync/"))
        val authorized = headers["authorization"] == "Bearer $accessToken" && headers["x-tagloom-actor"] == actorId.value
        val length = headers["content-length"]?.toInt() ?: 0
        require(length in 0..1_048_576)
        // 测试协议只含 ASCII 标识和 Base64 密文，所以字节长度等于 UTF-8 字符长度。
        val body = CharArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(body, offset, length - offset)
            check(count > 0)
            offset += count
        }
        if (pauseNextResponse.compareAndSet(true, false)) {
            requestReceived.countDown()
            check(resumeResponse.await(10, TimeUnit.SECONDS))
            return
        }
        val response = if (authorized) route(request[1].substringAfter("/sync/"), body.concatToString()) else "{}"
        val bytes = response.encodeToByteArray()
        val status = if (authorized) "200 OK" else "401 Unauthorized"
        socket.getOutputStream().apply {
            write("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".encodeToByteArray())
            write(bytes)
            flush()
        }
    }

    /**
     * 执行与生产服务一致的 heads、内容 ID 清单及幂等收发边界。
     * @param path 已认证工作区下的固定测试路由。
     * @param body 完整 JSON 请求。
     * @return 完整 JSON 响应。
     */
    private fun route(path: String, body: String): String = when (path) {
        "change-heads" -> {
            val changes = artifacts.values.filter { it.descriptor.kind == SyncArtifactKind.CHANGE }
            val parents = changes.flatMap { it.descriptor.parentArtifactIds }.toSet()
            val heads = changes.groupBy { requireNotNull(it.descriptor.objectId) }.map { (id, values) ->
                ObjectChangeHeads(id, values.map { it.artifactId }.filterNot(parents::contains).sorted())
            }
            CanonicalJson.encodeToString(SyncHeadsResponse.serializer(), SyncHeadsResponse(heads))
        }
        "artifacts/inventory" -> {
            val entries = artifacts.values.filter { it.descriptor.kind != SyncArtifactKind.CHANGE }
                .groupBy { it.descriptor.kind }.map { (kind, values) ->
                    SyncArtifactInventoryEntry(kind, values.map { it.artifactId }.sorted())
                }
            CanonicalJson.encodeToString(SyncArtifactInventoryResponse.serializer(), SyncArtifactInventoryResponse(entries))
        }
        "changes/push", "artifacts/push" -> {
            val request = CanonicalJson.decodeFromString(SyncPushRequest.serializer(), body)
            val count = request.artifacts.count { artifact ->
                check(artifact.descriptor.workspaceId == workspaceId)
                val existing = artifacts.putIfAbsent(artifact.artifactId, artifact)
                check(existing == null || existing == artifact)
                existing == null
            }
            CanonicalJson.encodeToString(SyncPushResponse.serializer(), SyncPushResponse(count, request.artifacts.size - count))
        }
        "changes/fetch" -> {
            val request = CanonicalJson.decodeFromString(SyncFetchRequest.serializer(), body)
            CanonicalJson.encodeToString(SyncFetchResponse.serializer(), SyncFetchResponse(request.artifactIds.mapNotNull(artifacts::get)))
        }
        "artifacts/fetch" -> {
            val request = CanonicalJson.decodeFromString(SyncArtifactFetchRequest.serializer(), body)
            val found = request.artifactIds.mapNotNull(artifacts::get).filter { it.descriptor.kind == request.kind }
            CanonicalJson.encodeToString(SyncArtifactFetchResponse.serializer(), SyncArtifactFetchResponse(found))
        }
        else -> error("unsupported-test-route")
    }

    /** 停止测试监听、释放暂停响应并确认后台协议断言全部成功。 */
    override fun close() {
        listener.close()
        resumeResponse.countDown()
        executor.shutdown()
        worker.get(10, TimeUnit.SECONDS)
    }
}
