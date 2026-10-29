package com.oruke.tagloom.core.sync

import com.oruke.tagloom.core.model.ChangeId
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.ObjectSnapshot
import com.oruke.tagloom.core.workspace.ManifestObjectKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** 保存由调用方完成身份、摘要和 AEAD 验证的一份对象候选，不代表已经发布的本地状态。 */
@Serializable
data class WorkspaceReplicaObject(
    /** 该候选在 manifest 中的对象类别。 */
    val kind: ManifestObjectKind,
    /** 保留真实快照头的完整对象，payload 不复制为另一套模型。 */
    val snapshot: ObjectSnapshot<JsonElement>,
)

/** 区分本轮对象是否需要重新经过工作区事务确认。 */
enum class WorkspaceReplicaAction {
    /** 本地候选已覆盖远端，保持当前确认状态。 */
    KEEP_LOCAL,
    /** 本地缺少对象或远端为明确因果后继，采用远端完整候选。 */
    FAST_FORWARD,
    /** 两侧并发，调用方必须生成引用全部父 heads 的新 Merge Change。 */
    MERGE,
}

/** 表示完整候选为何需要保留给用户查看和恢复。 */
@Serializable
enum class WorkspaceReplicaConflictReason {
    /** 自动三方合并仍存在重叠字段，或非 Note 对象存在并发不同值。 */
    CONCURRENT_VALUE,
    /** 一侧删除对象而另一侧仍保留更新。 */
    DELETE_UPDATE,
    /** 无法从可认证历史恢复唯一共同祖先，禁止猜造三方基线。 */
    NO_COMMON_ANCESTOR,

    /** 单个对象的合法变更在合并后破坏跨对象目录不变量，成组保留一侧目录。 */
    CATALOG_INVARIANT,
}

/**
 * 保存完整双版本及可用共同基线，供加密冲突列表展示与后续普通事务恢复。
 *
 * 两侧包含敏感正文和属性，只允许进入工作区加密历史；规划器不写日志、文件或网络。
 */
@Serializable
data class WorkspaceReplicaConflict(
    /** 调用方以双方版本和对象身份生成的不透明稳定 ID。 */
    val conflictId: String,
    /** 冲突对象在 manifest 中的类别。 */
    val kind: ManifestObjectKind,
    /** 冲突对象的稳定随机身份。 */
    val objectId: ObjectId,
    /** 按稳定版本身份排序后的版本一；字段名保留协议兼容，不表示当前设备。 */
    val localSnapshot: ObjectSnapshot<JsonElement>,
    /** 按稳定版本身份排序后的版本二；字段名保留协议兼容，不表示远端设备。 */
    val remoteSnapshot: ObjectSnapshot<JsonElement>,
    /** 能从唯一共同祖先重放时的 payload；缺失时为空，不伪造快照头。 */
    val basePayload: JsonElement? = null,
    /** 当前合并结果采用的稳定版本一或版本二，LOCAL/REMOTE 分别对应两个协议字段。 */
    val temporaryWinner: MergeSide,
    /** 无法完全自动处理的稳定原因。 */
    val reason: WorkspaceReplicaConflictReason,
    /** 双方快照更新时间的较晚值，避免设备合并时钟令同一冲突产生不同历史。 */
    val createdAt: String,
    /** 当前冲突的处理状态，恢复后由正式事务标记。 */
    val status: ConflictStatus = ConflictStatus.UNRESOLVED,
    /** 用户恢复或确认产生的新 Change，未处理时为空。 */
    val resolutionChangeId: ChangeId? = null,
    /** 用户确认处理的 UTC ISO-8601 时间，未处理时为空。 */
    val resolvedAt: String? = null,
    /** 可独立处理的冲突字段；旧记录、无基线及目录整体约束冲突为空。 */
    val fields: List<WorkspaceReplicaFieldConflict> = emptyList(),
    /** 逐字段选择的正式 Change 证据；整体降级后仍保留，但不再提供独立字段操作。 */
    val fieldResolutions: List<WorkspaceReplicaFieldResolution> = emptyList(),
    /** 附加解释不一致时保守降级，后续交换不能重新把该历史解释为可独立字段恢复。 */
    val requiresWholeObjectResolution: Boolean = false,
) {
    init {
        require(conflictId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "同步冲突 ID 必须是不透明安全标识" }
        require(localSnapshot.header.objectId == objectId && remoteSnapshot.header.objectId == objectId) {
            "同步冲突候选对象身份不一致"
        }
        require(createdAt.isNotBlank()) { "同步冲突创建时间不能为空" }
        require(fields.map { it.fieldId }.distinct().size == fields.size) { "同步冲突字段身份重复" }
        require(fieldResolutions.map { it.fieldId to it.changeId }.distinct().size == fieldResolutions.size &&
            (fields.isEmpty() || fieldResolutions.all { resolution -> fields.any { it.fieldId == resolution.fieldId } })) { "同步冲突处理字段无效" }
        require(status == ConflictStatus.UNRESOLVED || resolutionChangeId != null && !resolvedAt.isNullOrBlank()) {
            "已处理同步冲突必须记录处理 Change 和时间"
        }
    }
}

/** 保存单个对象的纯规划结果，调用方必须在事务锁内重新验证本地 revision。 */
data class WorkspaceReplicaDecision(
    /** 对象在 manifest 中的类别。 */
    val kind: ManifestObjectKind,
    /** 对象稳定随机身份。 */
    val objectId: ObjectId,
    /** 是否保留本地、快进或生成 Merge Change。 */
    val action: WorkspaceReplicaAction,
    /** 候选模板；MERGE 的 revision、Change 和时间头由调用方正式生成。 */
    val snapshot: ObjectSnapshot<JsonElement>,
    /** 必须由新 Merge Change 引用的稳定排序父 heads。 */
    val parentChangeIds: List<ChangeId>,
    /** 应与新权威快照在同一事务确认的完整冲突证据。 */
    val conflicts: List<WorkspaceReplicaConflict> = emptyList(),
)

/** 汇总按类别和对象身份稳定排序的工作区合并决定，不执行任何持久化。 */
data class WorkspaceReplicaPlan(
    /** 包含本地与远端对象并集的逐对象决定。 */
    val objects: List<WorkspaceReplicaDecision>,
) {
    /** 本轮必须保留的全部完整冲突，顺序与对象决定一致。 */
    val conflicts: List<WorkspaceReplicaConflict>
        get() = objects.flatMap(WorkspaceReplicaDecision::conflicts)
}
