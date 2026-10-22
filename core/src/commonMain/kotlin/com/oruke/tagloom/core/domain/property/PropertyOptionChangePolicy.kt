package com.oruke.tagloom.core.domain.property

import com.oruke.tagloom.core.model.*
import kotlinx.serialization.json.*

/** 防止目录维护让已有 Note 值或类型默认值悬空，必须在同一工作区写入锁内执行。 */
object PropertyOptionChangePolicy {
    /**
     * 校验目录删除的引用；改名和移动使用同一 ID，因此不会触发删除保护。
     * @param previous 当前确认定义，首次创建为空。
     * @param next 待写入完整定义。
     * @param notes 当前全部活动权威笔记。
     * @param tags 当前类型目录，用于保护默认选值。
     */
    fun requireSafeUpdate(
        previous: PropertyDefinition?,
        next: PropertyDefinition,
        notes: List<Note>,
        tags: List<TagDefinition>
    ) {
        val removed = previous?.options.orEmpty().map { it.id }.toSet() - next.options.map { it.id }.toSet()
        if (removed.isEmpty()) return
        require(previous?.options.orEmpty().none { it.parentOptionId in removed }) { "property-option-has-children" }
        val values = notes.mapNotNull { it.propertyValues[next.id]?.value } + tags.flatMap { tag ->
            tag.propertyBindings.filter { it.propertyId == next.id }.mapNotNull { it.defaultValue }
        }
        require(values.none { value ->
            when (value) {
                is JsonPrimitive -> value.isString && value.content in removed
                is JsonArray -> value.any { (it as? JsonPrimitive)?.content in removed }
                else -> false
            }
        }) { "property-option-in-use" }
    }
}
