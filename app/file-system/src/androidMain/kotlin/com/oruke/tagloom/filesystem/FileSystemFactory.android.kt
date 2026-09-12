package com.oruke.tagloom.filesystem

/**
 * 创建 Android 应用私有目录中的文件、加密与事务能力。
 *
 * @return 与 JVM 共用事务协议的 Android 文件能力。
 */
actual fun createWorkspaceFileSystem(): WorkspaceFileSystem =
    createJvmWorkspaceFileSystem(AndroidFileSystemEnvironment.workspaceDirectory().resolve("default"))
