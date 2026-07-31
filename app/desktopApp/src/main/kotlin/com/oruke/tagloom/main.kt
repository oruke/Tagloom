package com.oruke.tagloom

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Dimension
import java.awt.Image
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.painterResource
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
        icon = painterResource(Res.drawable.app_logo),
        state = windowState,
    ) {
        LaunchedEffect(Unit) {
            window.minimumSize = Dimension(900, 640)
            installTrayIcon(
                window = window,
                iconImage = loadTrayIconImage(),
                onExit = {
                    runBlocking { workspaceGateway?.lock() }
                    exitApplication()
                },
            )
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

/** desktopApp 模块自身 classloader，用于加载 resources 下的图标资源。 */
private val appClassLoader: ClassLoader = object {}.javaClass.classLoader

/**
 * 把 Tagloom 图标安装到系统托盘，提供「打开 Tagloom / 退出」菜单与双击恢复窗口。
 *
 * 托盘是纯平台装配能力：菜单动作通过回调委托给调用方，不承载业务规则；
 * 系统托盘不可用（如部分 Linux 桌面环境）时静默跳过，不影响主窗口流程。
 *
 * @param window 主窗口引用，用于托盘菜单恢复窗口显示。
 * @param iconImage 托盘图标图像；为 null 时不安装托盘。
 * @param onExit 退出回调：调用方负责锁定工作区并退出应用。
 */
private fun installTrayIcon(
    window: ComposeWindow,
    iconImage: Image?,
    onExit: () -> Unit,
) {
    if (iconImage == null || !SystemTray.isSupported()) return

    val menu = PopupMenu()
    menu.add(
        MenuItem("打开 Tagloom").apply {
            addActionListener {
                // 从托盘恢复主窗口：显示并置前，保持窗口状态不变
                window.isVisible = true
                window.toFront()
            }
        },
    )
    menu.addSeparator()
    menu.add(
        MenuItem("退出").apply {
            addActionListener { onExit() }
        },
    )

    val trayIcon = TrayIcon(iconImage, "Tagloom", menu).apply {
        isImageAutoSize = true
        addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(event: MouseEvent) {
                    // 双击左键快速恢复主窗口，与菜单「打开」行为一致
                    if (event.clickCount >= 2 && event.button == MouseEvent.BUTTON1) {
                        window.isVisible = true
                        window.toFront()
                    }
                }
            },
        )
    }
    try {
        SystemTray.getSystemTray().add(trayIcon)
    } catch (ignored: Exception) {
        // 托盘注册失败（平台限制或资源竞争）时忽略，应用主流程不受影响
    }
}

/**
 * 从 classpath 加载托盘图标图像。
 *
 * 资源文件位于 desktopApp 的 resources（tray-logo.png，实心蓝底白色 T 字
 * 品牌图标，在深色/浅色托盘区域均有可读性）。
 *
 * @return 托盘图标图像；资源缺失时返回 null。
 */
private fun loadTrayIconImage(): Image? =
    appClassLoader.getResourceAsStream("/tray-logo.png")?.use { stream ->
        ImageIO.read(stream)
    }
