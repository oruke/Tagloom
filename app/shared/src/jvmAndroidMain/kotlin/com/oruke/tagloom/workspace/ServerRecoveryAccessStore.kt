package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.crypto.SecretKeyMaterial
import com.oruke.tagloom.core.model.WorkspaceId
import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.sync.SyncRecoverySnapshot
import com.oruke.tagloom.platform.WorkspaceStorageMode
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest

/** 仅在本机设备保护后持久化的远端连接材料，禁止直接日志输出。 */
@Serializable
internal class ServerRecoveryAccess(
    /** 本机记录格式版本。 */
    val version: Int = 1,
    /** 原始工作区身份。 */
    val workspaceId: WorkspaceId,
    /** 经过 HTTPS 校验的服务器地址。 */
    val serverUrl: String,
    /** 本机随机设备身份，不复用其他恢复设备。 */
    val actorId: ActorId,
    /** 当前设备访问凭据，只在设备保护的配置中保存。 */
    val token: String,
    /** 为后续同设备签名保留的设备保护 seed，内存中也不保留原始 seed。 */
    val protectedSigningSeed: ByteArray,
    /** 已登记的签名公钥。 */
    val signingPublicKeyHex: String,
    /** Recovery Key 派生的不透明定位符。 */
    var recoveryId: String,
    /** 恢复认证凭据的摘要，不保存原 Recovery Key 或其访问 Token。 */
    var recoveryTokenSha256: String,
    /** 绑定当前主密钥版本，轮换后必须显式认证新的 Recovery Key。 */
    var keyId: String,
    /** 最近确认的副本；为空时仅完成设备登记。 */
    var snapshot: SyncRecoverySnapshot? = null,
    /** 首次网络请求前先落盘，确保响应丢失后能使用同一身份重试。 */
    var registered: Boolean = false,
    /** 旧配置保持本地模式，只有用户启用服务器模式后才自动上传。 */
    var mode: WorkspaceStorageMode = WorkspaceStorageMode.LOCAL,
    /** 已上传的本地 manifest 版本；上传期间的新写入留待下次处理。 */
    var uploadedManifestRevision: Long? = null,
) {
    /** 清除临时对象中的保护材料副本；字符串凭据仅限当前调用作用域。 */
    fun destroy() { protectedSigningSeed.fill(0) }
}

/** 将连接材料绑定到设备、工作区和本机目录；不参与工作区同步或备份。 */
internal class ServerRecoveryAccessStore(
    /** 与工作区目录分离的应用私有配置位置。 */
    private val directory: Path,
    /** Windows DPAPI / Android Keystore 设备保护能力。 */
    private val protector: DesktopWorkspaceCredentialProtector = platformWorkspaceCredentialProtector(),
) {
    /**
     * 在密钥受限作用域内立即设备加密，不导出或序列化原始 seed。
     * @param seed 新生成的设备签名私钥材料。
     * @param actorId 与该 seed 绑定的设备身份。
     * @return 只可由当前设备解开的保护数据。
     */
    fun protectSigningSeed(seed: SecretKeyMaterial, actorId: ActorId): ByteArray = seed.useBytes {
        protector.protect(it, "tagloom/device-signing/v1/${actorId.value}".encodeToByteArray())
    }

    /**
     * 读取当前本地副本的连接材料，错误时失败关闭，不覆盖损坏配置。
     * @param root 活动工作区目录。
     * @param workspaceId 已认证工作区身份。
     * @return 已解开的短生命周期材料，调用方必须销毁 seed。
     */
    fun read(root: Path, workspaceId: WorkspaceId): ServerRecoveryAccess? {
        val context = context(root, workspaceId)
        val file = directory.resolve("${context.serverRecoveryHex()}.enc")
        if (!Files.exists(file, NOFOLLOW_LINKS)) return null
        check(Files.isRegularFile(file, NOFOLLOW_LINKS) && Files.size(file) <= 64 * 1024)
        val bytes = protector.unprotect(Files.readAllBytes(file), context)
        return try {
            CanonicalJson.decodeFromString(ServerRecoveryAccess.serializer(), bytes.decodeToString()).also {
                check(it.version == 1 && it.workspaceId == workspaceId)
            }
        } finally { bytes.fill(0) }
    }

    /**
     * 原子发布受设备保护的材料，明文从不写入临时文件。
     * @param root 此设备本地副本的目录。
     * @param access 已认证或待重试的连接材料。
     */
    fun save(root: Path, access: ServerRecoveryAccess) {
        val context = context(root, access.workspaceId)
        val plaintext = CanonicalJson.encodeToString(ServerRecoveryAccess.serializer(), access).encodeToByteArray()
        try {
            writeWorkspaceRegistryAtomically(directory.resolve("${context.serverRecoveryHex()}.enc"), protector.protect(plaintext, context))
        } finally { plaintext.fill(0) }
    }

    /**
     * 以目录及身份摘要区分同机恢复副本，避免共享版本状态导致无意覆盖远端。
     * @param root 工作区规范化目录。
     * @param workspaceId 工作区身份。
     * @return 域隔离的认证上下文摘要。
     */
    private fun context(root: Path, workspaceId: WorkspaceId): ByteArray = MessageDigest.getInstance("SHA-256")
        .digest("tagloom/server-access/v1\n${root.toAbsolutePath().normalize()}\n${workspaceId.value}".encodeToByteArray())
}

/** @return 协议使用的小写十六进制编码；调用方负责敏感源字节生命周期。 */
internal fun ByteArray.serverRecoveryHex(): String = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
