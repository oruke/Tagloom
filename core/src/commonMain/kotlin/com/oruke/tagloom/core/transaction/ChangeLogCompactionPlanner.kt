package com.oruke.tagloom.core.transaction

import com.oruke.tagloom.core.model.ChangeId
import com.oruke.tagloom.core.sync.ConflictStatus
import com.oruke.tagloom.core.sync.ObjectChange

/**
 * 根据快照验证、保留期、设备确认和冲突引用生成确定性 Change 压缩集合。
 */
object ChangeLogCompactionPlanner {
    /**
     * 拒绝任何证据不完整的压缩，并保留未解决冲突引用 Change 的完整祖先闭包。
     *
     * @param request 已认证压缩输入。
     * @return 按 HLC 和 Change ID 稳定排序的删除与保留集合。
     */
    fun plan(request: ChangeLogCompactionRequest): ChangeLogCompactionDecision {
        require(request.snapshotsValidated) { "全部权威快照验证成功后才能压缩 Change Log" }
        require(request.policy.undoRetentionExpired || request.policy.archivedToHistoryBackup) {
            "撤销保留期未结束且 Change 未归档"
        }
        require(request.changes.isNotEmpty()) { "当前没有可压缩 Change" }
        require(request.changes.map { change -> change.changeId }.distinct().size == request.changes.size) {
            "压缩输入包含重复 Change"
        }
        validateDeviceAcknowledgements(request.policy)

        val byId = request.changes.associateBy(ObjectChange::changeId)
        val retainedIds = mutableSetOf<ChangeId>()
        request.conflicts
            .filter { conflict -> conflict.status == ConflictStatus.UNRESOLVED }
            .flatMap { conflict -> listOf(conflict.localChangeId, conflict.remoteChangeId) }
            .forEach { referencedId -> retainAncestorClosure(referencedId, byId, retainedIds) }
        val stableChanges = request.changes.sortedWith(
            compareBy(
                { change -> change.hybridLogicalTime.physicalTimeMillis },
                { change -> change.hybridLogicalTime.logicalCounter },
                { change -> change.hybridLogicalTime.actorId.value },
                { change -> change.changeId.value },
            ),
        )
        val removable = stableChanges.filterNot { change -> change.changeId in retainedIds }
        require(removable.isNotEmpty()) { "全部 Change 仍被未解决冲突引用，不能压缩" }
        return ChangeLogCompactionDecision(
            removableChanges = removable,
            retainedChanges = stableChanges.filter { change -> change.changeId in retainedIds },
        )
    }

    /**
     * 启用同步时要求每个未撤销设备都确认 checkpoint。
     *
     * @param policy 当前工作区压缩策略。
     */
    private fun validateDeviceAcknowledgements(policy: ChangeLogCompactionPolicy) {
        if (!policy.syncEnabled) {
            return
        }
        val activeDevices = policy.knownDeviceIds - policy.revokedDeviceIds
        require(activeDevices.all(policy.acknowledgedDeviceIds::contains)) { "仍有有效设备未确认 checkpoint" }
    }

    /**
     * 从冲突引用节点沿父边保留完整 DAG 祖先，避免剩余证据出现悬空父节点。
     *
     * @param startId 冲突直接引用 Change。
     * @param byId 当前 Change 索引。
     * @param retainedIds 累积保留集合。
     */
    private fun retainAncestorClosure(
        startId: ChangeId,
        byId: Map<ChangeId, ObjectChange>,
        retainedIds: MutableSet<ChangeId>,
    ) {
        val pending = ArrayDeque<ChangeId>()
        pending.addLast(startId)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!retainedIds.add(current)) {
                continue
            }
            byId[current]?.parentChangeIds.orEmpty().forEach(pending::addLast)
        }
    }
}
