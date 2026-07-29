package com.oruke.tagloom.core.sync

import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.model.ChangeId
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.TransactionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive

/**
 * 验证对象级 Change DAG 的 heads、因果关系和结构错误。
 */
class ChangeDagTest {
    /** 测试对象。 */
    private val objectId = ObjectId("object")

    /** 测试参与者。 */
    private val actorId = ActorId("device")

    /**
     * 验证并发分支会形成多个 heads，Merge Change 会恢复单 head。
     */
    @Test
    fun concurrentBranchesAndMergeProduceExpectedHeads() {
        val root = change("a")
        val left = change("b", "a")
        val right = change("c", "a")
        val branches = listOf(root, left, right)

        val branchAnalysis = ObjectChangeDag.analyze(branches)

        assertTrue(branchAnalysis.valid)
        assertEquals(listOf(ChangeId("a"), ChangeId("b"), ChangeId("c")), branchAnalysis.topologicalOrder)
        assertEquals(listOf(ChangeId("b"), ChangeId("c")), branchAnalysis.heads)
        assertTrue(ObjectChangeDag.isAncestor(ChangeId("a"), ChangeId("c"), branches))
        assertFalse(ObjectChangeDag.isAncestor(ChangeId("c"), ChangeId("a"), branches))

        val merge = change(
            id = "d",
            parents = arrayOf("c", "b"),
            kind = ChangeKind.MERGE,
        )
        val mergedAnalysis = ObjectChangeDag.analyze(branches + merge)

        assertTrue(mergedAnalysis.valid)
        assertEquals(listOf(ChangeId("d")), mergedAnalysis.heads)
        assertEquals(listOf(ChangeId("b"), ChangeId("c")), merge.parentChangeIds)
    }

    /**
     * 验证缺失父节点会返回明确结构错误。
     */
    @Test
    fun missingParentReturnsError() {
        val analysis = ObjectChangeDag.analyze(listOf(change("b", "missing")))

        assertFalse(analysis.valid)
        assertEquals(ChangeDagErrorCode.MISSING_PARENT, analysis.errors.single().code)
        assertEquals(ChangeId("missing"), analysis.errors.single().parentChangeId)
    }

    /**
     * 验证循环父边不能生成完整拓扑顺序。
     */
    @Test
    fun cyclicParentsReturnError() {
        val analysis = ObjectChangeDag.analyze(
            listOf(
                change("a", "b"),
                change("b", "a"),
            ),
        )

        assertFalse(analysis.valid)
        assertTrue(analysis.errors.any { error -> error.code == ChangeDagErrorCode.CYCLE })
        assertEquals(emptyList(), analysis.topologicalOrder)
    }

    /**
     * 验证 HLC 依次按物理时间、逻辑计数器和 Actor ID 排序。
     */
    @Test
    fun hybridTimeComparisonIsDeterministic() {
        val earlier = HybridLogicalTime(physicalTimeMillis = 10, logicalCounter = 5, actorId = ActorId("z"))
        val later = HybridLogicalTime(physicalTimeMillis = 11, logicalCounter = 0, actorId = ActorId("a"))
        val tieBreaker = HybridLogicalTime(physicalTimeMillis = 11, logicalCounter = 0, actorId = ActorId("b"))

        assertTrue(ObjectChangeDag.compareHybridTime(earlier, later) < 0)
        assertTrue(ObjectChangeDag.compareHybridTime(later, tieBreaker) < 0)
    }

    /**
     * 创建测试 Change，并规范化父节点顺序。
     *
     * @param id Change ID。
     * @param parents 父 Change ID 文本。
     * @param kind Change 业务类型。
     * @return 可用于 DAG 测试的 Change。
     */
    private fun change(
        id: String,
        vararg parents: String,
        kind: ChangeKind = ChangeKind.UPDATE,
    ): ObjectChange = ObjectChange(
        changeId = ChangeId(id),
        objectId = objectId,
        parentChangeIds = ObjectChangeDag.mergeParentIds(parents.map(::ChangeId)),
        actorId = actorId,
        hybridLogicalTime = HybridLogicalTime(
            physicalTimeMillis = id.first().code.toLong(),
            logicalCounter = 0,
            actorId = actorId,
        ),
        baseSnapshotRevision = null,
        transactionId = TransactionId("tx-$id"),
        kind = kind,
        operations = listOf(
            ChangeOperation(
                type = "set-title",
                payload = JsonPrimitive(id),
            ),
        ),
    )
}
