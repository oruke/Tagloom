package com.oruke.tagloom.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import com.oruke.tagloom.editor.search.NoteSearchState
import com.oruke.tagloom.ui.search.LocalNoteSearch
import com.oruke.tagloom.ui.search.NoteSearchOverlay
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.CodeBlockContent
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.PropertyId
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.editor.EditorCursorTarget
import com.oruke.tagloom.editor.EditorDocument
import com.oruke.tagloom.editor.PropertyDefinitionDestination
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.editor.editableRichText
import com.oruke.tagloom.editor.visibleBlockDepth
import com.oruke.tagloom.editor.visibleEditorBlocks
import com.oruke.tagloom.editor.visibleRootEditorBlocks
import com.oruke.tagloom.editor.selectTab
import com.oruke.tagloom.platform.DocumentExportFormat

/**
 * 渲染以连续写作为主、Block 操作按需出现的页面编辑器。
 *
 * @param state 编辑器状态。
 * @param document 当前选中文档。
 * @param search 当前笔记的临时查找会话。
 * @param onCloseSearch 关闭查找并恢复宿主键盘焦点。
 * @param databaseController Database Block 使用的目录和写入控制器。
 * @param onCreateDatabaseBlock 通过指定文本 Block 创建并绑定 Database 的动作。
 * @param onExport 请求按目标格式导出当前页面。
 * @param onSaveRequest 请求立即保存当前页面。
 * @param onWorkspaceMenuRequest 可选工作区维护菜单入口。
 * @param onSaveTagDefinition 持久化工作区标签定义。
 * @param onSavePropertyDefinition 持久化工作区属性定义。
 * @param onCreatePropertyOption 持久化并选择一个自由属性选项。
 * @param saving 当前是否正在写入加密快照。
 * @param modifier 外部布局修饰符。
 * @param searchMenu 搜索入口关联的浮层。
 * @param workspaceMenu 工作区操作入口关联的浮层。
 */
