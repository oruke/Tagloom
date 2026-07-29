package com.oruke.tagloom.core.sync

import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.ChangeId
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.ObjectId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** 定义冲突证据对应的业务字段类型。 */
@Serializable
enum class ConflictFieldKind {
    /** Note 标题。 */
    TITLE,

    /** Note 属性值。 */
    PROPERTY,

    /** Note 手动属性绑定。 */
    MANUAL_PROPERTY_BINDING,

    /** Note 属性样式覆盖。 */
    PROPERTY_STYLE,

    /** Note Block 或其 RichText。 */
    BLOCK,

    /** Note 加注。 */
    ANNOTATION,

    /** 合并后触发跨字段领域不变量。 */
    NOTE_INVARIANT,
}

/** 定义并发冲突中当前快照采用的临时胜者。 */
@Serializable
enum class MergeSide {
    /** 当前设备候选版本。 */
    LOCAL,

    /** 远端设备候选版本。 */
    REMOTE,
}

/** 定义冲突记录当前解决状态。 */
@Serializable
enum class ConflictStatus {
    /** 尚待用户确认。 */
    UNRESOLVED,

    /** 已通过后续普通 Change 解决。 */
    RESOLVED,
}

/**
 * 保存一次无法自动合并的 Base/Local/Remote 加密冲突证据。
 *
 * 该对象包含敏感正文或属性值，只能进入加密的 `history/conflicts`，不得写入日志或服务端公开元数据。
 */
@Serializable
data class ConflictRecord(
    /** 由对象、字段路径与两个 Change ID 确定性构成的冲突标识。 */
    val conflictId: String,
    /** 发生冲突的随机业务对象。 */
    val objectId: ObjectId,
    /** Block 级冲突的目标；对象字段冲突为空。 */
    val blockId: BlockId?,
    /** 冲突字段类型。 */
    val fieldKind: ConflictFieldKind,
    /** 不含标题或正文的稳定字段路径。 */
    val fieldPath: String,
    /** 共同祖先字段值；不存在时使用 JSON null。 */
    val baseValue: JsonElement,
    /** 当前设备字段值。 */
    val localValue: JsonElement,
    /** 远端设备字段值。 */
    val remoteValue: JsonElement,
    /** 当前权威快照暂时采用的一侧。 */
    val temporaryWinner: MergeSide,
    /** 当前设备候选 Change。 */
    val localChangeId: ChangeId,
    /** 远端设备候选 Change。 */
    val remoteChangeId: ChangeId,
    /** 冲突首次生成的 UTC ISO-8601 时间。 */
    val createdAt: String,
    /** 当前解决状态。 */
    val status: ConflictStatus = ConflictStatus.UNRESOLVED,
    /** 解决冲突的新 Change；未解决时为空。 */
    val resolutionChangeId: ChangeId? = null,
    /** 解决时间；未解决时为空。 */
    val resolvedAt: String? = null,
) {
    init {
        require(conflictId.isNotBlank()) { "冲突标识不能为空" }
        require(fieldPath.isNotBlank()) { "冲突字段路径不能为空" }
        require(createdAt.isNotBlank()) { "冲突创建时间不能为空" }
        require(
            status == ConflictStatus.UNRESOLVED || resolutionChangeId != null && !resolvedAt.isNullOrBlank(),
        ) { "已解决冲突必须记录解决 Change 与时间" }
    }
}

/** 描述 Note 候选状态及其 DAG head Change。 */
data class NoteMergeVersion(
    /** 候选 Note 状态。 */
    val note: Note,
    /** 产生该候选状态的 Change head。 */
    val change: ObjectChange,
)

/** 描述三方合并产生的临时权威 Note 与待解决冲突。 */
data class NoteMergeResult(
    /** 已自动合并或按确定性规则选择临时胜者的 Note。 */
    val note: Note,
    /** 永久保留败方内容的加密冲突证据。 */
    val conflicts: List<ConflictRecord>,
)
