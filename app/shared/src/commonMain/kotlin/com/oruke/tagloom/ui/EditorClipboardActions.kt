package com.oruke.tagloom.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.input.TextFieldValue
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.editor.*
import com.oruke.tagloom.platform.EditorClipboard
import com.oruke.tagloom.platform.createEditorClipboard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 将键盘、系统菜单和辅助技术操作汇入同一剪贴板流程，不缓存已解密正文。 */
internal class EditorClipboardActions(
    /** 当前文档状态及事务入口。 */
    private val state: TagloomEditorState,
    /** 原生字段选区同步入口。 */
    private val syncSelection: () -> Unit,
    /** 平台多格式剪贴板。 */
    private val clipboard: EditorClipboard,
    /** 随编辑器销毁而取消的协程作用域。 */
    private val scope: CoroutineScope,
    /** 完成编辑后清理拖选态并恢复原生焦点。 */
    private val onCursor: (EditorCursorTarget) -> Unit,
) {
    /** 当前是否存在可复制文字，跨块范围可以含空的首块。 */
    val canCopy: Boolean get() = state.selection.isNotEmpty

    /**
     * 复制完整选区；剪切在剪贴板成功写入后才删除。
     * @param cut 是否同时移除选中文字。
     */
    fun copy(cut: Boolean = false) {
        syncSelection()
        val fragment = state.selectedClipboardFragment() ?: return
        val content = EditorClipboardContent(fragment.plainText(), EditorClipboardHtml.render(fragment), fragment)
        val selection = state.selection
        val document = state.selectedDocument
        scope.launch {
            if (clipboard.write(content) && cut && state.selectedDocument?.id == document?.id &&
                state.selectedDocument?.note == document?.note && state.selection == selection) {
                state.replaceDocumentSelection("")?.let(onCursor)
            }
        }
    }

    /**
     * 读取当前剪贴板并替换选区；异步返回时不覆盖用户已经切换的文档或光标。
     * @param plainOnly 是否显式丢弃格式，直接插入原始纯文本。
     */
    fun paste(plainOnly: Boolean = false) {
        syncSelection()
        val selection = state.selection
        val document = state.selectedDocument
        scope.launch {
            val content = clipboard.read() ?: return@launch
            // 自动保存会替换 EditorDocument 的保存状态，但正文没有改变时仍应完成用户的粘贴。
            if (state.selectedDocument?.id == document?.id && state.selectedDocument?.note == document?.note &&
                state.selection == selection) {
                state.pasteClipboardContent(content, plainOnly)?.let(onCursor)
            }
        }
    }
}

/**
 * 为每个正文输入框装配共享剪贴板命令，原生选区变化始终读取最新字段值。
 * @param state 当前文档状态。
 * @param blockId 接收动作的块。
 * @param value 当前原生字段值。
 * @param controller 文档选区控制器。
 * @return 所有输入入口共用的操作对象。
 */
@Composable
internal fun rememberEditorClipboardActions(
    state: TagloomEditorState, blockId: BlockId, value: TextFieldValue,
    controller: DocumentSelectionController?
): EditorClipboardActions {
    val clipboard = LocalClipboard.current
    val port = remember(clipboard) { createEditorClipboard(clipboard) }
    val current by rememberUpdatedState(value)
    val scope = rememberCoroutineScope()
    return remember(state, blockId, controller, port, scope) {
        EditorClipboardActions(state, {
            if (!state.selection.isCrossBlock) state.updateSelection(
                blockId,
                current.selection.start,
                current.selection.end
            )
        }, port, scope, { controller?.collapse(it) })
    }
}
