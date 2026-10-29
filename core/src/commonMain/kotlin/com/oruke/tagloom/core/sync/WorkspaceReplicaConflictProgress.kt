package com.oruke.tagloom.core.sync

import com.oruke.tagloom.core.model.ChangeId
import com.oruke.tagloom.core.protocol.CanonicalJson
import kotlinx.serialization.json.JsonElement
import kotlin.time.Instant

/** 合并加密冲突的处理进度，不修改双版本证据，也不代替对象内容的 DAG 合并。 */
object WorkspaceReplicaConflictProgress {
    /**
     * 为本次正式确认的字段追加处理标记，全部字段完成后才关闭整项冲突。
     * @param conflict 当前经过认证的冲突历史。
     * @param fieldIds 本次选择的尚未处理字段身份。
     * @param side 用户选择的稳定版本。
     * @param changeId 与内容修改同事务创建的 Change。
     * @param resolvedAt 正式确认时间。
     * @return 带新处理进度的完整历史。
     */
    fun resolve(conflict: WorkspaceReplicaConflict, fieldIds: Set<String>, side: MergeSide,
        changeId: ChangeId, resolvedAt: String): WorkspaceReplicaConflict {
        require(conflict.status == ConflictStatus.UNRESOLVED) { "revision_conflict" }
        require(fieldIds.all { id -> conflict.fields.any { it.fieldId == id } } &&
            resolvedFieldIds(conflict).none { it in fieldIds }) { "revision_conflict" }
        // 用户已审阅当前合并快照，新 Change 因果覆盖本字段的旧选择；其它字段证据继续保留。
        val resolutions = conflict.fieldResolutions.filterNot { it.fieldId in fieldIds } + fieldIds.map {
            WorkspaceReplicaFieldResolution(it, side, changeId, resolvedAt)
        }
        val complete = conflict.fields.isEmpty() || completedIds(resolutions).size == conflict.fields.size
        return conflict.copy(fieldResolutions = resolutions.sortedWith(compareBy({ it.fieldId }, { it.changeId.value })),
            status = if (complete) ConflictStatus.RESOLVED else ConflictStatus.UNRESOLVED,
            resolutionChangeId = if (complete) changeId else null,
            resolvedAt = if (complete) resolvedAt else null)
    }

    /**
     * 合并同一冲突的独立字段确认，避免整条历史的二选一丢掉另一设备的处理结果。
     * @param first 当前认证历史。
     * @param second 同一冲突的另一份认证历史。
     * @param changes 已认证的逻辑 Change，用父边判断解决动作的因果关系，不能依靠墙钟丢弃选择。
     * @return 与输入方向无关、保留全部字段确认的历史。
     */
    fun merge(first: WorkspaceReplicaConflict, second: WorkspaceReplicaConflict,
        changes: Collection<ObjectChange> = emptyList()): WorkspaceReplicaConflict {
        require(evidence(first) == evidence(second)) { "sync_integrity_failed" }
        // 原因、基线及临时选择可能来自不同目录上下文；不把附加解释差异误报为密文篡改。
        val compatible = !first.requiresWholeObjectResolution && !second.requiresWholeObjectResolution &&
            first.reason == second.reason && first.basePayload == second.basePayload &&
            first.temporaryWinner == second.temporaryWinner &&
            (first.fields.isEmpty() || second.fields.isEmpty() || first.fields == second.fields)
        val preferred = listOf(first, second).maxWith(compareBy<WorkspaceReplicaConflict> { reasonPriority(it.reason) }
            .thenBy { it.fields.isEmpty() }.thenBy { it.temporaryWinner.name }
            .thenBy { it.basePayload?.let { value -> CanonicalJson.encodeToString(JsonElement.serializer(), value) }.orEmpty() })
        val fields = if (!compatible) emptyList() else if (first.fields.isNotEmpty()) first.fields else second.fields
        // 旧完整恢复没有逐字段选择方向；其明确的整版处理语义不能被新版附加说明重新打开。
        val wholeResolution = listOf(first, second).filter { it.fields.isEmpty() && it.status == ConflictStatus.RESOLVED }
            .maxWithOrNull(compareBy<WorkspaceReplicaConflict> { Instant.parse(requireNotNull(it.resolvedAt)) }
                .thenBy { it.resolutionChangeId?.value.orEmpty() })
        val history = changes.filter { it.objectId == first.objectId }
        val unique = (first.fieldResolutions + second.fieldResolutions).groupBy { it.fieldId to it.changeId }.values.map { entries ->
            require(entries.all { it == entries.first() }) { "sync_integrity_failed" }
            entries.first()
        }
        val resolutions = unique.filter { candidate ->
            unique.none { later -> candidate.fieldId == later.fieldId &&
                ObjectChangeDag.isAncestor(candidate.changeId, later.changeId, history) }
        }.sortedWith(compareBy({ it.fieldId }, { it.changeId.value }))
        // 同字段的并发相反选择重新待确认，即使其中一次只是确认临时值、payload 没有变化。
        val completed = fields.isNotEmpty() && completedIds(resolutions).size == fields.size
        val latest = resolutions.maxWithOrNull(compareBy<WorkspaceReplicaFieldResolution> { Instant.parse(it.resolvedAt) }
            .thenBy { it.changeId.value })
        return preferred.copy(fields = if (wholeResolution != null) emptyList() else fields, fieldResolutions = resolutions,
            requiresWholeObjectResolution = !compatible,
            basePayload = first.basePayload.takeIf { it == second.basePayload },
            status = if (wholeResolution != null || completed) ConflictStatus.RESOLVED else ConflictStatus.UNRESOLVED,
            resolutionChangeId = wholeResolution?.resolutionChangeId ?: latest?.changeId?.takeIf { completed },
            resolvedAt = wholeResolution?.resolvedAt ?: latest?.resolvedAt?.takeIf { completed })
    }

