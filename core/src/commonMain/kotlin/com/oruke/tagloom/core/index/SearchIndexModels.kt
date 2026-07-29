package com.oruke.tagloom.core.index

import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.PropertyId
import com.oruke.tagloom.core.model.TagId

/** 定义索引中稳定保存的链接类型。 */
object IndexLinkTypes {
    /** Note Reference Block 产生的结构化引用。 */
    const val NOTE_REFERENCE: String = "note-reference"

    /** RichText 链接 Mark 产生的内部引用。 */
    const val INLINE_LINK: String = "inline-link"

    /** relation 属性产生的结构化引用。 */
    const val PROPERTY_RELATION: String = "property-relation"
}

/**
 * 描述一条从当前笔记指向目标笔记或 Block 的索引链接。
 */
data class IndexedNoteLink(
    /** 发出链接的 Block；属性级链接为空。 */
    val sourceBlockId: BlockId?,
    /** 目标笔记。 */
    val targetNoteId: ObjectId,
    /** 目标 Block；引用整篇笔记时为空。 */
    val targetBlockId: BlockId?,
    /** 稳定链接类型，取值由 [IndexLinkTypes] 约定。 */
    val linkType: String,
)

/**
 * 描述已确认 Note 快照投影出的完整索引文档。
 */
data class NoteIndexDocument(
    /** 权威 Note 对象标识。 */
    val noteId: ObjectId,
    /** 产生文档的对象修订号。 */
    val revision: String,
    /** 笔记标题。 */
    val title: String,
    /** 忽略格式后用于全文检索的正文。 */
    val bodyText: String,
    /** 权威快照更新时间，使用 UTC ISO-8601 文本。 */
    val updatedAt: String,
    /** 用户直接关联的标签。 */
    val directTagIds: List<TagId>,
    /** 属性值的规范 JSON 文本，键为属性 ID。 */
    val propertyValues: Map<PropertyId, String>,
    /** 从 Block、行内链接和 relation 属性提取的出链。 */
    val outboundLinks: List<IndexedNoteLink>,
    /** 快照是否为 tombstone；为真时适配器必须移除旧索引行。 */
    val deleted: Boolean,
)

/** 描述属性精确匹配过滤条件。 */
data class PropertyIndexFilter(
    /** 待匹配属性。 */
    val propertyId: PropertyId,
    /** 与索引规范 JSON 完全相等的值。 */
    val canonicalValue: String,
)

/**
 * 描述全文、标签和属性组合查询。
 */
data class SearchIndexQuery(
    /** 用户全文输入；空白表示不启用全文条件。 */
    val text: String = "",
    /** 必须全部命中的标签集合。 */
    val tagIds: Set<TagId> = emptySet(),
    /** 必须全部命中的属性精确匹配条件。 */
    val propertyFilters: List<PropertyIndexFilter> = emptyList(),
    /** 最大返回数量。 */
    val limit: Int = 50,
    /** 分页偏移量。 */
    val offset: Int = 0,
) {
    init {
        require(limit in 1..500) { "索引查询 limit 必须在 1 到 500 之间" }
        require(offset >= 0) { "索引查询 offset 不能为负数" }
        require(propertyFilters.map(PropertyIndexFilter::propertyId).distinct().size == propertyFilters.size) {
            "同一查询不能重复指定属性过滤条件"
        }
    }
}

/** 描述一条全文或结构化查询命中。 */
data class SearchIndexHit(
    /** 命中的 Note 对象标识。 */
    val noteId: ObjectId,
    /** 命中笔记标题。 */
    val title: String,
    /** 由索引生成且已经截断的上下文片段。 */
    val snippet: String,
    /** FTS 相关度；无全文条件时为空。 */
    val score: Double?,
    /** 权威快照更新时间。 */
    val updatedAt: String,
)

/** 描述一条指向指定 Note 或 Block 的反向链接。 */
data class BacklinkIndexHit(
    /** 发出链接的笔记。 */
    val sourceNoteId: ObjectId,
    /** 发出链接的笔记标题。 */
    val sourceTitle: String,
    /** 发出链接的 Block；属性级链接为空。 */
    val sourceBlockId: BlockId?,
    /** 被引用的目标 Block；整篇 Note 引用为空。 */
    val targetBlockId: BlockId?,
    /** 稳定链接类型。 */
    val linkType: String,
)

/** 描述索引覆盖到的权威状态边界。 */
data class SearchIndexCheckpoint(
    /** 已完成投影的 manifest 修订标识。 */
    val manifestRevision: String,
    /** 该边界下未删除的 Note 数量。 */
    val noteCount: Int,
) {
    init {
        require(manifestRevision.isNotBlank()) { "索引 checkpoint 修订标识不能为空" }
        require(noteCount >= 0) { "索引 checkpoint Note 数量不能为负数" }
    }
}

/** 描述索引全量重建进度。 */
data class SearchIndexRebuildProgress(
    /** 已完成投影的文档数量。 */
    val completed: Int,
    /** 本次待投影的文档总数。 */
    val total: Int,
) {
    init {
        require(total >= 0) { "索引重建总数不能为负数" }
        require(completed in 0..total) { "索引重建完成数必须位于 0 到总数之间" }
    }
}
