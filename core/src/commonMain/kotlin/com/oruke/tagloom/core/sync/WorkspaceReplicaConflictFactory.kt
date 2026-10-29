package com.oruke.tagloom.core.sync

import com.oruke.tagloom.core.model.ChangeId
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.ObjectSnapshot
import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.workspace.ManifestObjectKind
import kotlin.time.Instant
import kotlinx.serialization.json.JsonElement

/** 为普通对象冲突和成组目录冲突生成同一套稳定的完整版本证据。 */
internal object WorkspaceReplicaConflictFactory {
    /**
     * 将设备方向规范化为稳定版本一、版本二，避免同一冲突在两个设备生成不同历史内容。
     * @param kind 冲突对象类别。
     * @param local 本轮本地完整候选。
     * @param remote 本轮远端完整候选。
     * @param basePayload 可认证的真实共同基线，缺失时为空。
     * @param winner 本轮输入方向下的临时胜者。
     * @param reason 冲突稳定分类。
     * @param conflictIdFactory 按稳定双方版本生成不透明 ID 的工厂。
     * @param fields 本轮输入方向下可独立恢复的字段证据。
     * @return 不依赖设备方向和本轮合并时钟的完整冲突。
     */
    fun create(
        kind: ManifestObjectKind,
        local: ObjectSnapshot<JsonElement>,
        remote: ObjectSnapshot<JsonElement>,
        basePayload: JsonElement?,
        winner: MergeSide,
        reason: WorkspaceReplicaConflictReason,
        conflictIdFactory: (ManifestObjectKind, ObjectId, ChangeId?, ChangeId?) -> String,
        fields: List<WorkspaceReplicaFieldConflict> = emptyList(),
    ): WorkspaceReplicaConflict {
        val localFirst = compareSnapshotVersions(local, remote) <= 0
        val versionOne = if (localFirst) local else remote
        val versionTwo = if (localFirst) remote else local
        val stableWinner = if (localFirst) winner else if (winner == MergeSide.LOCAL) MergeSide.REMOTE else MergeSide.LOCAL
        val createdAt = listOf(local.header.updatedAt, remote.header.updatedAt)
            .maxWith(compareBy<String> { Instant.parse(it) }.thenBy { it })
        return WorkspaceReplicaConflict(
            conflictIdFactory(kind, local.header.objectId, versionOne.header.lastChangeId, versionTwo.header.lastChangeId),
            kind, local.header.objectId, versionOne, versionTwo, basePayload, stableWinner, reason, createdAt,
            fields = fields.map { field ->
                if (localFirst) field else field.copy(localValue = field.remoteValue, remoteValue = field.localValue,
                    temporaryWinner = if (field.temporaryWinner == MergeSide.LOCAL) MergeSide.REMOTE else MergeSide.LOCAL)
            }.sortedBy { it.fieldId },
        )
    }

    /**
     * 按逻辑 head、revision 和完整业务内容排列版本，当前设备方向不参与历史身份。
     * @param left 第一个认证快照。
     * @param right 第二个认证快照。
     * @return 负数表示左侧为版本一，正数表示右侧为版本一。
     */
    private fun compareSnapshotVersions(left: ObjectSnapshot<JsonElement>, right: ObjectSnapshot<JsonElement>): Int =
        compareValuesBy(left, right, { it.header.lastChangeId?.value.orEmpty() }, { it.header.revision.actorId.value },
            { it.header.revision.sequence }, { it.header.deleted },
            { CanonicalJson.encodeToString(JsonElement.serializer(), it.payload) })
}
