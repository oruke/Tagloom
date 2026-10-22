package com.oruke.tagloom.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.model.PropertyOptionInputMode
import com.oruke.tagloom.editor.PropertyOptionDraft
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 渲染 select 类属性的有序选项草稿列表与校验反馈。
 *
 * @param options 当前有序选项草稿。
 * @param inputMode 固定模式要求至少一个选项，自由模式允许从空目录开始。
 * @param hasBlankOption 是否存在空名称。
 * @param hasDuplicateOption 是否存在不区分大小写的重复名称。
 * @param onLabelChange 更新一个选项名称。
 * @param onRemove 移除一个选项。
 * @param onAdd 追加新选项。
 * @param hierarchical 是否显示父级目录选择。
 * @param onParentChange 修改当前项父级。
 * @param usedOptionIds 已被当前笔记引用的选项，用于解释禁止删除。
 */
@Composable
internal fun PropertyOptionDraftList(
    options: List<PropertyOptionDraft>,
    inputMode: PropertyOptionInputMode,
    hasBlankOption: Boolean,
    hasDuplicateOption: Boolean,
    onLabelChange: (Int, String) -> Unit,
    onRemove: (Int) -> Unit,
    onAdd: () -> Unit,
    hierarchical: Boolean = false,
    onParentChange: (Int, String?) -> Unit = { _, _ -> },
    usedOptionIds: Set<String> = emptySet(),
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.editor_property_options), style = MaterialTheme.typography.titleSmall)
        options.forEachIndexed { index, option ->
            val identity = optionDraftIdentity(option, index)
            val hasChildren = options.any { it.parentOptionId == identity }
            var parentMenu by remember(identity) { mutableStateOf(false) }
            val descendants = mutableSetOf(identity)
            var count: Int
            do {
                count = descendants.size; options.forEachIndexed { position, item ->
                    if (item.parentOptionId in descendants) descendants.add(
                        optionDraftIdentity(item, position)
                    )
                }
            } while (descendants.size != count)
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedTextField(
                        value = option.label,
                        onValueChange = { label -> onLabelChange(index, label) },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(Res.string.editor_property_option_name)) },
                        singleLine = true,
                    )
                    TextButton(onClick = { onRemove(index) }, enabled = !hasChildren && option.id !in usedOptionIds) {
                        Text(stringResource(Res.string.editor_action_remove))
                    }
                }
                if (hierarchical) Box {
                    TextButton(onClick = { parentMenu = true }) {
                        Text(
                            stringResource(Res.string.option_parent) + ": " + optionDraftPath(
                                options,
                                option.parentOptionId
                            ).ifBlank { stringResource(Res.string.option_root) },
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    TagloomDropdownMenu(parentMenu, { parentMenu = false }) {
                        TagloomMenuItem(
                            stringResource(Res.string.option_root),
                            onClick = { onParentChange(index, null); parentMenu = false })
                        options.forEachIndexed { position, item ->
                            val parent = optionDraftIdentity(item, position)
                            if (parent !in descendants && item.label.isNotBlank()) TagloomMenuItem(
                                optionDraftPath(
                                    options,
                                    parent
                                ), onClick = { onParentChange(index, parent); parentMenu = false })
                        }
                    }
                }
                if (hasChildren || option.id in usedOptionIds) Text(
                    stringResource(if (hasChildren) Res.string.option_delete_children else Res.string.option_delete_used),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        OutlinedButton(onClick = onAdd) { Text(stringResource(Res.string.editor_property_add_option)) }
        when {
            options.isEmpty() && inputMode == PropertyOptionInputMode.FIXED -> {
                PropertyOptionError(stringResource(Res.string.editor_property_option_required))
            }

            hasBlankOption -> PropertyOptionError(stringResource(Res.string.editor_property_option_name_required))
            hasDuplicateOption -> PropertyOptionError(stringResource(Res.string.editor_property_option_name_duplicate))
        }
    }
}

/**
 * 读取旧选项或未确认草稿的表单身份，不将显示名称用作业务 ID。
 * @param draft 当前行草稿。
 * @param index 尚无草稿身份的历史调用方行号。
 * @return 只用于表单父边计算的身份。
 */
private fun optionDraftIdentity(draft: PropertyOptionDraft, index: Int): String =
    draft.id ?: draft.draftId ?: "pending-$index"

/**
 * 计算未确认选项的展示路径，循环时有界退出而不吞掉用户草稿。
 * @param drafts 当前完整表单目录。
 * @param id 要展示的项身份。
 * @return 根到当前项的名称路径。
 */
private fun optionDraftPath(drafts: List<PropertyOptionDraft>, id: String?): String {
    val byId = drafts.mapIndexed { index, item -> optionDraftIdentity(item, index) to item }.toMap()
    val names = mutableListOf<String>();
    val seen = mutableSetOf<String>();
    var current = id
    while (current != null && seen.add(current)) {
        val item = byId[current] ?: break; names.add(0, item.label); current = item.parentOptionId
    }
    return names.joinToString(" / ")
}

/**
 * 渲染不会占据输入控件位置的选项校验提示。
 *
 * @param message 面向用户的校验原因。
 */
@Composable
internal fun PropertyOptionError(message: String) {
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

/**
 * 返回不与现有选项重名的本地化默认名称。
 *
 * @param options 当前选项草稿。
 * @return 当前 Locale 下最小可用的“选项 N”名称。
 */
@Composable
internal fun nextOptionLabel(options: List<PropertyOptionDraft>): String {
    val existing = options.map { option -> option.label.trim() }.toSet()
    var number = 1
    var candidate = stringResource(Res.string.editor_property_option_default_name, number)
    while (candidate in existing) {
        number += 1
        candidate = stringResource(Res.string.editor_property_option_default_name, number)
    }
    return candidate
}
