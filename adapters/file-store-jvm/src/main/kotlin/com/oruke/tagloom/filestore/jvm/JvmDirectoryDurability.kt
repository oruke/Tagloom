package com.oruke.tagloom.filestore.jvm

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Win32Exception
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinNT
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * 抽象不同桌面操作系统持久化目录元数据所需的能力。
 */
interface JvmDirectoryDurability {
    /**
     * 等待指定目录此前的创建、重命名或删除元数据进入稳定存储。
     *
     * @param directory 待持久化目录。
     */
    fun force(directory: Path)
}

/**
 * 选择当前 JVM 操作系统可用的目录持久化实现。
 */
object JvmDirectoryDurabilityFactory {
    /**
     * 根据 JVM OS 属性创建实现，不根据文件内容或运行时异常静默切换。
     *
     * @return Windows 原生实现或支持目录 FileChannel 的通用实现。
     */
    fun create(): JvmDirectoryDurability = if (isWindows()) {
        WindowsDirectoryDurability()
    } else {
        FileChannelDirectoryDurability()
    }

    /**
     * 判断当前 JVM 是否运行在 Windows。
     *
     * @return OS 名称以 Windows 开头为 `true`。
     */
    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
}

/**
 * 使用 `CreateFileW(FILE_FLAG_BACKUP_SEMANTICS)` 和 `FlushFileBuffers` 持久化 Windows 目录句柄。
 */
class WindowsDirectoryDurability : JvmDirectoryDurability {
    /**
     * 打开目录句柄、刷新文件系统缓冲并可靠关闭句柄。
     *
     * @param directory 待刷新目录。
     */
    override fun force(directory: Path) {
        val handle = Kernel32.INSTANCE.CreateFile(
            directory.toAbsolutePath().normalize().toString(),
            WinNT.GENERIC_READ or WinNT.GENERIC_WRITE,
            WinNT.FILE_SHARE_READ or WinNT.FILE_SHARE_WRITE or WinNT.FILE_SHARE_DELETE,
            null,
            WinNT.OPEN_EXISTING,
            WinNT.FILE_FLAG_BACKUP_SEMANTICS,
            null,
        )
        if (handle == WinBase.INVALID_HANDLE_VALUE) {
            throw Win32Exception(Kernel32.INSTANCE.GetLastError())
        }
        try {
            if (!Kernel32.INSTANCE.FlushFileBuffers(handle)) {
                throw Win32Exception(Kernel32.INSTANCE.GetLastError())
            }
        } finally {
            if (!Kernel32.INSTANCE.CloseHandle(handle)) {
                throw Win32Exception(Kernel32.INSTANCE.GetLastError())
            }
        }
    }
}

/**
 * 在 Unix 类文件系统上通过只读目录 FileChannel 执行 force。
 */
class FileChannelDirectoryDurability : JvmDirectoryDurability {
    /**
     * 打开目录通道并请求元数据持久化。
     *
     * @param directory 待刷新目录。
     */
    override fun force(directory: Path) {
        FileChannel.open(directory, StandardOpenOption.READ).use { channel -> channel.force(true) }
    }
}
