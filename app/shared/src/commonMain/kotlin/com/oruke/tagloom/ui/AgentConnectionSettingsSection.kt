package com.oruke.tagloom.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.ai.AiAuthMode
import com.oruke.tagloom.core.ai.AiConnectionPresetKind
import com.oruke.tagloom.core.ai.AiConnectionPresets
import com.oruke.tagloom.core.ai.AiProtocolKind
import com.oruke.tagloom.core.ai.AiProviderConnection
import com.oruke.tagloom.platform.WorkspaceAgentConfigSnapshot
import com.oruke.tagloom.platform.WorkspaceAgentCredentialStatus
import com.oruke.tagloom.platform.WorkspaceConnectionSaveDraft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 渲染「1. API 连接与密钥分层存储」卡片区块，支持四类快捷预设、分层凭据保护与实时连通性校验。
 *
 * @param snapshot 当前脱敏配置与凭据就绪快照。
 * @param controller Agent 设置异步操作控制器。
 * @param coroutineScope 宿主会话协程作用域。
 */
@Composable
internal fun AgentConnectionSettingsSection(
    snapshot: WorkspaceAgentConfigSnapshot,
    controller: WorkspaceAgentSettingsController,
    coroutineScope: CoroutineScope,
) {
    val connections = snapshot.config.connections
    var formExpanded by remember(connections.isEmpty()) { mutableStateOf(connections.isEmpty()) }
    var editingConnectionId by remember { mutableStateOf<String?>(null) }
    var nameInput by remember { mutableStateOf("OpenAI") }
    var baseUrlInput by remember { mutableStateOf("https://api.openai.com/v1") }
    var protocolInput by remember { mutableStateOf(AiProtocolKind.OPENAI_RESPONSES) }
    var authModeInput by remember { mutableStateOf(AiAuthMode.KEYSTORE) }
    var envVarNameInput by remember { mutableStateOf("OPENAI_API_KEY") }
    var syncEnvelopeInput by remember { mutableStateOf(false) }
    var apiKeyInput by remember { mutableStateOf("") }

    fun applyPreset(kind: AiConnectionPresetKind) {
        val preset = AiConnectionPresets.templateFor(kind)
        editingConnectionId = null
        nameInput = preset.defaultName
        baseUrlInput = preset.defaultBaseUrl
        protocolInput = preset.protocol
        authModeInput = preset.authMode
        envVarNameInput = preset.suggestedEnvVarName.orEmpty()
        syncEnvelopeInput = false
        apiKeyInput = ""
    }

    fun startEdit(connection: AiProviderConnection) {
        editingConnectionId = connection.connectionId
        nameInput = connection.name
        baseUrlInput = connection.baseUrl
        protocolInput = connection.protocol
        authModeInput = connection.authMode
        envVarNameInput = connection.envVarName.orEmpty()
        syncEnvelopeInput = connection.syncKeyInEnvelope
        apiKeyInput = ""
        formExpanded = true
    }

    fun currentDraft(): WorkspaceConnectionSaveDraft = WorkspaceConnectionSaveDraft(
        connectionId = editingConnectionId,
        name = nameInput.trim(),
        baseUrl = baseUrlInput.trim(),
        protocol = protocolInput,
        authMode = authModeInput,
        envVarName = envVarNameInput.trim().takeIf { authModeInput == AiAuthMode.ENV_VAR && it.isNotEmpty() },
        syncKeyInEnvelope = authModeInput == AiAuthMode.KEYSTORE && syncEnvelopeInput,
        newApiKeyInput = if (authModeInput == AiAuthMode.KEYSTORE) apiKeyInput else "",
    )

    Surface(
        modifier = Modifier.fillMaxWidth().testTag("agent_connections_section"),
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
                    stringResource(Res.string.agent_connections_section_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (!formExpanded && !snapshot.readOnly) {
                    OutlinedButton(
                        onClick = {
                            applyPreset(AiConnectionPresetKind.OPENAI)
                            formExpanded = true
                        },
                        modifier = Modifier.testTag("conn_open_create"),
                    ) {
                        Text(stringResource(Res.string.agent_connection_add))
                    }
                }
            }

            if (connections.isEmpty()) {
                Text(
                    stringResource(Res.string.agent_connections_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                connections.forEach { connection ->
                    val credStatus = snapshot.credentialStatuses[connection.connectionId]
                        ?: WorkspaceAgentCredentialStatus.MISSING_LOCAL_KEY
                    AgentSavedConnectionRow(
                        connection = connection,
                        status = credStatus,
                        readOnly = snapshot.readOnly,
                        busy = controller.busy || controller.fetchingModelsConnectionId == connection.connectionId,
                        onFetchModels = {
                            coroutineScope.launch { controller.fetchRemoteModels(connection.connectionId) }
                        },
                        onEdit = { startEdit(connection) },
                        onDelete = {
                            coroutineScope.launch { controller.deleteConnection(connection.connectionId) }
                        },
                    )
                }
            }

            if (formExpanded && !snapshot.readOnly) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = stringResource(
                        if (editingConnectionId == null) {
                            Res.string.agent_connection_new_title
                        } else {
                            Res.string.agent_connection_edit_title
                        },
                    ),
                    style = MaterialTheme.typography.titleSmall,
                )

                if (editingConnectionId == null) {
                    Text(
                        stringResource(Res.string.agent_connection_presets_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AiConnectionPresets.templates.forEach { preset ->
                            FilterChip(
                                selected = baseUrlInput == preset.defaultBaseUrl && protocolInput == preset.protocol,
                                onClick = { applyPreset(preset.kind) },
                                label = { Text(presetLabel(preset.kind)) },
                                modifier = Modifier.testTag("conn_preset_${preset.kind.name.lowercase()}"),
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text(stringResource(Res.string.agent_connection_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("conn_name_input"),
                )

                OutlinedTextField(
                    value = baseUrlInput,
                    onValueChange = { baseUrlInput = it },
                    label = { Text(stringResource(Res.string.agent_connection_base_url_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("conn_base_url_input"),
                )

                Text(
                    stringResource(Res.string.agent_connection_protocol_label),
                    style = MaterialTheme.typography.labelMedium,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = protocolInput == AiProtocolKind.OPENAI_RESPONSES,
                        onClick = { protocolInput = AiProtocolKind.OPENAI_RESPONSES },
                        label = { Text(stringResource(Res.string.agent_protocol_responses)) },
                        modifier = Modifier.testTag("conn_protocol_responses"),
                    )
                    FilterChip(
                        selected = protocolInput == AiProtocolKind.OPENAI_CHAT_COMPLETIONS,
                        onClick = { protocolInput = AiProtocolKind.OPENAI_CHAT_COMPLETIONS },
                        label = { Text(stringResource(Res.string.agent_protocol_chat_completions)) },
                        modifier = Modifier.testTag("conn_protocol_chat_completions"),
                    )
                }

                Text(
                    stringResource(Res.string.agent_connection_auth_label),
                    style = MaterialTheme.typography.labelMedium,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = authModeInput == AiAuthMode.KEYSTORE,
                        onClick = { authModeInput = AiAuthMode.KEYSTORE },
                        label = { Text(stringResource(Res.string.agent_auth_keychain)) },
                        modifier = Modifier.testTag("conn_auth_keystore"),
                    )
                    if (snapshot.supportsEnvironmentVariables || authModeInput == AiAuthMode.ENV_VAR) {
                        FilterChip(
                            selected = authModeInput == AiAuthMode.ENV_VAR,
                            onClick = { authModeInput = AiAuthMode.ENV_VAR },
                            label = { Text(stringResource(Res.string.agent_auth_env_var)) },
                            modifier = Modifier.testTag("conn_auth_env_var"),
                        )
                    }
                    FilterChip(
                        selected = authModeInput == AiAuthMode.NONE,
                        onClick = { authModeInput = AiAuthMode.NONE },
                        label = { Text(stringResource(Res.string.agent_auth_none)) },
                        modifier = Modifier.testTag("conn_auth_none"),
                    )
                }

                when (authModeInput) {
                    AiAuthMode.KEYSTORE -> {
                        val existingStatus = editingConnectionId?.let { snapshot.credentialStatuses[it] }
                        val hasSavedKey = existingStatus == WorkspaceAgentCredentialStatus.READY_LOCAL_KEYSTORE ||
                            existingStatus == WorkspaceAgentCredentialStatus.READY_SYNCED_ENVELOPE
                        OutlinedTextField(
                            value = apiKeyInput,
                            onValueChange = { apiKeyInput = it },
                            label = { Text(stringResource(Res.string.agent_connection_api_key_label)) },
                            placeholder = if (hasSavedKey) {
                                { Text(stringResource(Res.string.agent_connection_api_key_saved_placeholder)) }
                            } else {
                                null
                            },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("conn_api_key_input"),
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Checkbox(
                                checked = syncEnvelopeInput,
                                onCheckedChange = { syncEnvelopeInput = it },
                                modifier = Modifier.testTag("conn_sync_envelope_checkbox"),
                            )
                            Text(
                                stringResource(Res.string.agent_connection_sync_envelope_label),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Text(
                            stringResource(Res.string.agent_connection_sync_envelope_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    AiAuthMode.ENV_VAR -> {
                        OutlinedTextField(
                            value = envVarNameInput,
                            onValueChange = { envVarNameInput = it },
                            label = { Text(stringResource(Res.string.agent_connection_env_var_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("conn_env_var_input"),
                        )
                        Text(
                            stringResource(Res.string.agent_connection_env_var_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    AiAuthMode.NONE -> {
                        Text(
                            stringResource(Res.string.agent_connection_none_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            val draft = currentDraft()
                            coroutineScope.launch { controller.testConnection(draft) }
                        },
                        enabled = !controller.testingConnection && !controller.busy &&
                            nameInput.isNotBlank() && baseUrlInput.isNotBlank(),
                        modifier = Modifier.testTag("conn_test_button"),
                    ) {
                        Text(stringResource(Res.string.agent_connection_test))
                    }
                    Button(
                        onClick = {
                            val draft = currentDraft()
                            coroutineScope.launch {
                                controller.saveConnection(draft) {
                                    apiKeyInput = ""
                                    editingConnectionId = null
                                    formExpanded = false
                                }
                            }
                        },
                        enabled = !controller.busy && nameInput.isNotBlank() && baseUrlInput.isNotBlank(),
                        modifier = Modifier.testTag("conn_save_button"),
                    ) {
                        Text(stringResource(Res.string.agent_connection_save))
                    }
                    if (connections.isNotEmpty() || editingConnectionId != null) {
                        TextButton(
                            onClick = {
                                apiKeyInput = ""
                                editingConnectionId = null
                                formExpanded = false
                            },
                            modifier = Modifier.testTag("conn_cancel_button"),
                        ) {
                            Text(stringResource(Res.string.workspace_action_cancel))
                        }
                    }
                }
            }
        }
    }
}

/**
 * 展示单条已保存的 Provider 连接及其凭据就绪标签和操作按钮。
 */
@Composable
private fun AgentSavedConnectionRow(
    connection: AiProviderConnection,
    status: WorkspaceAgentCredentialStatus,
    readOnly: Boolean,
    busy: Boolean,
    onFetchModels: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val isReady = status == WorkspaceAgentCredentialStatus.READY_LOCAL_KEYSTORE ||
        status == WorkspaceAgentCredentialStatus.READY_SYNCED_ENVELOPE ||
        status == WorkspaceAgentCredentialStatus.READY_ENV_VAR ||
        status == WorkspaceAgentCredentialStatus.READY_NONE
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("conn_row_${connection.connectionId}"),
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
                    Text(connection.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        connection.baseUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AgentStatusBadge(
                    text = credentialStatusLabel(status, connection.envVarName.orEmpty()),
                    highlighted = isReady,
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AgentStatusBadge(
                    text = if (connection.protocol == AiProtocolKind.OPENAI_RESPONSES) {
                        stringResource(Res.string.agent_protocol_responses)
                    } else {
                        stringResource(Res.string.agent_protocol_chat_completions)
                    },
                )
                if (!readOnly) {
                    TextButton(
                        onClick = onFetchModels,
                        enabled = !busy && isReady,
                        modifier = Modifier.testTag("conn_fetch_models_${connection.connectionId}"),
                    ) {
                        Text(stringResource(Res.string.agent_connection_fetch_models))
                    }
                    TextButton(
                        onClick = onEdit,
                        enabled = !busy,
                        modifier = Modifier.testTag("conn_edit_${connection.connectionId}"),
                    ) {
                        Text(stringResource(Res.string.agent_action_edit))
                    }
                    TextButton(
                        onClick = onDelete,
                        enabled = !busy,
                        modifier = Modifier.testTag("conn_delete_${connection.connectionId}"),
                    ) {
                        Text(stringResource(Res.string.agent_action_delete))
                    }
                }
            }
        }
    }
}

@Composable
private fun presetLabel(kind: AiConnectionPresetKind): String = when (kind) {
    AiConnectionPresetKind.OPENAI -> stringResource(Res.string.agent_preset_openai)
    AiConnectionPresetKind.OPENAI_COMPATIBLE -> stringResource(Res.string.agent_preset_compatible)
    AiConnectionPresetKind.OLLAMA_LOCAL -> stringResource(Res.string.agent_preset_ollama)
    AiConnectionPresetKind.LM_STUDIO_LOCAL -> stringResource(Res.string.agent_preset_lm_studio)
}

@Composable
private fun credentialStatusLabel(
    status: WorkspaceAgentCredentialStatus,
    envVarName: String,
): String = when (status) {
    WorkspaceAgentCredentialStatus.READY_LOCAL_KEYSTORE -> stringResource(Res.string.agent_cred_ready_local)
    WorkspaceAgentCredentialStatus.READY_SYNCED_ENVELOPE -> stringResource(Res.string.agent_cred_ready_synced)
    WorkspaceAgentCredentialStatus.READY_ENV_VAR -> stringResource(Res.string.agent_cred_ready_env, envVarName)
    WorkspaceAgentCredentialStatus.READY_NONE -> stringResource(Res.string.agent_cred_ready_none)
    WorkspaceAgentCredentialStatus.MISSING_LOCAL_KEY -> stringResource(Res.string.agent_cred_missing_local)
    WorkspaceAgentCredentialStatus.MISSING_ENV_VAR,
    WorkspaceAgentCredentialStatus.ENV_VAR_UNSUPPORTED -> stringResource(Res.string.agent_cred_missing_env, envVarName)
}
