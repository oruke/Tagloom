package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.agent.AgentNotePolicyGate
import com.oruke.tagloom.core.agent.AgentPrincipal
import com.oruke.tagloom.core.agent.AgentToolName
import com.oruke.tagloom.core.ai.AgentContextRef
import com.oruke.tagloom.core.ai.AgentHarnessWorkspacePort
import com.oruke.tagloom.core.ai.AgentRawSourceCandidate
import com.oruke.tagloom.core.ai.AgentReadToolExecutionOutcome
import com.oruke.tagloom.core.ai.AgentResolvedContextPayload
import com.oruke.tagloom.core.ai.AgentToolTrace
import com.oruke.tagloom.core.ai.AiContextBudget
import com.oruke.tagloom.core.ai.AiRetrievalScope
import com.oruke.tagloom.core.external.ExternalCallException
import com.oruke.tagloom.core.external.ExternalToolArguments
import com.oruke.tagloom.core.index.PropertyIndexFilter
import com.oruke.tagloom.core.index.SearchIndexQuery
import com.oruke.tagloom.core.model.AiAccessPolicy
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.PropertyId
import com.oruke.tagloom.core.model.SavedQuery
import com.oruke.tagloom.core.model.SystemDatabaseDefinitions
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.core.model.TagId
import com.oruke.tagloom.core.model.extractPlainText
import com.oruke.tagloom.core.protocol.CanonicalJson
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * 将内置 [com.oruke.tagloom.core.ai.AgentHarness] 桥接至当前已解锁 `DesktopWorkspaceSession` 的进程内只读检索端口。
 *
 * @param session 当前已解锁的本地工作区会话。
 */
