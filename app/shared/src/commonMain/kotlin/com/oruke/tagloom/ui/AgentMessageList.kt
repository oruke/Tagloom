package com.oruke.tagloom.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.tagloom.core.ai.AgentCitationStatus
import com.oruke.tagloom.core.ai.AgentHarnessProgress
import com.oruke.tagloom.core.ai.AgentReadToolCatalog
import com.oruke.tagloom.core.ai.AgentSourceEntry
import com.oruke.tagloom.core.ai.AgentThreadMessage
import com.oruke.tagloom.core.ai.AgentToolTrace
import com.oruke.tagloom.core.ai.AgentVerifiedTextSegment
import com.oruke.tagloom.core.ai.AiChatRole
import com.oruke.tagloom.core.ai.SourceLedger
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.Res
import tagloom.app.shared.generated.resources.agent_empty_welcome_subtitle
import tagloom.app.shared.generated.resources.agent_empty_welcome_title
import tagloom.app.shared.generated.resources.agent_msg_action_append
import tagloom.app.shared.generated.resources.agent_msg_action_copy
import tagloom.app.shared.generated.resources.agent_msg_action_save_new
import tagloom.app.shared.generated.resources.agent_msg_appended_toast
import tagloom.app.shared.generated.resources.agent_msg_copied_toast
import tagloom.app.shared.generated.resources.agent_msg_saved_new_toast
import tagloom.app.shared.generated.resources.agent_msg_stopped_badge
import tagloom.app.shared.generated.resources.agent_msg_thinking_title
import tagloom.app.shared.generated.resources.ic_copy
import tagloom.app.shared.generated.resources.ic_note_save
import tagloom.app.shared.generated.resources.ic_plus
import tagloom.app.shared.generated.resources.ic_sparkle

/**
 * 渲染会话历史消息流、流式进行中卡片、可折叠只读轨迹条、`SourceLedger` 防幻觉来源角标与纯图标消息动作栏。
 */
