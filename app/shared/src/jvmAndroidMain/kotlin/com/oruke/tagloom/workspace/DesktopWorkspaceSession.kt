package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.index.BacklinkIndexHit
import com.oruke.tagloom.core.index.NoteIndexDocument
import com.oruke.tagloom.core.index.PropertyIndexFilter
import com.oruke.tagloom.core.index.SearchIndexCheckpoint
import com.oruke.tagloom.core.index.SearchIndexHit
import com.oruke.tagloom.core.index.SearchIndexQuery
import com.oruke.tagloom.core.index.SearchIndexRebuildProgress
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
 *
 * @param paths 工作区受控路径。
 * @param fileSystem 当前工作区绑定的 JVM 文件能力。
 * @param writeLease 当前会话持有的跨进程单写入者租约。
 * @param manifestService manifest 认证与更新服务。
 * @param unlocked 初始已解锁 manifest；会话取得主密钥所有权。
 * @param projection 权威快照到加密索引的投影器。
 * @param transactionCoordinator 工作区唯一写入协调器。
 * @param noteTransactionService Note 统一事务入口。
 * @param bootstrapTransactionService 默认工作区对象初始化事务入口。
 * @param backupService 只读取当前确认点的密文备份服务。
 * @param savedQueryLoader 从权威密文加载保存查询。
 * @param savedQueryTransactionService 保存查询统一事务入口。
 * @param schemaStore Tag 与 PropertyDefinition 权威密文读写入口。
 * @param databaseStore Database 与 View 权威密文读写入口。
 * @param integrityScanner 扫描全部 manifest 密文对象。
 * @param compactionService 验证并提交 Change checkpoint 的桌面维护服务。
 * @param actorId 当前桌面会话随机 Actor ID。
 * @param idFactory 生成对象与事务的不透明 ID。
 * @param currentTimeMillis 提供 HLC 物理时间。
 * @param timestampProvider 提供 UTC ISO-8601 时间。
 */
