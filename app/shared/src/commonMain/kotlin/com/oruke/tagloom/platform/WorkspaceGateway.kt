package com.oruke.tagloom.platform

import com.oruke.tagloom.core.model.DatabaseDefinition
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.ObjectRevision
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.SavedQuery
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.core.model.ViewDefinition
import com.oruke.tagloom.core.transaction.TransactionRecoveryAction
import com.oruke.tagloom.core.workspace.ManifestObjectKind

/**
 * 表示共享 UI 可加载或保存的一篇工作区 Note。
 */
data class WorkspaceNote(
    /** 稳定 Note 对象 ID。 */
    val id: ObjectId,
    /** 当前已确认 Note payload。 */
    val note: Note,
    /** 当前权威 revision，用于乐观并发校验。 */
    val revision: ObjectRevision? = null,
    /** 权威 Note 快照的创建时间。 */
    val createdAt: String = "",
    /** 权威 Note 快照的最近更新时间。 */
    val updatedAt: String = "",
)

/**
 * 描述恢复页面可展示的脱敏状态，而不在平台层拼接自然语言。
 *
 * 共享 UI 根据当前 Locale 渲染文本，平台层只传递事务、对象类别和失败原因等稳定语义，
 * 从而避免中文或英文文案被固化到桌面文件系统实现中。
 */
sealed interface WorkspaceRecoveryMessage {
    /**
     * 描述启动时处理的一条 prepared 事务。
     */
    data class RecoveredTransaction(
        /** 仅用于用户核对的事务 ID 前缀，不暴露完整恢复记录。 */
        val transactionIdPrefix: String,
        /** 该事务最终采取的恢复动作。 */
        val action: TransactionRecoveryAction,
    ) : WorkspaceRecoveryMessage

    /**
     * 描述已从读取与索引中逻辑隔离的权威对象。
     */
    data class IsolatedObject(
        /** 被隔离对象在 manifest 中的协议类别。 */
        val kind: ManifestObjectKind,
        /** 仅用于用户核对的不透明对象 ID 前缀。 */
        val objectIdPrefix: String,
        /** 导致对象被隔离的脱敏完整性失败分类。 */
        val failure: WorkspaceIntegrityFailure,
    ) : WorkspaceRecoveryMessage
}

/**
 * 区分共享恢复界面可本地化展示的对象完整性失败。
 */
enum class WorkspaceIntegrityFailure {
    /** manifest 引用的密文文件不存在或不是普通文件。 */
    MISSING_FILE,

    /** 密文文件摘要与已认证 manifest 记录不一致。 */
    HASH_MISMATCH,

    /** 密文信封、AEAD 或快照 schema 无法通过认证。 */
    AUTHENTICATION_FAILED,
}

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
    /** 当前全部未删除 Database 定义。 */
    val databaseDefinitions: List<WorkspaceDatabaseDefinition> = emptyList(),
    /** 当前全部未删除 View 定义。 */
    val viewDefinitions: List<WorkspaceViewDefinition> = emptyList(),
    /** 无法自动证明新旧状态完整时为真。 */
    val readOnly: Boolean,
    /** 面向恢复界面的脱敏语义说明。 */
    val recoveryMessages: List<WorkspaceRecoveryMessage> = emptyList(),
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

/** 表示共享 UI 可使用的已确认 DatabaseDefinition。 */
data class WorkspaceDatabaseDefinition(
    /** Database 权威完整定义。 */
    val definition: DatabaseDefinition,
)

/** 表示共享 UI 可使用的已确认 ViewDefinition。 */
data class WorkspaceViewDefinition(
    /** View 权威完整定义。 */
    val definition: ViewDefinition,
)

/** 表示 Database 与首个 View 的原子创建结果。 */
data class WorkspaceDatabaseCatalog(
    /** 新确认 Database。 */
    val database: WorkspaceDatabaseDefinition,
    /** 新确认首个 View。 */
    val initialView: WorkspaceViewDefinition,
)

/**
 * 返回两阶段创建中只展示一次的 Recovery Key。
 */
data class WorkspacePreparedCreation(
    /** 带版本和校验码的 Recovery Key 文本。 */
    val recoveryKeyText: String,
)

