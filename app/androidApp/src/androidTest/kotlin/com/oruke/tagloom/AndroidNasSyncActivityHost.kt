package com.oruke.tagloom

import android.content.ComponentName
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry

/** 通过官方 UiAutomation shell 通道启动受权限保护的 debug 空宿主，不修改系统权限或默认工作区。 */
internal object AndroidNasSyncActivityHost {
    /**
     * 先让目标应用的空窗口进入前台，避免设备后台弹窗限制阻塞 ActivityScenario 的同步启动。
     *
     * 命令只包含已校验的本包固定组件；不传入 URL、密钥、路径或 Intent extras，不启动 MainActivity。
     * shell 使用其原有权限，测试不会 adopt 身份、授予权限或修改 appops，也不保存或打印完整命令输出。
     * @param diagnostics 仅记录固定启动阶段，不保存命令输出或设备状态正文。
     */
    fun launchFromShell(diagnostics: AndroidNasSyncDiagnostics) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val component = ComponentName(instrumentation.targetContext, NasAcceptanceActivity::class.java).flattenToString()
        require(component.matches(Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.]+"))) { "nas_ui_host_component_invalid" }
        diagnostics.mark("ui_shell_host_launch_started")
        // UiAutomation 以既有 shell 通道调用精确组件，空宿主不参与工作区初始化。
        val output = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("am start -W --user current -n $component")
        ).bufferedReader().use { it.readText() }
        check(output.lineSequence().any { it.trim() == "Status: ok" }) { "nas_ui_shell_host_not_started" }
        diagnostics.mark("ui_shell_host_launch_finished")
    }
}
