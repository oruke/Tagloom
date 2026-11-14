package com.oruke.tagloom.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.ai.AgentMutationProposal
import com.oruke.tagloom.core.ai.AgentProposalKind
import com.oruke.tagloom.core.ai.AgentProposalStatus
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.Res
import tagloom.app.shared.generated.resources.agent_diff_conflict_dirty
import tagloom.app.shared.generated.resources.agent_diff_conflict_missing
import tagloom.app.shared.generated.resources.agent_diff_conflict_stale
import tagloom.app.shared.generated.resources.agent_proposal_action_copy_text
import tagloom.app.shared.generated.resources.agent_proposal_action_discard
import tagloom.app.shared.generated.resources.agent_proposal_action_recalc
import tagloom.app.shared.generated.resources.agent_proposal_card_create_title
import tagloom.app.shared.generated.resources.agent_proposal_card_patch_title
import tagloom.app.shared.generated.resources.agent_proposal_copied_toast
import tagloom.app.shared.generated.resources.agent_proposal_review_left
import tagloom.app.shared.generated.resources.agent_proposal_review_right
import tagloom.app.shared.generated.resources.agent_proposal_reviewing_active
import tagloom.app.shared.generated.resources.agent_proposal_status_applied
import tagloom.app.shared.generated.resources.agent_proposal_status_base_ok
import tagloom.app.shared.generated.resources.agent_proposal_status_dirty
import tagloom.app.shared.generated.resources.agent_proposal_status_discarded
import tagloom.app.shared.generated.resources.agent_proposal_status_missing
import tagloom.app.shared.generated.resources.agent_proposal_status_new_draft
import tagloom.app.shared.generated.resources.agent_proposal_status_stale
import tagloom.app.shared.generated.resources.agent_proposal_summary_applied
import tagloom.app.shared.generated.resources.agent_proposal_summary_blocks_only
import tagloom.app.shared.generated.resources.agent_proposal_summary_both
import tagloom.app.shared.generated.resources.agent_proposal_summary_create
import tagloom.app.shared.generated.resources.agent_proposal_summary_meta_only
import tagloom.app.shared.generated.resources.ic_copy
import tagloom.app.shared.generated.resources.ic_diff
import tagloom.app.shared.generated.resources.ic_refresh

/**
 * 渲染单条助手消息下挂载的全部结构化修改提案摘要卡列表。
 *
 * @param controller 双形态 Agent 控制器。
 * @param messageId 助手回复消息 ID。
 * @param isStreaming 当前消息是否仍在流式生成中。
 * @param inFullScreenHub 当前宿主是否为中央全屏 Agent 工作台（决定审阅按钮方向文案）。
 * @param onNotice 复制建议文本后的轻量提示回调。
 */
@Composable
internal fun AgentProposalCardList(
    controller: WorkspaceAgentHarnessController,
    messageId: String,
    isStreaming: Boolean,
    inFullScreenHub: Boolean,
    onNotice: (String) -> Unit,
) {
    val proposals = controller.proposalsForMessage(messageId, isStreaming)
    if (proposals.isEmpty()) return
    val scope = rememberCoroutineScope()
    val copiedToast = stringResource(Res.string.agent_proposal_copied_toast)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        proposals.forEach { proposal ->
            val conflict = controller.evaluateProposalConflict(proposal)
            val isReviewing = controller.activeReviewProposalId == proposal.id &&
                proposal.status == AgentProposalStatus.PENDING
            AgentProposalSummaryCard(
                proposal = proposal,
                conflict = conflict,
                inFullScreenHub = inFullScreenHub,
                isReviewing = isReviewing,
                actionsEnabled = !isStreaming && !controller.proposalBusy,
                onOpenDiff = { controller.openProposalDiff(proposal.id) },
                onDiscard = { scope.launch { controller.discardProposal(proposal.id) } },
                onRecalculate = { scope.launch { controller.recalculateProposal(proposal.id) } },
                onCopiedText = { onNotice(copiedToast) },
            )
        }
    }
}

