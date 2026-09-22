@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.oruke.tagloom.platform

import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.asAwtTransferable
import com.oruke.tagloom.editor.*
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException

/** 原生私有 MIME 使用 String 载荷，可跨进程粘贴，不依赖 JVM 对象身份。 */
private val fragmentFlavor = DataFlavor("$EDITOR_CLIPBOARD_MIME;class=java.lang.String")

/**
 * 创建 JVM 多格式剪贴板，HTML 由 AWT 转换为系统原生格式。
 * @param clipboard 当前窗口注入的剪贴板。
 * @return 桌面剪贴板端口。
 */
internal actual fun createEditorClipboard(clipboard: Clipboard): EditorClipboard = JvmEditorClipboard(clipboard)

/**
 * 桌面系统剪贴板读写适配器；剪贴板被其他进程占用时不删除用户选区。
 * @param clipboard Compose 提供的窗口剪贴板。
 */
private class JvmEditorClipboard(
    /** 窗口注入的系统剪贴板，只在用户操作时访问。 */
    private val clipboard: Clipboard,
) : EditorClipboard {
    /**
     * 向系统发布同一次复制的所有格式。
     * @param content 待发布的多格式内容。
     * @return 系统写入是否成功。
     */
    override suspend fun write(content: EditorClipboardContent): Boolean = try {
        clipboard.setClipEntry(ClipEntry(EditorTransferable(content)))
        true
    } catch (_: IllegalStateException) {
        false
    }

    /** @return 已校验私有片段及纯文本；系统暂不可访问时为空。 */
    override suspend fun read(): EditorClipboardContent? = runCatching {
        val data = clipboard.getClipEntry()?.asAwtTransferable ?: return null
        val text = if (data.isDataFlavorSupported(DataFlavor.stringFlavor))
            data.getTransferData(DataFlavor.stringFlavor) as? String else null
        val fragment = if (data.isDataFlavorSupported(fragmentFlavor))
            decodeClipboardFragment(runCatching { data.getTransferData(fragmentFlavor) as? String }.getOrNull()) else null
        text?.let { EditorClipboardContent(it, fragment = fragment) }
    }.getOrNull()
}

/**
 * 同时向普通文本应用、富文本应用和 Tagloom 提供各自可识别的剪贴板格式。
 * @param content 当前选区的独立副本。
 */
private class EditorTransferable(content: EditorClipboardContent) : Transferable {
    /** 所有格式共享同一次复制快照，不持有可变编辑状态。 */
    private val values = buildMap<DataFlavor, String> {
        put(DataFlavor.stringFlavor, content.text)
        content.html?.let {
            put(DataFlavor.selectionHtmlFlavor, it)
            put(DataFlavor.fragmentHtmlFlavor, it)
            put(DataFlavor.allHtmlFlavor, it)
        }
        content.fragment?.let { put(fragmentFlavor, it.encode()) }
    }

    /** @return 本次复制支持的系统格式。 */
    override fun getTransferDataFlavors(): Array<DataFlavor> = values.keys.toTypedArray()

    /**
     * 判断目标应用请求的格式是否存在。
     * @param flavor 请求格式。
     * @return 是否可提供该格式。
     */
    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor in values

    /**
     * 提供对应格式的数据，未知格式按系统契约拒绝。
     * @param flavor 请求格式。
     * @return 对应的不可变文字载荷。
     */
    override fun getTransferData(flavor: DataFlavor): Any = values[flavor] ?: throw UnsupportedFlavorException(flavor)
}
