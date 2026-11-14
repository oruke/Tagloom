package com.oruke.tagloom.core.ai

import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.TagId
import kotlinx.serialization.Serializable

/**
 * 定义作曲框通过 `@` 挂载的上下文引用类别。
 */
@Serializable
enum class AgentContextRefKind {
    /** 单篇笔记（支持二次勾选或排除具体稳定 [BlockId]）。 */
    NOTE,

    /** 从选区或块手柄精确挂载的一组正文段落。 */
    BLOCKS,

    /** 笔记类型及其子类型闭包检索范围（自动排除 `REFERENCE_ONLY` 与 `LOCKED`）。 */
    TAG_SCOPE,

    /** 已保存查询结果范围（自动排除 `REFERENCE_ONLY` 与 `LOCKED`）。 */
    SAVED_QUERY_SCOPE,
}

/**
 * 描述一条挂载在会话作曲框或已发送用户消息上的 `@` 上下文引用。
 */
@Serializable
data class AgentContextRef(
    /** 引用 chip 稳定标识。 */
    val id: String,
    /** 引用类别。 */
    val kind: AgentContextRefKind,
    /** 目标笔记 ID（`NOTE` 与 `BLOCKS` 必填）。 */
    val noteId: ObjectId? = null,
    /** 显式限定包含的稳定段落 ID 列表；在 `NOTE` 模式下为空表示包含整篇笔记活动段落。 */
    val blockIds: List<BlockId> = emptyList(),
    /** 在 `NOTE` 二次勾选面板中显式排除的稳定段落 ID 列表。 */
    val excludedBlockIds: List<BlockId> = emptyList(),
    /** 目标笔记类型 ID（`TAG_SCOPE` 必填）。 */
    val tagId: TagId? = null,
    /** 目标保存查询 ID（`SAVED_QUERY_SCOPE` 必填）。 */
    val queryId: ObjectId? = null,
    /** 位于加密会话快照内的展示标题快照。 */
    val label: String,
)

/**
 * 描述 `SourceLedger` 登记并随加密会话持久化的单条真实来源证据。
 */
@Serializable
data class AgentSourceEntry(
    /** 从 `1` 开始的角标序号，对应回答正文中的 `[1]`、`[2]`。 */
    val sourceIndex: Int,
    /** 来源笔记稳定对象 ID。 */
    val noteId: ObjectId,
    /** 读取时的笔记标题。 */
    val noteTitle: String,
    /** 来源精确段落 ID；整篇摘要级引用时可为空。 */
    val blockId: BlockId? = null,
    /** 读取时的笔记协议 revision（`actorId:sequence`）。 */
    val revision: String,
    /** 脱敏截断后的原段落摘录预览（仅存于加密快照与已解锁内存）。 */
    val excerpt: String,
)

/**
 * 描述点击或悬浮来源角标时与当前工作区最新笔记快照实时核对后的状态。
 */
enum class AgentCitationStatus {
    /** 目标笔记与段落仍然存在且内容未发生实质变更。 */
    VERIFIED,

    /** 目标笔记或对应段落已在对话后被编辑更新。 */
    UPDATED,

    /** 目标笔记或对应段落已被删除。 */
    REMOVED,
}

/**
 * 描述助手回复前执行的一次只读知识库工具调用摘要。
 */
@Serializable
data class AgentToolTrace(
    /** 工具调用唯一标识。 */
    val callId: String,
    /** 稳定 snake_case 工具名称（如 `search_notes`、`get_note`）。 */
    val toolName: String,
    /** 面向可折叠轨迹条的单行摘要说明。 */
    val summary: String,
    /** 本次调用精读或命中的笔记篇数。 */
    val readNoteCount: Int = 0,
    /** 本次调用读取的正文段落数。 */
    val readBlockCount: Int = 0,
    /** 因 `AiAccessPolicy`（`REFERENCE_ONLY` / `LOCKED`）自动跳过的受保护笔记篇数。 */
    val skippedProtectedCount: Int = 0,
    /** 调用是否成功完成。 */
    val succeeded: Boolean = true,
)

/**
 * 描述 `AgentThread` 中的单条用户提问或助手回复消息。
 */
@Serializable
data class AgentThreadMessage(
    /** 消息稳定不透明 ID，用于多端三方合并保序并集。 */
    val id: String,
    /** 消息角色（`USER` 或 `ASSISTANT`）。 */
    val role: AiChatRole,
    /** 消息 Markdown 正文。 */
    val content: String,
    /** 模型思考过程摘要（不支持或关闭思考时为空串）。 */
    val thinkingSummary: String = "",
    /** 消息创建或完成时间（UTC ISO-8601）。 */
    val createdAt: String,
    /** 用户提问时挂载的 `@` 上下文引用列表。 */
    val contextRefs: List<AgentContextRef> = emptyList(),
    /** 助手回复关联的只读工具调用轨迹列表。 */
    val toolTraces: List<AgentToolTrace> = emptyList(),
    /** 助手回复登记的防幻觉来源台账条目列表。 */
    val sources: List<AgentSourceEntry> = emptyList(),
    /** 该回合（含中间工具步）消耗的真实或估算 Token 用量明细。 */
    val usage: AiTokenUsage? = null,
    /** 该回复是否由用户主动点击「停止」提前终止并保留已生成内容。 */
    val stoppedByUser: Boolean = false,
    /** 触发单轮步数或上下文预算护栏时的温和说明。 */
    val guardrailNotice: String? = null,
)

