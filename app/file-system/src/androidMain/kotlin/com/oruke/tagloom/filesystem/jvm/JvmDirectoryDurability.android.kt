package com.oruke.tagloom.filesystem.jvm

import android.system.Os
import android.system.OsConstants
import java.nio.file.Path

/**
 * 提供 Android 私有文件系统的目录持久化实现。
 * @return 通过原生 fsync 刷新目录的实现。
 */
internal actual fun platformDirectoryDurability(): JvmDirectoryDurability = AndroidDirectoryDurability()

/** 通过 Android 原生目录描述符刷新元数据；不吞掉权限或文件系统错误。 */
class AndroidDirectoryDurability : JvmDirectoryDurability {
    /**
     * 打开目录、刷新先前的目录变更并关闭描述符。
     * @param directory 权威工作区中的真实目录，不能传入 content URI。
     */
    override fun force(directory: Path) {
        val descriptor = Os.open(directory.toString(), OsConstants.O_RDONLY, 0)
        try {
            check(OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) { "目录持久化目标不是目录" }
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }
}
