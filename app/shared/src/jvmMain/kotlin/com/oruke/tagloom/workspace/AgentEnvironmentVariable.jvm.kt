package com.oruke.tagloom.workspace

/**
 * JVM 桌面端支持从宿主环境变量按名读取 API Key。
 */
internal actual val supportsDesktopEnvironmentVariables: Boolean = true

/**
 * 从 JVM 宿主进程环境变量读取非空白凭据值。
 *
 * @param name 环境变量名。
 * @return 去除首尾空白后的非空值；不存在或全空白时为空。
 */
internal actual fun readDesktopEnvironmentVariable(name: String): String? =
    System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }
