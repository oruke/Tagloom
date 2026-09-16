package com.oruke.tagloom.time

import kotlinx.serialization.Serializable

/** 当前设备的时间显示偏好；空时区和 SYSTEM 选项持续跟随设备，而非保存启动时的系统值。 */
@Serializable
data class DateTimePreferences(
    /** 可选 IANA 时区标识；空值表示跟随系统。 */
    val zoneId: String? = null,
    /** 日期显示顺序或系统/自定义模式。 */
    val dateFormat: DateDisplayFormat = DateDisplayFormat.SYSTEM,
    /** 自定义日期格式，仅使用跨平台共同支持的日期符号。 */
    val customDatePattern: String = "yyyy-MM-dd",
    /** 时间制覆盖；SYSTEM 保留设备的实际时间格式。 */
    val hourCycle: DisplayHourCycle = DisplayHourCycle.SYSTEM,
)

/** 可选日期格式，协议值稳定且不依赖界面语言。 */
@Serializable
enum class DateDisplayFormat {
    /** 跟随设备地区及系统日期格式。 */
    SYSTEM,

    /** 年、月、日。 */
    ISO,

    /** 日、月、年。 */
    DAY_FIRST,

    /** 月、日、年。 */
    MONTH_FIRST,

    /** 使用用户填写且经过校验的日期格式。 */
    CUSTOM,
}

/** 可选时间制；不改变持久化时间点。 */
@Serializable
enum class DisplayHourCycle {
    /** 使用设备时间格式。 */
    SYSTEM,

    /** 显示本地化上午/下午的十二小时制。 */
    TWELVE,

    /** 二十四小时制。 */
    TWENTY_FOUR,
}

/** 平台读取的系统显示规则，运行中刷新以响应系统地区、时区与时间制变更。 */
data class SystemDateTimeSettings(
    /** 用于平台格式化的地区标识。 */
    val localeTag: String,
    /** 系统当前时区标识。 */
    val zoneId: String,
    /** 平台短日期格式。 */
    val datePattern: String,
    /** 平台短时间格式，含本地时间制。 */
    val timePattern: String,
)

/** 供时区搜索使用的稳定标识和本地化名称。 */
data class DisplayTimeZone(
    /** 可持久化的时区 ID。 */
    val id: String,
    /** 当前地区下的时区名称。 */
    val name: String,
)

/** 日期时间输入失败的可本地化原因，不携带用户输入。 */
enum class TemporalInputError {
    /** 格式或实际日期无效。 */
    INVALID,

    /** 夏令时跳跃导致本地时间不存在。 */
    NONEXISTENT,

    /** 夏令时回拨导致本地时间对应多个时间点。 */
    AMBIGUOUS,
}

/** 日期时间转换失败；界面只展示原因对应的本地化提示。 */
class TemporalInputException(
    /** 不含原始时间文本的失败原因。 */
    val reason: TemporalInputError,
) : IllegalArgumentException(reason.name)

/**
 * 选择日期格式；自定义模式须先通过公共格式校验。
 * @param system 当前设备规则。
 * @return 平台可直接使用的日期格式。
 */
fun DateTimePreferences.datePattern(system: SystemDateTimeSettings): String = when (dateFormat) {
    DateDisplayFormat.SYSTEM -> system.datePattern
    DateDisplayFormat.ISO -> "yyyy-MM-dd"
    DateDisplayFormat.DAY_FIRST -> "dd/MM/yyyy"
    DateDisplayFormat.MONTH_FIRST -> "MM/dd/yyyy"
    DateDisplayFormat.CUSTOM -> customDatePattern
}

/**
 * 选择时间格式，系统模式保留本机的上午/下午位置和时间分隔符。
 * @param system 当前设备规则。
 * @return 平台可直接使用的时间格式。
 */
fun DateTimePreferences.timePattern(system: SystemDateTimeSettings): String = when (hourCycle) {
    DisplayHourCycle.SYSTEM -> system.timePattern
    DisplayHourCycle.TWELVE -> "h:mm a"
    DisplayHourCycle.TWENTY_FOUR -> "HH:mm"
}

/**
 * 校验跨平台日期格式的有限符号集，拒绝周所属年份、大写日数或混入时间/时区符号。
 * @return 同时包含年、月、日且引号配对时为 true。
 */
fun String.isValidDateDisplayPattern(): Boolean {
    if (length !in 1..80) return false
    val fields = mutableSetOf<Char>()
    var quoted = false
    var index = 0
    while (index < length) {
        val char = this[index]
        if (char == '\'') {
            if (getOrNull(index + 1) == '\'') index += 2 else {
                quoted = !quoted; index++
            }
        } else if (!quoted && char.isAsciiLetter()) {
            val start = index
            while (getOrNull(index) == char) index++
            val count = index - start
            if (when (char) {
                    'y' -> count != 4; 'M' -> count !in 1..4; 'd' -> count !in 1..2; 'E' -> count !in 3..4; else -> true
                }
            ) return false
            fields += char
        } else {
            if (char.isISOControl()) return false
            index++
        }
    }
    return !quoted && fields.containsAll(setOf('y', 'M', 'd'))
}

/** @return 当前字符是否属于日期格式保留的 ASCII 字母。 */
private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'
