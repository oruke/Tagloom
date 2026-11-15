package com.oruke.tagloom.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.ai.AgentContextRef
import com.oruke.tagloom.core.ai.AgentContextRefKind
import com.oruke.tagloom.core.ai.AiModelToolsState
import com.oruke.tagloom.core.ai.AiRetrievalScope
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.platform.WorkspaceSavedQuery
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.Res
import tagloom.app.shared.generated.resources.agent_composer_attach_context
import tagloom.app.shared.generated.resources.agent_composer_placeholder
import tagloom.app.shared.generated.resources.agent_composer_ref_locked_warning
import tagloom.app.shared.generated.resources.agent_composer_scope_all
import tagloom.app.shared.generated.resources.agent_composer_scope_chat_only
import tagloom.app.shared.generated.resources.agent_composer_scope_cited
import tagloom.app.shared.generated.resources.agent_composer_send
import tagloom.app.shared.generated.resources.agent_composer_stop
import tagloom.app.shared.generated.resources.ic_at
import tagloom.app.shared.generated.resources.ic_close
import tagloom.app.shared.generated.resources.ic_globe
import tagloom.app.shared.generated.resources.ic_send
import tagloom.app.shared.generated.resources.ic_stop

/**
 * 渲染全屏 Agent 工作台与右侧伴随栏共享的无边框轻盈作曲框（Composer）。
 *
 * 严格遵循纯图标零文本功能按钮与合一「模型 · 思考 ▾」无框文本按钮规范：
 * - 左侧：`@` 多类型挂载纯图标按钮 + `🌐` 检索范围纯图标切换按钮（`CHAT_ONLY` 模型自动禁用）；
 * - 右侧：`AgentModelThinkingSelector` 文本按钮 + 发送圆钮 / 流式生成期间切换为红色方块停止按钮。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AgentComposerBar(
    controller: WorkspaceAgentHarnessController,
    editorState: TagloomEditorState,
    savedQueries: List<WorkspaceSavedQuery>,
    enabledModels: List<com.oruke.tagloom.core.ai.AiModelConfig>,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val inputFocusRequester = remember { FocusRequester() }
    var pickerOpen by remember { mutableStateOf(false) }
    var editingBlockRef by remember { mutableStateOf<AgentContextRef?>(null) }

    LaunchedEffect(controller.composerFocusVersion) {
        if (controller.composerFocusVersion > 0) {
            runCatching { inputFocusRequester.requestFocus() }
        }
    }

    val selectedModel = controller.effectiveModel
    val isRunning = controller.runningThreadId != null &&
        (controller.runningThreadId == controller.selectedThreadId || controller.selectedThreadId == null)
    val isChatOnly = selectedModel?.toolsState == AiModelToolsState.CHAT_ONLY
    val effectiveScope = coerceRetrievalScopeForModel(controller.retrievalScope, selectedModel)
    val canSend = controller.promptDraft.trim().isNotEmpty() &&
        !isRunning &&
        controller.readiness == AgentReadinessStatus.READY

    editingBlockRef?.let { targetRef ->
        AgentNoteBlockSelectorDialog(
            ref = targetRef,
            state = editorState,
            onConfirm = { updated -> scope.launch { controller.upsertContextRef(updated) } },
            onDismiss = { editingBlockRef = null },
        )
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("agent-composer-box"),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (controller.activeContextRefs.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().testTag("agent-composer-ref-row"),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    controller.activeContextRefs.forEach { ref ->
                        val locked = isContextRefLockedInEditor(ref, editorState, controller.blockedRefIds)
                        ComposerContextRefChip(
                            ref = ref,
                            isLocked = locked,
                            onClick = {
                                if (!locked && ref.kind == AgentContextRefKind.NOTE) {
                                    editingBlockRef = ref
                                }
                            },
                            onRemove = { scope.launch { controller.removeContextRef(ref.id) } },
                        )
                    }
                }
            }

            BasicTextField(
                value = controller.promptDraft,
                onValueChange = { next ->
                    val justTypedAt = next.length == controller.promptDraft.length + 1 &&
                        next.endsWith("@") &&
                        (controller.promptDraft.isEmpty() || controller.promptDraft.last().isWhitespace())
                    controller.promptDraft = next
                    if (justTypedAt) {
                        pickerOpen = true
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp, max = 148.dp)
                    .focusRequester(inputFocusRequester)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                            val sendByEnter = !event.isShiftPressed || event.isCtrlPressed || event.isMetaPressed
                            if (sendByEnter && canSend) {
                                scope.launch { controller.sendMessage() }
                                return@onPreviewKeyEvent true
                            }
                        }
                        false
                    }
                    .testTag("agent-composer-input"),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth()) {
                        if (controller.promptDraft.isEmpty()) {
                            Text(
                                text = stringResource(Res.string.agent_composer_placeholder),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                            )
                        }
                        inner()
                    }
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box {
                    IconButton(
                        onClick = { pickerOpen = true },
                        modifier = Modifier.size(32.dp).testTag("agent-composer-attach-button"),
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_at),
                            contentDescription = stringResource(Res.string.agent_composer_attach_context),
                            modifier = Modifier.size(17.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    AgentContextPickerDropdown(
                        expanded = pickerOpen,
                        state = editorState,
                        savedQueries = savedQueries,
                        onDismiss = { pickerOpen = false },
                        onAttachNote = { id, title, blocks ->
                            stripTrailingAtTrigger(controller)
                            scope.launch { controller.attachNoteRef(id, title, blocks) }
                        },
                        onAttachTagScope = { id, name ->
                            stripTrailingAtTrigger(controller)
                            scope.launch { controller.attachTagScopeRef(id, name) }
                        },
                        onAttachQueryScope = { id, name ->
                            stripTrailingAtTrigger(controller)
                            scope.launch { controller.attachSavedQueryRef(id, name) }
                        },
                    )
                }

                val scopeDesc = when {
                    isChatOnly -> stringResource(Res.string.agent_composer_scope_chat_only)
                    effectiveScope == AiRetrievalScope.ALL_NOTES -> stringResource(Res.string.agent_composer_scope_all)
                    else -> stringResource(Res.string.agent_composer_scope_cited)
                }
                val allNotesActive = !isChatOnly && effectiveScope == AiRetrievalScope.ALL_NOTES
                Surface(
                    modifier = Modifier.size(32.dp),
                    color = if (allNotesActive) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = CircleShape,
                ) {
                    IconButton(
                        onClick = { scope.launch { controller.toggleRetrievalScope() } },
                        enabled = !isChatOnly,
                        modifier = Modifier
                            .size(32.dp)
                            .alpha(if (isChatOnly) 0.38f else 1f)
                            .testTag("agent-composer-scope-button"),
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_globe),
                            contentDescription = scopeDesc,
                            modifier = Modifier.size(17.dp),
                            tint = if (allNotesActive) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.weight(1f))

                AgentModelThinkingSelector(
                    enabledModels = enabledModels,
                    selectedModel = selectedModel,
                    thinkingEffort = controller.thinkingEffort,
                    onSelect = { modelId, effort ->
                        scope.launch { controller.updateModelAndThinking(modelId, effort) }
                    },
                    onOpenSettings = onOpenSettings,
                )

                if (isRunning) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.size(32.dp),
                    ) {
                        IconButton(
                            onClick = controller::stopGeneration,
                            modifier = Modifier.size(32.dp).testTag("agent-composer-stop-button"),
                        ) {
                            Icon(
                                painter = painterResource(Res.drawable.ic_stop),
                                contentDescription = stringResource(Res.string.agent_composer_stop),
                                modifier = Modifier.size(15.dp),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                } else {
                    Surface(
                        color = if (canSend) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = CircleShape,
                        modifier = Modifier.size(32.dp),
                    ) {
                        IconButton(
                            onClick = { scope.launch { controller.sendMessage() } },
                            enabled = canSend,
                            modifier = Modifier.size(32.dp).testTag("agent-composer-send-button"),
                        ) {
                            Icon(
                                painter = painterResource(Res.drawable.ic_send),
                                contentDescription = stringResource(Res.string.agent_composer_send),
                                modifier = Modifier.size(15.dp),
                                tint = if (canSend) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                            )
                        }
                    }
                }
            }
        }
    }

    AgentComposerDock(controller = controller)
    }
}

/**
 * 渲染作曲框顶部单条已挂载 `@` 引用胶囊；若已被设为 `LOCKED` 策略则展示拦截警告样式。
 */
