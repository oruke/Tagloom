package com.oruke.tagloom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.editor.EditorBlockType
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.Res
import tagloom.app.shared.generated.resources.editor_block_command_base_table_description
import tagloom.app.shared.generated.resources.editor_block_command_base_table_keywords
import tagloom.app.shared.generated.resources.editor_block_command_base_table_label
import tagloom.app.shared.generated.resources.editor_block_command_bulleted_list_description
import tagloom.app.shared.generated.resources.editor_block_command_bulleted_list_keywords
import tagloom.app.shared.generated.resources.editor_block_command_bulleted_list_label
import tagloom.app.shared.generated.resources.editor_block_command_code_description
import tagloom.app.shared.generated.resources.editor_block_command_code_keywords
import tagloom.app.shared.generated.resources.editor_block_command_code_label
import tagloom.app.shared.generated.resources.editor_block_command_database_description
import tagloom.app.shared.generated.resources.editor_block_command_database_keywords
import tagloom.app.shared.generated.resources.editor_block_command_database_label
import tagloom.app.shared.generated.resources.editor_block_command_divider_description
import tagloom.app.shared.generated.resources.editor_block_command_divider_keywords
import tagloom.app.shared.generated.resources.editor_block_command_divider_label
import tagloom.app.shared.generated.resources.editor_block_command_heading_one_description
import tagloom.app.shared.generated.resources.editor_block_command_heading_one_keywords
import tagloom.app.shared.generated.resources.editor_block_command_heading_one_label
import tagloom.app.shared.generated.resources.editor_block_command_heading_three_description
import tagloom.app.shared.generated.resources.editor_block_command_heading_three_keywords
import tagloom.app.shared.generated.resources.editor_block_command_heading_three_label
import tagloom.app.shared.generated.resources.editor_block_command_heading_two_description
import tagloom.app.shared.generated.resources.editor_block_command_heading_two_keywords
import tagloom.app.shared.generated.resources.editor_block_command_heading_two_label
import tagloom.app.shared.generated.resources.editor_block_command_numbered_list_description
import tagloom.app.shared.generated.resources.editor_block_command_numbered_list_keywords
import tagloom.app.shared.generated.resources.editor_block_command_numbered_list_label
import tagloom.app.shared.generated.resources.editor_block_command_quote_description
import tagloom.app.shared.generated.resources.editor_block_command_quote_keywords
import tagloom.app.shared.generated.resources.editor_block_command_quote_label
import tagloom.app.shared.generated.resources.editor_block_command_tabs_description
import tagloom.app.shared.generated.resources.editor_block_command_tabs_keywords
import tagloom.app.shared.generated.resources.editor_block_command_tabs_label
import tagloom.app.shared.generated.resources.editor_block_command_text_description
import tagloom.app.shared.generated.resources.editor_block_command_text_keywords
import tagloom.app.shared.generated.resources.editor_block_command_text_label
import tagloom.app.shared.generated.resources.editor_block_command_todo_description
import tagloom.app.shared.generated.resources.editor_block_command_todo_keywords
import tagloom.app.shared.generated.resources.editor_block_command_todo_label

/**
 * 描述 Slash 菜单和 Block 类型菜单共享的一项命令。
 */
internal data class EditorBlockCommand(
    /** 命令应用后的 Block 类型。 */
    val type: EditorBlockType,
    /** 菜单主标题对应的资源。 */
    val labelResource: StringResource,
    /** 一句话用途说明对应的资源。 */
    val descriptionResource: StringResource,
    /** 当前 Locale 下检索别名对应的逗号分隔资源。 */
    val keywordsResource: StringResource,
    /** 菜单左侧使用的紧凑符号。 */
    val symbol: String,
    /** 是否允许从现有普通 Block 无损转换；结构化 Block 仅在创建菜单中出现。 */
    val convertible: Boolean = true,
)

