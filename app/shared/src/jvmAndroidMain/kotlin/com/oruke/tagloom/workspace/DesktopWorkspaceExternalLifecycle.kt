package com.oruke.tagloom.workspace

/**
 * 在会话销毁主密钥之前同步失效外部授权、预览和结果缓存。
 * 回调只处理内存，不能等待 API 互斥锁，否则会与工作区锁形成反向锁序。
 */
internal fun DesktopWorkspaceSession.invalidateExternalAccess() {
    val listeners = synchronized(externalCloseListeners) {
        closed = true
        externalCloseListeners.toList().also { externalCloseListeners.clear() }
    }
    listeners.forEach { listener -> runCatching { listener() } }
}

/**
 * 订阅一次解锁会话的同步关闭通知，关闭后的订阅立即失效。
 * @param listener 只执行内存清理的回调，不得读写文件或等待其他业务锁。
 */
internal fun DesktopWorkspaceSession.onExternalClosed(listener: () -> Unit) {
    synchronized(externalCloseListeners) {
        if (closed) listener() else externalCloseListeners += listener
    }
}
