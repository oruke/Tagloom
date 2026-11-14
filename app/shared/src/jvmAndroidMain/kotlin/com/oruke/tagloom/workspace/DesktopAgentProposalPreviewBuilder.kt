package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.agent.AgentToolName
import com.oruke.tagloom.core.ai.AgentContextRef
import com.oruke.tagloom.core.ai.AgentMutationProposal
import com.oruke.tagloom.core.ai.AgentProposalItem
import com.oruke.tagloom.core.ai.AgentProposalItemKind
import com.oruke.tagloom.core.ai.AgentProposalKind
import com.oruke.tagloom.core.ai.AgentProposalStatus
import com.oruke.tagloom.core.ai.AgentProposalToolOutcome
import com.oruke.tagloom.core.ai.AgentToolTrace
import com.oruke.tagloom.core.ai.AiRetrievalScope
import com.oruke.tagloom.core.ai.NoteFineGrainedPatchService
import com.oruke.tagloom.core.domain.property.EffectivePropertyResolver
import com.oruke.tagloom.core.domain.tag.TagResolution
import com.oruke.tagloom.core.domain.tag.TagResolver
import com.oruke.tagloom.core.external.ExternalCallException
import com.oruke.tagloom.core.external.ExternalToolArguments
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.NoteNormalizer
import com.oruke.tagloom.core.model.NoteValidator
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.PropertyId
import com.oruke.tagloom.core.model.PropertyTypes
import com.oruke.tagloom.core.model.SystemDatabaseDefinitions
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.core.model.TagId
import com.oruke.tagloom.core.model.extractPlainText
import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * 在已解锁会话互斥锁内校验写提案工具（`create_note` / `update_note_metadata` / `patch_note_blocks`）并生成待确认 [AgentMutationProposal]。
 *
 * 预演期间严禁产生任何磁盘写入；所有 `AiAccessPolicy`、`expectedRevision`、单自定义笔记类型与只读/公式属性门禁均在此阶段提前拦截。
 *
 * @param session 当前已解锁工作区会话。
 * @param contextResolver 内置检索范围与访问策略门禁解析器。
 */