    /**
     * 读取已经达成一致的字段身份，并发相反选择不视为已解决。
     * @param conflict 当前认证历史；处理进度在合并时已按 DAG 规范化。
     * @return 可以禁止重复确认的字段身份集合。
     */
    fun resolvedFieldIds(conflict: WorkspaceReplicaConflict): Set<String> = completedIds(conflict.fieldResolutions)

    /**
     * 一个字段的活跃解决动作全部选择同一版本才算完成，多设备相同选择不会制造冲突。
     * @param resolutions 经过因果约简的逐字段解决动作。
     * @return 没有互斥活跃选择的字段身份。
     */
    private fun completedIds(resolutions: List<WorkspaceReplicaFieldResolution>): Set<String> =
        resolutions.groupBy { it.fieldId }.filterValues { entries -> entries.map { it.selectedSide }.distinct().size == 1 }.keys

    /**
     * 为解释不同的同版本历史选择稳定且保守的整体原因。
     * @param reason 当前记录的冲突分类。
     * @return 目录不变量最高，其次删除、缺失共同祖先和普通值竞争。
     */
    private fun reasonPriority(reason: WorkspaceReplicaConflictReason): Int = when (reason) {
        WorkspaceReplicaConflictReason.CATALOG_INVARIANT -> 3
        WorkspaceReplicaConflictReason.DELETE_UPDATE -> 2
        WorkspaceReplicaConflictReason.NO_COMMON_ANCESTOR -> 1
        WorkspaceReplicaConflictReason.CONCURRENT_VALUE -> 0
    }

    /**
     * 仅比较固定对象身份和完整双版本证据，原因及临时选择属于可因目录上下文变化的解释。
     * @param conflict 待比较历史。
     * @return 不含附加字段和处理进度的稳定证据。
     */
    private fun evidence(conflict: WorkspaceReplicaConflict): WorkspaceReplicaConflict = conflict.copy(
        status = ConflictStatus.UNRESOLVED, resolutionChangeId = null, resolvedAt = null,
        reason = WorkspaceReplicaConflictReason.CONCURRENT_VALUE, basePayload = null, temporaryWinner = MergeSide.LOCAL,
        fields = emptyList(), fieldResolutions = emptyList(), requiresWholeObjectResolution = false)
}
