package com.oruke.tagloom.core.ai

import com.oruke.tagloom.core.workspace.WorkspaceDefaults

/**
 * 校验 `WorkspaceConfig` 快照载荷的协议合法性、安全边界与内部引用一致性。
 *
 * 任何违背分层密钥隔离规则（如未开启信封同步却携带 `syncedApiKey`、在 `customHeaders` 夹带认证头、
 * URL 含内嵌凭据）或越界上下文窗口的参数均在此确定性拦截。
 */
object WorkspaceConfigValidator {
    /** 连接稳定 ID 格式约束。 */
    private val CONNECTION_ID_PATTERN: Regex = Regex("[A-Za-z0-9_-]{1,96}")

    /** 环境变量名称格式约束（仅允许合法标识符，严禁 shell 命令或空白字符）。 */
    private val ENV_VAR_NAME_PATTERN: Regex = Regex("[A-Za-z_][A-Za-z0-9_]{0,127}")

    /** 禁止出现在非敏感 `customHeaders` 中的认证或代理凭据头名称（小写比较）。 */
    private val FORBIDDEN_HEADER_NAMES: Set<String> = setOf(
        "authorization",
        "proxy-authorization",
        "x-api-key",
        "api-key",
        "cookie",
        "set-cookie",
    )

    /**
     * 全面验证一份工作区 Agent 配置快照，返回所有协议违规编码列表。
     *
     * @param config 待验证的配置载荷。
     * @return 为空列表表示配置完全合法；非空时包含稳定的非敏感违规原因编码。
     */
    fun validate(config: WorkspaceConfig): List<String> {
        val violations = mutableListOf<String>()
        if (config.schemaVersion != WorkspaceConfig.CURRENT_SCHEMA_VERSION) {
            violations += "workspace-config-unsupported-schema-version"
        }
        if (config.id != WorkspaceDefaults.DEFAULT_WORKSPACE_CONFIG_ID) {
            violations += "workspace-config-invalid-id"
        }

        val seenConnectionIds = mutableSetOf<String>()
        config.connections.forEach { connection ->
            validateConnection(connection, seenConnectionIds, violations)
        }

        val seenModelIds = mutableSetOf<String>()
        config.models.forEach { model ->
            validateModel(model, seenConnectionIds, seenModelIds, violations)
        }

        validatePreferences(config.preferences, violations)
        return violations
    }

    /**
     * 验证单个 Provider 连接对象的地址、认证模式与密钥隔离约束。
     *
     * @param connection 待校验连接。
     * @param seenConnectionIds 已登记连接 ID 集合，用于检测重复主键。
     * @param violations 累积违规列表。
     */
    private fun validateConnection(
        connection: AiProviderConnection,
        seenConnectionIds: MutableSet<String>,
        violations: MutableList<String>,
    ) {
        if (!connection.connectionId.matches(CONNECTION_ID_PATTERN)) {
            violations += "connection-id-invalid"
        } else if (!seenConnectionIds.add(connection.connectionId)) {
            violations += "connection-id-duplicate"
        }
        if (connection.name.isBlank() || connection.name.length > 120) {
            violations += "connection-name-invalid"
        }
        if (!isValidEndpointBaseUrl(connection.baseUrl)) {
            violations += "connection-base-url-invalid"
        }
        if (connection.timeoutSeconds !in AiProviderConnection.MIN_TIMEOUT_SECONDS..AiProviderConnection.MAX_TIMEOUT_SECONDS) {
            violations += "connection-timeout-out-of-range"
        }

        connection.customHeaders.forEach { (rawName, rawValue) ->
            val headerName = rawName.trim()
            if (headerName.isEmpty() || headerName.any { ch -> ch <= ' ' || ch == ':' }) {
                violations += "connection-header-name-invalid"
            } else if (headerName.lowercase() in FORBIDDEN_HEADER_NAMES) {
                violations += "connection-header-forbidden-auth"
            }
            if (rawValue.any { ch -> ch == '\r' || ch == '\n' }) {
                violations += "connection-header-value-invalid"
            }
        }

        when (connection.authMode) {
            AiAuthMode.KEYSTORE -> {
                if (connection.envVarName != null) {
                    violations += "connection-env-var-unexpected"
                }
                if (!connection.syncKeyInEnvelope && connection.syncedApiKey != null) {
                    violations += "connection-synced-key-forbidden-when-disabled"
                }
                if (connection.syncedApiKey != null && connection.syncedApiKey.isBlank()) {
                    violations += "connection-synced-key-blank"
                }
            }

            AiAuthMode.ENV_VAR -> {
                val envVar = connection.envVarName
                if (envVar == null || !envVar.matches(ENV_VAR_NAME_PATTERN)) {
                    violations += "connection-env-var-invalid"
                }
                if (connection.syncKeyInEnvelope || connection.syncedApiKey != null) {
                    violations += "connection-synced-key-forbidden-for-env-var"
                }
            }

            AiAuthMode.NONE -> {
                if (connection.envVarName != null) {
                    violations += "connection-env-var-unexpected"
                }
                if (connection.syncKeyInEnvelope || connection.syncedApiKey != null) {
                    violations += "connection-synced-key-forbidden-for-none-auth"
                }
            }
        }
    }

