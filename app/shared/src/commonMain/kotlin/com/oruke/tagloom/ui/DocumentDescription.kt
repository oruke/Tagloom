package com.oruke.tagloom.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import com.oruke.tagloom.editor.EditorDocument
import com.oruke.tagloom.editor.TagloomEditorState

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
    BasicTextField(
        value = value,
        onValueChange = { next -> state.propertyEditor.updateText(propertyId, next) },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        minLines = 1,
        maxLines = 3,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { innerTextField ->
            Box {
                if (value.isBlank()) {
                    Text(
                        text = "添加一句描述…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                innerTextField()
            }
        },
    )
}
