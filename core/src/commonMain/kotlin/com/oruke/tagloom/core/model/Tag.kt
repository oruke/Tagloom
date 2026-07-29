package com.oruke.tagloom.core.model

import kotlinx.serialization.Serializable

/**
 * 表示可继承属性和模板的超级标签定义。
 */
@Serializable
data class TagDefinition(
    /** 标签稳定业务标识。 */
    val id: TagId,
    /** 面向用户的标签名称。 */
    val name: String,
    /** 显式父标签；空列表表示隐式继承根 Note 标签。 */
    val parentTagIds: List<TagId> = emptyList(),
    /** 标签直接声明的属性绑定。 */
    val propertyBindings: List<TagPropertyBinding> = emptyList(),
    /** 新建同类笔记时使用的模板 Block。 */
    val templateBlocks: List<Block> = emptyList(),
) {
    init {
        require(name.isNotBlank()) { "标签名称不能为空" }
        require(parentTagIds.distinct().size == parentTagIds.size) { "父标签不能重复" }
        require(propertyBindings.map(TagPropertyBinding::propertyId).distinct().size == propertyBindings.size) {
            "同一标签不能重复绑定相同属性"
        }
    }
}
