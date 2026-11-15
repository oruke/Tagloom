package com.oruke.tagloom.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.ai.AiModelConfig
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.platform.WorkspaceSavedQuery
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.Res
import tagloom.app.shared.generated.resources.agent_banner_missing_key
import tagloom.app.shared.generated.resources.agent_banner_no_model
import tagloom.app.shared.generated.resources.agent_banner_open_settings
import tagloom.app.shared.generated.resources.agent_error_turn_failed
import tagloom.app.shared.generated.resources.agent_sidebar_toggle_nav
import tagloom.app.shared.generated.resources.agent_switch_to_companion
import tagloom.app.shared.generated.resources.agent_thread_archive
import tagloom.app.shared.generated.resources.agent_thread_default_title
import tagloom.app.shared.generated.resources.agent_thread_incognito_badge
import tagloom.app.shared.generated.resources.agent_thread_rename_placeholder
import tagloom.app.shared.generated.resources.agent_thread_restore
import tagloom.app.shared.generated.resources.ic_archive
import tagloom.app.shared.generated.resources.ic_incognito
import tagloom.app.shared.generated.resources.ic_menu
import tagloom.app.shared.generated.resources.ic_sidebar_split

/**
 * 渲染中央全屏 Agent 会话形态（`AgentWorkspaceScreen`）。
 *
 * 采用无多余内层侧栏的 `820.dp` 居中宽屏对话区，顶部支持内联重命名会话标题、无痕会话内存态提示、
 * 归档切换以及纯图标切回右侧伴随模式按钮。
 */
