package com.oruke.tagloom

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.oruke.tagloom.capability.createTagloomCapabilities

/**
 * 启动桌面宿主并把能力装配交给共享 App。
 *
 * JVM 能力由共享层 actual 工厂装配；桌面入口只保留窗口生命周期，不承载文件、索引、搜索或同步实现。
 */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Tagloom",
        state = rememberWindowState(width = 1_280.dp, height = 820.dp),
    ) {
        App(capabilities = createTagloomCapabilities())
    }
}
