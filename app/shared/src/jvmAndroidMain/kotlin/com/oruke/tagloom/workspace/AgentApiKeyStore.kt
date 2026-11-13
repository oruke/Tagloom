package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.ai.AiAuthMode
import com.oruke.tagloom.core.ai.AiProviderConnection
import com.oruke.tagloom.core.model.WorkspaceId
import com.oruke.tagloom.platform.WorkspaceAgentCredentialStatus
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/**
 * 管理工作区目录之外的本机隔离 API Key 存储（Windows DPAPI / Android Keystore），
 * 并在已解锁会话中按认证模式（本机密钥库、可选加密信封同步、桌面环境变量）解析请求密钥。
 *
 * 存储在 [directory] 下的密文文件绝不进入工作区快照、盲同步归档或加密备份包。
 *
 * @param directory 独立于工作区目录的本机私有凭据存储目录。
 * @param protector 平台级系统安全凭据保护器（DPAPI / Android Keystore）。
 * @param envReader 环境变量读取函数（测试可注入受控假实现，默认调用平台实现）。
 * @param envSupported 当前环境是否支持桌面环境变量模式。
 */
internal class AgentApiKeyStore(
    private val directory: Path,
    private val protector: DesktopWorkspaceCredentialProtector = platformWorkspaceCredentialProtector(),
    private val envReader: (String) -> String? = ::readDesktopEnvironmentVariable,
    val envSupported: Boolean = supportsDesktopEnvironmentVariables,
) {
    /**
     * 将连接的本机隔离 API Key 经系统安全保护器加密后原子写入私有目录。
     *
     * @param workspaceId 所属工作区 ID。
     * @param connectionId 连接稳定 ID。
     * @param apiKey 待保护的明文 API Key。
     */
    fun saveLocalApiKey(
        workspaceId: WorkspaceId,
        connectionId: String,
        apiKey: String,
    ) {
        val normalized = apiKey.trim()
        require(normalized.isNotEmpty()) { "本机保存的 API Key 不能为空" }
        Files.createDirectories(directory)
        val target = credentialFile(workspaceId, connectionId)
        val temp = directory.resolve("${target.fileName}.tmp")
        val plaintext = normalized.encodeToByteArray()
        try {
            val cipher = protector.protect(plaintext, aad(workspaceId, connectionId))
            Files.write(
                temp,
                cipher,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            plaintext.fill(0)
            Files.deleteIfExists(temp)
        }
    }

    /**
     * 读取并解密当前设备为该连接保存的本机隔离 API Key。
     *
     * @param workspaceId 所属工作区 ID。
     * @param connectionId 连接稳定 ID。
     * @return 解密后的密钥文本；不存在时返回空。
     */
    fun loadLocalApiKeyOrNull(
        workspaceId: WorkspaceId,
        connectionId: String,
    ): String? {
        val target = credentialFile(workspaceId, connectionId)
        if (!Files.isRegularFile(target)) return null
        val cipher = Files.readAllBytes(target)
        val plaintext = runCatching {
            protector.unprotect(cipher, aad(workspaceId, connectionId))
        }.getOrNull() ?: return null
        return try {
            plaintext.decodeToString().trim().takeIf { it.isNotEmpty() }
        } finally {
            plaintext.fill(0)
        }
    }

    /**
     * 删除指定连接在本机私有目录中保存的隔离 API Key。
     *
     * @param workspaceId 所属工作区 ID。
     * @param connectionId 连接稳定 ID。
     */
    fun deleteLocalApiKey(
        workspaceId: WorkspaceId,
        connectionId: String,
    ) {
        Files.deleteIfExists(credentialFile(workspaceId, connectionId))
    }

    /**
     * 检查连接在当前设备上的脱敏凭据就绪状态，不返回真实密钥字符串。
     *
     * @param workspaceId 所属工作区 ID。
     * @param connection 权威快照中的连接配置（可含 `syncedApiKey`）。
     * @return 供共享 UI 展示的脱敏凭据状态。
     */
    fun inspectStatus(
        workspaceId: WorkspaceId,
        connection: AiProviderConnection,
    ): WorkspaceAgentCredentialStatus = when (connection.authMode) {
        AiAuthMode.NONE -> WorkspaceAgentCredentialStatus.READY_NONE

        AiAuthMode.ENV_VAR -> {
            if (!envSupported) {
                WorkspaceAgentCredentialStatus.ENV_VAR_UNSUPPORTED
            } else {
                val envName = connection.envVarName
                if (!envName.isNullOrBlank() && !envReader(envName).isNullOrBlank()) {
                    WorkspaceAgentCredentialStatus.READY_ENV_VAR
                } else {
                    WorkspaceAgentCredentialStatus.MISSING_ENV_VAR
                }
            }
        }

        AiAuthMode.KEYSTORE -> {
            if (connection.syncKeyInEnvelope && !connection.syncedApiKey.isNullOrBlank()) {
                WorkspaceAgentCredentialStatus.READY_SYNCED_ENVELOPE
            } else if (loadLocalApiKeyOrNull(workspaceId, connection.connectionId) != null) {
                WorkspaceAgentCredentialStatus.READY_LOCAL_KEYSTORE
            } else {
                WorkspaceAgentCredentialStatus.MISSING_LOCAL_KEY
            }
        }
    }

    /**
     * 在发起真实模型请求前按需解析连接使用的内存态 API Key。
     *
     * @param workspaceId 当前已解锁工作区 ID。
     * @param connection 权威快照中的连接定义。
     * @return 仅供当次 HTTP 请求头使用的密钥；免密或缺失时为空。
     */
    fun resolveForRequest(
        workspaceId: WorkspaceId,
        connection: AiProviderConnection,
    ): String? = when (connection.authMode) {
        AiAuthMode.NONE -> null
        AiAuthMode.ENV_VAR -> if (envSupported) connection.envVarName?.let(envReader) else null
        AiAuthMode.KEYSTORE -> {
            if (connection.syncKeyInEnvelope && !connection.syncedApiKey.isNullOrBlank()) {
                connection.syncedApiKey
            } else {
                loadLocalApiKeyOrNull(workspaceId, connection.connectionId)
            }
        }
    }

    /**
     * 计算不含连接名称或明文业务语义的受控本机凭据文件路径。
     *
     * @param workspaceId 工作区 ID。
     * @param connectionId 连接稳定 ID。
     * @return 私有目录内的单个 `.cred` 文件路径。
     */
    private fun credentialFile(workspaceId: WorkspaceId, connectionId: String): Path {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${workspaceId.value}:$connectionId".encodeToByteArray())
            .joinToString("") { b -> (b.toInt() and 0xFF).toString(16).padStart(2, '0') }
        return directory.resolve("$digest.cred")
    }

    /**
     * 构造绑定工作区身份与连接 ID 的系统保护器附加认证数据（AAD）。
     *
     * @param workspaceId 工作区 ID。
     * @param connectionId 连接稳定 ID。
     * @return UTF-8 AAD 字节数组。
     */
    private fun aad(workspaceId: WorkspaceId, connectionId: String): ByteArray =
        "tagloom-agent-api-key-v1:${workspaceId.value}:$connectionId".encodeToByteArray()
}
