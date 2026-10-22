package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.model.PropertyTypes
import com.oruke.tagloom.core.workspace.LinkNoteSchemas

/**
 * 首次使用时补齐稳定预置身份，保留用户已有名称与目录，不碰同名自定义类型。
 * @param typeName 首次创建的本地化类型名。
 * @param groupName 首次创建的本地化分组名。
 */
internal suspend fun DesktopWorkspaceSession.ensureLinkNoteSchema(typeName: String, groupName: String) {
    val catalog = schemaStore.load(unlocked)
    require(catalog.issues.isEmpty()) { "schema-integrity-failure" }
    LinkNoteSchemas.properties(groupName).forEach { definition ->
        val current = catalog.properties.singleOrNull { it.payload.id == definition.id }?.payload
        if (current == null) {
            val saved = schemaStore.saveProperty(unlocked, definition, nextSchemaWriteContext())
            refreshManifest(saved.encryptedManifest)
        } else require(current.type == definition.type && (definition.id != LinkNoteSchemas.GROUP_ID || current.hierarchical)) { "link-schema-incompatible" }
    }
    if (catalog.tags.none { it.payload.id == LinkNoteSchemas.TYPE_ID }) {
        val saved = schemaStore.saveTag(unlocked, LinkNoteSchemas.tag(typeName), nextSchemaWriteContext())
        refreshManifest(saved.encryptedManifest)
    }
}
