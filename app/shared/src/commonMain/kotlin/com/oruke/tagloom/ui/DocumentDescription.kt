package com.oruke.tagloom.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.editor.search.NoteSearchField
import com.oruke.tagloom.editor.search.NoteSearchTarget
import com.oruke.tagloom.ui.search.rememberNoteSearchDecoration
import com.oruke.tagloom.ui.search.LocalNoteSearch
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import com.oruke.tagloom.editor.EditorDocument
import com.oruke.tagloom.editor.TagloomEditorState
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 渲染身份行下方无字段标签、无卡片边界的内置描述输入。
 *
 * @param state 当前编辑器状态。
 * @param document 当前笔记。
 */
@Composable
internal fun DocumentDescription(
    state: TagloomEditorState,
    document: EditorDocument,
) {
    val propertyId = WorkspaceDefaults.DEFAULT_TEXT_PROPERTY_ID
    val value = state.propertyEditor.stringValue(document.note, propertyId)
    val find = rememberNoteSearchDecoration(NoteSearchTarget(NoteSearchField.DESCRIPTION))
    // 查找时展开描述，由正文列表直接定位字符；平时仍保持三行上限，避免嵌套纵向滚动互相抢占。
    BasicTextField(
        value = value,
        visualTransformation = find.transformation(),
        onTextLayout = find.onTextLayout,
        onValueChange = { next -> state.propertyEditor.updateText(propertyId, next) },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).then(find.modifier).testTag("note-description-input"),
        minLines = 1,
        maxLines = if (LocalNoteSearch.current?.isOpen == true) Int.MAX_VALUE else 3,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { innerTextField ->
            Box {
                if (value.isBlank()) {
                    Text(
                        text = stringResource(Res.string.editor_document_description_placeholder),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                innerTextField()
            }
        },
    )
}
