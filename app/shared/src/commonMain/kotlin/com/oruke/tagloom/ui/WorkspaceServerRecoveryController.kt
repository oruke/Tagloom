package com.oruke.tagloom.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.oruke.tagloom.core.crypto.CryptoException
import com.oruke.tagloom.platform.WorkspaceServerRecoveryStatus
import com.oruke.tagloom.platform.WorkspaceStorageMode
import com.oruke.tagloom.sync.http.ServerRecoveryException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive

/** 协调服务器副本操作与工作区 busy 状态；只持有安全状态，不缓存恢复密钥。 */
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
    /** 最近一次上传完整成功，供页面显示结果。 */
    var published: Boolean by mutableStateOf(false)
        private set
    /** 自动上传不占用编辑器 busy，避免网络延迟阻止本地保存。 */
    var updating: Boolean by mutableStateOf(false)
        private set
    /** 冲突和认证失败需要人工处理，不能无限重复发起相同上传。 */
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

    /** 读取当前已解锁工作区的受保护远端配置。 */
    suspend fun refresh() = workspace.runOperation {
        attempt {
            status = requireNotNull(workspace.gateway.serverRecovery).status()
            loaded = true
        }
    }

    /**
     * 保存草稿后上传密文确认点，并刷新成功时间。
     * @param serverUrl 用户指定的 HTTPS 地址。
     * @param bootstrapToken 首次登记访问码，不保留在状态中。
     * @param recoveryKeyText 初次或轮换后的 Recovery Key，不保留在状态中。
     */
    suspend fun publish(serverUrl: String, bootstrapToken: String, recoveryKeyText: String) = operate {
        check(loaded)
        workspace.flushPendingNotes()
        status = requireNotNull(workspace.gateway.serverRecovery).publish(serverUrl, bootstrapToken, recoveryKeyText)
        automaticPaused = false
        published = true
    }

    /** 停止服务器上传并保留当前工作区、草稿、密钥以及服务器已有副本。 */
    suspend fun useLocalMode() = operate {
        status = requireNotNull(workspace.gateway.serverRecovery).useLocalMode()
        loaded = true
        automaticPaused = false
    }

    /**
     * 随解锁界面运行，按确认版本合并短时间写入；断网退避重试，锁定或宿主结束即取消。
     * 网络错误不能转换为本地保存失败，也不能将较新的服务器副本静默覆盖。
     */
    suspend fun observeAutomaticUpdates() {
        if (!available) return
        while (currentCoroutineContext().isActive) {
            if (!workspace.busy) {
                try {
                    val gateway = requireNotNull(workspace.gateway.serverRecovery)
                    status = gateway.status()
                    loaded = true
                    if (!automaticPaused && status?.mode == WorkspaceStorageMode.SERVER && status?.pendingChanges == true) {
                        updating = true
                        status = gateway.updateIfChanged()
                        errorCode = null
                    }
                } catch (error: CancellationException) {
                    // 切换本地模式会取消一次传输；只有宿主或整个会话取消才终止观察循环。
                    currentCoroutineContext().ensureActive()
                } catch (error: Exception) {
                    errorCode = serverRecoveryErrorCode(error)
                    automaticPaused = errorCode != "server_recovery_failed"
                } finally { updating = false }
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
        automaticPaused = false; updating = false; cancelCreation()
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
        try { block() }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { errorCode = serverRecoveryErrorCode(error) }
    }
}

/**
 * 仅提取允许公开的错误分类，不传递底层响应或本机路径。
 * @param error 平台恢复或传输异常。
 * @return 共享资源能够解释的稳定代码。
 */
private fun serverRecoveryErrorCode(error: Exception): String = when (error) {
    is ServerRecoveryException -> error.code
    is CryptoException -> "recovery_key_invalid"
    else -> "server_recovery_failed"
}
