package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.crypto.CryptoProvider
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.ObjectSnapshot
import com.oruke.tagloom.core.model.SavedQuery
import com.oruke.tagloom.core.transaction.SavedQuerySnapshotEnvelopeService
import com.oruke.tagloom.core.workspace.ManifestObjectKind
import com.oruke.tagloom.core.workspace.UnlockedWorkspaceManifest
import com.oruke.tagloom.filesystem.jvm.JvmWorkspacePaths
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 从 manifest 引用的权威密文加载并认证 SavedQuery 快照。
 *
 * @param paths 当前工作区受控路径。
 * @param cryptoProvider 计算密文摘要的密码学端口。
 * @param envelopeService 解密和校验查询快照的服务。
 */
class DesktopSavedQueryLoader(
    private val paths: JvmWorkspacePaths,
    private val cryptoProvider: CryptoProvider,
    private val envelopeService: SavedQuerySnapshotEnvelopeService,
) {
    /**
     * 加载全部未删除保存查询，并按名称和 ID 稳定排序。
     *
     * @param unlocked 当前已认证 manifest 与主密钥。
     * @return 已认证活动查询快照。
     */
    suspend fun load(unlocked: UnlockedWorkspaceManifest): List<ObjectSnapshot<SavedQuery>> = withContext(Dispatchers.IO) {
        inspect(unlocked).snapshots
    }

    /**
     * 扫描保存查询并把缺失、摘要错误或认证失败对象逻辑隔离。
     *
     * @param unlocked 当前已认证 manifest 与主密钥。
     * @return 有效查询快照和脱敏完整性问题。
     */
    suspend fun inspect(unlocked: UnlockedWorkspaceManifest): DesktopSavedQueryLoadResult = withContext(Dispatchers.IO) {
        val issues = mutableListOf<DesktopObjectIntegrityIssue>()
        unlocked.payload.objects
            .filter { record -> record.kind == ManifestObjectKind.SAVED_QUERY }
            .mapNotNull { record ->
                val file = paths.target(record.path)
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    issues += record.toIssue(DesktopIntegrityFailure.MISSING_FILE)
                    return@mapNotNull null
                }
                val encrypted = Files.readAllBytes(file)
                if (cryptoProvider.sha256(encrypted).toHex() != record.encryptedSha256) {
                    issues += record.toIssue(DesktopIntegrityFailure.HASH_MISMATCH)
                    return@mapNotNull null
                }
                runCatching {
                    envelopeService.decrypt(
                    encryptedBytes = encrypted,
                    workspaceId = unlocked.file.publicHeader.workspaceId,
                    objectId = ObjectId(record.objectId),
                    revision = record.revision,
                    keyId = record.keyId,
                    workspaceMasterKey = unlocked.workspaceMasterKey,
                    )
                }.getOrElse {
                    issues += record.toIssue(DesktopIntegrityFailure.AUTHENTICATION_FAILED)
                    null
                }
            }
            .filterNot { snapshot -> snapshot.header.deleted }
            .sortedWith(compareBy({ snapshot -> snapshot.payload.name }, { snapshot -> snapshot.header.objectId.value }))
            .let { snapshots -> DesktopSavedQueryLoadResult(snapshots, issues) }
    }

    /**
     * 把 manifest 记录转换为不含查询名称或条件的完整性问题。
     *
     * @param failure 失败分类。
     * @return 脱敏问题。
     */
    private fun com.oruke.tagloom.core.workspace.ManifestObjectRecord.toIssue(
        failure: DesktopIntegrityFailure,
    ): DesktopObjectIntegrityIssue = DesktopObjectIntegrityIssue(kind, objectId, failure)

    /** 把摘要字节编码为小写十六进制。 */
    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
    }
}

/**
 * 保存 SavedQuery 扫描得到的有效快照与逻辑隔离问题。
 */
data class DesktopSavedQueryLoadResult(
    /** 可以展示和执行的查询快照。 */
    val snapshots: List<ObjectSnapshot<SavedQuery>>,
    /** 已跳过的损坏查询。 */
    val issues: List<DesktopObjectIntegrityIssue>,
)
