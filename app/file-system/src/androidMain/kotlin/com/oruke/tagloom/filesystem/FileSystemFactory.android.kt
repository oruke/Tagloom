package com.oruke.tagloom.filesystem

/**
 * Android 文件能力装配占位。
 *
 * @return 当前阶段不返回文件系统实现。
 */
actual fun createWorkspaceFileSystem(): WorkspaceFileSystem =
    TODO("Android 工作区文件系统尚未装配")
