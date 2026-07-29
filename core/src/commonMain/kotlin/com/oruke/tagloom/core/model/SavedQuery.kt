package com.oruke.tagloom.core.model

import kotlinx.serialization.Serializable

/**
 * 描述保存查询中的一个属性精确匹配条件。
 */
@Serializable
data class SavedQueryPropertyFilter(
    /** 待匹配属性 ID。 */
    val propertyId: PropertyId,
    /** 与索引规范 JSON 完全相等的值。 */
    val canonicalValue: String,
) {
    init {
        require(canonicalValue.isNotBlank()) { "保存查询属性值不能为空" }
    }
}

/**
 * 表示可跨设备同步和历史恢复的工作区级保存查询。
 */
@Serializable
data class SavedQuery(
    /** 面向用户的查询名称。 */
    val name: String,
    /** 可为空的全文条件。 */
    val text: String = "",
    /** 必须全部命中的标签。 */
    val tagIds: List<TagId> = emptyList(),
    /** 必须全部命中的属性精确条件。 */
    val propertyFilters: List<SavedQueryPropertyFilter> = emptyList(),
) {
    init {
        require(name.isNotBlank()) { "保存查询名称不能为空" }
        require(name.length <= MAX_NAME_CHARACTERS) { "保存查询名称过长" }
        require(tagIds.distinct().size == tagIds.size) { "保存查询标签不能重复" }
        require(propertyFilters.map(SavedQueryPropertyFilter::propertyId).distinct().size == propertyFilters.size) {
            "保存查询不能重复过滤同一属性"
        }
        require(text.isNotBlank() || tagIds.isNotEmpty() || propertyFilters.isNotEmpty()) { "保存查询至少需要一个条件" }
    }

    companion object {
        /** 保存查询名称上限，避免异常对象占用 UI。 */
        const val MAX_NAME_CHARACTERS: Int = 120
    }
}
