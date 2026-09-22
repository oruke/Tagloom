package com.oruke.tagloom.platform

import androidx.compose.ui.platform.Clipboard
import com.oruke.tagloom.editor.EditorClipboardContent

/** 平台剪贴板端口；只在用户触发复制、剪切或粘贴时读写，不在后台缓存正文。 */
internal interface EditorClipboard {
    /**
     * 原子发布多格式内容，剪切必须在成功后才能删除选区。
     * @param content 已裁剪的通用文字、HTML 和私有片段。
     * @return 系统剪贴板是否接受写入。
     */
    suspend fun write(content: EditorClipboardContent): Boolean

    /** @return 当前系统内容；空剪贴板或不支持的内容为空，损坏片段退回纯文本。 */
    suspend fun read(): EditorClipboardContent?
}

/**
 * 根据 Compose 当前注入的剪贴板创建平台适配器，测试可替换系统依赖。
 * @param clipboard 当前窗口的系统剪贴板。
 * @return 不持有正文副本的平台端口。
 */
internal expect fun createEditorClipboard(clipboard: Clipboard): EditorClipboard
