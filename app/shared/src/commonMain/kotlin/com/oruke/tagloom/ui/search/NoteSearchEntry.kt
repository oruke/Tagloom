package com.oruke.tagloom.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.ui.PlatformUsesTouchTargets
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 用单一放大镜打开当前笔记查找，悬停或长按时说明操作与快捷键。
 * @param active 当前笔记的查找栏是否已打开。
 * @param onClick 打开或重新聚焦当前笔记的查找栏。
 * @param modifier 搜索入口的布局与浮层锚点修饰符。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NoteSearchEntry(active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val touch = PlatformUsesTouchTargets
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(stringResource(Res.string.editor_find_tooltip)) } },
        state = rememberTooltipState(),
    ) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides if (touch) 48.dp else 0.dp) {
            IconButton(
                onClick = onClick,
                modifier = modifier.size(if (touch) 48.dp else 36.dp).testTag("note-search-entry")
                    .then(if (active) Modifier.background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.small) else Modifier),
            ) {
                Icon(
                    painter = painterResource(Res.drawable.ic_search),
                    contentDescription = stringResource(Res.string.editor_find_action),
                    modifier = Modifier.size(18.dp),
                    tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
