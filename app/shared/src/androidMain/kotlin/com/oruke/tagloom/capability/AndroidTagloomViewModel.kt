package com.oruke.tagloom.capability

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.oruke.tagloom.TagloomAppState
import com.oruke.tagloom.workspace.AndroidWorkspaceDocuments
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 在 Activity 重建之间保留唯一工作区会话，宿主结束时释放租约、索引和密钥。
 * @param application 只使用 Application 环境。
 */
class AndroidTagloomViewModel(application: Application) : AndroidViewModel(application) {
    /** Activity 可重新绑定的文档入口，配置变更不重建工作区协调器。 */
    val documents: AndroidWorkspaceDocuments
    /** 共享 App 消费的能力容器，禁止在 Compose 重组中重复创建。 */
    val capabilities: TagloomCapabilities
    /** 只保存在内存的草稿、Recovery Key 确认阶段和操作作用域。 */
    val appState: TagloomAppState

    init {
        AndroidTagloomEnvironment.initialize(application)
        documents = AndroidWorkspaceDocuments(application)
        capabilities = createAndroidTagloomCapabilities(documents)
        appState = TagloomAppState(capabilities, viewModelScope)
    }

    /** 宿主真正结束时销毁待确认材料，并在不受 ViewModel 取消影响的作用域关闭资源。 */
    override fun onCleared() {
        CoroutineScope(Dispatchers.IO).launch {
            // 等待已取消作用域中的文件操作退出，避免清理密钥时仍有后台提交使用它。
            viewModelScope.coroutineContext[Job]?.join()
            capabilities.workspace.cancelCreation()
            try {
                capabilities.syncEngine.close()
            } finally {
                capabilities.workspace.lock()
            }
        }
    }
}
