package com.oruke.tagloom.core.ai

/**
 * 定义新建 API 连接时的一键填表预设模板类别。
 */
enum class AiConnectionPresetKind {
    /** OpenAI 官方端点（默认绑定 `OpenAI Responses` 协议）。 */
    OPENAI,

    /** OpenAI 兼容端点（如 DeepSeek、OpenRouter、自建 vLLM，绑定 `OpenAI Chat Completions` 协议）。 */
    OPENAI_COMPATIBLE,

    /** Ollama 本机免密端点（`http://127.0.0.1:11434/v1`，免密 `OpenAI Chat Completions`）。 */
    OLLAMA_LOCAL,

    /** LM Studio 本机端点（`http://127.0.0.1:1234/v1`，免密 `OpenAI Chat Completions`）。 */
    LM_STUDIO_LOCAL,
}

/**
 * 描述一个连接预设模板的默认表单填充参数，不写入真实密钥。
 */
data class AiConnectionPresetTemplate(
    /** 预设枚举标识。 */
    val kind: AiConnectionPresetKind,
    /** 预设默认连接显示名称。 */
    val defaultName: String,
    /** 预设默认 Base URL。 */
    val defaultBaseUrl: String,
    /** 预设绑定的底层流式协议。 */
    val protocol: AiProtocolKind,
    /** 预设默认认证方式。 */
    val authMode: AiAuthMode,
    /** 推荐的桌面环境变量名提示。 */
    val suggestedEnvVarName: String? = null,
)

/**
 * 提供 API 连接快捷预设及常用已知模型的默认上下文窗口与思考能力推荐规则。
 */
object AiConnectionPresets {
    /** 供新建连接表单展示的四类标准预设列表。 */
    val templates: List<AiConnectionPresetTemplate> = listOf(
        AiConnectionPresetTemplate(
            kind = AiConnectionPresetKind.OPENAI,
            defaultName = "OpenAI",
            defaultBaseUrl = "https://api.openai.com/v1",
            protocol = AiProtocolKind.OPENAI_RESPONSES,
            authMode = AiAuthMode.KEYSTORE,
            suggestedEnvVarName = "OPENAI_API_KEY",
        ),
        AiConnectionPresetTemplate(
            kind = AiConnectionPresetKind.OPENAI_COMPATIBLE,
            defaultName = "OpenAI Compatible",
            defaultBaseUrl = "https://api.deepseek.com/v1",
            protocol = AiProtocolKind.OPENAI_CHAT_COMPLETIONS,
            authMode = AiAuthMode.KEYSTORE,
            suggestedEnvVarName = "DEEPSEEK_API_KEY",
        ),
        AiConnectionPresetTemplate(
            kind = AiConnectionPresetKind.OLLAMA_LOCAL,
            defaultName = "Ollama (Local)",
            defaultBaseUrl = "http://127.0.0.1:11434/v1",
            protocol = AiProtocolKind.OPENAI_CHAT_COMPLETIONS,
            authMode = AiAuthMode.NONE,
        ),
        AiConnectionPresetTemplate(
            kind = AiConnectionPresetKind.LM_STUDIO_LOCAL,
            defaultName = "LM Studio (Local)",
            defaultBaseUrl = "http://127.0.0.1:1234/v1",
            protocol = AiProtocolKind.OPENAI_CHAT_COMPLETIONS,
            authMode = AiAuthMode.NONE,
        ),
    )

    /**
     * 按预设类型获取对应的默认模板配置。
     *
     * @param kind 目标预设类别。
     * @return 对应的连接预设模板。
     */
    fun templateFor(kind: AiConnectionPresetKind): AiConnectionPresetTemplate =
        templates.first { template -> template.kind == kind }

    /**
     * 根据远端拉取或手动输入的 `modelId` 推断合理的初始上下文窗口大小（用户可随时手动调整）。
     *
     * @param modelId 原始模型标识。
     * @return 落在合法区间内的推荐 `contextWindowTokens`。
     */
    fun inferRecommendedContextWindowTokens(modelId: String): Int {
        val normalized = modelId.trim().lowercase()
        return when {
            normalized.contains("gpt-4.1") || normalized.contains("gemini-2.5") || normalized.contains("gemini-2.0") -> 1_000_000
            normalized.contains("gpt-5") -> 256_000
            normalized.startsWith("o1") || normalized.startsWith("o3") || normalized.startsWith("o4") ||
                normalized.contains("/o3") || normalized.contains("/o4") || normalized.contains("claude") -> 200_000
            normalized.contains("deepseek") || normalized.contains("gpt-4o") || normalized.contains("qwen") ||
                normalized.contains("llama-3") || normalized.contains("llama3") || normalized.contains("gemma-3") -> 128_000
            normalized.contains("mistral-small") || normalized.contains("yi-") -> 32_000
            else -> AiModelConfig.DEFAULT_CONTEXT_WINDOW_TOKENS
        }
    }

    /**
     * 根据常见推理模型命名推断初始可选深度思考档位列表。
     *
     * @param modelId 原始模型标识。
     * @return 若识别为推理模型则返回低/中/高三档，否则返回空列表。
     */
    fun inferSupportedThinkingEfforts(modelId: String): List<AiThinkingEffort> {
        val normalized = modelId.trim().lowercase()
        val isReasoning = normalized.startsWith("o1") ||
            normalized.startsWith("o3") ||
            normalized.startsWith("o4") ||
            normalized.contains("/o3") ||
            normalized.contains("/o4") ||
            normalized.contains("gpt-5") ||
            normalized.contains("reasoner") ||
            normalized.contains("deepseek-r1") ||
            normalized.contains("deepseek-v3.2") ||
            normalized.contains("qwq") ||
            normalized.contains("thinking")
        return if (isReasoning) {
            listOf(AiThinkingEffort.LOW, AiThinkingEffort.MEDIUM, AiThinkingEffort.HIGH)
        } else {
            emptyList()
        }
    }

    /**
     * 将整数 Token 容量格式化为紧凑可读文案（如 `128K`、`102.4K`、`1M`）。
     *
     * @param tokens Token 整数值。
     * @return 紧凑人类可读字符串。
     */
    fun formatTokenCount(tokens: Int): String {
        if (tokens >= 1_000_000 && tokens % 1_000_000 == 0) {
            return "${tokens / 1_000_000}M"
        }
        if (tokens >= 1_000) {
            val tenths = (tokens * 10L) / 1_000L
            return if (tenths % 10L == 0L) {
                "${tenths / 10L}K"
            } else {
                "${tenths / 10L}.${tenths % 10L}K"
            }
        }
        return tokens.toString()
    }
}