@Composable
internal fun EditorPane(
    state: TagloomEditorState,
    document: EditorDocument,
    search: NoteSearchState,
    onCloseSearch: () -> Unit,
    databaseController: DatabaseEditorController? = null,
    onCreateDatabaseBlock: (BlockId) -> Unit = {},
    onExport: (DocumentExportFormat) -> Unit,
    onSaveRequest: () -> Unit,
    onWorkspaceMenuRequest: (() -> Unit)? = null,
    onSaveTagDefinition: (TagDefinition, Boolean) -> Unit,
    onSavePropertyDefinition: (PropertyDefinition, PropertyDefinitionDestination) -> Unit,
    onCreatePropertyOption: (PropertyId, String) -> Unit,
    saving: Boolean = false,
    modifier: Modifier = Modifier,
    searchMenu: (@Composable () -> Unit)? = null,
    workspaceMenu: (@Composable () -> Unit)? = null,
) {
    var cursorRequest by remember(document.id) { mutableStateOf<EditorCursorTarget?>(null) }
    var commandMenuBlockId by remember(document.id) {
        mutableStateOf<com.oruke.tagloom.core.model.BlockId?>(null)
    }
    val allVisibleBlocks = state.visibleEditorBlocks(document.note)
    val rootBlocks = state.visibleRootEditorBlocks(document.note)
    val firstBlock = allVisibleBlocks.firstOrNull { block ->
        block.editableRichText() != null || block.content is CodeBlockContent
    }
    val listState = rememberLazyListState()
    LaunchedEffect(search.active, search.navigationVersion) {
        val text = search.activeText ?: return@LaunchedEffect
        search.revealTabs()
        val position = if (text.rootBlockId == null) 0 else state.visibleRootEditorBlocks(document.note)
            .indexOfFirst { it.id == text.rootBlockId }.takeIf { it >= 0 }?.plus(1) ?: return@LaunchedEffect
        listState.scrollToItem(position)
        search.revealedVersion = search.navigationVersion
    }
    LaunchedEffect(search.focusReturnVersion) {
        if (search.isOpen || search.focusReturnVersion == 0) return@LaunchedEffect
        val text = search.returnText ?: return@LaunchedEffect
        // 原输入框可能已被 LazyColumn 回收或藏在其他页签，先重新创建它，再恢复真实输入焦点。
        text.tabs.forEach { state.selectTab(it.containerId, it.tabId) }
        search.restoreReturnSelection()
        val position = if (text.rootBlockId == null) 0 else state.visibleRootEditorBlocks(document.note)
            .indexOfFirst { it.id == text.rootBlockId }.takeIf { it >= 0 }?.plus(1) ?: return@LaunchedEffect
        listState.scrollToItem(position)
        search.revealedFocusVersion = search.focusReturnVersion
    }

    CompositionLocalProvider(LocalNoteSearch provides search) {
        EditorBlockDragScope(state, document.id, listState, modifier) {
            DocumentSelectionScope(state, listState, onCursorRequest = { cursorRequest = it }) {
                val drag = LocalEditorBlockDrag.current
                Surface(Modifier.fillMaxSize().testTag("editor-pane"), color = MaterialTheme.colorScheme.surface) {
                    NoteSearchOverlay(search, onCloseSearch) { searchEntryModifier ->
                        Box(Modifier.fillMaxSize()) {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize().testTag("editor-block-list"),
                                state = listState,
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
                                            onWorkspaceMenuRequest = onWorkspaceMenuRequest,
                                            onSaveTagDefinition = onSaveTagDefinition,
                                            onSavePropertyDefinition = onSavePropertyDefinition,
                                            onCreatePropertyOption = onCreatePropertyOption,
                                            searchMenu = searchMenu,
                                            workspaceMenu = workspaceMenu,
                                            searchEntryModifier = searchEntryModifier,
                                        )
                                    }
                                }
                                itemsIndexed(
                                    items = rootBlocks,
                                    key = { _, block -> block.id.value },
                                ) { _, block ->
                                    CenteredEditorContent(
                                        includeGutter = true,
                                        modifier = Modifier.zIndex(if (block.id in drag?.liftedIds.orEmpty()) 1f else 0f)
                                            // 只动画重排，避免 Markdown 转换替换输入框时延迟销毁旧焦点节点。
                                            .animateItem(
                                                fadeInSpec = null,
                                                fadeOutSpec = null,
                                                placementSpec = if (drag?.activeId != null || drag?.releasedPositions?.isNotEmpty() == true) null else tween(
                                                    220
                                                )
                                            ),
                                    ) {
                                        EditorBlockRow(
                                            state = state,
                                            databaseController = databaseController,
                                            block = block,
                                            depth = state.visibleBlockDepth(block, document.note),
                                            cursorRequest = cursorRequest,
                                            commandMenuBlockId = commandMenuBlockId,
                                            onCursorRequest = { target -> cursorRequest = target },
                                            onCursorConsumed = { cursorRequest = null },
                                            onCommandMenuRequest = { blockId -> commandMenuBlockId = blockId },
                                            onCommandMenuDismiss = { commandMenuBlockId = null },
                                            onCreateDatabaseBlock = onCreateDatabaseBlock,
                                            onOpenNote = state::selectDocument,
                                            onSaveRequest = onSaveRequest,
                                        )
                                    }
                                }
                            }
                            if (state.selection.isNotEmpty && !search.isOpen) {
                                SelectionFormattingToolbar(state = state)
                            }
                            DocumentTimestamps(
                                document = document,
                                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 将标题、属性和正文放入同一居中阅读栏，并为 Block 操作保留对称边距。
 *
 * @param content 阅读栏内的编辑内容，文本本身保持正常阅读方向。
 * @param includeGutter 是否将手柄及对称留白纳入真实布局范围。
 * @param modifier 外层列表位移动画与绘制顺序。
 */
@Composable
private fun CenteredEditorContent(
    includeGutter: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val horizontalPadding = if (maxWidth < 600.dp) BLOCK_GUTTER_WIDTH + 8.dp else 48.dp
        val contentWidth = (maxWidth - horizontalPadding * 2).coerceAtLeast(0.dp).coerceAtMost(EDITOR_CONTENT_WIDTH)
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .width(contentWidth + if (includeGutter) BLOCK_GUTTER_WIDTH * 2 else 0.dp)
                .testTag("editor-reading-column"),
        ) {
            content()
        }
    }
}

/** 编辑正文的最大舒适宽度。 */
private val EDITOR_CONTENT_WIDTH = 760.dp
