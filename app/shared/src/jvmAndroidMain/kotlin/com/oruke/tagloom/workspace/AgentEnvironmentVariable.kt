package com.oruke.tagloom.workspace

/**
 * 标识当前平台是否允许读取桌面进程环境变量作为模型连接凭据。
 */
internal expect val supportsDesktopEnvironmentVariables: Boolean

/**
 * 按合法环境变量名读取当前进程环境变量中的非空密钥文本（仅在已解锁会话内存中按需调用）。
 *
 * @param name 已经过标识符格式校验的环境变量名称。
 * @return 非空白环境变量值；未设置或平台不支持时返回空。
 */
internal expect fun readDesktopEnvironmentVariable(name: String): String?
