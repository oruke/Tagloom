@file:Suppress("DEPRECATION")

package com.oruke.tagloom.platform

import androidx.compose.ui.platform.Clipboard
import com.oruke.tagloom.editor.*
import platform.UIKit.UIPasteboard

/** iOS 的自定义统一类型标识，不与公开文本类型混用。 */
private const val FRAGMENT_UTI = "com.tagloom.editor.fragment"

/**
 * 创建 iOS 剪贴板适配器，三种表示属于同一个 pasteboard item。
 * @param clipboard Compose 当前窗口注入的系统剪贴板。
 * @return iOS 剪贴板端口。
 */
internal actual fun createEditorClipboard(clipboard: Clipboard): EditorClipboard =
    IosEditorClipboard(clipboard.nativeClipboard)

/**
 * 不在会话中缓存正文的 UIKit 剪贴板适配器。
 * @param clipboard UIKit 系统剪贴板。
 */
private class IosEditorClipboard(
    /** 当前窗口的原生粘贴板。 */
    private val clipboard: UIPasteboard,
) : EditorClipboard {
    /**
     * 发布同一个选区的三种表示。
     * @param content 选区的多格式载荷。
     * @return 系统写入是否完成。
     */
    override suspend fun write(content: EditorClipboardContent): Boolean {
        clipboard.items = listOf(buildMap<String, Any> {
            put("public.utf8-plain-text", content.text)
            content.html?.let { put("public.html", it) }
            content.fragment?.let { put(FRAGMENT_UTI, it.encode()) }
        })
        return true
    }

    /** @return 当前纯文本及可识别的 Tagloom 片段。 */
    override suspend fun read(): EditorClipboardContent? {
        val item = clipboard.items.firstOrNull() as? Map<*, *>
        val text = clipboard.string ?: return null
        return EditorClipboardContent(text, fragment = decodeClipboardFragment(item?.get(FRAGMENT_UTI) as? String))
    }
}
