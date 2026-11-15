package com.oruke.tagloom.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.ai.AgentTokenMetricsCalculator
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.Res
import tagloom.app.shared.generated.resources.agent_dock_context_meter
import tagloom.app.shared.generated.resources.agent_dock_tokens_only
import tagloom.app.shared.generated.resources.agent_dock_tokens_with_cache
import tagloom.app.shared.generated.resources.ic_tokens

/**
 * 渲染作曲框正下方的对标 DeepSeek Harness 极简双触发器状态栏（`.composer-dock`）。
 *
 * - 左侧：`StatsPills` 会话累计计费 Token 与前缀缓存命中率触发器 + 明细浮层（压缩绝不清零）；
 * - 右侧：`14.dp` `ContextMeter` 环形进度表 + 三色分段窗口占用明细浮层（含 `80%` 自动压缩刻度线与「立即压缩」入口）。
 *
 * @param controller 工作区共享 Agent 控制器。
 * @param modifier 外部修饰符。
 */
@Composable
internal fun AgentComposerDock(
    controller: WorkspaceAgentHarnessController,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var usagePopoverOpen by remember { mutableStateOf(false) }
    var contextPopoverOpen by remember { mutableStateOf(false) }

    val usage = controller.currentUsageMetrics
    val meter = controller.currentContextMeterMetrics
    val prefs = controller.currentPreferences
    val canManualCompact = controller.selectedThreadEntry?.thread?.messages?.isNotEmpty() == true &&
        !controller.compactionBusy &&
        controller.runningThreadId == null

    val isDark = isSystemInDarkTheme()
    val amberWarningColor = if (isDark) Color(0xFFF0B443) else Color(0xFFC27800)
    val meterAccentColor = when {
        meter.overThreshold -> MaterialTheme.colorScheme.error
        meter.nearThreshold -> amberWarningColor
        else -> MaterialTheme.colorScheme.primary
    }
    val meterTextColor = when {
        meter.overThreshold -> MaterialTheme.colorScheme.error
        meter.nearThreshold -> amberWarningColor
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 3.dp, vertical = 2.dp)
            .testTag("agent-composer-dock"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box {
            val compactTotal = AgentTokenMetricsCalculator.formatCompactTokens(usage.totalTokens)
            val cacheHitPct = usage.cacheHitPercent
            val leftLabel = if (cacheHitPct != null && usage.cacheReadTokens > 0) {
                stringResource(Res.string.agent_dock_tokens_with_cache, compactTotal, cacheHitPct)
            } else {
                stringResource(Res.string.agent_dock_tokens_only, compactTotal)
            }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        usagePopoverOpen = !usagePopoverOpen
                        if (usagePopoverOpen) contextPopoverOpen = false
                    }
                    .padding(horizontal = 6.dp, vertical = 3.dp)
                    .testTag("agent-token-usage-trigger"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(
                    painter = painterResource(Res.drawable.ic_tokens),
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = leftLabel,
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AgentTokenUsagePopover(
                expanded = usagePopoverOpen,
                usage = usage,
                onDismiss = { usagePopoverOpen = false },
            )
        }

        Box {
            val compactUsed = AgentTokenMetricsCalculator.formatCompactTokens(meter.usedTokens)
            val compactWindow = AgentTokenMetricsCalculator.formatCompactTokens(meter.contextWindowTokens)
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        contextPopoverOpen = !contextPopoverOpen
                        if (contextPopoverOpen) usagePopoverOpen = false
                    }
                    .padding(horizontal = 6.dp, vertical = 3.dp)
                    .testTag("agent-context-meter-trigger"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                ContextMeterRingIcon(
                    percent = meter.percent,
                    ringColor = meterAccentColor,
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.size(14.dp).testTag("agent-context-meter-ring"),
                )
                Text(
                    text = stringResource(
                        Res.string.agent_dock_context_meter,
                        meter.percent,
                        compactUsed,
                        compactWindow,
                    ),
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    color = meterTextColor,
                )
            }

            AgentContextMeterPopover(
                expanded = contextPopoverOpen,
                meter = meter,
                autoCompactEnabled = prefs.autoCompactEnabled,
                canManualCompact = canManualCompact,
                onManualCompact = {
                    scope.launch {
                        if (controller.compactSelectedThreadManually()) {
                            contextPopoverOpen = false
                        }
                    }
                },
                onDismiss = { contextPopoverOpen = false },
            )
        }
    }
}

/**
 * 渲染 `14.dp` 上下文占用环形进度图标（起点 `-90deg` 十二点钟方向，平滑圆角描边）。
 */
@Composable
private fun ContextMeterRingIcon(
    percent: Int,
    ringColor: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val strokePx = 2.dp.toPx()
        val inset = strokePx / 2f
        val arcSize = Size(
            width = (size.width - strokePx).coerceAtLeast(1f),
            height = (size.height - strokePx).coerceAtLeast(1f),
        )
        drawArc(
            color = trackColor,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = strokePx),
        )
        val sweep = (percent.coerceIn(0, 100) / 100f) * 360f
        if (sweep > 0.5f) {
            drawArc(
                color = ringColor,
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )
        }
    }
}