/**
 * 渲染对话流中的结构化修改提案卡片，根据宿主形态自适应切换「在右侧对照审阅 Diff」或「在左侧正文中审阅」。
 */
@OptIn(ExperimentalLayoutApi::class)
@Suppress("DEPRECATION")
@Composable
internal fun AgentProposalSummaryCard(
    proposal: AgentMutationProposal,
    conflict: AgentProposalConflictState,
    inFullScreenHub: Boolean,
    isReviewing: Boolean,
    actionsEnabled: Boolean,
    onOpenDiff: () -> Unit,
    onDiscard: () -> Unit,
    onRecalculate: () -> Unit,
    onCopiedText: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val accentColor = when {
        proposal.status == AgentProposalStatus.DISCARDED -> MaterialTheme.colorScheme.outline
        conflict.hasConflict -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }

    Surface(
        modifier = Modifier.fillMaxWidth().testTag("agent-proposal-card-${proposal.id}"),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(
            width = if (isReviewing) 1.2.dp else 0.8.dp,
            color = if (isReviewing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    painter = painterResource(Res.drawable.ic_diff),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = accentColor,
                )
                Text(
                    text = if (proposal.kind == AgentProposalKind.CREATE_NOTE) {
                        stringResource(Res.string.agent_proposal_card_create_title, proposal.noteTitle)
                    } else {
                        stringResource(Res.string.agent_proposal_card_patch_title, proposal.noteTitle)
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                ProposalStatusBadge(proposal = proposal, conflict = conflict)
            }

            Text(
                text = formatProposalSummaryLine(proposal),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (proposal.status == AgentProposalStatus.PENDING && conflict.hasConflict) {
                val conflictText = when (conflict.kind) {
                    AgentProposalConflictKind.STALE_REVISION -> stringResource(
                        Res.string.agent_diff_conflict_stale,
                        formatShortRevision(conflict.currentRevision),
                        formatShortRevision(proposal.expectedRevision),
                    )
                    AgentProposalConflictKind.UNSAVED_EDITOR_DIRTY -> stringResource(Res.string.agent_diff_conflict_dirty)
                    AgentProposalConflictKind.TARGET_MISSING -> stringResource(Res.string.agent_diff_conflict_missing)
                    AgentProposalConflictKind.NONE -> ""
                }
                Text(
                    text = conflictText,
                    modifier = Modifier.testTag("agent-proposal-conflict-warning-${proposal.id}"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (proposal.status == AgentProposalStatus.PENDING) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val reviewButtonTag = if (inFullScreenHub) {
                        "agent-proposal-review-right-${proposal.id}"
                    } else {
                        "agent-proposal-review-left-${proposal.id}"
                    }
                    Button(
                        onClick = onOpenDiff,
                        enabled = actionsEnabled,
                        modifier = Modifier.height(32.dp).testTag(reviewButtonTag),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        val label = when {
                            isReviewing -> stringResource(Res.string.agent_proposal_reviewing_active)
                            inFullScreenHub -> stringResource(Res.string.agent_proposal_review_right)
                            else -> stringResource(Res.string.agent_proposal_review_left)
                        }
                        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    }

                    OutlinedButton(
                        onClick = onDiscard,
                        enabled = actionsEnabled,
                        modifier = Modifier.height(32.dp).testTag("agent-proposal-discard-${proposal.id}"),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Text(
                            stringResource(Res.string.agent_proposal_action_discard),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }

                    if (conflict.hasConflict && conflict.kind != AgentProposalConflictKind.TARGET_MISSING) {
                        OutlinedButton(
                            onClick = onRecalculate,
                            enabled = actionsEnabled,
                            modifier = Modifier.height(32.dp).testTag("agent-proposal-recalc-${proposal.id}"),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Icon(painterResource(Res.drawable.ic_refresh), null, Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                stringResource(Res.string.agent_proposal_action_recalc),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }

                    if (conflict.hasConflict) {
                        TextButton(
                            onClick = {
                                runCatching {
                                    clipboard.setText(AnnotatedString(formatProposalSuggestedText(proposal)))
                                }
                                onCopiedText()
                            },
                            modifier = Modifier.height(32.dp).testTag("agent-proposal-copy-${proposal.id}"),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Icon(painterResource(Res.drawable.ic_copy), null, Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                stringResource(Res.string.agent_proposal_action_copy_text),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 渲染提案卡片右上角的基准版本一致性或结案状态徽标。
 */
@Composable
private fun ProposalStatusBadge(
    proposal: AgentMutationProposal,
    conflict: AgentProposalConflictState,
) {
    val (bgColor: Color, fgColor: Color, text: String) = when (proposal.status) {
        AgentProposalStatus.APPLIED -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            stringResource(Res.string.agent_proposal_status_applied, formatShortRevision(proposal.appliedRevision)),
        )
        AgentProposalStatus.DISCARDED -> Triple(
            MaterialTheme.colorScheme.surfaceContainerHighest,
            MaterialTheme.colorScheme.onSurfaceVariant,
            stringResource(Res.string.agent_proposal_status_discarded),
        )
        AgentProposalStatus.PENDING -> when (conflict.kind) {
            AgentProposalConflictKind.STALE_REVISION -> Triple(
                MaterialTheme.colorScheme.errorContainer,
                MaterialTheme.colorScheme.onErrorContainer,
                stringResource(Res.string.agent_proposal_status_stale, formatShortRevision(proposal.expectedRevision)),
            )
            AgentProposalConflictKind.UNSAVED_EDITOR_DIRTY -> Triple(
                MaterialTheme.colorScheme.errorContainer,
                MaterialTheme.colorScheme.onErrorContainer,
                stringResource(Res.string.agent_proposal_status_dirty),
            )
            AgentProposalConflictKind.TARGET_MISSING -> Triple(
                MaterialTheme.colorScheme.errorContainer,
                MaterialTheme.colorScheme.onErrorContainer,
                stringResource(Res.string.agent_proposal_status_missing),
            )
            AgentProposalConflictKind.NONE -> if (proposal.kind == AgentProposalKind.CREATE_NOTE) {
                Triple(
                    MaterialTheme.colorScheme.secondaryContainer,
                    MaterialTheme.colorScheme.onSecondaryContainer,
                    stringResource(Res.string.agent_proposal_status_new_draft),
                )
            } else {
                Triple(
                    MaterialTheme.colorScheme.surface,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    stringResource(Res.string.agent_proposal_status_base_ok, formatShortRevision(proposal.expectedRevision)),
                )
            }
        }
    }

    Surface(
        color = bgColor,
        contentColor = fgColor,
        shape = MaterialTheme.shapes.extraSmall,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

/**
 * 格式化提案包含的元数据与局部块补丁统计摘要行。
 */
@Composable
private fun formatProposalSummaryLine(proposal: AgentMutationProposal): String {
    val appliedSummary = proposal.appliedSummary
    if (proposal.status == AgentProposalStatus.APPLIED && !appliedSummary.isNullOrBlank()) {
        return if (appliedSummary.all { it.isDigit() || it == '/' || it == ' ' }) {
            stringResource(Res.string.agent_proposal_summary_applied, appliedSummary.trim())
        } else {
            appliedSummary
        }
    }
    if (proposal.kind == AgentProposalKind.CREATE_NOTE) {
        return stringResource(Res.string.agent_proposal_summary_create, proposal.items.size)
    }
    val metaCount = proposal.metadataItemCount
    val blockCount = proposal.blockItemCount
    return when {
        metaCount > 0 && blockCount > 0 -> stringResource(Res.string.agent_proposal_summary_both, metaCount, blockCount)
        metaCount > 0 -> stringResource(Res.string.agent_proposal_summary_meta_only, metaCount)
        else -> stringResource(Res.string.agent_proposal_summary_blocks_only, blockCount)
    }
}
