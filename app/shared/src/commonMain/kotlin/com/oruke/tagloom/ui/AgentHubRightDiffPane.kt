package com.oruke.tagloom.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.ai.AgentMutationProposal
import com.oruke.tagloom.editor.EditorDocument
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.Res
import tagloom.app.shared.generated.resources.agent_diff_back_to_chat
import tagloom.app.shared.generated.resources.agent_diff_exit_review
import tagloom.app.shared.generated.resources.agent_diff_open_in_note_page
import tagloom.app.shared.generated.resources.ic_close
import tagloom.app.shared.generated.resources.ic_open_external

/**
 * 渲染中央全屏 Agent 工作台右侧滑出的原位 Diff 审阅面板（窄屏 `< 720.dp` 时作为全屏审阅浮层展示）。
 *
 * 顶部提供 `[↗ 在笔记页打开]` 穿透按钮，点击后无缝切换至「左侧笔记编辑页原位 Diff + 右侧 Agent 伴随栏」，
 * 且逐项「采纳 / 跳过」勾选状态 100% 保留。
 *
 * @param controller 双形态 Agent 控制器。
 * @param proposal 当前正在审阅的修改提案。
 * @param targetDocument 编辑器中的目标笔记快照。
 * @param isCompactOverlay 当前是否运行在 `< 720.dp` 全屏审阅浮层形态。
 * @param modifier 外部尺寸与布局修饰符。
 */
@Composable
internal fun AgentHubRightDiffPane(
    controller: WorkspaceAgentHarnessController,
    proposal: AgentMutationProposal,
    targetDocument: EditorDocument?,
    isCompactOverlay: Boolean,
    modifier: Modifier = Modifier,
) {
    val rootTag = if (isCompactOverlay) "agent-hub-compact-diff-overlay" else "agent-hub-right-diff-pane"

    Surface(
        modifier = modifier.fillMaxHeight().testTag(rootTag),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (isCompactOverlay) {
                    TextButton(
                        onClick = controller::closeProposalDiff,
                        modifier = Modifier.height(30.dp).testTag("agent-diff-compact-back-button"),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = stringResource(Res.string.agent_diff_back_to_chat),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                Text(
                    text = proposal.noteTitle,
                    modifier = Modifier.weight(1f).padding(start = if (isCompactOverlay) 0.dp else 4.dp),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                OutlinedButton(
                    onClick = { controller.openDiffInNotePage(proposal.id) },
                    modifier = Modifier.height(30.dp).testTag("agent-diff-open-in-note-button"),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_open_external),
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(Res.string.agent_diff_open_in_note_page),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                if (!isCompactOverlay) {
                    IconButton(
                        onClick = controller::closeProposalDiff,
                        modifier = Modifier.size(30.dp).testTag("agent-diff-close-pane-button"),
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_close),
                            contentDescription = stringResource(Res.string.agent_diff_exit_review),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            AgentSharedDiffDocumentView(
                controller = controller,
                proposal = proposal,
                targetDocument = targetDocument,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                modifier = Modifier.weight(1f),
            )
        }
    }
}
