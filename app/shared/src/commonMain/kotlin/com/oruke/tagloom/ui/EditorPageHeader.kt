package com.oruke.tagloom.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.editor.search.NoteSearchField
import com.oruke.tagloom.editor.search.NoteSearchTarget
import com.oruke.tagloom.ui.search.LocalNoteSearch
import com.oruke.tagloom.ui.search.NoteSearchEntry
import com.oruke.tagloom.ui.search.NoteSearchTextViewport
import com.oruke.tagloom.ui.search.rememberNoteSearchDecoration
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.PropertyId
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.editor.EditorCursorTarget
import com.oruke.tagloom.editor.EditorDocument
import com.oruke.tagloom.editor.PropertyDefinitionDestination
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.platform.DocumentExportFormat
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 渲染页面级路径、标题、标题下方元数据和导出入口。
 *
 * @param state 编辑器状态。
 * @param document 当前文档。
 * @param saving 当前是否正在保存。
 * @param firstBlock 第一个可编辑 Block。
 * @param onCursorRequest 正文焦点请求。
 * @param onExport 文档导出请求。
 * @param onSaveRequest 立即保存请求。
 * @param onWorkspaceMenuRequest 可选工作区安全与维护菜单入口。
 * @param onSaveTagDefinition 持久化工作区标签定义。
 * @param onSavePropertyDefinition 持久化工作区属性定义。
 * @param onCreatePropertyOption 持久化并选择一个自由属性选项。
 * @param searchMenu 工作区搜索浮层。
 * @param workspaceMenu 笔记操作浮层。
 * @param searchEntryModifier 搜索图标用于定位查找浮层的修饰符。
 */
