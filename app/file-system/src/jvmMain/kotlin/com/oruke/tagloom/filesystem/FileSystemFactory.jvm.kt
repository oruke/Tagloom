package com.oruke.tagloom.filesystem

import com.oruke.tagloom.core.crypto.CryptoProvider
import com.oruke.tagloom.core.transaction.DerivedStateUpdater
import com.oruke.tagloom.core.workspace.WorkspaceManifestService
import com.oruke.tagloom.filesystem.jvm.JvmDirectoryDurability
import com.oruke.tagloom.filesystem.jvm.JvmWorkspaceFileSystem
import com.oruke.tagloom.filesystem.jvm.JvmWorkspaceRecoveryContext
import com.oruke.tagloom.filesystem.jvm.crypto.LibsodiumCryptoProvider
import java.nio.file.Path

/**
 * JVM 文件能力默认装配。
 *
 * 默认根目录可通过 `tagloom.workspace.root` 系统属性指定；未指定时使用当前用户的
 * `.tagloom` 目录。需要恢复已解锁工作区时应调用 [createJvmWorkspaceFileSystem] 传入上下文。
 *
 * @return JVM 工作区文件系统实现。
 */
actual fun createWorkspaceFileSystem(): WorkspaceFileSystem =
    JvmWorkspaceFileSystem(defaultWorkspaceRoot())

/**
 * 解析 JVM 默认工作区目录。
 *
 * @return 系统属性指定目录或用户目录下的 `.tagloom`。
 */
private fun defaultWorkspaceRoot(): Path = System.getProperty("tagloom.workspace.root")
    ?.takeIf(String::isNotBlank)
    ?.let(Path::of)
    ?: Path.of(System.getProperty("user.home"), ".tagloom")
