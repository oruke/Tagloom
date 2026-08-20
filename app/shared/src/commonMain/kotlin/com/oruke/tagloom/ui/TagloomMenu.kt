package com.oruke.tagloom.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties

/**
 * Stitch 设计系统统一定制的 Tagloom 下拉菜单容器。
 *
 * 规范要求：
 * - 8dp 圆角；
 * - 1dp `outlineVariant.copy(alpha = 0.65f)` 边框；
 * - 纯净 Surface 背景色；
 * - `tonalElevation = 0.dp`, `shadowElevation = 8.dp`；
 * - 内衬 4dp 边距。
 *
 * @param expanded 菜单是否展开。
 * @param onDismissRequest 关闭菜单回调。
 * @param modifier 外部布局修饰符。
 * @param offset 弹出位置偏移。
 * @param scrollState 滚动状态。
 * @param properties 弹窗属性。
 * @param content 菜单子项内容。
 */
@Composable
fun TagloomDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    scrollState: ScrollState = rememberScrollState(),
    properties: PopupProperties = PopupProperties(focusable = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = offset,
        scrollState = scrollState,
        properties = properties,
        shape = RoundedCornerShape(8.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            content()
        }
    }
}

/**
 * Stitch 设计系统统一定制的 Tagloom 菜单项。
 *
 * 支持特性：
 * - 单行标准项（高度 34dp）；
 * - 双行说明项（高度 48dp，通过 [description] 触发）；
 * - 前置图标 / 符号（18~20dp 居中对齐）；
 * - 后置快捷键提示文本 [trailingText] 与后置图标 [trailingIcon]；
 * - 激活选中状态 [selected]；
 * - 危险警示动作 [destructive]（红色警示色与警示悬停底色）；
 * - 悬停动效与禁用状态 [enabled]。
 *
 * @param text 菜单主标题文本。
 * @param onClick 点击回调。
 * @param modifier 外部布局修饰符。
 * @param description 菜单副标题/用途说明（非空时渲染为 48dp 双行卡片）。
 * @param leadingIcon 前置图标组件。
 * @param trailingText 后置快捷键或辅助文本。
 * @param trailingIcon 后置图标组件。
 * @param selected 是否为选中/激活状态。
 * @param destructive 是否为危险动作（如删除、清空等）。
 * @param enabled 是否可交互。
 */
@Composable
fun TagloomMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingText: String? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    selected: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    val backgroundColor = when {
        !enabled -> Color.Transparent
        destructive -> if (hovered) MaterialTheme.colorScheme.error.copy(alpha = 0.08f) else Color.Transparent
        selected -> if (hovered) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f)
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        }
        hovered -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
        else -> Color.Transparent
    }

    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.outline
        destructive -> MaterialTheme.colorScheme.error
        selected -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }

    val itemHeight = if (description.isNullOrBlank()) 34.dp else 48.dp

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(itemHeight)
            .clip(RoundedCornerShape(4.dp))
            .background(backgroundColor)
            .hoverable(interactionSource = interactionSource, enabled = enabled)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (leadingIcon != null) {
            Box(
                modifier = Modifier.size(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                leadingIcon()
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailingText != null) {
            Text(
                text = trailingText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (trailingIcon != null) {
            Box(
                modifier = Modifier.size(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                trailingIcon()
            }
        }
    }
}

/**
 * Stitch 设计系统统一定制的自定义内容菜单项。
 *
 * @param onClick 点击回调。
 * @param modifier 外部布局修饰符。
 * @param leadingIcon 前置图标组件。
 * @param trailingText 后置快捷键或辅助文本。
 * @param trailingIcon 后置图标组件。
 * @param selected 是否为选中/激活状态。
 * @param destructive 是否为危险动作。
 * @param enabled 是否可交互。
 * @param height 菜单项高度。
 * @param content 主体内容组件。
 */
@Composable
fun TagloomMenuItem(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingText: String? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    selected: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
    height: Dp = 34.dp,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    val backgroundColor = when {
        !enabled -> Color.Transparent
        destructive -> if (hovered) MaterialTheme.colorScheme.error.copy(alpha = 0.08f) else Color.Transparent
        selected -> if (hovered) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f)
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        }
        hovered -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
        else -> Color.Transparent
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(4.dp))
            .background(backgroundColor)
            .hoverable(interactionSource = interactionSource, enabled = enabled)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (leadingIcon != null) {
            Box(
                modifier = Modifier.size(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                leadingIcon()
            }
        }
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart,
        ) {
            content()
        }
        if (trailingText != null) {
            Text(
                text = trailingText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (trailingIcon != null) {
            Box(
                modifier = Modifier.size(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                trailingIcon()
            }
        }
    }
}

/**
 * Stitch 设计系统统一定制的 1dp 菜单分割线。
 *
 * @param modifier 外部布局修饰符。
 */
@Composable
fun TagloomMenuDivider(
    modifier: Modifier = Modifier,
) {
    HorizontalDivider(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

/**
 * Stitch 设计系统统一定制的分组小标题。
 *
 * @param title 分组标题文本。
 * @param modifier 外部布局修饰符。
 */
@Composable
fun TagloomMenuSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
