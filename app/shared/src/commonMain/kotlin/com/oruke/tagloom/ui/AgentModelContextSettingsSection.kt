package com.oruke.tagloom.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.ai.AiConnectionPresets
import com.oruke.tagloom.core.ai.AiModelConfig
import com.oruke.tagloom.core.ai.AiModelToolsState
import com.oruke.tagloom.core.ai.AiProviderConnection
import com.oruke.tagloom.core.ai.AiThinkingEffort
import com.oruke.tagloom.platform.WorkspaceAgentConfigSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 渲染「2. 模型目录、上下文窗口与工具实测」卡片区块。
 *
 * 支持 `/models` 发现候选一键导入、每模型独立 `contextWindowTokens`（`16K..1M`）快捷档位与自定义数值、
 * 实时计算 `80%` 自动压缩水位线，以及一键发起零笔记最小工具调用实测。
 *
 * @param snapshot 当前脱敏配置与凭据就绪快照。
 * @param controller Agent 设置异步操作控制器。
 * @param coroutineScope 宿主会话协程作用域。
 */
@Composable
internal fun AgentModelContextSettingsSection(
    snapshot: WorkspaceAgentConfigSnapshot,
    controller: WorkspaceAgentSettingsController,
    coroutineScope: CoroutineScope,
) {
    val connections = snapshot.config.connections
    val models = snapshot.config.models
    val preferences = snapshot.config.preferences

    var formOpen by remember { mutableStateOf(false) }
    var selectedConnectionId by remember(connections) {
        mutableStateOf(connections.firstOrNull()?.connectionId.orEmpty())
    }
    var modelIdInput by remember { mutableStateOf("") }
    var displayNameInput by remember { mutableStateOf("") }
    var contextTokensInput by remember {
        mutableStateOf(AiModelConfig.DEFAULT_CONTEXT_WINDOW_TOKENS.toString())
    }
    var toolsStateInput by remember { mutableStateOf(AiModelToolsState.DECLARED) }
    var supportsThinkingInput by remember { mutableStateOf(false) }
    var enabledInput by remember { mutableStateOf(true) }

    fun populateForm(model: AiModelConfig) {
        selectedConnectionId = model.connectionId
        modelIdInput = model.modelId
        displayNameInput = model.displayName
        contextTokensInput = model.contextWindowTokens.toString()
        toolsStateInput = model.toolsState
        supportsThinkingInput = model.supportedThinkingEfforts.isNotEmpty()
        enabledInput = model.enabled
        formOpen = true
    }

    Surface(
        modifier = Modifier.fillMaxWidth().testTag("agent_models_section"),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(Res.string.agent_models_section_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (connections.isNotEmpty() && !formOpen && !snapshot.readOnly) {
                    OutlinedButton(
                        onClick = {
                            selectedConnectionId = connections.first().connectionId
                            modelIdInput = ""
                            displayNameInput = ""
                            contextTokensInput = AiModelConfig.DEFAULT_CONTEXT_WINDOW_TOKENS.toString()
                            toolsStateInput = AiModelToolsState.DECLARED
                            supportsThinkingInput = false
                            enabledInput = true
                            formOpen = true
                        },
                        modifier = Modifier.testTag("model_open_create"),
                    ) {
                        Text(stringResource(Res.string.agent_model_add))
                    }
                }
            }

            if (connections.isEmpty()) {
                Text(
                    stringResource(Res.string.agent_models_need_connection),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            val existingIds = models.map { it.id }.toSet()
            controller.discoveredModelsByConnection.forEach { (connId, discovered) ->
                val unimported = discovered.filter { remote ->
                    AiModelConfig.composeModelConfigId(connId, remote.modelId) !in existingIds
                }
                if (unimported.isNotEmpty()) {
                    Text(
                        stringResource(Res.string.agent_models_discovered_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        unimported.forEach { remote ->
                            AssistChip(
                                onClick = {
                                    coroutineScope.launch {
                                        controller.importDiscoveredModels(connId, listOf(remote))
                                    }
                                },
                                label = {
                                    Text(
                                        "${remote.displayName} (${AiConnectionPresets.formatTokenCount(remote.suggestedContextWindowTokens)})",
                                    )
                                },
                                modifier = Modifier.testTag("discovered_model_${connId}_${remote.modelId}"),
                            )
                        }
                    }
                }
            }

            if (models.isEmpty()) {
                Text(
                    stringResource(Res.string.agent_models_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val connectionMap = connections.associateBy { it.connectionId }
                models.forEach { model ->
                    AgentConfiguredModelRow(
                        model = model,
                        connection = connectionMap[model.connectionId],
                        autoCompactPercent = preferences.autoCompactPercent,
                        retainPercent = preferences.retainPercent,
                        readOnly = snapshot.readOnly,
                        busy = controller.busy || controller.probingModelId == model.id,
                        onSelectPresetWindow = { newTokens ->
                            coroutineScope.launch {
                                controller.saveModel(model.copy(contextWindowTokens = newTokens))
                            }
                        },
                        onProbeTools = {
                            coroutineScope.launch { controller.probeModelToolCalling(model.id) }
                        },
                        onToggleEnabled = { checked ->
                            coroutineScope.launch {
                                controller.saveModel(model.copy(enabled = checked))
                            }
                        },
                        onEdit = { populateForm(model) },
                        onDelete = {
                            coroutineScope.launch { controller.deleteModel(model.id) }
                        },
                    )
                }
            }

            if (formOpen && !snapshot.readOnly) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    stringResource(Res.string.agent_model_new_title),
                    style = MaterialTheme.typography.titleSmall,
                )

                Text(
                    stringResource(Res.string.agent_model_connection_label),
                    style = MaterialTheme.typography.labelMedium,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    connections.forEach { connection ->
                        FilterChip(
                            selected = selectedConnectionId == connection.connectionId,
                            onClick = { selectedConnectionId = connection.connectionId },
                            label = { Text(connection.name) },
                            modifier = Modifier.testTag("model_conn_chip_${connection.connectionId}"),
                        )
                    }
                }

                OutlinedTextField(
                    value = modelIdInput,
                    onValueChange = { next ->
                        modelIdInput = next
                        if (displayNameInput.isBlank()) {
                            supportsThinkingInput = AiConnectionPresets.inferSupportedThinkingEfforts(next).isNotEmpty()
                        }
                    },
                    label = { Text(stringResource(Res.string.agent_model_id_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("model_id_input"),
                )

                OutlinedTextField(
                    value = displayNameInput,
                    onValueChange = { displayNameInput = it },
                    label = { Text(stringResource(Res.string.agent_model_display_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("model_display_name_input"),
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AiModelConfig.PRESET_CONTEXT_WINDOWS.forEach { presetTokens ->
                        FilterChip(
                            selected = contextTokensInput.trim().toIntOrNull() == presetTokens,
                            onClick = { contextTokensInput = presetTokens.toString() },
                            label = { Text(AiConnectionPresets.formatTokenCount(presetTokens)) },
                            modifier = Modifier.testTag("model_form_ctx_$presetTokens"),
                        )
                    }
                }

                OutlinedTextField(
                    value = contextTokensInput,
                    onValueChange = { value -> contextTokensInput = value.filter { it.isDigit() } },
                    label = { Text(stringResource(Res.string.agent_model_context_tokens_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("model_context_tokens_input"),
                )

                val parsedWindow = contextTokensInput.toIntOrNull()
                    ?.coerceIn(AiModelConfig.MIN_CONTEXT_WINDOW_TOKENS, AiModelConfig.MAX_CONTEXT_WINDOW_TOKENS)
                    ?: AiModelConfig.DEFAULT_CONTEXT_WINDOW_TOKENS
                val previewCompactTokens = ((parsedWindow.toLong() * preferences.autoCompactPercent) / 100L).toInt()
                val previewRetainTokens = ((parsedWindow.toLong() * preferences.retainPercent) / 100L).toInt()
                Text(
                    text = stringResource(
                        Res.string.agent_model_compaction_summary,
                        AiConnectionPresets.formatTokenCount(parsedWindow),
                        preferences.autoCompactPercent,
                        AiConnectionPresets.formatTokenCount(previewCompactTokens),
                        AiConnectionPresets.formatTokenCount(previewRetainTokens),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("model_form_compaction_preview"),
                )

                Text(
                    stringResource(Res.string.agent_model_tools_label),
                    style = MaterialTheme.typography.labelMedium,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = toolsStateInput == AiModelToolsState.DECLARED || toolsStateInput == AiModelToolsState.VERIFIED,
                        onClick = { toolsStateInput = AiModelToolsState.DECLARED },
                        label = { Text(stringResource(Res.string.agent_tools_declared)) },
                        modifier = Modifier.testTag("model_tools_declared"),
                    )
                    FilterChip(
                        selected = toolsStateInput == AiModelToolsState.CHAT_ONLY,
                        onClick = { toolsStateInput = AiModelToolsState.CHAT_ONLY },
                        label = { Text(stringResource(Res.string.agent_tools_chat_only)) },
                        modifier = Modifier.testTag("model_tools_chat_only"),
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(
                        checked = supportsThinkingInput,
                        onCheckedChange = { supportsThinkingInput = it },
                        modifier = Modifier.testTag("model_thinking_checkbox"),
                    )
                    Text(stringResource(Res.string.agent_model_supports_thinking))
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val validTokens = contextTokensInput.toIntOrNull()
                    val canSave = !controller.busy &&
                        selectedConnectionId.isNotBlank() &&
                        modelIdInput.isNotBlank() &&
                        validTokens != null &&
                        validTokens in AiModelConfig.MIN_CONTEXT_WINDOW_TOKENS..AiModelConfig.MAX_CONTEXT_WINDOW_TOKENS
                    Button(
                        onClick = {
                            val trimmedModelId = modelIdInput.trim()
                            val model = AiModelConfig(
                                connectionId = selectedConnectionId,
                                modelId = trimmedModelId,
                                displayName = displayNameInput.trim().ifEmpty { trimmedModelId },
                                contextWindowTokens = requireNotNull(validTokens),
                                streamingSupported = true,
                                toolsState = toolsStateInput,
                                supportedThinkingEfforts = if (supportsThinkingInput) {
                                    listOf(AiThinkingEffort.LOW, AiThinkingEffort.MEDIUM, AiThinkingEffort.HIGH)
                                } else {
                                    emptyList()
                                },
                                enabled = enabledInput,
                            )
                            coroutineScope.launch {
                                controller.saveModel(model) { formOpen = false }
                            }
                        },
                        enabled = canSave,
                        modifier = Modifier.testTag("model_save_button"),
                    ) {
                        Text(stringResource(Res.string.agent_model_save))
                    }
                    TextButton(
                        onClick = { formOpen = false },
                        modifier = Modifier.testTag("model_cancel_button"),
                    ) {
                        Text(stringResource(Res.string.workspace_action_cancel))
                    }
                }
            }
        }
    }
}

/**
 * 渲染已保存的单个模型卡片，展示能力徽章、实时 80% 压缩线与快捷窗口切换筹码。
 */
@Composable
private fun AgentConfiguredModelRow(
    model: AiModelConfig,
    connection: AiProviderConnection?,
    autoCompactPercent: Int,
    retainPercent: Int,
    readOnly: Boolean,
    busy: Boolean,
    onSelectPresetWindow: (Int) -> Unit,
    onProbeTools: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val compactTokens = model.autoCompactThresholdTokens(autoCompactPercent)
    val retainTokens = ((model.contextWindowTokens.toLong() * retainPercent.coerceIn(1, 100)) / 100L).toInt()
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("model_row_${model.id}"),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(model.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = buildString {
                            append(model.modelId)
                            connection?.let { append(" · ").append(it.name) }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!readOnly) {
                    Switch(
                        checked = model.enabled,
                        onCheckedChange = onToggleEnabled,
                        enabled = !busy,
                        modifier = Modifier.testTag("model_enable_${model.id}"),
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (model.streamingSupported) {
                    AgentStatusBadge(text = stringResource(Res.string.agent_badge_streaming))
                }
                AgentStatusBadge(
                    text = when (model.toolsState) {
                        AiModelToolsState.VERIFIED -> stringResource(Res.string.agent_tools_verified)
                        AiModelToolsState.DECLARED -> stringResource(Res.string.agent_tools_declared)
                        AiModelToolsState.CHAT_ONLY -> stringResource(Res.string.agent_tools_chat_only)
                    },
                    highlighted = model.toolsState == AiModelToolsState.VERIFIED,
                    modifier = Modifier.testTag("model_tools_badge_${model.id}"),
                )
                if (model.supportedThinkingEfforts.isNotEmpty()) {
                    AgentStatusBadge(text = stringResource(Res.string.agent_badge_thinking))
                }
            }

            Text(
                text = stringResource(
                    Res.string.agent_model_compaction_summary,
                    AiConnectionPresets.formatTokenCount(model.contextWindowTokens),
                    autoCompactPercent,
                    AiConnectionPresets.formatTokenCount(compactTokens),
                    AiConnectionPresets.formatTokenCount(retainTokens),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("model_summary_${model.id}"),
            )

            if (!readOnly) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AiModelConfig.PRESET_CONTEXT_WINDOWS.forEach { presetTokens ->
                        FilterChip(
                            selected = model.contextWindowTokens == presetTokens,
                            onClick = {
                                if (model.contextWindowTokens != presetTokens) {
                                    onSelectPresetWindow(presetTokens)
                                }
                            },
                            enabled = !busy,
                            label = { Text(AiConnectionPresets.formatTokenCount(presetTokens)) },
                            modifier = Modifier.testTag("model_ctx_${model.id}_$presetTokens"),
                        )
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = onProbeTools,
                        enabled = !busy,
                        modifier = Modifier.testTag("model_probe_${model.id}"),
                    ) {
                        Text(stringResource(Res.string.agent_model_probe_tools))
                    }
                    TextButton(
                        onClick = onEdit,
                        enabled = !busy,
                        modifier = Modifier.testTag("model_edit_${model.id}"),
                    ) {
                        Text(stringResource(Res.string.agent_action_edit))
                    }
                    TextButton(
                        onClick = onDelete,
                        enabled = !busy,
                        modifier = Modifier.testTag("model_delete_${model.id}"),
                    ) {
                        Text(stringResource(Res.string.agent_action_delete))
                    }
                }
            }
        }
    }
}
