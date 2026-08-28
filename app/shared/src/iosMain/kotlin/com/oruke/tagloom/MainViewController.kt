package com.oruke.tagloom

import androidx.compose.ui.window.ComposeUIViewController
import com.oruke.tagloom.capability.createTagloomCapabilities

/**
 * 创建 iOS 宿主的共享 Compose 根控制器。
 *
 * @return 由共享 App 管理生命周期的 UIKit 控制器。
 */
fun MainViewController() = ComposeUIViewController {
    App(capabilities = createTagloomCapabilities())
}
