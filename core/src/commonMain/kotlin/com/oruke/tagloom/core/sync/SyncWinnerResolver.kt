package com.oruke.tagloom.core.sync

/** 按因果关系、HLC、更新优先与 Actor ID 解析并发临时胜者。 */
object SyncWinnerResolver {
    /**
     * 判断两个候选 Change 是否存在明确因果后继。
     *
     * @param localChange 当前设备 head。
     * @param remoteChange 远端设备 head。
     * @param knownChanges 用于查询祖先路径的完整已知 Change 集合。
     * @return 存在因果关系时返回后继一侧，并发时为空。
     */
    fun causalSuccessor(
        localChange: ObjectChange,
        remoteChange: ObjectChange,
        knownChanges: Collection<ObjectChange>,
    ): MergeSide? = when {
        localChange.changeId == remoteChange.changeId -> MergeSide.LOCAL
        ObjectChangeDag.isAncestor(localChange.changeId, remoteChange.changeId, knownChanges) -> MergeSide.REMOTE
        ObjectChangeDag.isAncestor(remoteChange.changeId, localChange.changeId, knownChanges) -> MergeSide.LOCAL
        else -> null
    }

    /**
     * 为并发字段选择确定性临时胜者。
     *
     * 先比较 HLC 物理时间与逻辑计数；完全相同且一侧删除时让更新获胜，最后才比较 Actor 与 Change ID。
     * 败方仍必须进入 [ConflictRecord]，该结果不能用于物理销毁历史。
     *
     * @param localChange 当前设备 Change。
     * @param remoteChange 远端设备 Change。
     * @param localDeletes 当前字段在本地候选中是否被删除。
     * @param remoteDeletes 当前字段在远端候选中是否被删除。
     * @return 所有副本一致的临时胜者。
     */
    fun concurrentWinner(
        localChange: ObjectChange,
        remoteChange: ObjectChange,
        localDeletes: Boolean = localChange.kind == ChangeKind.DELETE,
        remoteDeletes: Boolean = remoteChange.kind == ChangeKind.DELETE,
    ): MergeSide {
        val localTime = localChange.hybridLogicalTime
        val remoteTime = remoteChange.hybridLogicalTime
        val physicalComparison = localTime.physicalTimeMillis.compareTo(remoteTime.physicalTimeMillis)
        if (physicalComparison != 0) {
            return if (physicalComparison > 0) MergeSide.LOCAL else MergeSide.REMOTE
        }
        val logicalComparison = localTime.logicalCounter.compareTo(remoteTime.logicalCounter)
        if (logicalComparison != 0) {
            return if (logicalComparison > 0) MergeSide.LOCAL else MergeSide.REMOTE
        }
        if (localDeletes != remoteDeletes) {
            return if (localDeletes) MergeSide.REMOTE else MergeSide.LOCAL
        }
        val actorComparison = localChange.actorId.value.compareTo(remoteChange.actorId.value)
        if (actorComparison != 0) {
            return if (actorComparison > 0) MergeSide.LOCAL else MergeSide.REMOTE
        }
        return if (localChange.changeId.value >= remoteChange.changeId.value) MergeSide.LOCAL else MergeSide.REMOTE
    }
}
