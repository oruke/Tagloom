package com.oruke.tagloom

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.platform.DocumentFileGateway
import com.oruke.tagloom.platform.WorkspaceGateway
import com.oruke.tagloom.ui.TagloomTheme
import com.oruke.tagloom.ui.TagloomWorkspaceScreen
import com.oruke.tagloom.ui.WorkspaceLifecycleScreen

/**
 * 构造共享 Tagloom 编辑器界面；平台外壳只负责窗口和生命周期。
 *
 * @param documentFileGateway 平台文档导出能力；预览环境可以为 null。
 * @param workspaceGateway 平台加密工作区能力；为空时只展示内存演示。
 */
@Composable
@Preview
fun App(
    documentFileGateway: DocumentFileGateway? = null,
    workspaceGateway: WorkspaceGateway? = null,
) {
    TagloomTheme {
        if (workspaceGateway == null) {
            val editorState = remember { TagloomEditorState.demo() }
            TagloomWorkspaceScreen(editorState, documentFileGateway)
        } else {
            WorkspaceLifecycleScreen(workspaceGateway, documentFileGateway)
        }
    }
}