/** 编辑器 v0.2 支持的 Block 创建与转换命令。 */
internal val EditorBlockCommands: List<EditorBlockCommand> = listOf(
    EditorBlockCommand(
        EditorBlockType.TEXT,
        Res.string.editor_block_command_text_label,
        Res.string.editor_block_command_text_description,
        Res.string.editor_block_command_text_keywords,
        "T",
    ),
    EditorBlockCommand(
        EditorBlockType.HEADING_ONE,
        Res.string.editor_block_command_heading_one_label,
        Res.string.editor_block_command_heading_one_description,
        Res.string.editor_block_command_heading_one_keywords,
        "H1",
    ),
    EditorBlockCommand(
        EditorBlockType.HEADING_TWO,
        Res.string.editor_block_command_heading_two_label,
        Res.string.editor_block_command_heading_two_description,
        Res.string.editor_block_command_heading_two_keywords,
        "H2",
    ),
    EditorBlockCommand(
        EditorBlockType.HEADING_THREE,
        Res.string.editor_block_command_heading_three_label,
        Res.string.editor_block_command_heading_three_description,
        Res.string.editor_block_command_heading_three_keywords,
        "H3",
    ),
    EditorBlockCommand(
        EditorBlockType.BULLETED_LIST,
        Res.string.editor_block_command_bulleted_list_label,
        Res.string.editor_block_command_bulleted_list_description,
        Res.string.editor_block_command_bulleted_list_keywords,
        "•",
    ),
    EditorBlockCommand(
        EditorBlockType.NUMBERED_LIST,
        Res.string.editor_block_command_numbered_list_label,
        Res.string.editor_block_command_numbered_list_description,
        Res.string.editor_block_command_numbered_list_keywords,
        "1.",
    ),
    EditorBlockCommand(
        EditorBlockType.TODO,
        Res.string.editor_block_command_todo_label,
        Res.string.editor_block_command_todo_description,
        Res.string.editor_block_command_todo_keywords,
        "☐",
    ),
    EditorBlockCommand(
        EditorBlockType.QUOTE,
        Res.string.editor_block_command_quote_label,
        Res.string.editor_block_command_quote_description,
        Res.string.editor_block_command_quote_keywords,
        "“",
    ),
    EditorBlockCommand(
        EditorBlockType.CODE,
        Res.string.editor_block_command_code_label,
        Res.string.editor_block_command_code_description,
        Res.string.editor_block_command_code_keywords,
        "</>",
    ),
    EditorBlockCommand(
        EditorBlockType.DIVIDER,
        Res.string.editor_block_command_divider_label,
        Res.string.editor_block_command_divider_description,
        Res.string.editor_block_command_divider_keywords,
        "—",
    ),
    EditorBlockCommand(
        EditorBlockType.BASE_TABLE,
        Res.string.editor_block_command_base_table_label,
        Res.string.editor_block_command_base_table_description,
        Res.string.editor_block_command_base_table_keywords,
        "▦",
        convertible = false,
    ),
    EditorBlockCommand(
        EditorBlockType.DATABASE,
        Res.string.editor_block_command_database_label,
        Res.string.editor_block_command_database_description,
        Res.string.editor_block_command_database_keywords,
        "▦",
        convertible = false,
    ),
    EditorBlockCommand(
        EditorBlockType.TABS,
        Res.string.editor_block_command_tabs_label,
        Res.string.editor_block_command_tabs_description,
        Res.string.editor_block_command_tabs_keywords,
        "▤",
        convertible = false,
    ),
)

/**
 * 按用户输入过滤 Slash 命令。
 *
 * @param query 去除 `/` 后的检索文本。
 * @param databaseAvailable 当前平台是否装配 Database 持久化入口。
 * @return 保持产品定义顺序的匹配命令。
 */
@Composable
internal fun filterEditorBlockCommands(
    query: String,
    databaseAvailable: Boolean,
): List<EditorBlockCommand> {
    val normalized = query.trim().lowercase()
    val availableCommands = if (databaseAvailable) {
        EditorBlockCommands
    } else {
        EditorBlockCommands.filterNot { command -> command.type == EditorBlockType.DATABASE }
    }
    if (normalized.isEmpty()) {
        return availableCommands
    }
    return availableCommands.filter { command ->
        command.localizedLabel().lowercase().contains(normalized) ||
            command.localizedKeywords().any { keyword -> keyword.lowercase().contains(normalized) }
    }
}

/**
 * 返回 Block 命令在当前 Locale 下的主标题。
 *
 * @return Slash 菜单和类型菜单使用的标题。
 */
@Composable
private fun EditorBlockCommand.localizedLabel(): String = stringResource(labelResource)

/**
 * 返回 Block 命令在当前 Locale 下的一句话说明。
 *
 * @return 标题下方的用途说明。
 */
@Composable
private fun EditorBlockCommand.localizedDescription(): String = stringResource(descriptionResource)

/**
 * 返回当前 Locale 可识别的命令检索别名。
 *
 * @return 去除空白后的不重复小写别名集合。
 */
@Composable
private fun EditorBlockCommand.localizedKeywords(): Set<String> =
    stringResource(keywordsResource)
        .split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toSet()

/**
 * 渲染一个包含类型符号、名称和说明的标准 Block 菜单项。
 *
 * @param command Block 命令。
 * @param selected 是否为键盘当前指向的命令。
 * @param onClick 选择动作。
 */
@Composable
internal fun EditorBlockCommandItem(
    command: EditorBlockCommand,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        modifier = Modifier.background(
            if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        ),
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(command.localizedLabel(), fontWeight = FontWeight.Medium)
                Text(
                    command.localizedDescription(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        onClick = onClick,
        leadingIcon = {
            Text(
                command.symbol,
                modifier = Modifier.padding(end = 4.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        },
    )
}
