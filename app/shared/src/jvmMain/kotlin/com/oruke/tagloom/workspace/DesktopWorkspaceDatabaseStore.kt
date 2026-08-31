package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.crypto.CryptoProvider
import com.oruke.tagloom.core.model.DatabaseDefinition
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.ObjectSnapshot
import com.oruke.tagloom.core.model.ViewDefinition
import com.oruke.tagloom.core.transaction.DatabaseCatalogCreateRequest
import com.oruke.tagloom.core.transaction.DatabaseCatalogSnapshotEnvelopeService
import com.oruke.tagloom.core.transaction.DatabaseCatalogSnapshotTransactionResult
import com.oruke.tagloom.core.transaction.DatabaseCatalogSnapshotTransactionService
import com.oruke.tagloom.core.transaction.DatabaseDefinitionSnapshotTransactionRequest
import com.oruke.tagloom.core.transaction.ViewDefinitionSnapshotTransactionRequest
import com.oruke.tagloom.core.transaction.WorkspaceDatabaseWriteContext
import com.oruke.tagloom.core.workspace.ManifestObjectKind
import com.oruke.tagloom.core.workspace.ManifestObjectRecord
import com.oruke.tagloom.core.workspace.UnlockedWorkspaceManifest
import com.oruke.tagloom.filesystem.jvm.JvmWorkspacePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * 从权威密文加载、认证并提交工作区 Database/View 目录对象。
 *
 * @param paths 当前工作区受控路径。
 * @param cryptoProvider 计算密文摘要的密码学端口。
 * @param envelopeService 解密并认证目录快照。
 * @param transactionService Database/View 统一事务入口。
 */