internal class DesktopAgentProposalPreviewBuilder(
    private val session: DesktopWorkspaceSession,
    private val contextResolver: DesktopAgentHarnessContextResolver,
) {
    /**
     * 在持有 `sessionMutex` 时预演模型发起的写工具调用并构造待审阅提案结果。
     *
     * @param callId 模型工具调用 ID。
     * @param tool 待预演的写工具枚举。
     * @param argumentsJson 原始参数 JSON 字符串。
     * @param assistantMessageId 当前正在生成的助手消息 ID。
     * @param retrievalScope 当前激活的检索暴露范围。
     * @param activeRefs 当前有效的 `@` 上下文引用列表。
     * @return 回传给模型的工具 JSON、待审阅提案与轨迹摘要。
     */
    suspend fun prepareWriteProposalLocked(
        callId: String,
        tool: AgentToolName,
        argumentsJson: String,
        assistantMessageId: String,
        retrievalScope: AiRetrievalScope,
        activeRefs: List<AgentContextRef>,
    ): AgentProposalToolOutcome {
        val toolWire = tool.name.lowercase()
        val args = runCatching {
            CanonicalJson.format.parseToJsonElement(argumentsJson.ifBlank { "{}" }).jsonObject
        }.getOrElse {
            return errorOutcome(callId, toolWire, "invalid_arguments", "写提案参数 JSON 无效")
        }
        return try {
            session.requireWritable()
            val proposal = when (tool) {
                AgentToolName.CREATE_NOTE -> buildCreateNoteProposal(args, assistantMessageId)
                AgentToolName.UPDATE_NOTE_METADATA ->
                    buildMetadataProposal(args, assistantMessageId, retrievalScope, activeRefs)
                AgentToolName.PATCH_NOTE_BLOCKS ->
                    buildBlockPatchProposal(args, assistantMessageId, retrievalScope, activeRefs)
                else -> throw ExternalCallException("tool_unavailable")
            }
            val resultJson = CanonicalJson.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("status", "pending_confirmation")
                    put("proposalId", proposal.id)
                    put("noteId", proposal.noteId.value)
                    put("expectedRevision", proposal.expectedRevision?.let(::JsonPrimitive) ?: JsonNull)
                    put("itemCount", proposal.items.size)
                },
            )
            val summary = if (proposal.kind == AgentProposalKind.CREATE_NOTE) {
                "已准备新笔记提案「${proposal.noteTitle}」（待确认）"
            } else {
                "已准备修改提案「${proposal.noteTitle}」（${proposal.items.size} 项变更待确认）"
            }
            AgentProposalToolOutcome(
                resultJson = resultJson,
                proposal = proposal,
                trace = AgentToolTrace(callId = callId, toolName = toolWire, summary = summary),
            )
        } catch (err: ExternalCallException) {
            val skipped = if (err.code.contains("ai_access_policy")) 1 else 0
            errorOutcome(callId, toolWire, err.code, "生成修改提案被拒 (${err.code})", skipped)
        }
    }

    /**
     * 基于最新已确认笔记快照重新核验并刷新过期提案的 `expectedRevision`、Schema 基准与旧值预览。
     *
     * @param proposal 待重算的既有 `PENDING` 提案。
     * @param currentStored 当前最新权威笔记快照。
     * @return 刷新后的 [AgentMutationProposal]；若块锚点已被删除则抛出 `block_anchor_missing`。
     */
    suspend fun refreshProposalAgainstLatestNoteLocked(
        proposal: AgentMutationProposal,
        currentStored: DesktopStoredNote,
    ): AgentMutationProposal {
        val currentNote = currentStored.snapshot.payload
        val schema = session.schemaStore.load(session.unlocked)
        val tagsById = schema.tags.associate { it.payload.id to it.payload }
        val propsById = schema.properties.associate { it.payload.id to it.payload }
        val effectiveMap = resolveEffectivePropertyMap(currentNote, tagsById, propsById)
        val blocksById = currentNote.blocks.filterNot(Block::deleted).associateBy(Block::id)

        val refreshedItems = proposal.items.map { item ->
            when (item.kind) {
                AgentProposalItemKind.TITLE -> item.copy(oldPreview = currentNote.title)
                AgentProposalItemKind.CUSTOM_TAG -> item.copy(
                    oldPreview = formatCustomTagPreview(currentNote.directTagIds, tagsById),
                )
                AgentProposalItemKind.PROPERTY -> {
                    val propId = requireNotNull(item.propertyId)
                    val oldJson = currentNote.propertyValues[propId]?.value ?: effectiveMap[propId]
                    item.copy(oldPreview = formatPropertyValue(oldJson, propsById[propId]))
                }
                AgentProposalItemKind.REPLACE_BLOCK,
                AgentProposalItemKind.REMOVE_BLOCK,
                -> {
                    val block = blocksById[item.targetBlockId]
                        ?: throw ExternalCallException("block_anchor_missing")
                    item.copy(oldPreview = block.extractPlainText().orEmpty().trim())
                }
                AgentProposalItemKind.INSERT_AFTER_BLOCK -> {
                    if (item.targetBlockId !in blocksById) {
                        throw ExternalCallException("block_anchor_missing")
                    }
                    item
                }
                AgentProposalItemKind.PREPEND_BLOCKS,
                AgentProposalItemKind.APPEND_BLOCKS,
                -> item
            }
        }

        val now = session.timestampProvider()
        val dryRunDraft = NoteFineGrainedPatchService.applyAcceptedProposalItems(
            baseNote = currentNote,
            acceptedItems = refreshedItems,
            updatedAt = now,
            blockIdFactory = { BlockId(session.idFactory()) },
            tableIdFactory = session.idFactory,
        )
        val normalized = validateNoteStructure(dryRunDraft)
        val validated = DesktopAgentNoteSchemaService(session).prepare(normalized, currentNote)
        val newTitle = refreshedItems.lastOrNull { it.kind == AgentProposalItemKind.TITLE && it.accepted }?.newTitle
            ?: currentNote.title

        return proposal.copy(
            noteTitle = newTitle,
            expectedRevision = currentStored.snapshot.header.revision.externalText(),
            items = refreshedItems,
            schemaRevisions = validated.schemaRevisions.entries.associate { it.key.value to it.value.externalText() },
            updatedAt = now,
        )
    }

    /** 构造 `create_note` 新建笔记待确认提案并完成 Schema 预演校验。 */
    private suspend fun buildCreateNoteProposal(
        args: JsonObject,
        assistantMessageId: String,
    ): AgentMutationProposal {
        val title = ExternalToolArguments.string(args, "title", true)!!.trim()
        if (title.isEmpty() || title.length > 4096) throw ExternalCallException("invalid_arguments")
        val rawTagIds = ExternalToolArguments.strings(args, "tagIds", 10).map(::TagId)
        val customTags = rawTagIds.filter { it != WorkspaceDefaults.DEFAULT_NOTE_TAG_ID }.distinct()
        if (customTags.size > 1) throw ExternalCallException("note_type_invalid")
        val customTagId = customTags.singleOrNull()
        val markdown = ExternalToolArguments.string(args, "markdown")?.trim().orEmpty()
        val propUpdates = (args["propertyUpdates"] as? JsonObject).orEmpty()

        val schema = session.schemaStore.load(session.unlocked)
        val tagsById = schema.tags.associate { it.payload.id to it.payload }
        val propsById = schema.properties.associate { it.payload.id to it.payload }
        val items = mutableListOf<AgentProposalItem>()
        items += AgentProposalItem(
            id = session.idFactory(),
            kind = AgentProposalItemKind.TITLE,
            toolLabel = "create_note",
            label = "新笔记标题",
            oldPreview = "",
            newPreview = title,
            newTitle = title,
        )
        if (customTagId != null) {
            val tagName = tagsById[customTagId]?.name ?: throw ExternalCallException("schema_invalid")
            items += AgentProposalItem(
                id = session.idFactory(),
                kind = AgentProposalItemKind.CUSTOM_TAG,
                toolLabel = "create_note",
                label = "笔记类型",
                oldPreview = "默认笔记",
                newPreview = tagName,
                newCustomTagId = customTagId,
            )
        }
        propUpdates.forEach { (rawId, jsonVal) ->
            val propId = PropertyId(ExternalToolArguments.opaque(rawId))
            val def = propsById[propId] ?: throw ExternalCallException("schema_invalid")
            items += AgentProposalItem(
                id = session.idFactory(),
                kind = AgentProposalItemKind.PROPERTY,
                toolLabel = "create_note",
                label = "属性 · ${def.name}",
                oldPreview = "",
                newPreview = formatPropertyValue(jsonVal, def),
                propertyId = propId,
                newPropertyValue = jsonVal,
            )
        }
        if (markdown.isNotEmpty()) {
            items += AgentProposalItem(
                id = session.idFactory(),
                kind = AgentProposalItemKind.APPEND_BLOCKS,
                toolLabel = "create_note",
                label = "初始 Markdown 正文",
                oldPreview = "",
                newPreview = markdown,
                markdown = markdown,
            )
        }

        val now = session.timestampProvider()
        val draft = NoteFineGrainedPatchService.applyAcceptedProposalItems(
            baseNote = Note(title = title, directTagIds = listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID)),
            acceptedItems = items,
            updatedAt = now,
            blockIdFactory = { BlockId(session.idFactory()) },
            tableIdFactory = session.idFactory,
        )
        val validated = DesktopAgentNoteSchemaService(session).prepare(validateNoteStructure(draft), previous = null)
        return AgentMutationProposal(
            id = session.idFactory(),
            messageId = assistantMessageId,
            kind = AgentProposalKind.CREATE_NOTE,
            noteId = ObjectId(session.idFactory()),
            noteTitle = title,
            expectedRevision = null,
            status = AgentProposalStatus.PENDING,
            items = items,
            schemaRevisions = validated.schemaRevisions.entries.associate { it.key.value to it.value.externalText() },
            createdAt = now,
            updatedAt = now,
        )
    }

    /** 构造 `update_note_metadata` 元数据细粒度提案并执行预演校验。 */
    private suspend fun buildMetadataProposal(
        args: JsonObject,
        assistantMessageId: String,
        retrievalScope: AiRetrievalScope,
        activeRefs: List<AgentContextRef>,
    ): AgentMutationProposal {
        val target = resolveAuthorizedMutableNote(args, retrievalScope, activeRefs)
        val currentNote = target.snapshot.payload
        val patch = NoteFineGrainedPatchService.parseMetadataPatch(args)
        val schema = session.schemaStore.load(session.unlocked)
        val tagsById = schema.tags.associate { it.payload.id to it.payload }
        val propsById = schema.properties.associate { it.payload.id to it.payload }
        val effectiveMap = resolveEffectivePropertyMap(currentNote, tagsById, propsById)

        val items = mutableListOf<AgentProposalItem>()
        patch.title?.let { newTitle ->
            items += AgentProposalItem(
                id = session.idFactory(),
                kind = AgentProposalItemKind.TITLE,
                toolLabel = "update_note_metadata",
                label = "笔记标题",
                oldPreview = currentNote.title,
                newPreview = newTitle,
                newTitle = newTitle,
            )
        }
        if (patch.hasCustomTagChange) {
            val newTagName = if (patch.customTagId == null) {
                "默认笔记（清除自定义类型）"
            } else {
                tagsById[patch.customTagId]?.name ?: throw ExternalCallException("schema_invalid")
            }
            items += AgentProposalItem(
                id = session.idFactory(),
                kind = AgentProposalItemKind.CUSTOM_TAG,
                toolLabel = "update_note_metadata",
                label = "笔记类型",
                oldPreview = formatCustomTagPreview(currentNote.directTagIds, tagsById),
                newPreview = newTagName,
                clearCustomTag = patch.customTagId == null,
                newCustomTagId = patch.customTagId,
            )
        }
        patch.propertyUpdates.forEach { (propId, jsonVal) ->
            val def = propsById[propId] ?: throw ExternalCallException("schema_invalid")
            val oldJson = currentNote.propertyValues[propId]?.value ?: effectiveMap[propId]
            items += AgentProposalItem(
                id = session.idFactory(),
                kind = AgentProposalItemKind.PROPERTY,
                toolLabel = "update_note_metadata",
                label = "属性 · ${def.name}",
                oldPreview = formatPropertyValue(oldJson, def),
                newPreview = formatPropertyValue(jsonVal, def),
                propertyId = propId,
                newPropertyValue = jsonVal,
            )
        }

        val now = session.timestampProvider()
        val draft = NoteFineGrainedPatchService.applyMetadataPatch(currentNote, patch, now)
        val validated = DesktopAgentNoteSchemaService(session).prepare(validateNoteStructure(draft), currentNote)
        return AgentMutationProposal(
            id = session.idFactory(),
            messageId = assistantMessageId,
            kind = AgentProposalKind.PATCH_NOTE,
            noteId = target.id,
            noteTitle = patch.title ?: currentNote.title,
            expectedRevision = target.snapshot.header.revision.externalText(),
            status = AgentProposalStatus.PENDING,
            items = items,
            schemaRevisions = validated.schemaRevisions.entries.associate { it.key.value to it.value.externalText() },
            createdAt = now,
            updatedAt = now,
        )
    }

    /** 构造 `patch_note_blocks` 局部段落补丁提案并执行预演校验。 */
    private suspend fun buildBlockPatchProposal(
        args: JsonObject,
        assistantMessageId: String,
        retrievalScope: AiRetrievalScope,
        activeRefs: List<AgentContextRef>,
    ): AgentMutationProposal {
        val target = resolveAuthorizedMutableNote(args, retrievalScope, activeRefs)
        val currentNote = target.snapshot.payload
        val ops = NoteFineGrainedPatchService.parseBlockOperations(args)
        val now = session.timestampProvider()
        val draft = NoteFineGrainedPatchService.applyBlockOperations(
            note = currentNote,
            operations = ops,
            importedAt = now,
            blockIdFactory = { BlockId(session.idFactory()) },
            tableIdFactory = session.idFactory,
        )
        val validated = DesktopAgentNoteSchemaService(session).prepare(validateNoteStructure(draft), currentNote)
        val blocksById = currentNote.blocks.filterNot(Block::deleted).associateBy(Block::id)

        val items = ops.map { op ->
            val oldText = op.targetBlockId?.let { blocksById[it]?.extractPlainText().orEmpty().trim() }.orEmpty()
            val label = when (op.kind) {
                AgentProposalItemKind.REPLACE_BLOCK -> "替换段落 · ${op.targetBlockId?.value}（保留稳定 ID）"
                AgentProposalItemKind.INSERT_AFTER_BLOCK -> "在 ${op.targetBlockId?.value} 后插入段落"
                AgentProposalItemKind.PREPEND_BLOCKS -> "在正文开头前插段落"
                AgentProposalItemKind.APPEND_BLOCKS -> "在正文末尾追加段落"
                AgentProposalItemKind.REMOVE_BLOCK -> "移除段落 · ${op.targetBlockId?.value}"
                else -> "局部段落变更"
            }
            AgentProposalItem(
                id = session.idFactory(),
                kind = op.kind,
                toolLabel = "patch_note_blocks",
                label = label,
                oldPreview = if (op.kind == AgentProposalItemKind.INSERT_AFTER_BLOCK) "" else oldText,
                newPreview = op.markdown?.trim().orEmpty(),
                targetBlockId = op.targetBlockId,
                markdown = op.markdown,
            )
        }
        return AgentMutationProposal(
            id = session.idFactory(),
            messageId = assistantMessageId,
            kind = AgentProposalKind.PATCH_NOTE,
            noteId = target.id,
            noteTitle = currentNote.title,
            expectedRevision = target.snapshot.header.revision.externalText(),
            status = AgentProposalStatus.PENDING,
            items = items,
            schemaRevisions = validated.schemaRevisions.entries.associate { it.key.value to it.value.externalText() },
            createdAt = now,
            updatedAt = now,
        )
    }

    /** 校验内置检索范围、`AiAccessPolicy` 与 `expectedRevision` 乐观锁并返回授权目标笔记。 */
    private suspend fun resolveAuthorizedMutableNote(
        args: JsonObject,
        retrievalScope: AiRetrievalScope,
        activeRefs: List<AgentContextRef>,
    ): DesktopStoredNote {
        val policyResolver = session.loadAiAccessPolicyResolver()
        val activeNotes = session.loadActiveNotes()
            .filterNot { SystemDatabaseDefinitions.isProtectedSystemPage(it.id) }
        val notesById = activeNotes.associateBy { it.id }
        val scopeCtx = contextResolver.buildScopedReadContextLocked(retrievalScope, activeRefs, activeNotes, policyResolver)
        val stored = contextResolver.resolveAuthorizedDirectNote(args, notesById, policyResolver, retrievalScope, scopeCtx)
        val expectedRev = parseExternalRevision(ExternalToolArguments.string(args, "expectedRevision", true)!!)
        if (expectedRev != stored.snapshot.header.revision) {
            throw ExternalCallException("revision_conflict")
        }
        return stored
    }

    /** 规范化并校验 Note 结构不变量。 */
    private fun validateNoteStructure(draft: Note): Note {
        val normalized = NoteNormalizer.normalize(draft)
        if (normalized.title.isBlank() || normalized.title.length > 4096 || normalized.blocks.size > 5000 ||
            NoteValidator.validate(normalized).isNotEmpty()
        ) {
            throw ExternalCallException("note_invalid")
        }
        return normalized
    }

    /** 解析当前笔记的有效继承属性值映射，用于展示准确的修改前预览。 */
    private fun resolveEffectivePropertyMap(
        note: Note,
        tagsById: Map<TagId, TagDefinition>,
        propsById: Map<PropertyId, PropertyDefinition>,
    ): Map<PropertyId, JsonElement?> {
        val resolution = TagResolver(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID)
            .resolve(note.directTagIds, tagsById) as? TagResolution.Success ?: return emptyMap()
        return EffectivePropertyResolver().resolve(note, resolution, tagsById, propsById)
            .properties.associate { it.propertyId to it.value }
    }

    /** 格式化当前笔记的单自定义笔记类型名称。 */
    private fun formatCustomTagPreview(directTagIds: List<TagId>, tagsById: Map<TagId, TagDefinition>): String {
        val custom = directTagIds.firstOrNull { it != WorkspaceDefaults.DEFAULT_NOTE_TAG_ID } ?: return "默认笔记"
        return tagsById[custom]?.name ?: custom.value
    }

    /** 将属性 JSON 值转换为面向用户可读的文本或选项标签（空值返回 `（未设置）`）。 */
    private fun formatPropertyValue(value: JsonElement?, def: PropertyDefinition?): String {
        if (value == null || value == JsonNull) return "（空）"
        if (def != null && def.type == PropertyTypes.SELECT) {
            val optId = (value as? JsonPrimitive)?.content
            return def.options.firstOrNull { it.id == optId }?.label ?: optId.orEmpty()
        }
        if (def != null && def.type == PropertyTypes.MULTI_SELECT && value is JsonArray) {
            val labels = value.mapNotNull { elem ->
                val optId = (elem as? JsonPrimitive)?.content ?: return@mapNotNull null
                def.options.firstOrNull { it.id == optId }?.label ?: optId
            }
            return if (labels.isEmpty()) "（空）" else labels.joinToString("、")
        }
        return when (value) {
            is JsonPrimitive -> value.content
            else -> CanonicalJson.encodeToString(JsonElement.serializer(), value)
        }
    }

    /** 构造写提案预演失败返回结果。 */
    private fun errorOutcome(
        callId: String,
        toolName: String,
        errorCode: String,
        summary: String,
        skippedProtectedCount: Int = 0,
    ): AgentProposalToolOutcome = AgentProposalToolOutcome(
        resultJson = """{"error":"$errorCode"}""",
        proposal = null,
        trace = AgentToolTrace(
            callId = callId,
            toolName = toolName,
            summary = summary,
            skippedProtectedCount = skippedProtectedCount,
            succeeded = false,
        ),
    )
}
