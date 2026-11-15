package com.oruke.tagloom.platform

import com.oruke.tagloom.core.ai.AgentCitationStatus
import com.oruke.tagloom.core.ai.AgentContextRef
import com.oruke.tagloom.core.ai.AgentHarnessProgress
import com.oruke.tagloom.core.ai.AgentHarnessTurnOutput
import com.oruke.tagloom.core.ai.AgentSourceEntry
import com.oruke.tagloom.core.ai.AgentThread
import com.oruke.tagloom.core.ai.AiRetrievalScope
import com.oruke.tagloom.core.ai.AiThinkingEffort
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.ObjectRevision

/**
 * 表示共享 UI 可消费的一条持久化或无痕内存 Agent 会话条目。
 */
data class WorkspaceAgentThreadEntry(
    /** 会话完整载荷。 */
    val thread: AgentThread,
    /** 是否为仅驻留已解锁内存、不落盘且不同步的无痕会话。 */
    val incognito: Boolean = false,
    /** 持久化会话的当前权威 revision；无痕会话为空。 */
    val revision: ObjectRevision? = null,
) {
    /** 会话稳定对象 ID 快捷读取属性。 */
    val id: ObjectId
        get() = thread.id
}

/**
 * 描述单轮内置 Agent 问答或被用户主动停止后的完成结果。
 */
data class WorkspaceAgentTurnResult(
    /** 已完成持久化确认（或无痕内存更新）的最新会话条目。 */
    val entry: WorkspaceAgentThreadEntry,
    /** 单轮模型生成与防幻觉引用核验结果。 */
    val output: AgentHarnessTurnOutput,
)

/**
 * 描述用户确认采纳修改提案并经单次原子事务提交后的目标笔记与会话更新结果。
 */
data class WorkspaceAgentProposalCommitOutcome(
    /** 已标记该提案为 `APPLIED` 并完成确认的最新会话条目。 */
    val entry: WorkspaceAgentThreadEntry,
    /** 同一事务中新建或更新确认的权威笔记快照视图。 */
    val committedNote: WorkspaceNote,
)

/**
 * 抽象共享双形态 Agent UI（全屏工作台与右侧伴随栏）所需的会话管理与流式推理网关。
 *
 * 实现必须保证：
 * 1. 纯浏览点击、切换模型、挂载引用或重命名绝不修改 `lastMessageAt`，杜绝侧栏「最近会话」跳动；
 * 2. 流式生成与中间工具步期间零磁盘写入，仅在持久化会话创建、单轮完成/用户停止或元数据变更时单次提交事务；
 * 3. 无痕会话仅驻留已解锁内存，工作区锁定时立即取消在途请求并清空无痕状态。
 */
interface WorkspaceAgentHarnessGateway {
    /**
     * 读取当前已解锁会话内的全部未删除持久化会话与无痕会话（纯只读，不修改任何排序时间戳）。
     *
     * @return 按 `lastMessageAt` 降序稳定排列的会话条目列表。
     */
    suspend fun listThreads(): List<WorkspaceAgentThreadEntry>

    /**
     * 创建一条新的持久化或无痕会话。
     *
     * @param incognito 为 `true` 时仅驻留已解锁会话内存，不写入 `threads/` 或加密同步目录。
     * @param selectedModelId 初始选中模型复合 ID；为空时回退至工作区默认模型或首个启用模型。
     * @param thinkingEffort 初始思考强度档位。
     * @param retrievalScope 初始检索暴露范围。
     * @param initialContextRefs 初始挂载的 `@` 上下文引用列表。
     * @param initialTitle 初始占位标题。
     * @return 新建会话条目。
     */
    suspend fun createThread(
        incognito: Boolean = false,
        selectedModelId: String? = null,
        thinkingEffort: AiThinkingEffort = AiThinkingEffort.OFF,
        retrievalScope: AiRetrievalScope = AiRetrievalScope.CITED_ONLY,
        initialContextRefs: List<AgentContextRef> = emptyList(),
        initialTitle: String = "新对话",
    ): WorkspaceAgentThreadEntry

    /**
     * 更新会话标题、归档状态或作曲框模型/思考/范围/引用配置，严格保持 `lastMessageAt` 不变。
     *
     * @param threadId 目标会话 ID。
     * @param title 新标题；为空时不修改。
     * @param archived 新归档状态；为空时不修改。
     * @param selectedModelId 新选中模型 ID；仅在 `updateSelectedModel = true` 时生效。
     * @param updateSelectedModel 是否显式更新 `selectedModelId`。
     * @param thinkingEffort 新思考档位；为空时不修改。
     * @param retrievalScope 新检索范围；为空时不修改。
     * @param activeContextRefs 新活跃引用列表；为空时不修改。
     * @return 更新后的会话条目。
     */
    suspend fun updateThreadState(
        threadId: ObjectId,
        title: String? = null,
        archived: Boolean? = null,
        selectedModelId: String? = null,
        updateSelectedModel: Boolean = false,
        thinkingEffort: AiThinkingEffort? = null,
        retrievalScope: AiRetrievalScope? = null,
        activeContextRefs: List<AgentContextRef>? = null,
    ): WorkspaceAgentThreadEntry

