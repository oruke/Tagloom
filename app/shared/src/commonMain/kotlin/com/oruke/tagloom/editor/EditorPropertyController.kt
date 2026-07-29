package com.oruke.tagloom.editor

import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.NotePropertyValue
import com.oruke.tagloom.core.model.PropertyId
import com.oruke.tagloom.core.model.PropertyValueSource
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * 编排编辑器中文本、数值、布尔、日期、单选和多选属性值的读取与用户覆盖写入。
 *
 * @param timestampProvider 提供属性更新时间。
 * @param updateNote 把纯 Note 转换交回编辑器状态。
 */
class EditorPropertyController(
    private val timestampProvider: () -> String,
    private val updateNote: (((Note) -> Note) -> Unit),
) {
    /**
     * 读取字符串型属性值；非字符串或未设置时返回空文本。
     *
     * @param note 当前笔记。
     * @param propertyId 属性 ID。
     * @return 可直接放入文本控件的值。
     */
    fun stringValue(
        note: Note,
        propertyId: PropertyId,
    ): String = note.propertyValues[propertyId]?.value?.jsonPrimitive?.contentOrNull.orEmpty()

    /**
     * 读取布尔属性值；未设置或类型不匹配时为空。
     *
     * @param note 当前笔记。
     * @param propertyId 属性 ID。
     * @return 已保存布尔值或 null。
     */
    fun booleanValue(
        note: Note,
        propertyId: PropertyId,
    ): Boolean? = note.propertyValues[propertyId]?.value?.jsonPrimitive?.booleanOrNull

    /**
     * 读取多选属性的稳定选项 ID。
     *
     * @param note 当前笔记。
     * @param propertyId 属性 ID。
     * @return 忽略非字符串成员后的选项 ID 列表。
     */
    fun stringListValue(
        note: Note,
        propertyId: PropertyId,
    ): List<String> = runCatching {
        note.propertyValues[propertyId]?.value?.jsonArray
            ?.mapNotNull { element -> element.jsonPrimitive.contentOrNull }
            .orEmpty()
    }.getOrDefault(emptyList())

    /**
     * 写入或清除文本属性。
     *
     * @param propertyId 文本属性 ID。
     * @param value 用户输入文本；空白会移除显式值。
     */
    fun updateText(
        propertyId: PropertyId,
        value: String,
    ) {
        updateString(propertyId, value, validateDate = false)
    }

    /**
     * 写入或清除单选属性的稳定选项 ID。
     *
     * @param propertyId 单选属性 ID。
     * @param optionId 稳定选项 ID；为空时清除。
     */
    fun updateSelect(
        propertyId: PropertyId,
        optionId: String?,
    ) {
        updateString(propertyId, optionId.orEmpty(), validateDate = false)
    }

    /**
     * 写入 ISO `YYYY-MM-DD` 日期，空白表示清除。
     *
     * @param propertyId 日期属性 ID。
     * @param value 用户日期文本。
     */
    fun updateDate(
        propertyId: PropertyId,
        value: String,
    ) {
        updateString(propertyId, value, validateDate = true)
    }

    /**
     * 写入有限数值或在空白时清除属性。
     *
     * @param propertyId 数值属性 ID。
     * @param value 用户数值文本。
     */
    fun updateNumber(
        propertyId: PropertyId,
        value: String,
    ) {
        val normalized = value.trim()
        if (normalized.isEmpty()) {
            updateValue(propertyId, null)
            return
        }
        val number = normalized.toDoubleOrNull()
        require(number != null && number.isFinite()) { "请输入有效数值" }
        updateValue(propertyId, JsonPrimitive(number))
    }

    /**
     * 写入或清除布尔属性。
     *
     * @param propertyId 布尔属性 ID。
     * @param value 用户选择；为空时清除显式值。
     */
    fun updateBoolean(
        propertyId: PropertyId,
        value: Boolean?,
    ) {
        updateValue(propertyId, value?.let(::JsonPrimitive))
    }

    /**
     * 写入多选属性的稳定选项 ID 列表。
     *
     * @param propertyId 多选属性 ID。
     * @param optionIds 当前全部已选选项 ID；空列表表示清除。
     */
    fun updateMultiSelect(
        propertyId: PropertyId,
        optionIds: List<String>,
    ) {
        val values = optionIds.filter(String::isNotBlank).distinct()
        updateValue(propertyId, values.takeIf(List<String>::isNotEmpty)?.let { ids ->
            JsonArray(ids.map(::JsonPrimitive))
        })
    }

    /**
     * 以用户来源写入规范字符串值，或在空值时移除属性。
     *
     * @param propertyId 目标属性。
     * @param rawValue 未规范用户输入。
     * @param validateDate 是否要求 ISO 日期格式。
     */
    private fun updateString(
        propertyId: PropertyId,
        rawValue: String,
        validateDate: Boolean,
    ) {
        val value = rawValue.trim()
        require(!validateDate || value.isEmpty() || ISO_DATE_PATTERN.matches(value)) { "日期必须使用 YYYY-MM-DD" }
        updateValue(propertyId, value.takeIf(String::isNotEmpty)?.let(::JsonPrimitive))
    }

    /**
     * 以用户来源写入任意已校验 JSON 值，或在空值时移除属性。
     *
     * @param propertyId 目标属性。
     * @param value 已按属性类型校验的 JSON 值；为空时清除。
     */
    private fun updateValue(
        propertyId: PropertyId,
        value: JsonElement?,
    ) {
        updateNote { note ->
            val values = note.propertyValues.toMutableMap()
            if (value == null) {
                values.remove(propertyId)
            } else {
                values[propertyId] = NotePropertyValue(
                    value = value,
                    source = PropertyValueSource.USER,
                    overridden = true,
                    updatedAt = timestampProvider(),
                )
            }
            note.copy(propertyValues = values)
        }
    }

    private companion object {
        /** MVP 日期输入的规范格式；日历合法性由后续平台日期控件保证。 */
        val ISO_DATE_PATTERN: Regex = Regex("\\d{4}-\\d{2}-\\d{2}")
    }
}
