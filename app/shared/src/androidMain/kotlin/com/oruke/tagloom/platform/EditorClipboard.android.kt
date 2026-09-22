@file:Suppress("DEPRECATION")

package com.oruke.tagloom.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.os.PersistableBundle
import androidx.compose.ui.platform.Clipboard
import com.oruke.tagloom.editor.*

/**
 * 创建 Android 多格式剪贴板；私有片段放在同一 ClipDescription 中。
 * @param clipboard 当前窗口注入的系统剪贴板。
 * @return Android 剪贴板端口。
 */
internal actual fun createEditorClipboard(clipboard: Clipboard): EditorClipboard =
    AndroidEditorClipboard(clipboard.nativeClipboard)

/**
 * 通过系统 ClipData 同时提供纯文本和 HTML。
 * @param clipboard Android 系统剪贴板。
 */
private class AndroidEditorClipboard(
    /** 当前窗口的原生剪贴板服务。 */
    private val clipboard: ClipboardManager,
) : EditorClipboard {
    /**
     * 把选区发布为一个含纯文本、HTML 和片段的 ClipData。
     * @param content 已裁剪的多格式内容。
     * @return 系统是否接受写入。
     */
    override suspend fun write(content: EditorClipboardContent): Boolean = runCatching {
        val clip = if (content.html != null) ClipData.newHtmlText("", content.text, content.html)
        else ClipData.newPlainText("", content.text)
        content.fragment?.let { fragment ->
            clip.description.extras = PersistableBundle().apply { putString(EDITOR_CLIPBOARD_MIME, fragment.encode()) }
        }
        clipboard.setPrimaryClip(clip)
        true
    }.getOrDefault(false)

    /** @return 当前文字及有效的私有片段；不读取剪贴板 URI 或外部文件。 */
    override suspend fun read(): EditorClipboardContent? = runCatching {
        val clip = clipboard.primaryClip ?: return null
        val item = clip.getItemAt(0)
        val text = item.text?.toString() ?: return null
        EditorClipboardContent(
            text, item.htmlText,
            decodeClipboardFragment(clip.description.extras?.getString(EDITOR_CLIPBOARD_MIME))
        )
    }.getOrNull()
}
