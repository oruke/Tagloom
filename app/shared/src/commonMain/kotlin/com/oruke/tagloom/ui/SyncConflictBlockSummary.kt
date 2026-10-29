package com.oruke.tagloom.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.platform.WorkspaceSyncConflictBlock
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 展示冲突 Block 的可读类型、顺序、父级和格式，不暴露随机身份或排序协议。
 * @param block 当前历史版本的结构摘要。
 */
@Composable
internal fun SyncConflictBlockSummary(block: WorkspaceSyncConflictBlock) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(Res.string.sync_conflict_block_position, syncConflictBlockType(block.type), block.position),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(when {
            block.parentTitle != null -> stringResource(Res.string.sync_conflict_block_parent_title, block.parentTitle)
            block.parentPosition != null -> stringResource(Res.string.sync_conflict_block_parent_position, block.parentPosition)
            else -> stringResource(Res.string.sync_conflict_block_top_level)
        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (block.formattingDiffers) {
            Text(stringResource(Res.string.sync_conflict_block_format_differs), style = MaterialTheme.typography.bodySmall)
            block.codeWrap?.let { wrap ->
                Text(stringResource(if (wrap) Res.string.sync_conflict_block_wrap_on else Res.string.sync_conflict_block_wrap_off),
                    style = MaterialTheme.typography.bodySmall)
            }
            block.numberedListStart?.let { start ->
                Text(stringResource(Res.string.sync_conflict_block_number_start, start), style = MaterialTheme.typography.bodySmall)
            }
            if (block.otherFormattingValues.isNotEmpty()) {
                Text(stringResource(Res.string.sync_conflict_block_other_format, block.otherFormattingValues.joinToString(" · ")),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * 沿用编辑器已有类型名称，仅补齐原先没有菜单入口的历史 Block 类型。
 * @param type 保留版本中的稳定 Block 类型。
 * @return 当前界面语言下的类型名。
 */
@Composable
private fun syncConflictBlockType(type: String): String = stringResource(when (type) {
    BlockTypes.PARAGRAPH -> Res.string.editor_block_command_text_label
    BlockTypes.HEADING -> Res.string.editor_block_placeholder_heading
    BlockTypes.BULLETED_LIST -> Res.string.editor_block_command_bulleted_list_label
    BlockTypes.NUMBERED_LIST -> Res.string.editor_block_command_numbered_list_label
    BlockTypes.TODO -> Res.string.editor_block_command_todo_label
    BlockTypes.QUOTE -> Res.string.editor_block_command_quote_label
    BlockTypes.CODE -> Res.string.editor_block_command_code_label
    BlockTypes.DIVIDER -> Res.string.editor_block_command_divider_label
    BlockTypes.TABS -> Res.string.editor_block_command_tabs_label
    BlockTypes.BASE_TABLE -> Res.string.editor_block_command_base_table_label
    BlockTypes.DATABASE -> Res.string.editor_block_command_database_label
    BlockTypes.TAB -> Res.string.sync_conflict_block_tab
    BlockTypes.IMAGE -> Res.string.sync_conflict_block_image
    BlockTypes.FILE -> Res.string.sync_conflict_block_file
    BlockTypes.NOTE_REFERENCE -> Res.string.sync_conflict_block_reference
    else -> Res.string.sync_conflict_field_block
})
