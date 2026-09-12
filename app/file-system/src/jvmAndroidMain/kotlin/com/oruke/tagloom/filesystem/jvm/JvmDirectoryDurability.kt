package com.oruke.tagloom.filesystem.jvm

import java.nio.file.Path

/** JVM 与 Android 文件事务所需的目录元数据持久化契约。 */
interface JvmDirectoryDurability {
    /**
     * 等待目录中此前的创建、重命名和删除操作进入稳定存储。
     * @param directory 需要持久化的目录。
     */
    fun force(directory: Path)
}

/** 为共享文件事务选择宿主原生目录持久化实现。 */
object JvmDirectoryDurabilityFactory {
    /**
     * 获取当前宿主实现，失败不得静默降级为空操作。
     * @return 可实际刷新目录元数据的实现。
     */
    fun create(): JvmDirectoryDurability = platformDirectoryDurability()
}

/**
 * 由宿主提供目录持久化原语。
 * @return 宿主支持的目录刷新实现。
 */
internal expect fun platformDirectoryDurability(): JvmDirectoryDurability
