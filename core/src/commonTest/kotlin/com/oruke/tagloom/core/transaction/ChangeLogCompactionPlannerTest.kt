package com.oruke.tagloom.core.transaction

import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.model.ChangeId
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.TransactionId
import com.oruke.tagloom.core.sync.ChangeKind
import com.oruke.tagloom.core.sync.ChangeOperation
import com.oruke.tagloom.core.sync.ConflictFieldKind
import com.oruke.tagloom.core.sync.ConflictRecord
import com.oruke.tagloom.core.sync.ConflictStatus
import com.oruke.tagloom.core.sync.HybridLogicalTime
import com.oruke.tagloom.core.sync.MergeSide
import com.oruke.tagloom.core.sync.ObjectChange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.JsonNull

/**
 * 验证 Change checkpoint 的保留期、设备确认和冲突引用安全边界。
 */
class ChangeLogCompactionPlannerTest {
    /**
     * 验证未确认快照或仍处于撤销保留期的请求不会产生删除集合。
     */
    @Test
    fun rejectsUnverifiedSnapshotsAndActiveUndoRetention() {
        val change = change("change-a", emptyList())
        assertFailsWith<IllegalArgumentException> {
            ChangeLogCompactionPlanner.plan(request(listOf(change), snapshotsValidated = false))
        }
        assertFailsWith<IllegalArgumentException> {
            ChangeLogCompactionPlanner.plan(
                request(
                    changes = listOf(change),
                    policy = defaultPolicy().copy(undoRetentionExpired = false),
                ),
            )
        }
    }

    /**
     * 验证同步开启后，未撤销有效设备必须全部确认 checkpoint。
     */
    @Test
    fun requiresEveryActiveDeviceAcknowledgement() {
        val policy = defaultPolicy().copy(
            syncEnabled = true,
            knownDeviceIds = setOf("device-a", "device-b"),
            acknowledgedDeviceIds = setOf("device-a"),
        )
        assertFailsWith<IllegalArgumentException> {
            ChangeLogCompactionPlanner.plan(request(listOf(change("change-a", emptyList())), policy = policy))
        }
        val accepted = ChangeLogCompactionPlanner.plan(
            request(
                changes = listOf(change("change-a", emptyList())),
                policy = policy.copy(revokedDeviceIds = setOf("device-b")),
            ),
        )
        assertEquals(listOf(ChangeId("change-a")), accepted.removableChanges.map(ObjectChange::changeId))
    }

    /**
     * 验证未解决冲突引用的 Change 及全部祖先保留，其他分支仍可压缩。
     */
    @Test
    fun retainsUnresolvedConflictAncestorClosure() {
        val root = change("change-root", emptyList())
        val conflicted = change("change-conflicted", listOf(root.changeId))
        val removable = change("change-removable", emptyList(), objectId = "object-b")
        val conflict = conflict(conflicted.changeId)

        val decision = ChangeLogCompactionPlanner.plan(
            request(changes = listOf(removable, conflicted, root), conflicts = listOf(conflict)),
        )

        assertEquals(listOf(removable.changeId), decision.removableChanges.map(ObjectChange::changeId))
        assertEquals(
            setOf(root.changeId, conflicted.changeId),
            decision.retainedChanges.map(ObjectChange::changeId).toSet(),
        )
    }

    /**
     * 构造默认允许本地压缩的请求。
     *
     * @param changes 当前 Change 集合。
     * @param conflicts 当前冲突证据。
     * @param snapshotsValidated 快照验证结果。
     * @param policy 保留和设备确认策略。
     * @return planner 请求。
     */
    private fun request(
        changes: List<ObjectChange>,
        conflicts: List<ConflictRecord> = emptyList(),
        snapshotsValidated: Boolean = true,
        policy: ChangeLogCompactionPolicy = defaultPolicy(),
    ): ChangeLogCompactionRequest = ChangeLogCompactionRequest(
        checkpointId = "checkpoint-test",
        transactionId = TransactionId("transaction-test"),
        createdAt = "2026-09-12T00:00:00Z",
        changes = changes,
        conflicts = conflicts,
        snapshotsValidated = snapshotsValidated,
        policy = policy,
    )

    /**
     * 构造撤销保留期已结束且未启用同步的策略。
     *
     * @return 默认测试策略。
     */
    private fun defaultPolicy(): ChangeLogCompactionPolicy = ChangeLogCompactionPolicy(
        undoRetentionExpired = true,
        archivedToHistoryBackup = false,
        syncEnabled = false,
    )

    /**
     * 构造一个最小合法 Change。
     *
     * @param id Change ID。
     * @param parents 直接父节点。
     * @param objectId 业务对象 ID。
     * @return 测试 Change。
     */
    private fun change(
        id: String,
        parents: List<ChangeId>,
        objectId: String = "object-a",
    ): ObjectChange = ObjectChange(
        changeId = ChangeId(id),
        objectId = ObjectId(objectId),
        parentChangeIds = parents,
        actorId = ActorId("actor-test"),
        hybridLogicalTime = HybridLogicalTime(id.length.toLong(), 0, ActorId("actor-test")),
        baseSnapshotRevision = null,
        transactionId = TransactionId("transaction-$id"),
        kind = ChangeKind.UPDATE,
        operations = listOf(ChangeOperation("replace-object")),
    )

    /**
     * 构造同时引用同一候选 Change 的未解决冲突。
     *
     * @param changeId 冲突依赖 Change。
     * @return 未解决冲突证据。
     */
    private fun conflict(changeId: ChangeId): ConflictRecord = ConflictRecord(
        conflictId = "conflict-test",
        objectId = ObjectId("object-a"),
        blockId = null,
        fieldKind = ConflictFieldKind.TITLE,
        fieldPath = "title",
        baseValue = JsonNull,
        localValue = JsonNull,
        remoteValue = JsonNull,
        temporaryWinner = MergeSide.LOCAL,
        localChangeId = changeId,
        remoteChangeId = changeId,
        createdAt = "2026-09-12T00:00:00Z",
        status = ConflictStatus.UNRESOLVED,
    )
}
