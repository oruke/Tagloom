package com.oruke.tagloom.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.ai.AiModelConfig
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.platform.WorkspaceSavedQuery
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.Res
import tagloom.app.shared.generated.resources.agent_close_companion
import tagloom.app.shared.generated.resources.agent_expand_to_fullscreen
import tagloom.app.shared.generated.resources.agent_sidebar_incognito_thread
import tagloom.app.shared.generated.resources.agent_sidebar_new_thread
import tagloom.app.shared.generated.resources.agent_thread_default_title
import tagloom.app.shared.generated.resources.ic_close
import tagloom.app.shared.generated.resources.ic_expand_full
import tagloom.app.shared.generated.resources.ic_incognito
import tagloom.app.shared.generated.resources.ic_plus

/**
 * 渲染固定在编辑器或「全部笔记」页面右侧的 Agent 伴随分栏（及窄屏底部对话抽屉）。
 *
 * 顶部紧凑会话下拉切换严格调用 `selectThreadForViewing(openFullScreen = false)`，确保零跳序。
 */
@Composable
internal fun AgentCompanionPane(
    controller: WorkspaceAgentHarnessController,
    editorState: TagloomEditorState,
    savedQueries: List<WorkspaceSavedQuery>,
    enabledModels: List<AiModelConfig>,
    isCompactViewport: Boolean,
    onOpenSettings: () -> Unit,
    onSaveEditorChanges: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var threadMenuOpen by remember { mutableStateOf(false) }
    val selectedEntry = controller.selectedThreadEntry
    val activeThreads = remember(controller.threads) {
        controller.threads.filterNot { it.thread.archived }
    }
    val currentTitle = selectedEntry?.thread?.title
        ?: stringResource(Res.string.agent_thread_default_title)

    Surface(
        modifier = modifier.fillMaxHeight().testTag("agent-companion-pane"),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Box(Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier
                            .clickable { threadMenuOpen = true }
                            .padding(horizontal = 6.dp, vertical = 5.dp)
                            .testTag("agent-companion-thread-selector"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (selectedEntry?.incognito == true) {
                            Icon(
                                painter = painterResource(Res.drawable.ic_incognito),
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        Text(
                            text = "$currentTitle ▾",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TagloomDropdownMenu(
                        expanded = threadMenuOpen,
                        onDismissRequest = { threadMenuOpen = false },
                    ) {
                        Column(Modifier.widthIn(min = 240.dp, max = 300.dp).padding(vertical = 4.dp)) {
                            activeThreads.forEach { item ->
                                val isCurrent = item.id == controller.selectedThreadId
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            threadMenuOpen = false
                                            scope.launch {
                                                controller.selectThreadForViewing(item.id, openFullScreen = false)
                                            }
                                        }
                                        .testTag("agent-companion-thread-item-${item.id.value}"),
                                    color = if (isCurrent) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                                    else MaterialTheme.colorScheme.surface,
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        if (item.incognito) {
                                            Icon(painterResource(Res.drawable.ic_incognito), null, Modifier.size(13.dp))
                                        }
                                        Text(
                                            text = item.thread.title,
                                            modifier = Modifier.weight(1f),
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                IconButton(
                    onClick = { scope.launch { controller.startNewThread(incognito = false, openFullScreen = false) } },
                    modifier = Modifier.size(30.dp).testTag("agent-companion-new-thread"),
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_plus),
                        contentDescription = stringResource(Res.string.agent_sidebar_new_thread),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                IconButton(
                    onClick = { scope.launch { controller.startNewThread(incognito = true, openFullScreen = false) } },
                    modifier = Modifier.size(30.dp).testTag("agent-companion-incognito-thread"),
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_incognito),
                        contentDescription = stringResource(Res.string.agent_sidebar_incognito_thread),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                IconButton(
                    onClick = controller::expandCompanionToFullScreen,
                    modifier = Modifier.size(30.dp).testTag("agent-expand-fullscreen-button"),
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_expand_full),
                        contentDescription = stringResource(Res.string.agent_expand_to_fullscreen),
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                IconButton(
                    onClick = { controller.companionVisible = false },
                    modifier = Modifier.size(30.dp).testTag("agent-close-companion-button"),
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_close),
                        contentDescription = stringResource(Res.string.agent_close_companion),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
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
                    messages = selectedEntry?.thread?.messages.orEmpty(),
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
                            inFullScreenHub = false,
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
    }
}
