package com.oruke.tagloom.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/** 将稳定图标键映射到随应用打包的线性矢量，未识别的未来图标键保持原样保存。 */
internal data class SupertagIconChoice(
    /** 写入加密 schema 的稳定键。 */
    val key: String,
    /** 当前语言的可搜索名称。 */
    val label: StringResource,
    /** 与操作图标保持一致线宽的矢量资源。 */
    val drawable: DrawableResource,
)

/** 用户可明确选择的内置矢量目录，不依赖外部服务。 */
internal val supertagIcons = listOf(
    SupertagIconChoice("folder", Res.string.supertag_icon_folder, Res.drawable.ic_folder),
    SupertagIconChoice("book", Res.string.supertag_icon_book, Res.drawable.ic_book),
    SupertagIconChoice("code", Res.string.supertag_icon_code, Res.drawable.ic_code),
    SupertagIconChoice("briefcase", Res.string.supertag_icon_briefcase, Res.drawable.ic_briefcase),
    SupertagIconChoice("person", Res.string.supertag_icon_person, Res.drawable.ic_person),
    SupertagIconChoice("star", Res.string.supertag_icon_star, Res.drawable.ic_star),
    SupertagIconChoice("heart", Res.string.supertag_icon_heart, Res.drawable.ic_heart),
    SupertagIconChoice("bulb", Res.string.supertag_icon_bulb, Res.drawable.ic_bulb),
    SupertagIconChoice("calendar", Res.string.supertag_icon_calendar, Res.drawable.ic_calendar),
    SupertagIconChoice("flag", Res.string.supertag_icon_flag, Res.drawable.ic_flag),
)

/**
 * 只显示用户保存的图标，空值和未知键不自动分配语义图案。
 * @param key 加密 schema 中的显式图标键。
 * @param modifier 图标布局和大小。
 */
@Composable
internal fun SupertagIcon(key: String?, modifier: Modifier = Modifier.size(22.dp)) {
    supertagIcons.firstOrNull { it.key == key }?.let { choice ->
        Icon(
            painterResource(choice.drawable), contentDescription = null, modifier = modifier,
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * 提供可搜索的图标目录和不使用图标选项；点击仅修改当前表单草稿。
 * @param selectedKey 当前草稿图标。
 * @param onSelect 选择图标键或清空。
 * @param onDismiss 取消选择并返回编辑表单。
 */
@Composable
internal fun SupertagIconPicker(selectedKey: String?, onSelect: (String?) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val matching =
        supertagIcons.filter { it.key.contains(query, true) || stringResource(it.label).contains(query, true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.supertag_icon_choose)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    query, { query = it }, Modifier.fillMaxWidth().testTag("supertag-icon-search"),
                    singleLine = true, placeholder = { Text(stringResource(Res.string.supertag_icon_search)) })
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(64.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp)
                ) {
                    items(matching, key = { it.key }) { choice ->
                        FilledTonalIconButton(
                            onClick = { onSelect(choice.key) },
                            modifier = Modifier.padding(4.dp).size(52.dp).testTag("supertag-icon-${choice.key}"),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = if (selectedKey == choice.key) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
                            ),
                        ) { Icon(painterResource(choice.drawable), stringResource(choice.label), Modifier.size(24.dp)) }
                    }
                }
                if (matching.isEmpty()) Text(
                    stringResource(Res.string.supertag_icon_empty),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSelect(null) }) { Text(stringResource(Res.string.supertag_icon_none)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.workspace_action_cancel)) } },
    )
}
