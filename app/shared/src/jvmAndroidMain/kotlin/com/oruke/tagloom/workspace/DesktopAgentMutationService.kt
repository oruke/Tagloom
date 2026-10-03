package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.agent.*
import com.oruke.tagloom.core.domain.property.*
import com.oruke.tagloom.core.domain.tag.*
import com.oruke.tagloom.core.external.*
import com.oruke.tagloom.core.markdown.*
import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import kotlinx.serialization.json.*

/** 在预览阶段构造并校验 Note 写入计划；执行阶段只允许确认过的 payload 和 revision。 */
internal class DesktopAgentMutationService(
    /** 当前唯一工作区会话，调用方持有会话锁。 */
    private val session: DesktopWorkspaceSession,
    /** 外部请求授权身份。 */
    private val principal: AgentPrincipal,
) {
    /**
     * 生成创建、更新、设定类型或删除的冻结写入计划。
     * @param request 已通过 scope 判定的共享请求。
     * @return 与预览一致的完整 Note 及预期旧 revision。
     */
    suspend fun plan(request: AgentToolRequest): DesktopAgentNoteMutation {
        session.requireWritable()
        val args = request.arguments as JsonObject
        val previous = ExternalToolArguments.string(args, "noteId")?.let {
            DesktopAgentReadService(session, principal).note(ObjectId(it))
        }
        val revision = if (previous == null) null else expected(args, previous)
        val note = when (request.tool) {
            AgentToolName.CREATE_NOTE -> {
                val title = ExternalToolArguments.string(args, "title", true)!!
                val tagIds = (listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID) + ExternalToolArguments.strings(args, "tagIds", 10).map(::TagId)).distinct()
                val imported = importMarkdown(ExternalToolArguments.string(args, "markdown").orEmpty(), title)
                imported.copy(title = title, directTagIds = tagIds)
            }
            AgentToolName.UPDATE_NOTE -> {
                val current = requireNotNull(previous).snapshot.payload
                if (args["note"] != null && (args["title"] != null || args["markdown"] != null)) {
                    throw ExternalCallException("invalid_arguments")
                }
                val replacement = args["note"]?.let { CanonicalJson.format.decodeFromJsonElement(Note.serializer(), it) }
                if (replacement != null) replacement else {
                    if (args["title"] == null && args["markdown"] == null) throw ExternalCallException("invalid_arguments")
                    val title = ExternalToolArguments.string(args, "title") ?: current.title
                    val imported = ExternalToolArguments.string(args, "markdown")?.let { importMarkdown(it, title) }
                    if (imported == null) current.copy(title = title)
                    else current.copy(title = title, blocks = imported.blocks, annotations = imported.annotations, defaultTabId = imported.defaultTabId)
                }
            }
            AgentToolName.ADD_TAG_TO_NOTE -> requireNotNull(previous).snapshot.payload.copy(
                directTagIds = listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID,
                    TagId(ExternalToolArguments.string(args, "tagId", true)!!)).distinct(),
            )
            AgentToolName.DELETE_NOTE -> requireNotNull(previous).snapshot.payload
            else -> throw ExternalCallException("tool_unavailable")
        }
        val normalized = NoteNormalizer.normalize(note)
        if (normalized.title.isBlank() || normalized.title.length > 4096 || normalized.blocks.size > 5000 ||
            NoteValidator.validate(normalized).isNotEmpty()) throw ExternalCallException("note_invalid")
        val deleted = request.tool == AgentToolName.DELETE_NOTE
        val prepared = if (deleted) DesktopAgentValidatedNote(normalized, emptyMap())
            else DesktopAgentNoteSchemaService(session).prepare(normalized, previous?.snapshot?.payload)
        return DesktopAgentNoteMutation(previous?.id ?: ObjectId(session.idFactory()), revision, prepared.note, deleted, prepared.schemaRevisions)
    }

    /**
     * 校验请求 revision 与当前权威快照相同。
     * @param args 协议参数。
     * @param previous 当前权威 Note。
     * @return 已验证的乐观锁 revision。
     */
    fun expected(args: JsonObject, previous: DesktopStoredNote): ObjectRevision {
        val revision = parseExternalRevision(ExternalToolArguments.string(args, "expectedRevision", true)!!)
        if (revision != previous.snapshot.header.revision) throw ExternalCallException("revision_conflict")
        return revision
    }

    /**
     * 导出一次已预览版本的 Markdown 和损失说明。
     * @param request 已授权导出请求。
     * @return 带 revision 的 Markdown 结果。
     */
    suspend fun export(request: AgentToolRequest): JsonObject {
        val args = request.arguments as JsonObject
        val stored = DesktopAgentReadService(session, principal).note(ObjectId(ExternalToolArguments.string(args, "noteId", true)!!))
        expected(args, stored)
        val mode = when (ExternalToolArguments.string(args, "mode") ?: "standard") {
            "standard" -> MarkdownExportMode.STANDARD
            "extended" -> MarkdownExportMode.TAGLOOM_EXTENDED
            else -> throw ExternalCallException("invalid_arguments")
        }
        val result = MarkdownCodec.export(stored.snapshot.payload, mode)
        return buildJsonObject {
            put("noteId", stored.id.value); put("revision", stored.snapshot.header.revision.externalText())
            put("markdown", result.markdown)
            put("capabilityLosses", JsonArray(result.capabilityLosses.map { loss -> buildJsonObject {
                put("code", loss.code); put("blockId", loss.blockId?.value?.let(::JsonPrimitive) ?: JsonNull)
            } }))
        }
    }

    /**
     * 导入 Markdown 时分配随机 Block 与表格身份，不派生自正文。
     * @param markdown 文档内容。
     * @param title 笔记标题。
     * @return 规范化 Note 草稿。
     */
    private fun importMarkdown(markdown: String, title: String): Note {
        if (markdown.length > 1_000_000) throw ExternalCallException("invalid_arguments")
        return MarkdownCodec.import(markdown, MarkdownImportOptions(
            defaultTitle = title, firstHeadingAsTitle = false, importedAt = session.timestampProvider(),
            blockIdFactory = { BlockId(session.idFactory()) }, tableIdFactory = session.idFactory,
        )).note
    }

}
