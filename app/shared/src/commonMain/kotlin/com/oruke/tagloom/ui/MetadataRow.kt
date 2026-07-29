package com.oruke.tagloom.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 渲染统一的元数据名称和值行。
 *
 * @param label 左侧稳定名称。
 * @param modifier 外部布局修饰符。
 * @param content 与名称对齐的值和交互。
 */
@Composable
internal fun MetadataRow(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth().defaultMinSize(minHeight = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.width(METADATA_LABEL_WIDTH).padding(end = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            content()
        }
    }
}

/** 元数据名称列的固定宽度，确保标签与所有属性值垂直对齐。 */
private val METADATA_LABEL_WIDTH = 112.dp
