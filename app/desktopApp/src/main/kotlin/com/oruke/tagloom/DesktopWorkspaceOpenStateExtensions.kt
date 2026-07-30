package com.oruke.tagloom

import com.oruke.tagloom.platform.WorkspaceIntegrityFailure
import com.oruke.tagloom.platform.WorkspaceNote
import com.oruke.tagloom.platform.WorkspaceOpenResult
import com.oruke.tagloom.platform.WorkspacePropertyDefinition
import com.oruke.tagloom.platform.WorkspaceRecoveryMessage
import com.oruke.tagloom.platform.WorkspaceSavedQuery
import com.oruke.tagloom.platform.WorkspaceTagDefinition
import com.oruke.tagloom.workspace.DesktopIntegrityFailure
import com.oruke.tagloom.workspace.DesktopStoredNote
import com.oruke.tagloom.workspace.DesktopWorkspaceOpenState

/**
 * 将桌面工作区打开状态转换为共享 UI 使用的跨平台模型。
 *
 * 转换过程只保留可安全展示的对象 ID 前缀和恢复状态语义；自然语言由共享 UI 在当前 Locale
 * 下生成，避免桌面适配器把任何语言的文案写死到平台状态中。
 *
 * @return 不含本机路径、PIN、Recovery Key 或完整对象标识的共享打开结果。
 */
internal fun DesktopWorkspaceOpenState.toSharedWorkspaceOpenResult(): WorkspaceOpenResult = WorkspaceOpenResult(
    notes = notes.map { note -> note.toSharedWorkspaceNote() },
    savedQueries = savedQueries.map { saved -> WorkspaceSavedQuery(saved.id, saved.snapshot.payload) },
    tagDefinitions = tagDefinitions.map { saved -> WorkspaceTagDefinition(saved.snapshot.payload) },
    propertyDefinitions = propertyDefinitions.map { saved -> WorkspacePropertyDefinition(saved.snapshot.payload) },
    readOnly = readOnly,
    recoveryMessages = recoveryReport.entries.map { entry ->
        WorkspaceRecoveryMessage.RecoveredTransaction(
            transactionIdPrefix = entry.transactionId.value.take(8),
            action = entry.action,
        )
    } + integrityIssues.map { issue ->
        WorkspaceRecoveryMessage.IsolatedObject(
            kind = issue.kind,
            objectIdPrefix = issue.objectId.take(8),
            failure = issue.failure.toWorkspaceIntegrityFailure(),
        )
    },
)

/**
 * 将桌面已确认 Note 投影为共享编辑器状态。
 *
 * @return 带稳定 ID、payload 和权威时间戳的共享 Note。
 */
internal fun DesktopStoredNote.toSharedWorkspaceNote(): WorkspaceNote = WorkspaceNote(
    id = id,
    note = snapshot.payload,
    createdAt = snapshot.header.createdAt,
    updatedAt = snapshot.header.updatedAt,
)

/**
 * 将桌面文件读取阶段的完整性失败映射为跨平台恢复界面可展示的语义。
 *
 * @return 与当前桌面失败原因一一对应的共享失败分类。
 */
private fun DesktopIntegrityFailure.toWorkspaceIntegrityFailure(): WorkspaceIntegrityFailure = when (this) {
    DesktopIntegrityFailure.MISSING_FILE -> WorkspaceIntegrityFailure.MISSING_FILE
    DesktopIntegrityFailure.HASH_MISMATCH -> WorkspaceIntegrityFailure.HASH_MISMATCH
    DesktopIntegrityFailure.AUTHENTICATION_FAILED -> WorkspaceIntegrityFailure.AUTHENTICATION_FAILED
}
