package com.oruke.tagloom.workspace

import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission

/**
 * 保持桌面本机登记路径兼容。
 * @return 当前用户应用配置目录中的登记文件。
 */
internal actual fun platformWorkspaceRegistryFile(): Path {
    val localAppData = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)
    val base = if (localAppData == null) Path.of(System.getProperty("user.home"), ".tagloom")
        else Path.of(localAppData, "Tagloom")
    return base.resolve("workspace-access.json")
}

/**
 * POSIX 宿主使用所有者权限，Windows 继承当前用户配置目录的 ACL。
 * @param path 本机登记的临时普通文件。
 */
internal actual fun restrictWorkspaceRegistryPermissions(path: Path) {
    if (Files.getFileAttributeView(path, PosixFileAttributeView::class.java) != null) {
        Files.setPosixFilePermissions(path, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
    }
}
