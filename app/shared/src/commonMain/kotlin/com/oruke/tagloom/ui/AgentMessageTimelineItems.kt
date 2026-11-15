package com.oruke.tagloom.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.ai.AgentCompactionItem
import com.oruke.tagloom.core.ai.AgentThreadMessage
import com.oruke.tagloom.core.ai.AgentTokenMetricsCalculator
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.Res
import tagloom.app.shared.generated.resources.agent_compaction_active_badge
import tagloom.app.shared.generated.resources.agent_compaction_auto_subtitle
import tagloom.app.shared.generated.resources.agent_compaction_auto_title
import tagloom.app.shared.generated.resources.agent_compaction_manual_subtitle
import tagloom.app.shared.generated.resources.agent_compaction_manual_title
import tagloom.app.shared.generated.resources.ic_compact

/**
 * 渲染右对齐的用户提问气泡及其冻结 `@` 引用标签行。
 *
 * @param message 用户提问消息对象。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UserMessageBubble(message: AgentThreadMessage) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag("agent-user-msg-${message.id}"),
        horizontalAlignment = Alignment.End,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 560.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (message.contextRefs.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        message.contextRefs.forEach { ref ->
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                shape = MaterialTheme.shapes.extraSmall,
                                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                            ) {
                                Text(
                                    text = ref.label,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/**
 * 渲染插入在对话时间线压缩游标边界处的可展开/收起 `CompactionItem` 摘要卡片。
 *
 * 严格遵循原型 `.compaction-item` 虚线边框风格：默认单行展示触发阈值与释放 Token 数，点击展开可审阅保留脉络摘要正文。
 *
 * @param item 持久化的压缩摘要节点。
 * @param isActiveCursor 该节点是否为当前会话生效的最新活跃压缩游标。
 * @param modifier 外部修饰符。
 */
@Composable
internal fun AgentCompactionItemCard(
    item: AgentCompactionItem,
    isActiveCursor: Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(item.id) { mutableStateOf(false) }
    val dashedColor = MaterialTheme.colorScheme.outlineVariant
    val cornerDp = 8.dp

    val isAuto = item.triggerPercent > 0
    val titleText = stringResource(
        if (isAuto) Res.string.agent_compaction_auto_title else Res.string.agent_compaction_manual_title,
    )
    val freedFormatted = AgentTokenMetricsCalculator.formatCompactTokens(item.freedTokens)
    val subtitleText = if (isAuto) {
        stringResource(Res.string.agent_compaction_auto_subtitle, item.triggerPercent, freedFormatted)
    } else {
        stringResource(Res.string.agent_compaction_manual_subtitle, freedFormatted)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val strokeWidth = 1.dp.toPx()
                val radius = cornerDp.toPx()
                drawRoundRect(
                    color = dashedColor,
                    cornerRadius = CornerRadius(radius, radius),
                    style = Stroke(
                        width = strokeWidth,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()), 0f),
                    ),
                )
            }
            .testTag("agent-compaction-card-${item.id}"),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = RoundedCornerShape(cornerDp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 9.dp)
                    .testTag("agent-compaction-toggle-${item.id}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    painter = painterResource(Res.drawable.ic_compact),
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = titleText,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (isActiveCursor) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                shape = MaterialTheme.shapes.extraSmall,
                            ) {
                                Text(
                                    text = stringResource(Res.string.agent_compaction_active_badge),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                    Text(
                        text = subtitleText,
                        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = if (expanded) "▴" else "▾",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
                Text(
                    text = item.summary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                        .testTag("agent-compaction-body-${item.id}"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
