package com.oruke.tagloom.time

import android.annotation.SuppressLint
import android.text.format.DateFormat
import com.oruke.tagloom.capability.AndroidTagloomEnvironment
import com.oruke.tagloom.workspace.platformWorkspaceRegistryFile
import java.text.SimpleDateFormat
import java.util.TimeZone

/**
 * Android 宿主已启用 desugar_jdk_libs_nio，为最低 API 24 提供 Path；共享库的 IDE 检查无法读取宿主配置。
 * @return Android 实际系统时间制、地区和不参与备份的设备偏好。
 */
@SuppressLint("NewApi")
actual fun createDateTimeEnvironment(): DateTimeEnvironment = DateTimeEnvironment(
    JvmDateTimePlatform {
        val context = AndroidTagloomEnvironment.context()
        val locale = context.resources.configuration.locales[0]
        SystemDateTimeSettings(
            locale.toLanguageTag(), TimeZone.getDefault().id,
            (DateFormat.getDateFormat(context) as SimpleDateFormat).toPattern(),
            DateFormat.getBestDateTimePattern(locale, if (DateFormat.is24HourFormat(context)) "Hm" else "hm"),
        )
    },
    FileDateTimePreferenceStore(platformWorkspaceRegistryFile().resolveSibling("date-time-preferences.json")),
)
