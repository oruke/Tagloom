package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.crypto.ServerRecoveryCredentials
import com.oruke.tagloom.core.sync.SyncDeviceRegistration
import com.oruke.tagloom.core.sync.SyncWorkspaceRegistration
import com.oruke.tagloom.core.workspace.WorkspaceManifestCodec
import com.oruke.tagloom.core.workspace.WorkspaceManifestService
import com.oruke.tagloom.filesystem.jvm.crypto.LibsodiumCryptoProvider
import com.oruke.tagloom.sync.http.ServerRecoveryException
import com.oruke.tagloom.sync.http.jvm.ServerRecoveryClient
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * 首次连接时优先用恢复证明加入已有服务器工作区；不存在时才使用部署者凭据登记。
 * @param session 本机已认证会话，用于核对恢复身份与权威对象。
 * @param access 待登记的独立设备材料，调用前已保存以支持重试。
 * @param credentials 已验证属于当前工作区的恢复证明。
 * @param bootstrapToken 部署者访问码，服务器已存在该工作区时可以为空。
 * @param client 本次 HTTPS 客户端。
 */
internal suspend fun enrollServerRecoveryDevice(
    session: DesktopWorkspaceSession,
    access: ServerRecoveryAccess,
    credentials: ServerRecoveryCredentials,
    bootstrapToken: String,
    client: ServerRecoveryClient,
) {
    val tokenDigest = MessageDigest.getInstance("SHA-256").digest(access.token.encodeToByteArray()).serverRecoveryHex()
    val recovered = try {
        client.recover(credentials, access.actorId, SyncDeviceRegistration(tokenDigest, access.signingPublicKeyHex))
    } catch (error: ServerRecoveryException) {
        if (error.code != "authentication_failed") throw error
        null
    }
    if (recovered == null) {
        if (bootstrapToken.isBlank()) throw ServerRecoveryException("registration_required")
        client.register(access.workspaceId, bootstrapToken,
            SyncWorkspaceRegistration(access.actorId, tokenDigest, access.signingPublicKeyHex))
        return
    }
    if (recovered.workspaceId != access.workspaceId || recovered.snapshot.workspaceId != access.workspaceId ||
        recovered.device.actorId != access.actorId || recovered.device.signingPublicKeyHex != access.signingPublicKeyHex ||
        recovered.device.revokedAt != null) throw ServerRecoveryException("invalid_response")

    // 文件夹恢复没有旧设备的远端版本记录；必须认证并比较权威对象，不能直接把当前版本号当作覆盖许可。
    val archive = Files.createTempFile("tagloom-server-compare-", ".zip")
    try {
        client.download(recovered.snapshot, access.actorId, access.token, archive)
        session.requireMatchingServerCopy(archive)
        access.snapshot = recovered.snapshot
    } finally { Files.deleteIfExists(archive) }
}

/**
 * 只在本机认证远端 manifest 并比较对象目录；本机解锁槽不同不影响笔记身份判断。
 * @param archive 已校验摘要与大小的密文归档，不解压业务正文。
 */
private suspend fun DesktopWorkspaceSession.requireMatchingServerCopy(archive: Path) = sessionMutex.withLock {
    requireWritable()
    val bytes = readServerRecoveryManifest(archive)
    val header = WorkspaceManifestCodec.decode(bytes).publicHeader
    if (header.workspaceId != workspaceId) throw ServerRecoveryException("invalid_response")
    val crypto = LibsodiumCryptoProvider()
    crypto.initialize()
    val remote = WorkspaceManifestService(crypto).inspectWithMasterKey(bytes, unlocked.workspaceMasterKey)
    if (remote.objects.toSet() != unlocked.payload.objects.toSet()) throw ServerRecoveryException("recovery_revision_changed")
}

/**
 * 有界读取归档中的 manifest 密文，阻止 ZIP 条目虚报大小导致内存膨胀。
 * @param archive 受控临时密文路径。
 * @return 交给现有 manifest 认证流程的字节。
 */
private fun readServerRecoveryManifest(archive: Path): ByteArray = ZipFile(archive.toFile()).use { zip ->
    val entry = zip.getEntry("manifest.enc") ?: throw ServerRecoveryException("recovery_archive_integrity_failed")
    zip.getInputStream(entry).use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            // 与 WorkspaceManifestCodec 的 4 MiB 容器上限一致，不信任 ZIP 提供的解压长度。
            if (output.size() + count > 4 * 1024 * 1024) throw ServerRecoveryException("recovery_archive_integrity_failed")
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
}
