package com.oruke.tagloom.platform

import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.SavedQuery
import com.oruke.tagloom.core.model.TagDefinition

/**
 * 表示共享 UI 可加载或保存的一篇工作区 Note。
 */
data class WorkspaceNote(
    /** 稳定 Note 对象 ID。 */
    val id: ObjectId,
    /** 当前已确认 Note payload。 */
    val note: Note,
    /** 权威 Note 快照的创建时间。 */
    val createdAt: String = "",
    /** 权威 Note 快照的最近更新时间。 */
    val updatedAt: String = "",
)

/**
 * 返回工作区打开后的活动 Note 与恢复状态。
 */
data class WorkspaceOpenResult(
    /** 当前全部未删除 Note。 */
    val notes: List<WorkspaceNote>,
    /** 当前全部未删除保存查询。 */
    val savedQueries: List<WorkspaceSavedQuery> = emptyList(),
    /** 当前全部未删除标签定义。 */
    val tagDefinitions: List<WorkspaceTagDefinition> = emptyList(),
    /** 当前全部未删除属性定义。 */
    val propertyDefinitions: List<WorkspacePropertyDefinition> = emptyList(),
    /** 无法自动证明新旧状态完整时为真。 */
    val readOnly: Boolean,
    /** 面向恢复界面的非敏感说明。 */
    val recoveryMessages: List<String> = emptyList(),
)

/**
 * 表示共享 UI 可运行的一个已确认保存查询。
 */
data class WorkspaceSavedQuery(
    /** 稳定保存查询对象 ID。 */
    val id: ObjectId,
    /** 当前已确认查询 payload。 */
    val query: SavedQuery,
)

/**
 * 表示共享 UI 可使用的一个已确认标签定义。
 */
data class WorkspaceTagDefinition(
    /** 标签定义完整 payload。 */
    val definition: TagDefinition,
)

/**
 * 表示共享 UI 可使用的一个已确认属性定义。
 */
data class WorkspacePropertyDefinition(
    /** 属性定义完整 payload。 */
    val definition: PropertyDefinition,
)

/**
 * 返回两阶段创建中只展示一次的 Recovery Key。
 */
data class WorkspacePreparedCreation(
    /** 带版本和校验码的 Recovery Key 文本。 */
    val recoveryKeyText: String,
)

/**
 * 返回主密钥轮换后的新会话与一次性 Recovery Key。
 */
data class WorkspaceMasterKeyRotationResult(
    /** 使用新主密钥重新打开的工作区。 */
    val openResult: WorkspaceOpenResult,
    /** 轮换后必须离线保存、只展示一次的新 Recovery Key。 */
    val recoveryKeyText: String,
)

/**
 * 描述一条加密派生索引命中。
 */
data class WorkspaceSearchHit(
    /** 命中 Note ID。 */
    val noteId: ObjectId,
    /** 命中标题。 */
    val title: String,
    /** 已截断且仅在解锁会话内显示的上下文片段。 */
    val snippet: String,
)

/**
 * 抽象共享 UI 所需的工作区创建、解锁、事务写入、搜索和锁定能力。
 *
 * 平台实现负责目录选择和真实适配器组装；无实现的平台不得在 UI 中展示这些能力。
 */
interface WorkspaceGateway {
    /**
     * 为共享编辑器生成 URL-safe 随机 ID。
     *
     * @param purpose 仅用于调用语义，不得进入返回 ID。
     * @return 不含标题、路径或设备信息的不透明 ID。
     */
    fun createOpaqueId(purpose: String): String

    /**
     * 返回当前 UTC ISO-8601 时间文本，供加注等草稿模型使用。
     *
     * @return UTC 时间文本。
     */
    fun currentUtcTimestamp(): String

    /**
     * 选择空目录并准备新工作区，确认前不得写入文件。
     *
     * @param password 用户初始密码。
     * @return 用户取消目录选择时为空，否则返回一次性 Recovery Key。
     */
    suspend fun prepareCreation(password: String): WorkspacePreparedCreation?

