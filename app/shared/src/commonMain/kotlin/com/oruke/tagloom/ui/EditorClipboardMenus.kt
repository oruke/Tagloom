package com.oruke.tagloom.ui

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar

/**
 * 保留系统触摸工具栏外观，把复制、剪切、粘贴接入文档命令。
 * @param actions 当前字段的剪贴板动作。
 * @param content 当前正文输入框。
 */
@Composable
internal fun EditorClipboardMenus(actions: EditorClipboardActions, content: @Composable () -> Unit) {
    val toolbar = LocalTextToolbar.current
    val adapted = remember(toolbar, actions) { EditorClipboardTextToolbar(toolbar, actions) }
    CompositionLocalProvider(LocalTextToolbar provides adapted) {
        PlatformEditorClipboardMenus(actions, content)
    }
}

/**
 * 为平台原生文本菜单保留本地化标签并替换剪贴板动作。
 * @param actions 当前字段动作。
 * @param content 正文输入框。
 */
@Composable
internal expect fun PlatformEditorClipboardMenus(actions: EditorClipboardActions, content: @Composable () -> Unit)

/**
 * 适配 Compose 的旧版触摸选区工具栏，不改变选区或自动填充回调。
 * @param delegate 平台提供的原生工具栏。
 * @param actions 文档级剪贴板动作。
 */
private class EditorClipboardTextToolbar(
    /** 保留平台工具栏的外观和显示状态。 */
    private val delegate: TextToolbar,
    /** 文档级复制、剪切与粘贴入口。 */
    private val actions: EditorClipboardActions
) : TextToolbar by delegate {
    /**
     * 显示系统工具栏，并用完整文档选区处理剪贴板操作。
     * @param rect 工具栏定位区域。
     * @param onCopyRequested 原生复制动作是否可用。
     * @param onPasteRequested 原生粘贴动作是否可用。
     * @param onCutRequested 原生剪切动作是否可用。
     * @param onSelectAllRequested 原生全选动作。
     */
    override fun showMenu(
        rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?
    ) {
        delegate.showMenu(
            rect, onCopyRequested?.let { { actions.copy(); delegate.hide() } },
            onPasteRequested?.let { { actions.paste(); delegate.hide() } },
            onCutRequested?.let { { actions.copy(cut = true); delegate.hide() } }, onSelectAllRequested
        )
    }

    /**
     * 转发包含自动填充的新版重载，确保系统选择的入口也使用相同剪贴板命令。
     * @param rect 工具栏定位区域。
     * @param onCopyRequested 原生复制可用性。
     * @param onPasteRequested 原生粘贴可用性。
     * @param onCutRequested 原生剪切可用性。
     * @param onSelectAllRequested 原生全选动作。
     * @param onAutofillRequested 原生自动填充动作。
     */
    override fun showMenu(
        rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?, onAutofillRequested: (() -> Unit)?
    ) {
        delegate.showMenu(
            rect,
            onCopyRequested?.let { { actions.copy(); delegate.hide() } },
            onPasteRequested?.let { { actions.paste(); delegate.hide() } },
            onCutRequested?.let { { actions.copy(cut = true); delegate.hide() } },
            onSelectAllRequested,
            onAutofillRequested
        )
    }
}
