package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.index.BacklinkIndexHit
import com.oruke.tagloom.core.index.PropertyIndexFilter
import com.oruke.tagloom.core.index.SearchIndexHit
import com.oruke.tagloom.core.index.SearchIndexQuery
import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.ObjectRevision
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.SavedQuery
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.core.model.TransactionId
import com.oruke.tagloom.core.sync.HybridLogicalTime
import com.oruke.tagloom.core.transaction.ChangeLogCompactionPolicy
import com.oruke.tagloom.core.transaction.ChangeLogCompactionResult
import com.oruke.tagloom.core.transaction.NoteSnapshotTransactionRequest
import com.oruke.tagloom.core.transaction.NoteSnapshotTransactionService
import com.oruke.tagloom.core.transaction.SavedQuerySnapshotTransactionRequest
import com.oruke.tagloom.core.transaction.SavedQuerySnapshotTransactionService
import com.oruke.tagloom.core.transaction.TransactionCoordinator
import com.oruke.tagloom.core.transaction.TransactionRecoveryReport
import com.oruke.tagloom.core.transaction.WorkspaceSchemaWriteContext
import com.oruke.tagloom.core.workspace.ManifestObjectKind
import com.oruke.tagloom.core.workspace.UnlockedWorkspaceManifest
import com.oruke.tagloom.core.workspace.WorkspaceBootstrapTransactionService
import com.oruke.tagloom.platform.localizedWorkspaceSchemaLabels
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import com.oruke.tagloom.core.workspace.WorkspaceManifestCodec
import com.oruke.tagloom.core.workspace.WorkspaceManifestService
import com.oruke.tagloom.filesystem.jvm.JvmWorkspaceFileSystem
import com.oruke.tagloom.filesystem.jvm.JvmEncryptedWorkspaceBackupService
import com.oruke.tagloom.filesystem.jvm.JvmWorkspacePaths
import com.oruke.tagloom.filesystem.WorkspaceWriteLease
import com.oruke.tagloom.filesystem.jvm.WorkspaceBackupReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * 持有一个桌面端已解锁工作区的主密钥、事务协调器和加密索引生命周期。
 */
