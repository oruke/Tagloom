package com.oruke.tagloom.workspace

/**
 * 保护未启用 PIN 时保存在本机的高熵工作区解锁材料。
 *
 * 该能力只适用于本机访问登记：材料仍保存在应用配置目录的登记文件中，但必须先由
 * 系统密钥保护能力绑定到当前设备，不能因“无需输入 PIN”而退化为明文保存。
 */
internal interface DesktopWorkspaceCredentialProtector {
    /** 本机登记中稳定记录的保护方式，防止跨宿主误解释密文。 */
    val protection: DesktopWorkspaceCredentialProtection

    /**
     * 将本机高熵解锁材料保护为只可由当前设备解开的二进制数据。
     *
     * @param plaintext 待保护的随机本机解锁材料。
     * @param associatedData 与工作区记录绑定的认证上下文。
     * @return 可安全写入本机访问登记的 设备保护数据。
     */
    fun protect(
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray

    /**
     * 解开当前设备保护的本机高熵解锁材料。
     *
     * @param protectedData 访问登记保存的 设备保护数据。
     * @param associatedData 与保护时完全一致的认证上下文。
     * @return 仅供当前调用短暂使用的随机本机解锁材料。
     */
    fun unprotect(
        protectedData: ByteArray,
        associatedData: ByteArray,
    ): ByteArray
}
/**
 * 获取当前宿主的设备绑定保护器。
 * @return Windows DPAPI 或 Android Keystore 实现。
 */
internal expect fun platformWorkspaceCredentialProtector(): DesktopWorkspaceCredentialProtector
