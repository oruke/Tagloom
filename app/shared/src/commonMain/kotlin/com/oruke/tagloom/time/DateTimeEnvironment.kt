package com.oruke.tagloom.time

/** 本机显示偏好持久化端口；实现不得写入工作区业务对象。 */
interface DateTimePreferenceStore {
    /** @return 已保存偏好；首次使用或不可读取时返回系统默认。 */
    suspend fun load(): DateTimePreferences

    /**
     * 持久化已校验偏好，失败时抛出异常供界面保留当前设置并重试。
     * @param preferences 完整设备偏好。
     */
    suspend fun save(preferences: DateTimePreferences)
}

/** 本地化和时区平台端口，共享 UI 不引用 JVM 或 Apple 时间 API。 */
interface DateTimePlatform {
    /** @return 当前系统地区、日期格式、时间格式和时区。 */
    fun systemSettings(): SystemDateTimeSettings

    /**
     * 枚举可供用户搜索选择的时区。
     * @param system 当前地区，决定名称语言。
     * @return 去重且稳定排序的时区目录。
     */
    fun timeZones(system: SystemDateTimeSettings): List<DisplayTimeZone>

    /** @return 当前 UTC ISO 时间，仅用于设置预览。 */
    fun currentTimestamp(): String

    /**
     * 将权威值转换为显示文本；损坏或未知格式原样保留，纯日期永不换算时区。
     * @param value ISO 日期或包含偏移的标准时间。
     * @param dateOnly 是否为纯日期。
     * @param preferences 本机显示覆盖。
     * @param system 当前系统规则。
     * @return 本地化文本；不改写输入。
     */
    fun format(
        value: String,
        dateOnly: Boolean,
        preferences: DateTimePreferences,
        system: SystemDateTimeSettings
    ): String

    /**
     * 将本地化输入转回协议值；不能猜测不存在或重复的本地时间。
     * @param value 本地化文本，亦接受明确的 ISO 日期/带偏移时间。
     * @param dateOnly 是否为纯日期。
     * @param preferences 编辑开始时的显示覆盖。
     * @param system 编辑开始时的系统规则。
     * @return ISO 日期或 UTC 时间；空输入返回空文本，非法输入抛出 TemporalInputException。
     */
    fun parse(
        value: String,
        dateOnly: Boolean,
        preferences: DateTimePreferences,
        system: SystemDateTimeSettings
    ): String
}

/** 聚合设备日期时间能力；测试可替换配置位置而保留真实平台格式化实现。 */
data class DateTimeEnvironment(
    /** 地区、时区、日期时间格式化与解析。 */
    val platform: DateTimePlatform,
    /** 与工作区独立的本地配置存储。 */
    val store: DateTimePreferenceStore,
)

/** @return 当前宿主的日期时间与设备偏好实现。 */
expect fun createDateTimeEnvironment(): DateTimeEnvironment
