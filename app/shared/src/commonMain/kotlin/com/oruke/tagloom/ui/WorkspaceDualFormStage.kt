package com.oruke.tagloom.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.editor.TagloomEditorState
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.Res
import tagloom.app.shared.generated.resources.agent_return_to_chat
import tagloom.app.shared.generated.resources.agent_sidebar_toggle_nav
import tagloom.app.shared.generated.resources.agent_toggle_companion
import tagloom.app.shared.generated.resources.editor_action_new_note
import tagloom.app.shared.generated.resources.ic_menu
import tagloom.app.shared.generated.resources.ic_sparkle
import tagloom.app.shared.generated.resources.link_library
import tagloom.app.shared.generated.resources.supertag_library

/**
 * 编排三档自适应工作区外壳（宽屏 `>= 1120.dp`、中屏 `720..1119.dp` 左栏自动收起、窄屏 `< 720.dp` 抽屉与浮层）及双形态 Agent 容器。
 */
@Composable
internal fun WorkspaceDualFormStage(
    maxWidth: Dp,
    state: TagloomEditorState,
    navigationModifier: Modifier,
    inOverlaySubpage: Boolean,
    onCreateDocument: () -> Unit,
    onOpenLinks: (() -> Unit)?,
    onOpenSupertags: () -> Unit,
    onOpenSettings: (() -> Unit)?,
    onOpenAgentSettings: () -> Unit,
    onSaveRequest: () -> Unit,
    sidebarContent: @Composable (Modifier, () -> Unit) -> Unit,
    editorOrSpecialContent: @Composable (isCompact: Boolean, showNavButton: Boolean, onToggleNav: () -> Unit) -> Unit,
) {
    val agentController = LocalWorkspaceAgentHarness.current
    var compactDrawerOpen by remember { mutableStateOf(false) }

    val isCompact = maxWidth < 720.dp
    val isMedium = maxWidth >= 720.dp && maxWidth < 1120.dp
    val isWide = maxWidth >= 1120.dp

    val companionSplitActive = agentController?.available == true &&
        agentController.companionVisible &&
        !agentController.fullScreenAgentActive &&
        !inOverlaySubpage

    val showPersistentSidebar = isWide ||
        (isMedium && (!companionSplitActive || agentController.mediumSidebarExpandedOverride))
    val showMediumSidebarToggle = isMedium && companionSplitActive

    Box(navigationModifier) {
        Row(Modifier.fillMaxSize()) {
            if (showPersistentSidebar) {
                sidebarContent(Modifier.width(if (isWide) 236.dp else 224.dp)) {}
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            Column(Modifier.weight(1f).background(MaterialTheme.colorScheme.surface)) {
                if (isCompact && !inOverlaySubpage && agentController?.fullScreenAgentActive != true) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = { compactDrawerOpen = true },
                            modifier = Modifier.size(32.dp).testTag("compact-open-sidebar-button"),
                        ) {
                            Icon(
                                painter = painterResource(Res.drawable.ic_menu),
                                contentDescription = stringResource(Res.string.agent_sidebar_toggle_nav),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        TextButton(onClick = onCreateDocument) {
                            Text(stringResource(Res.string.editor_action_new_note))
                        }
                        Box(Modifier.weight(1f))
                        if (agentController?.available == true) {
                            IconButton(
                                onClick = agentController::toggleCompanionPane,
                                modifier = Modifier.size(32.dp).testTag("compact-companion-toggle"),
                            ) {
                                Icon(
                                    painter = painterResource(Res.drawable.ic_sparkle),
                                    contentDescription = stringResource(Res.string.agent_toggle_companion),
                                    modifier = Modifier.size(17.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        if (onOpenLinks != null) {
                            TextButton(onClick = onOpenLinks) { Text(stringResource(Res.string.link_library)) }
                        }
                        TextButton(onClick = onOpenSupertags) {
                            Text(stringResource(Res.string.supertag_library))
                        }
                        if (onOpenSettings != null) {
                            WorkspaceSettingsEntry(selected = false, onClick = onOpenSettings)
                        }
                    }
                } else if (showMediumSidebarToggle && !inOverlaySubpage && !agentController.fullScreenAgentActive) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = {
                                agentController.mediumSidebarExpandedOverride =
                                    !agentController.mediumSidebarExpandedOverride
                            },
                            modifier = Modifier.size(30.dp).testTag("medium-expand-sidebar-button"),
                        ) {
                            Icon(
                                painter = painterResource(Res.drawable.ic_menu),
                                contentDescription = stringResource(Res.string.agent_sidebar_toggle_nav),
                                modifier = Modifier.size(17.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    editorOrSpecialContent(
                        isCompact,
                        isCompact || showMediumSidebarToggle,
                    ) {
                        if (isCompact) {
                            compactDrawerOpen = !compactDrawerOpen
                        } else if (agentController != null) {
                            agentController.mediumSidebarExpandedOverride = !agentController.mediumSidebarExpandedOverride
                        }
                    }
                }
            }

            if (!isCompact && companionSplitActive) {
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                AgentCompanionPane(
                    controller = agentController,
                    editorState = state,
                    savedQueries = agentController.savedQueriesSnapshot,
                    enabledModels = agentController.enabledModels,
                    isCompactViewport = false,
                    onOpenSettings = onOpenAgentSettings,
                    onSaveEditorChanges = onSaveRequest,
                    modifier = Modifier.width(if (isWide) 388.dp else 356.dp),
                )
            }
        }

        if (isCompact && compactDrawerOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.36f))
                    .clickable { compactDrawerOpen = false },
            )
            Box(Modifier.align(Alignment.CenterStart).fillMaxHeight()) {
                sidebarContent(Modifier.width(264.dp)) { compactDrawerOpen = false }
            }
        }

        if (isCompact && companionSplitActive) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.36f))
                    .clickable { agentController.companionVisible = false },
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.88f)
                    .testTag("agent-compact-sheet"),
                shape = MaterialTheme.shapes.large,
                shadowElevation = 8.dp,
            ) {
                AgentCompanionPane(
                    controller = agentController,
                    editorState = state,
                    savedQueries = agentController.savedQueriesSnapshot,
                    enabledModels = agentController.enabledModels,
                    isCompactViewport = true,
                    onOpenSettings = onOpenAgentSettings,
                    onSaveEditorChanges = onSaveRequest,
                )
            }
        }

        if (isCompact &&
            agentController?.showReturnToAgentPill == true &&
            !agentController.companionVisible &&
            !agentController.fullScreenAgentActive
        ) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
                    .clickable {
                        agentController.showReturnToAgentPill = false
                        agentController.companionVisible = true
                    }
                    .testTag("agent-return-pill"),
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = MaterialTheme.shapes.large,
                shadowElevation = 4.dp,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.primaryContainer),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(painterResource(Res.drawable.ic_sparkle), null, Modifier.size(14.dp))
                    Text(
                        stringResource(Res.string.agent_return_to_chat),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        if (agentController?.historyDialogOpen == true) {
            AgentThreadHistoryDialog(
                controller = agentController,
                onDismiss = { agentController.historyDialogOpen = false },
            )
        }
    }
}
