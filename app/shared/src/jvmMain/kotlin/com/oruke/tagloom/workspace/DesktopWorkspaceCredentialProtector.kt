package com.oruke.tagloom.workspace

import com.sun.jna.Platform
import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.platform.win32.WinCrypt

/**
 * 获取 Windows 本机材料保护器。
 * @return 使用当前用户 DPAPI 的实现。
 */
internal actual fun platformWorkspaceCredentialProtector(): DesktopWorkspaceCredentialProtector = WindowsWorkspaceCredentialProtector()

/**
 * 使用 Windows DPAPI 保护未设置 PIN 的本机工作区解锁材料。
 *
 * DPAPI 密文绑定当前 Windows 用户配置文件；即使登记文件被复制到另一台设备或另一个
 * Windows 用户帐户，也不能直接恢复工作区材料。调用时禁止弹出系统界面，避免桌面启动
 * 流程被隐式交互阻塞。
 */
internal class WindowsWorkspaceCredentialProtector : DesktopWorkspaceCredentialProtector {
    /** 兼容既有 Windows 本机登记格式。 */
    override val protection = DesktopWorkspaceCredentialProtection.WINDOWS_ACCOUNT

    /**
     * 使用当前 Windows 用户的 DPAPI 主密钥保护高熵材料。
     *
     * @param plaintext 待保护的随机本机解锁材料。
     * @param associatedData 与工作区登记绑定的认证上下文。
     * @return DPAPI 输出的二进制密文。
     */
    override fun protect(
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        requireWindowsDpapi()
        return Crypt32Util.cryptProtectData(
            plaintext,
            associatedData,
            WinCrypt.CRYPTPROTECT_UI_FORBIDDEN,
            DPAPI_DESCRIPTION,
            null,
        )
    }

    /**
     * 使用当前 Windows 用户的 DPAPI 主密钥解开高熵材料。
     *
     * @param protectedData 访问登记保存的 DPAPI 二进制密文。
     * @param associatedData 与保护阶段一致的认证上下文。
     * @return 仅供当前调用短暂使用的随机本机解锁材料。
     */
    override fun unprotect(
        protectedData: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        requireWindowsDpapi()
        return Crypt32Util.cryptUnprotectData(
            protectedData,
            associatedData,
            WinCrypt.CRYPTPROTECT_UI_FORBIDDEN,
            null,
        )
    }

    /**
     * 拒绝在不具备 DPAPI 的平台上伪造“无 PIN 自动解锁”能力。
     */
    private fun requireWindowsDpapi() {
        check(Platform.isWindows()) { "workspace-access-windows-dpapi-unavailable" }
    }

    private companion object {
        /** 仅作为 DPAPI 元数据使用的固定域描述，不属于用户可见文案。 */
        const val DPAPI_DESCRIPTION: String = "tagloom-desktop-workspace-access"
    }
}
