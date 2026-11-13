package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.index.SearchIndexQuery
import com.oruke.tagloom.core.model.DatabaseDefinition
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.ObjectRevision
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.SavedQuery
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.core.model.ViewDefinition
import com.oruke.tagloom.platform.WorkspaceDatabaseCatalog
import com.oruke.tagloom.platform.WorkspaceDatabaseDefinition
import com.oruke.tagloom.platform.WorkspaceGateway
import com.oruke.tagloom.platform.WorkspaceMasterKeyRotationResult
import com.oruke.tagloom.platform.WorkspaceNote
import com.oruke.tagloom.platform.WorkspaceOpenResult
import com.oruke.tagloom.platform.WorkspacePreparedCreation
import com.oruke.tagloom.platform.WorkspacePropertyDefinition
import com.oruke.tagloom.platform.WorkspaceRecentWorkspace
import com.oruke.tagloom.platform.WorkspaceSavedQuery
import com.oruke.tagloom.platform.WorkspaceSearchHit
import com.oruke.tagloom.platform.WorkspaceTagDefinition
import com.oruke.tagloom.platform.WorkspaceViewDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID
import java.nio.file.Path
import com.oruke.tagloom.platform.WorkspaceServerRecoveryGateway
import com.oruke.tagloom.sync.http.jvm.ServerRecoveryClient

/**
 * 通过注入的文档入口编排本地工作区；JVM 与 Android 共用相同业务流程。
 */
