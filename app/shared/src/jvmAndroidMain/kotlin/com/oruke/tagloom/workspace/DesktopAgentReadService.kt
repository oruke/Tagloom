package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.agent.*
import com.oruke.tagloom.core.external.*
import com.oruke.tagloom.core.index.*
import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.core.protocol.CanonicalJson
import kotlinx.serialization.json.*

/** 复用权威快照和派生索引执行外部读取，所有返回结果均先经过授权范围过滤。 */
internal class DesktopAgentReadService(
    /** 唯一已解锁会话，调用方持有会话锁。 */
    private val session: DesktopWorkspaceSession,
    /** 当前外部身份与 Note 可读范围。 */
    private val principal: AgentPrincipal,
) {
    /**
     * 读取指定授权 Note，越权目标在协调器与本服务双重拒绝。
     * @param id 目标 Note。
     * @return 活动权威快照，不返回 tombstone。
     */
    suspend fun note(id: ObjectId): DesktopStoredNote {
        if (principal.allowedNoteIds?.contains(id) == false) throw ExternalCallException("authorization_denied")
        return session.loadActiveNotes().singleOrNull { it.id == id } ?: throw ExternalCallException("not_found")
    }

    /**
     * 根据共享协议执行读取或本地建议。
     * @param request 已授权且参数规范的请求。
     * @return 只存在解锁会话内存中的结构化业务结果。
     */
    suspend fun execute(request: AgentToolRequest): JsonElement {
        val args = request.arguments as JsonObject
        val limit = ExternalToolArguments.integer(args, "limit", 20, 1..100)
        val offset = ExternalToolArguments.integer(args, "offset", 0, 0..1_000_000)
        val schema = DesktopAgentSchemaService(session, principal)
        val target = ExternalToolArguments.string(args, "noteId")?.let { note(ObjectId(it)) }
        return when (request.tool) {
            AgentToolName.GET_NOTE -> externalNoteJson(requireNotNull(target))
            AgentToolName.GET_BLOCK -> buildJsonObject {
                val block = requireNotNull(target).snapshot.payload.blocks.singleOrNull {
                    it.id.value == ExternalToolArguments.string(args, "blockId", true)
                } ?: throw ExternalCallException("not_found")
                put("noteId", target.id.value); put("revision", target.snapshot.header.revision.externalText())
                put("block", CanonicalJson.format.encodeToJsonElement(Block.serializer(), block))
            }
            AgentToolName.LIST_NOTES -> externalPage(session.loadActiveNotes()
                .filter { principal.allowedNoteIds?.contains(it.id) != false }
                .sortedBy { it.id.value }.map { externalNoteJson(it, includeContent = false) }, limit, offset)
            AgentToolName.SEARCH_NOTES, AgentToolName.QUERY_NOTES -> search(args, limit, offset)
            AgentToolName.GET_BACKLINKS -> externalPage(session.projection.index.backlinks(
                requireNotNull(target).id, ExternalToolArguments.string(args, "blockId")?.let(::BlockId),
            ).filter { principal.allowedNoteIds?.contains(it.sourceNoteId) != false }.map { hit ->
                buildJsonObject {
                    put("noteId", hit.sourceNoteId.value); put("title", hit.sourceTitle)
                    put("sourceBlockId", hit.sourceBlockId?.value?.let(::JsonPrimitive) ?: JsonNull)
                    put("targetBlockId", hit.targetBlockId?.value?.let(::JsonPrimitive) ?: JsonNull)
                    put("linkType", hit.linkType)
                }
            }, limit, offset)
            AgentToolName.GET_TAGS -> externalPage(schema.visibleSchema().tags.map {
                CanonicalJson.format.encodeToJsonElement(TagDefinition.serializer(), it.payload)
            }, limit, offset)
            AgentToolName.GET_TAG_SCHEMA -> schema.tagSchema(TagId(ExternalToolArguments.string(args, "tagId", true)!!))
            AgentToolName.GET_WORKSPACE_SCHEMA -> schema.schemaJson()
            AgentToolName.GET_SAVED_QUERIES -> {
                if (principal.allowedNoteIds != null) externalPage(emptyList(), limit, offset)
                else externalPage(session.savedQueryLoader.load(session.unlocked).map {
                    buildJsonObject {
                        put("queryId", it.header.objectId.value)
                        put("query", CanonicalJson.format.encodeToJsonElement(SavedQuery.serializer(), it.payload))
                    }
                }, limit, offset)
            }
            AgentToolName.GET_SAVED_QUERY -> {
                if (principal.allowedNoteIds != null) throw ExternalCallException("authorization_denied")
                val saved = savedQuery(ExternalToolArguments.string(args, "queryId", true)!!)
                buildJsonObject { put("queryId", saved.header.objectId.value)
                    put("query", CanonicalJson.format.encodeToJsonElement(SavedQuery.serializer(), saved.payload)) }
            }
            AgentToolName.RUN_SAVED_QUERY -> {
                val saved = savedQuery(ExternalToolArguments.string(args, "queryId", true)!!).payload
                search(buildJsonObject {
                    put("text", saved.text); put("tagIds", JsonArray(saved.tagIds.map { JsonPrimitive(it.value) }))
                    put("properties", JsonObject(saved.propertyFilters.associate { it.propertyId.value to CanonicalJson.format.parseToJsonElement(it.canonicalValue) }))
                }, limit, offset)
            }
            AgentToolName.SUGGEST_TAGS -> {
                val text = ExternalToolArguments.string(args, "text") ?: target?.snapshot?.payload?.title.orEmpty()
                val tags = schema.visibleSchema().tags.map { it.payload }.filter {
                    text.isBlank() || it.name.contains(text, true) || text.contains(it.name, true)
                }
                externalPage(tags.map { CanonicalJson.format.encodeToJsonElement(TagDefinition.serializer(), it) }, limit, offset)
            }
            AgentToolName.SUGGEST_PROPERTIES -> schema.effectiveProperties(requireNotNull(target).snapshot.payload)
            else -> throw ExternalCallException("tool_unavailable")
        }
    }

    /**
     * 查询索引并获取一条额外命中，以证明下一页是否存在。
     * @param args 文本、类型 ID 和属性精确过滤值。
     * @param limit 返回数量。
     * @param offset 授权命中集合内的偏移。
     * @return 授权分页结果，不暴露工作区全部命中数。
     */
    private suspend fun search(args: JsonObject, limit: Int, offset: Int): JsonObject {
        val properties = args["properties"]?.let {
            it as? JsonObject ?: throw ExternalCallException("invalid_arguments")
        }.orEmpty()
        if (properties.size > 100) throw ExternalCallException("invalid_arguments")
        val query = SearchIndexQuery(
            text = ExternalToolArguments.string(args, "query") ?: ExternalToolArguments.string(args, "text").orEmpty(),
            tagIds = ExternalToolArguments.strings(args, "tagIds", 10).map(::TagId).toSet(),
            propertyFilters = properties.map { (id, value) ->
                PropertyIndexFilter(PropertyId(ExternalToolArguments.opaque(id)), CanonicalJson.encodeToString(JsonElement.serializer(), value))
            }, limit = limit + 1, offset = offset, allowedNoteIds = principal.allowedNoteIds,
        )
        val hits = session.projection.index.search(query)
        return buildJsonObject {
            put("items", JsonArray(hits.take(limit).map { hit -> buildJsonObject {
                put("noteId", hit.noteId.value); put("title", hit.title); put("snippet", hit.snippet)
                put("updatedAt", hit.updatedAt); put("score", hit.score?.let(::JsonPrimitive) ?: JsonNull)
            } }))
            put("hasMore", hits.size > limit)
            put("nextOffset", if (hits.size > limit) JsonPrimitive(offset + limit) else JsonNull)
        }
    }

    /**
     * 从权威快照加载一个保存查询。
     * @param id 不透明查询 ID。
     * @return 已确认查询，不接受调用方伪造的定义。
     */
    private suspend fun savedQuery(id: String): ObjectSnapshot<SavedQuery> =
        session.savedQueryLoader.load(session.unlocked).singleOrNull { it.header.objectId.value == id }
            ?: throw ExternalCallException("not_found")
}