class DesktopWorkspaceSession internal constructor(
    /** 工作区受控路径，不允许外部调用直接访问。 */
    internal val paths: JvmWorkspacePaths,
    /** 当前工作区绑定的 JVM 文件能力。 */
    internal val fileSystem: JvmWorkspaceFileSystem,
    /** 当前会话持有的跨进程单写入者租约。 */
    private val writeLease: WorkspaceWriteLease,
    /** manifest 认证与更新服务。 */
    internal val manifestService: WorkspaceManifestService,
    /** 当前已解锁 manifest；会话取得主密钥所有权。 */
    internal var unlocked: UnlockedWorkspaceManifest,
    /** 权威快照到加密索引的投影器。 */
    internal val projection: DesktopIndexProjection,
    /** 工作区唯一文件写入协调器。 */
    internal val transactionCoordinator: TransactionCoordinator,
    /** 桌面与外部调用复用的 Note 统一事务入口。 */
    internal val noteTransactionService: NoteSnapshotTransactionService,
    /** 外部读取和失败调用的加密审计事务入口。 */
    internal val agentAuditService: com.oruke.tagloom.core.transaction.AgentAuditTransactionService,
    /** 默认工作区对象初始化事务入口。 */
    private val bootstrapTransactionService: WorkspaceBootstrapTransactionService,
    /** 只读取当前确认点的密文备份服务。 */
    private val backupService: JvmEncryptedWorkspaceBackupService,
    /** 从权威密文加载保存查询。 */
    internal val savedQueryLoader: DesktopSavedQueryLoader,
    /** 保存查询统一事务入口。 */
    private val savedQueryTransactionService: SavedQuerySnapshotTransactionService,
    /** Tag 与 PropertyDefinition 权威密文读写入口。 */
    internal val schemaStore: DesktopWorkspaceSchemaStore,
    /** Database 与 View 权威密文读写入口。 */
    internal val databaseStore: DesktopWorkspaceDatabaseStore,
    /** 工作区级 Agent 配置权威密文读写入口。 */
    internal val configStore: DesktopWorkspaceConfigStore,
    /** 扫描全部 manifest 密文对象的完整性服务。 */
    private val integrityScanner: DesktopWorkspaceIntegrityScanner,
    /** 验证并提交 Change checkpoint 的桌面维护服务。 */
    private val compactionService: DesktopChangeLogCompactionService,
    /** 当前解锁会话随机 Actor ID，同时区分外部授权生命周期。 */
    internal val actorId: ActorId,
    /** 生成对象与事务的不透明 ID。 */
    internal val idFactory: () -> String,
    /** 提供 HLC 物理时间。 */
    internal val currentTimeMillis: () -> Long,
    /** 提供 UTC ISO-8601 时间。 */
    internal val timestampProvider: () -> String,
) {
    /** 串行化会话状态刷新、锁定和业务调用。 */
    internal val sessionMutex: Mutex = Mutex()

    /** 当前 Actor 下一个对象 revision 序号。 */
    internal var nextActorSequence: Long = 1L

    /** 观察远端父节点后继续单调递增的业务逻辑时钟，只在会话锁内推进。 */
    internal val hybridClock = WorkspaceHybridClock(actorId, currentTimeMillis)

    /** 锁定后立即拒绝全部读取和写入。 */
    @Volatile
    internal var closed: Boolean = false

    /** 已连接服务器的工作区保留共同祖先，设备确认协议完成前禁止压缩 Change。 */
    internal var syncHistoryRequired: Boolean = false

    /** 锁定前同步清理外部明文缓存的生命周期订阅；回调不得读写工作区。 */
    internal val externalCloseListeners = mutableListOf<() -> Unit>()

    /** 恢复无法证明完整状态时进入只读模式。 */
    internal var readOnly: Boolean = false

    /** 最近一次启动恢复报告。 */
    private var recoveryReport: TransactionRecoveryReport = TransactionRecoveryReport(emptyList(), false)

    /** 当前会话已逻辑隔离的对象完整性问题。 */
    private var integrityIssues: List<DesktopObjectIntegrityIssue> = emptyList()

    /** 当前工作区随机 ID。 */
    val workspaceId
        get() = unlocked.file.publicHeader.workspaceId

    /**
     * 完成 prepared 事务恢复并确保索引覆盖当前 manifest。
     *
     * @return 可直接展示的 Note、恢复报告和只读状态。
     */
    suspend fun initialize(): DesktopWorkspaceOpenState = sessionMutex.withLock {
        requireOpen()
        recoveryReport = transactionCoordinator.recoverOnStartup()
        readOnly = recoveryReport.requiresReadOnlyMode
        refreshManifest()
        val defaultTagMissing = unlocked.payload.objects.none { record ->
            record.kind == ManifestObjectKind.TAG && record.objectId == WorkspaceDefaults.DEFAULT_NOTE_TAG_ID.value
        }
        // 空工作区通过同一原子事务补齐默认 Tag 和属性 schema；只读恢复模式绝不产生新写入。
        if (!readOnly && defaultTagMissing) {
            val bootstrap = bootstrapTransactionService.createDefaultNoteTag(
                unlocked = unlocked,
                actorId = actorId,
                actorSequence = nextActorSequence,
                transactionId = TransactionId(idFactory()),
                hybridLogicalTime = hybridClock.next(),
                committedAt = timestampProvider(),
                labels = localizedWorkspaceSchemaLabels(),
            )
            nextActorSequence += bootstrap.consumedActorSequences
            refreshManifest(bootstrap.encryptedManifest)
        }
        integrityIssues = (integrityScanner.inspect(unlocked) + projection.ensureCurrent() +
            savedQueryLoader.inspect(unlocked).issues + schemaStore.inspect(unlocked).issues +
            databaseStore.inspect(unlocked).issues + configStore.load(unlocked).issues).distinct()
        if (!readOnly && integrityIssues.isEmpty()) {
            DesktopReplicaCodec(this).observeClock()
            DesktopNoteTagSchemaMigration.migrate(schemaStore, unlocked, ::nextSchemaWriteContext)?.let { manifest ->
                refreshManifest(manifest)
            }
        }
        openState()
    }

    /**
     * 创建或更新一篇 Note，并同时确认 Change、快照、manifest 与派生索引。
     *
     * @param objectId 更新目标；为空时生成新 Note ID。
     * @param note 待规范化与保存的完整 Note。
     * @param expectedRevision 调用方读取时的 revision；新建时为空。
     * @return 新确认的 Note。
     */
    suspend fun saveNote(
        objectId: ObjectId?,
        note: Note,
        expectedRevision: ObjectRevision? = null,
    ): DesktopStoredNote = sessionMutex.withLock {
        saveNoteLocked(objectId, note, expectedRevision)
    }

    /**
     * 把一篇已确认 Note 写为 tombstone，并从派生索引移除。
     *
     * @param objectId 待删除 Note ID。
     */
    suspend fun deleteNote(objectId: ObjectId) = sessionMutex.withLock {
        deleteNoteLocked(objectId)
    }

    /**
     * 查询已确认快照投影出的全文、标签和属性索引。
     *
     * @param query 已校验索引查询。
     * @return 稳定排序的命中。
     */
    suspend fun search(query: SearchIndexQuery): List<SearchIndexHit> = sessionMutex.withLock {
        requireOpen()
        projection.index.search(query)
    }

    /**
     * 查询指向目标 Note 或 Block 的反向链接。
     *
     * @param noteId 目标 Note。
     * @param blockId 可选目标 Block。
     * @return 稳定排序的反向链接。
     */
    suspend fun backlinks(
        noteId: ObjectId,
        blockId: BlockId? = null,
    ): List<BacklinkIndexHit> = sessionMutex.withLock {
        requireOpen()
        projection.index.backlinks(noteId, blockId)
    }

    /**
     * 在会话互斥锁内认证当前 manifest 并创建只含权威密文的备份。
     *
     * @param destination 工作区之外的备份文件。
     * @return 已发布备份的密文统计。
     */
    suspend fun createBackup(destination: Path): WorkspaceBackupReport = sessionMutex.withLock {
        requireOpen()
        withContext(Dispatchers.IO) { backupService.createBackup(unlocked, destination) }
    }

    /**
     * 验证保留证据后创建加密 checkpoint，并压缩当前 manifest 中可删除的 Change。
     *
     * @param policy 撤销保留、归档和设备确认策略。
     * @return 已确认 checkpoint、manifest 与已清理路径。
     */
    suspend fun compactChangeLog(policy: ChangeLogCompactionPolicy): ChangeLogCompactionResult =
        sessionMutex.withLock {
            requireWritable()
            check(!syncHistoryRequired) { "sync_history_required" }
            val result = try {
                compactionService.compact(
                    unlocked = unlocked,
                    checkpointId = idFactory(),
                    transactionId = TransactionId(idFactory()),
                    createdAt = timestampProvider(),
                    policy = policy,
                )
            } catch (error: Throwable) {
                // manifest 可能已确认而无引用密文清理失败；刷新确认点后再向上报告。
                runCatching { refreshManifest() }
                throw error
            }
            refreshManifest(result.encryptedManifest)
            integrityIssues = (integrityScanner.inspect(unlocked) + projection.ensureCurrent() +
                savedQueryLoader.inspect(unlocked).issues + schemaStore.inspect(unlocked).issues +
                databaseStore.inspect(unlocked).issues + configStore.load(unlocked).issues).distinct()
            result
        }

    /**
     * 创建一个工作区级保存查询，并通过统一事务确认快照和 Change。
     *
     * @param query 待保存查询条件。
     * @return 新确认保存查询。
     */
    suspend fun saveQuery(query: SavedQuery): DesktopStoredSavedQuery = sessionMutex.withLock {
        requireWritable()
        val objectId = ObjectId(idFactory())
        val result = try {
            savedQueryTransactionService.commit(
                unlocked = unlocked,
                request = SavedQuerySnapshotTransactionRequest(
                    objectId = objectId,
                    query = query,
                    actorId = actorId,
                    actorSequence = nextActorSequence++,
                    transactionId = TransactionId(idFactory()),
                    hybridLogicalTime = hybridClock.next(),
                    committedAt = timestampProvider(),
                ),
            )
        } catch (error: Throwable) {
            runCatching { refreshManifest() }
            throw error
        }
        refreshManifest(result.encryptedManifest)
        DesktopStoredSavedQuery(result.snapshot)
    }

    /**
     * 执行一个已确认保存查询，不接受 UI 伪造或未保存条件。
     *
     * @param objectId 保存查询对象 ID。
     * @return 加密派生索引命中。
     */
    suspend fun runSavedQuery(objectId: ObjectId): List<SearchIndexHit> = sessionMutex.withLock {
        requireOpen()
        val saved = savedQueryLoader.load(unlocked).singleOrNull { snapshot -> snapshot.header.objectId == objectId }
            ?: throw IllegalArgumentException("保存查询不存在")
        projection.index.search(
            SearchIndexQuery(
                text = saved.payload.text,
                tagIds = saved.payload.tagIds.toSet(),
                propertyFilters = saved.payload.propertyFilters.map { filter ->
                    PropertyIndexFilter(filter.propertyId, filter.canonicalValue)
                },
            ),
        )
    }

    /**
     * 在密码槽原子替换后重新认证磁盘 manifest，并刷新派生索引 checkpoint。
     *
     * @return 凭据变更后的当前打开状态。
     */
    suspend fun refreshAfterCredentialChange(): DesktopWorkspaceOpenState = sessionMutex.withLock {
        requireOpen()
        refreshManifest()
        integrityIssues = (integrityScanner.inspect(unlocked) + projection.ensureCurrent() +
            savedQueryLoader.inspect(unlocked).issues + schemaStore.inspect(unlocked).issues +
            databaseStore.inspect(unlocked).issues + configStore.load(unlocked).issues).distinct()
        openState()
    }

    /**
     * 在会话互斥锁内轮换全部权威信封，并无论成功或失败都关闭旧密钥会话。
     *
     * @param service 执行新信封与新 manifest 原子事务的服务。
     * @param request 新密钥身份、密码槽和事务参数。
     * @return 已确认新密钥会话与 Recovery Key。
     */
    suspend fun rotateMasterKey(
        service: DesktopWorkspaceKeyRotationService,
        request: DesktopMasterKeyRotationRequest,
    ): DesktopWorkspaceKeyRotationResult = sessionMutex.withLock {
        requireWritable()
        val rotation = try {
            service.rotate(
                unlocked = unlocked,
                newKeyId = request.newKeyId,
                newPassword = request.newPassword,
                newParameters = request.newParameters,
                passwordSlotId = request.passwordSlotId,
                recoverySlotId = request.recoverySlotId,
                transactionId = request.transactionId,
            )
        } catch (error: Throwable) {
            // 轮换失败后旧会话也必须关闭；清理失败只能作为附加证据，不能覆盖原始业务异常。
            invalidateExternalAccess()
            closeResources()?.let { cleanupFailure ->
                if (cleanupFailure !== error) {
                    error.addSuppressed(cleanupFailure)
                }
            }
            throw error
        }

        // 轮换尝试后旧索引密钥和旧主密钥都不得继续服务，Manager 会按权威 manifest 重新打开。
        invalidateExternalAccess()
        val cleanupFailure = closeResources()
        if (cleanupFailure != null) {
            // 新结果尚未交给 Manager，不能在旧会话关闭失败时遗留新主密钥和 Recovery Key。
            rotation.destroy()
            throw cleanupFailure
        }
        rotation
    }

    /**
     * 关闭索引并主动销毁 Workspace Master Key，随后所有入口立即失效。
     */
    suspend fun lock() = sessionMutex.withLock {
        if (closed) {
            return@withLock
        }
        invalidateExternalAccess()
        closeResources()?.let { throw it }
    }

    /**
     * 尽力关闭索引、销毁主密钥并释放跨进程写租约。
     *
     * 三项清理必须全部尝试，避免索引异常导致密钥或写租约残留；后续清理异常以 suppressed
     * 形式附加到第一项异常，保证调用方仍能看到最初失败原因。
     *
     * @return 没有清理失败时为空，否则返回包含全部清理异常的首个异常。
     */
    private suspend fun closeResources(): Throwable? {
        var failure: Throwable? = null
        try {
            projection.close()
        } catch (error: Throwable) {
            failure = mergeCleanupFailure(failure, error)
        }
        try {
            unlocked.destroy()
        } catch (error: Throwable) {
            failure = mergeCleanupFailure(failure, error)
        }
        try {
            writeLease.release()
        } catch (error: Throwable) {
            failure = mergeCleanupFailure(failure, error)
        }
        return failure
    }

    /**
     * 合并清理阶段异常并保留最早的失败原因。
     *
     * @param current 当前已经记录的清理异常。
     * @param next 新发生的清理异常。
     * @return 作为主异常保留的异常对象。
     */
    private fun mergeCleanupFailure(
        current: Throwable?,
        next: Throwable,
    ): Throwable {
        if (current == null) {
            return next
        }
        if (current !== next) {
            current.addSuppressed(next)
        }
        return current
    }

    /**
     * 读取当前会话状态快照。
     *
     * @return 活动 Note、恢复报告和只读状态。
     */
    internal suspend fun openState(): DesktopWorkspaceOpenState {
        val schemas = schemaStore.load(unlocked)
        val databases = databaseStore.load(unlocked)
        return DesktopWorkspaceOpenState(
            notes = loadActiveNotes(),
            savedQueries = savedQueryLoader.load(unlocked).map(::DesktopStoredSavedQuery),
            tagDefinitions = schemas.tags.map(::DesktopStoredTagDefinition),
            propertyDefinitions = schemas.properties.map(::DesktopStoredPropertyDefinition),
            databaseDefinitions = databases.databases.map(::DesktopStoredDatabaseDefinition),
            viewDefinitions = databases.views.map(::DesktopStoredViewDefinition),
            integrityIssues = integrityIssues,
            recoveryReport = recoveryReport,
            readOnly = readOnly,
        )
    }

    /**
     * 读取并过滤 tombstone Note。
     *
     * @return 当前活动 Note。
     */
    internal suspend fun loadActiveNotes(): List<DesktopStoredNote> = projection.loadSnapshots()
        .filterNot { snapshot -> snapshot.header.deleted }
        .map(::DesktopStoredNote)

    /**
     * 从磁盘读取已确认 manifest 并更新会话 revision。
     *
     * @param encryptedBytes 已知的新 manifest；为空时从磁盘读取。
     */
    internal suspend fun refreshManifest(encryptedBytes: ByteArray? = null) {
        val bytes = encryptedBytes ?: withContext(Dispatchers.IO) { Files.readAllBytes(paths.manifest) }
        val payload = manifestService.inspectWithMasterKey(bytes, unlocked.workspaceMasterKey)
        unlocked = UnlockedWorkspaceManifest(
            file = WorkspaceManifestCodec.decode(bytes),
            payload = payload,
            workspaceMasterKey = unlocked.workspaceMasterKey,
        )
    }

    /** 拒绝锁定后的全部操作。 */
    internal fun requireOpen() {
        if (closed) throw com.oruke.tagloom.core.external.ExternalCallException("workspace_locked")
    }

    /** 拒绝锁定或只读安全模式下的写入。 */
    internal fun requireWritable() {
        requireOpen()
        if (readOnly) throw com.oruke.tagloom.core.external.ExternalCallException("workspace_read_only")
    }

}
