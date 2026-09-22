@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.oruke.tagloom.ui

import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.text.LocalTextContextMenu
import androidx.compose.foundation.text.TextContextMenu
import androidx.compose.runtime.*

/**
 * 使用系统默认菜单和本地化标签，只把正文剪贴板动作接入结构化选区。
 * @param actions 当前字段剪贴板动作。
 * @param content 正文输入框。
 */
@Composable
internal actual fun PlatformEditorClipboardMenus(actions: EditorClipboardActions, content: @Composable () -> Unit) {
    val menu = LocalTextContextMenu.current
    val adapted = remember(menu, actions) { EditorDesktopTextMenu(menu, actions) }
    CompositionLocalProvider(LocalTextContextMenu provides adapted, content = content)
}

/**
 * 桌面右键菜单代理；保留原有选词、全选及菜单展示方式。
 * @param delegate 平台菜单实现。
 * @param actions 正文剪贴板命令。
 */
private class EditorDesktopTextMenu(
    /** 系统菜单外观及本地化标签的来源。 */
    private val delegate: TextContextMenu,
    /** 当前字段的文档剪贴板命令。 */
    private val actions: EditorClipboardActions
) : TextContextMenu {
    /**
     * 将原生 TextManager 的三项剪贴板动作替换为正文命令。
     * @param textManager 原生输入框动作集合。
     * @param state 右键菜单状态。
     * @param content 输入框内容。
     */
    @Composable
    override fun Area(
        textManager: TextContextMenu.TextManager,
        state: ContextMenuState,
        content: @Composable () -> Unit
    ) {
        val manager = remember(textManager, actions) {
            object : TextContextMenu.TextManager by textManager {
                override val copy get() = TextContextMenu.Action(actions.canCopy || textManager.copy?.enabled == true) { actions.copy() }
                override val cut
                    get() = TextContextMenu.Action(actions.canCopy || textManager.cut?.enabled == true) {
                        actions.copy(
                            cut = true
                        )
                    }
                override val paste get() = TextContextMenu.Action(textManager.paste?.enabled == true) { actions.paste() }
            }
        }
        delegate.Area(manager, state, content)
    }
}
