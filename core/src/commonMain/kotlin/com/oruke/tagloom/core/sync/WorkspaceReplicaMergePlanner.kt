package com.oruke.tagloom.core.sync

import com.oruke.tagloom.core.model.ChangeId
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.ObjectSnapshot
import com.oruke.tagloom.core.model.ObjectType
import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.workspace.ManifestObjectKind
import kotlin.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** 在认证快照之间按因果关系和真实共同祖先规划合并，不接触工作区密钥或持久化。 */
object WorkspaceReplicaMergePlanner {
    /**
     * 为两个确认点的对象并集生成稳定的快进、保持或合并决定。
     *
     * 调用方负责先认证输入；未找到真实共同祖先时只选临时版本并保留完整双方，绝不拿任一当前版本冒充基线。
     * @param local 本地 manifest 已确认的完整对象候选。
     * @param remote 远端确认点中已经验证的完整对象候选。
     * @param changes 两侧可读取的已认证逻辑 Change，重复同值节点允许去重。
     * @param mergedAt Note 字段合并器使用的本轮 UTC ISO-8601 时间；完整冲突历史使用双方版本更新时间。
     * @param conflictIdFactory 根据对象和两个 heads 生成不含业务语义的稳定冲突 ID。
     * @return 按类别及对象 ID 排序的纯合并规划。
     */
    fun plan(
        local: Collection<WorkspaceReplicaObject>,
        remote: Collection<WorkspaceReplicaObject>,
        changes: Collection<ObjectChange>,
        mergedAt: String,
        conflictIdFactory: (ManifestObjectKind, ObjectId, ChangeId?, ChangeId?) -> String,
    ): WorkspaceReplicaPlan {
        require(mergedAt.isNotBlank()) { "同步合并时间不能为空" }
        val localById = indexObjects(local)
        val remoteById = indexObjects(remote)
        val known = changes.groupBy(ObjectChange::changeId).mapValues { (_, versions) ->
            require(versions.all { it == versions.first() }) { "相同 Change ID 出现不同内容" }
            versions.first()
        }
        // 允许 checkpoint 以前缺失的祖先触发保守冲突，但跨对象父边和环不能成为因果依据。
        known.values.forEach { change ->
            require(change.parentChangeIds.all { known[it]?.objectId?.let { parentId -> parentId == change.objectId } != false }) {
                "同步 Change 父边跨对象"
            }
        }
        known.values.groupBy(ObjectChange::objectId).values.forEach { history ->
            require(ObjectChangeDag.analyze(history).errors.none { it.code != ChangeDagErrorCode.MISSING_PARENT }) {
                "同步 Change DAG 结构无效"
            }
        }
        val identities = (localById.keys + remoteById.keys).sortedWith(compareBy({ it.first.name }, { it.second.value }))
        return WorkspaceReplicaPlan(identities.map { identity ->
            val localObject = localById[identity]
            val remoteObject = remoteById[identity]
            when {
                localObject == null -> decision(requireNotNull(remoteObject), WorkspaceReplicaAction.FAST_FORWARD)
                remoteObject == null -> decision(localObject, WorkspaceReplicaAction.KEEP_LOCAL)
                else -> planObject(localObject, remoteObject, known, mergedAt, conflictIdFactory)
            }
        })
    }

    /**
     * 校验对象类别与快照头，并拒绝一个确认点内重复的对象身份。
     * @param objects 单个已认证确认点中的业务对象。
     * @return 按类别与随机对象 ID 建立的唯一映射。
     */
    private fun indexObjects(objects: Collection<WorkspaceReplicaObject>): Map<Pair<ManifestObjectKind, ObjectId>, WorkspaceReplicaObject> {
        val expectedTypes = mapOf(
            ManifestObjectKind.NOTE to ObjectType.NOTE,
            ManifestObjectKind.TAG to ObjectType.TAG,
            ManifestObjectKind.PROPERTY to ObjectType.PROPERTY,
            ManifestObjectKind.SAVED_QUERY to ObjectType.SAVED_QUERY,
            ManifestObjectKind.DATABASE to ObjectType.DATABASE,
            ManifestObjectKind.VIEW to ObjectType.VIEW,
        )
        val indexed = objects.associateBy { candidate ->
            require(candidate.snapshot.header.objectType == expectedTypes[candidate.kind]) { "同步对象类别与快照类型不一致" }
            candidate.kind to candidate.snapshot.header.objectId
        }
        require(indexed.size == objects.size) { "同步确认点含重复对象" }
        return indexed
    }

