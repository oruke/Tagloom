package com.oruke.tagloom.core.workspace

import com.oruke.tagloom.core.protocol.CanonicalJson

/**
 * 区分 manifest 解析和状态约束失败，避免向日志暴露原始密文或 JSON。
 */
enum class WorkspaceManifestFailureCode {
    /** 文件为空、超限、JSON 损坏或缺少必需字段。 */
    MALFORMED_FILE,

    /** 文件版本、身份或状态转换不受支持。 */
    UNSUPPORTED_STATE,
}

/**
 * 表示不携带 manifest 原始内容的安全解析异常。
 *
 * @property code 调用方可以稳定处理的失败类别。
 * @param cause 仅供本地诊断链使用且不得直接记录消息。
 */
class WorkspaceManifestException(
    val code: WorkspaceManifestFailureCode,
    cause: Throwable? = null,
) : RuntimeException("工作区 manifest 操作失败：${code.name}", cause)

/**
 * 使用确定性 JSON 编解码完整 `manifest.enc` 容器，并限制不可信文件大小。
 */
object WorkspaceManifestCodec {
    /** manifest 文件允许的最大字节数。 */
    private const val MAX_MANIFEST_BYTES: Int = 4 * 1_024 * 1_024

    /**
     * 将公开头与加密载荷编码为稳定字节。
     *
     * @param file 已完成结构校验的 manifest 容器。
     * @return 可持久化的 UTF-8 确定性 JSON 字节。
     */
    fun encode(file: WorkspaceManifestFile): ByteArray = CanonicalJson
        .encodeToString(WorkspaceManifestFile.serializer(), file)
        .encodeToByteArray()
        .also { bytes ->
            if (bytes.size > MAX_MANIFEST_BYTES) {
                throw WorkspaceManifestException(WorkspaceManifestFailureCode.UNSUPPORTED_STATE)
            }
        }

    /**
     * 从不可信磁盘字节解析 manifest，同时统一隐藏解析器的输入细节。
     *
     * @param bytes 完整 `manifest.enc` 字节。
     * @return 通过实体约束校验的 manifest 容器。
     * @throws WorkspaceManifestException 文件为空、超限或无法解析时抛出。
     */
    fun decode(bytes: ByteArray): WorkspaceManifestFile {
        if (bytes.isEmpty() || bytes.size > MAX_MANIFEST_BYTES) {
            throw WorkspaceManifestException(WorkspaceManifestFailureCode.MALFORMED_FILE)
        }
        return try {
            CanonicalJson.decodeFromString(
                WorkspaceManifestFile.serializer(),
                bytes.decodeToString(),
            )
        } catch (exception: WorkspaceManifestException) {
            throw exception
        } catch (exception: Throwable) {
            throw WorkspaceManifestException(WorkspaceManifestFailureCode.MALFORMED_FILE, exception)
        }
    }
}
