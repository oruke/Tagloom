package com.oruke.tagloom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.ai.AgentContextMeterMetrics
import com.oruke.tagloom.core.ai.AgentTokenMetricsCalculator
import com.oruke.tagloom.core.ai.AgentTokenUsageMetrics
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.Res
import tagloom.app.shared.generated.resources.agent_context_action_compact_now
import tagloom.app.shared.generated.resources.agent_context_auto_line_label
import tagloom.app.shared.generated.resources.agent_context_auto_line_off
import tagloom.app.shared.generated.resources.agent_context_auto_line_on
import tagloom.app.shared.generated.resources.agent_context_popover_sub
import tagloom.app.shared.generated.resources.agent_context_popover_title
import tagloom.app.shared.generated.resources.agent_context_seg_messages
import tagloom.app.shared.generated.resources.agent_context_seg_system
import tagloom.app.shared.generated.resources.agent_context_seg_tools
import tagloom.app.shared.generated.resources.agent_usage_popover_cache_rate
import tagloom.app.shared.generated.resources.agent_usage_popover_cache_read
import tagloom.app.shared.generated.resources.agent_usage_popover_footer
import tagloom.app.shared.generated.resources.agent_usage_popover_output
import tagloom.app.shared.generated.resources.agent_usage_popover_output_with_reasoning
import tagloom.app.shared.generated.resources.agent_usage_popover_title
import tagloom.app.shared.generated.resources.agent_usage_popover_uncached_input
import tagloom.app.shared.generated.resources.ic_compact

/**
 * 渲染左侧 `StatsPills` 点击弹出的会话累计计费 Token 用量明细浮层。
 *
 * @param expanded 浮层是否展开。
 * @param usage 当前会话累计计费 Token 与缓存命中率视图。
 * @param onDismiss 关闭浮层回调。
 */
