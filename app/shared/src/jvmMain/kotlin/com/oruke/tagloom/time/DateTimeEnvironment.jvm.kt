package com.oruke.tagloom.time

import com.oruke.tagloom.workspace.platformWorkspaceRegistryFile
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary

/** @return 桌面系统格式和与工作区无关的本机配置文件。 */
actual fun createDateTimeEnvironment(): DateTimeEnvironment = DateTimeEnvironment(
    JvmDateTimePlatform(::desktopSystemDateTimeSettings),
    FileDateTimePreferenceStore(platformWorkspaceRegistryFile().resolveSibling("date-time-preferences.json")),
)

/** @return Windows 用户实际短日期/时间格式，其他桌面或原生读取失败时使用 JDK 地区规则。 */
internal fun desktopSystemDateTimeSettings(): SystemDateTimeSettings {
    val fallback = javaSystemDateTimeSettings()
    if (!Platform.isWindows()) return fallback
    return runCatching { windowsSystemDateTimeSettings(fallback) }.getOrDefault(fallback)
}

/**
 * 读取 Windows 用户自定义地区规则；保持独立入口以验证原生读取没有被后备值掩盖。
 * @param fallback JDK 提供的设备时区与默认规则。
 * @return 保留用户覆盖的本机短日期、短时间与地区。
 */
internal fun windowsSystemDateTimeSettings(fallback: SystemDateTimeSettings): SystemDateTimeSettings {
    val api = Native.load("kernel32", WindowsLocaleApi::class.java)
    return fallback.copy(
        localeTag = api.localeValue(0x5c),
        datePattern = api.localeValue(0x1f).toJavaDatePattern(),
        timePattern = api.localeValue(0x79).toJavaDatePattern(),
    )
}

/** 只读取 Windows 用户地区偏好，保留系统自定义短日期和 12/24 小时设置。 */
private interface WindowsLocaleApi : StdCallLibrary {
    /**
     * 调用系统地区查询 API；空地区名称代表当前用户，并保留用户覆盖。
     * @param localeName 地区名称或空值。
     * @param type 所需地区字段。
     * @param data UTF-16 输出缓冲区。
     * @param count 缓冲区字符数。
     * @return 写入字符数，失败为零。
     */
    fun GetLocaleInfoEx(localeName: WString?, type: Int, data: CharArray, count: Int): Int
}

/**
 * 读取一个系统地区字段，失败时交由调用方使用平台后备规则。
 * @param type Windows 地区字段常量。
 * @return 不含结尾空字符的字段值。
 */
private fun WindowsLocaleApi.localeValue(type: Int): String {
    val buffer = CharArray(256)
    val count = GetLocaleInfoEx(null, type, buffer, buffer.size)
    check(count > 1)
    return buffer.concatToString(0, count - 1)
}

/** @return Java 格式符号；仅转换未被单引号包裹的 Windows 星期与上午/下午符号。 */
internal fun String.toJavaDatePattern(): String {
    val result = StringBuilder()
    var index = 0
    var quoted = false
    while (index < length) {
        val char = this[index]
        if (char == '\'') {
            quoted = !quoted; result.append(char); index++; continue
        }
        if (!quoted && (char == 'd' || char == 't' || char == 'g')) {
            val start = index
            while (getOrNull(index) == char) index++
            val token = substring(start, index)
            result.append(
                when {
                    char == 't' -> "a"; char == 'g' -> "G"; token.length >= 3 -> "E".repeat(token.length); else -> token
                }
            )
        } else {
            result.append(char); index++
        }
    }
    return result.toString()
}