class DesktopWorkspaceGateway(
    /** 持有创建、解锁和活动会话生命周期的 Manager。 */
    private val manager: DesktopWorkspaceManager = DesktopWorkspaceManager(),
    /** 协调本机访问材料与最近工作区登记的生命周期。 */
    private val localAccessService: DesktopWorkspaceLocalAccessService = DesktopWorkspaceLocalAccessService(),
    /** 宿主负责选择目录或传输密文备份，共享业务不区分操作系统。 */
    private val documents: WorkspaceDocumentAccess = platformWorkspaceDocuments(),
    /** 服务器连接材料的本机私有目录，测试应注入独立临时位置。 */
    private val recoveryAccessDirectory: Path = DesktopWorkspaceAccessPaths.defaultRegistryFile().parent.resolve("server-recovery"),
    /** 每次操作独立创建 HTTPS 客户端，允许隔离测试注入回环传输。 */
    private val recoveryClientFactory: (String) -> ServerRecoveryClient = { ServerRecoveryClient(it) },
    /** 流式模型推理与连接探测端口，允许隔离测试注入假实现。 */
    private val agentStreamPort: com.oruke.tagloom.core.ai.AiStreamPort = com.oruke.tagloom.sync.ai.JvmKtorAiStreamPortFactory.create(),
) : WorkspaceGateway {
    /** 外部写入只通知 UI 重新读取确认点，不通过事件携带明文。 */
    private val externalChangeNotifications = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** 当前会话复用同一 Agent 服务，防止每次调用重复登记关闭监听。 */
    private var externalAgent: DesktopAgentWorkspaceService? = null

    /** 供 UI 订阅的确认变化信号，事件中不包含明文或凭据。 */
    override val externalChanges: kotlinx.coroutines.flow.Flow<Unit> get() = externalChangeNotifications

    /**
     * 为本机协议适配器提供同一会话的受控工具入口。
     * @return 锁定时为空；不暴露文件路径或工作区密钥。
     */
    @Synchronized
    fun externalSessionOrNull(): com.oruke.tagloom.core.external.AgentWorkspaceSession? {
        val session = manager.sessionOrNull() ?: return null.also { externalAgent = null }
        if (externalAgent?.sessionId != session.actorId.value) {
            externalAgent = DesktopAgentWorkspaceService(session) { externalChangeNotifications.tryEmit(Unit) }
        }
        return externalAgent
    }

    /** @return 当前已确认工作区；只读取，不重新打开或初始化会话。 */
    override suspend fun reload(): WorkspaceOpenResult = withContext(Dispatchers.IO) {
        requireNotNull(manager.sessionOrNull()) { "工作区尚未解锁" }.reloadState().toSharedWorkspaceOpenResult()
    }

    /** 与本机权威会话共用创建、备份和恢复边界的服务器入口。 */
    private val serverRecoveryService = DesktopServerRecoveryService(
        manager, localAccessService, ServerWorkspaceDirectoryStore(recoveryAccessDirectory.resolveSibling("server-workspaces")),
        ServerRecoveryAccessStore(recoveryAccessDirectory), recoveryClientFactory,
        onWorkspaceChanged = { externalChangeNotifications.tryEmit(Unit) },
    )

    /** 只向共享 UI 暴露受控恢复能力。 */
    override val serverRecovery: WorkspaceServerRecoveryGateway get() = serverRecoveryService

    /** 与本机私有凭据隔离存储绑定的 Agent 配置网关。 */
    private val agentConfigService = DesktopAgentConfigGateway(
        gateway = this,
        apiKeyStore = AgentApiKeyStore(recoveryAccessDirectory.resolveSibling("agent-credentials")),
        streamPort = agentStreamPort,
        onConfigCommitted = { externalChangeNotifications.tryEmit(Unit) },
    )

    /** 只向共享 UI 暴露脱敏的 Agent 与模型配置入口。 */
    override val agentConfig: com.oruke.tagloom.platform.WorkspaceAgentConfigGateway get() = agentConfigService

    /**
     * 返回当前活动的本地工作区会话，供共享能力代理复用同一条索引连接。
     *
     * @return 当前已认证会话；工作区锁定时为空。
     */
    internal fun sessionOrNull(): DesktopWorkspaceSession? = manager.sessionOrNull()

    /** 链接功能与其他入口共用当前唯一会话，不另开工作区。 */
    override val links: com.oruke.tagloom.platform.WorkspaceLinkGateway = DesktopLinkNoteGateway(this)

    /**
     * 生成不含业务语义的 UUID 文本。
     *
     * @param purpose 调用目的；不会编码到 ID。
     * @return URL-safe 不透明 ID。
     */
    override fun createOpaqueId(purpose: String): String {
        require(purpose.isNotBlank()) { "ID 用途不能为空" }
        return UUID.randomUUID().toString()
    }

    /**
     * 返回当前 UTC ISO-8601 时间。
     *
     * @return UTC 时间文本。
     */
    override fun currentUtcTimestamp(): String = Instant.now().toString()

    /**
     * 返回当前设备最近登记工作区的安全展示信息。
     *
     * @return 未登记工作区时为空；结果不包含本机路径或解锁材料。
     */
    override suspend fun recentWorkspaceOrNull(): WorkspaceRecentWorkspace? = withContext(Dispatchers.IO) {
        localAccessService.recentWorkspaceOrNull()?.let { access ->
            WorkspaceRecentWorkspace(
                displayName = access.displayName,
                requiresPin = access.requiresPin,
            )
        }
    }

    /**
     * 使用当前设备保护的材料自动打开最近工作区。
     *
     * @return 已认证工作区的共享打开结果。
     */
    override suspend fun openRecentWorkspace(): WorkspaceOpenResult = withContext(Dispatchers.IO) {
        return@withContext localAccessService.withActiveWorkspaceCredential(pin = null) { root, secret ->
            manager.unlock(root, secret)
        }.toSharedWorkspaceOpenResult()
    }

    /**
     * 使用本机 PIN 解开最近工作区的高熵材料并打开工作区。
     *
     * @param pin 用户输入的本机 PIN。
     * @return 已认证工作区的共享打开结果。
     */
    override suspend fun unlockRecentWorkspace(pin: String): WorkspaceOpenResult = withContext(Dispatchers.IO) {
        return@withContext localAccessService.withActiveWorkspaceCredential(pin) { root, secret ->
            manager.unlock(root, secret)
        }.toSharedWorkspaceOpenResult()
    }

    /**
     * 选择空目录并准备新工作区。
     *
     * @param pin 用户为当前设备设置的可选 PIN；为空时不启用 PIN。
     * @return 用户取消时为空，否则返回一次性 Recovery Key。
     */
    override suspend fun prepareCreation(pin: String?): WorkspacePreparedCreation? = withContext(Dispatchers.IO) {
        val root = documents.creationDirectory() ?: return@withContext null
        val prepared = localAccessService.preparePendingCreation(
            root = root,
            pin = pin,
            prepare = { secret -> manager.prepareCreation(root, secret) },
        )
        return@withContext WorkspacePreparedCreation(prepared.recoveryKeyText)
    }

    /**
     * 确认 Recovery Key 已保存并打开新工作区。
     *
     * @return 已打开工作区。
     */
    override suspend fun confirmCreation(): WorkspaceOpenResult = withContext(Dispatchers.IO) {
        return@withContext localAccessService.confirmPendingCreation(
            confirm = manager::confirmCreation,
            onRegistrationFailure = manager::lock,
        ).toSharedWorkspaceOpenResult()
    }

    /** 放弃尚未确认的创建流程。 */
    override fun cancelCreation() {
        manager.cancelPendingCreation()
        localAccessService.discardPendingCreation()
    }

    /**
     * 选择既有目录，使用 Recovery Key 建立新的本机访问入口。
     *
     * @param recoveryKeyText 用户离线保存的 Recovery Key。
     * @param pin 用户为当前设备设置的可选 PIN；为空时不启用 PIN。
     * @return 用户取消时为空。
     */
    override suspend fun unlockWithRecovery(
        recoveryKeyText: String,
        pin: String?,
    ): WorkspaceOpenResult? = withContext(Dispatchers.IO) {
        val root = documents.recoveryDirectory() ?: return@withContext null
        val result = localAccessService.establishLocalAccess(
            root = root,
            pin = pin,
            authenticate = { secret -> manager.recoverAndReplacePasswordSlot(root, recoveryKeyText, secret) },
            onRegistrationFailure = manager::lock,
        ).toSharedWorkspaceOpenResult()
        serverRecoveryService.useLocalMode()
        return@withContext result
    }

    /**
     * 选择 ZIP 目标并创建当前确认点的密文备份。
     *
     * @return 用户完成备份时为 true，取消时为 false。
     */
    override suspend fun createBackup(): Boolean = withContext(Dispatchers.IO) {
        return@withContext documents.exportBackup { destination ->
            requireNotNull(manager.sessionOrNull()) { "工作区尚未解锁" }.createBackup(destination)
        }
    }

    /**
     * 选择密文备份与空目录，使用 Recovery Key 恢复并建立本机访问入口。
     *
     * @param recoveryKeyText 用户离线保存的 Recovery Key。
     * @param pin 用户为恢复后工作区设置的可选 PIN；为空时不启用 PIN。
     * @return 用户取消任一步骤时为空。
     */
    override suspend fun restoreBackup(
        recoveryKeyText: String,
        pin: String?,
    ): WorkspaceOpenResult? = withContext(Dispatchers.IO) {
        return@withContext documents.importBackup { archive, target ->
            localAccessService.establishLocalAccess(
                root = target,
                pin = pin,
                authenticate = { secret ->
                    manager.restoreBackupWithRecovery(
                        archive = archive,
                        targetRoot = target,
                        recoveryKeyText = recoveryKeyText,
                        newPassword = secret,
                    )
                },
                onRegistrationFailure = manager::lock,
            ).toSharedWorkspaceOpenResult()
        }
    }

    /**
     * 为当前设备保护的本机材料启用 PIN。
     *
     * @param pin 用户设置的 6 至 12 位数字 PIN。
     */
    override suspend fun enablePin(pin: String): Unit = withContext(Dispatchers.IO) {
        localAccessService.enablePin(pin)
    }

    /**
     * 认证当前 PIN 后用新 PIN 重新加密本机解锁材料。
     *
     * 工作区 manifest、对象密文和 Recovery Key 全部保持不变。
     *
     * @param currentPin 当前设备 PIN。
     * @param newPin 新设备 PIN。
     */
    override suspend fun changePin(
        currentPin: String,
        newPin: String,
    ): Unit = withContext(Dispatchers.IO) {
        localAccessService.changePin(currentPin, newPin)
    }

    /**
     * 认证当前 PIN 后停止要求 PIN，改回当前设备自动打开模式。
     *
     * @param currentPin 当前设备 PIN。
     */
    override suspend fun disablePin(currentPin: String): Unit = withContext(Dispatchers.IO) {
        localAccessService.disablePin(currentPin)
    }

    /**
     * 轮换活动工作区主密钥并返回只展示一次的新 Recovery Key。
     *
     * 高熵本机解锁材料在轮换前后保持不变；它不会把用户 PIN 写入工作区 manifest，
     * 同时避免本机登记与已经提交的新 manifest 之间出现两套不同解锁材料。
     *
     * @param currentPin 当前设备 PIN；未启用 PIN 时为空。
     * @return 使用新密钥重新打开的共享状态与新 Recovery Key。
     */
    override suspend fun rotateMasterKey(currentPin: String?): WorkspaceMasterKeyRotationResult = withContext(Dispatchers.IO) {
        val result = localAccessService.withActiveWorkspaceCredential(currentPin) { _, secret ->
            manager.rotateActiveMasterKey(secret, secret)
        }
        return@withContext WorkspaceMasterKeyRotationResult(
            openResult = result.openState.toSharedWorkspaceOpenResult(),
            recoveryKeyText = result.recoveryKeyText,
        )
    }

    /**
     * 通过活动会话创建或更新 Note。
     *
     * @param objectId 已持久化 ID；新草稿为空。
     * @param note 完整 Note payload。
     * @param expectedRevision 调用方读取时的权威 revision。
     * @return 新确认 Note。
     */
    override suspend fun saveNote(
        objectId: ObjectId?,
        note: Note,
        expectedRevision: ObjectRevision?,
    ): WorkspaceNote = withContext(Dispatchers.IO) {
        requireNotNull(manager.sessionOrNull()) { "工作区尚未解锁" }
            .saveNote(objectId, note, expectedRevision)
            .toSharedWorkspaceNote()
    }

    /**
     * 把 Note 写为 tombstone。
     *
     * @param objectId 待删除 Note。
     */
    override suspend fun deleteNote(objectId: ObjectId): Unit = withContext(Dispatchers.IO) {
        requireNotNull(manager.sessionOrNull()) { "工作区尚未解锁" }.deleteNote(objectId)
    }

    /**
     * 查询加密派生索引。
     *
     * @param text 全文条件。
     * @return 共享 UI 命中。
     */
    suspend fun search(text: String): List<WorkspaceSearchHit> = withContext(Dispatchers.IO) {
        val normalized = text.trim()
        if (normalized.isEmpty()) {
            return@withContext emptyList()
        }
        return@withContext requireNotNull(manager.sessionOrNull()) { "工作区尚未解锁" }
            .search(SearchIndexQuery(text = normalized))
            .map { hit -> WorkspaceSearchHit(hit.noteId, hit.title, hit.snippet) }
    }

    /**
     * 通过活动会话保存查询快照与 Change。
     *
     * @param query 待保存查询。
     * @return 新确认保存查询。
     */
    override suspend fun saveQuery(query: SavedQuery): WorkspaceSavedQuery = withContext(Dispatchers.IO) {
        val saved = requireNotNull(manager.sessionOrNull()) { "工作区尚未解锁" }.saveQuery(query)
        return@withContext WorkspaceSavedQuery(saved.id, saved.snapshot.payload)
    }

    /**
     * 通过活动会话创建或更新标签定义快照与 Change。
     *
     * @param definition 待保存完整标签定义。
     * @return 新确认标签定义。
     */
    override suspend fun saveTagDefinition(definition: TagDefinition): WorkspaceTagDefinition = withContext(Dispatchers.IO) {
        require(definition.id != com.oruke.tagloom.core.workspace.WorkspaceDefaults.DEFAULT_NOTE_TAG_ID) { "builtin-supertag-readonly" }
        val saved = requireNotNull(manager.sessionOrNull()) { "工作区尚未解锁" }.saveTagDefinition(definition)
        return@withContext WorkspaceTagDefinition(saved.snapshot.payload)
    }

    /**
     * 经活动会话复核引用并确认超级标签 tombstone。
     * @param tagId 待删除超级标签 ID。
     */
    override suspend fun deleteTagDefinition(tagId: com.oruke.tagloom.core.model.TagId): Unit = withContext(Dispatchers.IO) {
        requireNotNull(manager.sessionOrNull()) { "工作区尚未解锁" }.deleteTagDefinition(tagId)
    }

    /**
     * 通过活动会话创建或更新属性定义快照与 Change。
     *
     * @param definition 待保存完整属性定义。
     * @return 新确认属性定义。
     */
    override suspend fun savePropertyDefinition(definition: PropertyDefinition): WorkspacePropertyDefinition = withContext(Dispatchers.IO) {
        val saved = requireNotNull(manager.sessionOrNull()) { "工作区尚未解锁" }.savePropertyDefinition(definition)
        return@withContext WorkspacePropertyDefinition(saved.snapshot.payload)
    }

    /**
     * 通过活动会话原子创建 Database 与首个 View。
     *
     * @param database Database 完整定义。
     * @param initialView 首个 View。
     * @return 两个已确认目录对象。
     */
    override suspend fun createDatabase(
        database: DatabaseDefinition,
        initialView: ViewDefinition,
    ): WorkspaceDatabaseCatalog = withContext(Dispatchers.IO) {
        val (savedDatabase, savedView) = requireNotNull(manager.sessionOrNull()) { "工作区尚未解锁" }
            .createDatabaseCatalog(database, initialView)
        return@withContext WorkspaceDatabaseCatalog(
            WorkspaceDatabaseDefinition(savedDatabase.snapshot.payload),
            WorkspaceViewDefinition(savedView.snapshot.payload),
        )
    }

    /**
     * 通过活动会话更新 DatabaseDefinition。
     *
     * @param definition 新完整定义。
     * @return 新确认 Database。
     */
    override suspend fun saveDatabaseDefinition(definition: DatabaseDefinition): WorkspaceDatabaseDefinition = withContext(Dispatchers.IO) {
        val saved = requireNotNull(manager.sessionOrNull()) { "工作区尚未解锁" }
            .saveDatabaseDefinition(definition)
        return@withContext WorkspaceDatabaseDefinition(saved.snapshot.payload)
    }

    /**
     * 通过活动会话为 Database 创建一个新 ViewDefinition。
     *
     * @param definition 新视图完整定义。
     * @return 新确认 View。
     */
    override suspend fun createViewDefinition(definition: ViewDefinition): WorkspaceViewDefinition = withContext(Dispatchers.IO) {
        val saved = requireNotNull(manager.sessionOrNull()) { "工作区尚未解锁" }
            .createViewDefinition(definition)
        return@withContext WorkspaceViewDefinition(saved.snapshot.payload)
    }

    /**
     * 通过活动会话更新 ViewDefinition。
     *
     * @param definition 新完整定义。
     * @return 新确认 View。
     */
    override suspend fun saveViewDefinition(definition: ViewDefinition): WorkspaceViewDefinition = withContext(Dispatchers.IO) {
        val saved = requireNotNull(manager.sessionOrNull()) { "工作区尚未解锁" }
            .saveViewDefinition(definition)
        return@withContext WorkspaceViewDefinition(saved.snapshot.payload)
    }

    /**
     * 从权威查询快照恢复条件并执行加密索引查询。
     *
     * @param objectId 保存查询 ID。
     * @return 共享 UI 命中。
     */
    suspend fun runSavedQuery(objectId: ObjectId): List<WorkspaceSearchHit> = withContext(Dispatchers.IO) {
        requireNotNull(manager.sessionOrNull()) { "工作区尚未解锁" }
            .runSavedQuery(objectId)
            .map { hit -> WorkspaceSearchHit(hit.noteId, hit.title, hit.snippet) }
    }

    /** 锁定工作区并销毁会话密钥。 */
    override suspend fun lock(): Unit = withContext(Dispatchers.IO) {
        agentConfigService.cancelActiveOperations()
        serverRecoveryService.cancelTransfer()
        manager.lock()
    }

}
