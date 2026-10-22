package com.oruke.tagloom.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.oruke.tagloom.core.index.PropertyIndexFilter
import com.oruke.tagloom.core.index.SearchIndexQuery
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.PropertyId
import com.oruke.tagloom.core.model.SavedQuery
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.editor.EditorDocument
import com.oruke.tagloom.editor.PropertyDefinitionDestination
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.editor.applyFreePropertyOption
import com.oruke.tagloom.editor.bindPropertyToTag
import com.oruke.tagloom.editor.materializeSelectedSnapshotDefaults
import com.oruke.tagloom.editor.planFreePropertyOption
import com.oruke.tagloom.editor.setAppliedObjectType
import com.oruke.tagloom.editor.setPropertyAttached
import com.oruke.tagloom.editor.upsertPropertyDefinition
import com.oruke.tagloom.editor.upsertTagDefinition
import com.oruke.tagloom.editor.validateTagDefinition
import com.oruke.tagloom.index.SearchEngine
import com.oruke.tagloom.platform.WorkspaceGateway
import com.oruke.tagloom.platform.WorkspaceOpenResult
import com.oruke.tagloom.platform.WorkspaceRecentWorkspace
import com.oruke.tagloom.platform.WorkspaceRecoveryMessage
import com.oruke.tagloom.platform.WorkspaceSavedQuery
import com.oruke.tagloom.platform.WorkspaceSearchHit

/**
 * 区分共享 UI 的锁定、Recovery Key 确认、已打开和只读恢复阶段。
 */
internal enum class WorkspaceUiPhase {
    /** 正在读取本机最近工作区登记。 */
    INITIALIZING,

    /** 尚未解锁。 */
    WELCOME,

    /** 新工作区正在等待用户确认 Recovery Key。 */
    RECOVERY_CONFIRMATION,

    /** 正常读写会话。 */
    OPEN,

    /** 启动恢复无法证明状态完整，只展示恢复说明。 */
    READ_ONLY,
}

/**
 * 统一管理共享 UI 的异步工作区生命周期和编辑器明文清理。
 */