class DesktopWorkspaceDatabaseStore(
    private val paths: JvmWorkspacePaths,
    private val cryptoProvider: CryptoProvider,
    private val envelopeService: DatabaseCatalogSnapshotEnvelopeService,
    private val transactionService: DatabaseCatalogSnapshotTransactionService,
) {
    /**
     * 加载全部活动 Database/View 并稳定排序。
     *
     * @param unlocked 当前已认证 manifest。
     * @return 有效快照和逻辑隔离问题。
     */
    suspend fun load(unlocked: UnlockedWorkspaceManifest): DesktopWorkspaceDatabaseLoadResult = inspect(unlocked)

    /**
     * 扫描 Database/View 密文并隔离缺失、摘要错误或认证失败对象。
     *
     * @param unlocked 当前已认证 manifest。
     * @return 认证成功的目录快照与脱敏问题。
     */
    suspend fun inspect(unlocked: UnlockedWorkspaceManifest): DesktopWorkspaceDatabaseLoadResult =
        withContext(Dispatchers.IO) {
            val databases = loadRecords(
                unlocked,
                ManifestObjectKind.DATABASE,
                envelopeService::decryptDatabase,
            )
            val views = loadRecords(
                unlocked,
                ManifestObjectKind.VIEW,
                envelopeService::decryptView,
            )
            DesktopWorkspaceDatabaseLoadResult(
                databases = databases.snapshots
                    .filterNot { snapshot -> snapshot.header.deleted }
                    .sortedWith(compareBy({ snapshot -> snapshot.payload.name }, { snapshot -> snapshot.header.objectId.value })),
                views = views.snapshots
                    .filterNot { snapshot -> snapshot.header.deleted }
                    .sortedWith(compareBy({ snapshot -> snapshot.payload.databaseId.value }, { snapshot -> snapshot.payload.orderKey })),
                issues = databases.issues + views.issues,
            )
        }

    /**
     * 原子创建 Database 与首个 View。
     *
     * @param unlocked 当前已认证 manifest。
     * @param database Database 完整定义。
     * @param initialView 首个 View。
     * @param context 写入事务身份。
     * @return 单事务确认结果。
     */
    suspend fun create(
        unlocked: UnlockedWorkspaceManifest,
        database: DatabaseDefinition,
        initialView: ViewDefinition,
        context: WorkspaceDatabaseWriteContext,
    ): DatabaseCatalogSnapshotTransactionResult = transactionService.createCatalog(
        unlocked,
        DatabaseCatalogCreateRequest(database, initialView, context),
    )

    /**
     * 更新一个已确认 DatabaseDefinition。
     *
     * @param unlocked 当前已认证 manifest。
     * @param database 新完整定义。
     * @param context 写入事务身份。
     * @return 单事务确认结果。
     */
    suspend fun saveDatabase(
        unlocked: UnlockedWorkspaceManifest,
        database: DatabaseDefinition,
        context: WorkspaceDatabaseWriteContext,
    ): DatabaseCatalogSnapshotTransactionResult {
        val current = inspect(unlocked)
        val previous = current.databases.singleOrNull { snapshot -> snapshot.payload.id == database.id }
            ?: throw IllegalArgumentException("待更新 Database 不存在")
        return transactionService.commitDatabase(
            unlocked,
            DatabaseDefinitionSnapshotTransactionRequest(
                database = database,
                previousSnapshot = previous,
                views = current.views.filter { snapshot -> snapshot.payload.databaseId == database.id }.map { it.payload },
                context = context,
            ),
        )
    }

    /**
     * 为已存在 Database 创建一个新的独立 ViewDefinition。
     *
     * @param unlocked 当前已认证 manifest。
     * @param view 新视图完整定义。
     * @param context 写入事务身份。
     * @return 单对象确认结果。
     */
    suspend fun createView(
        unlocked: UnlockedWorkspaceManifest,
        view: ViewDefinition,
        context: WorkspaceDatabaseWriteContext,
    ): DatabaseCatalogSnapshotTransactionResult {
        val current = inspect(unlocked)
        require(current.databases.any { snapshot -> snapshot.payload.id == view.databaseId }) { "View 所属 Database 不存在" }
        require(current.views.none { snapshot -> snapshot.payload.id == view.id }) { "View 已经存在" }
        return transactionService.commitView(
            unlocked,
            ViewDefinitionSnapshotTransactionRequest(view = view, context = context),
        )
    }

    /**
     * 更新一个已确认 ViewDefinition。
     *
     * @param unlocked 当前已认证 manifest。
     * @param view 新完整定义。
     * @param context 写入事务身份。
     * @return 单事务确认结果。
     */
    suspend fun saveView(
        unlocked: UnlockedWorkspaceManifest,
        view: ViewDefinition,
        context: WorkspaceDatabaseWriteContext,
    ): DatabaseCatalogSnapshotTransactionResult {
        val current = inspect(unlocked)
        require(current.databases.any { snapshot -> snapshot.payload.id == view.databaseId }) { "View 所属 Database 不存在" }
        val previous = current.views.singleOrNull { snapshot -> snapshot.payload.id == view.id }
            ?: throw IllegalArgumentException("待更新 View 不存在")
        return transactionService.commitView(
            unlocked,
            ViewDefinitionSnapshotTransactionRequest(view, previous, context),
        )
    }

    /**
     * 加载并认证一种目录记录。
     *
     * @param T payload 类型。
     * @param unlocked 当前 manifest。
     * @param kind manifest 类别。
     * @param decrypt 绑定对象类型的解密函数。
     * @return 有效快照与隔离问题。
     */
    private suspend fun <T> loadRecords(
        unlocked: UnlockedWorkspaceManifest,
        kind: ManifestObjectKind,
        decrypt: suspend (ByteArray, com.oruke.tagloom.core.model.WorkspaceId, ObjectId, String, com.oruke.tagloom.core.model.KeyId, com.oruke.tagloom.core.crypto.SecretKeyMaterial) -> ObjectSnapshot<T>,
    ): DesktopDatabaseRecordLoadResult<T> {
        val issues = mutableListOf<DesktopObjectIntegrityIssue>()
        val snapshots = unlocked.payload.objects.filter { record -> record.kind == kind }.mapNotNull { record ->
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
                decrypt(
                    encrypted,
                    unlocked.file.publicHeader.workspaceId,
                    ObjectId(record.objectId),
                    record.revision,
                    record.keyId,
                    unlocked.workspaceMasterKey,
                )
            }.getOrElse {
                issues += record.toIssue(DesktopIntegrityFailure.AUTHENTICATION_FAILED)
                null
            }
        }
        return DesktopDatabaseRecordLoadResult(snapshots, issues)
    }

    /**
     * 转换为不含名称或查询内容的完整性问题。
     *
     * @param failure 失败分类。
     * @return 脱敏问题。
     */
    private fun ManifestObjectRecord.toIssue(failure: DesktopIntegrityFailure): DesktopObjectIntegrityIssue =
        DesktopObjectIntegrityIssue(kind, objectId, failure)

    /**
     * 编码小写十六进制摘要。
     *
     * @return 小写十六进制文本。
     */
    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
    }
}

/** 保存一次 Database/View 扫描结果。 */
data class DesktopWorkspaceDatabaseLoadResult(
    /** 活动 Database 快照。 */ val databases: List<ObjectSnapshot<DatabaseDefinition>>,
    /** 活动 View 快照。 */ val views: List<ObjectSnapshot<ViewDefinition>>,
    /** 被隔离对象。 */ val issues: List<DesktopObjectIntegrityIssue>,
)
/** 保存一种目录对象的读取结果。 */
private data class DesktopDatabaseRecordLoadResult<T>(
    /** 认证成功快照。 */ val snapshots: List<ObjectSnapshot<T>>,
    /** 被隔离对象。 */ val issues: List<DesktopObjectIntegrityIssue>,
)
