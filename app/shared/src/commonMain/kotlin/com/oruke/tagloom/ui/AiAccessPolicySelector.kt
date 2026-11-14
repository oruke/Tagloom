package com.oruke.tagloom.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.domain.tag.AiAccessPolicyResolver
import com.oruke.tagloom.core.model.AiAccessPolicy
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.core.model.TagId
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import com.oruke.tagloom.editor.EditorDocument
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.editor.resolveNoteAiAccessPolicy
import com.oruke.tagloom.editor.updateSelectedNoteAiAccessPolicy
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.Res
import tagloom.app.shared.generated.resources.agent_policy_allow_short
import tagloom.app.shared.generated.resources.agent_policy_header_allow
import tagloom.app.shared.generated.resources.agent_policy_header_locked
import tagloom.app.shared.generated.resources.agent_policy_header_ref_only
import tagloom.app.shared.generated.resources.agent_policy_inherit_default
import tagloom.app.shared.generated.resources.agent_policy_locked_short
import tagloom.app.shared.generated.resources.agent_policy_option_allow
import tagloom.app.shared.generated.resources.agent_policy_option_allow_desc
import tagloom.app.shared.generated.resources.agent_policy_option_locked
import tagloom.app.shared.generated.resources.agent_policy_option_locked_desc
import tagloom.app.shared.generated.resources.agent_policy_option_ref_only
import tagloom.app.shared.generated.resources.agent_policy_option_ref_only_desc
import tagloom.app.shared.generated.resources.agent_policy_provenance_inherited
import tagloom.app.shared.generated.resources.agent_policy_provenance_override
import tagloom.app.shared.generated.resources.agent_policy_ref_only_short
import tagloom.app.shared.generated.resources.agent_policy_supertag_impact
import tagloom.app.shared.generated.resources.agent_policy_supertag_section_hint
import tagloom.app.shared.generated.resources.agent_policy_supertag_section_title
import tagloom.app.shared.generated.resources.ic_lock

/**
 * 渲染紧凑的三态 `AiAccessPolicy` 标签徽章，供侧栏、全部笔记与 `@` 选择器共用。
 *
 * @param policy 笔记或类型的有效策略。
 * @param showAllow 为 `false` 时隐藏默认的 `ALLOW` 徽章以保持侧栏清爽，仅凸显 `仅@` 与 `禁读`。
 * @param modifier 外部修饰符。
 */
@Composable
internal fun AiAccessPolicyBadge(
    policy: AiAccessPolicy,
    showAllow: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (policy == AiAccessPolicy.ALLOW && !showAllow) return
    val (bg, fg, label) = when (policy) {
        AiAccessPolicy.ALLOW -> Triple(
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
            MaterialTheme.colorScheme.onSecondaryContainer,
            stringResource(Res.string.agent_policy_allow_short),
        )
        AiAccessPolicy.REFERENCE_ONLY -> Triple(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            stringResource(Res.string.agent_policy_ref_only_short),
        )
        AiAccessPolicy.LOCKED -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            stringResource(Res.string.agent_policy_locked_short),
        )
    }
    Surface(
        modifier = modifier,
        color = bg,
        contentColor = fg,
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (policy == AiAccessPolicy.LOCKED) {
                Icon(painterResource(Res.drawable.ic_lock), contentDescription = null, modifier = Modifier.size(11.dp))
            }
            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
        }
    }
}

/**
 * 渲染笔记页头右侧的当前笔记 `AiAccessPolicy` 选择器与继承归因弹层。
 *
 * @param state 编辑器状态。
 * @param document 当前笔记文档。
 * @param onSaveRequest 修改策略后触发立即落盘回调。
 */
