package com.oruke.tagloom.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import com.oruke.tagloom.editor.search.NoteSearchTarget
import com.oruke.tagloom.ui.search.rememberNoteSearchDecoration
import com.oruke.tagloom.ui.search.NoteSearchTextViewport

/**
 * 渲染表格中的紧凑文本输入，查找颜色仅叠加到显示文本。
 * @param value 当前单元格或列标题原文。
 * @param onValueChange 原文编辑回调，不包含查找样式。
 * @param placeholder 空值提示。
 * @param enabled 是否允许输入。
 * @param modifier 外部布局修饰符。
 * @param fontWeight 可选字重。
 * @param searchTarget 当前列标题或单元格的查找定位。
 */
@Composable
internal fun BaseTableTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    searchTarget: NoteSearchTarget? = null,
) {
    val search = rememberNoteSearchDecoration(searchTarget)
    NoteSearchTextViewport(modifier) { fieldModifier ->
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            modifier = fieldModifier.then(search.modifier),
            visualTransformation = search.transformation(),
            onTextLayout = search.onTextLayout,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = fontWeight,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            singleLine = true,
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(
                            placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    inner()
                }
            },
        )
    }
}
