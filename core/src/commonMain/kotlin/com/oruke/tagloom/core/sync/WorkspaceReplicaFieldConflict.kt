package com.oruke.tagloom.core.sync

import com.oruke.tagloom.core.model.ChangeId
import com.oruke.tagloom.core.protocol.CanonicalJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement

/** 用字段名或稳定集合身份定位内容，禁止使用会随重排变化的数组下标。 */
@Serializable
data class ReplicaFieldPathSegment(
    /** 对象字段名；集合成员选择器时为空。 */
    val field: String? = null,
    /** 集合成员的稳定身份字段；普通字段段时为空。 */
    val identityField: String? = null,
    /** 集合成员的稳定身份；不使用标题或正文派生身份。 */
    val identityValue: String? = null,
) {
    init {
        require(field != null && identityField == null && identityValue == null ||
            field == null && identityField != null && identityValue != null) { "冲突路径段必须是字段或稳定身份选择器" }
    }
}

/** 保存无法自动合并的一个字段；Kotlin null 表示缺失，JsonNull 表示显式空值。 */
@Serializable
data class WorkspaceReplicaFieldConflict(
    /** 不依赖集合顺序的字段定位路径。 */
    val path: List<ReplicaFieldPathSegment>,
    /** 共同祖先字段；没有该字段时为空。 */
    @Serializable(with = ReplicaNullableJsonSerializer::class)
    val baseValue: JsonElement? = null,
    /** 稳定版本一字段；没有该字段时为空。 */
    @Serializable(with = ReplicaNullableJsonSerializer::class)
    val localValue: JsonElement? = null,
    /** 稳定版本二字段；没有该字段时为空。 */
    @Serializable(with = ReplicaNullableJsonSerializer::class)
    val remoteValue: JsonElement? = null,
    /** 自动合并结果在本字段采用的版本。 */
    val temporaryWinner: MergeSide,
) {
    /** 仅在加密历史及解锁会话内使用的稳定路径身份。 */
    val fieldId: String
        get() = CanonicalJson.encodeToString(ListSerializer(ReplicaFieldPathSegment.serializer()), path)
}

/** 记录一次字段选择产生的正式 Change；同字段的并发不同选择必须同时保留。 */
@Serializable
data class WorkspaceReplicaFieldResolution(
    /** 所处理字段的稳定路径身份。 */
    val fieldId: String,
    /** 用户选择的稳定版本。 */
    val selectedSide: MergeSide,
    /** 同事务创建并发布的正式 Change。 */
    val changeId: ChangeId,
    /** 本次处理的 UTC ISO-8601 时间。 */
    val resolvedAt: String,
) {
    init {
        require(fieldId.isNotBlank()) { "同步冲突处理字段身份不能为空" }
        require(resolvedAt.isNotBlank()) { "同步冲突处理时间不能为空" }
    }
}
