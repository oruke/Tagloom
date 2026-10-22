package com.oruke.tagloom.ui

import androidx.compose.runtime.*
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.editor.EditorCursorTarget
import com.oruke.tagloom.editor.TagloomEditorState
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 独立网址粘贴到空段落后，让用户保留普通文字链接或显式保存并引用。
 * @param state 当前编辑器。
 * @param blockId 收到粘贴的空段落。
 * @param url 本次独立网址。
 * @param onDismiss 保留现有文本并关闭提示。
 * @param onCursorRequest 保存后继续正文的焦点动作。
 */
@Composable
internal fun LinkPasteChoices(
    state: TagloomEditorState,
    blockId: BlockId,
    url: String,
    onDismiss: () -> Unit,
    onCursorRequest: (EditorCursorTarget) -> Unit
) {
    val controller = LocalWorkspaceLinks.current ?: return
    val scope = rememberCoroutineScope()
    val typeName = stringResource(Res.string.link_type)
    val groupName = stringResource(Res.string.link_group)
    TagloomDropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        TagloomMenuItem(stringResource(Res.string.link_paste_save), onClick = {
            onDismiss()
            state.updateBlockText(blockId, "")
            controller.begin(blockId, url)
            scope.launch { controller.submit(typeName, groupName)?.let(onCursorRequest) }
        })
        TagloomMenuItem(stringResource(Res.string.link_paste_plain), onClick = onDismiss)
    }
}
