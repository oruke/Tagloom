package com.oruke.tagloom.workspace

import java.nio.file.Path
import java.util.Base64

/**
 * 对共享 UI 只暴露本机最近工作区的安全展示信息。
 *
 * @param recordId 本机登记内部标识；仅用于本次进程状态，不是工作区或密钥 ID。
 * @param displayName 用户选择目录的展示名称。
 * @param requiresPin 当前设备是否必须输入 PIN 才能恢复本机材料。
 */
data class DesktopWorkspaceAccessTarget(
    /** 本机登记内部标识。 */
    val recordId: String,
    /** 可显示的工作区目录名称。 */
    val displayName: String,
    /** 是否已启用 PIN 保护。 */
    val requiresPin: Boolean,
)

/**
 * 在内存中持有高熵本机解锁材料，并限制其只能短暂转换为 manifest 密码槽输入。
 *
 * @param workspaceRoot 此材料对应的规范化工作区根目录。
 * @param secret 调用方提供的 32 字节随机材料；构造时立即复制。
 */
class DesktopWorkspaceAccessCredential internal constructor(
    workspaceRoot: Path,
    secret: ByteArray,
) {
    /** 此材料对应的规范化工作区目录。 */
    val workspaceRoot: Path = workspaceRoot.toAbsolutePath().normalize()

    /** 可在流程结束时尽力清零的随机材料副本。 */
    private val secret: ByteArray = secret.copyOf()

    /** 凭据是否已被销毁，销毁后禁止再次生成解锁输入。 */
    private var destroyed: Boolean = false

    /**
     * 以一次性 Base64 字符串向 manifest 密码槽操作提供高熵输入。
     *
     * JVM 字符串无法保证完全清零，因此调用方不得缓存、记录或持久化回调参数。
     *
     * @param block 使用临时高熵输入执行一次工作区操作的挂起回调。
     * @return 回调产生的不包含解锁材料的结果。
     */
    suspend fun <T> useWorkspacePassword(block: suspend (String) -> T): T {
        check(!destroyed) { "workspace-access-credential-destroyed" }
        val password = BASE64_ENCODER.encodeToString(secret)
        return block(password)
    }

    /**
     * 在受限作用域内向本机访问登记提供随机材料副本。
     *
     * @param block 使用临时材料完成保护或认证的回调。
     * @return 回调产生的不包含随机材料的结果。
     */
    internal fun <T> useSecret(block: (ByteArray) -> T): T {
        check(!destroyed) { "workspace-access-credential-destroyed" }
        val copy = secret.copyOf()
        return try {
            block(copy)
        } finally {
            copy.fill(0)
        }
    }

    /**
     * 尽力清零当前对象持有的随机材料，并使后续使用立即失败。
     */
    fun destroy() {
        secret.fill(0)
        destroyed = true
    }

    private companion object {
        /** 与访问登记一致的 URL-safe Base64 编码器。 */
        val BASE64_ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    }
}
/**
 * 提供与工作区目录隔离的桌面本机访问登记路径。
 */
internal object DesktopWorkspaceAccessPaths {
    /**
     * 解析当前操作系统用户配置目录下的固定登记文件位置。
     *
     * @return Windows 优先使用 LocalAppData，其他桌面环境使用用户目录下的应用子目录。
     */
    fun defaultRegistryFile(): Path {
        val localAppData = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)
        val base = if (localAppData == null) {
            Path.of(System.getProperty("user.home"), ".tagloom")
        } else {
            Path.of(localAppData, "Tagloom")
        }
        return base.resolve("workspace-access.json")
    }
}
