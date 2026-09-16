package com.oruke.tagloom.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.editor.EditorDocument
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 在编辑器视口右下角渲染不可编辑的权威快照时间。
 *
 * @param document 当前笔记及其快照时间。
 * @param modifier 外部定位修饰符。
 */
@Composable
internal fun DocumentTimestamps(
    document: EditorDocument,
    modifier: Modifier = Modifier,
) {
    val dateTime = currentDateTimeSettings()
    val unsavedText = stringResource(Res.string.editor_timestamp_not_saved)
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Text(
            text = stringResource(
                Res.string.editor_timestamp_line,
                dateTime.format(document.createdAt).ifBlank { unsavedText },
                dateTime.format(document.updatedAt).ifBlank { unsavedText },
            ),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
