package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.markdown.MarkdownCodec
import com.oruke.tagloom.core.markdown.MarkdownExportMode
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.sync.WorkspaceReplicaConflict
import com.oruke.tagloom.core.sync.WorkspaceReplicaFieldConflict
import com.oruke.tagloom.core.sync.WorkspaceReplicaConflictProgress
import com.oruke.tagloom.platform.WorkspaceSyncConflictField
import kotlinx.serialization.json.*

/**
 * 将加密历史中的稳定路径映射为界面字段类别及可读值，不把协议路径当作产品文案。
 * @param conflict 当前认证的完整双版本及处理进度。
 * @param propertyNames 当前工作区属性 ID 到显示名称的映射。
 * @return 可以独立审阅和确认的字段列表。
 */
internal fun replicaFieldViews(conflict: WorkspaceReplicaConflict, propertyNames: Map<String, String>): List<WorkspaceSyncConflictField> {
    val resolved = WorkspaceReplicaConflictProgress.resolvedFieldIds(conflict)
    return conflict.fields.map { field ->
        val root = field.path.firstOrNull()?.field
        val last = field.path.lastOrNull()?.field
        val label = when (root) {
            "title" -> "TITLE"
            "propertyValues" -> "PROPERTY"
            "manualPropertyBindings", "propertyBindings" -> when (last) {
                "required" -> "REQUIRED"
                "readonly" -> "READONLY"
                "inheritable" -> "INHERITABLE"
                "defaultValue" -> "DEFAULT_VALUE"
                "defaultPolicy" -> "DEFAULT_POLICY"
                "order" -> "ORDER"
                "styleOverride" -> "PROPERTY_STYLE"
                else -> "PROPERTY_BINDING"
            }
            "propertyStyleOverrides" -> "PROPERTY_STYLE"
            "blocks", "templateBlocks" -> "BLOCK"
            "annotations" -> "ANNOTATION"
            "defaultTabId" -> "DEFAULT_TAB"
            "parentTagIds" -> "PARENT_TAGS"
            "directTagIds", "tagIds" -> "TAGS"
            "options" -> when (last) {
                "label" -> "OPTION_LABEL"
                "color" -> "OPTION_COLOR"
                "parentId" -> "OPTION_PARENT"
                else -> "OPTIONS"
            }
            "query", "propertyFilters", "presentation" -> "QUERY"
            else -> when (last ?: root) {
                "name" -> "NAME"
                "icon", "iconKey" -> "ICON"
                "description" -> "DESCRIPTION"
                "type" -> "TYPE"
                "defaultValue" -> "DEFAULT_VALUE"
                else -> "OTHER"
            }
        }
        val propertyId = if (root in setOf("propertyValues", "propertyStyleOverrides")) field.path.getOrNull(1)?.field
            else field.path.firstOrNull { it.identityField == "propertyId" }?.identityValue
        val detail = propertyId?.let(propertyNames::get)
            ?: optionLabel(conflict, field)
        val localBlock = replicaFieldBlock(field.localValue, root)
        val remoteBlock = replicaFieldBlock(field.remoteValue, root)
        WorkspaceSyncConflictField(field.fieldId, label, detail,
            replicaFieldContent(field.localValue, root, last), replicaFieldContent(field.remoteValue, root, last),
            field.localValue == null, field.remoteValue == null, field.fieldId in resolved,
            localDeleted = localBlock?.deleted == true, remoteDeleted = remoteBlock?.deleted == true,
            localBlock = replicaBlockFieldView(localBlock, remoteBlock, conflict.localSnapshot.payload, root),
            remoteBlock = replicaBlockFieldView(remoteBlock, localBlock, conflict.remoteSnapshot.payload, root))
    }
}

/**
 * 从保留版本中寻找选项的显示名，身份和数组顺序不作为用户文案。
 * @param conflict 当前双版本历史。
 * @param field 当前冲突字段。
 * @return 有选项身份时的可读名称，否则为空。
 */
private fun optionLabel(conflict: WorkspaceReplicaConflict, field: WorkspaceReplicaFieldConflict): String? {
    if (field.path.firstOrNull()?.field != "options") return null
    val id = field.path.firstOrNull { it.identityField == "id" }?.identityValue ?: return null
    return listOf(conflict.localSnapshot.payload, conflict.remoteSnapshot.payload).firstNotNullOfOrNull { payload ->
        val option = ((payload as? JsonObject)?.get("options") as? JsonArray)?.filterIsInstance<JsonObject>()
            ?.singleOrNull { (it["id"] as? JsonPrimitive)?.contentOrNull == id }
        (option?.get("label") as? JsonPrimitive)?.contentOrNull
            ?: (option?.get("name") as? JsonPrimitive)?.contentOrNull
    }
}

/**
 * 使用原有 Markdown 导出展示完整 Block，其余字段保留实际值而不暴露 JSON 结构；敏感密钥字段严格遮蔽。
 * @param value 字段值；为空表示不存在。
 * @param root 业务根字段，用于区分 Block 与普通结构。
 * @param leaf 冲突路径叶字段名，用于识别敏感凭据字段。
 * @return 可供用户选择和复制的脱敏文本。
 */
private fun replicaFieldContent(value: JsonElement?, root: String?, leaf: String? = null): String {
    if (value == null || value == JsonNull) return ""
    if (leaf == "syncedApiKey") {
        val content = (value as? JsonPrimitive)?.contentOrNull
        return if (content.isNullOrBlank()) "" else "••••••••"
    }
    val block = replicaFieldBlock(value, root)
    if (block != null) {
        // tombstone 的原正文仍属于可恢复历史；删除语义由独立标志明确展示。
        return MarkdownCodec.export(Note("", emptyList(), blocks = listOf(block.copy(parentId = null, deleted = false))),
            MarkdownExportMode.TAGLOOM_EXTENDED).markdown
    }
    return replicaReadableValue(redactSensitiveReplicaPayload(value))
}

/**
 * 将嵌套值转换为正文式文本，隐藏序列化括号及内部键，不丢失实际选值。
 * @param value 已认证且仅在解锁会话内使用的值。
 * @return 不含 JSON 协议语法的内容。
 */
internal fun replicaReadableValue(value: JsonElement): String = when (value) {
    JsonNull -> ""
    is JsonPrimitive -> value.content
    is JsonArray -> value.joinToString("\n") { replicaReadableValue(it) }
    is JsonObject -> when {
        "runs" in value -> (value["runs"] as? JsonArray).orEmpty().joinToString("") {
            ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.content.orEmpty()
        }
        "richText" in value -> replicaReadableValue(requireNotNull(value["richText"]))
        "value" in value -> replicaReadableValue(requireNotNull(value["value"]))
        else -> value.filterKeys { it !in setOf("id", "propertyId", "schemaVersion") }.values
            .map(::replicaReadableValue).filter(String::isNotBlank).joinToString("\n")
    }
}
