package com.oruke.tagloom.workspace

import com.sun.jna.Platform
import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.platform.win32.WinCrypt

/**
 * 保护未启用 PIN 时保存在本机的高熵工作区解锁材料。
 *
 * 该能力只适用于当前 Windows MVP：材料仍保存在应用配置目录的登记文件中，但必须先由
 * Windows DPAPI 绑定到当前用户帐户，不能因“无需输入 PIN”而退化为明文保存。
 */
internal interface DesktopWorkspaceCredentialProtector {
    /**
     * 将本机高熵解锁材料保护为只可由当前 Windows 用户解开的二进制数据。
     *
     * @param plaintext 待保护的随机本机解锁材料。
     * @param associatedData 与工作区记录绑定的认证上下文。
     * @return 可安全写入本机访问登记的 Windows 保护数据。
     */
    fun protect(
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray

    /**
     * 解开当前 Windows 用户保护的本机高熵解锁材料。
     *
     * @param protectedData 访问登记保存的 Windows 保护数据。
     * @param associatedData 与保护时完全一致的认证上下文。
     * @return 仅供当前调用短暂使用的随机本机解锁材料。
     */
    fun unprotect(
        protectedData: ByteArray,
        associatedData: ByteArray,
    ): ByteArray
}
/**
 * 使用 Windows DPAPI 保护未设置 PIN 的本机工作区解锁材料。
 *
 * DPAPI 密文绑定当前 Windows 用户配置文件；即使登记文件被复制到另一台设备或另一个
 * Windows 用户帐户，也不能直接恢复工作区材料。调用时禁止弹出系统界面，避免桌面启动
 * 流程被隐式交互阻塞。
 */
internal class WindowsWorkspaceCredentialProtector : DesktopWorkspaceCredentialProtector {
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
