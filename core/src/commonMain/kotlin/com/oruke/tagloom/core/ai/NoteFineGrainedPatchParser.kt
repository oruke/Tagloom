package com.oruke.tagloom.core.ai

import com.oruke.tagloom.core.external.ExternalCallException
import com.oruke.tagloom.core.external.ExternalToolArguments
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.NotePropertyValue
import com.oruke.tagloom.core.model.PropertyId
import com.oruke.tagloom.core.model.PropertyValueSource
import com.oruke.tagloom.core.model.TagId
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 描述从 `patch_note_blocks` 协议参数解析出的单条块级局部补丁指令。
 */
data class ParsedBlockPatchOperation(
    /** 操作类型（与 [AgentProposalItemKind] 块操作一一对应）。 */
    val kind: AgentProposalItemKind,
    /** 目标或锚点稳定 `BlockId`；`PREPEND_BLOCKS` 与 `APPEND_BLOCKS` 时为空。 */
    val targetBlockId: BlockId? = null,
    /** 局部 Markdown 正文；`REMOVE_BLOCK` 时为空。 */
    val markdown: String? = null,
)

/**
 * 描述从 `update_note_metadata` 协议参数解析出的局部元数据补丁指令。
 */
data class ParsedMetadataPatch(
    /** 可选新标题；为空表示不修改标题。 */
    val title: String? = null,
    /** 是否显式修改单自定义笔记类型。 */
    val hasCustomTagChange: Boolean = false,
    /** 目标单自定义笔记类型 ID；在 `hasCustomTagChange = true` 且本字段为空时表示清除自定义类型仅保留根 `Note`。 */
    val customTagId: TagId? = null,
    /** 按稳定 `PropertyId` 排列的属性更新映射；值为 `JsonNull` 表示移除该笔记上的显式属性值。 */
    val propertyUpdates: Map<PropertyId, JsonElement> = emptyMap(),
)

/**
 * 负责解析并校验 `patch_note_blocks` 与 `update_note_metadata` 工具协议参数，以及合成笔记元数据字段。
 */
internal object NoteFineGrainedPatchParser {
    /** 允许在单条局部 Markdown 补丁中传入的最大字符数上限。 */
    internal const val MAX_PATCH_MARKDOWN_LENGTH: Int = 250_000

    /** 单条块操作允许出现的协议键集合。 */
    private val ALLOWED_BLOCK_OP_KEYS: Set<String> = setOf("op", "blockId", "markdown")

    /**
     * 解析并校验 `patch_note_blocks` 工具参数中的 `operations` 数组。
     *
     * @param args 工具协议参数对象。
     * @return 按输入顺序排列的强类型块操作列表。
     */
    fun parseBlockOperations(args: JsonObject): List<ParsedBlockPatchOperation> {
        val rawOps = args["operations"] as? JsonArray ?: throw ExternalCallException("invalid_arguments")
        if (rawOps.isEmpty() || rawOps.size > 50) {
            throw ExternalCallException("invalid_arguments")
        }
        return rawOps.map { element ->
            val obj = element as? JsonObject ?: throw ExternalCallException("invalid_arguments")
            if (!obj.keys.all { it in ALLOWED_BLOCK_OP_KEYS }) {
                throw ExternalCallException("invalid_arguments")
            }
            val opName = ExternalToolArguments.string(obj, "op", required = true)!!
            when (opName) {
                "replace_block" -> ParsedBlockPatchOperation(
                    kind = AgentProposalItemKind.REPLACE_BLOCK,
                    targetBlockId = BlockId(
                        ExternalToolArguments.opaque(
                            ExternalToolArguments.string(obj, "blockId", required = true)!!,
                        ),
                    ),
                    markdown = requireNonBlankMarkdown(obj),
                )
                "insert_after_block" -> ParsedBlockPatchOperation(
                    kind = AgentProposalItemKind.INSERT_AFTER_BLOCK,
                    targetBlockId = BlockId(
                        ExternalToolArguments.opaque(
                            ExternalToolArguments.string(obj, "blockId", required = true)!!,
                        ),
                    ),
                    markdown = requireNonBlankMarkdown(obj),
                )
                "prepend_blocks" -> {
                    if (obj["blockId"] != null) throw ExternalCallException("invalid_arguments")
                    ParsedBlockPatchOperation(
                        kind = AgentProposalItemKind.PREPEND_BLOCKS,
                        markdown = requireNonBlankMarkdown(obj),
                    )
                }
                "append_blocks" -> {
                    if (obj["blockId"] != null) throw ExternalCallException("invalid_arguments")
                    ParsedBlockPatchOperation(
                        kind = AgentProposalItemKind.APPEND_BLOCKS,
                        markdown = requireNonBlankMarkdown(obj),
                    )
                }
                "remove_block" -> {
                    if (obj["markdown"] != null) throw ExternalCallException("invalid_arguments")
                    ParsedBlockPatchOperation(
                        kind = AgentProposalItemKind.REMOVE_BLOCK,
                        targetBlockId = BlockId(
                            ExternalToolArguments.opaque(
                                ExternalToolArguments.string(obj, "blockId", required = true)!!,
                            ),
                        ),
                    )
                }
                else -> throw ExternalCallException("invalid_arguments")
            }
        }
    }

