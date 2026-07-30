package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.ObjectSnapshot
import com.oruke.tagloom.core.model.SavedQuery
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.WorkspaceId
import com.oruke.tagloom.core.model.KeyId
import com.oruke.tagloom.core.model.TransactionId
import com.oruke.tagloom.core.crypto.Argon2idParameters
import com.oruke.tagloom.core.transaction.TransactionRecoveryReport
import com.oruke.tagloom.core.workspace.ManifestObjectKind

/**
 * 向创建界面返回一次性 Recovery Key 和待确认工作区身份。
 */
data class PreparedDesktopWorkspaceCreation(
    /** 新工作区随机 ID；仅用于界面核对，不含本地路径。 */
    val workspaceId: WorkspaceId,
    /** 只展示一次、带版本和校验码的 Recovery Key 文本。 */
    val recoveryKeyText: String,
)

/**
 * 表示桌面工作区当前可编辑的一篇已确认 Note。
 */
data class DesktopStoredNote(
    /** 权威 Note 快照。 */
    val snapshot: ObjectSnapshot<Note>,
) {
    /** 便于 UI 选择文档的稳定对象 ID。 */
    val id: ObjectId
        get() = snapshot.header.objectId
}

/**
 * 表示桌面工作区当前活动的已确认 SavedQuery。
 */
data class DesktopStoredSavedQuery(
    /** 权威 SavedQuery 快照。 */
    val snapshot: ObjectSnapshot<SavedQuery>,
) {
    /** 保存查询稳定对象 ID。 */
    val id: ObjectId
        get() = snapshot.header.objectId
}

/**
 * 表示桌面工作区当前活动的已确认 TagDefinition。
 */
data class DesktopStoredTagDefinition(
    /** 权威 TagDefinition 快照。 */
    val snapshot: ObjectSnapshot<TagDefinition>,
) {
    /** 标签稳定对象 ID。 */
    val id: ObjectId
        get() = snapshot.header.objectId
}

/**
 * 表示桌面工作区当前活动的已确认 PropertyDefinition。
 */
data class DesktopStoredPropertyDefinition(
    /** 权威 PropertyDefinition 快照。 */
    val snapshot: ObjectSnapshot<PropertyDefinition>,
) {
    /** 属性定义稳定对象 ID。 */
    val id: ObjectId
        get() = snapshot.header.objectId
}

/**
 * 区分权威对象被逻辑隔离的脱敏原因。
 */
enum class DesktopIntegrityFailure {
    /** manifest 引用文件不存在或不是普通文件。 */
    MISSING_FILE,

    /** 密文字节摘要与 manifest 不一致。 */
    HASH_MISMATCH,

    /** 信封、AEAD 或快照 schema 认证失败。 */
    AUTHENTICATION_FAILED,
}

/**
 * 描述一个不会包含标题或正文的对象完整性问题。
 */
data class DesktopObjectIntegrityIssue(
    /** manifest 对象类别。 */
    val kind: ManifestObjectKind,
    /** 不透明对象 ID。 */
    val objectId: String,
    /** 逻辑隔离原因。 */
    val failure: DesktopIntegrityFailure,
)

/**
 * 保存一次主密钥轮换所需的新密钥身份与密码槽参数。
 */
data class DesktopMasterKeyRotationRequest(
    /** 新 Workspace Master Key 版本 ID。 */
    val newKeyId: KeyId,
    /** 轮换后的工作区密码。 */
    val newPassword: String,
    /** 新密码槽 KDF 参数。 */
    val newParameters: Argon2idParameters,
    /** 新密码槽 ID。 */
    val passwordSlotId: String,
    /** 新 Recovery Key 槽 ID。 */
    val recoverySlotId: String,
    /** 轮换事务 ID。 */
    val transactionId: TransactionId,
)

/**
 * 返回主密钥轮换后的打开状态和只展示一次的新 Recovery Key。
 */
data class DesktopMasterKeyRotationOpenResult(
    /** 使用新主密钥重新打开的工作区。 */
    val openState: DesktopWorkspaceOpenState,
    /** 带版本与校验码的新 Recovery Key 文本。 */
    val recoveryKeyText: String,
)

/**
 * 描述桌面会话打开时的恢复结果和只读状态。
 */
data class DesktopWorkspaceOpenState(
    /** 当前已确认且未删除的 Note。 */
    val notes: List<DesktopStoredNote>,
    /** 当前已确认且未删除的保存查询。 */
    val savedQueries: List<DesktopStoredSavedQuery>,
    /** 当前已确认且未删除的标签定义。 */
    val tagDefinitions: List<DesktopStoredTagDefinition>,
    /** 当前已确认且未删除的属性定义。 */
    val propertyDefinitions: List<DesktopStoredPropertyDefinition>,
    /** 已从读取和索引中隔离的损坏对象。 */
    val integrityIssues: List<DesktopObjectIntegrityIssue>,
    /** 启动时处理 prepared 事务的恢复报告。 */
    val recoveryReport: TransactionRecoveryReport,
    /** 无法证明新旧状态完整时为真，界面必须禁用写入。 */
    val readOnly: Boolean,
)