/**
 * 描述非破坏性存储在会话中的自动或手动上下文压缩摘要节点。
 */
@Serializable
data class AgentCompactionItem(
    /** 压缩节点稳定不透明 ID。 */
    val id: String,
    /** 压缩完成时间（UTC ISO-8601）。 */
    val createdAt: String,
    /** 被折叠进本摘要的最后一条早期消息 ID（压缩游标边界）。 */
    val upToMessageId: String,
    /** 触发压缩时的上下文窗口阈值百分比（手动触发时为 `0`）。 */
    val triggerPercent: Int,
    /** 本次压缩释放的近似活跃窗口 Token 数。 */
    val freedTokens: Int,
    /** 结构化保留脉络摘要正文。 */
    val summary: String,
    /** 生成摘要请求自身消耗的计费 Token 明细，照常计入累计账单。 */
    val compactionUsage: AiTokenUsage? = null,
)

/**
 * 描述当前活跃上下文窗口的三色分段 Token 占用明细。
 */
@Serializable
data class AgentContextBreakdown(
    /** 系统提示词占用的 Token 数。 */
    val systemTokens: Int = 0,
    /** 工具 Schema 与注入笔记上下文占用的 Token 数。 */
    val toolsTokens: Int = 0,
    /** 近期对话与思考消息占用的 Token 数。 */
    val messageTokens: Int = 0,
) {
    /** 当前活跃上下文总占用 Token 数。 */
    val usedTokens: Int
        get() = systemTokens + toolsTokens + messageTokens
}

/**
 * 表示持久化 Agent 会话的权威加密快照载荷（对应 `ObjectType.AGENT_THREAD`）。
 *
 * 外层受 HKDF `WrappingKeyPurpose.SNAPSHOT` + 随机 DEK 的双层 XChaCha20-Poly1305 信封保护；
 * 无痕会话仅驻留已解锁内存，不写入此快照。
 */
@Serializable
data class AgentThread(
    /** 会话载荷 schema 版本号。 */
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    /** 会话稳定不透明对象 ID（与 `SnapshotHeader.objectId` 一致）。 */
    val id: ObjectId,
    /** 面向用户展示的会话标题（仅存在于密文中）。 */
    val title: String,
    /** 会话首次创建时间（UTC ISO-8601）。 */
    val createdAt: String,
    /** 最近一次新建或实际发送/完成消息的活动时间（纯浏览点击严禁修改，保障侧栏零跳动）。 */
    val lastMessageAt: String,
    /** 会话是否已归档（归档后移出侧栏「最近会话 Top 5」与默认下拉列表）。 */
    val archived: Boolean = false,
    /** 当前会话选中的模型复合 ID（对应 `AiModelConfig.id`）。 */
    val selectedModelId: String? = null,
    /** 当前会话选中的深度思考档位。 */
    val thinkingEffort: AiThinkingEffort = AiThinkingEffort.OFF,
    /** 当前会话激活的检索暴露范围。 */
    val retrievalScope: AiRetrievalScope = AiRetrievalScope.CITED_ONLY,
    /** 当前作曲框挂载但尚未发送的活跃 `@` 引用列表。 */
    val activeContextRefs: List<AgentContextRef> = emptyList(),
    /** 按时间与因果顺序排列的完整历史消息列表（自动压缩后仍无损保留）。 */
    val messages: List<AgentThreadMessage> = emptyList(),
    /** 历次自动或手动上下文压缩生成的摘要节点列表。 */
    val compactions: List<AgentCompactionItem> = emptyList(),
    /** 当前生效的最新压缩节点 ID；为空表示尚未执行过压缩。 */
    val activeCompactionId: String? = null,
    /** 会话全生命周期累计计费 Token 账单（压缩上下文绝不清零此账单）。 */
    val cumulativeUsage: AiTokenUsage = AiTokenUsage(),
    /** 最近一轮完成后的活跃上下文三色分段基准。 */
    val latestContextBreakdown: AgentContextBreakdown = AgentContextBreakdown(),
) {
    /**
     * 根据会话中所有消息与压缩节点的 `usage` 记录确定性汇总累计计费账单。
     *
     * 多端三方合并后调用此方法，可确保两端独立对话消耗的 Token 被精确累加而非单侧覆盖。
     *
     * @return 汇总后的 [AiTokenUsage]。
     */
    fun recomputeCumulativeUsage(): AiTokenUsage {
        var total = AiTokenUsage()
        messages.forEach { message ->
            message.usage?.let { usage -> total += usage }
        }
        compactions.forEach { item ->
            item.compactionUsage?.let { usage -> total += usage }
        }
        return total
    }

    companion object {
        /** 当前 `AgentThread` 协议 schema 版本。 */
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}
