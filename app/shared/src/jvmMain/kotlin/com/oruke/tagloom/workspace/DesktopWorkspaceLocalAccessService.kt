package com.oruke.tagloom.workspace

import java.nio.file.Path

/**
 * 协调桌面设备本机访问模式与随机解锁材料的短生命周期。
 *
 * 它不读取或修改工作区文件，也不持有 Workspace Master Key；只负责在“生成材料 → 以材料
 * 完成认证 → 使用 Windows 帐户或 PIN 登记材料”的边界内清理内存，并在登记失败时让调用
 * 方锁定已打开会话。
 */
class DesktopWorkspaceLocalAccessService(
    /** 保存最近工作区和本机保护材料的桌面登记。 */
    private val accessStore: DesktopWorkspaceAccessStore = DesktopWorkspaceAccessStore(),
) {
    /** 新工作区确认 Recovery Key 前暂存的随机本机解锁材料。 */
    private var pendingCreationCredential: DesktopWorkspaceAccessCredential? = null

    /** 与待确认材料配对的可选 PIN；为空时使用当前 Windows 用户帐户保护。 */
    private var pendingCreationPin: String? = null

    /**
     * 读取当前设备最近登记的工作区安全展示信息。
     *
     * @return 未登记工作区时为空；结果不包含绝对路径或解锁材料。
     */
    suspend fun recentWorkspaceOrNull(): DesktopWorkspaceAccessTarget? = accessStore.activeWorkspaceOrNull()

    /**
     * 用当前本机访问模式临时解开最近工作区材料并执行一次认证动作。
     *
     * @param pin 用户输入的 6 至 12 位数字 PIN；未启用 PIN 时必须为空。
     * @param open 使用工作区目录和临时高熵材料完成认证的动作。
     * @return 认证动作的结果；回调结束后随机材料会被立即销毁。
     */
    suspend fun <T> withActiveWorkspaceCredential(
        pin: String?,
        open: suspend (Path, String) -> T,
    ): T {
        val credential = requireNotNull(accessStore.unlockActiveCredential(pin)) {
            "workspace-access-record-missing"
        }
        return try {
            credential.useWorkspacePassword { secret -> open(credential.workspaceRoot, secret) }
        } finally {
            credential.destroy()
        }
    }

    /**
     * 为两阶段创建生成材料并保留到用户确认 Recovery Key 为止。
     *
     * 新创建未确认前不能登记为最近工作区，否则用户取消后会留下无法打开的默认入口。
     *
     * @param root 用户选中的空工作区目录。
     * @param pin 用户主动设置的可选 PIN；为空时使用 Windows 帐户保护。
     * @param prepare 使用临时高熵材料准备两阶段工作区创建的动作。
     * @return 创建准备动作的结果。
     */
    suspend fun <T> preparePendingCreation(
        root: Path,
        pin: String?,
        prepare: suspend (String) -> T,
    ): T {
        discardPendingCreation()
        val credential = accessStore.createCredential(root, pin)
        return try {
            val prepared = credential.useWorkspacePassword(prepare)
            pendingCreationCredential = credential
            pendingCreationPin = pin
            prepared
        } catch (error: Throwable) {
            credential.destroy()
            throw error
        }
    }

    /**
     * 确认两阶段创建，并在成功后将同一随机材料写为最近工作区本机登记。
     *
     * 先确认工作区、再保存本机登记，是为了让默认入口永远指向已发布的 manifest；登记失败
     * 时必须锁定已经打开的会话，避免界面把“下次可直接打开”误报为成功。
     *
     * @param confirm 落盘并打开待确认工作区的动作。
     * @param onRegistrationFailure 登记保存失败后的会话锁定补偿动作。
     * @return 已确认并登记后的工作区打开结果。
     */
    suspend fun <T> confirmPendingCreation(
        confirm: suspend () -> T,
        onRegistrationFailure: suspend () -> Unit,
    ): T {
        val credential = requireNotNull(pendingCreationCredential) {
            "workspace-access-pending-credential-missing"
        }
        val pin = pendingCreationPin
        return try {
            // 第一步：发布工作区权威状态。
            val result = confirm()
            try {
                // 第二步：只有已发布状态才登记为下次启动的默认入口。
                accessStore.rememberAsActive(credential, pin)
                result
            } catch (error: Throwable) {
                // 第三步：本机登记未能证明成功，关闭会话并保留 Recovery Key 兜底入口。
                lockAfterRegistrationFailure(error, onRegistrationFailure)
                throw error
            }
        } finally {
            discardPendingCreation()
        }
    }

    /**
     * 用新随机材料建立通过 Recovery Key 认证后的本机访问入口。
     *
     * @param root 已由用户选择的既有或恢复目标工作区根目录。
     * @param pin 用户主动设置的可选 PIN；为空时使用 Windows 帐户保护。
     * @param authenticate 使用临时高熵材料完成恢复、解锁槽替换或备份恢复的动作。
     * @param onRegistrationFailure 本机登记失败后锁定已打开会话的补偿动作。
     * @return 已认证并登记后的工作区打开结果。
     */
    suspend fun <T> establishLocalAccess(
        root: Path,
        pin: String?,
        authenticate: suspend (String) -> T,
        onRegistrationFailure: suspend () -> Unit,
    ): T {
        val credential = accessStore.createCredential(root, pin)
        return try {
            // 第一步：认证或恢复必须先完成，避免把无效材料写入默认入口。
            val result = credential.useWorkspacePassword(authenticate)
            try {
                // 第二步：仅在认证成功后，用当前本机保护模式原子登记随机材料。
                accessStore.rememberAsActive(credential, pin)
                result
            } catch (error: Throwable) {
                // 第三步：已打开但未登记的会话不能继续使用，防止对本机访问能力做出错误承诺。
                lockAfterRegistrationFailure(error, onRegistrationFailure)
                throw error
            }
        } finally {
            credential.destroy()
        }
    }

    /**
     * 为当前 Windows 帐户保护的本机材料启用 PIN。
     *
     * @param pin 用户设置的 6 至 12 位数字 PIN。
     */
    suspend fun enablePin(pin: String) {
        rewrapActiveCredential(
            currentPin = null,
            newPin = pin,
            expectedPinEnabled = false,
        )
    }

    /**
     * 使用当前 PIN 认证本机材料，并用新 PIN 重新加密同一份材料。
     *
     * 工作区 manifest、对象密文与 Recovery Key 均不参与本机 PIN 修改。
     *
     * @param currentPin 当前设备 PIN。
     * @param newPin 新设备 PIN。
     */
    suspend fun changePin(
        currentPin: String,
        newPin: String,
    ) {
        rewrapActiveCredential(
            currentPin = currentPin,
            newPin = newPin,
            expectedPinEnabled = true,
        )
    }

    /**
     * 认证当前 PIN 后改回当前 Windows 用户保护的自动打开模式。
     *
     * @param currentPin 当前设备 PIN。
     */
    suspend fun disablePin(currentPin: String) {
        rewrapActiveCredential(
            currentPin = currentPin,
            newPin = null,
            expectedPinEnabled = true,
        )
    }

    /**
     * 放弃未确认创建时销毁暂存材料与可选 PIN 引用。
     */
    fun discardPendingCreation() {
        pendingCreationCredential?.destroy()
        pendingCreationCredential = null
        pendingCreationPin = null
    }

    /**
     * 使用已启用模式对应的凭据恢复材料，再原子改写为新的本机保护模式。
     *
     * @param currentPin 当前 PIN；预期无 PIN 模式时必须为空。
     * @param newPin 新 PIN；为空时改为 Windows 帐户保护。
     * @param expectedPinEnabled 调用入口预期的当前 PIN 启用状态。
     */
    private suspend fun rewrapActiveCredential(
        currentPin: String?,
        newPin: String?,
        expectedPinEnabled: Boolean,
    ) {
        val target = requireNotNull(accessStore.activeWorkspaceOrNull()) {
            "workspace-access-record-missing"
        }
        check(target.requiresPin == expectedPinEnabled) { "workspace-access-protection-state-mismatch" }
        val credential = requireNotNull(accessStore.unlockActiveCredential(currentPin)) {
            "workspace-access-record-missing"
        }
        try {
            accessStore.rememberAsActive(credential, newPin)
        } finally {
            credential.destroy()
        }
    }

    /**
     * 尝试锁定已打开会话，并保留本机登记失败这一原始异常作为主失败原因。
     *
     * @param original 本机登记写入失败。
     * @param onRegistrationFailure 关闭会话并销毁会话密钥的补偿动作。
     */
    private suspend fun lockAfterRegistrationFailure(
        original: Throwable,
        onRegistrationFailure: suspend () -> Unit,
    ) {
        runCatching { onRegistrationFailure() }
            .exceptionOrNull()
            ?.let(original::addSuppressed)
    }
}
