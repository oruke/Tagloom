package com.oruke.tagloom.workspace

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 获取 Android 设备密钥保护器。
 * @return 使用不可导出 AES 密钥的保护器。
 */
internal actual fun platformWorkspaceCredentialProtector(): DesktopWorkspaceCredentialProtector =
    AndroidWorkspaceCredentialProtector()

/** 使用 Android Keystore 保护本机高熵解锁材料；不改变工作区快照的密码套件。 */
internal class AndroidWorkspaceCredentialProtector : DesktopWorkspaceCredentialProtector {
    /** 明确标记设备密文，禁止与 Windows DPAPI 记录混用。 */
    override val protection = DesktopWorkspaceCredentialProtection.ANDROID_KEYSTORE

    /**
     * 使用系统生成的随机 GCM IV 加密本机材料。
     * @param plaintext 随机本机解锁材料。
     * @param associatedData 与工作区路径和记录 ID 绑定的认证上下文。
     * @return 版本字节、12 字节 IV 和带认证标签的密文。
     */
    override fun protect(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey(create = true))
        cipher.updateAAD(associatedData)
        check(cipher.iv.size == IV_BYTES) { "workspace-access-iv-invalid" }
        return byteArrayOf(FORMAT_VERSION) + cipher.iv + cipher.doFinal(plaintext)
    }

    /**
     * 认证并解开当前设备保存的材料，不在密钥丢失时生成替代密钥。
     * @param protectedData 已持久化的版本化设备密文。
     * @param associatedData 保护时使用的完整认证上下文。
     * @return 调用方用完后必须清零的随机材料。
     */
    override fun unprotect(protectedData: ByteArray, associatedData: ByteArray): ByteArray {
        require(protectedData.size >= 1 + IV_BYTES + 16 && protectedData[0] == FORMAT_VERSION) {
            "workspace-access-ciphertext-invalid"
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(create = false),
            GCMParameterSpec(128, protectedData.copyOfRange(1, 1 + IV_BYTES)))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(protectedData, 1 + IV_BYTES, protectedData.size - 1 - IV_BYTES)
    }

    /**
     * 获取或首次生成不可导出的设备 AES 密钥。
     * @param create 仅加密首次登记时允许生成；解密缺失密钥必须失败。
     * @return Keystore 密钥句柄，不会导出密钥字节。
     */
    private fun encryptionKey(create: Boolean): SecretKey = synchronized(KEY_LOCK) {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return@synchronized it }
        check(create) { "workspace-access-device-key-missing" }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build())
        generator.generateKey()
    }

    private companion object {
        /** 本机材料密文版本，独立于工作区信封版本。 */
        const val FORMAT_VERSION: Byte = 1
        /** GCM 使用的随机 IV 长度。 */
        const val IV_BYTES: Int = 12
        /** Android 系统密钥容器。 */
        const val KEYSTORE: String = "AndroidKeyStore"
        /** 仅用于本机访问材料的固定密钥别名。 */
        const val KEY_ALIAS: String = "tagloom.workspace-access.v1"
        /** 仅本机材料使用的系统 AEAD 算法。 */
        const val TRANSFORMATION: String = "AES/GCM/NoPadding"
        /** 防止多个保护器同时创建并覆盖同一别名。 */
        val KEY_LOCK: Any = Any()
    }
}
