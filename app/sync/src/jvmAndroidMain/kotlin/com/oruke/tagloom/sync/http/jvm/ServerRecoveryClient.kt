package com.oruke.tagloom.sync.http.jvm

import com.oruke.tagloom.sync.http.ServerRecoveryException
import com.oruke.tagloom.core.crypto.ServerRecoveryCredentials
import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.model.WorkspaceId
import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.sync.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.*
import kotlinx.serialization.KSerializer
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * 通过 HTTPS 传输完整密文副本；不持有工作区解密材料，也不自动跟随重定向。
 * @param serverUrl 用户指定的服务器根地址，可包含反向代理路径前缀。
 * @param allowLoopbackHttp 仅供隔离测试显式启用回环 HTTP。
 */
class ServerRecoveryClient(serverUrl: String, allowLoopbackHttp: Boolean = false) : AutoCloseable {
    /** 已校验且不含凭据、查询串、fragment 的远端根地址。 */
    private val base = validateServerRecoveryUrl(serverUrl, allowLoopbackHttp)
    /** 每次业务调用独立创建，调用结束关闭连接与在途请求。 */
    private val client = HttpClient(CIO) {
        followRedirects = false
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 60_000
            requestTimeoutMillis = 10 * 60_000
        }
    }

    /**
     * 使用部署者凭据首次登记工作区；相同设备材料可安全重试。
     * @param workspaceId 本机工作区身份。
     * @param bootstrapToken 部署者提供的登记凭据，不会保存在客户端。
     * @param registration 随机设备身份、公钥和 Token 摘要。
     * @return 已登记的公开设备描述。
     */
    suspend fun register(workspaceId: WorkspaceId, bootstrapToken: String, registration: SyncWorkspaceRegistration): SyncDeviceDescription {
        return client.preparePut("$base/v1/workspaces/${workspaceId.value}") {
            bearerAuth(bootstrapToken)
            contentType(ContentType.Application.Json)
            setBody(CanonicalJson.encodeToString(SyncWorkspaceRegistration.serializer(), registration))
        }.execute { decode(it, SyncDeviceDescription.serializer()) }
    }

    /**
     * 用独立恢复认证材料定位工作区并授权新设备，原 Recovery Key 不离开本机。
     * @param credentials Recovery Key 用途隔离派生的定位与认证材料。
     * @param actorId 当前设备新身份。
     * @param registration 当前设备公钥与凭据摘要。
     * @return 工作区与可下载副本描述。
     */
    suspend fun recover(credentials: ServerRecoveryCredentials, actorId: ActorId, registration: SyncDeviceRegistration): SyncRecoveredWorkspace {
        return client.preparePut("$base/v1/recoveries/${credentials.recoveryId}/devices/${actorId.value}") {
            bearerAuth(credentials.accessToken)
            contentType(ContentType.Application.Json)
            setBody(CanonicalJson.encodeToString(SyncDeviceRegistration.serializer(), registration))
        }.execute { decode(it, SyncRecoveredWorkspace.serializer()) }
    }

    /**
     * 流式上传密文并以旧版本为前置条件确认，冲突时不重试覆盖。
     * @param workspaceId 工作区身份。
     * @param actorId 当前设备身份。
     * @param token 当前设备访问凭据。
     * @param revision 客户端已知的服务端版本，首次为零。
     * @param recoveryId 恢复定位符。
     * @param recoveryTokenSha256 恢复访问凭据摘要。
     * @param archive 本机生成的完整密文 ZIP。
     * @return 服务器已确认副本的元数据。
     */
    suspend fun upload(workspaceId: WorkspaceId, actorId: ActorId, token: String, revision: Long,
                       recoveryId: String, recoveryTokenSha256: String, archive: Path): SyncRecoverySnapshot {
        val archiveDigest = recoveryArchiveDigest(archive)
        val archiveSize = Files.size(archive)
        return client.preparePut("$base/v1/workspaces/${workspaceId.value}/recovery-snapshot") {
            bearerAuth(token)
            header("X-Tagloom-Actor", actorId.value)
            header("X-Tagloom-Recovery-Revision", revision)
            header("X-Tagloom-Recovery-Id", recoveryId)
            header("X-Tagloom-Recovery-Token-Sha256", recoveryTokenSha256)
            header("X-Tagloom-Archive-Sha256", archiveDigest)
            setBody(RecoveryArchiveContent(archive))
        }.execute { response ->
            decode(response, SyncRecoverySnapshot.serializer()).also {
                if (it.workspaceId != workspaceId || revision == Long.MAX_VALUE || it.revision != revision + 1 ||
                    it.sha256 != archiveDigest || it.size != archiveSize) throw ServerRecoveryException("invalid_response")
                java.time.Instant.parse(it.createdAt)
            }
        }
    }

    /**
     * 流式下载并校验大小、摘要与选择时的版本，服务器切换副本时要求重新尝试。
     * @param snapshot 已取得的副本描述。
     * @param actorId 刚登记的新设备身份。
     * @param token 新设备访问凭据。
     * @param destination 受控的临时密文文件。
     */
    suspend fun download(snapshot: SyncRecoverySnapshot, actorId: ActorId, token: String, destination: Path) {
        require(snapshot.size in 1..MAXIMUM_DOWNLOAD_BYTES && snapshot.revision > 0) { "recovery_archive_too_large" }
        client.prepareGet("$base/v1/workspaces/${snapshot.workspaceId.value}/recovery-snapshot") {
            bearerAuth(token)
            header("X-Tagloom-Actor", actorId.value)
        }.execute { response ->
            requireSuccess(response)
            if (response.headers["X-Tagloom-Archive-Sha256"] != snapshot.sha256 ||
                response.headers["X-Tagloom-Recovery-Revision"] != snapshot.revision.toString())
                throw ServerRecoveryException("recovery_revision_changed")
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            Files.newOutputStream(destination).use { output ->
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = channel.readAvailable(buffer, 0, buffer.size)
                    if (count < 0) break
                    total += count
                    if (total > snapshot.size) throw ServerRecoveryException("recovery_archive_integrity_failed")
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
            }
            if (total != snapshot.size || digest.digest().recoveryHex() != snapshot.sha256)
                throw ServerRecoveryException("recovery_archive_integrity_failed")
        }
    }

    /**
     * 解码有限大小的协议响应；错误响应只映射状态码，避免回显服务端任意内容。
     * @param response 远端 HTTP 响应。
     * @param serializer 预期协议编解码器。
     * @return 通过结构校验的响应对象。
     */
    private suspend fun <T> decode(response: HttpResponse, serializer: KSerializer<T>): T {
        requireSuccess(response)
        val bytes = ByteArray(16 * 1024 + 1)
        val channel = response.bodyAsChannel()
        var total = 0
        while (total < bytes.size) {
            val count = channel.readAvailable(bytes, total, bytes.size - total)
            if (count < 0) break
            total += count
        }
        if (total >= bytes.size) throw ServerRecoveryException("invalid_response")
        return CanonicalJson.decodeFromString(serializer, bytes.decodeToString(0, total))
    }

    /**
     * 将状态映射为有限错误分类，不读取或记录可能包含敏感内容的服务端错误体。
     * @param response 远端响应。
     */
    private fun requireSuccess(response: HttpResponse) {
        if (response.status.value in 200..299) return
        throw ServerRecoveryException(when (response.status.value) {
            401, 403 -> "authentication_failed"
            409 -> "recovery_revision_changed"
            413 -> "recovery_archive_too_large"
            else -> "server_recovery_failed"
        })
    }

    /** 关闭此次传输，避免连接和认证请求超出业务调用生命周期。 */
    override fun close() = client.close()

    private companion object {
        /** 与现有备份规模一致的客户端下载硬上限；不信任远端提供的文件长度。 */
        const val MAXIMUM_DOWNLOAD_BYTES: Long = 512L * 1024 * 1024
    }
}

