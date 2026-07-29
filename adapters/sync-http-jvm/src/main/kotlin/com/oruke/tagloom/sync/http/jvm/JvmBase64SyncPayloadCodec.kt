package com.oruke.tagloom.sync.http.jvm

import com.oruke.tagloom.core.sync.SyncPayloadCodec
import java.util.Base64

/** 使用 JDK 标准无换行 Base64 实现同步密文编码边界。 */
object JvmBase64SyncPayloadCodec : SyncPayloadCodec {
    /**
     * 编码密文字节。
     *
     * @param payload 待编码密文。
     * @return 标准 Base64 文本。
     */
    override fun encode(payload: ByteArray): String = Base64.getEncoder().encodeToString(payload)

    /**
     * 解码标准 Base64 密文。
     *
     * @param payloadBase64 待解码文本。
     * @return 新密文字节数组。
     */
    override fun decode(payloadBase64: String): ByteArray = Base64.getDecoder().decode(payloadBase64)
}
