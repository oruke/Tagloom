package com.oruke.tagloom.core.model

import kotlinx.serialization.Serializable

/**
 * 表示笔记对象快照中的业务 payload。
 */
@Serializable
data class Note(
    /** 笔记标题。 */
    val title: String,
    /** 用户直接关联的标签，不包含继承得到的有效标签。 */
    val directTagIds: List<TagId>,
    /** 笔记实际保存或明确覆盖的属性值。 */
    val propertyValues: Map<PropertyId, NotePropertyValue> = emptyMap(),
    /** 仅对当前笔记生效的手动属性绑定。 */
    val manualPropertyBindings: List<ManualPropertyBinding> = emptyList(),
    /** 当前笔记对属性展示样式的最高优先级覆盖。 */
    val propertyStyleOverrides: Map<PropertyId, PropertyStyle> = emptyMap(),
    /** 按树结构和 orderKey 组织的稳定 Block。 */
    val blocks: List<Block> = emptyList(),
    /** 用户显式设定的默认页签；当前活动页签不进入权威快照。 */
    val defaultTabId: BlockId? = null,
    /** 由 InlineRun 引用的独立加注。 */
    val annotations: Map<AnnotationId, Annotation> = emptyMap(),
    /** 网页自动字段的加密来源记录；旧笔记没有来源，视为手动内容。 */
    val linkMetadata: LinkMetadataProvenance? = null,
) {
    init {
        require(directTagIds.distinct().size == directTagIds.size) { "笔记直接标签不能重复" }
        require(manualPropertyBindings.map(ManualPropertyBinding::propertyId).distinct().size == manualPropertyBindings.size) {
            "笔记手动属性绑定不能重复"
        }
        require(blocks.map(Block::id).distinct().size == blocks.size) { "笔记 Block ID 不能重复" }
        require(annotations.keys.all { id -> annotations[id]?.id == id }) { "加注 Map 键必须与加注 ID 一致" }
    }
}
