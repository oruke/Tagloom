package com.oruke.tagloom.workspace

/**
 * Android 客户端不使用桌面 shell 环境变量作为 API Key 来源。
 */
internal actual val supportsDesktopEnvironmentVariables: Boolean = false

/**
 * Android 平台始终返回空，提示改用 Android Keystore 或加密信封同步密钥。
 *
 * @param name 环境变量名。
 * @return 恒为空。
 */
internal actual fun readDesktopEnvironmentVariable(name: String): String? = null