    /**
     * 在同一对象的两个候选之间先判因果，再处理并发及删除竞争。
     * @param local 本地确认候选。
     * @param remote 远端认证候选。
     * @param known 已去重的认证 Change。
     * @param mergedAt 本轮冲突创建时间。
     * @param conflictIdFactory 稳定不透明冲突 ID 工厂。
     * @return 单个对象的完整规划决定。
     */
    private fun planObject(
        local: WorkspaceReplicaObject,
        remote: WorkspaceReplicaObject,
        known: Map<ChangeId, ObjectChange>,
        mergedAt: String,
        conflictIdFactory: (ManifestObjectKind, ObjectId, ChangeId?, ChangeId?) -> String,
    ): WorkspaceReplicaDecision {
        val localHead = local.snapshot.header.lastChangeId
        val remoteHead = remote.snapshot.header.lastChangeId
        if (localHead == remoteHead && sameValue(local.snapshot, remote.snapshot)) {
            return decision(local, WorkspaceReplicaAction.KEEP_LOCAL)
        }
        require(localHead == null || localHead != remoteHead) { "同一逻辑 Change 对应不同对象内容" }
        val objectId = local.snapshot.header.objectId
        val history = known.values.filter { it.objectId == objectId }
        val localChange = localHead?.let(known::get)
        val remoteChange = remoteHead?.let(known::get)
        require(localChange == null || localChange.objectId == objectId) { "本地 head Change 对象不一致" }
        require(remoteChange == null || remoteChange.objectId == objectId) { "远端 head Change 对象不一致" }
        if (localChange != null && remoteChange != null) {
            when (SyncWinnerResolver.causalSuccessor(localChange, remoteChange, history)) {
                MergeSide.LOCAL -> return decision(local, WorkspaceReplicaAction.KEEP_LOCAL)
                MergeSide.REMOTE -> return decision(remote, WorkspaceReplicaAction.FAST_FORWARD)
                null -> Unit
            }
        }

        val winner = concurrentWinner(local.snapshot, remote.snapshot, localChange, remoteChange)
        val selected = if (winner == MergeSide.LOCAL) local else remote
        val parents = ObjectChangeDag.mergeParentIds(listOfNotNull(localHead, remoteHead))
        if (sameValue(local.snapshot, remote.snapshot)) {
            return decision(selected, WorkspaceReplicaAction.MERGE, parents)
        }
        // 删除是快照头语义，不能让只合并 payload 的 Note 合并器沿用本地 deleted 值。
        if (local.snapshot.header.deleted != remote.snapshot.header.deleted) {
            return conflicted(local, remote, selected.snapshot, winner, WorkspaceReplicaConflictReason.DELETE_UPDATE,
                null, parents, conflictIdFactory)
        }
        val basePayload = commonBasePayload(local.kind, localHead, remoteHead, history)
        if (local.kind == ManifestObjectKind.NOTE && basePayload != null && localChange != null && remoteChange != null) {
            val merge = NoteReplicaPayloadMerge.merge(objectId, basePayload, local.snapshot.payload, remote.snapshot.payload,
                localChange, remoteChange, history, mergedAt, winner)
            val candidate = selected.snapshot.copy(payload = merge.payload)
            if (!merge.conflicted) {
                return decision(selected.copy(snapshot = candidate), WorkspaceReplicaAction.MERGE, parents)
            }
            return conflicted(local, remote, candidate, winner, WorkspaceReplicaConflictReason.CONCURRENT_VALUE,
                basePayload, parents, conflictIdFactory, merge.fields)
        }
        if (local.kind != ManifestObjectKind.NOTE && basePayload != null) {
            val merge = ReplicaJsonFieldMerge.merge(local.kind, basePayload, local.snapshot.payload, remote.snapshot.payload, winner)
            val candidate = selected.snapshot.copy(payload = merge.payload)
            if (merge.fields.isEmpty()) return decision(selected.copy(snapshot = candidate), WorkspaceReplicaAction.MERGE, parents)
            return conflicted(local, remote, candidate, winner, WorkspaceReplicaConflictReason.CONCURRENT_VALUE,
                basePayload, parents, conflictIdFactory, merge.fields)
        }
        val reason = if (basePayload == null) {
            WorkspaceReplicaConflictReason.NO_COMMON_ANCESTOR
        } else WorkspaceReplicaConflictReason.CONCURRENT_VALUE
        return conflicted(local, remote, selected.snapshot, winner, reason, basePayload, parents, conflictIdFactory)
    }

