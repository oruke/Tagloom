package com.oruke.tagloom.time

import java.text.DateFormat
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** JVM 与 Android 共用的真实日期解析和时区换算，输入时显式检查夏令时分支。 */
class JvmDateTimePlatform(
    /** 系统格式读取器；Android 与 Windows 可提供原生用户覆盖。 */
    private val systemProvider: () -> SystemDateTimeSettings = ::javaSystemDateTimeSettings,
) : DateTimePlatform {
    /** @return 平台当前读取的系统规则，保留跟随系统与用户覆盖的区别。 */
    override fun systemSettings(): SystemDateTimeSettings = systemProvider()

    /**
     * 获取 IANA 时区目录及本地化名称。
     * @param system 当前地区。
     * @return 含 UTC 的完整时区目录。
     */
    override fun timeZones(system: SystemDateTimeSettings): List<DisplayTimeZone> =
        (ZoneId.getAvailableZoneIds() + "UTC").sorted().map { id ->
            DisplayTimeZone(id, TimeZone.getTimeZone(id).getDisplayName(Locale.forLanguageTag(system.localeTag)))
        }

    /** @return 当前 UTC 时间。 */
    override fun currentTimestamp(): String = Instant.now().toString()

    /**
     * 按设备偏好显示日期，不能以时区转换改变纯日期的日历日。
     * @param value 标准协议值。
     * @param dateOnly 是否为纯日期。
     * @param preferences 显示偏好。
     * @param system 当前设备规则。
     * @return 本地化值；无法解析时保留原文。
     */
    override fun format(
        value: String,
        dateOnly: Boolean,
        preferences: DateTimePreferences,
        system: SystemDateTimeSettings
    ): String {
        if (value.isBlank()) return value
        return runCatching {
            val instant = if (dateOnly) LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC)
            else OffsetDateTime.parse(value).toInstant()
            formatter(dateOnly, preferences, system, if (dateOnly) "UTC" else preferences.zoneId ?: system.zoneId)
                .format(Date.from(instant))
        }.getOrDefault(value)
    }

    /**
     * 严格解析全部输入；夏令时回拨时要求显式偏移而不猜测早晚分支。
     * @param value 本地化输入或标准 ISO 文本。
     * @param dateOnly 是否为纯日期。
     * @param preferences 编辑开始时偏好。
     * @param system 编辑开始时系统规则。
     * @return ISO 日期或 UTC 时间。
     */
    override fun parse(
        value: String,
        dateOnly: Boolean,
        preferences: DateTimePreferences,
        system: SystemDateTimeSettings
    ): String {
        val text = value.trim()
        if (text.isEmpty()) return ""
        try {
            if (dateOnly && ISO_DATE.matches(text)) return LocalDate.parse(text).toString()
            if (!dateOnly && ISO_DATE.matches(text.take(10)) && text.getOrNull(10) == 'T') {
                return OffsetDateTime.parse(text).toInstant().toString()
            }
            // 先按 UTC 解析日历字段，再查询目标时区规则；直接让 Calendar 解析会静默归一化夏令时缺口。
            val parser = formatter(dateOnly, preferences, system, "UTC")
            val position = ParsePosition(0)
            val date = parser.parse(text, position)
            if (date == null || position.index != text.length) throw TemporalInputException(TemporalInputError.INVALID)
            val local = date.toInstant().atOffset(ZoneOffset.UTC).toLocalDateTime()
            if (dateOnly) return local.toLocalDate().toString()
            val offsets = ZoneId.of(preferences.zoneId ?: system.zoneId).rules.getValidOffsets(local)
            if (offsets.isEmpty()) throw TemporalInputException(TemporalInputError.NONEXISTENT)
            if (offsets.size > 1) throw TemporalInputException(TemporalInputError.AMBIGUOUS)
            return local.toInstant(offsets.single()).toString()
        } catch (error: TemporalInputException) {
            throw error
        } catch (_: Exception) {
            throw TemporalInputException(TemporalInputError.INVALID)
        }
    }

    /**
     * 为一次调用创建独立格式化器，避免 SimpleDateFormat 的共享线程状态。
     * @param dateOnly 是否只使用日期模式。
     * @param preferences 显示覆盖。
     * @param system 系统规则。
     * @param zoneId 本次计算时区。
     * @return 严格解析的本地格式化器。
     */
    private fun formatter(
        dateOnly: Boolean,
        preferences: DateTimePreferences,
        system: SystemDateTimeSettings,
        zoneId: String
    ): SimpleDateFormat {
        val pattern = preferences.datePattern(system) + if (dateOnly) "" else " ${preferences.timePattern(system)}"
        return SimpleDateFormat(pattern, Locale.forLanguageTag(system.localeTag)).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone(ZoneId.of(zoneId))
        }
    }
}

/** @return JDK 系统地区、时区和短日期/时间格式；平台原生偏好读取失败时作为后备。 */
internal fun javaSystemDateTimeSettings(): SystemDateTimeSettings {
    val locale = Locale.getDefault(Locale.Category.FORMAT)
    return SystemDateTimeSettings(
        localeTag = locale.toLanguageTag(),
        zoneId = TimeZone.getDefault().toZoneId().id,
        datePattern = (DateFormat.getDateInstance(DateFormat.SHORT, locale) as SimpleDateFormat).toPattern(),
        timePattern = (DateFormat.getTimeInstance(DateFormat.SHORT, locale) as SimpleDateFormat).toPattern(),
    )
}

/** 只有完整 ISO 纯日期走协议解析，避免将本地日/月顺序误判为 ISO。 */
private val ISO_DATE = Regex("\\d{4}-\\d{2}-\\d{2}")
