package com.oruke.tagloom.filesystem.jvm

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Win32Exception
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinNT
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * 为桌面宿主选择目录持久化实现。
 * @return Windows 原生实现或 Unix FileChannel 实现。
 */
internal actual fun platformDirectoryDurability(): JvmDirectoryDurability =
    if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
        WindowsDirectoryDurability()
    } else {
        FileChannelDirectoryDurability()
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
