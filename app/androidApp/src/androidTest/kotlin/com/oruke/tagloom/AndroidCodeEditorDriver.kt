package com.oruke.tagloom

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.graphics.Rect
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * 通过 Android 正式无障碍动作驱动真实 Compose 控件，兼容移除旧输入注入接口的 Android 17。
 * 只检查当前测试应用的可见窗口，不读取其他应用内容，也不替换业务回调。
 */
internal class AndroidCodeEditorDriver(
    /** 当前设备的 instrumentation，提供真实窗口和无障碍操作通道。 */
    private val instrumentation: Instrumentation,
    /** 控件等待失败时保存隔离测试窗口的图像，保留可核查的失败现场。 */
    private val onTimeout: () -> Unit,
) {
    /** 仅允许查找测试目标应用的界面。 */
    private val targetPackage = instrumentation.targetContext.packageName

    /** 不依赖 Espresso 隐藏 InputManager 反射接口的系统操作能力。 */
    private val automation = instrumentation.uiAutomation

    init {
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
    }

    /**
     * 点击具有指定文案或内容描述的真实控件，布局重建导致节点失效时在同一截止时间内重新查找。
     * @param label 目标控件的准确文案。
     */
    fun click(label: String) {
        val deadline = SystemClock.uptimeMillis() + NODE_TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            val node = awaitNode(deadline) { matchesText(it, label) }
            val target = generateSequence(node) { it.parent }.firstOrNull { it.isClickable }
            // Compose 重建期间可能仍读到已移除节点；仅重试未被系统接受的动作，成功后不重复点击。
            if (target != null && target.refresh() && target.isEnabled &&
                target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ) return
            SystemClock.sleep(50)
        }
        onTimeout()
        error("点击动作未被接受：$label")
    }

    /**
     * 通过 ACTION_SET_TEXT 输入内容，真实触发 Compose 的文本编辑及 Markdown 粘贴流程。
     * @param label 旧文本或空输入框的占位提示。
     * @param text 替换后的原文。
     */
    fun setText(label: String, text: String) {
        val node = awaitNode { matchesText(it, label) }
        replaceText(node, text)
    }

    /**
     * 按稳定标识输入，避免同名查找按钮和输入框的无障碍标签产生歧义。
     * @param tag 生产输入框已有的测试标识。
     * @param text 替换后的原文。
     */
    fun setTaggedText(tag: String, text: String) {
        replaceText(awaitNode { it.viewIdResourceName == tag }, text)
    }

    /**
     * 对匹配节点对应的编辑控件执行平台原生文本动作。
     * @param node 当前应用中实际可见的目标节点。
     * @param text 输入框要接收的完整文本。
     */
    private fun replaceText(node: AccessibilityNodeInfo, text: String) {
        val target = generateSequence(node) { it.parent }.firstOrNull { it.isEditable }
            ?: error("目标控件不可编辑")
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        check(target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) { "文本编辑未被接受" }
    }

    /**
     * 等待真实异步流程显示预期文本。
     * @param text 期望出现的完整文本或内容描述。
     * @return 对应的可见无障碍节点，便于复核控件状态。
     */
    fun awaitText(text: String): AccessibilityNodeInfo = awaitNode { matchesText(it, text) }

    /**
     * 等待标签所属的切换控件达到指定状态，避免把不承载状态的子 Text 当作复选框。
     * @param label 切换控件的可见标签。
     * @param checked 预期的启用状态。
     */
    @Suppress("DEPRECATION")
    fun awaitChecked(label: String, checked: Boolean) {
        awaitNode { node ->
            matchesText(node, label) && generateSequence(node) { it.parent }
                .firstOrNull { it.isCheckable }?.isChecked == checked
        }
    }

    /**
     * 读取测试标识对应的屏幕边界，用于核对居中布局。
     * @param tag 生产组件提供的稳定布局标识。
     * @return 该组件在屏幕上的实际边界。
     */
    fun bounds(tag: String): Rect = Rect().also { rectangle ->
        awaitNode { it.viewIdResourceName == tag }.getBoundsInScreen(rectangle)
    }

    /**
     * 按文案或内容描述匹配节点，忽略控件暴露的快捷键后缀。
     * @param node 来自当前测试应用的可见节点。
     * @param text 目标文案。
     * @return 文本或内容描述与目标相同时为 true。
     */
    private fun matchesText(node: AccessibilityNodeInfo, text: String): Boolean =
        node.text?.toString() == text || node.contentDescription?.toString() == text ||
                node.text?.toString()?.startsWith("$text\n") == true

    /**
     * 从最新应用窗口查找可见节点，等待布局和真实文件 I/O，而不关闭任何业务校验。
     * @param deadline 单调时钟中的等待截止时间；重试调用复用同一值，避免逐次延长超时。
     * @param predicate 目标节点必须满足的语义条件。
     * @return 超时前找到的节点；找不到时令测试明确失败。
     */
    private fun awaitNode(
        deadline: Long = SystemClock.uptimeMillis() + NODE_TIMEOUT_MILLIS,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo {
        var observedNodes = 0
        var observedWindows = 0
        var observedAppRoots = 0
        while (SystemClock.uptimeMillis() < deadline) {
            // 浮层移动与计数更新可能共用一帧；读取前清除系统无障碍缓存，避免反复检查旧节点。
            if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
            val windows = automation.windows
            val windowRoots = windows.sortedByDescending(AccessibilityWindowInfo::getLayer)
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .mapNotNull { it.root }
            val roots = (listOfNotNull(automation.rootInActiveWindow) + windowRoots)
                .filter { it.packageName?.toString() == targetPackage }
            observedWindows = windows.size
            observedAppRoots = roots.size
            observedNodes = 0
            val pending = ArrayDeque(roots)
            while (pending.isNotEmpty()) {
                val node = pending.removeFirst()
                observedNodes++
                if (node.isVisibleToUser && predicate(node)) return node
                repeat(node.childCount) { index -> node.getChild(index)?.let(pending::addLast) }
            }
            SystemClock.sleep(50)
        }
        onTimeout()
        error("测试应用未出现预期控件：窗口数=$observedWindows，目标根节点数=$observedAppRoots，节点数=$observedNodes")
    }

    private companion object {
        /** 包含真实加密写入所需的最大状态等待时间。 */
        const val NODE_TIMEOUT_MILLIS = 20_000L
    }
}