    /**
     * 删除指定会话（持久化会话写入加密 tombstone，无痕会话从内存移除）。
     *
     * @param threadId 待删除会话对象 ID。
     */
    suspend fun deleteThread(threadId: ObjectId)

    /**
     * 执行一轮用户提问与受控只读知识库工具循环，完成后单次持久化更新（无痕会话仅更新内存）。
     *
     * @param threadId 目标会话 ID。
     * @param userPrompt 用户输入文本。
     * @param contextRefs 本轮挂载的 `@` 引用列表。
     * @param selectedModelId 本轮选中的模型复合 ID。
     * @param thinkingEffort 本轮选中的思考强度。
     * @param retrievalScope 本轮激活的检索范围。
     * @param onProgress 流式增量内存回调（禁止写盘）。
     * @return 完成或被用户手动停止后的结果。
     */
    suspend fun runTurn(
        threadId: ObjectId,
        userPrompt: String,
        contextRefs: List<AgentContextRef>,
        selectedModelId: String?,
        thinkingEffort: AiThinkingEffort,
        retrievalScope: AiRetrievalScope,
        onProgress: (AgentHarnessProgress) -> Unit = {},
    ): WorkspaceAgentTurnResult

    /**
     * 由用户主动点击「停止」按钮终止当前在途生成，保留已流式接收的部分正文并正常落盘。
     *
     * @param threadId 可选限定会话 ID；为空时停止当前活动生成。
     */
    fun stopActiveTurn(threadId: ObjectId? = null)

    /**
     * 针对给定来源台账列表实时核对当前工作区中的最新笔记与段落状态。
     *
     * @param entries 待核验的来源条目列表。
     * @return `sourceIndex -> AgentCitationStatus` 映射表。
     */
    suspend fun evaluateCitations(entries: List<AgentSourceEntry>): Map<Int, AgentCitationStatus>

    /**
     * 切换待审阅提案中单个细粒度变更项的「采纳 / 跳过」勾选状态（不修改 `lastMessageAt`）。
     *
     * @param threadId 所属会话 ID。
     * @param proposalId 目标提案 ID。
     * @param itemId 目标变更项 ID。
     * @param accepted 为真表示采纳，为假表示跳过。
     * @return 更新后的会话条目。
     */
    suspend fun updateProposalItemAccepted(
        threadId: ObjectId,
        proposalId: String,
        itemId: String,
        accepted: Boolean,
    ): WorkspaceAgentThreadEntry

    /**
     * 放弃指定待确认修改提案（标记为 `DISCARDED`，不改动目标笔记与 `lastMessageAt`）。
     *
     * @param threadId 所属会话 ID。
     * @param proposalId 目标提案 ID。
     * @return 更新后的会话条目。
     */
    suspend fun discardProposal(
        threadId: ObjectId,
        proposalId: String,
    ): WorkspaceAgentThreadEntry

    /**
     * 当目标笔记在提案冻结后发生手动编辑导致 `expectedRevision` 过期时，基于最新已确认快照重新校验并刷新提案基准。
     *
     * @param threadId 所属会话 ID。
     * @param proposalId 目标提案 ID。
     * @return 刷新 `expectedRevision` 与旧值预览后的会话条目。
     */
    suspend fun recalculateProposal(
        threadId: ObjectId,
        proposalId: String,
    ): WorkspaceAgentThreadEntry

    /**
     * 将提案中所有勾选采纳的变更项与会话提案 `APPLIED` 状态在单次 `TransactionCoordinator` 原子事务中一并落盘确认。
     *
     * @param threadId 所属会话 ID。
     * @param proposalId 目标提案 ID。
     * @return 新确认的权威笔记与会话条目。
     */
    suspend fun applyProposal(
        threadId: ObjectId,
        proposalId: String,
    ): WorkspaceAgentProposalCommitOutcome

    /**
     * 手动对指定会话执行一次两阶段无损上下文压缩（保持 `lastMessageAt` 不变，保留近期 `retainPercent` 对话、活跃 `@` 引用与待审提案）。
     *
     * @param threadId 目标会话 ID。
     * @param activeContextRefs 当前作曲框挂载的活跃 `@` 引用快照；为空时沿用会话已存引用。
     * @return 追加了手动压缩节点并更新活跃窗口水位后的最新会话条目。
     */
    suspend fun compactThread(
        threadId: ObjectId,
        activeContextRefs: List<AgentContextRef>? = null,
    ): WorkspaceAgentThreadEntry
}
