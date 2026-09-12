package com.oruke.tagloom.workspace

import com.oruke.tagloom.filesystem.jvm.JvmAtomicFileOperations
import java.nio.file.Files
import java.nio.file.Path

/**
 * 将设备保护后的访问登记原子发布；先限制临时文件权限，再强制持久化文件和目录项。
 * @param registryFile 应用配置目录中的登记目标，禁止符号链接。
 * @param bytes 仅含设备保护材料和本机路由信息的完整登记。
 */
internal fun writeWorkspaceRegistryAtomically(registryFile: Path, bytes: ByteArray) {
    val parent = requireNotNull(registryFile.parent) { "workspace-access-registry-parent-missing" }
    Files.createDirectories(parent)
    check(!Files.isSymbolicLink(registryFile)) { "workspace-access-registry-is-a-symbolic-link" }
    val temporary = Files.createTempFile(parent, ".workspace-access-", ".tmp")
    try {
        // 临时文件也受相同设备权限限制，避免发布前扩大登记的可读范围。
        restrictWorkspaceRegistryPermissions(temporary)
        Files.write(temporary, bytes)
        // 本机登记是下次启动的解锁入口，不允许在原子替换或目录 fsync 失败时报告成功。
        JvmAtomicFileOperations().publishPreparedFileAtomically(temporary, registryFile)
    } finally {
        bytes.fill(0)
        Files.deleteIfExists(temporary)
    }
}

/**
 * 限制登记文件为当前设备应用或用户可读写。
 * @param path 已创建且尚未写入登记内容的普通文件。
 */
internal expect fun restrictWorkspaceRegistryPermissions(path: Path)
