package com.oruke.tagloom

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import com.oruke.tagloom.capability.TagloomCapabilities
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.platform.DocumentFileGateway
import com.oruke.tagloom.ui.PreviewDatabaseController
import com.oruke.tagloom.ui.TagloomTheme
import com.oruke.tagloom.ui.TagloomWorkspaceScreen
import com.oruke.tagloom.ui.WorkspaceLifecycleScreen

/**
 * 构造共享 Tagloom 编辑器界面；平台外壳只负责窗口和生命周期。
 *
 * @param documentFileGateway 平台文档导出能力；预览环境可以为 null。
 * @param capabilities 当前宿主提供的跨平台能力；预览环境可以为 null。
 * @param appState 宿主保留的内存会话状态；为空时跟随当前 Compose 界面生命周期。
 */
@Composable
@Preview
fun App(
    documentFileGateway: DocumentFileGateway? = null,
    capabilities: TagloomCapabilities? = null,
    appState: TagloomAppState? = null,
) {
    TagloomTheme {
        if (capabilities == null) {
            val editorState = remember { TagloomEditorState.demo() }
            val databaseController = remember(editorState) { PreviewDatabaseController(editorState) }
            TagloomWorkspaceScreen(
                state = editorState,
                databaseController = databaseController,
                documentFileGateway = documentFileGateway,
            )
        } else {
            WorkspaceLifecycleScreen(capabilities, documentFileGateway, appState)
        }
    }
}
