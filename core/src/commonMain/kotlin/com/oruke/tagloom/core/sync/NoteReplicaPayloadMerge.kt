package com.oruke.tagloom.core.sync

import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.NoteValidator
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.protocol.CanonicalJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** 将 Note 专用合并证据转换为稳定 JSON 路径，补齐旧合并器尚未覆盖的扩展字段。 */
internal object NoteReplicaPayloadMerge {
    /** Note 当前已知的顶层协议字段；未知扩展单独递归合并，不能覆盖正文专用算法的结果。 */
    private val knownNoteFields = setOf("title", "directTagIds", "propertyValues", "manualPropertyBindings",
        "propertyStyleOverrides", "blocks", "defaultTabId", "annotations", "linkMetadata", "aiAccessPolicy")

    /**
     * 按已认证基线合并 Note，并把需要人工处理的最小安全范围定位到字段或完整 Block。
     * @param objectId 当前 Note 的稳定身份。
     * @param basePayload 真实共同祖先 payload。
     * @param localPayload 本轮本地候选 payload。
     * @param remotePayload 本轮远端候选 payload。
     * @param localChange 本地候选 head。
     * @param remoteChange 远端候选 head。
     * @param history 同一对象已认证的 Change 集合。
     * @param mergedAt 本轮 UTC 合并时间。
     * @param winner 对象级确定性临时胜者。
     * @return 完整合并 payload、字段证据及是否需要保留完整冲突历史。
     */
    fun merge(
        objectId: ObjectId,
        basePayload: JsonElement,
        localPayload: JsonElement,
        remotePayload: JsonElement,
        localChange: ObjectChange,
        remoteChange: ObjectChange,
        history: Collection<ObjectChange>,
        mergedAt: String,
        winner: MergeSide,
    ): NoteReplicaPayloadMergeResult {
        val base = decode(basePayload)
        val local = decode(localPayload)
        val remote = decode(remotePayload)
        val merge = NoteThreeWayMergeService.merge(objectId, base, NoteMergeVersion(local, localChange),
            NoteMergeVersion(remote, remoteChange), history, mergedAt)
        // 跨字段不变量冲突无法逐项安全解释，必须保留整对象恢复，而不是伪造可独立选择的路径。
        if (merge.conflicts.any { it.fieldKind == ConflictFieldKind.NOTE_INVARIANT }) {
            return NoteReplicaPayloadMergeResult(if (winner == MergeSide.LOCAL) localPayload else remotePayload,
                emptyList(), conflicted = true)
        }
        val fields = merge.conflicts.map { conflict ->
            evidence(path(conflict), conflict.temporaryWinner, basePayload, localPayload, remotePayload)
        }.toMutableList()

        // defaultTabId 与网页来源记录晚于旧 Note 合并器，分别三方比较，避免无关写入时清空或回退。
        val tab = ThreeWayValueMerge.merge(base.defaultTabId, local.defaultTabId, remote.defaultTabId)
        val tabWinner = SyncWinnerResolver.concurrentWinner(localChange, remoteChange,
            local.defaultTabId == null, remote.defaultTabId == null)
        val defaultTabId = if (!tab.conflicted) tab.value else {
            fields += evidence(listOf(ReplicaFieldPathSegment(field = "defaultTabId")), tabWinner,
                basePayload, localPayload, remotePayload)
            if (tabWinner == MergeSide.LOCAL) local.defaultTabId else remote.defaultTabId
        }
        val metadata = ThreeWayValueMerge.merge(base.linkMetadata, local.linkMetadata, remote.linkMetadata)
        val metadataWinner = SyncWinnerResolver.concurrentWinner(localChange, remoteChange,
            local.linkMetadata == null, remote.linkMetadata == null)
        val linkMetadata = if (!metadata.conflicted) metadata.value else {
            fields += evidence(listOf(ReplicaFieldPathSegment(field = "linkMetadata")), metadataWinner,
                basePayload, localPayload, remotePayload)
            if (metadataWinner == MergeSide.LOCAL) local.linkMetadata else remote.linkMetadata
        }
        val policy = ThreeWayValueMerge.merge(base.aiAccessPolicy, local.aiAccessPolicy, remote.aiAccessPolicy)
        val policyWinner = SyncWinnerResolver.concurrentWinner(localChange, remoteChange,
            local.aiAccessPolicy == null, remote.aiAccessPolicy == null)
        val aiAccessPolicy = if (!policy.conflicted) policy.value else {
            fields += evidence(listOf(ReplicaFieldPathSegment(field = "aiAccessPolicy")), policyWinner,
                basePayload, localPayload, remotePayload)
            if (policyWinner == MergeSide.LOCAL) local.aiAccessPolicy else remote.aiAccessPolicy
        }
        val mergedNote = merge.note.copy(
            defaultTabId = defaultTabId,
            linkMetadata = linkMetadata,
            aiAccessPolicy = aiAccessPolicy,
        )
        if (NoteValidator.validate(mergedNote).isNotEmpty()) {
            return NoteReplicaPayloadMergeResult(if (winner == MergeSide.LOCAL) localPayload else remotePayload,
                emptyList(), conflicted = true)
        }
        val unknown = ReplicaJsonFieldMerge.merge(com.oruke.tagloom.core.workspace.ManifestObjectKind.NOTE,
            unknownFields(basePayload), unknownFields(localPayload), unknownFields(remotePayload), winner)
        fields += unknown.fields
        val payload = JsonObject(unknown.payload.jsonObject +
            CanonicalJson.format.encodeToJsonElement(Note.serializer(), mergedNote).jsonObject)
        return NoteReplicaPayloadMergeResult(payload, fields.distinctBy { it.fieldId }.sortedBy { it.fieldId },
            conflicted = fields.isNotEmpty())
    }

