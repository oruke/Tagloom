package com.oruke.tagloom.core.sync

import com.oruke.tagloom.core.model.ChangeId
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.workspace.ManifestObjectKind
import kotlinx.serialization.builtins.ListSerializer

/**
 * 当独立合法目录的逐对象合并破坏引用或继承不变量时，确定性选择一组完整目录并保留冲突。
 *
 * Note 仍采用原规划器的字段合并，独有对象始终保留；无法构成合法并集时失败关闭，不能默默删除笔记或伪造缺失版本。
 */
object WorkspaceReplicaCatalogConflictResolver {
    /** 必须成组选择、不能逐个对象混选的共享目录类别。 */
    private val catalogKinds = setOf(ManifestObjectKind.TAG, ManifestObjectKind.PROPERTY,
        ManifestObjectKind.DATABASE, ManifestObjectKind.VIEW, ManifestObjectKind.SAVED_QUERY)

    /**
     * 为合并后的跨对象不变量冲突尝试稳定的完整目录选择，不执行任何持久化。
     * @param local 本地完整认证目录，必须独立合法。
     * @param remote 远端完整认证目录，必须独立合法。
     * @param changes 可用于因果及目录排序的认证 Change 并集。
     * @param mergedAt Note 字段合并器使用的本轮 UTC 时间。
     * @param conflictIdFactory 按稳定双方 heads 生成不透明冲突身份的工厂。
     * @return 合法的完整对象并集；两个目录选择均无效时抛出 sync_schema_invalid。
     */
    fun plan(
        local: Collection<WorkspaceReplicaObject>,
        remote: Collection<WorkspaceReplicaObject>,
        changes: Collection<ObjectChange>,
        mergedAt: String,
        conflictIdFactory: (ManifestObjectKind, ObjectId, ChangeId?, ChangeId?) -> String,
    ): WorkspaceReplicaPlan {
        check(valid(local) && valid(remote)) { "sync_schema_invalid" }
        val original = WorkspaceReplicaMergePlanner.plan(local, remote, changes, mergedAt, conflictIdFactory)
        if (valid(original.objects.map { WorkspaceReplicaObject(it.kind, it.snapshot) })) return original
        val localById = local.associateBy { it.kind to it.snapshot.header.objectId }
        val remoteById = remote.associateBy { it.kind to it.snapshot.header.objectId }
        val preferred = if (compareCatalogs(local, remote, changes) >= 0) MergeSide.LOCAL else MergeSide.REMOTE
        // 两个设备即使交换参数方向，也按同一目录身份尝试；首选不满足独有对象引用时再尝试另一份。
        for (side in listOf(preferred, if (preferred == MergeSide.LOCAL) MergeSide.REMOTE else MergeSide.LOCAL)) {
            val decisions = original.objects.map { decision ->
                val identity = decision.kind to decision.objectId
                val localObject = localById[identity]
                val remoteObject = remoteById[identity]
                if (decision.kind !in catalogKinds || localObject == null || remoteObject == null) decision else {
                    chooseCatalogVersion(decision, localObject, remoteObject, side, conflictIdFactory)
                }
            }
            if (valid(decisions.map { WorkspaceReplicaObject(it.kind, it.snapshot) })) return WorkspaceReplicaPlan(decisions)
        }
        error("sync_schema_invalid")
    }

    /**
     * 将共享目录对象统一采用同一侧，任何内容差异都保留完整双方并连接两个 heads。
     * @param original 原逐对象规划，值相同且无须处理时可原样保留。
     * @param local 本地目录候选。
     * @param remote 远端目录候选。
     * @param side 本轮整组采用的一侧。
     * @param conflictIdFactory 不透明稳定冲突身份工厂。
     * @return 不会把目录一半取本地、一半取远端的对象决定。
     */
    private fun chooseCatalogVersion(
        original: WorkspaceReplicaDecision,
        local: WorkspaceReplicaObject,
        remote: WorkspaceReplicaObject,
        side: MergeSide,
        conflictIdFactory: (ManifestObjectKind, ObjectId, ChangeId?, ChangeId?) -> String,
    ): WorkspaceReplicaDecision {
        val sameValue = local.snapshot.payload == remote.snapshot.payload && local.snapshot.header.deleted == remote.snapshot.header.deleted
        if (sameValue) return original
        val selected = if (side == MergeSide.LOCAL) local.snapshot else remote.snapshot
        val conflict = WorkspaceReplicaConflictFactory.create(local.kind, local.snapshot, remote.snapshot, null,
            side, WorkspaceReplicaConflictReason.CATALOG_INVARIANT, conflictIdFactory)
        return WorkspaceReplicaDecision(local.kind, selected.header.objectId, WorkspaceReplicaAction.MERGE, selected,
            ObjectChangeDag.mergeParentIds(listOfNotNull(local.snapshot.header.lastChangeId, remote.snapshot.header.lastChangeId)),
            listOf(conflict))
    }

    /**
     * 比较两份完整目录的最新已知 HLC，并以 canonical 内容稳定打破平局，不依赖本轮主机时钟。
     * @param local 第一份完整目录。
     * @param remote 第二份完整目录。
     * @param changes 可认证的逻辑 Change。
     * @return 正数表示优先采用本地方向，负数表示优先采用远端方向。
     */
    private fun compareCatalogs(
        local: Collection<WorkspaceReplicaObject>,
        remote: Collection<WorkspaceReplicaObject>,
        changes: Collection<ObjectChange>,
    ): Int {
        val byId = changes.associateBy(ObjectChange::changeId)
        val timeComparator = Comparator<HybridLogicalTime>(ObjectChangeDag::compareHybridTime)
        val localTime = local.mapNotNull { it.snapshot.header.lastChangeId?.let(byId::get)?.hybridLogicalTime }.maxWithOrNull(timeComparator)
        val remoteTime = remote.mapNotNull { it.snapshot.header.lastChangeId?.let(byId::get)?.hybridLogicalTime }.maxWithOrNull(timeComparator)
        val timeOrder = when {
            localTime == null && remoteTime == null -> 0
            localTime == null -> -1
            remoteTime == null -> 1
            else -> ObjectChangeDag.compareHybridTime(localTime, remoteTime)
        }
        if (timeOrder != 0) return timeOrder
        return canonicalCatalog(local).compareTo(canonicalCatalog(remote))
    }

    /**
     * 按类别与对象身份生成稳定比较文本，纯内存比较不会写出正文或目录名称。
     * @param objects 一份完整认证目录。
     * @return 仅用于平局排序的 canonical 文本。
     */
    private fun canonicalCatalog(objects: Collection<WorkspaceReplicaObject>): String = CanonicalJson.encodeToString(
        ListSerializer(WorkspaceReplicaObject.serializer()),
        objects.sortedWith(compareBy({ it.kind.name }, { it.snapshot.header.objectId.value })),
    )

    /**
     * 判断完整候选目录是否满足现有跨对象不变量，协议异常只用于选择其他完整候选。
     * @param objects 待预验证的完整候选并集。
     * @return 全目录可安全发布时为真。
     */
    private fun valid(objects: Collection<WorkspaceReplicaObject>): Boolean = try {
        WorkspaceReplicaCatalogValidator.validate(objects)
        true
    } catch (_: IllegalArgumentException) {
        false
    }
}
