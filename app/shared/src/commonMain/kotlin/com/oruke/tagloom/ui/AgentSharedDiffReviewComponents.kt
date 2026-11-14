package com.oruke.tagloom.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.ai.AgentMutationProposal
import com.oruke.tagloom.core.ai.AgentProposalItem
import com.oruke.tagloom.core.ai.AgentProposalItemKind
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.editor.EditorDocument
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.Res
import tagloom.app.shared.generated.resources.agent_diff_apply_selected
import tagloom.app.shared.generated.resources.agent_diff_banner_subtitle
import tagloom.app.shared.generated.resources.agent_diff_banner_title
import tagloom.app.shared.generated.resources.agent_diff_conflict_dirty
import tagloom.app.shared.generated.resources.agent_diff_conflict_missing
import tagloom.app.shared.generated.resources.agent_diff_conflict_stale
import tagloom.app.shared.generated.resources.agent_diff_empty_old_value
import tagloom.app.shared.generated.resources.agent_diff_error_apply_failed
import tagloom.app.shared.generated.resources.agent_diff_exit_review
import tagloom.app.shared.generated.resources.agent_diff_item_accept
import tagloom.app.shared.generated.resources.agent_diff_item_skip
import tagloom.app.shared.generated.resources.agent_diff_removed_new_value
import tagloom.app.shared.generated.resources.agent_diff_untouched_caption
import tagloom.app.shared.generated.resources.agent_proposal_action_copy_text
import tagloom.app.shared.generated.resources.agent_proposal_action_recalc
import tagloom.app.shared.generated.resources.agent_proposal_copied_toast
import tagloom.app.shared.generated.resources.ic_copy
import tagloom.app.shared.generated.resources.ic_refresh

/**
 * 全屏 Agent 工作台右侧滑出面板与笔记编辑页左侧正文区 100% 共用的原位对照 Diff 文档视图。
 *
 * @param controller 双形态 Agent 控制器。
 * @param proposal 当前正在审阅的修改提案。
 * @param targetDocument 编辑器中的目标笔记快照（新建笔记提案或目标缺失时为空）。
 * @param contentPadding 滚动容器内边距。
 * @param modifier 外部布局修饰符。
 */
@Composable
internal fun AgentSharedDiffDocumentView(
    controller: WorkspaceAgentHarnessController,
    proposal: AgentMutationProposal,
    targetDocument: EditorDocument?,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val conflict = controller.evaluateProposalConflict(proposal)
    var copiedNotice by remember { mutableStateOf<String?>(null) }
    val copiedLabel = stringResource(Res.string.agent_proposal_copied_toast)

    val rows = remember(proposal, targetDocument?.note) {
        buildDiffCanvasRows(proposal, targetDocument)
    }
    val titleItem = remember(proposal.items) {
        proposal.items.firstOrNull { it.kind == AgentProposalItemKind.TITLE }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("agent-shared-diff-canvas"),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "diff-review-banner") {
            AgentDiffReviewBanner(
                proposal = proposal,
                conflict = conflict,
                busy = controller.proposalBusy,
                errorNoticeCode = controller.proposalNoticeCode,
                copiedNotice = copiedNotice,
                onApplySelected = { scope.launch { controller.applyProposal(proposal.id) } },
                onExitReview = controller::closeProposalDiff,
                onRecalculate = { scope.launch { controller.recalculateProposal(proposal.id) } },
                onCopiedText = { copiedNotice = copiedLabel },
            )
        }

        item(key = "diff-note-title-header") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = proposal.noteTitle,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (titleItem != null) {
                    AgentDiffItemCard(
                        item = titleItem,
                        enabled = !controller.proposalBusy,
                        onToggleAccepted = { accepted ->
                            scope.launch { controller.toggleProposalItem(proposal.id, titleItem.id, accepted) }
                        },
                    )
                }
            }
        }

        items(rows, key = { it.key }) { row ->
            when (row) {
                is DiffCanvasRow.PatchItemRow -> {
                    AgentDiffItemCard(
                        item = row.item,
                        enabled = !controller.proposalBusy,
                        onToggleAccepted = { accepted ->
                            scope.launch { controller.toggleProposalItem(proposal.id, row.item.id, accepted) }
                        },
                    )
                }
                is DiffCanvasRow.UntouchedBlockRow -> {
                    UntouchedContextBlockCard(row.block)
                }
            }
        }
    }
}

