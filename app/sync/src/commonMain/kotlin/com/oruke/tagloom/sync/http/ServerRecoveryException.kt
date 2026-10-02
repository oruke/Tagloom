package com.oruke.tagloom.sync.http

/** 只携带稳定错误码的远端恢复异常，禁止把响应正文、URL 或认证头带入日志。 */
class ServerRecoveryException(
    /** 可由共享 UI 映射为本地化操作提示的分类。 */
    val code: String,
) : IllegalStateException(code)
