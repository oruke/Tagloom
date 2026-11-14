package com.oruke.tagloom.core.ai

import com.oruke.tagloom.core.agent.AgentToolName
import com.oruke.tagloom.core.external.ExternalToolDefinitions
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.ObjectId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * 描述工具执行或显式 `@` 引用注入时捕获的原始来源候选，待写入当轮 [SourceLedger]。
 */
data class AgentRawSourceCandidate(
    /** 来源笔记稳定 ID。 */
    val noteId: ObjectId,
    /** 来源笔记标题。 */
    val noteTitle: String,
    /** 可选精确段落 ID。 */
    val blockId: BlockId? = null,
    /** 读取时的笔记协议 revision。 */
    val revision: String,
    /** 原文摘录文本。 */
    val excerpt: String,
)

/**
 * 描述显式 `@` 引用经过工作区 `AiAccessPolicy` 与 `AiContextBudget` 解析后的注入块。
 */
data class AgentResolvedContextPayload(
    /** 注入给当前提问轮次的结构化上下文说明（为空表示无有效可注入内容）。 */
    val formattedContextBlock: String,
    /** 真实读取到的笔记与段落来源候选列表。 */
    val sources: List<AgentRawSourceCandidate>,
    /** 因策略变为 `LOCKED` 而被拦截的已挂引用 ID 集合。 */
    val blockedRefIds: Set<String>,
    /** 是否因单轮 `AiContextBudget` 上限发生了裁剪。 */
    val truncatedByBudget: Boolean,
)

/**
 * 描述一次进程内只读知识工具执行的返回载荷、来源证据与轨迹摘要。
 */
data class AgentReadToolExecutionOutcome(
    /** 回传给模型的结构化 JSON 文本（已按字符预算保护裁剪）。 */
    val resultJson: String,
    /** 本次工具调用真实读取到的来源候选列表。 */
    val sources: List<AgentRawSourceCandidate>,
    /** 面向界面可折叠轨迹条的摘要记录。 */
    val trace: AgentToolTrace,
)

/**
 * 描述一次进程内写提案工具预演的返回载荷、待确认修改提案与轨迹摘要（绝不直接写盘）。
 */
data class AgentProposalToolOutcome(
    /** 回传给模型的结构化 JSON 文本（如 `status: pending_confirmation` 或门禁拒绝原因）。 */
    val resultJson: String,
    /** 预演校验通过时生成的待确认细粒度修改提案；校验拒绝时为空。 */
    val proposal: AgentMutationProposal? = null,
    /** 面向界面可折叠轨迹条的摘要记录。 */
    val trace: AgentToolTrace,
)

/**
 * 抽象内置 [AgentHarness] 对当前已解锁工作区会话的只读检索与写提案预演端口。
 *
 * 实现必须在同一客户端进程内直接复用应用服务，并在分页或预演前强制执行 `AiAccessPolicy` 过滤。
 */
interface AgentHarnessWorkspacePort {
    /**
     * 解析本轮提问挂载的 `@` 引用并按预算裁剪。
     *
     * @param refs 用户在作曲框挂载的引用列表。
     * @param budget 单轮最大笔记数、最大段落数与最大字符数预算。
     * @return 结构化注入文本、来源列表与被 `LOCKED` 拦截的引用 ID。
     */
    suspend fun resolveExplicitContext(
        refs: List<AgentContextRef>,
        budget: AiContextBudget,
    ): AgentResolvedContextPayload

    /**
     * 在受控检索范围与 `AiAccessPolicy` 门禁下执行单个只读知识工具。
     *
     * @param callId 模型工具调用 ID。
     * @param tool 待执行的只读工具枚举。
     * @param argumentsJson 模型传入的原始参数 JSON 文本。
     * @param retrievalScope 当前会话激活的检索暴露范围。
     * @param activeRefs 当前轮次有效的 `@` 上下文引用（限定显式单篇白名单或类型/查询范围）。
     * @param maxResultCharacters 单次工具返回结果的最大字符数护栏。
     * @return 工具 JSON 结果、来源证据与轨迹记录。
     */
    suspend fun executeReadTool(
        callId: String,
        tool: AgentToolName,
        argumentsJson: String,
        retrievalScope: AiRetrievalScope,
        activeRefs: List<AgentContextRef>,
        maxResultCharacters: Int,
    ): AgentReadToolExecutionOutcome

    /**
     * 在受控检索范围、`AiAccessPolicy` 与 Schema 校验门禁下预演写工具并生成待确认 Diff 提案。
     *
     * @param callId 模型工具调用 ID。
     * @param tool 待预演的写提案工具枚举（`create_note` / `patch_note_blocks` / `update_note_metadata`）。
     * @param assistantMessageId 当前正在生成的助手消息 ID。
     * @param argumentsJson 模型传入的原始参数 JSON 文本。
     * @param retrievalScope 当前会话激活的检索暴露范围。
     * @param activeRefs 当前轮次有效的 `@` 上下文引用。
     * @return 工具 JSON 响应、待审阅提案与轨迹记录。
     */
    suspend fun prepareWriteProposal(
        callId: String,
        tool: AgentToolName,
        argumentsJson: String,
        assistantMessageId: String,
        retrievalScope: AiRetrievalScope,
        activeRefs: List<AgentContextRef>,
    ): AgentProposalToolOutcome = AgentProposalToolOutcome(
        resultJson = """{"error":"proposal_not_supported"}""",
        proposal = null,
        trace = AgentToolTrace(
            callId = callId,
            toolName = tool.name.lowercase(),
            summary = "当前端口未启用写提案预演",
            succeeded = false,
        ),
    )
}

