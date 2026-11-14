package com.oruke.tagloom.core.ai

import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.PropertyId
import com.oruke.tagloom.core.model.TagId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 定义会话内结构化修改提案的目标类型。
 */
@Serializable
enum class AgentProposalKind {
    /** 对已有笔记执行细粒度元数据更新和/或局部段落补丁。 */
    PATCH_NOTE,

    /** 新建带笔记类型、属性与初始正文的完整笔记。 */
    CREATE_NOTE,
}

/**
 * 定义结构化修改提案在会话生命周期中的确认状态。
 */
@Serializable
enum class AgentProposalStatus {
    /** 冻结待审阅，尚未写入目标笔记快照。 */
    PENDING,

    /** 已由用户确认并经单次 `TransactionCoordinator` 原子事务提交。 */
    APPLIED,

    /** 已由用户主动放弃，不修改目标笔记。 */
    DISCARDED,
}

/**
 * 定义提案内部支持独立「采纳 / 跳过」的细粒度变更单元类别。
 */
@Serializable
enum class AgentProposalItemKind {
    /** 更新或设定笔记标题。 */
    TITLE,

    /** 设置或清除单自定义笔记类型（始终保留内置根 `Note`）。 */
    CUSTOM_TAG,

    /** 设置或清空单个结构化属性值。 */
    PROPERTY,

    /** 按稳定 `BlockId` 原位替换段落内容（首块保留原 `BlockId` 与父层级）。 */
    REPLACE_BLOCK,

    /** 在指定稳定 `BlockId` 之后插入局部 Markdown 段落。 */
    INSERT_AFTER_BLOCK,

    /** 在笔记根级活动段落开头前插局部 Markdown 段落。 */
    PREPEND_BLOCKS,

    /** 在笔记根级活动段落末尾追加局部 Markdown 段落。 */
    APPEND_BLOCKS,

    /** 按稳定 `BlockId` 将目标段落标记为 tombstone 移除。 */
    REMOVE_BLOCK,
}

/**
 * 描述修改提案中可由用户独立切换「采纳 / 跳过」的单个元数据或块级变更项。
 */
@Serializable
data class AgentProposalItem(
    /** 变更项在当前提案内的稳定不透明标识。 */
    val id: String,
    /** 变更项操作类别。 */
    val kind: AgentProposalItemKind,
    /** 来源工具标识（如 `update_note_metadata`、`patch_note_blocks`、`create_note`）。 */
    val toolLabel: String,
    /** 面向双方位 Diff 卡片头部展示的可读操作标签（如 `属性 · 状态`、`替换段落 · blk-2`）。 */
    val label: String,
    /** 提案生成时基准快照上的旧值可读摘要；纯新增项为空串。 */
    val oldPreview: String = "",
    /** 建议写入的新值可读摘要；纯移除项为空串。 */
    val newPreview: String = "",
    /** 用户在审阅界面是否勾选采纳该变更项（默认采纳）。 */
    val accepted: Boolean = true,
    /** `TITLE` 变更项的新标题文本。 */
    val newTitle: String? = null,
    /** `CUSTOM_TAG` 变更项是否显式清除自定义笔记类型仅保留内置根 `Note`。 */
    val clearCustomTag: Boolean = false,
    /** `CUSTOM_TAG` 变更项的新单自定义笔记类型 ID。 */
    val newCustomTagId: TagId? = null,
    /** `PROPERTY` 变更项的目标稳定属性 ID。 */
    val propertyId: PropertyId? = null,
    /** `PROPERTY` 变更项的新属性值 JSON；为空（`null`）表示清空该笔记的显式属性值。 */
    val newPropertyValue: JsonElement? = null,
    /** `REPLACE_BLOCK`、`INSERT_AFTER_BLOCK`、`REMOVE_BLOCK` 定位的目标稳定段落 ID。 */
    val targetBlockId: BlockId? = null,
    /** 块级替换或插入项携带的局部轻量 Markdown 源码。 */
    val markdown: String? = null,
) {
    /** 判断当前变更项是否属于元数据级修改（标题、笔记类型或属性）。 */
    val isMetadataChange: Boolean
        get() = kind == AgentProposalItemKind.TITLE ||
            kind == AgentProposalItemKind.CUSTOM_TAG ||
            kind == AgentProposalItemKind.PROPERTY

    /** 判断当前变更项是否属于正文块级修改。 */
    val isBlockChange: Boolean
        get() = !isMetadataChange
}

/**
 * 描述存储在加密 `AGENT_THREAD` 快照内的结构化笔记修改提案。
 *
 * 所有模型写入工具（`patch_note_blocks`、`update_note_metadata`、`create_note`）均只生成此冻结提案，
 * 由用户在全屏会话右侧滑出面板或笔记编辑页左侧原位 Diff 中逐项审阅确认后原子落盘。
 */
@Serializable
data class AgentMutationProposal(
    /** 提案稳定不透明标识。 */
    val id: String,
    /** 生成该提案的助手回复消息 ID（对应 `AgentThreadMessage.id`）。 */
    val messageId: String,
    /** 提案目标类型（更新既有笔记或创建新笔记）。 */
    val kind: AgentProposalKind,
    /** 目标笔记稳定对象 ID（`CREATE_NOTE` 时为预分配的稳定新笔记 ID）。 */
    val noteId: ObjectId,
    /** 提案创建时的目标笔记展示标题。 */
    val noteTitle: String,
    /** 更新既有笔记时冻结的乐观锁基准 `revision`（`actorId:sequence`）；新建笔记时为空。 */
    val expectedRevision: String? = null,
    /** 提案当前状态。 */
    val status: AgentProposalStatus = AgentProposalStatus.PENDING,
    /** 按执行顺序排列的细粒度可采纳变更项列表。 */
    val items: List<AgentProposalItem>,
    /** 预览时冻结的工作区类型与属性目录 `objectId -> revision` 映射，确认前复核防篡改。 */
    val schemaRevisions: Map<String, String> = emptyMap(),
    /** 成功提交后目标笔记生成的新 `revision` 文本。 */
    val appliedRevision: String? = null,
    /** 成功提交后的结果摘要（如 `已原子提交 · 采纳 2/3 项`）。 */
    val appliedSummary: String? = null,
    /** 提案首次生成时间（UTC ISO-8601）。 */
    val createdAt: String,
    /** 提案状态或逐项选择最近更新时间（UTC ISO-8601）。 */
    val updatedAt: String,
) {
    /** 提案中包含的元数据变更项数量。 */
    val metadataItemCount: Int
        get() = items.count(AgentProposalItem::isMetadataChange)

    /** 提案中包含的局部块补丁变更项数量。 */
    val blockItemCount: Int
        get() = items.count(AgentProposalItem::isBlockChange)

    /** 当前处于勾选采纳状态的变更项数量。 */
    val acceptedItemCount: Int
        get() = items.count(AgentProposalItem::accepted)
}
