package com.oruke.tagloom.sync.server

import io.ktor.http.HttpStatusCode
import java.security.MessageDigest

/**
 * 校验用于公开身份和受控路径的不透明标识。
 * @param value 调用方提供的随机身份。
 */
internal fun requireSyncIdentity(value: String) {
    require(value.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "同步身份格式无效" }
}

/**
 * 校验协议中的 SHA-256 摘要或 32 字节公钥。
 * @param value 待校验的小写十六进制值。
 */
internal fun requireSyncDigest(value: String) {
    require(value.matches(Regex("[0-9a-f]{64}"))) { "同步摘要格式无效" }
}

/** @return 已校验十六进制值对应的新字节数组。 */
internal fun String.syncHexBytes(): ByteArray {
    requireSyncDigest(this)
    return ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/** @return 公开摘要的规范小写十六进制。 */
internal fun ByteArray.syncHex(): String = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

/**
 * 以恒定时间比较请求凭据和已存摘要，不在错误中携带凭据。
 * @param token 当前请求的明文访问凭据。
 * @param expected 已存的 SHA-256；为空表示未授权。
 * @return 凭据是否匹配。
 */
internal fun matchesSyncToken(token: String, expected: String?): Boolean {
    if (expected == null || token.length !in 1..1024) return false
    val actual = MessageDigest.getInstance("SHA-256").digest(token.encodeToByteArray())
    val stored = expected.syncHexBytes()
    return try { MessageDigest.isEqual(actual, stored) } finally { actual.fill(0); stored.fill(0) }
}

/**
 * 使用稳定错误码拒绝管理操作，不回显用户输入。
 * @param status 对外 HTTP 状态。
 * @param code 可本地化的协议错误码。
 * @return 始终抛出受控异常。
 */
internal fun rejectSync(status: HttpStatusCode, code: String): Nothing = throw SyncHttpException(status, code)