    /**
     * 把旧 Note 合并器的业务字段路径转换为不依赖数组顺序的协议路径。
     * @param conflict 已确认不属于整对象不变量冲突的字段证据。
     * @return 精确字段、集合成员或完整 Block 的稳定定位路径。
     */
    private fun path(conflict: ConflictRecord): List<ReplicaFieldPathSegment> = when (conflict.fieldKind) {
        ConflictFieldKind.TITLE -> listOf(ReplicaFieldPathSegment(field = "title"))
        ConflictFieldKind.PROPERTY -> listOf(ReplicaFieldPathSegment(field = "propertyValues"),
            ReplicaFieldPathSegment(field = conflict.fieldPath.removePrefix("property:")))
        ConflictFieldKind.MANUAL_PROPERTY_BINDING -> listOf(ReplicaFieldPathSegment(field = "manualPropertyBindings"),
            ReplicaFieldPathSegment(identityField = "propertyId", identityValue = conflict.fieldPath.removePrefix("manual-property:")))
        ConflictFieldKind.PROPERTY_STYLE -> listOf(ReplicaFieldPathSegment(field = "propertyStyleOverrides"),
            ReplicaFieldPathSegment(field = conflict.fieldPath.removePrefix("property-style:")))
        ConflictFieldKind.BLOCK -> listOf(ReplicaFieldPathSegment(field = "blocks"),
            ReplicaFieldPathSegment(identityField = "id", identityValue = requireNotNull(conflict.blockId).value))
        ConflictFieldKind.ANNOTATION -> listOf(ReplicaFieldPathSegment(field = "annotations"),
            ReplicaFieldPathSegment(field = conflict.fieldPath.removePrefix("annotation:")))
        ConflictFieldKind.NOTE_INVARIANT -> error("整对象冲突不应转换为字段路径")
    }

    /**
     * 从真实原始 payload 读取冲突值，使字段缺失与显式 JsonNull 保持不同语义。
     * @param path 稳定字段定位路径。
     * @param winner 本轮输入方向下的字段临时胜者。
     * @param base 共同祖先 payload。
     * @param local 本地候选 payload。
     * @param remote 远端候选 payload。
     * @return 保留全部三方字段值的证据。
     */
    private fun evidence(path: List<ReplicaFieldPathSegment>, winner: MergeSide,
        base: JsonElement, local: JsonElement, remote: JsonElement): WorkspaceReplicaFieldConflict =
        WorkspaceReplicaFieldConflict(path, ReplicaJsonFieldMerge.read(base, path),
            ReplicaJsonFieldMerge.read(local, path), ReplicaJsonFieldMerge.read(remote, path), winner)

    /**
     * 解码已认证的完整 Note payload，不访问存储或密钥。
     * @param payload 待解码的完整 Note JSON。
     * @return 领域 Note。
     */
    private fun decode(payload: JsonElement): Note = CanonicalJson.format.decodeFromJsonElement(Note.serializer(), payload)

    /**
     * 提取旧客户端尚不认识的 Note 顶层扩展，保持字段值原样并单独三方合并。
     * @param payload 已认证且可解码为 Note 的原始 JSON。
     * @return 不含已知领域字段的扩展对象。
     */
    private fun unknownFields(payload: JsonElement): JsonObject = JsonObject(payload.jsonObject.filterKeys { it !in knownNoteFields })
}

/** 保存 Note 合并候选及可独立处理的字段；整对象不变量冲突不提供字段路径。 */
internal data class NoteReplicaPayloadMergeResult(
    /** 已合并的完整业务 payload。 */
    val payload: JsonElement,
    /** 需要人工选择的字段或 Block 证据。 */
    val fields: List<WorkspaceReplicaFieldConflict>,
    /** 包含整对象回退在内，是否仍需要保留完整冲突历史。 */
    val conflicted: Boolean,
)