class DesktopWorkspaceSession internal constructor(
    private val paths: JvmWorkspacePaths,
    internal val fileSystem: JvmWorkspaceFileSystem,
    private val writeLease: WorkspaceWriteLease,
    private val manifestService: WorkspaceManifestService,
    internal var unlocked: UnlockedWorkspaceManifest,
    private val projection: DesktopIndexProjection,
    private val transactionCoordinator: TransactionCoordinator,
    private val noteTransactionService: NoteSnapshotTransactionService,
    private val bootstrapTransactionService: WorkspaceBootstrapTransactionService,
    private val backupService: JvmEncryptedWorkspaceBackupService,
    private val savedQueryLoader: DesktopSavedQueryLoader,
    private val savedQueryTransactionService: SavedQuerySnapshotTransactionService,
    private val schemaStore: DesktopWorkspaceSchemaStore,
    internal val databaseStore: DesktopWorkspaceDatabaseStore,
    private val integrityScanner: DesktopWorkspaceIntegrityScanner,
    private val compactionService: DesktopChangeLogCompactionService,
    internal val actorId: ActorId,
    internal val idFactory: () -> String,
    internal val currentTimeMillis: () -> Long,
    internal val timestampProvider: () -> String,
) {
    /** 串行化会话状态刷新、锁定和业务调用。 */
    internal val sessionMutex: Mutex = Mutex()

    /** 当前 Actor 下一个对象 revision 序号。 */
    internal var nextActorSequence: Long = 1L

    /** 锁定后立即拒绝全部读取和写入。 */
    private var closed: Boolean = false

    /** 恢复无法证明完整状态时进入只读模式。 */
    private var readOnly: Boolean = false

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
                hybridLogicalTime = HybridLogicalTime(currentTimeMillis(), 0, actorId),
                committedAt = timestampProvider(),
            )
            nextActorSequence += bootstrap.consumedActorSequences
            refreshManifest(bootstrap.encryptedManifest)
        }
        integrityIssues = (integrityScanner.inspect(unlocked) + projection.ensureCurrent() +
            savedQueryLoader.inspect(unlocked).issues + schemaStore.inspect(unlocked).issues +
            databaseStore.inspect(unlocked).issues).distinct()
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
        requireWritable()
        val targetId = objectId ?: ObjectId(idFactory())
        val previous = projection.loadSnapshots().singleOrNull { snapshot -> snapshot.header.objectId == targetId }
        if (expectedRevision != null) {
            require(previous?.header?.revision == expectedRevision) { "Note revision 已落后，拒绝覆盖更新" }
        }
        val noteWithDefaultTag = if (previous == null && WorkspaceDefaults.DEFAULT_NOTE_TAG_ID !in note.directTagIds) {
            note.copy(directTagIds = note.directTagIds + WorkspaceDefaults.DEFAULT_NOTE_TAG_ID)
        } else {
            note
        }
        val result = try {
            noteTransactionService.commit(
                unlocked = unlocked,
                request = NoteSnapshotTransactionRequest(
                    objectId = targetId,
                    note = noteWithDefaultTag,
                    previousSnapshot = previous,
                    actorId = actorId,
                    actorSequence = nextActorSequence++,
                    transactionId = TransactionId(idFactory()),
                    hybridLogicalTime = HybridLogicalTime(currentTimeMillis(), 0, actorId),
                    committedAt = timestampProvider(),
                ),
            )
        } catch (error: Throwable) {
            // manifest 可能已确认而派生更新或清理失败；重新读取确认点，避免会话继续使用旧 revision。
            runCatching { refreshManifest() }
            throw error
        }
        refreshManifest(result.encryptedManifest)
        DesktopStoredNote(result.snapshot)
    }

    /**
     * 把一篇已确认 Note 写为 tombstone，并从派生索引移除。
     *
     * @param objectId 待删除 Note ID。
     */
    suspend fun deleteNote(objectId: ObjectId) = sessionMutex.withLock {
        requireWritable()
        val previous = projection.loadSnapshots().singleOrNull { snapshot -> snapshot.header.objectId == objectId }
            ?: throw IllegalArgumentException("待删除 Note 不存在")
        val result = noteTransactionService.commit(
            unlocked = unlocked,
            request = NoteSnapshotTransactionRequest(
                objectId = objectId,
                note = previous.payload,
                previousSnapshot = previous,
                actorId = actorId,
                actorSequence = nextActorSequence++,
                transactionId = TransactionId(idFactory()),
                hybridLogicalTime = HybridLogicalTime(currentTimeMillis(), 0, actorId),
                committedAt = timestampProvider(),
                deleted = true,
            ),
        )
        refreshManifest(result.encryptedManifest)
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
     * 替换一篇已确认 Note 的派生索引投影。
     *
     * 该入口只供 JVM 能力代理使用；权威快照仍必须先由事务服务确认，普通业务写入不会直接调用它。
     *
     * @param document 待写入索引的完整 Note 投影。
     */
    internal suspend fun replaceIndex(document: NoteIndexDocument) = sessionMutex.withLock {
        requireOpen()
        projection.index.replace(document)
    }

    /**
     * 从派生索引移除一篇 Note。
     *
     * @param noteId 待移除的权威 Note ID。
     */
    internal suspend fun removeIndex(noteId: ObjectId) = sessionMutex.withLock {
        requireOpen()
        projection.index.remove(noteId)
    }

    /**
     * 从已确认快照投影重建派生索引。
     *
     * @param documents 当前确认点的 Note 投影集合。
     * @param checkpoint 重建成功后记录的权威边界。
     * @param onProgress 每完成一篇文档后的进度回调。
     */
    internal suspend fun rebuildIndex(
        documents: List<NoteIndexDocument>,
        checkpoint: SearchIndexCheckpoint,
        onProgress: (SearchIndexRebuildProgress) -> Unit,
    ) = sessionMutex.withLock {
        requireOpen()
        projection.index.rebuild(documents, checkpoint, onProgress)
    }

    /**
     * 读取当前索引覆盖的权威 checkpoint。
     *
     * @return 索引尚未完整重建时为空。
     */
    internal suspend fun readIndexCheckpoint(): SearchIndexCheckpoint? = sessionMutex.withLock {
        requireOpen()
        projection.index.readCheckpoint()
    }

    /**
     * 清除派生索引数据但保留其数据库结构。
     */
    internal suspend fun clearIndex() = sessionMutex.withLock {
        requireOpen()
        projection.index.clear()
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
                databaseStore.inspect(unlocked).issues).distinct()
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
                    hybridLogicalTime = HybridLogicalTime(currentTimeMillis(), 0, actorId),
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
     * 创建或更新一个工作区标签定义，并确认其 Change 与加密快照。
     *
     * @param definition 待保存完整标签定义。
     * @return 新确认标签定义。
     */
    suspend fun saveTagDefinition(definition: TagDefinition): DesktopStoredTagDefinition = sessionMutex.withLock {
        requireWritable()
        val result = try {
            schemaStore.saveTag(unlocked, definition, nextSchemaWriteContext())
        } catch (error: Throwable) {
            runCatching { refreshManifest() }
            throw error
        }
        refreshManifest(result.encryptedManifest)
        DesktopStoredTagDefinition(result.snapshot)
    }

    /**
     * 创建或更新一个工作区属性定义，并确认其 Change 与加密快照。
     *
     * @param definition 待保存完整属性定义。
     * @return 新确认属性定义。
     */
    suspend fun savePropertyDefinition(definition: PropertyDefinition): DesktopStoredPropertyDefinition =
        sessionMutex.withLock {
            requireWritable()
            val result = try {
                schemaStore.saveProperty(unlocked, definition, nextSchemaWriteContext())
            } catch (error: Throwable) {
                runCatching { refreshManifest() }
                throw error
            }
            refreshManifest(result.encryptedManifest)
            DesktopStoredPropertyDefinition(result.snapshot)
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
            databaseStore.inspect(unlocked).issues).distinct()
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
            closed = true
            closeResources()?.let { cleanupFailure ->
                if (cleanupFailure !== error) {
                    error.addSuppressed(cleanupFailure)
                }
            }
            throw error
        }

        // 轮换尝试后旧索引密钥和旧主密钥都不得继续服务，Manager 会按权威 manifest 重新打开。
        closed = true
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
        closed = true
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
    private suspend fun openState(): DesktopWorkspaceOpenState {
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
     * 消耗下一个 Actor 序号并生成 schema 写入上下文。
     *
     * 序号在提交失败时也不会复用，避免重试产生相同 revision 身份。
     *
     * @return 当前写入唯一上下文。
     */
    private fun nextSchemaWriteContext(): WorkspaceSchemaWriteContext = WorkspaceSchemaWriteContext(
        actorId = actorId,
        actorSequence = nextActorSequence++,
        transactionId = TransactionId(idFactory()),
        hybridLogicalTime = HybridLogicalTime(currentTimeMillis(), 0, actorId),
        committedAt = timestampProvider(),
    )

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
        check(!closed) { "工作区已经锁定" }
    }

    /** 拒绝锁定或只读安全模式下的写入。 */
    internal fun requireWritable() {
        requireOpen()
        check(!readOnly) { "工作区处于只读恢复模式" }
    }

}