    /**
     * 从唯一最近共同祖先恢复完整 payload，多基线或摘要式历史均明确返回缺失。
     * @param kind 当前对象类别。
     * @param localHead 本地 head，历史根快照可为空。
     * @param remoteHead 远端 head，历史根快照可为空。
     * @param history 同一对象已经认证的已知 Change。
     * @return 可直接作为三方基线的 payload，否则为空。
     */
    private fun commonBasePayload(
        kind: ManifestObjectKind,
        localHead: ChangeId?,
        remoteHead: ChangeId?,
        history: Collection<ObjectChange>,
    ): JsonElement? {
        if (localHead == null || remoteHead == null) return null
        val common = history.filter { change ->
            (change.changeId == localHead || ObjectChangeDag.isAncestor(change.changeId, localHead, history)) &&
                (change.changeId == remoteHead || ObjectChangeDag.isAncestor(change.changeId, remoteHead, history))
        }
        val closest = common.filter { candidate ->
            common.none { other -> ObjectChangeDag.isAncestor(candidate.changeId, other.changeId, history) }
        }.singleOrNull() ?: return null
        val protocol = when (kind) {
            ManifestObjectKind.NOTE -> "note"
            ManifestObjectKind.TAG -> "tag-definition"
            ManifestObjectKind.PROPERTY -> "property-definition"
            ManifestObjectKind.SAVED_QUERY -> "saved-query"
            ManifestObjectKind.DATABASE -> "database-definition"
            ManifestObjectKind.VIEW -> "view-definition"
            else -> return null
        }
        val operation = closest.operations.singleOrNull { operation ->
            operation.type in setOf("replace-$protocol", "delete-$protocol", "merge-replica-object",
                "create-default-note-tag", "create-default-property", "merge-note-heads")
        } ?: return null
        return if (operation.type == "merge-note-heads") (operation.payload as? JsonObject)?.get("mergedNote") else operation.payload
    }

