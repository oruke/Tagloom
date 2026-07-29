package com.oruke.tagloom.editor

import com.oruke.tagloom.core.model.DefaultValuePolicy
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.PropertyId
import com.oruke.tagloom.core.model.PropertyStyle
import com.oruke.tagloom.core.model.PropertyTypes
import com.oruke.tagloom.core.model.TagPropertyBinding
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * 保存超级标签属性绑定在对话框中的可编辑草稿。
 *
 * 默认值使用文本草稿承接尚未完成的输入，只有确认时才按属性 schema 转换成 JSON，
 * 避免数字等类型在用户输入中途被错误拒绝。
 */
internal data class TagPropertyBindingDraft(
    /** 被绑定的工作区属性。 */
    val propertyId: PropertyId,
    /** 属性在该标签下是否必填。 */
    val required: Boolean,
    /** 属性在该标签下是否只读。 */
    val readonly: Boolean,
    /** 子标签是否继承当前绑定。 */
    val inheritable: Boolean,
    /** 默认值采用动态、快照还是无默认策略。 */
    val defaultPolicy: DefaultValuePolicy,
    /** 尚未按属性类型确认的默认值文本。 */
    val defaultValueText: String,
    /** 标签内的稳定展示顺序。 */
    val order: Int,
    /** 不属于本次表单范围但必须原样保留的样式覆盖。 */
    val styleOverride: PropertyStyle,
)

/**
 * 把已确认标签属性绑定转换为可编辑草稿。
 *
 * @param definition 当前属性定义，用于把选项和基础类型转换为可读文本。
 * @return 保留约束、顺序和样式覆盖的 UI 草稿。
 */
internal fun TagPropertyBinding.toEditorDraft(definition: PropertyDefinition): TagPropertyBindingDraft =
    TagPropertyBindingDraft(
        propertyId = propertyId,
        required = required,
        readonly = readonly,
        inheritable = inheritable,
        defaultPolicy = defaultPolicy,
        defaultValueText = defaultValue.toEditorText(definition),
        order = order,
        styleOverride = styleOverride,
    )

/**
 * 把标签属性绑定草稿转换为可写入权威 schema 的协议模型。
 *
 * 动态和快照策略必须提供合法默认值；`NONE` 会明确清空旧默认值，避免不可见的旧值继续生效。
 *
 * @param definition 当前属性定义，用于按 schema 校验默认值。
 * @param normalizedOrder 对话框最终列表中的顺序。
 * @return 可持久化标签属性绑定。
 */
internal fun TagPropertyBindingDraft.toConfirmedBinding(
    definition: PropertyDefinition,
    normalizedOrder: Int,
): TagPropertyBinding {
    val confirmedDefault = if (defaultPolicy == DefaultValuePolicy.NONE) {
        null
    } else {
        parseDefaultValue(definition, defaultValueText)
    }
    require(defaultPolicy == DefaultValuePolicy.NONE || confirmedDefault != null) {
        "${definition.name} 使用默认策略时必须填写默认值"
    }
    return TagPropertyBinding(
        propertyId = propertyId,
        required = required,
        defaultValue = confirmedDefault,
        defaultPolicy = defaultPolicy,
        readonly = readonly,
        inheritable = inheritable,
        order = normalizedOrder,
        styleOverride = styleOverride,
    )
}

/**
 * 把 JSON 默认值转换为对话框可编辑文本。
 *
 * @param definition 属性定义。
 * @return 对应属性类型的紧凑文本；无默认值时为空。
 */
private fun JsonElement?.toEditorText(definition: PropertyDefinition): String {
    if (this == null) return ""
    return when (definition.type) {
        PropertyTypes.MULTI_SELECT -> runCatching {
            jsonArray.mapNotNull { element -> element.jsonPrimitive.contentOrNull }.joinToString(",")
        }.getOrDefault(toString())

        PropertyTypes.BOOLEAN -> jsonPrimitive.booleanOrNull?.toString().orEmpty()
        PropertyTypes.NUMBER -> jsonPrimitive.doubleOrNull?.toString().orEmpty()
        PropertyTypes.TEXT,
        PropertyTypes.DATE,
        PropertyTypes.DATETIME,
        PropertyTypes.SELECT,
        PropertyTypes.URL,
        PropertyTypes.EMAIL,
        -> jsonPrimitive.contentOrNull.orEmpty()

        else -> toString()
    }
}

/**
 * 按工作区属性 schema 解析默认值草稿。
 *
 * @param definition 属性定义。
 * @param rawValue 用户尚未规范化的文本。
 * @return 合法 JSON 默认值；空文本返回空。
 */
private fun parseDefaultValue(
    definition: PropertyDefinition,
    rawValue: String,
): JsonElement? {
    val value = rawValue.trim()
    if (value.isEmpty()) return null
    return when (definition.type) {
        PropertyTypes.NUMBER -> {
            val number = value.toDoubleOrNull()
            require(number != null && number.isFinite()) { "${definition.name} 的默认值必须是有效数字" }
            JsonPrimitive(number)
        }

        PropertyTypes.BOOLEAN -> when (value.lowercase()) {
            "true", "是" -> JsonPrimitive(true)
            "false", "否" -> JsonPrimitive(false)
            else -> throw IllegalArgumentException("${definition.name} 的默认值必须是 true 或 false")
        }

        PropertyTypes.DATE -> {
            require(ISO_DATE_PATTERN.matches(value)) { "${definition.name} 的默认日期必须使用 YYYY-MM-DD" }
            JsonPrimitive(value)
        }

        PropertyTypes.SELECT -> {
            require(definition.options.any { option -> option.id == value }) { "${definition.name} 的默认选项不存在" }
            JsonPrimitive(value)
        }

        PropertyTypes.MULTI_SELECT -> {
            val ids = value.split(',').map(String::trim).filter(String::isNotEmpty).distinct()
            require(ids.all { id -> definition.options.any { option -> option.id == id } }) {
                "${definition.name} 包含不存在的默认选项"
            }
            JsonArray(ids.map(::JsonPrimitive))
        }

        PropertyTypes.TEXT,
        PropertyTypes.DATETIME,
        PropertyTypes.URL,
        PropertyTypes.EMAIL,
        -> JsonPrimitive(value)

        else -> runCatching { Json.parseToJsonElement(value) }
            .getOrElse { throw IllegalArgumentException("${definition.name} 的默认值必须是合法 JSON") }
    }
}

/** 日期属性默认值使用的最小稳定格式。 */
private val ISO_DATE_PATTERN: Regex = Regex("\\d{4}-\\d{2}-\\d{2}")
