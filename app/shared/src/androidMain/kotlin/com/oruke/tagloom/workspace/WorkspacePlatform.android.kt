package com.oruke.tagloom.workspace

import com.oruke.tagloom.capability.AndroidTagloomEnvironment
import java.nio.file.Path
import android.system.Os
import android.system.OsConstants

/**
 * 获取 Android 私有本机登记路径。
 * @return 不参与 Android 自动备份的本机登记文件。
 */
internal actual fun platformWorkspaceRegistryFile(): Path =
    AndroidTagloomEnvironment.context().noBackupFilesDir.canonicalFile.toPath().resolve("workspace-access.json")

/**
 * 创建 Android 密文文档入口；交互前需绑定 Activity。
 * @return 使用私有目录和系统文档 URI 的实现。
 */
internal actual fun platformWorkspaceDocuments(): WorkspaceDocumentAccess =
    AndroidWorkspaceDocuments(AndroidTagloomEnvironment.context())

/**
 * 通过 Android 文件 API 将私有登记限制为应用 UID 可读写，无需查询受限的 FileStore。
 * @param path 应用私有目录中的临时普通文件。
 */
internal actual fun restrictWorkspaceRegistryPermissions(path: Path) {
    Os.chmod(path.toString(), OsConstants.S_IRUSR or OsConstants.S_IWUSR)
}