@Composable
private fun ComposerContextRefChip(
    ref: AgentContextRef,
    isLocked: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val containerColor = if (isLocked) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
    else MaterialTheme.colorScheme.surfaceContainerHigh
    val contentColor = if (isLocked) MaterialTheme.colorScheme.onErrorContainer
    else MaterialTheme.colorScheme.onSurface
    val suffix = when {
        isLocked -> " · ${stringResource(Res.string.agent_composer_ref_locked_warning)}"
        ref.excludedBlockIds.isNotEmpty() -> " (-${ref.excludedBlockIds.size})"
        else -> ""
    }
    Surface(
        modifier = Modifier
            .clickable(enabled = !isLocked && ref.kind == AgentContextRefKind.NOTE, onClick = onClick)
            .testTag("agent-composer-ref-${ref.id}"),
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "${ref.label}$suffix",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                painter = painterResource(Res.drawable.ic_close),
                contentDescription = null,
                modifier = Modifier
                    .size(14.dp)
                    .clickable(onClick = onRemove)
                    .testTag("agent-composer-ref-remove-${ref.id}"),
                tint = contentColor.copy(alpha = 0.75f),
            )
        }
    }
}

/**
 * 在用户通过键盘输入 `@` 呼出选择器并完成挂载后，移除末尾触发用的孤立 `@` 字符。
 */
private fun stripTrailingAtTrigger(controller: WorkspaceAgentHarnessController) {
    val current = controller.promptDraft
    if (current.endsWith("@") && (current.length == 1 || current[current.length - 2].isWhitespace())) {
        controller.promptDraft = current.dropLast(1)
    }
}