@Composable
internal fun AgentTokenUsagePopover(
    expanded: Boolean,
    usage: AgentTokenUsageMetrics,
    onDismiss: () -> Unit,
) {
    TagloomDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .width(296.dp)
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .testTag("popover-token-usage"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.agent_usage_popover_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${AgentTokenMetricsCalculator.formatExactTokens(usage.totalTokens)} tok",
                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            val cacheRateValue = usage.cacheHitPercent?.let { "$it%" } ?: "—"
            val outputText = if (usage.reasoningTokens > 0) {
                stringResource(
                    Res.string.agent_usage_popover_output_with_reasoning,
                    AgentTokenMetricsCalculator.formatExactTokens(usage.outputTokens),
                    AgentTokenMetricsCalculator.formatExactTokens(usage.reasoningTokens),
                )
            } else {
                AgentTokenMetricsCalculator.formatExactTokens(usage.outputTokens)
            }

            MeterDetailRow(stringResource(Res.string.agent_usage_popover_cache_rate), cacheRateValue)
            MeterDetailRow(
                stringResource(Res.string.agent_usage_popover_uncached_input),
                AgentTokenMetricsCalculator.formatExactTokens(usage.uncachedInputTokens),
            )
            MeterDetailRow(
                stringResource(Res.string.agent_usage_popover_cache_read),
                AgentTokenMetricsCalculator.formatExactTokens(usage.cacheReadTokens),
            )
            MeterDetailRow(stringResource(Res.string.agent_usage_popover_output), outputText)

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = stringResource(Res.string.agent_usage_popover_footer),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 渲染右侧 `ContextMeter` 点击弹出的上下文窗口三色分段明细浮层（含 `80%` 自动压缩刻度线与手动压缩动作）。
 *
 * @param expanded 浮层是否展开。
 * @param meter 当前上下文窗口三色分段占用与阈值水位视图。
 * @param autoCompactEnabled 工作区是否启用了自动压缩。
 * @param canManualCompact 当前会话是否满足手动压缩前置条件。
 * @param onManualCompact 点击「立即压缩」回调。
 * @param onDismiss 关闭浮层回调。
 */
@Composable
internal fun AgentContextMeterPopover(
    expanded: Boolean,
    meter: AgentContextMeterMetrics,
    autoCompactEnabled: Boolean,
    canManualCompact: Boolean,
    onManualCompact: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isDark = isSystemInDarkTheme()
    val segSysColor = if (isDark) Color(0xFF7EA39C) else Color(0xFF607873)
    val segToolsColor = if (isDark) Color(0xFF4ECBB9) else Color(0xFF007D6E)
    val segMsgColor = if (isDark) Color(0xFF7CAAF7) else Color(0xFF2C66B8)

    TagloomDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .width(312.dp)
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .testTag("popover-context-meter"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.agent_context_popover_title, meter.percent),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(
                        Res.string.agent_context_popover_sub,
                        AgentTokenMetricsCalculator.formatCompactTokens(meter.usedTokens),
                        AgentTokenMetricsCalculator.formatCompactTokens(meter.contextWindowTokens),
                    ),
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            ContextThreeSegmentBar(
                meter = meter,
                sysColor = segSysColor,
                toolsColor = segToolsColor,
                msgColor = segMsgColor,
            )

            MeterLegendRow(
                swatchColor = segSysColor,
                label = stringResource(Res.string.agent_context_seg_system),
                value = "~${AgentTokenMetricsCalculator.formatCompactTokens(meter.systemTokens)}",
            )
            MeterLegendRow(
                swatchColor = segToolsColor,
                label = stringResource(Res.string.agent_context_seg_tools),
                value = "~${AgentTokenMetricsCalculator.formatCompactTokens(meter.toolsTokens)}",
            )
            MeterLegendRow(
                swatchColor = segMsgColor,
                label = stringResource(Res.string.agent_context_seg_messages),
                value = "~${AgentTokenMetricsCalculator.formatCompactTokens(meter.messageTokens)}",
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val thresholdCompact = AgentTokenMetricsCalculator.formatCompactTokens(meter.thresholdTokens)
            val autoStatus = if (autoCompactEnabled) {
                stringResource(Res.string.agent_context_auto_line_on, thresholdCompact)
            } else {
                stringResource(Res.string.agent_context_auto_line_off, thresholdCompact)
            }
            MeterDetailRow(
                label = stringResource(Res.string.agent_context_auto_line_label, meter.thresholdPercent),
                value = autoStatus,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onManualCompact,
                    enabled = canManualCompact,
                    modifier = Modifier.testTag("btn-manual-compact"),
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_compact),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = stringResource(Res.string.agent_context_action_compact_now),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

/**
 * 渲染带 `thresholdPercent%` 自动压缩红色刻度线的三色分段进度条（单遍权重布局，避免弹层内在尺寸死循环）。
 */
@Composable
private fun ContextThreeSegmentBar(
    meter: AgentContextMeterMetrics,
    sysColor: Color,
    toolsColor: Color,
    msgColor: Color,
) {
    val window = meter.contextWindowTokens.coerceAtLeast(1).toFloat()
    val sysRatio = (meter.systemTokens / window).coerceIn(0f, 1f)
    val toolsRatio = (meter.toolsTokens / window).coerceIn(0f, (1f - sysRatio).coerceAtLeast(0f))
    val msgRatio = (meter.messageTokens / window).coerceIn(0f, (1f - sysRatio - toolsRatio).coerceAtLeast(0f))
    val remainRatio = (1f - sysRatio - toolsRatio - msgRatio).coerceAtLeast(0f)
    val tickFraction = (meter.thresholdPercent.coerceIn(50, 95) / 100f)
    val afterTickFraction = (1f - tickFraction).coerceAtLeast(0.01f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(12.dp)
            .testTag("context-meter-segmented-bar"),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            if (sysRatio >= 0.002f) {
                Box(Modifier.fillMaxHeight().weight(sysRatio).background(sysColor))
            }
            if (toolsRatio >= 0.002f) {
                Box(Modifier.fillMaxHeight().weight(toolsRatio).background(toolsColor))
            }
            if (msgRatio >= 0.002f) {
                Box(Modifier.fillMaxHeight().weight(msgRatio).background(msgColor))
            }
            if (remainRatio >= 0.002f) {
                Spacer(Modifier.fillMaxHeight().weight(remainRatio))
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(tickFraction))
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(12.dp)
                    .background(MaterialTheme.colorScheme.error, RoundedCornerShape(1.dp))
                    .testTag("context-meter-threshold-tick"),
            )
            Spacer(Modifier.weight(afterTickFraction))
        }
    }
}

/**
 * 渲染带色块图例的单行分段统计项。
 */
@Composable
private fun MeterLegendRow(
    swatchColor: Color,
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(8.dp).background(swatchColor, RoundedCornerShape(2.dp)))
            Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        }
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 渲染累计账单或自动压缩线单行键值明细。
 */
@Composable
private fun MeterDetailRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