@Composable
internal fun PageHeader(
    state: TagloomEditorState,
    document: EditorDocument,
    saving: Boolean,
    firstBlock: Block?,
    onCursorRequest: (EditorCursorTarget) -> Unit,
    onExport: (DocumentExportFormat) -> Unit,
    onSaveRequest: () -> Unit,
    onWorkspaceMenuRequest: (() -> Unit)?,
    onSaveTagDefinition: (TagDefinition, Boolean) -> Unit,
    onSavePropertyDefinition: (PropertyDefinition, PropertyDefinitionDestination) -> Unit,
    onCreatePropertyOption: (PropertyId, String) -> Unit,
    searchMenu: (@Composable () -> Unit)? = null,
    workspaceMenu: (@Composable () -> Unit)? = null,
    searchEntryModifier: Modifier = Modifier,
) {
    var exportMenuOpen by remember(document.id) { mutableStateOf(false) }
    val find = rememberNoteSearchDecoration(NoteSearchTarget(NoteSearchField.TITLE))
    val titleFocus = remember(document.id) { FocusRequester() }
    LaunchedEffect(document.id, state.newNoteTitleFocusId) {
        if (state.newNoteTitleFocusId == document.id) {
            titleFocus.requestFocus()
            state.newNoteTitleFocusId = null
        }
    }
    Column(Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                stringResource(Res.string.editor_page_breadcrumb_note),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("/", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
            Text(
                when {
                    saving -> stringResource(Res.string.editor_page_saving)
                    document.dirty -> stringResource(Res.string.editor_page_unsaved)
                    else -> stringResource(Res.string.editor_page_saved)
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (document.dirty) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            NoteAiAccessPolicyControl(
                state = state,
                document = document,
                onSaveRequest = onSaveRequest,
            )
            LocalWorkspaceAgentHarness.current?.takeIf { it.available }?.let { agentController ->
                Surface(
                    color = if (agentController.companionVisible) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.small,
                ) {
                    IconButton(
                        onClick = agentController::toggleCompanionPane,
                        modifier = Modifier.size(30.dp).testTag("editor-companion-toggle"),
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_sparkle),
                            contentDescription = stringResource(Res.string.agent_toggle_companion),
                            modifier = Modifier.size(16.dp),
                            tint = if (agentController.companionVisible) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Box {
                PageTextAction(stringResource(Res.string.editor_page_export)) { exportMenuOpen = true }
                TagloomDropdownMenu(
                    expanded = exportMenuOpen,
                    onDismissRequest = { exportMenuOpen = false },
                ) {
                    ExportMenuItem(
                        stringResource(Res.string.editor_export_markdown_name),
                        stringResource(Res.string.editor_export_markdown_description),
                        DocumentExportFormat.MARKDOWN,
                    ) {
                        exportMenuOpen = false
                        onExport(it)
                    }
                    ExportMenuItem(
                        stringResource(Res.string.editor_export_html_name),
                        stringResource(Res.string.editor_export_html_description),
                        DocumentExportFormat.HTML,
                    ) {
                        exportMenuOpen = false
                        onExport(it)
                    }
                    ExportMenuItem(
                        stringResource(Res.string.editor_export_pdf_name),
                        stringResource(Res.string.editor_export_pdf_description),
                        DocumentExportFormat.PDF,
                    ) {
                        exportMenuOpen = false
                        onExport(it)
                    }
                    ExportMenuItem(
                        stringResource(Res.string.editor_export_word_name),
                        stringResource(Res.string.editor_export_word_description),
                        DocumentExportFormat.WORD,
                    ) {
                        exportMenuOpen = false
                        onExport(it)
                    }
                }
            }
            Box {
                LocalNoteSearch.current?.let { search ->
                    NoteSearchEntry(active = search.isOpen, onClick = search::open, modifier = searchEntryModifier)
                }
                searchMenu?.invoke()
            }
            if (onWorkspaceMenuRequest != null || workspaceMenu != null) {
                Box {
                    if (onWorkspaceMenuRequest != null) {
                        PageIconAction("⋯", stringResource(Res.string.editor_page_more_actions), onWorkspaceMenuRequest)
                    }
                    workspaceMenu?.invoke()
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        NoteSearchTextViewport(Modifier.fillMaxWidth()) { fieldModifier ->
            BasicTextField(
                value = document.note.title,
                visualTransformation = find.transformation(),
                onTextLayout = find.onTextLayout,
                onValueChange = state::updateTitle,
                modifier = fieldModifier
                    .testTag("note-title-input")
                    .focusRequester(titleFocus)
                    .then(find.modifier)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) {
                            return@onPreviewKeyEvent false
                        }
                        when {
                            (event.isCtrlPressed || event.isMetaPressed) && event.key == Key.S -> {
                                onSaveRequest()
                                true
                            }

                            event.key == Key.Enter && firstBlock != null -> {
                                onCursorRequest(EditorCursorTarget(firstBlock.id, 0))
                                true
                            }

                            else -> false
                        }
                    },
                textStyle = MaterialTheme.typography.headlineLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                singleLine = true,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { innerTextField ->
                    Box(Modifier.fillMaxWidth()) {
                        if (document.note.title.isBlank()) {
                            Text(
                                stringResource(Res.string.editor_sidebar_untitled_note),
                                style = MaterialTheme.typography.headlineLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        innerTextField()
                    }
                },
            )
        }
        Spacer(Modifier.height(12.dp))
        DocumentMetadataPanel(
            state = state,
            document = document,
            onSaveTagDefinition = onSaveTagDefinition,
            onSavePropertyDefinition = onSavePropertyDefinition,
            onCreatePropertyOption = onCreatePropertyOption,
        )
    }
}

/**
 * 渲染页头右侧不抢占标题注意力的图标动作。
 *
 * @param glyph 稳定单字符图标。
 * @param description 本地化的无障碍操作名称。
 * @param onClick 点击动作。
 */
@Composable
private fun PageIconAction(
    glyph: String,
    description: String,
    onClick: () -> Unit,
) {
    Text(
        text = glyph,
        modifier = Modifier.clickable(onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 渲染导出菜单中的格式名称和一句话用途。
 *
 * @param label 格式名称。
 * @param description 格式用途说明。
 * @param format 平台导出格式。
 * @param onClick 用户选择动作。
 */
@Composable
private fun ExportMenuItem(
    label: String,
    description: String,
    format: DocumentExportFormat,
    onClick: (DocumentExportFormat) -> Unit,
) {
    TagloomMenuItem(
        text = label,
        description = description,
        onClick = { onClick(format) },
    )
}

/**
 * 渲染页面头部的低强调文字操作。
 *
 * @param label 操作文案。
 * @param onClick 点击动作。
 */
@Composable
private fun PageTextAction(
    label: String,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
