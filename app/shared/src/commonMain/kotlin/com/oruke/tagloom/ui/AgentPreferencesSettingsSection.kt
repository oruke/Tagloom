package com.oruke.tagloom.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.ai.AiAgentPreferences
import com.oruke.tagloom.core.ai.AiRetrievalScope
import com.oruke.tagloom.platform.WorkspaceAgentConfigSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 渲染「3. 自动压缩与默认运行边界」卡片区块。
 *
 * 允许用户管理 80% 上下文自动压缩开关、触发比例与保留近期对话比例、默认模型与检索范围，
 * 以及单轮工具循环与笔记精读预算护栏。
 *
 * @param snapshot 当前脱敏配置与凭据就绪快照。
 * @param controller Agent 设置异步操作控制器。
 * @param coroutineScope 宿主会话协程作用域。
 */
@Composable
internal fun AgentPreferencesSettingsSection(
    snapshot: WorkspaceAgentConfigSnapshot,
    controller: WorkspaceAgentSettingsController,
    coroutineScope: CoroutineScope,
) {
    val prefs = snapshot.config.preferences
    val enabledModels = snapshot.config.models.filter { it.enabled }

    var autoCompactEnabled by remember(prefs) { mutableStateOf(prefs.autoCompactEnabled) }
    var autoCompactPercentInput by remember(prefs) { mutableStateOf(prefs.autoCompactPercent.toString()) }
    var retainPercentInput by remember(prefs) { mutableStateOf(prefs.retainPercent.toString()) }
    var defaultModelId by remember(prefs) { mutableStateOf(prefs.defaultModelId) }
    var defaultScope by remember(prefs) { mutableStateOf(prefs.defaultScope) }
    var maxToolRoundsInput by remember(prefs) { mutableStateOf(prefs.maxToolRounds.toString()) }
    var maxNotesInput by remember(prefs) { mutableStateOf(prefs.maxNotes.toString()) }
    var maxBlocksInput by remember(prefs) { mutableStateOf(prefs.maxBlocks.toString()) }

    val parsedAutoCompact = autoCompactPercentInput.toIntOrNull()
    val parsedRetain = retainPercentInput.toIntOrNull()
    val parsedToolRounds = maxToolRoundsInput.toIntOrNull()
    val parsedMaxNotes = maxNotesInput.toIntOrNull()
    val parsedMaxBlocks = maxBlocksInput.toIntOrNull()

    val isFormValid = parsedAutoCompact != null && parsedAutoCompact in 50..95 &&
        parsedRetain != null && parsedRetain in 8..40 && parsedRetain < parsedAutoCompact &&
        parsedToolRounds != null && parsedToolRounds in 1..16 &&
        parsedMaxNotes != null && parsedMaxNotes in 1..32 &&
        parsedMaxBlocks != null && parsedMaxBlocks in 4..128

    Surface(
        modifier = Modifier.fillMaxWidth().testTag("agent_preferences_section"),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(Res.string.agent_preferences_section_title),
                style = MaterialTheme.typography.titleMedium,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(Res.string.agent_pref_auto_compact_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(Res.string.agent_pref_auto_compact_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = autoCompactEnabled,
                    onCheckedChange = { autoCompactEnabled = it },
                    enabled = !snapshot.readOnly && !controller.busy,
                    modifier = Modifier.testTag("pref_auto_compact_switch"),
                )
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(70, 80, 85, 90).forEach { presetPercent ->
                    FilterChip(
                        selected = parsedAutoCompact == presetPercent,
                        onClick = { autoCompactPercentInput = presetPercent.toString() },
                        enabled = !snapshot.readOnly && !controller.busy,
                        label = { Text("${presetPercent}%") },
                        modifier = Modifier.testTag("pref_compact_chip_$presetPercent"),
                    )
                }
            }

            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val compactLayout = maxWidth < 560.dp
                if (compactLayout) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedTextField(
                            value = autoCompactPercentInput,
                            onValueChange = { value -> autoCompactPercentInput = value.filter { it.isDigit() } },
                            label = { Text(stringResource(Res.string.agent_pref_auto_compact_percent_label)) },
                            enabled = !snapshot.readOnly,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("pref_auto_compact_percent_input"),
                        )
                        OutlinedTextField(
                            value = retainPercentInput,
                            onValueChange = { value -> retainPercentInput = value.filter { it.isDigit() } },
                            label = { Text(stringResource(Res.string.agent_pref_retain_percent_label)) },
                            enabled = !snapshot.readOnly,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("pref_retain_percent_input"),
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedTextField(
                            value = autoCompactPercentInput,
                            onValueChange = { value -> autoCompactPercentInput = value.filter { it.isDigit() } },
                            label = { Text(stringResource(Res.string.agent_pref_auto_compact_percent_label)) },
                            enabled = !snapshot.readOnly,
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("pref_auto_compact_percent_input"),
                        )
                        OutlinedTextField(
                            value = retainPercentInput,
                            onValueChange = { value -> retainPercentInput = value.filter { it.isDigit() } },
                            label = { Text(stringResource(Res.string.agent_pref_retain_percent_label)) },
                            enabled = !snapshot.readOnly,
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("pref_retain_percent_input"),
                        )
                    }
                }
            }

            if (enabledModels.isNotEmpty()) {
                Text(
                    stringResource(Res.string.agent_pref_default_model_label),
                    style = MaterialTheme.typography.labelMedium,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = defaultModelId == null,
                        onClick = { defaultModelId = null },
                        enabled = !snapshot.readOnly,
                        label = { Text(stringResource(Res.string.agent_pref_default_model_none)) },
                        modifier = Modifier.testTag("pref_default_model_auto"),
                    )
                    enabledModels.forEach { model ->
                        FilterChip(
                            selected = defaultModelId == model.id,
                            onClick = { defaultModelId = model.id },
                            enabled = !snapshot.readOnly,
                            label = { Text(model.displayName) },
                            modifier = Modifier.testTag("pref_default_model_${model.id}"),
                        )
                    }
                }
            }

            Text(
                stringResource(Res.string.agent_pref_default_scope_label),
                style = MaterialTheme.typography.labelMedium,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = defaultScope == AiRetrievalScope.CITED_ONLY,
                    onClick = { defaultScope = AiRetrievalScope.CITED_ONLY },
                    enabled = !snapshot.readOnly,
                    label = { Text(stringResource(Res.string.agent_scope_referenced_only)) },
                    modifier = Modifier.testTag("pref_scope_cited_only"),
                )
                FilterChip(
                    selected = defaultScope == AiRetrievalScope.ALL_NOTES,
                    onClick = { defaultScope = AiRetrievalScope.ALL_NOTES },
                    enabled = !snapshot.readOnly,
                    label = { Text(stringResource(Res.string.agent_scope_all_notes)) },
                    modifier = Modifier.testTag("pref_scope_all_notes"),
                )
            }

            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val compactLayout = maxWidth < 560.dp
                if (compactLayout) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedTextField(
                            value = maxToolRoundsInput,
                            onValueChange = { value -> maxToolRoundsInput = value.filter { it.isDigit() } },
                            label = { Text(stringResource(Res.string.agent_pref_max_tool_rounds_label)) },
                            enabled = !snapshot.readOnly,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("pref_max_tool_rounds_input"),
                        )
                        OutlinedTextField(
                            value = maxNotesInput,
                            onValueChange = { value -> maxNotesInput = value.filter { it.isDigit() } },
                            label = { Text(stringResource(Res.string.agent_pref_max_notes_label)) },
                            enabled = !snapshot.readOnly,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("pref_max_notes_input"),
                        )
                        OutlinedTextField(
                            value = maxBlocksInput,
                            onValueChange = { value -> maxBlocksInput = value.filter { it.isDigit() } },
                            label = { Text(stringResource(Res.string.agent_pref_max_blocks_label)) },
                            enabled = !snapshot.readOnly,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("pref_max_blocks_input"),
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedTextField(
                            value = maxToolRoundsInput,
                            onValueChange = { value -> maxToolRoundsInput = value.filter { it.isDigit() } },
                            label = { Text(stringResource(Res.string.agent_pref_max_tool_rounds_label)) },
                            enabled = !snapshot.readOnly,
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("pref_max_tool_rounds_input"),
                        )
                        OutlinedTextField(
                            value = maxNotesInput,
                            onValueChange = { value -> maxNotesInput = value.filter { it.isDigit() } },
                            label = { Text(stringResource(Res.string.agent_pref_max_notes_label)) },
                            enabled = !snapshot.readOnly,
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("pref_max_notes_input"),
                        )
                        OutlinedTextField(
                            value = maxBlocksInput,
                            onValueChange = { value -> maxBlocksInput = value.filter { it.isDigit() } },
                            label = { Text(stringResource(Res.string.agent_pref_max_blocks_label)) },
                            enabled = !snapshot.readOnly,
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("pref_max_blocks_input"),
                        )
                    }
                }
            }

            if (!snapshot.readOnly) {
                Button(
                    onClick = {
                        val updated = AiAgentPreferences(
                            defaultModelId = defaultModelId,
                            defaultScope = defaultScope,
                            maxToolRounds = requireNotNull(parsedToolRounds),
                            maxNotes = requireNotNull(parsedMaxNotes),
                            maxBlocks = requireNotNull(parsedMaxBlocks),
                            maxCharacters = prefs.maxCharacters,
                            autoCompactEnabled = autoCompactEnabled,
                            autoCompactPercent = requireNotNull(parsedAutoCompact),
                            retainPercent = requireNotNull(parsedRetain),
                        )
                        coroutineScope.launch { controller.savePreferences(updated) }
                    },
                    enabled = !controller.busy && isFormValid,
                    modifier = Modifier.testTag("pref_save_button"),
                ) {
                    Text(stringResource(Res.string.agent_pref_save))
                }
            }
        }
    }
}
