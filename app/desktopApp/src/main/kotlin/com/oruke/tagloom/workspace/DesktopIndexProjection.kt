package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.crypto.CryptoProvider
import com.oruke.tagloom.core.crypto.EnvelopeCryptoService
import com.oruke.tagloom.core.crypto.SecretKeyMaterial
import com.oruke.tagloom.core.index.NoteIndexDocumentFactory
import com.oruke.tagloom.core.index.SearchIndexCheckpoint
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.ObjectSnapshot
import com.oruke.tagloom.core.model.TransactionId
import com.oruke.tagloom.core.transaction.DerivedStateUpdater
import com.oruke.tagloom.core.transaction.NoteSnapshotEnvelopeService
import com.oruke.tagloom.core.workspace.ManifestObjectKind
import com.oruke.tagloom.core.workspace.WorkspaceManifestPayload
import com.oruke.tagloom.core.workspace.WorkspaceManifestCodec
import com.oruke.tagloom.core.workspace.WorkspaceManifestService
import com.oruke.tagloom.filestore.jvm.JvmWorkspacePaths
import com.oruke.tagloom.index.sqlite.EncryptedSqliteSearchIndex
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 从当前 manifest 指向的权威 Note 快照重建本地加密 SQLite 索引。
 *
 * @param paths 已完成工作区根路径约束的 JVM 路径集合。
 * @param cryptoProvider 校验密文摘要的密码学端口。
 * @param manifestService 认证磁盘 manifest 的领域服务。
 * @param workspaceMasterKey 当前解锁会话主密钥；生命周期由桌面会话负责。
 * @param index 已打开且拥有独立 INDEX 密钥的派生索引。
 * @param noteEnvelopeService 认证 Note 快照信封的服务。
 */
internal class DesktopIndexProjection(
    private val paths: JvmWorkspacePaths,
    private val cryptoProvider: CryptoProvider,
    private val manifestService: WorkspaceManifestService,
    private val workspaceMasterKey: SecretKeyMaterial,
    val index: EncryptedSqliteSearchIndex,
    private val noteEnvelopeService: NoteSnapshotEnvelopeService,
) : DerivedStateUpdater {
    /**
     * 在事务确认后从磁盘权威 manifest 全量重建派生索引。
     *
     * @param transactionId 刚由 manifest 确认的事务 ID。
     */
    override suspend fun update(transactionId: TransactionId) {
        val manifest = readManifestPayload()
        require(transactionId in manifest.committedTransactionIds) { "派生更新事务尚未由 manifest 确认" }
        rebuild(manifest)
    }

    /**
     * 确认索引 checkpoint 与当前 manifest 一致，否则执行完整重建。
     */
    suspend fun ensureCurrent(): List<DesktopObjectIntegrityIssue> {
        val manifest = readManifestPayload()
        val expectedRevision = manifest.revision.toString()
        val report = loadSnapshotReport()
        if (index.readCheckpoint()?.manifestRevision != expectedRevision || report.issues.isNotEmpty()) {
            rebuild(manifest, report.snapshots)
        }
        return report.issues
    }

    /**
     * 读取当前 manifest 中全部已认证 Note 快照，包括 tombstone。
     *
     * @return 按对象 ID 稳定排序的快照。
     */
    suspend fun loadSnapshots(): List<ObjectSnapshot<Note>> = loadSnapshotReport().snapshots

    /**
     * 读取全部 Note，并把缺失、摘要错误或认证失败对象从结果中逻辑隔离。
     *
     * @return 有效快照和脱敏完整性问题。
     */
    private suspend fun loadSnapshotReport(): DesktopNoteSnapshotLoadResult = withContext(Dispatchers.IO) {
        val manifestBytes = Files.readAllBytes(paths.manifest)
        val manifestFile = WorkspaceManifestCodec.decode(manifestBytes)
        val manifest = manifestService.inspectWithMasterKey(manifestBytes, workspaceMasterKey)
        val snapshots = mutableListOf<ObjectSnapshot<Note>>()
        val issues = mutableListOf<DesktopObjectIntegrityIssue>()
        val records = manifest.objects
            .filter { record -> record.kind == ManifestObjectKind.NOTE }
            .sortedBy { record -> record.objectId }
        records.forEach { record ->
            val file = paths.target(record.path)
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                issues += record.toIssue(DesktopIntegrityFailure.MISSING_FILE)
                return@forEach
            }
            val encrypted = Files.readAllBytes(file)
            val actualSha256 = cryptoProvider.sha256(encrypted).toHex()
            if (actualSha256 != record.encryptedSha256) {
                issues += record.toIssue(DesktopIntegrityFailure.HASH_MISMATCH)
                return@forEach
            }
            val snapshot = runCatching {
                noteEnvelopeService.open(
                encryptedBytes = encrypted,
                workspaceId = manifestFile.publicHeader.workspaceId,
                objectId = ObjectId(record.objectId),
                revision = record.revision,
                keyId = record.keyId,
                workspaceMasterKey = workspaceMasterKey,
                )
            }.getOrElse {
                issues += record.toIssue(DesktopIntegrityFailure.AUTHENTICATION_FAILED)
                return@forEach
            }
            snapshots += snapshot
        }
        DesktopNoteSnapshotLoadResult(snapshots, issues)
    }

    /**
     * 关闭 SQLite 索引并销毁它拥有的 INDEX 密钥。
     */
    suspend fun close() {
        index.close()
    }

    /**
     * 把指定 manifest 的全部 Note 投影为一个原子索引 checkpoint。
     *
     * @param manifest 已认证 manifest 载荷。
     * @param snapshots 已扫描有效快照；为空时重新扫描。
     */
    private suspend fun rebuild(
        manifest: WorkspaceManifestPayload,
        snapshots: List<ObjectSnapshot<Note>>? = null,
    ) {
        val verifiedSnapshots = snapshots ?: loadSnapshots()
        val documents = verifiedSnapshots.map(NoteIndexDocumentFactory::fromSnapshot)
        index.rebuild(
            documents = documents,
            checkpoint = SearchIndexCheckpoint(
                manifestRevision = manifest.revision.toString(),
                noteCount = verifiedSnapshots.count { snapshot -> !snapshot.header.deleted },
            ),
            onProgress = {},
        )
    }

    /**
     * 把 manifest Note 记录转换为不含正文的完整性问题。
     *
     * @param failure 失败分类。
     * @return 可展示或记录的脱敏问题。
     */
    private fun com.oruke.tagloom.core.workspace.ManifestObjectRecord.toIssue(
        failure: DesktopIntegrityFailure,
    ): DesktopObjectIntegrityIssue = DesktopObjectIntegrityIssue(kind, objectId, failure)

    /**
     * 认证并读取当前磁盘 manifest 载荷。
     *
     * @return 当前唯一权威确认点。
     */
    private suspend fun readManifestPayload(): WorkspaceManifestPayload = withContext(Dispatchers.IO) {
        manifestService.inspectWithMasterKey(Files.readAllBytes(paths.manifest), workspaceMasterKey)
    }

    /**
     * 把摘要字节转换为小写十六进制文本。
     *
     * @return 64 位摘要文本。
     */
    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
    }
}

/**
 * 保存 Note 快照扫描得到的有效对象和逻辑隔离问题。
 */
private data class DesktopNoteSnapshotLoadResult(
    /** 可以参与读取和索引的快照。 */
    val snapshots: List<ObjectSnapshot<Note>>,
    /** 已跳过的损坏对象。 */
    val issues: List<DesktopObjectIntegrityIssue>,
)
