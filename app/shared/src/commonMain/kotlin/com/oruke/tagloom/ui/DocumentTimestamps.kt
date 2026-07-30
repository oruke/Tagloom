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
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Text(
            text = stringResource(
                Res.string.editor_timestamp_line,
                document.createdAt.toCompactUtcTimestamp(
                    stringResource(Res.string.editor_timestamp_not_saved),
                ),
                document.updatedAt.toCompactUtcTimestamp(
                    stringResource(Res.string.editor_timestamp_not_saved),
                ),
            ),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 把 ISO-8601 UTC 文本压缩为稳定的分钟级展示格式。
 *
 * 公共 UI 不引入平台日期依赖；无法解析的未来格式会原样保留，空值显示为调用方提供的
 * 已本地化未保存文本。
 *
 * @param unsavedText 当前语言中表示尚未保存的文本。
 * @return `YYYY-MM-DD HH:mm UTC` 或安全回退文本。
 */
internal fun String.toCompactUtcTimestamp(unsavedText: String): String {
    if (isBlank()) return unsavedText
    val match = COMPACT_UTC_PATTERN.find(this) ?: return this
    return "${match.groupValues[1]} ${match.groupValues[2]} UTC"
}

/** ISO 时间中日期与分钟部分的稳定提取规则。 */
private val COMPACT_UTC_PATTERN: Regex = Regex("^(\\d{4}-\\d{2}-\\d{2})T(\\d{2}:\\d{2})")
