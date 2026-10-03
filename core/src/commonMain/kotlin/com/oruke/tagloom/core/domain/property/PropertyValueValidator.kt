package com.oruke.tagloom.core.domain.property

import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.PropertyTypes
import kotlinx.serialization.json.*

/** 校验已实现可写属性的 JSON 值；未知、文件和公式值只允许原样保留，不猜测其协议。 */
object PropertyValueValidator {
    /**
     * 校验一次新增或修改的属性值，选项必须引用已确认 schema 中的稳定 ID。
     * @param value 外部或应用服务准备写入的 JSON 值。
     * @param definition 已确认的工作区属性定义。
     * @return 值与已实现类型一致时为真；空值应通过移除笔记值表达。
     */
    fun accepts(value: JsonElement, definition: PropertyDefinition): Boolean {
        val primitive = value as? JsonPrimitive
        val text = primitive?.takeIf { it.isString }?.content
        return when (definition.type) {
            PropertyTypes.TEXT, PropertyTypes.URL, PropertyTypes.EMAIL -> text != null
            PropertyTypes.NUMBER -> primitive?.isString == false && primitive.doubleOrNull?.isFinite() == true
            PropertyTypes.BOOLEAN -> primitive?.isString == false && primitive.booleanOrNull != null
            PropertyTypes.DATE -> text != null && acceptsDate(text)
            PropertyTypes.DATETIME -> text != null && acceptsDateTime(text)
            PropertyTypes.SELECT -> text != null && definition.options.any { it.id == text }
            PropertyTypes.MULTI_SELECT -> {
                val values = value as? JsonArray ?: return false
                val ids = values.map { (it as? JsonPrimitive)?.takeIf { item -> item.isString }?.content }
                ids.all { id -> id != null && definition.options.any { it.id == id } } && ids.distinct().size == ids.size
            }
            else -> false
        }
    }

    /**
     * 检查无时区 ISO 日期及公历日历合法性，避免 JVM 与 Android 使用不同宽松解析规则。
     * @param value `YYYY-MM-DD` 文本。
     * @return 年月日均有效时为真。
     */
    fun acceptsDate(value: String): Boolean {
        if (!DATE_PATTERN.matches(value)) return false
        val year = value.substring(0, 4).toInt()
        val month = value.substring(5, 7).toInt()
        val day = value.substring(8, 10).toInt()
        if (year == 0 || month !in 1..12) return false
        val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
        val lastDay = when (month) {
            2 -> if (leap) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }
        return day in 1..lastDay
    }

    /**
     * 检查带 UTC 或显式偏移的 ISO 时间；不按客户端本地时区解释未带时区的输入。
     * @param value ISO 日期时间文本。
     * @return 日期、时间和偏移均有效时为真。
     */
    fun acceptsDateTime(value: String): Boolean {
        val match = DATETIME_PATTERN.matchEntire(value) ?: return false
        if (!acceptsDate(value.substring(0, 10))) return false
        if (match.groupValues[1].toInt() !in 0..23 || match.groupValues[2].toInt() !in 0..59 ||
            match.groupValues[3].toInt() !in 0..59) return false
        val offset = match.groupValues[4]
        if (offset == "Z") return true
        val hours = offset.substring(1, 3).toInt()
        val minutes = offset.substring(4, 6).toInt()
        return hours in 0..18 && minutes in 0..59 && (hours < 18 || minutes == 0)
    }

    /** 存储日期固定为 ISO，界面本地化只影响展示。 */
    private val DATE_PATTERN = Regex("\\d{4}-\\d{2}-\\d{2}")
    /** 日期时间必须包含时区，秒的小数部分至多九位。 */
    private val DATETIME_PATTERN = Regex("\\d{4}-\\d{2}-\\d{2}T(\\d{2}):(\\d{2}):(\\d{2})(?:\\.\\d{1,9})?(Z|[+-]\\d{2}:\\d{2})")
}
