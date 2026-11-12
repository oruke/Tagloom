package com.oruke.tagloom

import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.oruke.tagloom.capability.TagloomCapabilities
import com.oruke.tagloom.platform.WorkspaceStorageMode
import kotlinx.coroutines.runBlocking

/** 复用现有 Android 无障碍通道，从生产 App 根节点检查 NAS 配置可见性与真实导航。 */
internal object AndroidNasSyncUi {
    /**
     * 打开正式 App、进入设置及工作区模式，并保留实际设备画面。
     * @param capabilities 当前隔离工作区的真实 Android 能力。
     * @param area 当前受控验收目录及截图出口。
     * @param serverUrl 期望出现的公开 HTTPS 服务入口，不包含凭据。
     * @param diagnostics 仅记录固定 UI 等待阶段的脱敏观察器。
     */
    fun verify(capabilities: TagloomCapabilities, area: AndroidNasSyncArea, serverUrl: String,
        diagnostics: AndroidNasSyncDiagnostics) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        AndroidNasSyncActivityHost.launchFromShell(diagnostics)
        diagnostics.mark("ui_activity_launch_started")
        ActivityScenario.launch(NasAcceptanceActivity::class.java).use { scenario ->
            diagnostics.mark("ui_activity_launch_finished")
            scenario.onActivity { activity ->
                activity.enableEdgeToEdge()
                activity.setContent {
                    Box(Modifier.fillMaxSize().safeDrawingPadding().semantics { testTagsAsResourceId = true }) {
                        App(capabilities = capabilities)
                    }
                }
            }
            diagnostics.mark("ui_content_attached")
            val driver = AndroidCodeEditorDriver(instrumentation) { area.screenshot("ui-timeout.png") }
            val settings = visibleLabel(setOf("设置", "Settings"))
            val chinese = settings == "设置"
            val manage = if (chinese) "管理工作区模式" else "Manage workspace mode"
            val mode = if (chinese) "工作区模式" else "Workspace mode"
            val sync = if (chinese) "立即同步" else "Sync now"
            driver.awaitText(settings)
            check(area.screenshot("notes.png")) { "nas_ui_note_capture_missing" }
            diagnostics.mark("ui_settings_navigation_started")
            driver.click(settings)
            scrollTo(manage)
            driver.click(manage)
            driver.awaitText(mode)
            driver.awaitText(serverUrl)
            scrollTo(sync)
            diagnostics.mark("ui_sync_click_started")
            driver.click(sync)
            val success = if (chinese) "同步完成，可以在其他设备上继续使用。"
                else "Sync complete. Your workspace is ready on other devices."
            // 标题在点击前就存在，不能证明请求已完成；只有正式 publish 完成后才出现这条成功文案。
            // 等待完成后再核对受保护状态，避免关闭 Activity 时取消在途请求却把 UI 验收记为成功。
            scrollTo(success, timeoutMillis = 60_000)
            driver.awaitText(success)
            diagnostics.mark("ui_sync_success_visible")
            runBlocking {
                val server = requireNotNull(capabilities.workspace.serverRecovery)
                val status = requireNotNull(server.status())
                check(status.mode == WorkspaceStorageMode.SERVER && status.registered && !status.requiresRecoveryKey &&
                    !status.pendingChanges && !status.uploadedAt.isNullOrBlank() && status.conflictsCount == 0) {
                    "nas_ui_sync_status_not_confirmed"
                }
                val conflicts = server.conflicts()
                check(conflicts.isNotEmpty() && conflicts.all { it.resolved && it.fields.all { field -> field.resolved } }) {
                    "nas_ui_conflict_history_not_resolved"
                }
            }
            diagnostics.mark("ui_sync_state_verified")
            check(area.screenshot("server-mode.png")) { "nas_ui_server_capture_missing" }
        }
        diagnostics.mark("ui_activity_closed")
    }

    /**
     * 从实际可见文案识别应用资源语言，避免系统地区回退使测试误用另一套标签。
     * @param labels 当前产品资源提供的等价文案。
     * @return 当前测试应用真实显示的一个候选文案。
     */
    private fun visibleLabel(labels: Set<String>): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val packageName = instrumentation.targetContext.packageName
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
            val root = automation.rootInActiveWindow
            if (root?.packageName?.toString() == packageName) {
                val pending = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
                while (pending.isNotEmpty()) {
                    val node = pending.removeFirst()
                    if (node.isVisibleToUser) {
                        node.text?.toString()?.takeIf { it in labels }?.let { return it }
                        node.contentDescription?.toString()?.takeIf { it in labels }?.let { return it }
                    }
                    repeat(node.childCount) { index -> node.getChild(index)?.let(pending::addLast) }
                }
            }
            SystemClock.sleep(100)
        }
        error("nas_ui_entry_missing")
    }

    /**
     * 在当前应用的真实滚动容器内查找设置动作，不跨应用注入点击或读取其它窗口。
     * @param label 需要滚动到可见范围的准确产品文案。
     * @param timeoutMillis 包括网络操作及界面更新在内的等待上限，普通导航默认二十秒。
     */
    private fun scrollTo(label: String, timeoutMillis: Long = 20_000) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val packageName = instrumentation.targetContext.packageName
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
            val root = automation.rootInActiveWindow
            if (root?.packageName?.toString() == packageName) {
                val pending = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
                val scrollable = mutableListOf<AccessibilityNodeInfo>()
                while (pending.isNotEmpty()) {
                    val node = pending.removeFirst()
                    if (node.isVisibleToUser && (node.text?.toString() == label || node.contentDescription?.toString() == label)) return
                    if (node.isVisibleToUser && node.isScrollable) scrollable += node
                    repeat(node.childCount) { index -> node.getChild(index)?.let(pending::addLast) }
                }
                scrollable.asReversed().firstOrNull { it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
            }
            SystemClock.sleep(150)
        }
        error("nas_ui_scroll_target_missing")
    }
}
