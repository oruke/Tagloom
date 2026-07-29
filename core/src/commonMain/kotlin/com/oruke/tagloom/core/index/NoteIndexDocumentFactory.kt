package com.oruke.tagloom.core.index

import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.InlineMarkTypes
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.NoteReferenceBlockContent
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.ObjectSnapshot
import com.oruke.tagloom.core.model.ObjectType
import com.oruke.tagloom.core.model.PropertyId
import com.oruke.tagloom.core.model.extractPlainText
import com.oruke.tagloom.core.model.richTextOrNull
import com.oruke.tagloom.core.protocol.CanonicalJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 把权威 Note 快照确定性投影为平台无关的索引文档。 */
object NoteIndexDocumentFactory {
    /**
     * 从已确认 Note 快照提取全文、标签、属性和结构化出链。
     *
     * relation 的值支持 Note ID 字符串、字符串数组，以及包含 `noteId`/可选 `blockId` 的对象或对象数组。
     * 未知或无效的内部链接会被忽略，避免索引故障阻断权威快照提交。
     *
     * @param snapshot 待投影的已确认 Note 快照。
     * @param relationPropertyIds 已由 Property Definition 判定为 relation 的属性集合。
     * @return 可供任意平台索引适配器消费的完整文档。
     */
    fun fromSnapshot(
        snapshot: ObjectSnapshot<Note>,
        relationPropertyIds: Set<PropertyId> = emptySet(),
    ): NoteIndexDocument {
        require(snapshot.header.objectType == ObjectType.NOTE) { "索引文档只能从 Note 快照生成" }

        val activeBlocks = snapshot.payload.blocks.filterNot(Block::deleted)
        val bodyText = activeBlocks.mapNotNull(Block::extractPlainText).joinToString(separator = "\n")
        val blockLinks = activeBlocks.flatMap(::linksFromBlock)
        val relationLinks = relationPropertyIds.flatMap { propertyId ->
            val value = snapshot.payload.propertyValues[propertyId]?.value
            linksFromRelationValue(value)
        }
        val propertyValues = snapshot.payload.propertyValues
            .toList()
            .sortedBy { (propertyId, _) -> propertyId.value }
            .associate { (propertyId, value) ->
                propertyId to CanonicalJson.encodeToString(JsonElement.serializer(), value.value)
            }

        return NoteIndexDocument(
            noteId = snapshot.header.objectId,
            revision = "${snapshot.header.revision.actorId.value}:${snapshot.header.revision.sequence}",
            title = snapshot.payload.title,
            bodyText = bodyText,
            updatedAt = snapshot.header.updatedAt,
            directTagIds = snapshot.payload.directTagIds.distinct().sortedBy { it.value },
            propertyValues = propertyValues,
            outboundLinks = (blockLinks + relationLinks).distinct().sortedWith(LINK_COMPARATOR),
            deleted = snapshot.header.deleted,
        )
    }

    /**
     * 提取 Block Content 与 RichText Mark 中的内部链接。
     *
     * @param block 已排除 tombstone 的源 Block。
     * @return 该 Block 发出的全部有效内部链接。
     */
    private fun linksFromBlock(block: Block): List<IndexedNoteLink> {
        val direct = when (val content = block.content) {
            is NoteReferenceBlockContent -> listOf(
                IndexedNoteLink(
                    sourceBlockId = block.id,
                    targetNoteId = content.noteId,
                    targetBlockId = content.blockId,
                    linkType = IndexLinkTypes.NOTE_REFERENCE,
                ),
            )
            else -> emptyList()
        }
        val richText = block.richTextOrNull() ?: return direct
        val inline = richText.runs.flatMap { run ->
            run.marks
                .asSequence()
                .filter { mark -> mark.type == InlineMarkTypes.LINK }
                .mapNotNull { mark -> parseInternalHref(block.id, mark.attributes["href"]) }
                .toList()
        }
        return direct + inline
    }

    /**
     * 解析 `tagloom-note:<noteId>` 或 `tagloom-note:<noteId>#<blockId>` 内部链接。
     *
     * @param sourceBlockId 发出链接的 Block。
     * @param href Link Mark 保存的目标字符串。
     * @return 有效内部链接；外部 URL 或无效标识返回空。
     */
    private fun parseInternalHref(
        sourceBlockId: BlockId,
        href: String?,
    ): IndexedNoteLink? {
        val target = href?.takeIf { it.startsWith(INTERNAL_NOTE_PREFIX) }
            ?.removePrefix(INTERNAL_NOTE_PREFIX)
            ?.takeIf(String::isNotBlank)
            ?: return null
        val noteValue = target.substringBefore('#')
        val blockValue = target.substringAfter('#', missingDelimiterValue = "").ifBlank { null }
        return runCatching {
            IndexedNoteLink(
                sourceBlockId = sourceBlockId,
                targetNoteId = ObjectId(noteValue),
                targetBlockId = blockValue?.let(::BlockId),
                linkType = IndexLinkTypes.INLINE_LINK,
            )
        }.getOrNull()
    }

    /**
     * 从 relation 属性协议值中递归提取目标链接。
     *
     * @param value relation 属性值。
     * @return 可识别的目标链接；无值或未知结构返回空列表。
     */
    private fun linksFromRelationValue(value: JsonElement?): List<IndexedNoteLink> = when (value) {
        null -> emptyList()
        is JsonArray -> value.flatMap(::linksFromRelationValue)
        is JsonPrimitive -> value.contentOrNull
            ?.let(::relationLinkFromIds)
            ?.let(::listOf)
            .orEmpty()
        is JsonObject -> {
            val noteId = (value["noteId"] as? JsonPrimitive)?.contentOrNull
            val blockId = (value["blockId"] as? JsonPrimitive)?.contentOrNull
            relationLinkFromIds(noteId, blockId)?.let(::listOf).orEmpty()
        }
    }

    /**
     * 把 relation 协议中的 Note/Block 标识转换为索引链接。
     *
     * @param noteId 目标 Note 字符串标识。
     * @param blockId 可选目标 Block 字符串标识。
     * @return 标识有效时返回属性关系链接，否则为空。
     */
    private fun relationLinkFromIds(
        noteId: String?,
        blockId: String? = null,
    ): IndexedNoteLink? = runCatching {
        val targetNoteId = noteId?.let(::ObjectId) ?: return null
        IndexedNoteLink(
            sourceBlockId = null,
            targetNoteId = targetNoteId,
            targetBlockId = blockId?.let(::BlockId),
            linkType = IndexLinkTypes.PROPERTY_RELATION,
        )
    }.getOrNull()

    /** 稳定排序链接，确保不同平台产生相同的索引写入顺序。 */
    private val LINK_COMPARATOR: Comparator<IndexedNoteLink> = compareBy(
        { it.sourceBlockId?.value.orEmpty() },
        { it.targetNoteId.value },
        { it.targetBlockId?.value.orEmpty() },
        IndexedNoteLink::linkType,
    )

    /** Tagloom 内部 Note URL 的协议前缀。 */
    private const val INTERNAL_NOTE_PREFIX: String = "tagloom-note:"
}