@Composable
internal fun NoteAiAccessPolicyControl(
    state: TagloomEditorState,
    document: EditorDocument,
    onSaveRequest: () -> Unit,
) {
    var expanded by remember(document.id) { mutableStateOf(false) }
    val resolved = remember(document.note.aiAccessPolicy, document.note.directTagIds, state.tagDefinitions.toList()) {
        state.resolveNoteAiAccessPolicy(document.note)
    }
    val headerLabel = when (resolved.effectivePolicy) {
        AiAccessPolicy.ALLOW -> stringResource(Res.string.agent_policy_header_allow)
        AiAccessPolicy.REFERENCE_ONLY -> stringResource(Res.string.agent_policy_header_ref_only)
        AiAccessPolicy.LOCKED -> stringResource(Res.string.agent_policy_header_locked)
    }
    val chipColor = when (resolved.effectivePolicy) {
        AiAccessPolicy.ALLOW -> MaterialTheme.colorScheme.surfaceContainerLow
        AiAccessPolicy.REFERENCE_ONLY -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f)
        AiAccessPolicy.LOCKED -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f)
    }
    val textColor = when (resolved.effectivePolicy) {
        AiAccessPolicy.ALLOW -> MaterialTheme.colorScheme.onSurfaceVariant
        AiAccessPolicy.REFERENCE_ONLY -> MaterialTheme.colorScheme.onTertiaryContainer
        AiAccessPolicy.LOCKED -> MaterialTheme.colorScheme.onErrorContainer
    }
    val inheritedNames = resolved.inheritedFromTagIds.mapNotNull { id ->
        state.tagDefinitions.firstOrNull { it.id == id }?.name
    }.ifEmpty { listOf("Note") }.joinToString(" / ")

    Box {
        Surface(
            modifier = Modifier
                .clickable { expanded = true }
                .testTag("note-ai-policy-button"),
            color = chipColor,
            contentColor = textColor,
            shape = MaterialTheme.shapes.small,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Text(
                text = headerLabel,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        TagloomDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            Column(Modifier.widthIn(min = 280.dp, max = 340.dp)) {
                Text(
                    text = if (resolved.overriddenOnNote) {
                        stringResource(Res.string.agent_policy_provenance_override)
                    } else {
                        stringResource(Res.string.agent_policy_provenance_inherited, inheritedNames)
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TagloomMenuDivider()
                TagloomMenuItem(
                    text = stringResource(Res.string.agent_policy_inherit_default),
                    selected = document.note.aiAccessPolicy == null,
                    onClick = {
                        expanded = false
                        state.updateSelectedNoteAiAccessPolicy(null)
                        onSaveRequest()
                    },
                )
                TagloomMenuDivider()
                PolicyChoiceMenuItem(
                    title = stringResource(Res.string.agent_policy_option_allow),
                    description = stringResource(Res.string.agent_policy_option_allow_desc),
                    selected = document.note.aiAccessPolicy == AiAccessPolicy.ALLOW,
                    testTag = "note-ai-policy-allow",
                    onClick = {
                        expanded = false
                        state.updateSelectedNoteAiAccessPolicy(AiAccessPolicy.ALLOW)
                        onSaveRequest()
                    },
                )
                PolicyChoiceMenuItem(
                    title = stringResource(Res.string.agent_policy_option_ref_only),
                    description = stringResource(Res.string.agent_policy_option_ref_only_desc),
                    selected = document.note.aiAccessPolicy == AiAccessPolicy.REFERENCE_ONLY,
                    testTag = "note-ai-policy-ref-only",
                    onClick = {
                        expanded = false
                        state.updateSelectedNoteAiAccessPolicy(AiAccessPolicy.REFERENCE_ONLY)
                        onSaveRequest()
                    },
                )
                PolicyChoiceMenuItem(
                    title = stringResource(Res.string.agent_policy_option_locked),
                    description = stringResource(Res.string.agent_policy_option_locked_desc),
                    selected = document.note.aiAccessPolicy == AiAccessPolicy.LOCKED,
                    testTag = "note-ai-policy-locked",
                    onClick = {
                        expanded = false
                        state.updateSelectedNoteAiAccessPolicy(AiAccessPolicy.LOCKED)
                        onSaveRequest()
                    },
                )
            }
        }
    }
}

/**
 * 渲染超级标签（笔记类型）维护页中的默认 `AiAccessPolicy` 配置区与子类型继承影响预览。
 */
@Composable
internal fun SupertagAiAccessPolicySection(
    state: TagloomEditorState,
    original: TagDefinition,
    parentTagIds: List<TagId>,
    selectedPolicy: AiAccessPolicy,
    enabled: Boolean,
    onSelectPolicy: (AiAccessPolicy) -> Unit,
) {
    val impact = remember(original.id, parentTagIds, selectedPolicy, state.tagDefinitions.toList(), state.documents.toList()) {
        val tagsMap = state.tagDefinitions.associateBy { it.id }.toMutableMap()
        tagsMap[original.id] = original.copy(parentTagIds = parentTagIds, aiAccessPolicy = original.aiAccessPolicy)
        val notes = state.documents.map { it.id to it.note }
        AiAccessPolicyResolver(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID)
            .previewTagImpact(original.id, selectedPolicy, tagsMap, notes)
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(Res.string.agent_policy_supertag_section_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(Res.string.agent_policy_supertag_section_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        listOf(
            Triple(AiAccessPolicy.ALLOW, Res.string.agent_policy_option_allow, Res.string.agent_policy_option_allow_desc),
            Triple(AiAccessPolicy.REFERENCE_ONLY, Res.string.agent_policy_option_ref_only, Res.string.agent_policy_option_ref_only_desc),
            Triple(AiAccessPolicy.LOCKED, Res.string.agent_policy_option_locked, Res.string.agent_policy_option_locked_desc),
        ).forEach { (policy, titleRes, descRes) ->
            val selected = selectedPolicy == policy
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) { onSelectPolicy(policy) }
                    .testTag("supertag-ai-policy-${policy.name.lowercase()}"),
                shape = MaterialTheme.shapes.small,
                color = if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                else MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(
                    1.dp,
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = if (enabled) ({ onSelectPolicy(policy) }) else null)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(titleRes), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                            AiAccessPolicyBadge(policy = policy, showAllow = true)
                        }
                        Text(
                            stringResource(descRes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Text(
            stringResource(
                Res.string.agent_policy_supertag_impact,
                impact.descendantTagIds.size,
                impact.inheritingNoteCount,
                impact.changedNoteCount,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag("supertag-ai-policy-impact"),
        )
    }
}

@Composable
private fun PolicyChoiceMenuItem(
    title: String,
    description: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    Box(Modifier.testTag(testTag)) {
        TagloomMenuItem(
            text = title,
            description = description,
            selected = selected,
            onClick = onClick,
        )
    }
}
