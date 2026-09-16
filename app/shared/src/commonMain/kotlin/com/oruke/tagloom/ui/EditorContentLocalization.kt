package com.oruke.tagloom.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import com.oruke.tagloom.editor.EditorContentNames
import com.oruke.tagloom.editor.TagloomEditorState
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 将当前资源语言的创建文案交给编辑器，只更新后续命名规则，不触发笔记写入。
 * @param state 当前会话编辑器，切换语言后继续保留已有草稿。
 */
@Composable
internal fun ConfigureEditorContentNames(state: TagloomEditorState) {
    val names = EditorContentNames(
        untitledNote = stringResource(Res.string.editor_new_note_title),
        importedNote = stringResource(Res.string.editor_imported_note_title),
        untitledTab = stringResource(Res.string.editor_tabs_untitled),
        firstColumn = stringResource(Res.string.editor_base_table_default_name),
        secondColumn = stringResource(Res.string.editor_base_table_default_content),
        columnPattern = stringResource(Res.string.editor_base_table_column_name),
        tabPattern = stringResource(Res.string.editor_tabs_numbered_name),
        copyPattern = stringResource(Res.string.editor_content_copy_name),
        untitledDatabase = stringResource(Res.string.database_default_name),
        tableViewName = stringResource(Res.string.database_view_table),
        viewPattern = stringResource(Res.string.database_default_new_view_name),
    )
    SideEffect { state.contentNames = names }
}