@Composable
internal fun AgentWorkspaceScreen(
    controller: WorkspaceAgentHarnessController,
    editorState: TagloomEditorState,
    savedQueries: List<WorkspaceSavedQuery>,
    enabledModels: List<AiModelConfig>,
    isCompactViewport: Boolean,
    isWideViewport: Boolean = true,
    showMenuButton: Boolean,
    onOpenMenu: () -> Unit,
    onOpenSettings: () -> Unit,
    onSaveEditorChanges: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val entry = controller.selectedThreadEntry
    val defaultTitle = stringResource(Res.string.agent_thread_default_title)
    var titleDraft by remember(entry?.id, entry?.thread?.title) {
        mutableStateOf(entry?.thread?.title ?: defaultTitle)
    }
    var hadFocus by remember(entry?.id) { mutableStateOf(false) }

    LaunchedEffect(entry?.thread?.title) {
        if (entry != null && entry.thread.title != titleDraft) {
            titleDraft = entry.thread.title
        }
    }

    Surface(
        modifier = modifier.fillMaxSize().testTag("agent-workspace-screen"),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (showMenuButton) {
                    IconButton(
                        onClick = onOpenMenu,
                        modifier = Modifier.size(32.dp).testTag("agent-fullscreen-menu-button"),
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_menu),
                            contentDescription = stringResource(Res.string.agent_sidebar_toggle_nav),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                BasicTextField(
                    value = titleDraft,
                    onValueChange = { titleDraft = it },
                    enabled = entry != null,
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused) {
                                hadFocus = true
                            } else if (hadFocus && entry != null && titleDraft.trim() != entry.thread.title) {
                                scope.launch { controller.renameThread(entry.id, titleDraft) }
                            }
                        }
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && entry != null) {
                                scope.launch { controller.renameThread(entry.id, titleDraft) }
                                true
                            } else {
                                false
                            }
                        }
                        .testTag("agent-thread-title-input"),
                    textStyle = MaterialTheme.typography.titleMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { inner ->
                        Box {
                            if (titleDraft.isEmpty()) {
                                Text(
                                    stringResource(Res.string.agent_thread_rename_placeholder),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            inner()
                        }
                    },
                )

                if (entry?.incognito == true) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(painterResource(Res.drawable.ic_incognito), null, Modifier.size(13.dp))
                            Text(
                                stringResource(Res.string.agent_thread_incognito_badge),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }

                if (entry != null && !entry.incognito) {
                    val archived = entry.thread.archived
                    IconButton(
                        onClick = { scope.launch { controller.setThreadArchived(entry.id, !archived) } },
                        modifier = Modifier.size(32.dp).testTag("agent-thread-archive-button"),
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_archive),
                            contentDescription = stringResource(
                                if (archived) Res.string.agent_thread_restore else Res.string.agent_thread_archive,
                            ),
                            modifier = Modifier.size(17.dp),
                            tint = if (archived) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                IconButton(
                    onClick = controller::toggleCompanionPane,
                    modifier = Modifier.size(32.dp).testTag("agent-switch-to-companion-button"),
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_sidebar_split),
                        contentDescription = stringResource(Res.string.agent_switch_to_companion),
                        modifier = Modifier.size(17.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            val activeProposal = controller.activeReviewProposal
            val targetDoc = activeProposal?.let { prop ->
                editorState.documents.firstOrNull { it.id == prop.noteId }
            }

            Box(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        Column(
                            modifier = Modifier
                                .widthIn(max = 820.dp)
                                .fillMaxSize()
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            AgentReadinessAndErrorBanner(
                                readiness = controller.readiness,
                                turnErrorCode = controller.turnErrorCode,
                                onOpenSettings = onOpenSettings,
                            )

                            val liveForSelected = controller.liveProgress?.takeIf {
                                it.threadId == controller.selectedThreadId || controller.selectedThreadId == null
                            }
                            AgentMessageList(
                                messages = entry?.thread?.messages.orEmpty(),
                                compactions = entry?.thread?.compactions.orEmpty(),
                                activeCompactionId = entry?.thread?.activeCompactionId,
                                liveProgress = liveForSelected,
                                citationStatuses = controller.citationStatuses,
                                onJumpToCitation = { src -> controller.jumpToCitation(src, isCompactViewport) },
                                onAppendToNote = { md -> controller.appendMessageToCurrentNote(md, onSaveEditorChanges) },
                                onSaveAsNewNote = { md, title -> controller.saveMessageAsNewNote(md, title, onSaveEditorChanges) },
                                proposalSlot = { msgId, streaming, onNotice ->
                                    AgentProposalCardList(
                                        controller = controller,
                                        messageId = msgId,
                                        isStreaming = streaming,
                                        inFullScreenHub = true,
                                        onNotice = onNotice,
                                    )
                                },
                                modifier = Modifier.weight(1f),
                            )

                            AgentComposerBar(
                                controller = controller,
                                editorState = editorState,
                                savedQueries = savedQueries,
                                enabledModels = enabledModels,
                                onOpenSettings = onOpenSettings,
                            )
                        }
                    }

                    if (activeProposal != null && !isCompactViewport) {
                        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        AgentHubRightDiffPane(
                            controller = controller,
                            proposal = activeProposal,
                            targetDocument = targetDoc,
                            isCompactOverlay = false,
                            modifier = Modifier.width(if (isWideViewport) 460.dp else 400.dp),
                        )
                    }
                }

                if (activeProposal != null && isCompactViewport) {
                    AgentHubRightDiffPane(
                        controller = controller,
                        proposal = activeProposal,
                        targetDocument = targetDoc,
                        isCompactOverlay = true,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/**
 * 渲染温和内联配置引导横幅（未启用模型或本机缺少凭据）与脱敏请求错误提示，不阻断进入对话页。
 */
@Composable
internal fun AgentReadinessAndErrorBanner(
    readiness: AgentReadinessStatus,
    turnErrorCode: String?,
    onOpenSettings: () -> Unit,
) {
    if (readiness != AgentReadinessStatus.READY) {
        val message = when (readiness) {
            AgentReadinessStatus.NO_ENABLED_MODEL -> stringResource(Res.string.agent_banner_no_model)
            AgentReadinessStatus.MISSING_LOCAL_API_KEY -> stringResource(Res.string.agent_banner_missing_key)
            AgentReadinessStatus.READY -> ""
        }
        Surface(
            modifier = Modifier.fillMaxWidth().testTag("agent-readiness-banner"),
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
            shape = MaterialTheme.shapes.small,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = message,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                TextButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.testTag("agent-banner-configure-button"),
                ) {
                    Text(stringResource(Res.string.agent_banner_open_settings))
                }
            }
        }
    }
    if (turnErrorCode != null) {
        Surface(
            modifier = Modifier.fillMaxWidth().testTag("agent-turn-error-banner"),
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f),
            shape = MaterialTheme.shapes.small,
        ) {
            Text(
                text = stringResource(Res.string.agent_error_turn_failed),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}
