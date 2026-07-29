package com.oruke.tagloom.core.sync

import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.model.ChangeId
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.TransactionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonPrimitive

/** 验证因果后继、HLC 与删除/更新竞态的确定性胜者规则。 */
class SyncWinnerResolverTest {
    /** 测试对象。 */
    private val objectId = ObjectId("note-winner")

    /** 验证明确的 DAG 后继优先于时间比较。 */
    @Test
    fun causalSuccessorWinsWithoutConflictTieBreak() {
        val root = change("change-root", "actor-root", physicalTime = 200)
        val child = change("change-child", "actor-child", physicalTime = 100, parent = root.changeId)

        val winner = SyncWinnerResolver.causalSuccessor(root, child, listOf(root, child))

        assertEquals(MergeSide.REMOTE, winner)
    }

    /** 验证并发 Change 优先采用更晚的 HLC。 */
    @Test
    fun laterHybridLogicalTimeWins() {
        val local = change("change-local", "actor-z", physicalTime = 100)
        val remote = change("change-remote", "actor-a", physicalTime = 101)

        assertEquals(MergeSide.REMOTE, SyncWinnerResolver.concurrentWinner(local, remote))
    }

    /** 验证时间完全相同时更新优先于删除，Actor 排序只能作为后续兜底。 */
    @Test
    fun updateWinsDeleteWhenHybridLogicalTimeTies() {
        val localDelete = change("change-delete", "actor-z", physicalTime = 100, kind = ChangeKind.DELETE)
        val remoteUpdate = change("change-update", "actor-a", physicalTime = 100)

        val winner = SyncWinnerResolver.concurrentWinner(
            localChange = localDelete,
            remoteChange = remoteUpdate,
            localDeletes = true,
            remoteDeletes = false,
        )

        assertEquals(MergeSide.REMOTE, winner)
    }

    /**
     * 创建可参与胜者比较的 Change。
     *
     * @param id Change ID。
     * @param actor Actor ID。
     * @param physicalTime HLC 物理时间。
     * @param parent 可选父 Change。
     * @param kind Change 类型。
     * @return 测试 Change。
     */
    private fun change(
        id: String,
        actor: String,
        physicalTime: Long,
        parent: ChangeId? = null,
        kind: ChangeKind = ChangeKind.UPDATE,
    ): ObjectChange {
        val actorId = ActorId(actor)
        return ObjectChange(
            changeId = ChangeId(id),
            objectId = objectId,
            parentChangeIds = listOfNotNull(parent),
            actorId = actorId,
            hybridLogicalTime = HybridLogicalTime(physicalTime, logicalCounter = 0, actorId = actorId),
            baseSnapshotRevision = null,
            transactionId = TransactionId("transaction-$id"),
            kind = kind,
            operations = if (kind == ChangeKind.DELETE) {
                emptyList()
            } else {
                listOf(ChangeOperation("edit", payload = JsonPrimitive(id)))
            },
        )
    }
}
