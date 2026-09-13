package com.oruke.tagloom.ui.search

import androidx.compose.ui.input.key.*
import com.oruke.tagloom.editor.search.NoteSearchState

/**
 * 在编辑器宿主处理查找快捷键，正文、代码和侧栏获得焦点时均可使用。
 * @param event 当前原生键盘事件。
 * @param search 当前笔记的查找会话。
 * @param enabled 当前是否正在显示可查找的笔记。
 * @param onClose 关闭查找并恢复宿主焦点。
 * @return 查找功能消费该事件时为 true。
 */
internal fun handleNoteSearchKeyEvent(
    event: KeyEvent,
    search: NoteSearchState,
    enabled: Boolean,
    onClose: () -> Unit
): Boolean {
    if (!enabled || event.type != KeyEventType.KeyDown) return false
    return when {
        (event.isCtrlPressed || event.isMetaPressed) && !event.isShiftPressed && event.key == Key.F -> {
            search.open()
            true
        }

        search.isOpen && event.key == Key.F3 -> {
            search.move(event.isShiftPressed)
            true
        }

        search.isOpen && event.key == Key.Escape -> {
            onClose()
            true
        }

        else -> false
    }
}
