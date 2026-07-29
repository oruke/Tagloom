package com.oruke.tagloom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.oruke.tagloom.core.model.InlineMarkAttributes
import com.oruke.tagloom.core.model.InlineMarkTypes
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.editor.addAnnotationToSelection
import com.oruke.tagloom.editor.applyLinkToSelection
import com.oruke.tagloom.editor.clearSelectionMark
import com.oruke.tagloom.editor.selectionHasMark
import com.oruke.tagloom.editor.selectionMarkAttribute
import com.oruke.tagloom.editor.setSelectionMark
import com.oruke.tagloom.editor.toggleSelectionMark

/**
 * 渲染文本选区出现时的浮动格式工具栏。
 *
 * @param state 编辑器状态。
 */
@Composable
internal fun SelectionFormattingToolbar(state: TagloomEditorState) {
    var colorMenuMode by remember { mutableStateOf<InlineColorMode?>(null) }
    var utilityMenuMode by remember { mutableStateOf<SelectionUtilityMode?>(null) }
    Popup(
        alignment = Alignment.TopCenter,
        offset = IntOffset(0, 58),
        properties = PopupProperties(focusable = false),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shape = RoundedCornerShape(9.dp),
            shadowElevation = 6.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FormatAction(state, "B", InlineMarkTypes.BOLD, FontWeight.Bold)
                FormatAction(state, "I", InlineMarkTypes.ITALIC)
                FormatAction(state, "<>", InlineMarkTypes.CODE)
                FormatAction(state, "S", InlineMarkTypes.STRIKETHROUGH)
                InlineColorMenuAction(
                    state = state,
                    mode = InlineColorMode.TEXT,
                    expanded = colorMenuMode == InlineColorMode.TEXT,
                    onExpandedChange = { expanded ->
                        colorMenuMode = if (expanded) InlineColorMode.TEXT else null
                    },
                )
                InlineColorMenuAction(
                    state = state,
                    mode = InlineColorMode.HIGHLIGHT,
                    expanded = colorMenuMode == InlineColorMode.HIGHLIGHT,
                    onExpandedChange = { expanded ->
                        colorMenuMode = if (expanded) InlineColorMode.HIGHLIGHT else null
                    },
                )
                SelectionUtilityAction(
                    state = state,
                    mode = SelectionUtilityMode.LINK,
                    expanded = utilityMenuMode == SelectionUtilityMode.LINK,
                    onExpandedChange = { expanded ->
                        utilityMenuMode = if (expanded) SelectionUtilityMode.LINK else null
                    },
                )
                SelectionUtilityAction(
                    state = state,
                    mode = SelectionUtilityMode.ANNOTATION,
                    expanded = utilityMenuMode == SelectionUtilityMode.ANNOTATION,
                    onExpandedChange = { expanded ->
                        utilityMenuMode = if (expanded) SelectionUtilityMode.ANNOTATION else null
                    },
                )
                Text(
                    "${state.selection.end - state.selection.start} 字",
                    modifier = Modifier.padding(horizontal = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/**
 * 渲染链接或独立加注的选区动作与按需输入菜单。
 *
 * @param state 编辑器状态。
 * @param mode 链接或加注模式。
 * @param expanded 输入菜单是否展开。
 * @param onExpandedChange 请求切换菜单状态。
 */
@Composable
private fun SelectionUtilityAction(
    state: TagloomEditorState,
    mode: SelectionUtilityMode,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    Box {
        Text(
            text = mode.label,
            modifier = Modifier
                .clickable { onExpandedChange(!expanded) }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.inverseOnSurface,
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            Column(Modifier.width(300.dp).padding(10.dp)) {
                Text(
                    mode.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                OutlinedTextField(
                    value = mode.value(state),
                    onValueChange = { value -> mode.update(state, value) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    placeholder = { Text(mode.placeholder) },
                    minLines = if (mode == SelectionUtilityMode.ANNOTATION) 3 else 1,
                    singleLine = mode == SelectionUtilityMode.LINK,
                )
                Button(
                    onClick = {
                        mode.apply(state)
                        onExpandedChange(false)
                    },
                    enabled = mode.value(state).isNotBlank(),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text(mode.actionLabel)
                }
            }
        }
    }
}

/**
 * 区分选区工具栏中的链接与独立加注输入。
 *
 * @property label 工具栏短标签。
 * @property title 菜单标题。
 * @property placeholder 输入提示。
 * @property actionLabel 提交按钮文案。
 */
private enum class SelectionUtilityMode(
    val label: String,
    val title: String,
    val placeholder: String,
    val actionLabel: String,
) {
    /** 为选区写入链接 Mark。 */
    LINK("链接", "添加链接", "https://example.com", "应用链接"),

    /** 为选区创建独立加注正文。 */
    ANNOTATION("加注", "添加独立加注", "解释这个词或短语", "添加加注");

    /**
     * 读取当前模式的输入草稿。
     *
     * @param state 编辑器状态。
     * @return 当前输入文本。
     */
    fun value(state: TagloomEditorState): String = when (this) {
        LINK -> state.linkDraft
        ANNOTATION -> state.annotationDraft
    }

    /**
     * 更新当前模式的输入草稿。
     *
     * @param state 编辑器状态。
     * @param value 新输入文本。
     */
    fun update(
        state: TagloomEditorState,
        value: String,
    ) {
        when (this) {
            LINK -> state.linkDraft = value
            ANNOTATION -> state.annotationDraft = value
        }
    }

    /**
     * 把当前模式的草稿应用到活动选区。
     *
     * @param state 编辑器状态。
     */
    fun apply(state: TagloomEditorState) {
        when (this) {
            LINK -> state.applyLinkToSelection()
            ANNOTATION -> state.addAnnotationToSelection()
        }
    }
}

/**
 * 渲染文字颜色或高亮颜色入口，并以不抢焦点的菜单展示语义色。
 *
 * @param state 编辑器状态。
 * @param mode 文字或背景高亮模式。
 * @param expanded 当前颜色面板是否展开。
 * @param onExpandedChange 请求切换面板状态。
 */
@Composable
private fun InlineColorMenuAction(
    state: TagloomEditorState,
    mode: InlineColorMode,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    val selectedToken = state.selectionMarkAttribute(mode.markType, InlineMarkAttributes.COLOR_TOKEN)
    val selectedOption = InlineColorPalette.firstOrNull { option -> option.token == selectedToken }
    Box {
        Text(
            text = mode.symbol,
            modifier = Modifier
                .background(
                    when (mode) {
                        InlineColorMode.TEXT -> MaterialTheme.colorScheme.inverseSurface
                        InlineColorMode.HIGHLIGHT -> selectedOption?.highlightColor
                            ?: MaterialTheme.colorScheme.inverseSurface
                    },
                    RoundedCornerShape(5.dp),
                )
                .clickable { onExpandedChange(!expanded) }
                .padding(horizontal = 9.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = when (mode) {
                InlineColorMode.TEXT -> selectedOption?.textColor ?: MaterialTheme.colorScheme.inverseOnSurface
                InlineColorMode.HIGHLIGHT -> MaterialTheme.colorScheme.inverseOnSurface
            },
            fontWeight = FontWeight.Bold,
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            properties = PopupProperties(focusable = false),
        ) {
            Text(
                mode.label,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DropdownMenuItem(
                text = { Text("默认") },
                leadingIcon = { ColorSwatch(mode, null) },
                trailingIcon = { if (selectedToken == null) Text("✓") },
                onClick = {
                    state.clearSelectionMark(mode.markType)
                    onExpandedChange(false)
                },
            )
            HorizontalDivider()
            InlineColorPalette.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    leadingIcon = { ColorSwatch(mode, option) },
                    trailingIcon = { if (selectedToken == option.token) Text("✓") },
                    onClick = {
                        state.setSelectionMark(
                            markType = mode.markType,
                            attributes = mapOf(InlineMarkAttributes.COLOR_TOKEN to option.token),
                        )
                        onExpandedChange(false)
                    },
                )
            }
        }
    }
}

/**
 * 渲染颜色菜单中的文字或背景预览方块。
 *
 * @param mode 文字或高亮模式。
 * @param option 语义色；为空时展示默认预览。
 */
@Composable
private fun ColorSwatch(
    mode: InlineColorMode,
    option: InlineColorOption?,
) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .background(
                when (mode) {
                    InlineColorMode.TEXT -> MaterialTheme.colorScheme.surfaceVariant
                    InlineColorMode.HIGHLIGHT -> option?.highlightColor ?: MaterialTheme.colorScheme.surface
                },
                RoundedCornerShape(4.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (mode == InlineColorMode.TEXT) {
            Text(
                "A",
                color = option?.textColor ?: MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * 区分颜色选择器正在编辑文字前景还是背景高亮。
 *
 * @property markType 写入权威 RichText 的稳定 Mark 类型。
 * @property label 面板标题。
 * @property symbol 工具栏紧凑符号。
 */
private enum class InlineColorMode(
    val markType: String,
    val label: String,
    val symbol: String,
) {
    /** 文字前景色。 */
    TEXT(InlineMarkTypes.TEXT_COLOR, "文字颜色", "A"),

    /** 文字背景高亮。 */
    HIGHLIGHT(InlineMarkTypes.HIGHLIGHT, "高亮颜色", "▰"),
}

/**
 * 渲染一个会反映当前选区 Mark 状态的格式操作。
 *
 * @param state 编辑器状态。
 * @param label 按钮文案。
 * @param markType Mark 类型。
 * @param fontWeight 可选字重。
 */
@Composable
private fun FormatAction(
    state: TagloomEditorState,
    label: String,
    markType: String,
    fontWeight: FontWeight? = null,
) {
    val selected = state.selectionHasMark(markType)
    Text(
        text = label,
        modifier = Modifier
            .background(
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.inverseSurface,
                RoundedCornerShape(5.dp),
            )
            .clickable { state.toggleSelectionMark(markType) }
            .padding(horizontal = 9.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.inverseOnSurface,
        fontWeight = fontWeight,
    )
}