    /**
     * 验证单个模型条目的复合主键、所属连接、上下文窗口区间与思考档位。
     *
     * @param model 待校验模型配置。
     * @param validConnectionIds 当前配置中存在的合法连接 ID 集合。
     * @param seenModelIds 已登记模型复合主键集合。
     * @param violations 累积违规列表。
     */
    private fun validateModel(
        model: AiModelConfig,
        validConnectionIds: Set<String>,
        seenModelIds: MutableSet<String>,
        violations: MutableList<String>,
    ) {
        if (model.connectionId !in validConnectionIds) {
            violations += "model-connection-missing"
        }
        if (model.modelId.isBlank() || model.modelId.length > 160 || model.modelId.any { it.isISOControl() }) {
            violations += "model-id-invalid"
        }
        val expectedId = AiModelConfig.composeModelConfigId(model.connectionId, model.modelId)
        if (model.id != expectedId) {
            violations += "model-composite-id-mismatch"
        } else if (!seenModelIds.add(model.id)) {
            violations += "model-duplicate"
        }
        if (model.displayName.isBlank() || model.displayName.length > 160) {
            violations += "model-display-name-invalid"
        }
        if (model.contextWindowTokens !in AiModelConfig.MIN_CONTEXT_WINDOW_TOKENS..AiModelConfig.MAX_CONTEXT_WINDOW_TOKENS) {
            violations += "model-context-window-out-of-range"
        }
        if (AiThinkingEffort.OFF in model.supportedThinkingEfforts ||
            model.supportedThinkingEfforts.distinct().size != model.supportedThinkingEfforts.size
        ) {
            violations += "model-thinking-efforts-invalid"
        }
    }

    /**
     * 验证运行偏好与 `80% / 16%` 自动上下文压缩区间不变量。
     *
     * @param preferences 待校验偏好。
     * @param violations 累积违规列表。
     */
    private fun validatePreferences(
        preferences: AiAgentPreferences,
        violations: MutableList<String>,
    ) {
        if (preferences.defaultModelId != null && preferences.defaultModelId.isBlank()) {
            violations += "preferences-default-model-blank"
        }
        if (preferences.maxToolRounds !in 1..20) {
            violations += "preferences-max-tool-rounds-out-of-range"
        }
        if (preferences.maxNotes !in 1..100) {
            violations += "preferences-max-notes-out-of-range"
        }
        if (preferences.maxBlocks !in 1..500) {
            violations += "preferences-max-blocks-out-of-range"
        }
        if (preferences.maxCharacters !in 1_000..200_000) {
            violations += "preferences-max-characters-out-of-range"
        }
        if (preferences.autoCompactPercent !in 50..95) {
            violations += "preferences-auto-compact-percent-out-of-range"
        }
        if (preferences.retainPercent !in 8..40) {
            violations += "preferences-retain-percent-out-of-range"
        }
        if (preferences.retainPercent >= preferences.autoCompactPercent) {
            violations += "preferences-retain-exceeds-compact-threshold"
        }
    }

    /**
     * 检查 Base URL 为合法的 HTTP/HTTPS 绝对地址，且不含内嵌账号密码、查询参数或锚点片段。
     *
     * @param rawUrl 待检查地址字符串。
     * @return 地址格式安全合规时返回真。
     */
    fun isValidEndpointBaseUrl(rawUrl: String): Boolean {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty() || trimmed.length > 512 || trimmed.any { it.isWhitespace() || it.isISOControl() }) {
            return false
        }
        val schemeSeparator = trimmed.indexOf("://")
        if (schemeSeparator <= 0) return false
        val scheme = trimmed.substring(0, schemeSeparator).lowercase()
        if (scheme != "https" && scheme != "http") return false
        val remainder = trimmed.substring(schemeSeparator + 3)
        if (remainder.isEmpty() || '?' in remainder || '#' in remainder) return false
        val authority = remainder.substringBefore('/')
        if (authority.isEmpty() || '@' in authority) return false
        val host = if (authority.startsWith("[")) {
            val endBracket = authority.indexOf(']')
            if (endBracket <= 1) return false
            val portSuffix = authority.substring(endBracket + 1)
            if (portSuffix.isNotEmpty() && !portSuffix.matches(PORT_SUFFIX_PATTERN)) return false
            authority.substring(1, endBracket)
        } else {
            val colonIndex = authority.indexOf(':')
            if (colonIndex >= 0) {
                val portText = authority.substring(colonIndex + 1)
                val port = portText.toIntOrNull() ?: return false
                if (port !in 1..65535) return false
                authority.substring(0, colonIndex)
            } else {
                authority
            }
        }
        return host.isNotBlank()
    }

    /** IPv6 端口后缀格式。 */
    private val PORT_SUFFIX_PATTERN: Regex = Regex(":[0-9]{1,5}")
}
