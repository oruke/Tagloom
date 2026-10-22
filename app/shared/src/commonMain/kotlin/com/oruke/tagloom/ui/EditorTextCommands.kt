package com.oruke.tagloom.ui

import androidx.compose.ui.text.input.TextFieldValue
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.editor.EditorCursorTarget
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.editor.editableRichText
import com.oruke.tagloom.editor.toggleSelectionMark
import com.oruke.tagloom.editor.turnBlockInto
import com.oruke.tagloom.editor.turnBlockIntoBaseTable

/**
 * 对当前非空选区切换 Mark，并向键盘事件报告是否已处理。
 *
 * @param state 编辑器状态。
 * @param markType Mark 稳定类型。
 * @return 存在非空选区时为 true。
 */
internal fun toggleMark(
    state: TagloomEditorState,
    markType: String,
): Boolean {
    if (!state.selection.isNotEmpty) {
        return false
    }
    state.toggleSelectionMark(markType)
    return true
}

/**
 * 清除 Slash 查询文本并转换当前 Block。
 *
 * @param state 编辑器状态。
 * @param block 当前 Block。
 * @param command 目标类型命令。
 * @param slashTrigger 当前输入触发范围；由手柄菜单打开时为空。
 * @param onCursorRequest 转换后的焦点请求。
 * @param onCreateDatabaseBlock 请求异步创建并绑定 Database Block。
 * @param onCreateLink 请求进入空段落的原位链接录入，未装配平台时为空。
 */
internal fun applyCommand(
    state: TagloomEditorState,
    block: Block,
    command: EditorBlockCommand,
    slashTrigger: SlashCommandTrigger?,
    onCursorRequest: (EditorCursorTarget) -> Unit,
    onCreateDatabaseBlock: (BlockId) -> Unit,
    onCreateLink: ((BlockId) -> Unit)? = null,
) {
    if (slashTrigger != null) {
        val currentText = block.editableRichText()?.plainText.orEmpty()
        state.updateBlockText(
            block.id,
            currentText.removeRange(slashTrigger.start, slashTrigger.endExclusive),
        )
    }
    if (command.captureLink) {
        onCreateLink?.invoke(block.id)
        return
    }
    if (command.type == com.oruke.tagloom.editor.EditorBlockType.DATABASE) {
        onCreateDatabaseBlock(block.id)
        return
    }
    state.turnBlockInto(block.id, command.type)?.let(onCursorRequest)
}

/**
 * 清除 Slash 查询文本并按用户选择的尺寸创建正文表格。
 *
 * @param state 编辑器状态。
 * @param block 当前命令 Block。
 * @param slashTrigger Slash 触发范围；由手柄菜单打开时为空。
 * @param rows 初始活动行数。
 * @param columns 初始活动列数。
 * @param onCursorRequest 转换后的焦点请求。
 */
internal fun applyBaseTableCommand(
    state: TagloomEditorState,
    block: Block,
    slashTrigger: SlashCommandTrigger?,
    rows: Int,
    columns: Int,
    onCursorRequest: (EditorCursorTarget) -> Unit,
) {
    if (slashTrigger != null) {
        val currentText = block.editableRichText()?.plainText.orEmpty()
        state.updateBlockText(block.id, currentText.removeRange(slashTrigger.start, slashTrigger.endExclusive))
    }
    state.turnBlockIntoBaseTable(block.id, rows, columns)?.let(onCursorRequest)
}

/**
 * 寻找折叠光标所在词中的 Slash 命令触发范围。
 *
 * Slash 只在行首或空白字符之后生效，URL 和普通路径中的 `/` 因此不会意外打开命令菜单。
 *
 * @param value 当前文本框值与光标。
 * @return 可继续输入过滤的 Slash token；不满足触发条件时为空。
 */
internal fun findSlashCommandTrigger(value: TextFieldValue): SlashCommandTrigger? {
    if (!value.selection.collapsed) {
        return null
    }
    val caret = value.selection.end.coerceIn(0, value.text.length)
    if (caret == 0) {
        return null
    }
    val slash = value.text.lastIndexOf('/', caret - 1)
    if (slash < 0 || (slash > 0 && !value.text[slash - 1].isWhitespace())) {
        return null
    }
    val query = value.text.substring(slash + 1, caret)
    if (query.any(Char::isWhitespace)) {
        return null
    }
    return SlashCommandTrigger(slash, caret, query)
}

/**
 * 描述当前 Slash 命令在 Block 纯文本中的范围和查询文本。
 */
internal data class SlashCommandTrigger(
    /** `/` 所在字符偏移。 */
    val start: Int,
    /** 当前光标位置，也是 token 半开终点。 */
    val endExclusive: Int,
    /** 去除 `/` 后用于过滤命令的文本。 */
    val query: String,
)
