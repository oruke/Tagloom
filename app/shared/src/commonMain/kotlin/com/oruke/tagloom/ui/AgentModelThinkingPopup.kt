package com.oruke.tagloom.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.ai.AiModelConfig
import com.oruke.tagloom.core.ai.AiModelToolsState
import com.oruke.tagloom.core.ai.AiThinkingEffort
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.Res
import tagloom.app.shared.generated.resources.agent_composer_model_unselected
import tagloom.app.shared.generated.resources.agent_composer_thinking_high
import tagloom.app.shared.generated.resources.agent_composer_thinking_low
import tagloom.app.shared.generated.resources.agent_composer_thinking_medium
import tagloom.app.shared.generated.resources.agent_composer_thinking_off
import tagloom.app.shared.generated.resources.agent_model_badge_chat
import tagloom.app.shared.generated.resources.agent_model_badge_tools
import tagloom.app.shared.generated.resources.agent_model_popup_thinking_label
import tagloom.app.shared.generated.resources.agent_model_popup_title

/**
 * 渲染无外框、无底色胶囊的「模型 · 思考 ▾」合一文本按钮及复合弹层选择器。
 *
 * 当模型仅支持 `OFF` 思考强度时，按钮文案自动省略思考后缀，仅展示 `模型名称 ▾`。
 */
@Composable
internal fun AgentModelThinkingSelector(
    enabledModels: List<AiModelConfig>,
    selectedModel: AiModelConfig?,
    thinkingEffort: AiThinkingEffort,
    onSelect: (String?, AiThinkingEffort) -> Unit,
    onOpenSettings: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val supportsThinkingChoice = selectedModel != null && selectedModel.supportedThinkingEfforts.any { it != AiThinkingEffort.OFF }
    val thinkingShort = thinkingEffortLabel(thinkingEffort)
    val buttonText = when {
        selectedModel == null -> stringResource(Res.string.agent_composer_model_unselected)
        supportsThinkingChoice && thinkingEffort != AiThinkingEffort.OFF ->
            "${selectedModel.displayName} · $thinkingShort"
        else -> selectedModel.displayName
    }

    Box(modifier) {
        Row(
            modifier = Modifier
                .clickable {
                    if (enabledModels.isEmpty() && onOpenSettings != null) {
                        onOpenSettings()
                    } else {
                        expanded = true
                    }
                }
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .testTag("agent-model-thinking-button"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = buttonText,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TagloomChevronDown()
        }

        TagloomDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            Column(Modifier.widthIn(min = 260.dp, max = 320.dp).padding(vertical = 4.dp)) {
                Text(
                    stringResource(Res.string.agent_model_popup_title),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                enabledModels.forEach { candidate ->
                    val isSelected = candidate.id == selectedModel?.id
                    val badgeLabel = if (candidate.toolsState == AiModelToolsState.CHAT_ONLY) {
                        stringResource(Res.string.agent_model_badge_chat)
                    } else {
                        stringResource(Res.string.agent_model_badge_tools)
                    }
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val nextEffort = coerceThinkingEffortForModel(thinkingEffort, candidate)
                                onSelect(candidate.id, nextEffort)
                            }
                            .testTag("agent-model-option-${candidate.id}"),
                        color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                        else MaterialTheme.colorScheme.surface,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (isSelected) "✓" else " ",
                                modifier = Modifier.width(18.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Column(Modifier.weight(1f)) {
                                Text(candidate.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${candidate.contextWindowTokens / 1000}k ctx",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                shape = MaterialTheme.shapes.extraSmall,
                            ) {
                                Text(
                                    badgeLabel,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                val targetModel = selectedModel ?: enabledModels.firstOrNull()
                val supportedEfforts = targetModel?.supportedThinkingEfforts.orEmpty()
                if (supportedEfforts.any { it != AiThinkingEffort.OFF }) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(
                            stringResource(Res.string.agent_model_popup_thinking_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            AiThinkingEffort.entries
                                .filter { it == AiThinkingEffort.OFF || it in supportedEfforts }
                                .forEach { effort ->
                                    val chosen = thinkingEffort == effort
                                    Surface(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable {
                                                onSelect(targetModel?.id, effort)
                                                expanded = false
                                            }
                                            .testTag("agent-thinking-${effort.name.lowercase()}"),
                                        color = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                                        contentColor = if (chosen) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                        shape = MaterialTheme.shapes.small,
                                    ) {
                                        Box(Modifier.padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                                            Text(thinkingEffortLabel(effort), style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun thinkingEffortLabel(effort: AiThinkingEffort): String = when (effort) {
    AiThinkingEffort.OFF -> stringResource(Res.string.agent_composer_thinking_off)
    AiThinkingEffort.LOW -> stringResource(Res.string.agent_composer_thinking_low)
    AiThinkingEffort.MEDIUM -> stringResource(Res.string.agent_composer_thinking_medium)
    AiThinkingEffort.HIGH -> stringResource(Res.string.agent_composer_thinking_high)
}
