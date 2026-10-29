package com.oruke.tagloom.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.oruke.tagloom.core.crypto.CryptoException
import com.oruke.tagloom.core.external.ExternalCallException
import com.oruke.tagloom.platform.WorkspaceServerRecoveryStatus
import com.oruke.tagloom.platform.WorkspaceStorageMode
import com.oruke.tagloom.platform.WorkspaceSyncConflict
import com.oruke.tagloom.sync.http.ServerRecoveryException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive

/** 协调双向同步、冲突审阅与工作区操作；解锁会话结束即清除历史明文和临时凭据。 */
internal class WorkspaceServerRecoveryController(
    /** 复用工作区操作互斥、草稿提交和打开结果处理。 */
    private val workspace: WorkspaceUiController,
) {
    /** 当前平台是否具备真实服务器恢复能力。 */
    val available: Boolean get() = workspace.gateway.serverRecovery != null
    /** 已加载的非敏感服务器配置。 */
    var status: WorkspaceServerRecoveryStatus? by mutableStateOf(null)
        private set
    /** 当前配置是否已成功读取；读取失败禁止误以为未配置并覆盖记录。 */
    var loaded: Boolean by mutableStateOf(false)
        private set
    /** 当前可本地化的有限错误分类。 */
    var errorCode: String? by mutableStateOf(null)
        private set
    /** 最近一次手动同步完整成功，供页面显示结果。 */
    var published: Boolean by mutableStateOf(false)
        private set
    /** 自动同步不占用编辑器 busy，避免网络延迟阻止本地保存。 */
    var updating: Boolean by mutableStateOf(false)
        private set
    /** 当前解锁会话内供用户审阅的完整版本历史。 */
    var conflicts: List<WorkspaceSyncConflict> by mutableStateOf(emptyList())
        private set
    /** 历史是否已经成功读取，避免把加载失败误显示为空列表。 */
    var conflictsLoaded: Boolean by mutableStateOf(false)
        private set
    /** 加载历史期间提供独立反馈，不把旧数据清空。 */
    var conflictsLoading: Boolean by mutableStateOf(false)
        private set
    /** 认证或完整性失败需要人工处理，不能无限重复同一请求。 */
    private var automaticPaused = false
    /** 创建确认前的服务器地址，仅在当前会话存在。 */
    private var pendingAddress: String? = null
    /** 创建确认前的登记访问码，确认、取消或锁定后清除。 */
    private var pendingBootstrap: String? = null

    /**
     * 准备服务器新建，生成恢复密钥后仍进入统一的密钥确认页。
     * @param address 服务器地址。
     * @param bootstrap 首次登记访问码。
     * @param pin 当前设备可选 PIN。
     */
    suspend fun prepareCreation(address: String, bootstrap: String, pin: String?) = operate {
        val prepared = requireNotNull(workspace.gateway.serverRecovery).prepareCreation(address, pin)
        pendingAddress = address
        pendingBootstrap = bootstrap
        workspace.presentPreparedCreation(prepared)
    }

    /**
     * 本地创建已成功后尝试首次上传；失败只报告连接问题，不重复创建或丢弃本地数据。
     * @param recoveryKey 已由用户确认保存的一次性恢复密钥。
     */
    suspend fun finishCreation(recoveryKey: String) {
        val address = pendingAddress ?: return
        val bootstrap = pendingBootstrap.orEmpty()
        cancelCreation()
        attempt {
            status = requireNotNull(workspace.gateway.serverRecovery).publish(address, bootstrap, recoveryKey)
            loaded = true
            published = true
        }
    }

    /** 取消或完成创建时丢弃临时服务器凭据。 */
    fun cancelCreation() { pendingAddress = null; pendingBootstrap = null }

    /** 读取当前已解锁工作区的受保护远端配置，忽略已结束会话的响应。 */
    suspend fun refresh() = workspace.runOperation {
        attempt {
            val marker = workspace.workspaceSessionMarker
            val refreshed = requireNotNull(workspace.gateway.serverRecovery).status()
            if (workspace.workspaceSessionMarker !== marker) return@attempt
            status = refreshed
            loaded = true
        }
    }

    /**
     * 保存草稿后同步双方确认点，并只刷新没有未提交编辑的内容。
     * @param serverUrl 用户指定的 HTTPS 地址。
     * @param bootstrapToken 首次登记访问码，不保留在状态中。
     * @param recoveryKeyText 初次或轮换后的 Recovery Key，不保留在状态中。
     */
    suspend fun publish(serverUrl: String, bootstrapToken: String, recoveryKeyText: String) = operate {
        check(loaded)
        val marker = workspace.workspaceSessionMarker
        workspace.flushPendingNotes()
        val synchronized = requireNotNull(workspace.gateway.serverRecovery).publish(serverUrl, bootstrapToken, recoveryKeyText)
        if (workspace.workspaceSessionMarker !== marker) return@operate
        status = synchronized
        workspace.refreshConfirmedWorkspace()
        if (workspace.workspaceSessionMarker !== marker) return@operate
        if (conflictsLoaded) reloadConflicts(marker)
        automaticPaused = false
        published = true
    }

    /** 停止双向同步并保留当前工作区、草稿、历史以及服务器已有副本。 */
    suspend fun useLocalMode() = operate {
        val marker = workspace.workspaceSessionMarker
        val local = requireNotNull(workspace.gateway.serverRecovery).useLocalMode()
        if (workspace.workspaceSessionMarker !== marker) return@operate
        status = local
        loaded = true
        automaticPaused = false
    }

    /** 读取完整加密冲突历史供审阅，失败时保留已加载结果并展示可重试错误。 */
    suspend fun loadConflicts() = operate {
        val marker = workspace.workspaceSessionMarker
        conflictsLoading = true
        try { reloadConflicts(marker) }
        finally { if (workspace.workspaceSessionMarker === marker) conflictsLoading = false }
    }

    /**
     * 将用户审阅的冲突字段恢复为新变更，草稿及版本变化均不能被隐式覆盖。
     * @param conflict 用户已展开审阅的双版本及其当前对象 revision。
     * @param useRemote 是否选择第二份版本；命名沿用端口，不表达当前设备的位置。
     * @param fieldId 当前选择的字段身份；为空时处理全部未处理字段，旧历史恢复整版。
     */
    suspend fun resolveConflict(conflict: WorkspaceSyncConflict, useRemote: Boolean, fieldId: String? = null) = operate {
        val marker = workspace.workspaceSessionMarker
        val gateway = requireNotNull(workspace.gateway.serverRecovery)
        workspace.flushPendingNotes()
        try {
            gateway.resolveConflict(conflict.id, useRemote, conflict.currentRevision, fieldId)
        } catch (error: Exception) {
            if (error !is CancellationException && serverRecoveryErrorCode(error) == "revision_conflict") {
                // 草稿提交或另一台设备可能已改变对象；重新加载供审阅，绝不自动重试旧选择。
                reloadConflicts(marker)
            }
            throw error
        }
        if (workspace.workspaceSessionMarker !== marker) return@operate
        workspace.refreshConfirmedWorkspace()
        if (workspace.workspaceSessionMarker !== marker) return@operate
        val refreshed = gateway.status()
        if (workspace.workspaceSessionMarker !== marker) return@operate
        status = refreshed
        reloadConflicts(marker)
    }

    /**
     * 随解锁界面检查双方确认版本；本地无修改也要拉取其他设备的新内容。
     * 后台同步不占用编辑 busy，断网退避重试，已结束会话的结果不能回填。
     */
    suspend fun observeAutomaticUpdates() {
        if (!available) return
        while (currentCoroutineContext().isActive) {
            if (!workspace.busy && !updating && workspace.phase == WorkspaceUiPhase.OPEN) {
                val marker = workspace.workspaceSessionMarker
                try {
                    val gateway = requireNotNull(workspace.gateway.serverRecovery)
                    val current = gateway.status()
                    if (workspace.workspaceSessionMarker !== marker) continue
                    status = current
                    loaded = true
                    if (!automaticPaused && current?.mode == WorkspaceStorageMode.SERVER && !current.requiresRecoveryKey) {
                        updating = true
                        val synchronized = gateway.updateIfChanged()
                        if (workspace.workspaceSessionMarker !== marker) continue
                        status = synchronized
                        if (conflictsLoaded && (current.conflictsCount != synchronized?.conflictsCount || current.uploadedAt != synchronized?.uploadedAt)) {
                            reloadConflicts(marker)
                        }
                        errorCode = null
                    }
                } catch (error: CancellationException) {
                    // 切换本地模式会取消一次传输；只有宿主或整个会话取消才终止观察循环。
                    currentCoroutineContext().ensureActive()
                } catch (error: Exception) {
                    if (workspace.workspaceSessionMarker === marker) {
                        errorCode = serverRecoveryErrorCode(error)
                        automaticPaused = errorCode !in setOf("server_recovery_failed", "sync_retry_required", "recovery_revision_changed")
                    }
                } finally {
                    if (workspace.workspaceSessionMarker === marker) updating = false
                }
            }
            delay(if (errorCode == null) 3_000 else 30_000)
        }
    }

    /**
     * 从服务器恢复到应用管理的目录，成功后直接进入原工作区。
     * @param serverUrl 服务器 HTTPS 地址。
     * @param recoveryKeyText 离线保存的 Recovery Key。
     * @param pin 新设备可选 PIN。
     */
    suspend fun restore(serverUrl: String, recoveryKeyText: String, pin: String?) = operate {
        requireNotNull(workspace.gateway.serverRecovery).restore(serverUrl, recoveryKeyText, pin)?.let {
            workspace.acceptServerRecovery(it)
        }
    }

    /** 锁定时丢弃当前工作区的服务器状态，避免切换工作区后显示旧地址。 */
    fun clear() {
        status = null; loaded = false; errorCode = null; published = false
        conflicts = emptyList(); conflictsLoaded = false; conflictsLoading = false
        automaticPaused = false; updating = false; cancelCreation()
    }

    /**
     * 读取历史并在原会话仍有效时更新明文列表。
     * @param marker 发起操作时的工作区会话标记。
     */
    private suspend fun reloadConflicts(marker: Any) {
        val history = requireNotNull(workspace.gateway.serverRecovery).conflicts()
        if (workspace.workspaceSessionMarker !== marker) return
        conflicts = history.sortedWith(compareBy<WorkspaceSyncConflict> { it.resolved }.thenByDescending { it.createdAt })
        conflictsLoaded = true
    }

    /**
     * 通过工作区互斥执行一次操作，异常映射为本地化分类并保留表单以便重试。
     * @param block 此次恢复或上传流程。
     */
    private suspend fun operate(block: suspend () -> Unit) = workspace.runOperation {
        errorCode = null
        published = false
        attempt(block)
    }

    /**
     * 将平台失败收敛为可本地化分类，供嵌套创建及独立设置操作共同使用。
     * @param block 当前已经获得工作区操作权限的流程。
     */
    private suspend fun attempt(block: suspend () -> Unit) {
        val marker = workspace.workspaceSessionMarker
        try { block() }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            if (workspace.workspaceSessionMarker === marker) errorCode = serverRecoveryErrorCode(error)
        }
    }
}

/**
 * 仅提取允许公开的错误分类，不传递底层响应或本机路径。
 * @param error 平台恢复或传输异常。
 * @return 共享资源能够解释的稳定代码。
 */
private fun serverRecoveryErrorCode(error: Exception): String = when (error) {
    is ServerRecoveryException -> error.code
    is ExternalCallException -> error.code
    is CryptoException -> "recovery_key_invalid"
    is IllegalStateException -> error.message?.takeIf { it in setOf(
        "sync_integrity_failed", "sync_key_changed", "sync_schema_invalid", "sync_retry_required", "revision_conflict",
    ) } ?: "server_recovery_failed"
    else -> "server_recovery_failed"
}
