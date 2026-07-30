package com.oruke.tagloom

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Dimension
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 启动桌面窗口；开发预览参数只加载内存示例，不触碰真实工作区。
 *
 * @param args 支持 `--preview-editor` 的桌面启动参数。
 */
fun main(args: Array<String>) = application {
    val previewEditor = PREVIEW_EDITOR_ARGUMENT in args
    val windowState = rememberWindowState(width = 1_280.dp, height = 820.dp)
    val workspaceGateway = if (previewEditor) null else rememberDesktopWorkspaceGateway()
    val windowTitle = if (previewEditor) {
        stringResource(Res.string.desktop_window_preview_title)
    } else {
        stringResource(Res.string.workspace_app_name)
    }
    Window(
        onCloseRequest = {
            runBlocking { workspaceGateway?.lock() }
            exitApplication()
        },
        title = windowTitle,
        state = windowState,
    ) {
        LaunchedEffect(Unit) {
            window.minimumSize = Dimension(900, 640)
        }
        App(
            documentFileGateway = rememberDesktopDocumentFileGateway(),
            workspaceGateway = workspaceGateway,
        )
    }
}

/**
 * 为窗口生命周期创建并复用 Markdown、HTML、PDF 与 Word 文档导出能力。
 *
 * @return 当前窗口使用的文件网关。
 */
@androidx.compose.runtime.Composable
private fun rememberDesktopDocumentFileGateway(): DesktopDocumentFileGateway =
    androidx.compose.runtime.remember { DesktopDocumentFileGateway() }

/**
 * 为窗口生命周期创建并复用桌面加密工作区能力。
 *
 * @return 当前窗口使用的工作区网关。
 */
@androidx.compose.runtime.Composable
private fun rememberDesktopWorkspaceGateway(): DesktopWorkspaceGateway =
    androidx.compose.runtime.remember { DesktopWorkspaceGateway() }

/** 只供开发与视觉验收使用的内存编辑器启动参数。 */
private const val PREVIEW_EDITOR_ARGUMENT = "--preview-editor"