/**
 * 渲染原位对照审阅顶部汇总横幅（含乐观锁冲突拦截提示、重算提案与单事务提交按钮）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Suppress("DEPRECATION")
@Composable
internal fun AgentDiffReviewBanner(
    proposal: AgentMutationProposal,
    conflict: AgentProposalConflictState,
    busy: Boolean,
    errorNoticeCode: String?,
    copiedNotice: String?,
    onApplySelected: () -> Unit,
    onExitReview: () -> Unit,
    onRecalculate: () -> Unit,
    onCopiedText: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    if (copiedNotice != null) {
        LaunchedEffect(copiedNotice) { delay(1800) }
    }

    val containerColor = if (conflict.hasConflict) {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.48f)
    }
    val borderColor = if (conflict.hasConflict) {
        MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
    }

    Surface(
        modifier = Modifier.fillMaxWidth().testTag("agent-diff-review-banner"),
        color = containerColor,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(
                    Res.string.agent_diff_banner_title,
                    proposal.acceptedItemCount,
                    proposal.items.size,
                ),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(Res.string.agent_diff_banner_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (conflict.hasConflict) {
                val conflictMsg = when (conflict.kind) {
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
                    text = conflictMsg,
                    modifier = Modifier.testTag("agent-diff-banner-conflict"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium,
                )
            } else if (errorNoticeCode == "apply_failed") {
                Text(
                    text = stringResource(Res.string.agent_diff_error_apply_failed),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (copiedNotice != null) {
                Text(
                    text = copiedNotice,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Button(
                    onClick = onApplySelected,
                    enabled = !busy && !conflict.hasConflict && proposal.acceptedItemCount > 0,
                    modifier = Modifier.height(34.dp).testTag("agent-diff-apply-button"),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                ) {
                    Text(
                        stringResource(Res.string.agent_diff_apply_selected, proposal.acceptedItemCount),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                OutlinedButton(
                    onClick = onExitReview,
                    enabled = !busy,
                    modifier = Modifier.height(34.dp).testTag("agent-diff-exit-button"),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text(stringResource(Res.string.agent_diff_exit_review), style = MaterialTheme.typography.labelMedium)
                }

                if (conflict.hasConflict && conflict.kind != AgentProposalConflictKind.TARGET_MISSING) {
                    OutlinedButton(
                        onClick = onRecalculate,
                        enabled = !busy,
                        modifier = Modifier.height(34.dp).testTag("agent-diff-recalc-button"),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
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
                            runCatching { clipboard.setText(AnnotatedString(formatProposalSuggestedText(proposal))) }
                            onCopiedText()
                        },
                        modifier = Modifier.height(34.dp).testTag("agent-diff-copy-button"),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
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

/**
 * 渲染单个细粒度元数据或块级补丁卡片，带右上角独立「采纳 / 跳过」分段开关与红绿新旧值对照区。
 */
@Composable
internal fun AgentDiffItemCard(
    item: AgentProposalItem,
    enabled: Boolean,
    onToggleAccepted: (Boolean) -> Unit,
) {
    val showOldRow = item.oldPreview.isNotBlank() ||
        item.kind == AgentProposalItemKind.REPLACE_BLOCK ||
        item.kind == AgentProposalItemKind.REMOVE_BLOCK ||
        item.kind == AgentProposalItemKind.PROPERTY ||
        item.kind == AgentProposalItemKind.CUSTOM_TAG
    val showNewRow = item.kind != AgentProposalItemKind.REMOVE_BLOCK || item.newPreview.isNotBlank()

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (item.accepted) 1f else 0.62f)
            .testTag("agent-diff-item-${item.id}"),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(
            width = 1.dp,
            color = if (item.accepted) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = item.toolLabel,
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "· ${item.label}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AcceptSkipToggleGroup(
                    itemId = item.id,
                    accepted = item.accepted,
                    enabled = enabled,
                    onToggleAccepted = onToggleAccepted,
                )
            }

            if (showOldRow) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFFD93838).copy(alpha = 0.08f),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = item.oldPreview.ifBlank { stringResource(Res.string.agent_diff_empty_old_value) },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall.copy(
                            textDecoration = if (item.oldPreview.isNotBlank()) TextDecoration.LineThrough else TextDecoration.None,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (showNewRow) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = item.newPreview.ifBlank { stringResource(Res.string.agent_diff_removed_new_value) },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/**
 * 渲染变更项右上角的「采纳 / 跳过」双态切换胶囊按钮组。
 */
@Composable
private fun AcceptSkipToggleGroup(
    itemId: String,
    accepted: Boolean,
    enabled: Boolean,
    onToggleAccepted: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Surface(
            modifier = Modifier
                .clickable(enabled = enabled) { onToggleAccepted(true) }
                .testTag("agent-diff-accept-$itemId"),
            color = if (accepted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (accepted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            shape = MaterialTheme.shapes.extraSmall,
        ) {
            Text(
                text = stringResource(Res.string.agent_diff_item_accept),
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Surface(
            modifier = Modifier
                .clickable(enabled = enabled) { onToggleAccepted(false) }
                .testTag("agent-diff-skip-$itemId"),
            color = if (!accepted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = if (!accepted) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            shape = MaterialTheme.shapes.extraSmall,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Text(
                text = stringResource(Res.string.agent_diff_item_skip),
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/**
 * 渲染正文中未被补丁修改的上下文段落，明确标示稳定 `BlockId` 与「未改动 · 原样保留」。
 */
@Composable
private fun UntouchedContextBlockCard(block: Block) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .testTag("agent-diff-untouched-${block.id.value}"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "BlockId: ${block.id.value}",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.outline,
            )
            Text(
                text = stringResource(Res.string.agent_diff_untouched_caption),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Text(
            text = formatBlockContextText(block),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
