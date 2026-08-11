package com.oruke.tagloom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.TabBlockContent
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.editor.activeTabId
import com.oruke.tagloom.editor.addTab
import com.oruke.tagloom.editor.deleteTab
import com.oruke.tagloom.editor.renameTab
import com.oruke.tagloom.editor.selectTab
import com.oruke.tagloom.editor.setDefaultTab
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.Res
import tagloom.app.shared.generated.resources.editor_tabs_add
import tagloom.app.shared.generated.resources.editor_tabs_default
import tagloom.app.shared.generated.resources.editor_tabs_delete_cascade
import tagloom.app.shared.generated.resources.editor_tabs_delete_unpack
import tagloom.app.shared.generated.resources.editor_tabs_set_default

/**
 * 渲染 Tabs 容器的会话切换、标题编辑和删除策略入口。
 *
 * @param state 编辑器状态。
 * @param block 当前 Tabs 容器 Block。
 */
@Composable
internal fun TabsBlockEditor(
    state: TagloomEditorState,
    block: Block,
) {
    val note = state.selectedDocument?.note ?: return
    val tabs = note.blocks
        .filter { child -> !child.deleted && child.parentId == block.id && child.type == BlockTypes.TAB }
        .sortedWith(compareBy(Block::orderKey, { child -> child.id.value }))
    val activeTabId = state.activeTabId(block.id)
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        tabs.forEach { tab ->
            TabHeader(
                state = state,
                tabsBlock = block,
                tab = tab,
                selected = tab.id == activeTabId,
                default = tab.id == note.defaultTabId,
            )
        }
        TextButton(onClick = { state.addTab(block.id) }) {
            Text("+ ${stringResource(Res.string.editor_tabs_add)}")
        }
    }
}

/**
 * 渲染一个可选择的页签标题及其策略菜单。
 *
 * @param state 编辑器状态。
 * @param tabsBlock 所属 Tabs 容器。
 * @param tab 当前 tab Block。
 * @param selected 是否为会话活动页签。
 * @param default 是否为 Note 显式默认页签。
 */
@Composable
private fun TabHeader(
    state: TagloomEditorState,
    tabsBlock: Block,
    tab: Block,
    selected: Boolean,
    default: Boolean,
) {
    val content = tab.content as? TabBlockContent ?: return
    var menuOpen by remember(tab.id) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .widthIn(min = 104.dp, max = 220.dp)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
                else MaterialTheme.colorScheme.surface,
            )
            .clickable { state.selectTab(tabsBlock.id, tab.id) }
            .padding(start = 12.dp, top = 8.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selected) {
                BasicTextField(
                    value = content.title.plainText,
                    onValueChange = { value -> state.renameTab(tab.id, value) },
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.labelLarge.copy(
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    singleLine = true,
                )
            } else {
                Text(
                    content.title.plainText,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
            Box {
                TextButton(onClick = { menuOpen = true }) {
                    Text("···", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.editor_tabs_set_default)) },
                        onClick = {
                            state.setDefaultTab(tabsBlock.id, tab.id)
                            menuOpen = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.editor_tabs_delete_unpack)) },
                        onClick = {
                            state.deleteTab(tabsBlock.id, tab.id, cascade = false)
                            menuOpen = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.editor_tabs_delete_cascade)) },
                        onClick = {
                            state.deleteTab(tabsBlock.id, tab.id, cascade = true)
                            menuOpen = false
                        },
                    )
                }
            }
        }
        if (default) {
            Text(
                stringResource(Res.string.editor_tabs_default),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Box(
            Modifier
                .padding(end = 12.dp)
                .background(
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                )
                .padding(vertical = 1.dp),
        )
    }
}