/**
 * 描述本机最近登记工作区的安全展示信息。
 *
 * 该模型故意不携带绝对路径、Recovery Key、PIN 或工作区主密钥，避免共享 UI 把本机访问
 * 配置当成工作区权威数据。
 */
data class WorkspaceRecentWorkspace(
    /** 用户可见的工作区目录名称。 */
    val displayName: String,
    /** 当前设备是否已启用 PIN 保护。 */
    val requiresPin: Boolean,
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
 * 抽象共享 UI 所需的工作区创建、解锁、事务写入和锁定能力。
 *
 * 平台实现负责位置选择和真实能力组装；无实现的平台不得在 UI 中展示这些能力。
 */
interface WorkspaceGateway {
    /** 当前平台已装配的链接创建与网页信息获取应用服务；缺少实现时不展示录入入口。 */
    val links: WorkspaceLinkGateway? get() = null
    /** 外部确认写入后的刷新通知，不携带笔记内容；未实现平台不产生通知。 */
    val externalChanges: kotlinx.coroutines.flow.Flow<Unit> get() = kotlinx.coroutines.flow.emptyFlow()

    /**
     * 重新读取当前已确认工作区，不改变解锁或恢复状态。
     * @return 当前状态；没有对应平台能力时为空。
     */
    suspend fun reload(): WorkspaceOpenResult? = null

    /** 当前平台真实接入的服务器恢复能力；未提供实现的平台不展示入口。 */
    val serverRecovery: WorkspaceServerRecoveryGateway? get() = null

    /** 当前平台接入的 Agent 与模型配置及本机隔离凭据网关；未提供实现的平台不展示入口。 */
    val agentConfig: WorkspaceAgentConfigGateway? get() = null

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
     * 读取本机最近登记的默认工作区。
     *
     * @return 尚未在本机登记工作区时为空；结果不包含绝对路径或任何解锁材料。
     */
    suspend fun recentWorkspaceOrNull(): WorkspaceRecentWorkspace?

    /**
     * 使用当前设备的系统保护材料打开最近登记工作区。
     *
     * 该入口只适用于未启用 PIN 的本机登记；平台必须拒绝把它用于 PIN 模式，避免 UI
     * 误把“自动打开”降级为绕过本机访问保护。
     *
     * @return 已认证的工作区打开结果。
     */
    suspend fun openRecentWorkspace(): WorkspaceOpenResult

    /**
     * 使用 PIN 解锁本机最近登记工作区。
     *
     * @param pin 用户输入的本机 PIN；平台负责解开本机高熵材料后认证工作区。
     * @return 已认证的工作区打开结果。
     */
    suspend fun unlockRecentWorkspace(pin: String): WorkspaceOpenResult

    /**
     * 选择空目录并准备新工作区，确认前不得写入文件。
     *
     * 平台会生成不会展示给用户的高熵解锁材料；用户可选择用 PIN 或设备系统保护机制
     * 保护其本机副本。
     *
     * @param pin 用户为本机访问记录设置的可选 PIN；为空时不启用 PIN。
     * @return 用户取消目录选择时为空，否则返回一次性 Recovery Key。
     */
    suspend fun prepareCreation(pin: String?): WorkspacePreparedCreation?

    /**
     * 在用户确认已离线保存 Recovery Key 后落盘并打开工作区。
     *
     * @return 已打开工作区。
     */
    suspend fun confirmCreation(): WorkspaceOpenResult

    /** 放弃待确认创建并清零尚未落盘的密钥。 */
    fun cancelCreation()

    /**
     * 选择已有工作区，用 Recovery Key 认证并为当前设备建立新的本机解锁入口。
     *
     * @param recoveryKeyText 带版本和校验码的 Recovery Key。
     * @param pin 用户为当前设备设置的可选 PIN；为空时不启用 PIN。
     * @return 用户取消目录选择时为空。
     */
    suspend fun unlockWithRecovery(
        recoveryKeyText: String,
        pin: String?,
    ): WorkspaceOpenResult?

    /**
     * 选择备份文件目标并导出当前确认点的权威密文。
     *
     * @return 用户完成备份时为 true，取消文件选择时为 false。
     */
    suspend fun createBackup(): Boolean

    /**
     * 选择密文备份和空恢复目录，用 Recovery Key 认证并建立当前设备的本机入口。
     *
     * @param recoveryKeyText 带版本和校验码的 Recovery Key。
     * @param pin 用户为恢复后本机入口设置的可选 PIN；为空时不启用 PIN。
     * @return 用户取消任一步骤时为空，否则返回已打开工作区。
     */
    suspend fun restoreBackup(
        recoveryKeyText: String,
        pin: String?,
    ): WorkspaceOpenResult?

    /**
     * 为当前设备系统保护的本机解锁材料启用 PIN。
     *
     * @param pin 用户设置的 6 至 12 位数字 PIN。
     */
    suspend fun enablePin(pin: String)

    /**
     * 更换当前设备 PIN，不修改工作区 manifest、对象信封或 Recovery Key。
     *
     * @param currentPin 当前本机 PIN。
     * @param newPin 新本机 PIN。
     */
    suspend fun changePin(
        currentPin: String,
        newPin: String,
    )

    /**
     * 认证当前 PIN 后停用 PIN，改回当前设备系统保护的自动打开模式。
     *
     * @param currentPin 当前本机 PIN。
     */
    suspend fun disablePin(currentPin: String)

    /**
     * 轮换 Workspace Master Key 并重写全部权威信封。
     *
     * 平台使用当前本机访问模式重新取得高熵材料；PIN 本身不进入工作区 manifest。
     *
     * @param currentPin 当前本机 PIN；未启用 PIN 时为空。
     * @return 新会话和只展示一次的新 Recovery Key。
     */
    suspend fun rotateMasterKey(currentPin: String?): WorkspaceMasterKeyRotationResult

    /**
     * 通过统一事务入口创建或更新 Note。
     *
     * @param objectId 已持久化 Note ID；新草稿为空。
     * @param note 待保存完整 payload。
     * @param expectedRevision 调用方读取时的权威 revision；创建时为空。
     * @return 新确认 Note 与最终稳定 ID。
     */
    suspend fun saveNote(
        objectId: ObjectId?,
        note: Note,
        expectedRevision: ObjectRevision? = null,
    ): WorkspaceNote

    /**
     * 把一篇已确认 Note 写为 tombstone。
     *
     * @param objectId 待删除 Note ID。
     */
    suspend fun deleteNote(objectId: ObjectId)

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
     * 删除未被引用的超级标签，平台必须在统一写入锁内复核引用并写 tombstone。
     * @param tagId 待删除的稳定超级标签 ID。
     */
    suspend fun deleteTagDefinition(tagId: com.oruke.tagloom.core.model.TagId) {
        error("supertag-deletion-unavailable")
    }

    /**
     * 创建或更新一个工作区属性定义。
     *
     * @param definition 包含稳定 ID 的完整属性定义。
     * @return 新确认属性定义。
     */
    suspend fun savePropertyDefinition(definition: PropertyDefinition): WorkspacePropertyDefinition

    /**
     * 原子创建 Database 与首个 View。
     *
     * @param database Database 完整定义。
     * @param initialView 首个 View 完整定义。
     * @return 单一事务确认的目录对象。
     */
    suspend fun createDatabase(
        database: DatabaseDefinition,
        initialView: ViewDefinition,
    ): WorkspaceDatabaseCatalog

    /**
     * 更新一个已确认 DatabaseDefinition。
     *
     * @param definition 新完整定义。
     * @return 新确认 Database。
     */
    suspend fun saveDatabaseDefinition(definition: DatabaseDefinition): WorkspaceDatabaseDefinition

    /**
     * 在已确认 Database 下创建一个新 ViewDefinition。
     *
     * @param definition 新视图完整定义。
     * @return 新确认 View。
     */
    suspend fun createViewDefinition(definition: ViewDefinition): WorkspaceViewDefinition

    /**
     * 更新一个已确认 ViewDefinition。
     *
     * @param definition 新完整定义。
     * @return 新确认 View。
     */
    suspend fun saveViewDefinition(definition: ViewDefinition): WorkspaceViewDefinition

    /** 关闭索引、销毁会话密钥并立即使全部旧调用失效。 */
    suspend fun lock()
}
