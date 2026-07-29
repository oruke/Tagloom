package com.oruke.tagloom

import com.oruke.tagloom.core.index.SearchIndexQuery
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.SavedQuery
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.platform.WorkspaceGateway
import com.oruke.tagloom.platform.WorkspaceNote
import com.oruke.tagloom.platform.WorkspaceOpenResult
import com.oruke.tagloom.platform.WorkspaceMasterKeyRotationResult
import com.oruke.tagloom.platform.WorkspacePreparedCreation
import com.oruke.tagloom.platform.WorkspaceSearchHit
import com.oruke.tagloom.platform.WorkspaceSavedQuery
import com.oruke.tagloom.platform.WorkspaceTagDefinition
import com.oruke.tagloom.platform.WorkspacePropertyDefinition
import com.oruke.tagloom.workspace.DesktopStoredNote
import com.oruke.tagloom.workspace.DesktopWorkspaceManager
import com.oruke.tagloom.workspace.DesktopWorkspaceOpenState
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

/**
 * 使用 Swing 目录选择器和真实桌面工作区 Manager 实现共享 UI Gateway。
 */
class DesktopWorkspaceGateway(
    /** 持有创建、解锁和活动会话生命周期的 Manager。 */
    private val manager: DesktopWorkspaceManager = DesktopWorkspaceManager(),
) : WorkspaceGateway {
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
     * 选择空目录并准备新工作区。
     *
     * @param password 初始密码。
     * @return 用户取消时为空，否则返回一次性 Recovery Key。
     */
    override suspend fun prepareCreation(password: String): WorkspacePreparedCreation? {
        val root = chooseDirectory("选择空目录创建 Tagloom 工作区") ?: return null
        val prepared = manager.prepareCreation(root, password)
        return WorkspacePreparedCreation(prepared.recoveryKeyText)
    }

    /**
     * 确认 Recovery Key 已保存并打开新工作区。
     *
     * @return 已打开工作区。
     */
    override suspend fun confirmCreation(): WorkspaceOpenResult = manager.confirmCreation().toSharedResult()

    /** 放弃尚未确认的创建流程。 */
    override fun cancelCreation() {
        manager.cancelPendingCreation()
    }

    /**
     * 选择既有目录并使用密码解锁。
     *
     * @param password 本次密码。
     * @return 用户取消时为空。
     */
    override suspend fun unlockWithPassword(password: String): WorkspaceOpenResult? {
        val root = chooseDirectory("选择 Tagloom 工作区") ?: return null
        return manager.unlock(root, password).toSharedResult()
    }

    /**
     * 选择既有目录并使用 Recovery Key 解锁。
     *
     * @param recoveryKeyText 恢复文本。
     * @return 用户取消时为空。
     */
    override suspend fun unlockWithRecovery(recoveryKeyText: String): WorkspaceOpenResult? {
        val root = chooseDirectory("选择要用 Recovery Key 解锁的 Tagloom 工作区") ?: return null
        return manager.unlockWithRecovery(root, recoveryKeyText).toSharedResult()
    }

    /**
     * 选择 ZIP 目标并创建当前确认点的密文备份。
     *
     * @return 用户完成备份时为 true，取消时为 false。
     */
    override suspend fun createBackup(): Boolean {
        val destination = chooseBackupDestination() ?: return false
        requireNotNull(manager.sessionOrNull()) { "工作区尚未解锁" }.createBackup(destination)
        return true
    }

    /**
     * 选择密文备份与空目录，并使用原密码打开恢复工作区。
     *
     * @param password 原工作区密码。
     * @return 用户取消任一步骤时为空。
     */
    override suspend fun restoreBackup(password: String): WorkspaceOpenResult? {
        val archive = chooseBackupArchive() ?: return null
        val target = chooseDirectory("选择空目录恢复 Tagloom 工作区") ?: return null
        return manager.restoreBackup(archive, target, password).toSharedResult()
    }

    /**
     * 更换活动工作区密码并保持对象密文不变。
     *
     * @param oldPassword 当前密码。
     * @param newPassword 新密码。
     * @return 刷新后的共享打开状态。
     */
    override suspend fun changePassword(
        oldPassword: String,
        newPassword: String,
    ): WorkspaceOpenResult = manager.changeActivePassword(oldPassword, newPassword).toSharedResult()

    /**
     * 轮换活动工作区主密钥并返回只展示一次的新 Recovery Key。
     *
     * @param currentPassword 当前密码。
     * @param newPassword 新主密钥密码槽使用的密码。
     * @return 使用新密钥重新打开的共享状态与新 Recovery Key。
     */
    override suspend fun rotateMasterKey(
        currentPassword: String,
        newPassword: String,
    ): WorkspaceMasterKeyRotationResult {
        val result = manager.rotateActiveMasterKey(currentPassword, newPassword)
        return WorkspaceMasterKeyRotationResult(
            openResult = result.openState.toSharedResult(),
            recoveryKeyText = result.recoveryKeyText,
        )
    }

    /**
     * 通过活动会话创建或更新 Note。
     *
     * @param objectId 已持久化 ID；新草稿为空。
     * @param note 完整 Note payload。
     * @return 新确认 Note。
     */
    override suspend fun saveNote(
        objectId: ObjectId?,
        note: Note,
    ): WorkspaceNote = requireNotNull(manager.sessionOrNull()) { "工作区尚未解锁" }
        .saveNote(objectId, note)
        .toSharedNote()

    /**
     * 把 Note 写为 tombstone。
     *
     * @param objectId 待删除 Note。
     */
    override suspend fun deleteNote(objectId: ObjectId) {
        requireNotNull(manager.sessionOrNull()) { "工作区尚未解锁" }.deleteNote(objectId)
    }

    /**
     * 查询加密派生索引。
     *
     * @param text 全文条件。
     * @return 共享 UI 命中。
     */
    override suspend fun search(text: String): List<WorkspaceSearchHit> {
        val normalized = text.trim()
        if (normalized.isEmpty()) {
            return emptyList()
        }
        return requireNotNull(manager.sessionOrNull()) { "工作区尚未解锁" }
            .search(SearchIndexQuery(text = normalized))
            .map { hit -> WorkspaceSearchHit(hit.noteId, hit.title, hit.snippet) }
    }

    /**
     * 通过活动会话保存查询快照与 Change。
     *
     * @param query 待保存查询。
     * @return 新确认保存查询。
     */
    override suspend fun saveQuery(query: SavedQuery): WorkspaceSavedQuery {
        val saved = requireNotNull(manager.sessionOrNull()) { "工作区尚未解锁" }.saveQuery(query)
        return WorkspaceSavedQuery(saved.id, saved.snapshot.payload)
    }

    /**
     * 通过活动会话创建或更新标签定义快照与 Change。
     *
     * @param definition 待保存完整标签定义。
     * @return 新确认标签定义。
     */
    override suspend fun saveTagDefinition(definition: TagDefinition): WorkspaceTagDefinition {
        val saved = requireNotNull(manager.sessionOrNull()) { "工作区尚未解锁" }.saveTagDefinition(definition)
        return WorkspaceTagDefinition(saved.snapshot.payload)
    }

    /**
     * 通过活动会话创建或更新属性定义快照与 Change。
     *
     * @param definition 待保存完整属性定义。
     * @return 新确认属性定义。
     */
    override suspend fun savePropertyDefinition(definition: PropertyDefinition): WorkspacePropertyDefinition {
        val saved = requireNotNull(manager.sessionOrNull()) { "工作区尚未解锁" }.savePropertyDefinition(definition)
        return WorkspacePropertyDefinition(saved.snapshot.payload)
    }

    /**
     * 从权威查询快照恢复条件并执行加密索引查询。
     *
     * @param objectId 保存查询 ID。
     * @return 共享 UI 命中。
     */
    override suspend fun runSavedQuery(objectId: ObjectId): List<WorkspaceSearchHit> =
        requireNotNull(manager.sessionOrNull()) { "工作区尚未解锁" }
            .runSavedQuery(objectId)
            .map { hit -> WorkspaceSearchHit(hit.noteId, hit.title, hit.snippet) }

    /** 锁定工作区并销毁会话密钥。 */
    override suspend fun lock() {
        manager.lock()
    }

    /**
     * 在 Swing 事件线程选择单个目录。
     *
     * @param title 目录选择器标题。
     * @return 用户确认的规范绝对路径；取消时为空。
     */
    private suspend fun chooseDirectory(title: String): Path? = withContext(Dispatchers.Swing) {
        val chooser = JFileChooser().apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
            isMultiSelectionEnabled = false
        }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile.toPath().toAbsolutePath().normalize()
        } else {
            null
        }
    }

    /**
     * 在 Swing 事件线程选择备份保存文件。
     *
     * @return 用户确认的规范绝对路径；取消时为空。
     */
    private suspend fun chooseBackupDestination(): Path? = withContext(Dispatchers.Swing) {
        val chooser = JFileChooser().apply {
            dialogTitle = "保存 Tagloom 密文备份"
            fileSelectionMode = JFileChooser.FILES_ONLY
            isAcceptAllFileFilterUsed = false
            fileFilter = FileNameExtensionFilter("Tagloom 备份 (*.tagloom-backup.zip)", "zip")
            selectedFile = java.io.File(DEFAULT_BACKUP_FILE_NAME)
        }
        if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
            normalizeBackupFileName(chooser.selectedFile.toPath())
        } else {
            null
        }
    }

    /**
     * 在 Swing 事件线程选择一个 Tagloom 密文备份。
     *
     * @return 用户确认的规范绝对路径；取消时为空。
     */
    private suspend fun chooseBackupArchive(): Path? = withContext(Dispatchers.Swing) {
        val chooser = JFileChooser().apply {
            dialogTitle = "选择 Tagloom 密文备份"
            fileSelectionMode = JFileChooser.FILES_ONLY
            isAcceptAllFileFilterUsed = false
            fileFilter = FileNameExtensionFilter("Tagloom 备份 (*.tagloom-backup.zip)", "zip")
        }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile.toPath().toAbsolutePath().normalize()
        } else {
            null
        }
    }

    /**
     * 为未输入约定后缀的备份目标补齐完整文件名。
     *
     * @param selected 用户选择路径。
     * @return 带 `.tagloom-backup.zip` 后缀的规范绝对路径。
     */
    private fun normalizeBackupFileName(selected: Path): Path {
        val normalized = selected.toAbsolutePath().normalize()
        return if (normalized.fileName.toString().endsWith(BACKUP_FILE_SUFFIX, ignoreCase = true)) {
            normalized
        } else {
            normalized.resolveSibling(normalized.fileName.toString() + BACKUP_FILE_SUFFIX)
        }
    }

    /**
     * 把桌面恢复状态投影为共享 UI 模型。
     *
     * @return 不含本机路径或密钥的打开结果。
     */
    private fun DesktopWorkspaceOpenState.toSharedResult(): WorkspaceOpenResult = WorkspaceOpenResult(
        notes = notes.map { note -> note.toSharedNote() },
        savedQueries = savedQueries.map { saved -> WorkspaceSavedQuery(saved.id, saved.snapshot.payload) },
        tagDefinitions = tagDefinitions.map { saved -> WorkspaceTagDefinition(saved.snapshot.payload) },
        propertyDefinitions = propertyDefinitions.map { saved -> WorkspacePropertyDefinition(saved.snapshot.payload) },
        readOnly = readOnly,
        recoveryMessages = recoveryReport.entries.map { entry ->
            "事务 ${entry.transactionId.value.take(8)}：${entry.action.name}"
        } + integrityIssues.map { issue ->
            "已隔离 ${issue.kind} 对象 ${issue.objectId.take(8)}：${issue.failure.name}"
        },
    )

    /**
     * 把已确认桌面 Note 投影为共享 UI 模型。
     *
     * @return 稳定 ID 与 payload。
     */
    private fun DesktopStoredNote.toSharedNote(): WorkspaceNote = WorkspaceNote(
        id = id,
        note = snapshot.payload,
        createdAt = snapshot.header.createdAt,
        updatedAt = snapshot.header.updatedAt,
    )

    private companion object {
        /** 备份保存对话框的默认脱敏文件名。 */
        const val DEFAULT_BACKUP_FILE_NAME: String = "tagloom-backup.tagloom-backup.zip"

        /** Tagloom 备份约定后缀。 */
        const val BACKUP_FILE_SUFFIX: String = ".tagloom-backup.zip"
    }
}