@Composable
internal fun AgentMessageList(
    messages: List<AgentThreadMessage>,
    liveProgress: AgentHarnessProgress?,
    citationStatuses: Map<Int, AgentCitationStatus>,
    onJumpToCitation: (AgentSourceEntry) -> Unit,
    onAppendToNote: (String) -> Boolean,
    onSaveAsNewNote: (String, String) -> Unit,
    proposalSlot: (@Composable (messageId: String, isStreaming: Boolean, onNotice: (String) -> Unit) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var actionNotice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(messages.size, liveProgress?.streamingContent?.length) {
        val totalItems = messages.size + if (liveProgress != null) 1 else 0
        if (totalItems > 0) {
            listState.animateScrollToItem(totalItems - 1)
        }
    }

    Box(modifier.fillMaxSize()) {
        if (messages.isEmpty() && liveProgress == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp)
                    .testTag("agent-empty-welcome"),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    painter = painterResource(Res.drawable.ic_sparkle),
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(Res.string.agent_empty_welcome_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(Res.string.agent_empty_welcome_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            val copiedLabel = stringResource(Res.string.agent_msg_copied_toast)
            val appendedLabel = stringResource(Res.string.agent_msg_appended_toast)
            val savedNewLabel = stringResource(Res.string.agent_msg_saved_new_toast)
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().testTag("agent-message-list"),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(messages, key = { it.id }) { msg ->
                    if (msg.role == AiChatRole.USER) {
                        UserMessageBubble(msg)
                    } else {
                        AssistantMessageCard(
                            messageId = msg.id,
                            content = msg.content,
                            thinkingSummary = msg.thinkingSummary,
                            toolTraces = msg.toolTraces,
                            sources = msg.sources,
                            stoppedByUser = msg.stoppedByUser,
                            guardrailNotice = msg.guardrailNotice,
                            isStreaming = false,
                            citationStatuses = citationStatuses,
                            onJumpToCitation = onJumpToCitation,
                            onCopied = { actionNotice = copiedLabel },
                            onAppend = {
                                if (onAppendToNote(msg.content)) actionNotice = appendedLabel
                            },
                            onSaveNew = {
                                val title = msg.content.lineSequence()
                                    .map { it.trim().removePrefix("#").trim() }
                                    .firstOrNull { it.isNotBlank() }
                                    ?.take(36)
                                    .orEmpty()
                                onSaveAsNewNote(msg.content, title)
                                actionNotice = savedNewLabel
                            },
                            proposalContent = proposalSlot?.let { slot ->
                                { slot(msg.id, false) { toast -> actionNotice = toast } }
                            },
                        )
                    }
                }
                if (liveProgress != null) {
                    item(key = "live-streaming-progress") {
                        AssistantMessageCard(
                            messageId = liveProgress.assistantMessageId,
                            content = liveProgress.streamingContent,
                            thinkingSummary = liveProgress.streamingThinking,
                            toolTraces = liveProgress.toolTraces,
                            sources = liveProgress.sources,
                            stoppedByUser = false,
                            guardrailNotice = null,
                            isStreaming = true,
                            citationStatuses = citationStatuses,
                            onJumpToCitation = onJumpToCitation,
                            onCopied = {},
                            onAppend = {},
                            onSaveNew = {},
                            proposalContent = proposalSlot?.let { slot ->
                                { slot(liveProgress.assistantMessageId, true) { toast -> actionNotice = toast } }
                            },
                        )
                    }
                }
            }
        }

        actionNotice?.let { notice ->
            LaunchedEffect(notice) {
                kotlinx.coroutines.delay(1800)
                actionNotice = null
            }
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp)
                    .testTag("agent-action-toast"),
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = notice,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/**
 * 渲染右对齐的用户提问气泡及其冻结 `@` 引用标签行。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UserMessageBubble(message: AgentThreadMessage) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag("agent-user-msg-${message.id}"),
        horizontalAlignment = Alignment.End,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 560.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (message.contextRefs.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        message.contextRefs.forEach { ref ->
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                shape = MaterialTheme.shapes.extraSmall,
                                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                            ) {
                                Text(
                                    text = ref.label,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/**
 * 渲染助手回复卡片，包含可折叠轨迹条、可折叠思考摘要、`SourceLedger` 核验角标及纯图标动作条。
 */
@OptIn(ExperimentalLayoutApi::class)
@Suppress("DEPRECATION")
@Composable
private fun AssistantMessageCard(
    messageId: String,
    content: String,
    thinkingSummary: String,
    toolTraces: List<AgentToolTrace>,
    sources: List<AgentSourceEntry>,
    stoppedByUser: Boolean,
    guardrailNotice: String?,
    isStreaming: Boolean,
    citationStatuses: Map<Int, AgentCitationStatus>,
    onJumpToCitation: (AgentSourceEntry) -> Unit,
    onCopied: () -> Unit,
    onAppend: () -> Unit,
    onSaveNew: () -> Unit,
    proposalContent: (@Composable () -> Unit)? = null,
) {
    val clipboard = LocalClipboardManager.current
    var tracesExpanded by remember(messageId) { mutableStateOf(false) }
    var thinkingExpanded by remember(messageId) { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth().testTag("agent-assistant-msg-$messageId"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (toolTraces.isNotEmpty()) {
            val summaryText = AgentReadToolCatalog.summarizeTrajectory(toolTraces)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { tracesExpanded = !tracesExpanded }
                    .testTag("agent-trajectory-strip-$messageId"),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = summaryText,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = if (tracesExpanded) "▴" else "▾",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (tracesExpanded) {
                        HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        toolTraces.forEach { trace ->
                            Text(
                                text = "• ${trace.summary}",
                                modifier = Modifier.padding(vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        if (thinkingSummary.isNotBlank()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { thinkingExpanded = !thinkingExpanded }
                    .testTag("agent-thinking-strip-$messageId"),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.small,
            ) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text(
                        text = "${stringResource(Res.string.agent_msg_thinking_title)} ${if (thinkingExpanded) "▴" else "▾"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (thinkingExpanded) {
                        Text(
                            text = thinkingSummary,
                            modifier = Modifier.padding(top = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        val segments = remember(content, sources) { SourceLedger.parseSegments(content, sources) }
        if (segments.isNotEmpty()) {
            val inlineMap = mutableMapOf<String, InlineTextContent>()
            val annotated = buildAnnotatedString {
                segments.forEachIndexed { idx, segment ->
                    when (segment) {
                        is AgentVerifiedTextSegment.PlainText -> append(segment.text)
                        is AgentVerifiedTextSegment.VerifiedCitation -> {
                            val inlineId = "cite-$messageId-$idx"
                            val widthSp = (22 + segment.sourceIndex.toString().length * 7).sp
                            inlineMap[inlineId] = InlineTextContent(
                                Placeholder(
                                    width = widthSp,
                                    height = 20.sp,
                                    placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
                                ),
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CitationBadgeWithPreview(
                                        keySuffix = "$messageId-$idx",
                                        entry = segment.entry,
                                        status = citationStatuses[segment.sourceIndex] ?: AgentCitationStatus.VERIFIED,
                                        onJumpToCitation = onJumpToCitation,
                                    )
                                }
                            }
                            appendInlineContent(inlineId, "[${segment.sourceIndex}]")
                        }
                    }
                }
            }
            Text(
                text = annotated,
                inlineContent = inlineMap,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        } else if (isStreaming) {
            Text("…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (stoppedByUser) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.extraSmall,
            ) {
                Text(
                    text = stringResource(Res.string.agent_msg_stopped_badge),
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (!guardrailNotice.isNullOrBlank()) {
            Text(
                text = guardrailNotice,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        proposalContent?.invoke()

        if (!isStreaming && content.isNotBlank()) {
            Row(
                modifier = Modifier.testTag("agent-msg-actions-$messageId"),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        runCatching { clipboard.setText(AnnotatedString(content)) }
                        onCopied()
                    },
                    modifier = Modifier.size(28.dp).testTag("agent-msg-copy-$messageId"),
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_copy),
                        contentDescription = stringResource(Res.string.agent_msg_action_copy),
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = onAppend,
                    modifier = Modifier.size(28.dp).testTag("agent-msg-append-$messageId"),
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_plus),
                        contentDescription = stringResource(Res.string.agent_msg_action_append),
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = onSaveNew,
                    modifier = Modifier.size(28.dp).testTag("agent-msg-save-new-$messageId"),
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_note_save),
                        contentDescription = stringResource(Res.string.agent_msg_action_save_new),
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
