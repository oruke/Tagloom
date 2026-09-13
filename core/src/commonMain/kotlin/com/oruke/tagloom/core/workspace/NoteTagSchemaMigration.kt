package com.oruke.tagloom.core.workspace

import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.core.model.TagPropertyBinding

/**
 * 移除旧版根 Note 自动引入的状态和日期绑定；不处理自定义类型或属性值。
 *
 * 只识别除排序外完全符合旧默认值的绑定，避免覆盖用户设置的约束、默认值或样式。
 * 返回值必须由调用方通过 schema 事务确认，不能作为未持久化的 UI 覆盖层。
 *
 * @return 需要确认的新根定义；没有旧默认绑定时返回原定义，重复执行不会产生变更。
 */
fun TagDefinition.withoutLegacyNotePropertyBindings(): TagDefinition {
    if (id != WorkspaceDefaults.DEFAULT_NOTE_TAG_ID) return this
    val legacyIds = setOf(WorkspaceDefaults.DEFAULT_SELECT_PROPERTY_ID, WorkspaceDefaults.DEFAULT_DATE_PROPERTY_ID)
    val retained = propertyBindings.filterNot { binding ->
        binding.propertyId in legacyIds && binding == TagPropertyBinding(binding.propertyId, order = binding.order)
    }
    return if (retained.size == propertyBindings.size) this else copy(propertyBindings = retained)
}
