package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.crypto.ServerRecoveryCredentials
import com.oruke.tagloom.core.sync.SyncDeviceRegistration
import com.oruke.tagloom.core.sync.SyncWorkspaceRegistration
import com.oruke.tagloom.sync.http.ServerRecoveryException
import com.oruke.tagloom.sync.http.jvm.ServerRecoveryClient
import java.security.MessageDigest

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
    session.requireWritable()
    require(session.workspaceId == access.workspaceId) { "sync_workspace_mismatch" }
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

    // 登记只确认设备权限，不把服务器版本号当作覆盖许可。
    // 首次副本保留 snapshot=null，由统一同步入口下载、AEAD 认证并合并离线分支后才能发布。
}
