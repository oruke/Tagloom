package com.oruke.tagloom.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.editor.TagloomEditorState
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 在目标不可用时重新选择已存在笔记，保持当前引用块身份和其他来源引用不变。
 * @param state 已解锁笔记目录。
 * @param block 当前不可用引用。
 */
@Composable
internal fun NoteReferenceTargetPicker(state: TagloomEditorState, block: Block) {
    var open by remember(block.id) { mutableStateOf(false) }
    var query by remember(block.id) { mutableStateOf("") }
    Box {
        TextButton(
            onClick = { open = true },
            modifier = Modifier.testTag("reference-reselect-${block.id.value}")
        ) { Text(stringResource(Res.string.link_reselect)) }
        TagloomDropdownMenu(open, { open = false; query = "" }) {
            PopoverSearchField(query, stringResource(Res.string.link_reselect_search), open) { query = it }
            state.documents.filter {
                query.isBlank() || it.note.title.contains(
                    query,
                    true
                ) || LinkNotePolicy.address(it.note)?.value?.contains(query, true) == true
            }.forEach { note ->
                TagloomMenuItem(note.note.title, onClick = {
                    state.updateBlock(block.id) { it.copy(content = NoteReferenceBlockContent(note.id)) }
                    open = false; query = ""
                })
            }
        }
    }
}
