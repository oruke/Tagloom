package com.oruke.tagloom.workspace

import com.sun.jna.platform.win32.Crypt32Util
import java.nio.file.Files
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** 验证实际文件和 DPAPI 输入失败不会把配置或恢复材料带进未捕获异常。 */
class NasSyncAcceptanceConfigurationTest {
    /** 真实临时配置缺少 JSON 终止符时，异常只保留固定分类而不附原始文本。 */
    @Test
    fun malformedConfigurationDoesNotLeakInputInFailure() {
        val path = Files.createTempFile("nas-configuration-privacy-", ".json")
        try {
            Files.writeString(path, "{\"bootstrapToken\":\"$SAMPLE_PRIVATE_VALUE\",")
            val failure = assertFailsWith<IllegalStateException> { readNasAcceptanceConfiguration(arrayOf(path.toString())) }
            assertEquals("nas_acceptance_configuration_invalid", failure.message)
            assertNull(failure.cause)
            assertFalse(failure.stackTraceToString().contains(SAMPLE_PRIVATE_VALUE))
        } finally { Files.deleteIfExists(path) }
    }

    /** 真正经 DPAPI 保护但不能解析的状态，同样不允许异常展开其恢复材料片段。 */
    @Test
    fun malformedProtectedStateDoesNotLeakDecryptedInput() {
        val path = Files.createTempFile("nas-state-privacy-", ".dpapi")
        val plaintext = "{\"recoveryKey\":\"$SAMPLE_PRIVATE_VALUE\",".encodeToByteArray()
        try {
            Files.write(path, Crypt32Util.cryptProtectData(plaintext))
            plaintext.fill(0)
            val failure = assertFailsWith<IllegalStateException> { readNasAcceptanceProtectedState(path, PrivateStateProbe.serializer()) }
            assertEquals("nas_acceptance_state_invalid", failure.message)
            assertNull(failure.cause)
            assertFalse(failure.stackTraceToString().contains(SAMPLE_PRIVATE_VALUE))
        } finally { plaintext.fill(0); Files.deleteIfExists(path) }
    }

    private companion object {
        /** 仅用于验证错误文本隔离的脱敏样例，不具有任何认证能力。 */
        const val SAMPLE_PRIVATE_VALUE: String = "sample-recovery-material-must-not-appear"
    }
}

/** 用实际状态反序列化协议触发错误，不使用密码或网络 Mock。 */
@Serializable
private data class PrivateStateProbe(
    /** 仅供错误输入测试的脱敏恢复字段。 */
    val recoveryKey: String,
)
