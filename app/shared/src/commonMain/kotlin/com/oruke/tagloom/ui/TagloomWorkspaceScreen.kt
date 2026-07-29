package com.oruke.tagloom.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.PropertyId
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.editor.PropertyDefinitionDestination
import com.oruke.tagloom.editor.bindPropertyToTag
import com.oruke.tagloom.editor.applyFreePropertyOption
import com.oruke.tagloom.editor.planFreePropertyOption
import com.oruke.tagloom.editor.setPropertyAttached
import com.oruke.tagloom.editor.setAppliedObjectType
import com.oruke.tagloom.editor.upsertPropertyDefinition
import com.oruke.tagloom.editor.upsertTagDefinition
import com.oruke.tagloom.platform.DocumentExportRequest
import com.oruke.tagloom.platform.DocumentFileGateway
import kotlinx.coroutines.launch

/**
 * 渲染左侧导航与中央页面组成的 Tagloom 工作区。
 *
 * 标签和属性属于当前文档内容，固定显示在标题下方，不再占用常驻右侧栏。
 *
 * @param state 编辑器状态。
 * @param documentFileGateway 平台文件选择与文档编码能力。
 * @param onSaveRequest 可选立即保存动作；内存演示环境为空操作。
 * @param onSearchRequest 可选已确认内容搜索入口。
 * @param onWorkspaceMenuRequest 可选工作区安全与维护菜单入口。
 * @param onSaveTagDefinition 可选工作区标签 schema 持久化动作；为空时只更新内存演示。
 * @param onSavePropertyDefinition 可选工作区属性 schema 持久化动作；为空时只更新内存演示。
 * @param onCreatePropertyOption 可选自由选项持久化动作；为空时只更新内存演示。
 * @param saving 当前是否正在写入加密快照。
 * @param modifier 外部布局修饰符。
 */
@Composable
fun TagloomWorkspaceScreen(
    state: TagloomEditorState,
    documentFileGateway: DocumentFileGateway? = null,
    onSaveRequest: () -> Unit = {},
    onSearchRequest: (() -> Unit)? = null,
    onWorkspaceMenuRequest: (() -> Unit)? = null,
    onSaveTagDefinition: ((TagDefinition, Boolean) -> Unit)? = null,
    onSavePropertyDefinition: ((PropertyDefinition, PropertyDefinitionDestination) -> Unit)? = null,
    onCreatePropertyOption: ((PropertyId, String) -> Unit)? = null,
    saving: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val searchFocusRequester = remember { FocusRequester() }
    val document = state.selectedDocument
    val saveTagDefinition = onSaveTagDefinition ?: { definition: TagDefinition, attach: Boolean ->
        state.upsertTagDefinition(definition)
        if (attach) state.setAppliedObjectType(definition.id)
    }
    val savePropertyDefinition = onSavePropertyDefinition ?: { definition: PropertyDefinition, destination: PropertyDefinitionDestination ->
        state.upsertPropertyDefinition(definition)
        when (destination) {
            PropertyDefinitionDestination.CurrentNote -> state.setPropertyAttached(definition.id, attached = true)
            PropertyDefinitionDestination.Workspace -> Unit
            is PropertyDefinitionDestination.Tag -> {
                state.upsertTagDefinition(state.bindPropertyToTag(destination.tagId, definition.id))
            }
        }
    }
    val createPropertyOption = onCreatePropertyOption ?: { propertyId: PropertyId, label: String ->
        val plan = state.planFreePropertyOption(propertyId, label)
        state.applyFreePropertyOption(plan.definition, plan.optionId)
    }
    val createDocument = { state.createDocument() }

    Box(
        modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || (!event.isCtrlPressed && !event.isMetaPressed)) {
                    return@onPreviewKeyEvent false
                }
                when (event.key) {
                    Key.N -> {
                        createDocument()
                        true
                    }
                    Key.K -> {
                        searchFocusRequester.requestFocus()
                        true
                    }
                    else -> false
                }
            },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val showNavigation = maxWidth >= 720.dp
            Row(Modifier.fillMaxSize()) {
                if (showNavigation) {
                    WorkspaceSidebar(
                        state = state,
                        searchFocusRequester = searchFocusRequester,
                        onCreateDocument = createDocument,
                        modifier = Modifier.width(232.dp),
                    )
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                if (document == null) {
                    EmptyWorkspace(
                        onCreate = createDocument,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    EditorPane(
                        state = state,
                        document = document,
                        onExport = { format ->
                            val gateway = documentFileGateway
                            if (gateway == null) {
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar("当前平台尚未提供文档导出能力")
                                }
                            } else {
                                coroutineScope.launch {
                                    val exported = runCatching {
                                        gateway.exportDocument(
                                            DocumentExportRequest(
                                                suggestedBaseName = document.note.title.toSafeFileBaseName(),
                                                note = document.note,
                                                format = format,
                                            ),
                                        )
                                    }.getOrElse {
                                        snackbarHostState.showSnackbar("导出失败，请检查目标文件是否可写")
                                        false
                                    }
                                    if (exported) {
                                        snackbarHostState.showSnackbar("${format.displayName()} 已导出")
                                    }
                                }
                            }
                        },
                        onSaveRequest = onSaveRequest,
                        onSearchRequest = onSearchRequest,
                        onWorkspaceMenuRequest = onWorkspaceMenuRequest,
                        onSaveTagDefinition = saveTagDefinition,
                        onSavePropertyDefinition = savePropertyDefinition,
                        onCreatePropertyOption = createPropertyOption,
                        saving = saving,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
        )
    }
}

/**
 * 渲染尚无选中文档时的直接创建入口。
 *
 * @param onCreate 新建笔记动作。
 * @param modifier 外部布局修饰符。
 */
@Composable
private fun EmptyWorkspace(
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("开始写下一篇笔记", style = MaterialTheme.typography.headlineMedium)
            Text(
                "内容会保存为本地加密对象快照",
                modifier = Modifier.padding(top = 8.dp, bottom = 18.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onCreate) { Text("新建笔记") }
        }
    }
}

/**
 * 把笔记标题收敛为不含路径语义的建议文件基础名。
 *
 * @return 非空且不含扩展名的建议文件名。
 */
private fun String.toSafeFileBaseName(): String {
    val safe = trim()
        .map { character -> if (character.isLetterOrDigit() || character in "-_ ") character else '_' }
        .joinToString(separator = "")
        .trim()
        .ifBlank { "tagloom-note" }
        .take(80)
    return safe
}

/**
 * 返回导出完成提示使用的中文格式名。
 *
 * @return 用户可识别的格式名称。
 */
private fun com.oruke.tagloom.platform.DocumentExportFormat.displayName(): String = when (this) {
    com.oruke.tagloom.platform.DocumentExportFormat.MARKDOWN -> "Markdown"
    com.oruke.tagloom.platform.DocumentExportFormat.HTML -> "HTML"
    com.oruke.tagloom.platform.DocumentExportFormat.PDF -> "PDF"
    com.oruke.tagloom.platform.DocumentExportFormat.WORD -> "Word"
}
