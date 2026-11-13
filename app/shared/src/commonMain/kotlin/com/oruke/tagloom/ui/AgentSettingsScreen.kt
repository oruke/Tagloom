package com.oruke.tagloom.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 渲染工作区「Agent 与模型」独立二级设置页，纵向整合 API 连接与密钥分层存储、
 * 模型上下文窗口与工具调用实测、以及 80% 自动压缩与默认护栏参数。
 *
 * @param controller 已解锁会话的 Agent 配置状态与异步动作控制器。
 * @param coroutineScope 宿主会话协程作用域，确保表单提交跨越局部重组。
 * @param onBack 返回上一级工作区设置页面。
 */
@Composable
internal fun AgentSettingsScreen(
    controller: WorkspaceAgentSettingsController,
    coroutineScope: CoroutineScope,
    onBack: () -> Unit,
) {
    LaunchedEffect(controller) {
        controller.ensureLoaded()
    }

    val snapshot = controller.snapshot
    val isAnyOperationActive = controller.busy ||
        controller.testingConnection ||
        controller.fetchingModelsConnectionId != null ||
        controller.probingModelId != null

    Surface(
        modifier = Modifier.fillMaxSize().testTag("agent_settings_screen"),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxSize()) {
            TextButton(
                onClick = onBack,
                modifier = Modifier.padding(start = 12.dp, top = 8.dp).testTag("agent_settings_back"),
            ) {
                Icon(
                    painterResource(Res.drawable.ic_arrow_back),
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp).size(18.dp),
                )
                Text(stringResource(Res.string.agent_settings_back))
            }

            if (isAnyOperationActive) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .widthIn(max = 860.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painterResource(Res.drawable.ic_bulb),
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        stringResource(Res.string.agent_settings_title),
                        style = MaterialTheme.typography.headlineLarge,
                    )
                }

                Text(
                    stringResource(Res.string.agent_settings_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                controller.statusCode?.let { code ->
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { liveRegion = LiveRegionMode.Polite }
                            .testTag("agent_settings_status_banner"),
                    ) {
                        Text(
                            text = localizedAgentStatus(code),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                controller.errorCode?.let { code ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { liveRegion = LiveRegionMode.Polite }
                            .testTag("agent_settings_error_banner"),
                    ) {
                        Text(
                            text = localizedAgentError(code),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                if (snapshot != null) {
                    AgentConnectionSettingsSection(
                        snapshot = snapshot,
                        controller = controller,
                        coroutineScope = coroutineScope,
                    )

                    AgentModelContextSettingsSection(
                        snapshot = snapshot,
                        controller = controller,
                        coroutineScope = coroutineScope,
                    )

                    AgentPreferencesSettingsSection(
                        snapshot = snapshot,
                        controller = controller,
                        coroutineScope = coroutineScope,
                    )
                }
            }
        }
    }
}

/**
 * 渲染紧凑型模型或连接状态徽章标签。
 *
 * @param text 标签文案。
 * @param highlighted 是否使用主色容器强调展示。
 * @param modifier 外部修饰符。
 */
@Composable
internal fun AgentStatusBadge(
    text: String,
    highlighted: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.semantics(mergeDescendants = true) {},
        shape = MaterialTheme.shapes.extraSmall,
        color = if (highlighted) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        contentColor = if (highlighted) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/**
 * 将控制器产出的非敏感成功状态码转换为当前语言资源文本。
 *
 * @param code 成功状态标识码。
 * @return 本地化提示文案。
 */
@Composable
private fun localizedAgentStatus(code: String): String = when (code) {
    "connection_saved" -> stringResource(Res.string.agent_status_connection_saved)
    "connection_deleted" -> stringResource(Res.string.agent_status_connection_deleted)
    "connection_ok" -> stringResource(Res.string.agent_status_connection_ok)
    "models_fetched" -> stringResource(Res.string.agent_status_models_fetched)
    "models_empty" -> stringResource(Res.string.agent_status_models_empty)
    "model_saved" -> stringResource(Res.string.agent_status_model_saved)
    "model_deleted" -> stringResource(Res.string.agent_status_model_deleted)
    "probe_verified" -> stringResource(Res.string.agent_status_probe_verified)
    "probe_chat_only" -> stringResource(Res.string.agent_status_probe_chat_only)
    "preferences_saved" -> stringResource(Res.string.agent_status_preferences_saved)
    else -> stringResource(Res.string.agent_status_model_saved)
}

/**
 * 将控制器产出的脱敏错误分类码转换为当前语言资源文本。
 *
 * @param code 错误分类标识码。
 * @return 本地化错误提示文案。
 */
@Composable
private fun localizedAgentError(code: String): String = when (code) {
    "auth" -> stringResource(Res.string.agent_error_auth)
    "rate_limit" -> stringResource(Res.string.agent_error_rate_limit)
    "endpoint" -> stringResource(Res.string.agent_error_endpoint)
    "network" -> stringResource(Res.string.agent_error_network)
    "validation" -> stringResource(Res.string.agent_error_validation)
    else -> stringResource(Res.string.agent_error_generic)
}
