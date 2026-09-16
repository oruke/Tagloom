@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.oruke.tagloom.time

import kotlinx.serialization.json.Json
import platform.Foundation.*
import kotlin.time.Clock
import kotlin.time.Instant

/** @return Apple 系统格式及当前设备的独立偏好；不依赖尚未装配的 iOS 工作区。 */
actual fun createDateTimeEnvironment(): DateTimeEnvironment =
    DateTimeEnvironment(AppleDateTimePlatform(), AppleDateTimePreferenceStore())

/** 使用 Foundation 的地区和时区规则，日期属性始终在 UTC 日历中计算。 */
private class AppleDateTimePlatform : DateTimePlatform {
    /** @return 当前 Apple 地区、时区和用户短日期/时间格式。 */
    override fun systemSettings(): SystemDateTimeSettings {
        val locale = NSLocale.currentLocale
        val date = NSDateFormatter().apply {
            this.locale = locale; dateStyle = NSDateFormatterShortStyle; timeStyle = NSDateFormatterNoStyle
        }
        val time = NSDateFormatter().apply {
            this.locale = locale; dateStyle = NSDateFormatterNoStyle; timeStyle = NSDateFormatterShortStyle
        }
        return SystemDateTimeSettings(
            locale.localeIdentifier,
            NSTimeZone.localTimeZone.name,
            date.dateFormat,
            time.dateFormat
        )
    }

    /**
     * 提供 Apple 当前支持的时区。
     * @param system 当前系统规则。
     * @return 含 UTC 的稳定时区列表。
     */
    override fun timeZones(system: SystemDateTimeSettings): List<DisplayTimeZone> =
        (NSTimeZone.knownTimeZoneNames.filterIsInstance<String>() + "UTC").distinct().sorted()
            .map { DisplayTimeZone(it, it) }

    /** @return 当前 UTC 时间。 */
    override fun currentTimestamp(): String = Clock.System.now().toString()

    /**
     * 显示协议时间，无法解析时保留原文。
     * @param value 标准日期或日期时间。
     * @param dateOnly 是否为纯日期。
     * @param preferences 设备显示覆盖。
     * @param system 当前系统规则。
     * @return 本地化文本。
     */
    override fun format(
        value: String,
        dateOnly: Boolean,
        preferences: DateTimePreferences,
        system: SystemDateTimeSettings
    ): String =
        runCatching {
            if (value.isBlank()) value else {
                val instant = Instant.parse(if (dateOnly) "${value}T00:00:00Z" else value)
                formatter(dateOnly, preferences, system, if (dateOnly) "UTC" else preferences.zoneId ?: system.zoneId)
                    .stringFromDate(NSDate.dateWithTimeIntervalSince1970(instant.toEpochMilliseconds() / 1000.0))
            }
        }.getOrDefault(value)

    /**
     * 解析完整本地输入，通过格式化回读拒绝自动归一化的夏令时缺口和重复时刻。
     * @param value 本地化文本或带偏移的 ISO 文本。
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
            if (dateOnly && Regex("\\d{4}-\\d{2}-\\d{2}").matches(text)) {
                Instant.parse("${text}T00:00:00Z")
                return text
            }
            if (!dateOnly && Regex("\\d{4}-\\d{2}-\\d{2}T.*").matches(text)) return Instant.parse(text).toString()
            val parser =
                formatter(dateOnly, preferences, system, if (dateOnly) "UTC" else preferences.zoneId ?: system.zoneId)
            val date = parser.dateFromString(text) ?: throw TemporalInputException(TemporalInputError.INVALID)
            if (parser.stringFromDate(date) != text) throw TemporalInputException(if (dateOnly) TemporalInputError.INVALID else TemporalInputError.NONEXISTENT)
            if (!dateOnly) {
                // 常见回拨也可能为半小时；检查相邻时间点的同一墙上时刻，拒绝平台默认选择的分支。
                for (offset in listOf(-7200, -3600, -1800, 1800, 3600, 7200)) {
                    if (parser.stringFromDate(date.dateByAddingTimeInterval(offset.toDouble())) == text) throw TemporalInputException(
                        TemporalInputError.AMBIGUOUS
                    )
                }
            }
            val result = Instant.fromEpochMilliseconds((date.timeIntervalSince1970 * 1000).toLong()).toString()
            return if (dateOnly) result.substringBefore('T') else result
        } catch (error: TemporalInputException) {
            throw error
        } catch (_: Exception) {
            throw TemporalInputException(TemporalInputError.INVALID)
        }
    }

    /**
     * 创建当前调用专用的 Foundation 格式化器。
     * @param dateOnly 是否只使用日期模式。
     * @param preferences 用户覆盖。
     * @param system 系统规则。
     * @param zoneId 本次计算时区。
     * @return 禁止宽松日期解析的格式化器。
     */
    private fun formatter(
        dateOnly: Boolean,
        preferences: DateTimePreferences,
        system: SystemDateTimeSettings,
        zoneId: String
    ): NSDateFormatter = NSDateFormatter().apply {
        locale = NSLocale(system.localeTag)
        timeZone = requireNotNull(NSTimeZone.timeZoneWithName(zoneId))
        lenient = false
        dateFormat = preferences.datePattern(system) + if (dateOnly) "" else " ${preferences.timePattern(system)}"
    }
}

/** 将非业务显示偏好保存到应用本机 defaults，不加入工作区同步或快照。 */
private class AppleDateTimePreferenceStore : DateTimePreferenceStore {
    /** 用稳定键隔离显示偏好。 */
    private val key = "tagloom.date-time.preferences"

    /** 兼容后续新增字段。 */
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** @return 保存的偏好，读取失败时返回系统默认。 */
    override suspend fun load(): DateTimePreferences = runCatching {
        json.decodeFromString<DateTimePreferences>(NSUserDefaults.standardUserDefaults.stringForKey(key) ?: "{}")
    }.getOrDefault(DateTimePreferences())

    /**
     * 保存当前设备显示偏好。
     * @param preferences 已校验的完整偏好。
     */
    override suspend fun save(preferences: DateTimePreferences) {
        NSUserDefaults.standardUserDefaults.setObject(json.encodeToString(preferences), forKey = key)
    }
}