/**
 * 校验服务器地址，避免凭据进入明文连接、URL userinfo 或重定向。
 * @param value 用户指定的地址。
 * @param allowLoopbackHttp 隔离测试是否允许字面回环主机 HTTP。
 * @return 标准化的地址。
 */
fun validateServerRecoveryUrl(value: String, allowLoopbackHttp: Boolean = false): String {
    val uri = try { URI(value.trim()) } catch (_: Exception) { throw ServerRecoveryException("invalid_server_url") }
    val secure = uri.scheme == "https"
    val localTest = allowLoopbackHttp && uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "[::1]")
    if ((!secure && !localTest) || uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null)
        throw ServerRecoveryException("invalid_server_url")
    return uri.toASCIIString().trimEnd('/')
}

/**
 * 流式计算密文文件摘要，不把大归档加载到内存。
 * @param archive 已完整发布的密文文件。
 * @return 小写 SHA-256 十六进制。
 */
fun recoveryArchiveDigest(archive: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(archive).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().recoveryHex()
}

/** @return 协议使用的小写十六进制文本。 */
private fun ByteArray.recoveryHex(): String = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

/** 将已加密的归档作为固定长度请求体流式发送。 */
private class RecoveryArchiveContent(
    /** 仅包含密文的受控源文件。 */
    private val archive: Path,
) : OutgoingContent.WriteChannelContent() {
    /** 明确使用二进制流，不暴露原始文档类型。 */
    override val contentType = ContentType.Application.OctetStream
    /** 已完整落盘的源文件长度。 */
    override val contentLength = Files.size(archive)

    /**
     * 在请求生命周期内复制密文，取消时由 Ktor 关闭通道。
     * @param channel 当前 HTTP 请求体输出通道。
     */
    override suspend fun writeTo(channel: ByteWriteChannel) {
        Files.newInputStream(archive).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                channel.writeFully(buffer, 0, count)
            }
        }
    }
}
