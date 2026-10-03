package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.agent.AgentPrincipal
import com.oruke.tagloom.core.domain.property.EffectivePropertyResolver
import com.oruke.tagloom.core.domain.tag.*
import com.oruke.tagloom.core.external.ExternalCallException
import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import kotlinx.serialization.json.*

/** 在会话锁中加载授权范围内的类型与属性，统一提供有效继承 schema 和本地建议。 */
internal class DesktopAgentSchemaService(
    /** 已认证客户端会话；调用方必须持有其互斥锁。 */
    private val session: DesktopWorkspaceSession,
    /** 可信认证层提供的 Note 范围。 */
    private val principal: AgentPrincipal,
) {
    /**
     * 加载经过 Note allow-list 限制的 schema。
     * @return 授权笔记直接类型、祖先类型和被引用属性。
     */
    suspend fun visibleSchema(): DesktopWorkspaceSchemaLoadResult {
        val all = session.schemaStore.load(session.unlocked)
        val allowed = principal.allowedNoteIds ?: return all
        val notes = session.loadActiveNotes().filter { it.id in allowed }.map { it.snapshot.payload }
        val tags = all.tags.associate { it.payload.id to it.payload }
        val tagIds = notes.flatMap { note ->
            (TagResolver(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID).resolve(note.directTagIds, tags) as? TagResolution.Success)?.tags?.map { it.tagId }.orEmpty()
        }.toSet()
        val visibleTags = all.tags.filter { it.payload.id in tagIds }
        val propertyIds = (visibleTags.flatMap { it.payload.propertyBindings.map { binding -> binding.propertyId } } +
            notes.flatMap { it.propertyValues.keys + it.manualPropertyBindings.map { binding -> binding.propertyId } }).toSet()
        return all.copy(tags = visibleTags, properties = all.properties.filter { it.payload.id in propertyIds })
    }

    /**
     * 读取可见类型目录与属性定义。
     * @return 不包含本机配置的工作区 schema。
     */
    suspend fun schemaJson(): JsonObject {
        val schema = visibleSchema()
        return buildJsonObject {
            put("tags", JsonArray(schema.tags.map { CanonicalJson.format.encodeToJsonElement(TagDefinition.serializer(), it.payload) }))
            put("properties", JsonArray(schema.properties.map { CanonicalJson.format.encodeToJsonElement(PropertyDefinition.serializer(), it.payload) }))
        }
    }

    /**
     * 解析笔记的有效继承属性，不静默覆盖冲突。
     * @param note 授权 Note 或类型预览用的空 Note。
     * @return 有效属性、默认值、选项和冲突。
     */
    suspend fun effectiveProperties(note: Note): JsonElement {
        val schema = visibleSchema()
        val tags = schema.tags.associate { it.payload.id to it.payload }
        val resolution = TagResolver(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID).resolve(note.directTagIds, tags) as? TagResolution.Success
            ?: throw ExternalCallException("schema_invalid")
        val effective = EffectivePropertyResolver().resolve(note, resolution, tags, schema.properties.associate { it.payload.id to it.payload })
        return CanonicalJson.format.encodeToJsonElement(com.oruke.tagloom.core.domain.property.EffectivePropertyResult.serializer(), effective)
    }

    /**
     * 读取一个授权可见类型的完整继承 schema。
     * @param tagId 目标类型 ID。
     * @return 类型定义和有效属性。
     */
    suspend fun tagSchema(tagId: TagId): JsonObject {
        val tag = visibleSchema().tags.singleOrNull { it.payload.id == tagId }?.payload
            ?: throw ExternalCallException("not_found")
        return buildJsonObject {
            put("tag", CanonicalJson.format.encodeToJsonElement(TagDefinition.serializer(), tag))
            put("effectiveProperties", effectiveProperties(Note("", listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID, tagId).distinct())))
        }
    }
}
