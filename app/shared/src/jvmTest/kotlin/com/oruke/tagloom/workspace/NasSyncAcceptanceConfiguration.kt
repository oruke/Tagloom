package com.oruke.tagloom.workspace

import com.sun.jna.platform.win32.Crypt32Util
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 外部协调器提供的临时配置；仅配置路径可以出现在命令行中。 */
@Serializable
internal data class NasAcceptanceConfiguration(
    /** 本轮隔离目录的随机身份。 */
    val runId: String,
    /** 当前待执行的阶段。 */
    val phase: String,
    /** 系统信任 HTTPS 的实际 NAS 地址。 */
    val serverUrl: String,
    /** 仅初始化首次登记需要的短期部署访问码。 */
    val bootstrapToken: String = "",
)

/** 将配置路径和已解析配置一起交给验收入口，避免入口自行展开读取异常。 */
internal class NasAcceptanceConfigurationInput(
    /** 本次显式选择且已经规范化的配置文件。 */
    val path: Path,
    /** 只在验收进程内使用的配置，不写入日志。 */
    val configuration: NasAcceptanceConfiguration,
)

/**
 * 有界读取临时配置，所有路径、I/O 和解析失败仅返回固定分类，不携带 JSON 或原始 cause。
 * @param args 唯一配置路径；为空时读取专用环境变量，其他参数个数均拒绝。
 * @return 供后续隔离目录校验的路径和配置。
 */
internal fun readNasAcceptanceConfiguration(args: Array<String>): NasAcceptanceConfigurationInput {
    try {
        require(args.size <= 1)
        val selected = args.singleOrNull() ?: requireNotNull(System.getenv("TAGLOOM_NAS_ACCEPTANCE_CONFIG"))
        val path = Path.of(selected).toAbsolutePath().normalize()
        val bytes = readNasAcceptanceInputBytes(path)
        return try {
            NasAcceptanceConfigurationInput(path, nasAcceptanceInputJson.decodeFromString(
                NasAcceptanceConfiguration.serializer(), bytes.decodeToString()))
        } finally { bytes.fill(0) }
    } catch (_: Exception) {
        // kotlinx JSON 错误可能附带原始输入，配置包含部署登记材料，不能让 JVM 自动打印原异常。
        throw IllegalStateException("nas_acceptance_configuration_invalid")
    }
}

/**
 * 解密并解析本轮 DPAPI 状态；损坏密文、坏 JSON 或协议错误均不向外暴露恢复材料。
 * @param T 本轮状态的结构类型。
 * @param path 仅当前运行使用的 DPAPI 文件，不接受工作区快照作为配置。
 * @param serializer 由验收入口确定的状态协议。
 * @return 文件尚未创建时为空，否则为认证并解析后的状态。
 */
internal fun <T> readNasAcceptanceProtectedState(path: Path, serializer: KSerializer<T>): T? {
    try {
        if (!Files.exists(path, NOFOLLOW_LINKS)) return null
        val ciphertext = readNasAcceptanceInputBytes(path)
        val plaintext = try { Crypt32Util.cryptUnprotectData(ciphertext) } finally { ciphertext.fill(0) }
        return try {
            nasAcceptanceInputJson.decodeFromString(serializer, plaintext.decodeToString())
        } finally { plaintext.fill(0) }
    } catch (_: Exception) {
        // 此处在阶段对象构造期间执行，必须先收敛异常，不能依赖稍后业务阶段的 catch。
        throw IllegalStateException("nas_acceptance_state_invalid")
    }
}

/**
 * 只读取本轮小型普通配置文件，拒绝链接叶节点和异常体积，避免错误输入扩散到解析器。
 * @param path 调用方限定的配置或密文状态文件。
 * @return 由调用方在解析或解密后清零的短期字节。
 */
private fun readNasAcceptanceInputBytes(path: Path): ByteArray {
    require(Files.isRegularFile(path, NOFOLLOW_LINKS) && Files.size(path) in 1L..MAX_NAS_ACCEPTANCE_INPUT_BYTES)
    val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_NAS_ACCEPTANCE_INPUT_BYTES.toInt() + 1) }
    if (bytes.size.toLong() > MAX_NAS_ACCEPTANCE_INPUT_BYTES) {
        bytes.fill(0)
        throw IllegalArgumentException("nas_acceptance_input_too_large")
    }
    return bytes
}

/** 与协调器无关的附加字段可以忽略，但解析失败不得输出原始配置。 */
private val nasAcceptanceInputJson = Json { ignoreUnknownKeys = true }
/** 恢复材料和短期凭据配置的硬上限；本轮状态远小于此值。 */
private const val MAX_NAS_ACCEPTANCE_INPUT_BYTES: Long = 65_536L