internal class DesktopAgentHarnessWorkspaceAdapter(
    private val session: DesktopWorkspaceSession,
) : AgentHarnessWorkspacePort {
    private val contextResolver = DesktopAgentHarnessContextResolver(session)

    /**
     * 解析作曲框挂载的 `@` 引用并按单轮上下文预算裁剪。
     */
    override suspend fun resolveExplicitContext(
        refs: List<AgentContextRef>,
        budget: AiContextBudget,
    ): AgentResolvedContextPayload = session.sessionMutex.withLock {
        session.requireOpen()
        contextResolver.resolveLocked(refs, budget)
    }

    /**
     * 在当前检索暴露范围与 `AiAccessPolicy` 分页前门禁下执行单步只读工具调用。
     */
    override suspend fun executeReadTool(
        callId: String,
        tool: AgentToolName,
        argumentsJson: String,
        retrievalScope: AiRetrievalScope,
        activeRefs: List<AgentContextRef>,
        maxResultCharacters: Int,
    ): AgentReadToolExecutionOutcome = session.sessionMutex.withLock {
        session.requireOpen()
        val args = runCatching {
            CanonicalJson.format.parseToJsonElement(argumentsJson.ifBlank { "{}" }).jsonObject
        }.getOrElse {
            return@withLock errorOutcome(callId, tool.wireName(), "invalid_arguments", "工具参数 JSON 格式无效")
        }
        try {
            dispatchToolLocked(callId, tool, args, retrievalScope, activeRefs, maxResultCharacters)
        } catch (err: ExternalCallException) {
            val skipped = if (err.code.contains("ai_access_policy")) 1 else 0
            errorOutcome(callId, tool.wireName(), err.code, "拒绝访问 (${err.code})", skipped)
        }
    }

    /**
     * 在持有会话互斥锁的前提下分发并执行已校验的只读工具。
     */
    private suspend fun dispatchToolLocked(
        callId: String,
        tool: AgentToolName,
        args: JsonObject,
        retrievalScope: AiRetrievalScope,
        activeRefs: List<AgentContextRef>,
        maxResultCharacters: Int,
    ): AgentReadToolExecutionOutcome {
        val policyResolver = session.loadAiAccessPolicyResolver()
        val activeNotes = session.loadActiveNotes()
            .filterNot { SystemDatabaseDefinitions.isProtectedSystemPage(it.id) }
        val notesById = activeNotes.associateBy { it.id }
        val scopeCtx = contextResolver.buildScopedReadContextLocked(retrievalScope, activeRefs, activeNotes, policyResolver)
        val limit = ExternalToolArguments.integer(args, "limit", 20, 1..50)
        val offset = ExternalToolArguments.integer(args, "offset", 0, 0..1_000_000)
        val schema = DesktopAgentSchemaService(session, AgentPrincipal("builtin-agent-harness", emptySet()))

        return when (tool) {
            AgentToolName.GET_NOTE -> {
                val target = contextResolver.resolveAuthorizedDirectNote(args, notesById, policyResolver, retrievalScope, scopeCtx)
                val note = target.snapshot.payload
                val rev = target.snapshot.header.revision.externalText()
                val sources = extractNoteSources(target.id, note.title, rev, note.blocks)
                AgentReadToolExecutionOutcome(
                    resultJson = clipJson(externalNoteJson(target), maxResultCharacters),
                    sources = sources,
                    trace = AgentToolTrace(
                        callId = callId,
                        toolName = tool.wireName(),
                        summary = "精读笔记「${note.title}」",
                        readNoteCount = 1,
                        readBlockCount = sources.count { it.blockId != null },
                    ),
                )
            }
            AgentToolName.GET_BLOCK -> {
                val target = contextResolver.resolveAuthorizedDirectNote(args, notesById, policyResolver, retrievalScope, scopeCtx)
                val blockIdText = ExternalToolArguments.string(args, "blockId", true)!!
                val block = target.snapshot.payload.blocks.singleOrNull { it.id.value == blockIdText && !it.deleted }
                    ?: throw ExternalCallException("not_found")
                val rev = target.snapshot.header.revision.externalText()
                val excerpt = block.extractPlainText().orEmpty().ifBlank { target.snapshot.payload.title }
                val payload = buildJsonObject {
                    put("noteId", target.id.value)
                    put("revision", rev)
                    put("block", CanonicalJson.format.encodeToJsonElement(Block.serializer(), block))
                }
                AgentReadToolExecutionOutcome(
                    resultJson = clipJson(payload, maxResultCharacters),
                    sources = listOf(AgentRawSourceCandidate(target.id, target.snapshot.payload.title, block.id, rev, excerpt)),
                    trace = AgentToolTrace(
                        callId = callId,
                        toolName = tool.wireName(),
                        summary = "读取笔记「${target.snapshot.payload.title}」指定段落",
                        readNoteCount = 1,
                        readBlockCount = 1,
                    ),
                )
            }
            AgentToolName.SEARCH_NOTES, AgentToolName.QUERY_NOTES ->
                executeIndexSearchTool(callId, tool.wireName(), args, limit, offset, scopeCtx, notesById, maxResultCharacters)
            AgentToolName.RUN_SAVED_QUERY -> {
                val queryId = ExternalToolArguments.string(args, "queryId", true)!!
                val saved = session.savedQueryLoader.load(session.unlocked)
                    .singleOrNull { it.header.objectId.value == queryId }
                    ?: throw ExternalCallException("not_found")
                executeIndexSearchTool(
                    callId, tool.wireName(), savedQueryToSearchArgs(saved.payload), limit, offset,
                    scopeCtx, notesById, maxResultCharacters,
                    customLabel = "执行保存查询「${saved.payload.name}」",
                )
            }
            AgentToolName.LIST_NOTES -> {
                var skippedProtected = 0
                val discoverable = activeNotes.filter { stored ->
                    if (scopeCtx.discoverableAllowList != null && stored.id !in scopeCtx.discoverableAllowList) {
                        return@filter false
                    }
                    val allowed = AgentNotePolicyGate.canDiscover(policyResolver.resolveEffectiveNotePolicy(stored.snapshot.payload))
                    if (!allowed) skippedProtected += 1
                    allowed
                }.sortedBy { it.id.value }
                val page = externalPage(discoverable.map { externalNoteJson(it, includeContent = false) }, limit, offset)
                AgentReadToolExecutionOutcome(
                    resultJson = clipJson(page, maxResultCharacters),
                    sources = emptyList(),
                    trace = AgentToolTrace(
                        callId = callId,
                        toolName = tool.wireName(),
                        summary = "列出可读笔记目录 (${discoverable.size.coerceAtMost(limit)} 篇)",
                        readNoteCount = discoverable.size.coerceAtMost(limit),
                        skippedProtectedCount = skippedProtected,
                    ),
                )
            }
            AgentToolName.GET_BACKLINKS -> {
                val target = contextResolver.resolveAuthorizedDirectNote(args, notesById, policyResolver, retrievalScope, scopeCtx)
                val blockFilter = ExternalToolArguments.string(args, "blockId")?.let(::BlockId)
                val allBacklinks = session.projection.index.backlinks(target.id, blockFilter, setOf(AiAccessPolicy.ALLOW))
                    .filter { hit ->
                        !SystemDatabaseDefinitions.isProtectedSystemPage(hit.sourceNoteId) &&
                            (scopeCtx.discoverableAllowList == null || hit.sourceNoteId in scopeCtx.discoverableAllowList)
                    }
                val unfilteredCount = session.projection.index.backlinks(target.id, blockFilter, AiAccessPolicy.entries.toSet())
                    .count { hit ->
                        !SystemDatabaseDefinitions.isProtectedSystemPage(hit.sourceNoteId) &&
                            (scopeCtx.discoverableAllowList == null || hit.sourceNoteId in scopeCtx.discoverableAllowList)
                    }
                val page = externalPage(
                    allBacklinks.map { hit ->
                        buildJsonObject {
                            put("noteId", hit.sourceNoteId.value)
                            put("title", hit.sourceTitle)
                            put("sourceBlockId", hit.sourceBlockId?.value?.let(::JsonPrimitive) ?: JsonNull)
                            put("targetBlockId", hit.targetBlockId?.value?.let(::JsonPrimitive) ?: JsonNull)
                            put("linkType", hit.linkType)
                        }
                    },
                    limit,
                    offset,
                )
                AgentReadToolExecutionOutcome(
                    resultJson = clipJson(page, maxResultCharacters),
                    sources = emptyList(),
                    trace = AgentToolTrace(
                        callId = callId,
                        toolName = tool.wireName(),
                        summary = "查询「${target.snapshot.payload.title}」反向链接 (${allBacklinks.size} 条)",
                        skippedProtectedCount = (unfilteredCount - allBacklinks.size).coerceAtLeast(0),
                    ),
                )
            }
            AgentToolName.GET_TAGS -> {
                val page = externalPage(
                    schema.visibleSchema().tags.map { CanonicalJson.format.encodeToJsonElement(TagDefinition.serializer(), it.payload) },
                    limit,
                    offset,
                )
                AgentReadToolExecutionOutcome(clipJson(page, maxResultCharacters), emptyList(), AgentToolTrace(callId, tool.wireName(), "读取笔记类型目录"))
            }
            AgentToolName.GET_TAG_SCHEMA -> {
                val tagId = TagId(ExternalToolArguments.string(args, "tagId", true)!!)
                AgentReadToolExecutionOutcome(clipJson(schema.tagSchema(tagId), maxResultCharacters), emptyList(), AgentToolTrace(callId, tool.wireName(), "读取笔记类型结构定义"))
            }
            AgentToolName.GET_WORKSPACE_SCHEMA ->
                AgentReadToolExecutionOutcome(clipJson(schema.schemaJson(), maxResultCharacters), emptyList(), AgentToolTrace(callId, tool.wireName(), "读取工作区类型与属性目录"))
            AgentToolName.GET_SAVED_QUERIES -> {
                val queries = session.savedQueryLoader.load(session.unlocked).map {
                    buildJsonObject {
                        put("queryId", it.header.objectId.value)
                        put("query", CanonicalJson.format.encodeToJsonElement(SavedQuery.serializer(), it.payload))
                    }
                }
                AgentReadToolExecutionOutcome(clipJson(externalPage(queries, limit, offset), maxResultCharacters), emptyList(), AgentToolTrace(callId, tool.wireName(), "读取已保存查询列表"))
            }
            AgentToolName.GET_SAVED_QUERY -> {
                val queryId = ExternalToolArguments.string(args, "queryId", true)!!
                val saved = session.savedQueryLoader.load(session.unlocked)
                    .singleOrNull { it.header.objectId.value == queryId }
                    ?: throw ExternalCallException("not_found")
                val json = buildJsonObject {
                    put("queryId", saved.header.objectId.value)
                    put("query", CanonicalJson.format.encodeToJsonElement(SavedQuery.serializer(), saved.payload))
                }
                AgentReadToolExecutionOutcome(clipJson(json, maxResultCharacters), emptyList(), AgentToolTrace(callId, tool.wireName(), "读取保存查询「${saved.payload.name}」定义"))
            }
            AgentToolName.SUGGEST_TAGS -> {
                val target = ExternalToolArguments.string(args, "noteId")?.let {
                    contextResolver.resolveAuthorizedDirectNote(args, notesById, policyResolver, retrievalScope, scopeCtx)
                }
                val text = ExternalToolArguments.string(args, "text") ?: target?.snapshot?.payload?.title.orEmpty()
                val matched = schema.visibleSchema().tags.map { it.payload }.filter {
                    text.isBlank() || it.name.contains(text, true) || text.contains(it.name, true)
                }
                val page = externalPage(matched.map { CanonicalJson.format.encodeToJsonElement(TagDefinition.serializer(), it) }, limit, offset)
                AgentReadToolExecutionOutcome(clipJson(page, maxResultCharacters), emptyList(), AgentToolTrace(callId, tool.wireName(), "匹配候选笔记类型"))
            }
            AgentToolName.SUGGEST_PROPERTIES -> {
                val target = contextResolver.resolveAuthorizedDirectNote(args, notesById, policyResolver, retrievalScope, scopeCtx)
                AgentReadToolExecutionOutcome(
                    clipJson(schema.effectiveProperties(target.snapshot.payload), maxResultCharacters),
                    emptyList(),
                    AgentToolTrace(callId, tool.wireName(), "分析笔记「${target.snapshot.payload.title}」有效属性"),
                )
            }
            else -> throw ExternalCallException("tool_unavailable")
        }
    }

    /**
     * 执行派生索引搜索并统计因 `AiAccessPolicy` 在分页前自动跳过的受保护笔记数量。
     */
    private suspend fun executeIndexSearchTool(
        callId: String,
        toolName: String,
        args: JsonObject,
        limit: Int,
        offset: Int,
        scopeCtx: ScopedReadContext,
        notesById: Map<ObjectId, DesktopStoredNote>,
        maxResultCharacters: Int,
        customLabel: String? = null,
    ): AgentReadToolExecutionOutcome {
        if (scopeCtx.discoverableAllowList != null && scopeCtx.discoverableAllowList.isEmpty()) {
            val emptyPage = buildJsonObject {
                put("items", JsonArray(emptyList()))
                put("hasMore", false)
                put("nextOffset", JsonNull)
            }
            return AgentReadToolExecutionOutcome(
                clipJson(emptyPage, maxResultCharacters),
                emptyList(),
                AgentToolTrace(callId, toolName, customLabel ?: "在限定范围内检索笔记 (0 篇)"),
            )
        }
        val properties = args["properties"]?.let {
            it as? JsonObject ?: throw ExternalCallException("invalid_arguments")
        }.orEmpty()
        val queryText = ExternalToolArguments.string(args, "query")
            ?: ExternalToolArguments.string(args, "text").orEmpty()
        val query = SearchIndexQuery(
            text = queryText,
            tagIds = ExternalToolArguments.strings(args, "tagIds", 10).map(::TagId).toSet(),
            propertyFilters = properties.map { (id, value) ->
                PropertyIndexFilter(
                    PropertyId(ExternalToolArguments.opaque(id)),
                    CanonicalJson.encodeToString(JsonElement.serializer(), value),
                )
            },
            limit = limit + 1,
            offset = offset,
            allowedNoteIds = scopeCtx.discoverableAllowList,
            allowedAiPolicies = setOf(AiAccessPolicy.ALLOW),
        )
        val rawHits = session.projection.index.search(query)
            .filterNot { SystemDatabaseDefinitions.isProtectedSystemPage(it.noteId) }
        val skipped = session.projection.index.countExcludedByPolicy(query)
        val pageHits = rawHits.take(limit)
        val sources = pageHits.mapNotNull { hit ->
            val stored = notesById[hit.noteId] ?: return@mapNotNull null
            AgentRawSourceCandidate(hit.noteId, hit.title, null, stored.snapshot.header.revision.externalText(), hit.snippet.ifBlank { hit.title })
        }
        val json = buildJsonObject {
            put("items", JsonArray(pageHits.map { hit -> buildJsonObject {
                put("noteId", hit.noteId.value); put("title", hit.title); put("snippet", hit.snippet)
                put("updatedAt", hit.updatedAt); put("score", hit.score?.let(::JsonPrimitive) ?: JsonNull)
            } }))
            put("hasMore", rawHits.size > limit)
            put("nextOffset", if (rawHits.size > limit) JsonPrimitive(offset + limit) else JsonNull)
        }
        val label = customLabel ?: if (queryText.isNotBlank()) "检索关键词「$queryText」(${pageHits.size} 篇)"
        else "按条件筛选笔记 (${pageHits.size} 篇)"
        return AgentReadToolExecutionOutcome(
            clipJson(json, maxResultCharacters),
            sources,
            AgentToolTrace(callId, toolName, label, readNoteCount = pageHits.size, skippedProtectedCount = skipped),
        )
    }

    /** 从整篇笔记提取最多前 24 个非空段落作为来源候选。 */
    private fun extractNoteSources(noteId: ObjectId, title: String, revision: String, blocks: List<Block>): List<AgentRawSourceCandidate> {
        val blockSources = blocks.asSequence()
            .filter { !it.deleted }
            .mapNotNull { block ->
                val text = block.extractPlainText().orEmpty().trim()
                if (text.isEmpty()) null else AgentRawSourceCandidate(noteId, title, block.id, revision, text)
            }
            .take(24)
            .toList()
        return blockSources.ifEmpty { listOf(AgentRawSourceCandidate(noteId, title, null, revision, title)) }
    }

    /** 将 `SavedQuery` 转为统一索引检索参数对象。 */
    private fun savedQueryToSearchArgs(saved: SavedQuery): JsonObject = buildJsonObject {
        put("text", saved.text)
        put("tagIds", JsonArray(saved.tagIds.map { JsonPrimitive(it.value) }))
        put("properties", JsonObject(saved.propertyFilters.associate {
            it.propertyId.value to CanonicalJson.format.parseToJsonElement(it.canonicalValue)
        }))
    }

    /** 对超长 JSON 响应执行安全截断保护。 */
    private fun clipJson(element: JsonElement, maxCharacters: Int): String {
        val raw = CanonicalJson.encodeToString(JsonElement.serializer(), element)
        if (raw.length <= maxCharacters) return raw
        return CanonicalJson.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("truncated", true)
                put("preview", raw.take((maxCharacters - 64).coerceAtLeast(128)))
            },
        )
    }

    /** 构造结构化工具失败结果。 */
    private fun errorOutcome(callId: String, toolName: String, errorCode: String, summary: String, skippedProtectedCount: Int = 0) =
        AgentReadToolExecutionOutcome(
            resultJson = """{"error":"$errorCode"}""",
            sources = emptyList(),
            trace = AgentToolTrace(callId, toolName, summary, skippedProtectedCount = skippedProtectedCount, succeeded = false),
        )

    /** 将工具枚举映射为稳定 snake_case 名称。 */
    private fun AgentToolName.wireName(): String = name.lowercase()
}
