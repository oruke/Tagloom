package com.oruke.tagloom.ui

import com.oruke.tagloom.time.isValidDateDisplayPattern
import kotlin.test.*

/** 自定义格式只允许同一组跨平台日历字段，防止看似正确但改变年或日语义的格式。 */
class DateDisplayPatternTest {
    /** 覆盖中文、文本月份、引号转义、周所属年和错误日期字段。 */
    @Test
    fun validatesSharedCalendarPatterns() {
        listOf(
            "yyyy/MM/dd",
            "yyyy年M月d日",
            "d MMM yyyy",
            "EEEE, MMMM d, yyyy",
            "yyyy-MM-dd 'day'",
            "yyyy-MM-dd ''"
        ).forEach {
            assertTrue(it.isValidDateDisplayPattern(), it)
        }
        listOf(
            "YYYY-MM-dd",
            "yyyy-MM-DD",
            "MM/dd",
            "yy-MM-dd",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd '",
            "",
            "yyyy\nMM\ndd"
        ).forEach {
            assertFalse(it.isValidDateDisplayPattern(), it)
        }
    }
}
