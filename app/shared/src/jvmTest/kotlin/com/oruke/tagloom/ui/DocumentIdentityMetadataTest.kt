package com.oruke.tagloom.ui

import com.oruke.tagloom.time.*
import kotlin.test.*

/** 用真实平台格式化验证时间点、纯日期和损坏值的显示语义。 */
class DocumentIdentityMetadataTest {
    /** 本例地区及时区规则，与测试主机设置隔离但仍使用真实 JDK 时区数据库。 */
    private val system = SystemDateTimeSettings("en-US", "Asia/Shanghai", "yyyy/MM/dd", "h:mm a")

    /** 实际用于产品的时间解析和格式化器。 */
    private val platform = JvmDateTimePlatform { system }

    /** 验证真实 Windows 原生地区查询可用，并能完成当前系统日期时间的格式化/解析。 */
    @Test
    fun readsNativeWindowsDateTimePreferences() {
        if (!com.sun.jna.Platform.isWindows()) return
        val native = windowsSystemDateTimeSettings(system)
        assertEquals(native, desktopSystemDateTimeSettings().copy(zoneId = system.zoneId))
        assertTrue(native.localeTag.isNotBlank())
        val value = "2026-09-16T06:30:00Z"
        val display = platform.format(value, false, DateTimePreferences(), native)
        assertNotEquals(value, display)
        assertEquals(value, platform.parse(display, false, DateTimePreferences(), native))
    }

    /** 验证默认本地化、跨日换算、固定覆盖、未知和空值回退，替代原先写死 UTC 的期望。 */
    @Test
    fun formatsSnapshotTimestampWithoutChangingSemantics() {
        val preferences = DateTimePreferences()
        assertEquals("2026/07/16 12:23 AM", platform.format("2026-07-15T16:23:46Z", false, preferences, system))
        assertEquals("2026/07/15", platform.format("2026-07-15", true, preferences, system))
        assertEquals("future-format", platform.format("future-format", false, preferences, system))
        assertEquals("", platform.format("", false, preferences, system))
        assertEquals(
            "2026-07-15 16:23", platform.format(
                "2026-07-15T16:23:46Z", false,
                preferences.copy(
                    zoneId = "UTC",
                    dateFormat = DateDisplayFormat.ISO,
                    hourCycle = DisplayHourCycle.TWENTY_FOUR
                ), system
            )
        )
    }

    /** 验证美式、德式和中文系统日期的格式化/解析闭环，纯日期不依赖所选时区。 */
    @Test
    fun roundTripsLocalizedDatesAndRejectsInvalidInputs() {
        val weekdayPreferences = DateTimePreferences(dateFormat = DateDisplayFormat.CUSTOM, customDatePattern = "EEE, dd MMM yyyy")
        val weekday = platform.format("2026-09-17T06:30:00Z", false, weekdayPreferences, system)
        assertEquals("Thu, 17 Sep 2026 2:30 PM", weekday)
        assertEquals("2026-09-17T06:30:00Z", platform.parse(weekday, false, weekdayPreferences, system))
        for (settings in listOf(
            system, system.copy(localeTag = "de-DE", datePattern = "dd.MM.yyyy", timePattern = "HH:mm"),
            system.copy(localeTag = "zh-CN", datePattern = "yyyy年M月d日", timePattern = "ah:mm")
        )) {
            val preferences = DateTimePreferences(zoneId = "Pacific/Honolulu")
            val date = platform.format("2024-02-29", true, preferences, settings)
            assertEquals("2024-02-29", platform.parse(date, true, preferences, settings))
            val displayed = platform.format("2026-01-01T06:30:00Z", false, preferences, settings)
            assertEquals("2026-01-01T06:30:00Z", platform.parse(displayed, false, preferences, settings))
        }
        for (invalid in listOf("2025-02-29", "2026-13-01", "2026/02/30", "2026/02/01garbage")) {
            assertFailsWith<TemporalInputException> { platform.parse(invalid, true, DateTimePreferences(), system) }
        }
    }

    /** 验证夏令时缺口、重复时刻、显式偏移以及半小时时区，禁止悄悄调整本地输入。 */
    @Test
    fun resolvesOffsetsAndRejectsDaylightSavingAmbiguity() {
        val preferences =
            DateTimePreferences("America/New_York", DateDisplayFormat.ISO, hourCycle = DisplayHourCycle.TWENTY_FOUR)
        assertEquals(
            TemporalInputError.NONEXISTENT,
            assertFailsWith<TemporalInputException> {
                platform.parse(
                    "2026-03-08 02:30",
                    false,
                    preferences,
                    system
                )
            }.reason
        )
        assertEquals(
            TemporalInputError.AMBIGUOUS,
            assertFailsWith<TemporalInputException> {
                platform.parse(
                    "2026-11-01 01:30",
                    false,
                    preferences,
                    system
                )
            }.reason
        )
        assertEquals("2026-11-01T05:30:00Z", platform.parse("2026-11-01T01:30:00-04:00", false, preferences, system))
        assertEquals("2026-11-01T06:30:00Z", platform.parse("2026-11-01T01:30:00-05:00", false, preferences, system))
        assertEquals(
            "2026-01-01 05:45",
            platform.format("2026-01-01T00:00:00Z", false, preferences.copy(zoneId = "Asia/Kathmandu"), system)
        )
        assertEquals("2026-07-01 08:00", platform.format("2026-07-01T12:00:00Z", false, preferences, system))
        assertEquals("2026-01-01 07:00", platform.format("2026-01-01T12:00:00Z", false, preferences, system))
    }
}
