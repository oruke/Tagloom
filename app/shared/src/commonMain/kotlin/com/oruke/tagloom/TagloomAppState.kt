package com.oruke.tagloom

import com.oruke.tagloom.capability.TagloomCapabilities
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.ui.WorkspaceUiController
import kotlinx.coroutines.CoroutineScope

/**
 * 持有共享 App 的内存会话状态，宿主可在窗口或 Activity 重建之间保留同一实例。
 * 草稿和待确认 Recovery Key 仅保存在内存，不能写入 SavedState 或系统备份。
 * @param capabilities 与状态生命周期一致的能力容器。
 * @param operationScope 用户发起操作的宿主作用域，宿主结束时必须取消。
 */
class TagloomAppState(
    capabilities: TagloomCapabilities,
    /** 配置变更期间继续等待文件、认证和系统文档交互结果。 */
    internal val operationScope: CoroutineScope,
) {
    /** 跨重建保留的编辑草稿和工作区阶段，不重新解锁已持有写入租约的会话。 */
    internal val controller = WorkspaceUiController(
        capabilities.workspace,
        capabilities.searchEngine,
        TagloomEditorState(
            idFactory = capabilities.workspace::createOpaqueId,
            timestampProvider = capabilities.workspace::currentUtcTimestamp,
        ),
    )
}
