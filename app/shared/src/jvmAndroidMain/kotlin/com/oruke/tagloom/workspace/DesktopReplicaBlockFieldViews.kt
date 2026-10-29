package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.extractPlainText
import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.platform.WorkspaceSyncConflictBlock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 读取一个完整 Block 字段，普通叶值不尝试解释为结构化正文。
 * @param value 保留版本中的原始字段值。
 * @param root 冲突路径的业务根字段。
 * @return 完整 Block；非 Block 字段或不存在时为空。
 */
internal fun replicaFieldBlock(value: JsonElement?, root: String?): Block? =
    if (root in setOf("blocks", "templateBlocks") && value is JsonObject && "id" in value && "content" in value)
        CanonicalJson.format.decodeFromJsonElement(Block.serializer(), value) else null

/**
 * 从保留的完整版本计算 Block 的可读位置和格式，不把 orderKey 或随机 ID 显示给用户。
 * @param block 当前字段的完整 Block；不存在时返回空。
 * @param peer 另一份历史中的 Block，用于判断格式差异。
 * @param snapshotPayload 当前版本的完整对象 payload。
 * @param root 正文或模板集合字段名。
 * @return 提供给 UI 本地化的结构摘要。
 */
internal fun replicaBlockFieldView(block: Block?, peer: Block?, snapshotPayload: JsonElement, root: String?): WorkspaceSyncConflictBlock? {
    if (block == null) return null
    val blocks = (root?.let { (snapshotPayload as? JsonObject)?.get(it) } as? JsonArray).orEmpty().map {
        CanonicalJson.format.decodeFromJsonElement(Block.serializer(), it)
    }
    val siblings = blocks.filter { it.parentId == block.parentId && (!it.deleted || it.id == block.id) }
        .sortedWith(compareBy(Block::orderKey, { it.id.value }))
    val parent = blocks.firstOrNull { it.id == block.parentId }
    val knownAttributes = setOf("codeWrap", "numbered-list-start")
    return WorkspaceSyncConflictBlock(
        type = block.type,
        position = (siblings.indexOfFirst { it.id == block.id } + 1).coerceAtLeast(1),
        parentPosition = parent?.let { blocks.indexOf(it) + 1 },
        parentTitle = parent?.extractPlainText(includeAttachmentFileName = false)?.takeIf(String::isNotBlank),
        formattingDiffers = peer != null && block.attributes != peer.attributes,
        codeWrap = if (block.type == BlockTypes.CODE) (block.attributes["codeWrap"] as? JsonPrimitive)?.booleanOrNull ?: true else null,
        numberedListStart = if (block.type == BlockTypes.NUMBERED_LIST)
            (block.attributes["numbered-list-start"] as? JsonPrimitive)?.intOrNull ?: 1 else null,
        otherFormattingValues = block.attributes.entries.filterNot { it.key in knownAttributes }.sortedBy { it.key }
            .map { replicaReadableValue(it.value) }.filter(String::isNotBlank),
    )
}
