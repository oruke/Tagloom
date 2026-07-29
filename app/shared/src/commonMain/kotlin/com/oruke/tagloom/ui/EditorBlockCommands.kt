package com.oruke.tagloom.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
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

/**
 * 描述 Slash 菜单和 Block 类型菜单共享的一项命令。
 */
internal data class EditorBlockCommand(
    /** 命令应用后的 Block 类型。 */
    val type: EditorBlockType,
    /** 菜单主标题。 */
    val label: String,
    /** 一句话用途说明。 */
    val description: String,
    /** 中文、英文和 Markdown 检索别名。 */
    val keywords: Set<String>,
    /** 菜单左侧使用的紧凑符号。 */
    val symbol: String,
)

/** 编辑器 v0.2 支持的 Block 创建与转换命令。 */
internal val EditorBlockCommands: List<EditorBlockCommand> = listOf(
    EditorBlockCommand(EditorBlockType.TEXT, "正文", "普通连续文本", setOf("text", "paragraph", "正文", "段落"), "T"),
    EditorBlockCommand(EditorBlockType.HEADING_ONE, "一级标题", "页面主要章节标题", setOf("h1", "heading1", "标题", "一级"), "H1"),
    EditorBlockCommand(EditorBlockType.HEADING_TWO, "二级标题", "页面次级章节标题", setOf("h2", "heading2", "标题", "二级"), "H2"),
    EditorBlockCommand(EditorBlockType.HEADING_THREE, "三级标题", "页面小节标题", setOf("h3", "heading3", "标题", "三级"), "H3"),
    EditorBlockCommand(EditorBlockType.BULLETED_LIST, "无序列表", "使用项目符号组织条目", setOf("bullet", "list", "列表", "无序"), "•"),
    EditorBlockCommand(EditorBlockType.NUMBERED_LIST, "有序列表", "使用连续序号组织步骤", setOf("number", "list", "列表", "有序"), "1."),
    EditorBlockCommand(EditorBlockType.TODO, "待办", "可以勾选完成的条目", setOf("todo", "checkbox", "待办", "清单"), "☐"),
    EditorBlockCommand(EditorBlockType.QUOTE, "引用", "突出显示一段引用文字", setOf("quote", "引用"), "“"),
    EditorBlockCommand(EditorBlockType.CODE, "代码块", "保留换行和等宽排版", setOf("code", "代码"), "</>"),
    EditorBlockCommand(EditorBlockType.DIVIDER, "分隔线", "分隔两个内容区域", setOf("divider", "line", "分隔线"), "—"),
)

/**
 * 按用户输入过滤 Slash 命令。
 *
 * @param query 去除 `/` 后的检索文本。
 * @return 保持产品定义顺序的匹配命令。
 */
internal fun filterEditorBlockCommands(query: String): List<EditorBlockCommand> {
    val normalized = query.trim().lowercase()
    if (normalized.isEmpty()) {
        return EditorBlockCommands
    }
    return EditorBlockCommands.filter { command ->
        command.label.lowercase().contains(normalized) ||
            command.keywords.any { keyword -> keyword.lowercase().contains(normalized) }
    }
}

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
                Text(command.label, fontWeight = FontWeight.Medium)
                Text(
                    command.description,
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
