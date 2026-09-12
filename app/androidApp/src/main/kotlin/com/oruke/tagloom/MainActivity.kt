package com.oruke.tagloom

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.oruke.tagloom.capability.AndroidTagloomViewModel

/** Android 宿主只负责窗口、系统文档入口和能力会话生命周期。 */
class MainActivity : ComponentActivity() {
    /** 配置变更期间保留同一工作区协调器和能力容器。 */
    private val host: AndroidTagloomViewModel by viewModels()

    /**
     * 绑定宿主并渲染共享 App。
     * @param savedInstanceState 系统恢复的宿主状态。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        host.documents.attach(this)

        setContent {
            // 系统栏、刘海和输入法属于宿主窗口边界，共享编辑器只处理可绘制内容区。
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                App(capabilities = host.capabilities, appState = host.appState)
            }
        }
    }

    /** 清除旧 Activity 的文档交互绑定，会话由 ViewModel 决定何时关闭。 */
    override fun onDestroy() {
        host.documents.detach(this)
        super.onDestroy()
    }
}

/** 渲染隔离的共享 UI 预览，不打开生产工作区。 */
@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
