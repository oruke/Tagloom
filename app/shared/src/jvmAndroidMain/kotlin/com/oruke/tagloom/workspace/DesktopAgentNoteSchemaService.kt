package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.domain.property.*
import com.oruke.tagloom.core.domain.tag.*
import com.oruke.tagloom.core.external.ExternalCallException
import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.core.workspace.WorkspaceDefaults

/** 保存已校验笔记与预览时 schema 版本，阻止确认期间类型定义变化后使用旧计划写入。 */
internal data class DesktopAgentValidatedNote(
    /** 类型身份和 snapshot 默认值已规范化的完整笔记。 */
    val note: Note,
    /** 预览所依赖的已确认类型及属性 revision。 */
    val schemaRevisions: Map<ObjectId, ObjectRevision>,
)

/** 校验外部笔记的类型、值和只读约束；与 UI 相同，必填但未填写的属性允许保留为草稿。 */
internal class DesktopAgentNoteSchemaService(
    /** 当前唯一已解锁会话，调用方必须持有会话互斥锁。 */
    private val session: DesktopWorkspaceSession,
) {
    /**
     * 校验变化值并物化 snapshot 默认值，保留既有未知值和失去绑定的可恢复值。
     * @param note 规范化的待预览笔记。
     * @param previous 更新前权威 payload；新建时为空。
     * @return 与当前 schema 一致的冻结笔记及版本。
     */
    suspend fun prepare(note: Note, previous: Note?): DesktopAgentValidatedNote {
        val schema = session.schemaStore.load(session.unlocked)
        val tags = schema.tags.associate { it.payload.id to it.payload }
        val properties = schema.properties.associate { it.payload.id to it.payload }
        val direct = (listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID) + note.directTagIds).distinct()
        if (direct.count { it != WorkspaceDefaults.DEFAULT_NOTE_TAG_ID } > 1) throw ExternalCallException("note_type_invalid")
        val resolution = TagResolver(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID).resolve(direct, tags) as? TagResolution.Success
            ?: throw ExternalCallException("schema_invalid")
        val normalized = note.copy(directTagIds = direct, propertyValues = note.propertyValues.filterValues { it.value != kotlinx.serialization.json.JsonNull })
        val resolver = EffectivePropertyResolver()
        val effective = resolver.resolve(normalized, resolution, tags, properties)
        if (effective.conflicts.isNotEmpty() || effective.missingPropertyIds.isNotEmpty()) throw ExternalCallException("schema_invalid")
        val inherited = resolver.resolve(normalized.copy(manualPropertyBindings = emptyList()), resolution, tags, properties)
            .properties.associateBy { it.propertyId }
        val previousResolution = previous?.let {
            TagResolver(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID).resolve(it.directTagIds, tags) as? TagResolution.Success
        }
        val before = if (previous != null && previousResolution != null) {
            resolver.resolve(previous, previousResolution, tags, properties).properties.associateBy { it.propertyId }
        } else emptyMap()

        // 阶段 1：完整 Note 输入不能通过手动绑定或伪造 source 绕过类型的只读/计算约束。
        for (propertyId in normalized.propertyValues.keys + previous?.propertyValues.orEmpty().keys) {
            val old = previous?.propertyValues?.get(propertyId)
            val replacement = normalized.propertyValues[propertyId]
            val propDef = properties[propertyId]
            val readonly = inherited[propertyId]?.binding?.readonly == true ||
                before[propertyId]?.binding?.readonly == true ||
                propDef?.type == PropertyTypes.FORMULA
            if (readonly && old != replacement) throw ExternalCallException("property_readonly")
            if (replacement != null && old != replacement && !PropertyValueValidator.accepts(replacement.value,
                    propDef ?: throw ExternalCallException("schema_invalid"))) {
                throw ExternalCallException("property_value_invalid")
            }
        }
        if (previous?.manualPropertyBindings.orEmpty().any { binding ->
                binding.readonly && normalized.manualPropertyBindings.none { it.propertyId == binding.propertyId && it.readonly }
            }) throw ExternalCallException("property_readonly")
        val values = normalized.propertyValues.mapValues { (id, value) ->
            if (previous?.propertyValues?.get(id) == value) value
            else value.copy(source = PropertyValueSource.USER, sourceTagId = null, overridden = true, updatedAt = session.timestampProvider())
        }

        // 阶段 2：只填补未显式覆盖的 snapshot 默认值，dynamic 和 none 继续由统一解析器解释。
        val snapshots = effective.properties.filter { it.requiresSnapshot && it.value != null }.associate { property ->
            property.propertyId to NotePropertyValue(requireNotNull(property.value), PropertyValueSource.TAG_SNAPSHOT,
                sourceTagId = property.sourceTagId, updatedAt = session.timestampProvider())
        }
        return DesktopAgentValidatedNote(normalized.copy(propertyValues = snapshots + values), schema.externalRevisions())
    }

    /**
     * 确认前复核预览所依赖的 schema，变化时要求重新生成预览。
     * @param expected 预览时冻结的类型和属性 revision。
     */
    suspend fun requireUnchanged(expected: Map<ObjectId, ObjectRevision>) {
        if (session.schemaStore.load(session.unlocked).externalRevisions() != expected) throw ExternalCallException("schema_changed")
    }
}

/** @return 已加载类型和属性的稳定 revision 集合，不含名称、默认值或正文。 */
private fun DesktopWorkspaceSchemaLoadResult.externalRevisions(): Map<ObjectId, ObjectRevision> =
    tags.associate { it.header.objectId to it.header.revision } + properties.associate { it.header.objectId to it.header.revision }