internal class WorkspaceUiController(
    /** 当前工作区生命周期和权威写入能力。 */
    internal val gateway: WorkspaceGateway,
    /** 当前已打开工作区的查询能力。 */
    private val searchEngine: SearchEngine,
    /** 仅在解锁会话内持有 Note 明文的编辑器状态。 */
    val editorState: TagloomEditorState,
) {
    /** 超级标签表单独立编排，复用同一工作区网关与编辑器目录。 */
    val supertags = WorkspaceSupertagController(gateway, editorState)

    /** 当前工作区 UI 阶段。 */
    var phase: WorkspaceUiPhase by mutableStateOf(WorkspaceUiPhase.INITIALIZING)
        private set

    /** 每次打开或开始锁定时更换的 UI 会话标记，用于丢弃上一会话仍在返回的异步刷新。 */
    internal var workspaceSessionMarker: Any = Any()
        private set

    /** 平台 I/O 或密码操作是否正在执行。 */
    var busy: Boolean by mutableStateOf(false)
        private set

    /** 当前可安全展示的本地错误。 */
    var errorMessage: WorkspaceUiMessage? by mutableStateOf(null)
        internal set

    /** 当前操作成功后的非敏感提示。 */
    var statusMessage: WorkspaceUiMessage? by mutableStateOf(null)
        internal set

    /** 本机最近登记工作区的安全展示信息。 */
    var recentWorkspace: WorkspaceRecentWorkspace? by mutableStateOf(null)
        private set

    /** 创建流程只展示一次的 Recovery Key。 */
    var preparedRecoveryKey: String? by mutableStateOf(null)
        private set

    /** 主密钥轮换后只展示一次、等待用户确认离线保存的新 Recovery Key。 */
    var rotatedRecoveryKey: String? by mutableStateOf(null)
        private set

    /** prepared 事务与完整性扫描的脱敏恢复语义。 */
    var recoveryMessages: List<WorkspaceRecoveryMessage> by mutableStateOf(emptyList())
        private set

    /** 当前搜索条件。 */
    var searchText: String by mutableStateOf("")

    /** 只来自已确认快照索引的搜索命中。 */
    var searchHits: List<WorkspaceSearchHit> by mutableStateOf(emptyList())
        private set

    /** 当前工作区已确认保存查询。 */
    var savedQueries: List<WorkspaceSavedQuery> by mutableStateOf(emptyList())
        private set

    /** Database/View 目录、投影和单元格写入控制器。 */
    val databaseController: WorkspaceDatabaseController = WorkspaceDatabaseController(gateway, editorState)

    /** 统一编排 Note 保存与自动保存版本去重。 */
    private val noteSaveDelegate = WorkspaceNoteSaveDelegate(gateway, editorState) { links.onNoteSaved(it) }

    /** 链接录入与网络任务独立于文件保存，仍复用同一网关与编辑草稿。 */
    val links = WorkspaceLinkController(gateway, editorState) { while (busy) kotlinx.coroutines.delay(10) }

    /** 所有层级属性共用的选项目录维护入口。 */
    val propertyOptions = PropertyOptionCatalogController(gateway, editorState)

    /** 服务器恢复页面状态与操作，独立于编辑器写入和查询职责。 */
    val serverRecovery = WorkspaceServerRecoveryController(this)

    /** 在上传完整恢复副本前提交当前会话的全部草稿。 */
    internal suspend fun flushPendingNotes() = noteSaveDelegate.savePending()

    /**
     * 接受已验证的服务器恢复结果并刷新当前设备的最近入口。
     * @param result 已完成本机认证及凭据保护的恢复结果。
     */
    internal suspend fun acceptServerRecovery(result: WorkspaceOpenResult) {
        open(result)
        recentWorkspace = gateway.recentWorkspaceOrNull()
    }

    /**
     * 读取本机最近登记工作区，决定自动打开、PIN 解锁还是首次连接入口。
     *
     * 未启用 PIN 的本机登记会在此处直接认证，避免每次启动反复选择位置或输入
     * Recovery Key；本机登记不可用时不会猜测目录或自动恢复，用户必须显式选择新建或
     * 用 Recovery Key 连接既有工作区。
     */
    suspend fun initialize() {
        if (busy || phase != WorkspaceUiPhase.INITIALIZING) {
            return
        }
        busy = true
        errorMessage = null
        statusMessage = null
        try {
            recentWorkspace = gateway.recentWorkspaceOrNull()
            recentWorkspace
                ?.takeIf { workspace -> !workspace.requiresPin }
                ?.let { open(gateway.openRecentWorkspace()) }
        } catch (error: Throwable) {
            errorMessage = WorkspaceUiMessage.OperationFailed
        } finally {
            if (phase == WorkspaceUiPhase.INITIALIZING) {
                phase = WorkspaceUiPhase.WELCOME
            }
            busy = false
        }
    }

    /**
     * 选择空目录并按用户选择的本机保护模式准备两阶段创建。
     *
     * @param pin 用户为当前设备设置的可选 PIN；为空时不启用 PIN。
     */
    suspend fun prepareCreation(pin: String?) = runOperation {
        serverRecovery.cancelCreation()
        val prepared = gateway.prepareCreation(pin) ?: return@runOperation
        presentPreparedCreation(prepared)
    }

    /**
     * 展示本地或服务器新建共用的恢复密钥确认页面。
     * @param prepared 尚未确认发布的新工作区材料。
     */
    internal fun presentPreparedCreation(prepared: com.oruke.tagloom.platform.WorkspacePreparedCreation) {
        preparedRecoveryKey = prepared.recoveryKeyText
        phase = WorkspaceUiPhase.RECOVERY_CONFIRMATION
    }

    /**
     * 在 Recovery Key 已确认后落盘并打开新工作区。
     */
    suspend fun confirmCreation() = runOperation {
        val recoveryKey = requireNotNull(preparedRecoveryKey)
        open(gateway.confirmCreation())
        recentWorkspace = gateway.recentWorkspaceOrNull()
        preparedRecoveryKey = null
        serverRecovery.finishCreation(recoveryKey)
    }

    /**
     * 放弃两阶段创建并回到锁定欢迎界面。
     */
    fun cancelCreation() {
        gateway.cancelCreation()
        serverRecovery.cancelCreation()
        preparedRecoveryKey = null
        errorMessage = null
        phase = WorkspaceUiPhase.WELCOME
    }

    /**
     * 使用当前设备系统保护的材料重新打开最近登记工作区。
     */
    suspend fun openRecentWorkspace() = runOperation {
        open(gateway.openRecentWorkspace())
    }

    /**
     * 使用 PIN 解锁最近登记工作区。
     *
     * @param pin 用户输入的当前设备 PIN。
     */
    suspend fun unlockRecentWorkspace(pin: String) = runOperation {
        open(gateway.unlockRecentWorkspace(pin))
    }

    /**
     * 选择既有工作区，使用 Recovery Key 建立新的本机访问入口。
     *
     * @param recoveryKeyText 用户离线保存的恢复文本。
     * @param pin 用户为当前设备设置的可选 PIN；为空时不启用 PIN。
     */
    suspend fun unlockWithRecovery(
        recoveryKeyText: String,
        pin: String?,
    ) = runOperation {
        gateway.unlockWithRecovery(recoveryKeyText, pin)?.let(::open)
        recentWorkspace = gateway.recentWorkspaceOrNull()
    }

    /**
     * 选择目标并创建当前确认点的密文备份。
     */
    suspend fun createBackup() = runOperation {
        if (gateway.createBackup()) {
            statusMessage = WorkspaceUiMessage.BackupCreated
        }
    }

    /**
     * 选择密文备份与空目录，使用 Recovery Key 恢复后建立新的本机访问入口。
     *
     * @param recoveryKeyText 用户离线保存的恢复文本。
     * @param pin 用户为当前设备设置的可选 PIN；为空时不启用 PIN。
     */
    suspend fun restoreBackup(
        recoveryKeyText: String,
        pin: String?,
    ) = runOperation {
        gateway.restoreBackup(recoveryKeyText, pin)?.let(::open)
        recentWorkspace = gateway.recentWorkspaceOrNull()
    }

    /**
     * 为当前设备系统保护的本机材料启用 PIN。
     *
     * @param pin 用户设置的 6 至 12 位数字 PIN。
     */
    suspend fun enablePin(pin: String) = runOperation {
        gateway.enablePin(pin)
        recentWorkspace = gateway.recentWorkspaceOrNull()
        statusMessage = WorkspaceUiMessage.PinEnabled
    }

    /**
     * 更换当前设备 PIN，不改变工作区 manifest 或已打开文档状态。
     *
     * @param currentPin 当前设备 PIN。
     * @param newPin 新设备 PIN。
     */
    suspend fun changePin(
        currentPin: String,
        newPin: String,
    ) = runOperation {
        gateway.changePin(currentPin, newPin)
        recentWorkspace = gateway.recentWorkspaceOrNull()
        statusMessage = WorkspaceUiMessage.PinChanged
    }

    /**
     * 认证当前本机访问模式并轮换主密钥、全部权威信封和 Recovery Key。
     *
     * @param currentPin 当前设备 PIN；未启用 PIN 时为空。
     */
    suspend fun rotateMasterKey(currentPin: String?) = runOperation {
        val result = gateway.rotateMasterKey(currentPin)
        open(result.openResult)
        rotatedRecoveryKey = result.recoveryKeyText
        statusMessage = WorkspaceUiMessage.MasterKeyRotated
    }

    /**
     * 认证当前 PIN 后停用 PIN，改回当前设备系统保护的自动打开模式。
     *
     * @param currentPin 当前设备 PIN。
     */
    suspend fun disablePin(currentPin: String) = runOperation {
        gateway.disablePin(currentPin)
        recentWorkspace = gateway.recentWorkspaceOrNull()
        statusMessage = WorkspaceUiMessage.PinDisabled
    }

    /**
     * 在用户明确确认已离线保存后清除界面中的新 Recovery Key 明文。
     */
    fun confirmRotatedRecoveryKeySaved() {
        rotatedRecoveryKey = null
    }

    /**
     * 保存当前文档；保存期间继续发生的内存编辑不会被旧返回值覆盖。
     */
    suspend fun saveSelected() = runOperation(failureMessage = WorkspaceUiMessage.NoteSaveFailed) {
        if (noteSaveDelegate.saveSelected()) {
            searchHits = emptyList()
        }
    }

    /**
     * 判断当前草稿是否比最近一次保存尝试更新。
     *
     * @return 工作区空闲且当前草稿版本尚未提交过时为 true。
     */
    fun canAutoSaveSelected(): Boolean = noteSaveDelegate.canAutoSave(busy)

    /**
     * 删除当前文档；已确认文档先写 tombstone，草稿只从内存移除。
     */
    suspend fun deleteSelected() = runOperation {
        val document = editorState.selectedDocument ?: return@runOperation
        if (document.persisted) {
            gateway.deleteNote(document.id)
        }
        editorState.removeDocument(document.id)
        searchHits = searchHits.filterNot { hit -> hit.noteId == document.id }
    }

    /**
     * 查询已确认快照索引，不把未保存草稿混入结果。
     */
    suspend fun search() = runOperation {
        searchHits = searchEngine.search(SearchIndexQuery(text = searchText)).map { hit ->
            WorkspaceSearchHit(hit.noteId, hit.title, hit.snippet)
        }
    }

    /**
     * 把当前全文条件保存为工作区对象。
     *
     * @param name 用户输入查询名称。
     */
    suspend fun saveCurrentQuery(name: String) = runOperation {
        val saved = gateway.saveQuery(SavedQuery(name = name.trim(), text = searchText.trim()))
        savedQueries = (savedQueries + saved).sortedWith(
            compareBy({ query -> query.query.name }, { query -> query.id.value }),
        )
        statusMessage = WorkspaceUiMessage.QuerySaved
    }

    /**
     * 运行一个已确认保存查询。
     *
     * @param objectId 保存查询对象 ID。
     */
    suspend fun runSavedQuery(objectId: com.oruke.tagloom.core.model.ObjectId) = runOperation {
        val saved = savedQueries.singleOrNull { query -> query.id == objectId }
            ?: return@runOperation
        searchHits = searchEngine.search(
            SearchIndexQuery(
                text = saved.query.text,
                tagIds = saved.query.tagIds.toSet(),
                propertyFilters = saved.query.propertyFilters.map { filter ->
                    PropertyIndexFilter(filter.propertyId, filter.canonicalValue)
                },
            ),
        ).map { hit -> WorkspaceSearchHit(hit.noteId, hit.title, hit.snippet) }
    }

    /**
     * 锁定工作区，并在 Gateway 销毁密钥后清空全部 Compose 明文状态。
     */
    suspend fun lock() = runOperation {
        workspaceSessionMarker = Any()
        links.clear()
        propertyOptions.clear()
        gateway.lock()
        serverRecovery.clear()
        editorState.clearWorkspace()
        databaseController.clear()
        noteSaveDelegate.resetAutoSaveAttempt()
        preparedRecoveryKey = null
        rotatedRecoveryKey = null
        recoveryMessages = emptyList()
        searchText = ""
        searchHits = emptyList()
        savedQueries = emptyList()
        recentWorkspace = gateway.recentWorkspaceOrNull()
        statusMessage = null
        phase = WorkspaceUiPhase.WELCOME
    }

    /**
     * 加载已认证 Note，并根据恢复结果选择正常编辑或只读说明界面。
     *
     * @param result 平台打开结果。
     */
    private fun open(result: WorkspaceOpenResult) {
        workspaceSessionMarker = Any()
        links.clear()
        propertyOptions.clear()
        noteSaveDelegate.resetAutoSaveAttempt()
        recoveryMessages = result.recoveryMessages
        if (result.readOnly) {
            editorState.clearWorkspace()
            databaseController.clear()
            savedQueries = emptyList()
            phase = WorkspaceUiPhase.READ_ONLY
            return
        }
        savedQueries = result.savedQueries
        databaseController.load(result.databaseDefinitions, result.viewDefinitions)
        if (result.recoveryMessages.isNotEmpty()) {
            statusMessage = WorkspaceUiMessage.IntegrityNotice
        }
        editorState.loadWorkspaceDocuments(
            result.notes.map { note ->
                EditorDocument(
                    id = note.id,
                    note = note.note,
                    revision = note.revision,
                    createdAt = note.createdAt,
                    updatedAt = note.updatedAt,
                    persisted = true,
                )
            },
            loadedTagDefinitions = result.tagDefinitions.map { tag -> tag.definition },
            loadedPropertyDefinitions = result.propertyDefinitions.map { property -> property.definition },
        )
        phase = WorkspaceUiPhase.OPEN
    }

    /**
     * 统一 busy 状态与安全错误展示，避免异常让按钮永久失效。
     *
     * @param failureMessage 本次操作失败后可安全展示的本地化语义。
     * @param block 单次平台工作区操作。
     */
    internal suspend fun runOperation(
        failureMessage: WorkspaceUiMessage = WorkspaceUiMessage.OperationFailed,
        block: suspend () -> Unit,
    ) {
        if (busy) {
            return
        }
        busy = true
        errorMessage = null
        statusMessage = null
        try {
            block()
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Throwable) {
            errorMessage = failureMessage
        } finally {
            busy = false
        }
    }
}