    /**
     * 按 HLC 选择临时版本；历史缺失时仅用认证快照时间排序并保留冲突。
     * @param local 本地完整快照。
     * @param remote 远端完整快照。
     * @param localChange 本地 head，checkpoint 已压缩时可为空。
     * @param remoteChange 远端 head，checkpoint 已压缩时可为空。
     * @return 对调输入后仍选择同一业务版本的一侧。
     */
    private fun concurrentWinner(
        local: ObjectSnapshot<JsonElement>,
        remote: ObjectSnapshot<JsonElement>,
        localChange: ObjectChange?,
        remoteChange: ObjectChange?,
    ): MergeSide {
        if (localChange != null && remoteChange != null) {
            return SyncWinnerResolver.concurrentWinner(localChange, remoteChange, local.header.deleted, remote.header.deleted)
        }
        val timeOrder = Instant.parse(local.header.updatedAt).compareTo(Instant.parse(remote.header.updatedAt))
        if (timeOrder != 0) return if (timeOrder > 0) MergeSide.LOCAL else MergeSide.REMOTE
        if (local.header.deleted != remote.header.deleted) return if (local.header.deleted) MergeSide.REMOTE else MergeSide.LOCAL
        val localKey = listOf(local.header.revision.actorId.value, local.header.revision.sequence.toString(),
            local.header.lastChangeId?.value.orEmpty(), CanonicalJson.encodeToString(JsonElement.serializer(), local.payload))
        val remoteKey = listOf(remote.header.revision.actorId.value, remote.header.revision.sequence.toString(),
            remote.header.lastChangeId?.value.orEmpty(), CanonicalJson.encodeToString(JsonElement.serializer(), remote.payload))
        val order = localKey.zip(remoteKey).firstOrNull { it.first != it.second }?.let { it.first.compareTo(it.second) } ?: 0
        return if (order >= 0) MergeSide.LOCAL else MergeSide.REMOTE
    }

    /**
     * 保留完整双方并生成合并决定，正文只出现在调用方随后加密的模型中。
     * @param local 本地候选。
     * @param remote 远端候选。
     * @param candidate 自动合并或确定性临时胜者的候选模板。
     * @param winner 临时胜者。
     * @param reason 冲突稳定分类。
     * @param basePayload 真实共同祖先 payload，没有时为空。
     * @param parents 新 Merge Change 应引用的全部 heads。
     * @param conflictIdFactory 不透明稳定 ID 工厂。
     * @param fields 本轮输入方向下可独立处理的字段；整体约束冲突为空。
     * @return 带完整冲突证据的合并决定。
     */
    private fun conflicted(
        local: WorkspaceReplicaObject,
        remote: WorkspaceReplicaObject,
        candidate: ObjectSnapshot<JsonElement>,
        winner: MergeSide,
        reason: WorkspaceReplicaConflictReason,
        basePayload: JsonElement?,
        parents: List<ChangeId>,
        conflictIdFactory: (ManifestObjectKind, ObjectId, ChangeId?, ChangeId?) -> String,
        fields: List<WorkspaceReplicaFieldConflict> = emptyList(),
    ): WorkspaceReplicaDecision {
        val objectId = candidate.header.objectId
        val conflict = WorkspaceReplicaConflictFactory.create(local.kind, local.snapshot, remote.snapshot,
            basePayload, winner, reason, conflictIdFactory, fields)
        return WorkspaceReplicaDecision(local.kind, objectId, WorkspaceReplicaAction.MERGE, candidate, parents, listOf(conflict))
    }

    /**
     * 创建不需要冲突证据的对象决定。
     * @param candidate 需要保留或确认的完整候选。
     * @param action 本轮对象动作。
     * @param parents 新 Merge Change 的父 heads，普通快进默认使用候选当前 head。
     * @return 不含冲突的对象决定。
     */
    private fun decision(
        candidate: WorkspaceReplicaObject,
        action: WorkspaceReplicaAction,
        parents: List<ChangeId> = listOfNotNull(candidate.snapshot.header.lastChangeId),
    ): WorkspaceReplicaDecision = WorkspaceReplicaDecision(candidate.kind, candidate.snapshot.header.objectId,
        action, candidate.snapshot, parents)

    /**
     * 比较业务值与删除状态；本地密文重加密产生的快照链哈希不参与内容判断。
     * @param local 本地完整快照。
     * @param remote 远端完整快照。
     * @return payload 与删除标记均一致时为真。
     */
    private fun sameValue(local: ObjectSnapshot<JsonElement>, remote: ObjectSnapshot<JsonElement>): Boolean =
        local.payload == remote.payload && local.header.deleted == remote.header.deleted

}
