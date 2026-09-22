package com.oruke.tagloom.ui

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource

/**
 * 适配 Android 默认启用的新文本菜单；旧工具栏仍由共享适配器处理。
 * @param actions 当前字段剪贴板动作。
 * @param content 正文输入框。
 */
@Composable
@Suppress("DEPRECATION")
internal actual fun PlatformEditorClipboardMenus(actions: EditorClipboardActions, content: @Composable () -> Unit) {
    val copyLabel = stringResource(android.R.string.copy)
    val cutLabel = stringResource(android.R.string.cut)
    val pasteLabel = stringResource(android.R.string.paste)
    val clipboard = LocalClipboard.current.nativeClipboard
    Box(Modifier.appendTextContextMenuComponents {
        if (actions.canCopy) {
            item(CopyMenuKey, copyLabel) { actions.copy(); close() }
            item(CutMenuKey, cutLabel) { actions.copy(cut = true); close() }
        }
        if (clipboard.primaryClipDescription?.hasMimeType("text/*") == true) {
            item(PasteMenuKey, pasteLabel) { actions.paste(); close() }
        }
    }.filterTextContextMenuComponents {
        it.key != TextContextMenuKeys.CopyKey && it.key != TextContextMenuKeys.CutKey && it.key != TextContextMenuKeys.PasteKey
    }) { content() }
}

/** 与框架默认动作区分的复制菜单键。 */
private object CopyMenuKey

/** 与框架默认动作区分的剪切菜单键。 */
private object CutMenuKey

/** 与框架默认动作区分的粘贴菜单键。 */
private object PasteMenuKey
