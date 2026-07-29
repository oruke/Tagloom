package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.crypto.CryptoProvider
import com.oruke.tagloom.core.crypto.EnvelopeCryptoService
import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.transaction.NoteSnapshotEnvelopeService
import com.oruke.tagloom.core.transaction.NoteSnapshotTransactionService
import com.oruke.tagloom.core.transaction.SavedQuerySnapshotEnvelopeService
import com.oruke.tagloom.core.transaction.SavedQuerySnapshotTransactionService
import com.oruke.tagloom.core.transaction.TransactionCoordinator
import com.oruke.tagloom.core.transaction.WorkspaceSchemaSnapshotEnvelopeService
import com.oruke.tagloom.core.transaction.WorkspaceSchemaSnapshotTransactionService
import com.oruke.tagloom.core.workspace.UnlockedWorkspaceManifest
import com.oruke.tagloom.core.workspace.WorkspaceBootstrapTransactionService
import com.oruke.tagloom.core.workspace.WorkspaceManifestService
import com.oruke.tagloom.filestore.jvm.JvmEncryptedWorkspaceBackupService
import com.oruke.tagloom.filestore.jvm.JvmTransactionStorage
import com.oruke.tagloom.filestore.jvm.JvmWorkspaceContentVerifier
import com.oruke.tagloom.filestore.jvm.JvmWorkspacePaths
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 组装桌面会话所需的文件事务、内容验证、加密索引和应用服务。
 */
object DesktopWorkspaceSessionFactory {
    /**
     * 创建尚未执行启动恢复的桌面工作区会话。
     *
     * @param root 已选择的工作区根目录。
     * @param cryptoProvider 真实密码学适配器。
     * @param manifestService manifest 服务。
     * @param unlocked 已认证解锁 manifest。
     * @param actorId 当前会话 Actor ID。
     * @param idFactory 不透明 ID 生成器。
     * @param currentTimeMillis HLC 物理时间源。
     * @param timestampProvider UTC 时间源。
     * @return 已组装但尚未初始化的桌面会话。
     */
    suspend fun open(
        root: Path,
        cryptoProvider: CryptoProvider,
        manifestService: WorkspaceManifestService,
        unlocked: UnlockedWorkspaceManifest,
        actorId: ActorId,
        idFactory: () -> String,
        currentTimeMillis: () -> Long,
        timestampProvider: () -> String,
    ): DesktopWorkspaceSession = withContext(Dispatchers.IO) {
        val paths = JvmWorkspacePaths(root)
        val envelopeService = EnvelopeCryptoService(cryptoProvider)
        val savedQueryEnvelopeService = SavedQuerySnapshotEnvelopeService(envelopeService)
        val workspaceSchemaEnvelopeService = WorkspaceSchemaSnapshotEnvelopeService(envelopeService)
        val index = DesktopIndexFactory.open(paths, cryptoProvider, unlocked)
        val projection = DesktopIndexProjection(
            paths = paths,
            cryptoProvider = cryptoProvider,
            manifestService = manifestService,
            workspaceMasterKey = unlocked.workspaceMasterKey,
            index = index,
            noteEnvelopeService = NoteSnapshotEnvelopeService(envelopeService),
        )
        val verifier = JvmWorkspaceContentVerifier(
            workspaceId = unlocked.file.publicHeader.workspaceId,
            activeKeyId = unlocked.file.publicHeader.activeKeyId,
            workspaceMasterKey = unlocked.workspaceMasterKey,
            envelopeCryptoService = envelopeService,
            workspaceManifestService = manifestService,
        )
        val coordinator = TransactionCoordinator(
            storage = JvmTransactionStorage(root, verifier),
            derivedStateUpdater = projection,
        )
        DesktopWorkspaceSession(
            paths = paths,
            manifestService = manifestService,
            unlocked = unlocked,
            projection = projection,
            transactionCoordinator = coordinator,
            noteTransactionService = NoteSnapshotTransactionService(
                cryptoProvider,
                envelopeService,
                manifestService,
                coordinator,
            ),
            bootstrapTransactionService = WorkspaceBootstrapTransactionService(
                cryptoProvider,
                envelopeService,
                manifestService,
                coordinator,
            ),
            backupService = JvmEncryptedWorkspaceBackupService(root, manifestService),
            savedQueryLoader = DesktopSavedQueryLoader(paths, cryptoProvider, savedQueryEnvelopeService),
            savedQueryTransactionService = SavedQuerySnapshotTransactionService(
                cryptoProvider,
                envelopeService,
                manifestService,
                coordinator,
            ),
            schemaStore = DesktopWorkspaceSchemaStore(
                paths = paths,
                cryptoProvider = cryptoProvider,
                envelopeService = workspaceSchemaEnvelopeService,
                transactionService = WorkspaceSchemaSnapshotTransactionService(
                    cryptoProvider,
                    envelopeService,
                    manifestService,
                    coordinator,
                ),
            ),
            integrityScanner = DesktopWorkspaceIntegrityScanner(paths, cryptoProvider),
            compactionService = DesktopChangeLogCompactionService(
                paths,
                cryptoProvider,
                envelopeService,
                manifestService,
                coordinator,
            ),
            actorId = actorId,
            idFactory = idFactory,
            currentTimeMillis = currentTimeMillis,
            timestampProvider = timestampProvider,
        )
    }
}
