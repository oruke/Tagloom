package com.oruke.tagloom.ui

import androidx.compose.runtime.Composable

/**
 * iOS 的文本操作由共享触摸工具栏适配器提供。
 * @param actions 当前字段剪贴板动作。
 * @param content 正文输入框。
 */
@Composable
internal actual fun PlatformEditorClipboardMenus(actions: EditorClipboardActions, content: @Composable () -> Unit) =
    content()