/**
 * 维护内置 Agent Harness 共享自 [ExternalToolDefinitions] 的 14 个只读知识工具与 3 个细粒度修改提案工具目录。
 *
 * 严格遵循 `docs/agent-design.md §3.1`：内置模型绝不暴露 `delete_note` 与文件导出 `export_note`，
 * 所有写工具仅生成 `PENDING` 待确认 Diff 提案，必须由用户点击确认后方能落盘。
 */
object AgentReadToolCatalog {
    /** 允许向内置模型暴露的 14 个只读知识工具枚举集合。 */
    val allowedReadTools: Set<AgentToolName> = setOf(
        AgentToolName.SEARCH_NOTES,
        AgentToolName.QUERY_NOTES,
        AgentToolName.LIST_NOTES,
        AgentToolName.GET_NOTE,
        AgentToolName.GET_BLOCK,
        AgentToolName.GET_BACKLINKS,
        AgentToolName.GET_TAGS,
        AgentToolName.GET_TAG_SCHEMA,
        AgentToolName.GET_WORKSPACE_SCHEMA,
        AgentToolName.GET_SAVED_QUERIES,
        AgentToolName.GET_SAVED_QUERY,
        AgentToolName.RUN_SAVED_QUERY,
        AgentToolName.SUGGEST_TAGS,
        AgentToolName.SUGGEST_PROPERTIES,
    )

    /** 允许向内置模型暴露的 3 个受控写提案工具枚举集合（绝不包含 `DELETE_NOTE` 与 `EXPORT_NOTE`）。 */
    val allowedProposalTools: Set<AgentToolName> = setOf(
        AgentToolName.CREATE_NOTE,
        AgentToolName.PATCH_NOTE_BLOCKS,
        AgentToolName.UPDATE_NOTE_METADATA,
    )

    /** 仅包含 14 个只读知识工具的标准 JSON Schema 定义列表。 */
    val definitions: List<AiToolDefinition> = toToolDefinitions(allowedReadTools)

    /** 包含 14 个只读知识工具与 3 个待确认写提案工具的完整标准 JSON Schema 定义列表。 */
    val allDefinitions: List<AiToolDefinition> = toToolDefinitions(allowedReadTools + allowedProposalTools)

    /**
     * 按工具函数名查找允许内置 Harness 执行的只读工具枚举。
     *
     * @param name 模型返回的工具函数名。
     * @return 匹配到的只读工具枚举；未知或非只读工具返回 `null`。
     */
    fun resolveReadTool(name: String): AgentToolName? = ExternalToolDefinitions.all
        .firstOrNull { definition -> definition.name == name && definition.tool in allowedReadTools }
        ?.tool

    /**
     * 按工具函数名查找允许内置 Harness 预演的写提案工具枚举。
     *
     * @param name 模型返回的工具函数名。
     * @return 匹配到的写提案工具枚举；未知或禁止暴露的工具（如 `delete_note`）返回 `null`。
     */
    fun resolveProposalTool(name: String): AgentToolName? = ExternalToolDefinitions.all
        .firstOrNull { definition -> definition.name == name && definition.tool in allowedProposalTools }
        ?.tool

    /**
     * 将指定工具集合转换为流式 Provider 适配器可直接消费的标准 JSON Schema 工具定义。
     *
     * @param allowed 允许包含的工具枚举集合。
     * @return 转换后的 [AiToolDefinition] 列表。
     */
    private fun toToolDefinitions(allowed: Set<AgentToolName>): List<AiToolDefinition> = ExternalToolDefinitions.all
        .filter { definition -> definition.tool in allowed }
        .map { definition ->
            AiToolDefinition(
                name = definition.name,
                description = definition.description,
                parametersSchema = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("properties", definition.properties)
                    if (definition.required.isNotEmpty()) {
                        put("required", JsonArray(definition.required.map(::JsonPrimitive)))
                    }
                    put("additionalProperties", JsonPrimitive(false))
                },
            )
        }

    /**
     * 将连续多次只读工具调用折叠为面向界面的单行摘要标题。
     *
     * @param traces 当前回合累积的工具调用轨迹列表。
     * @return 紧凑中文摘要（含精读篇数、段落数及自动跳过的受保护笔记数）。
     */
    fun summarizeTrajectory(traces: List<AgentToolTrace>): String {
        if (traces.isEmpty()) return ""
        val totalReadNotes = traces.sumOf(AgentToolTrace::readNoteCount)
        val totalReadBlocks = traces.sumOf(AgentToolTrace::readBlockCount)
        val totalSkipped = traces.sumOf(AgentToolTrace::skippedProtectedCount)
        val baseText = when {
            traces.size == 1 && totalSkipped == 0 -> traces.single().summary
            totalReadNotes > 0 && totalReadBlocks > 0 ->
                "已执行 ${traces.size} 步检索并精读 $totalReadNotes 篇笔记 ($totalReadBlocks 个段落)"
            totalReadNotes > 0 ->
                "已执行 ${traces.size} 步检索并读取 $totalReadNotes 篇笔记"
            else ->
                "已执行 ${traces.size} 步知识库查询"
        }
        return if (totalSkipped > 0) {
            "$baseText · 自动跳过 $totalSkipped 篇受策略保护笔记"
        } else {
            baseText
        }
    }
}