    /**
     * 在用户确认已离线保存 Recovery Key 后落盘并打开工作区。
     *
     * @return 已打开工作区。
     */
    suspend fun confirmCreation(): WorkspaceOpenResult

    /** 放弃待确认创建并清零尚未落盘的密钥。 */
    fun cancelCreation()

    /**
     * 选择已有工作区并使用密码解锁。
     *
     * @param password 本次输入密码。
     * @return 用户取消目录选择时为空。
     */
    suspend fun unlockWithPassword(password: String): WorkspaceOpenResult?

    /**
     * 选择已有工作区并使用 Recovery Key 解锁。
     *
     * @param recoveryKeyText 带版本和校验码的恢复文本。
     * @return 用户取消目录选择时为空。
     */
    suspend fun unlockWithRecovery(recoveryKeyText: String): WorkspaceOpenResult?

    /**
     * 选择备份文件目标并导出当前确认点的权威密文。
     *
     * @return 用户完成备份时为 true，取消文件选择时为 false。
     */
    suspend fun createBackup(): Boolean

    /**
     * 选择密文备份和空恢复目录，并使用原密码认证恢复结果。
     *
     * @param password 原工作区密码。
     * @return 用户取消任一步骤时为空，否则返回已打开工作区。
     */
    suspend fun restoreBackup(password: String): WorkspaceOpenResult?

    /**
     * 更换当前工作区密码，只重包 Workspace Master Key。
     *
     * @param oldPassword 当前密码。
     * @param newPassword 新密码。
     * @return 更新凭据后的当前打开状态。
     */
    suspend fun changePassword(
        oldPassword: String,
        newPassword: String,
    ): WorkspaceOpenResult

    /**
     * 轮换 Workspace Master Key 并重写全部权威信封。
     *
     * @param currentPassword 当前密码，用于显式重新认证。
     * @param newPassword 新主密钥密码槽使用的密码。
     * @return 新会话和只展示一次的新 Recovery Key。
     */
    suspend fun rotateMasterKey(
        currentPassword: String,
        newPassword: String,
    ): WorkspaceMasterKeyRotationResult

    /**
     * 通过统一事务入口创建或更新 Note。
     *
     * @param objectId 已持久化 Note ID；新草稿为空。
     * @param note 待保存完整 payload。
     * @return 新确认 Note 与最终稳定 ID。
     */
    suspend fun saveNote(
        objectId: ObjectId?,
        note: Note,
    ): WorkspaceNote

    /**
     * 把一篇已确认 Note 写为 tombstone。
     *
     * @param objectId 待删除 Note ID。
     */
    suspend fun deleteNote(objectId: ObjectId)

    /**
     * 查询已确认快照的派生索引。
     *
     * @param text 用户全文输入。
     * @return 稳定排序的命中。
     */
    suspend fun search(text: String): List<WorkspaceSearchHit>

    /**
     * 保存一个当前工作区查询。
     *
     * @param query 至少含一个查询条件的 payload。
     * @return 新确认保存查询。
     */
    suspend fun saveQuery(query: SavedQuery): WorkspaceSavedQuery

    /**
     * 创建或更新一个工作区标签定义。
     *
     * @param definition 包含稳定 ID 的完整标签定义。
     * @return 新确认标签定义。
     */
    suspend fun saveTagDefinition(definition: TagDefinition): WorkspaceTagDefinition

    /**
     * 创建或更新一个工作区属性定义。
     *
     * @param definition 包含稳定 ID 的完整属性定义。
     * @return 新确认属性定义。
     */
    suspend fun savePropertyDefinition(definition: PropertyDefinition): WorkspacePropertyDefinition

    /**
     * 运行一个已确认保存查询。
     *
     * @param objectId 保存查询对象 ID。
     * @return 稳定排序的命中。
     */
    suspend fun runSavedQuery(objectId: ObjectId): List<WorkspaceSearchHit>

    /** 关闭索引、销毁会话密钥并立即使全部旧调用失效。 */
    suspend fun lock()
}
