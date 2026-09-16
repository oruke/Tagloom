package com.oruke.tagloom.ui

import androidx.compose.runtime.*
import com.oruke.tagloom.time.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 管理本机显示偏好与系统规则；写入成功才发布偏好，不触发工作区保存。 */
internal class DateTimeSettingsController(
    /** 注入的本地配置与平台日期能力。 */
    val environment: DateTimeEnvironment,
) {
    /** 已确认的显示偏好。 */
    var preferences by mutableStateOf(DateTimePreferences())
        private set

    /** 最近读取的系统格式；跟随系统模式在刷新后重组。 */
    var system by mutableStateOf(environment.platform.systemSettings())
        private set

    /** 设置预览时间，刷新不触发笔记写入。 */
    var previewTimestamp by mutableStateOf(environment.platform.currentTimestamp())
        private set

    /** 是否已经读取设备配置。 */
    var loaded by mutableStateOf(false)
        private set

    /** 是否正在保存，防止多个选择覆盖写入顺序。 */
    var saving by mutableStateOf(false)
        private set

    /** 保存失败时保留原显示偏好，并提示可以重试。 */
    var failed by mutableStateOf(false)
        private set

    /** 读取持久化偏好；无效时区或自定义模式只回退对应字段。 */
    suspend fun load() {
        try {
            val saved = environment.store.load()
            val zones = environment.platform.timeZones(system).map { it.id }.toSet()
            preferences = saved.copy(
                zoneId = saved.zoneId?.takeIf { it in zones },
                dateFormat = if (saved.dateFormat == DateDisplayFormat.CUSTOM && !saved.customDatePattern.isValidDateDisplayPattern()) DateDisplayFormat.SYSTEM else saved.dateFormat,
                customDatePattern = saved.customDatePattern.takeIf { it.isValidDateDisplayPattern() } ?: "yyyy-MM-dd",
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            failed = true
        } finally {
            loaded = true
        }
    }

    /** 刷新系统时区、地区、时间制和预览时间；已选的固定覆盖继续生效。 */
    fun refreshSystem() {
        system = environment.platform.systemSettings()
        previewTimestamp = environment.platform.currentTimestamp()
    }

    /**
     * 校验并串行保存本机偏好。
     * @param next 用户请求的完整设置。
     * @return 成功写入时为 true，失败时保留原设置。
     */
    suspend fun save(next: DateTimePreferences): Boolean {
        if (!loaded || saving) return false
        if (!next.customDatePattern.isValidDateDisplayPattern()) return false
        if (next.zoneId != null && environment.platform.timeZones(system).none { it.id == next.zoneId }) return false
        saving = true
        failed = false
        return try {
            // 选择已被接受后，离开设置页也要完成短暂的本地提交，避免磁盘新值与会话旧值不一致。
            withContext(NonCancellable) {
                environment.store.save(next)
                preferences = next
                true
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            failed = true; false
        } finally {
            saving = false
        }
    }

    /**
     * 使用当前偏好格式化一个值。
     * @param value 标准日期或时间。
     * @param dateOnly 是否为纯日期。
     * @return 本地化文本或安全回退原文。
     */
    fun format(value: String, dateOnly: Boolean = false): String =
        environment.platform.format(value, dateOnly, preferences, system)
}

/** 应用内共用的显示偏好；独立组件预览可以不提供持久化控制器。 */
internal val LocalDateTimeSettings = staticCompositionLocalOf<DateTimeSettingsController?> { null }

/**
 * 为生产 App 提供统一格式化和设置状态，周期刷新系统选择。
 * @param environment 平台日期时间能力。
 * @param content 应用内容。
 */
@Composable
internal fun ProvideDateTimeSettings(environment: DateTimeEnvironment, content: @Composable () -> Unit) {
    val controller = remember(environment) { DateTimeSettingsController(environment) }
    LaunchedEffect(controller) {
        controller.load()
        while (true) {
            delay(30_000); controller.refreshSystem()
        }
    }
    CompositionLocalProvider(LocalDateTimeSettings provides controller, content = content)
}

/** @return 应用控制器；独立组件仅提供系统格式化，不主动写入配置。 */
@Composable
internal fun currentDateTimeSettings(): DateTimeSettingsController = LocalDateTimeSettings.current
    ?: remember { DateTimeSettingsController(createDateTimeEnvironment()) }