    /**
     * 解析并校验 `update_note_metadata` 工具参数。
     *
     * @param args 工具协议参数对象。
     * @return 强类型元数据更新描述；若未包含任何实质变更字段则抛出 `invalid_arguments`。
     */
    fun parseMetadataPatch(args: JsonObject): ParsedMetadataPatch {
        val title = ExternalToolArguments.string(args, "title")?.let { raw ->
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.length > 4096) throw ExternalCallException("invalid_arguments")
            trimmed
        }
        val hasCustomTag = "customTagId" in args
        val customTagId: TagId? = if (!hasCustomTag) {
            null
        } else {
            when (val rawTag = args["customTagId"]) {
                null, JsonNull -> null
                is JsonPrimitive -> {
                    if (!rawTag.isString) throw ExternalCallException("invalid_arguments")
                    val text = rawTag.content.trim()
                    if (text.isEmpty() || text == WorkspaceDefaults.DEFAULT_NOTE_TAG_ID.value) {
                        null
                    } else {
                        TagId(ExternalToolArguments.opaque(text))
                    }
                }
                else -> throw ExternalCallException("invalid_arguments")
            }
        }
        val updatesObj = args["propertyUpdates"]?.let { element ->
            element as? JsonObject ?: throw ExternalCallException("invalid_arguments")
        }.orEmpty()
        if (updatesObj.size > 100) throw ExternalCallException("invalid_arguments")
        val propertyUpdates = linkedMapOf<PropertyId, JsonElement>()
        updatesObj.forEach { (rawPropId, value) ->
            val propId = PropertyId(ExternalToolArguments.opaque(rawPropId))
            propertyUpdates[propId] = value
        }
        if (title == null && !hasCustomTag && propertyUpdates.isEmpty()) {
            throw ExternalCallException("invalid_arguments")
        }
        return ParsedMetadataPatch(
            title = title,
            hasCustomTagChange = hasCustomTag,
            customTagId = customTagId,
            propertyUpdates = propertyUpdates,
        )
    }

    /**
     * 在基准 [note] 上应用局部元数据更新（标题、单自定义类型与结构化属性键值）。
     *
     * @param note 基准权威笔记快照载荷。
     * @param patch 解析后的元数据更新指令。
     * @param updatedAt 属性更新时间（UTC ISO-8601）。
     * @return 更新元数据后的 [Note] 草稿（后续仍须经 Schema 服务执行只读与类型/选项校验）。
     */
    fun applyMetadataPatch(
        note: Note,
        patch: ParsedMetadataPatch,
        updatedAt: String,
    ): Note {
        val nextTitle = patch.title ?: note.title
        val nextDirectTags = if (!patch.hasCustomTagChange) {
            note.directTagIds
        } else if (patch.customTagId == null) {
            listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID)
        } else {
            listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID, patch.customTagId).distinct()
        }
        val nextProperties = note.propertyValues.toMutableMap()
        patch.propertyUpdates.forEach { (propId, jsonValue) ->
            if (jsonValue == JsonNull) {
                nextProperties.remove(propId)
            } else {
                nextProperties[propId] = NotePropertyValue(
                    value = jsonValue,
                    source = PropertyValueSource.USER,
                    sourceTagId = null,
                    overridden = true,
                    updatedAt = updatedAt,
                )
            }
        }
        return note.copy(
            title = nextTitle,
            directTagIds = nextDirectTags,
            propertyValues = nextProperties,
            aiAccessPolicy = note.aiAccessPolicy,
        )
    }

    /**
     * 读取并校验块操作对象中的非空 `markdown` 文本字段。
     *
     * @param obj 单条块操作参数 JSON 对象。
     * @return 非空且未超长的原始 Markdown 字符串。
     */
    private fun requireNonBlankMarkdown(obj: JsonObject): String {
        val md = ExternalToolArguments.string(obj, "markdown", required = true)!!
        if (md.isBlank() || md.length > MAX_PATCH_MARKDOWN_LENGTH) {
            throw ExternalCallException("invalid_arguments")
        }
        return md
    }
}
