package com.oruke.tagloom.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import com.oruke.tagloom.editor.setPropertyAttached
import com.oruke.tagloom.editor.setAppliedObjectType
import com.oruke.tagloom.editor.upsertPropertyDefinition
import com.oruke.tagloom.editor.upsertTagDefinition
import com.oruke.tagloom.editor.validateTagDefinition
import com.oruke.tagloom.platform.WorkspaceGateway
import com.oruke.tagloom.platform.WorkspaceOpenResult
import com.oruke.tagloom.platform.WorkspaceSavedQuery
import com.oruke.tagloom.platform.WorkspaceSearchHit

/**
 * 区分共享 UI 的锁定、Recovery Key 确认、已打开和只读恢复阶段。
 */
internal enum class WorkspaceUiPhase {
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
 *
 * @param gateway 当前平台真实工作区能力。
 * @param editorState 仅在解锁会话内持有 Note 明文的编辑器状态。
 */
internal class WorkspaceUiController(
    private val gateway: WorkspaceGateway,
    val editorState: TagloomEditorState,
) {
    /** 当前工作区 UI 阶段。 */
    var phase: WorkspaceUiPhase by mutableStateOf(WorkspaceUiPhase.WELCOME)
        private set

    /** 平台 I/O 或密码操作是否正在执行。 */
    var busy: Boolean by mutableStateOf(false)
        private set

    /** 当前可安全展示的本地错误。 */
    var errorMessage: String? by mutableStateOf(null)
        private set

    /** 当前操作成功后的非敏感提示。 */
    var statusMessage: String? by mutableStateOf(null)
        private set

    /** 创建流程只展示一次的 Recovery Key。 */
    var preparedRecoveryKey: String? by mutableStateOf(null)
        private set

    /** 主密钥轮换后只展示一次、等待用户确认离线保存的新 Recovery Key。 */
    var rotatedRecoveryKey: String? by mutableStateOf(null)
        private set

    /** prepared 事务恢复结果的脱敏说明。 */
    var recoveryMessages: List<String> by mutableStateOf(emptyList())
        private set

    /** 当前搜索条件。 */
    var searchText: String by mutableStateOf("")

    /** 只来自已确认快照索引的搜索命中。 */
    var searchHits: List<WorkspaceSearchHit> by mutableStateOf(emptyList())
        private set

    /** 当前工作区已确认保存查询。 */
    var savedQueries: List<WorkspaceSavedQuery> by mutableStateOf(emptyList())
        private set

    /**
     * 选择空目录并准备两阶段创建。
     *
     * @param password 初始密码。
     */
    suspend fun prepareCreation(password: String) = runOperation {
        val prepared = gateway.prepareCreation(password) ?: return@runOperation
        preparedRecoveryKey = prepared.recoveryKeyText
        phase = WorkspaceUiPhase.RECOVERY_CONFIRMATION
    }

    /**
     * 在 Recovery Key 已确认后落盘并打开新工作区。
     */
    suspend fun confirmCreation() = runOperation {
        open(gateway.confirmCreation())
        preparedRecoveryKey = null
    }

    /**
     * 放弃两阶段创建并回到锁定欢迎界面。
     */
    fun cancelCreation() {
        gateway.cancelCreation()
        preparedRecoveryKey = null
        errorMessage = null
        phase = WorkspaceUiPhase.WELCOME
    }

    /**
     * 选择既有工作区并使用密码解锁。
     *
     * @param password 本次输入密码。
     */
    suspend fun unlockWithPassword(password: String) = runOperation {
        gateway.unlockWithPassword(password)?.let(::open)
    }

    /**
     * 选择既有工作区并使用 Recovery Key 解锁。
     *
     * @param recoveryKeyText 用户离线保存的恢复文本。
     */
    suspend fun unlockWithRecovery(recoveryKeyText: String) = runOperation {
        gateway.unlockWithRecovery(recoveryKeyText)?.let(::open)
    }

    /**
     * 选择目标并创建当前确认点的密文备份。
     */
    suspend fun createBackup() = runOperation {
        if (gateway.createBackup()) {
            statusMessage = "密文备份已创建"
        }
    }

    /**
     * 选择密文备份与空目录，恢复后使用原密码解锁。
     *
     * @param password 原工作区密码。
     */
    suspend fun restoreBackup(password: String) = runOperation {
        gateway.restoreBackup(password)?.let(::open)
    }

    /**
     * 更换当前工作区密码并保留已打开文档状态。
     *
     * @param oldPassword 当前密码。
     * @param newPassword 新密码。
     */
    suspend fun changePassword(
        oldPassword: String,
        newPassword: String,
    ) = runOperation {
        gateway.changePassword(oldPassword, newPassword)
        statusMessage = "工作区密码已更换；对象正文未重新加密"
    }

    /**
     * 重新认证当前密码并轮换主密钥、全部权威信封和 Recovery Key。
     *
     * @param currentPassword 当前密码。
     * @param newPassword 新主密钥密码槽使用的密码。
     */
    suspend fun rotateMasterKey(
        currentPassword: String,
        newPassword: String,
    ) = runOperation {
        val result = gateway.rotateMasterKey(currentPassword, newPassword)
        open(result.openResult)
        rotatedRecoveryKey = result.recoveryKeyText
        statusMessage = "主密钥和全部权威密文已轮换；请立即离线保存新的 Recovery Key"
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
    suspend fun saveSelected() = runOperation {
        editorState.materializeSelectedSnapshotDefaults()
        val submitted = editorState.selectedDocument ?: return@runOperation
        val saved = gateway.saveNote(
            objectId = submitted.id.takeIf { submitted.persisted },
            note = submitted.note,
        )
        val current = editorState.documents.firstOrNull { document -> document.id == submitted.id }
        val hasConcurrentChanges = current?.note?.let { note -> note != submitted.note } == true
        val visibleNote = current?.note?.takeIf { hasConcurrentChanges } ?: saved.note
        editorState.acceptPersistedDocument(
            previousId = submitted.id,
            persistedDocument = EditorDocument(
                id = saved.id,
                note = visibleNote,
                createdAt = current?.createdAt?.takeIf { hasConcurrentChanges } ?: saved.createdAt,
                updatedAt = current?.updatedAt?.takeIf { hasConcurrentChanges } ?: saved.updatedAt,
                persisted = true,
                dirty = hasConcurrentChanges,
            ),
        )
        searchHits = emptyList()
    }

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
        searchHits = gateway.search(searchText)
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
        statusMessage = "查询已保存"
    }

    /**
     * 创建或更新标签定义，并可在确认后立即关联当前 Note。
     *
     * @param definition 待确认完整标签定义。
     * @param attachToSelected 保存成功后是否关联当前 Note。
     */
    suspend fun saveTagDefinition(
        definition: TagDefinition,
        attachToSelected: Boolean,
    ) = runOperation {
        val validationErrors = editorState.validateTagDefinition(definition)
        require(validationErrors.isEmpty()) { validationErrors.joinToString(separator = "；") }
        val saved = gateway.saveTagDefinition(definition).definition
        editorState.upsertTagDefinition(saved)
        if (attachToSelected) {
            editorState.setAppliedObjectType(saved.id)
        } else {
            editorState.materializeSelectedSnapshotDefaults()
        }
        statusMessage = if (attachToSelected) "对象类型已创建并关联" else "对象类型定义已更新"
    }

    /**
     * 创建或更新属性定义，并可在确认后立即加入当前 Note。
     *
     * @param definition 待确认完整属性定义。
     * @param destination 创建后绑定当前笔记、对象类型或仅进入工作区目录。
     */
    suspend fun savePropertyDefinition(
        definition: PropertyDefinition,
        destination: PropertyDefinitionDestination,
    ) = runOperation {
        val saved = gateway.savePropertyDefinition(definition).definition
        editorState.upsertPropertyDefinition(saved)
        statusMessage = when (destination) {
            PropertyDefinitionDestination.CurrentNote -> {
                editorState.setPropertyAttached(saved.id, attached = true)
                "属性已创建并加入当前笔记"
            }
            PropertyDefinitionDestination.Workspace -> "属性定义已保存到工作区"
            is PropertyDefinitionDestination.Tag -> {
                val updatedTag = editorState.bindPropertyToTag(destination.tagId, saved.id)
                val confirmedTag = gateway.saveTagDefinition(updatedTag).definition
                editorState.upsertTagDefinition(confirmedTag)
                editorState.materializeSelectedSnapshotDefaults()
                "属性已创建并绑定类型 ${confirmedTag.name}"
            }
        }
    }

    /**
     * 为自由单选或多选属性确认新选项，并在确认后选择该选项。
     *
     * schema 先于 Note 值确认，因此进程中断最多留下未引用的可复用选项，不会让 Note
     * 指向不存在的选项；Note 值随后仍由统一自动保存链路确认。
     *
     * @param propertyId 目标属性稳定 ID。
     * @param label 用户输入的选项名称。
     */
    suspend fun createFreePropertyOptionAndSelect(
        propertyId: PropertyId,
        label: String,
    ) = runOperation {
        val plan = editorState.planFreePropertyOption(propertyId, label)
        val confirmedDefinition = if (plan.requiresPersistence) {
            gateway.savePropertyDefinition(plan.definition).definition
        } else {
            plan.definition
        }
        editorState.applyFreePropertyOption(confirmedDefinition, plan.optionId)
        statusMessage = if (plan.requiresPersistence) "新选项已保存并选择" else "已选择已有选项"
    }

    /**
     * 运行一个已确认保存查询。
     *
     * @param objectId 保存查询对象 ID。
     */
    suspend fun runSavedQuery(objectId: com.oruke.tagloom.core.model.ObjectId) = runOperation {
        searchHits = gateway.runSavedQuery(objectId)
    }

    /**
     * 锁定工作区，并在 Gateway 销毁密钥后清空全部 Compose 明文状态。
     */
    suspend fun lock() = runOperation {
        gateway.lock()
        editorState.clearWorkspace()
        preparedRecoveryKey = null
        rotatedRecoveryKey = null
        recoveryMessages = emptyList()
        searchText = ""
        searchHits = emptyList()
        savedQueries = emptyList()
        statusMessage = null
        phase = WorkspaceUiPhase.WELCOME
    }

    /**
     * 加载已认证 Note，并根据恢复结果选择正常编辑或只读说明界面。
     *
     * @param result 平台打开结果。
     */
    private fun open(result: WorkspaceOpenResult) {
        recoveryMessages = result.recoveryMessages
        if (result.readOnly) {
            editorState.clearWorkspace()
            savedQueries = emptyList()
            phase = WorkspaceUiPhase.READ_ONLY
            return
        }
        savedQueries = result.savedQueries
        if (result.recoveryMessages.isNotEmpty()) {
            statusMessage = "已隔离无法验证的对象；可先创建密文备份并查看恢复提示"
        }
        editorState.loadWorkspaceDocuments(
            result.notes.map { note ->
                EditorDocument(
                    id = note.id,
                    note = note.note,
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
     * @param block 单次平台工作区操作。
     */
    private suspend fun runOperation(block: suspend () -> Unit) {
        if (busy) {
            return
        }
        busy = true
        errorMessage = null
        statusMessage = null
        try {
            block()
        } catch (error: Throwable) {
            errorMessage = error.message?.takeIf(String::isNotBlank) ?: "工作区操作失败"
        } finally {
            busy = false
        }
    }
}
