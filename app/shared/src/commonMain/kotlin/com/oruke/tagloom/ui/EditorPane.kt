package com.oruke.tagloom.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.PropertyId
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.editor.EditorCursorTarget
import com.oruke.tagloom.editor.EditorDocument
import com.oruke.tagloom.editor.PropertyDefinitionDestination
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.platform.DocumentExportFormat

/**
 * 渲染以连续写作为主、Block 操作按需出现的页面编辑器。
 *
 * @param state 编辑器状态。
 * @param document 当前选中文档。
 * @param onExport 请求按目标格式导出当前页面。
 * @param onSaveRequest 请求立即保存当前页面。
 * @param onSearchRequest 可选工作区搜索入口。
 * @param onWorkspaceMenuRequest 可选工作区维护菜单入口。
 * @param onSaveTagDefinition 持久化工作区标签定义。
 * @param onSavePropertyDefinition 持久化工作区属性定义。
 * @param onCreatePropertyOption 持久化并选择一个自由属性选项。
 * @param saving 当前是否正在写入加密快照。
 * @param modifier 外部布局修饰符。
 */
@Composable
fun EditorPane(
    state: TagloomEditorState,
    document: EditorDocument,
    onExport: (DocumentExportFormat) -> Unit,
    onSaveRequest: () -> Unit,
    onSearchRequest: (() -> Unit)? = null,
    onWorkspaceMenuRequest: (() -> Unit)? = null,
    onSaveTagDefinition: (TagDefinition, Boolean) -> Unit,
    onSavePropertyDefinition: (PropertyDefinition, PropertyDefinitionDestination) -> Unit,
    onCreatePropertyOption: (PropertyId, String) -> Unit,
    saving: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var cursorRequest by remember(document.id) { mutableStateOf<EditorCursorTarget?>(null) }
    var commandMenuBlockId by remember(document.id) {
        mutableStateOf<com.oruke.tagloom.core.model.BlockId?>(null)
    }
    val activeBlocks = state.orderedActiveBlocks(document.note)
    val firstBlock = activeBlocks.firstOrNull()

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 16.dp, bottom = 64.dp),
            ) {
                item(key = "page-header") {
                    CenteredEditorContent {
                        PageHeader(
                            state = state,
                            document = document,
                            saving = saving,
                            firstBlock = firstBlock,
                            onCursorRequest = { target -> cursorRequest = target },
                            onExport = onExport,
                            onSaveRequest = onSaveRequest,
                            onSearchRequest = onSearchRequest,
                            onWorkspaceMenuRequest = onWorkspaceMenuRequest,
                            onSaveTagDefinition = onSaveTagDefinition,
                            onSavePropertyDefinition = onSavePropertyDefinition,
                            onCreatePropertyOption = onCreatePropertyOption,
                        )
                    }
                }
                itemsIndexed(
                    items = activeBlocks,
                    key = { _, block -> block.id.value },
                ) { _, block ->
                    CenteredEditorContent {
                        EditorBlockRow(
                            state = state,
                            block = block,
                            depth = state.blockDepth(block, document.note),
                            cursorRequest = cursorRequest,
                            commandMenuRequested = commandMenuBlockId == block.id,
                            onCursorRequest = { target -> cursorRequest = target },
                            onCursorConsumed = { cursorRequest = null },
                            onCommandMenuRequest = { blockId -> commandMenuBlockId = blockId },
                            onCommandMenuDismiss = { commandMenuBlockId = null },
                            onSaveRequest = onSaveRequest,
                        )
                    }
                }
            }
            if (state.selection.isNotEmpty) {
                SelectionFormattingToolbar(state = state)
            }
            DocumentTimestamps(
                document = document,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 12.dp),
            )
        }
    }
}

/**
 * 把页面标题、正文和尾部点击区限制在舒适阅读宽度内。
 *
 * @param content 居中的编辑内容。
 */
@Composable
private fun CenteredEditorContent(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = EDITOR_CONTENT_WIDTH)
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        ) {
            content()
        }
    }
}

/** 编辑正文的最大舒适宽度。 */
private val EDITOR_CONTENT_WIDTH = 820.dp
