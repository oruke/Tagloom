package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.crypto.CryptoProvider
import com.oruke.tagloom.core.workspace.UnlockedWorkspaceManifest
import com.oruke.tagloom.filestore.jvm.JvmWorkspacePaths
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 对 manifest 引用的全部权威密文执行存在性和摘要扫描。
 *
 * @param paths 当前工作区受控路径。
 * @param cryptoProvider 计算密文 SHA-256 的端口。
 */
class DesktopWorkspaceIntegrityScanner(
    private val paths: JvmWorkspacePaths,
    private val cryptoProvider: CryptoProvider,
) {
    /**
     * 扫描全部 manifest 对象，不读取或输出任何明文。
     *
     * @param unlocked 当前已认证 manifest。
     * @return 按对象路径稳定排序的脱敏问题。
     */
    suspend fun inspect(unlocked: UnlockedWorkspaceManifest): List<DesktopObjectIntegrityIssue> =
        withContext(Dispatchers.IO) {
            unlocked.payload.objects
                .sortedBy { record -> record.path.value }
                .mapNotNull { record ->
                    val file = paths.target(record.path)
                    when {
                        !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) -> DesktopObjectIntegrityIssue(
                            record.kind.name,
                            record.objectId,
                            DesktopIntegrityFailure.MISSING_FILE,
                        )
                        cryptoProvider.sha256(Files.readAllBytes(file)).toHex() != record.encryptedSha256 ->
                            DesktopObjectIntegrityIssue(
                                record.kind.name,
                                record.objectId,
                                DesktopIntegrityFailure.HASH_MISMATCH,
                            )
                        else -> null
                    }
                }
        }

    /** 把摘要字节编码为小写十六进制。 */
    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
    }
}
