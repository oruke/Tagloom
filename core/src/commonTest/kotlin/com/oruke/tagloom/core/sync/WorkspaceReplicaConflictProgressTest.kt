package com.oruke.tagloom.core.sync

import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.model.ChangeId
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.ObjectRevision
import com.oruke.tagloom.core.model.ObjectSnapshot
import com.oruke.tagloom.core.model.ObjectType
import com.oruke.tagloom.core.model.SnapshotHeader
import com.oruke.tagloom.core.model.TransactionId
import com.oruke.tagloom.core.workspace.ManifestObjectKind
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 验证字段处理的因果语义：独立选择取并集，相反并发选择重新待确认，后继选择才可覆盖。 */
class WorkspaceReplicaConflictProgressTest {
    /** 不同设备分别处理不同字段，处理进度与输入方向无关并最终关闭整个冲突。 */
    @Test
    fun joinsIndependentFieldResolutions() {
        val original = conflict("title", "description")
        val title = original.fields[0].fieldId
        val description = original.fields[1].fieldId
        val first = change("first")
        val second = change("second")
        val left = WorkspaceReplicaConflictProgress.resolve(original, setOf(title), MergeSide.LOCAL, first.changeId, "2026-01-01T00:00:02Z")
        val right = WorkspaceReplicaConflictProgress.resolve(original, setOf(description), MergeSide.REMOTE, second.changeId, "2026-01-01T00:00:03Z")
        val result = WorkspaceReplicaConflictProgress.merge(left, right, listOf(first, second))
        assertEquals(result, WorkspaceReplicaConflictProgress.merge(right, left, listOf(second, first)))
        assertEquals(ConflictStatus.RESOLVED, result.status)
        assertEquals(setOf(title, description), WorkspaceReplicaConflictProgress.resolvedFieldIds(result))
    }

    /** 同字段的相反并发确认必须保留两份证据；后续基于合并快照的确认才重新关闭。 */
    @Test
    fun reopensOpposingConcurrentChoicesAndAcceptsCausalReview() {
        val original = conflict("title")
        val field = original.fields.single().fieldId
        val first = change("keep-current")
        val second = change("choose-other")
        val left = WorkspaceReplicaConflictProgress.resolve(original, setOf(field), MergeSide.LOCAL, first.changeId, "2026-01-01T00:00:09Z")
        val right = WorkspaceReplicaConflictProgress.resolve(original, setOf(field), MergeSide.REMOTE, second.changeId, "2026-01-01T00:00:01Z")
        val reopened = WorkspaceReplicaConflictProgress.merge(left, right, listOf(first, second))
        assertEquals(ConflictStatus.UNRESOLVED, reopened.status)
        assertEquals(2, reopened.fieldResolutions.size)
        assertTrue(WorkspaceReplicaConflictProgress.resolvedFieldIds(reopened).isEmpty())
        assertEquals(reopened, WorkspaceReplicaConflictProgress.merge(right, left, listOf(second, first)))

        val merged = change("merged", listOf(first.changeId, second.changeId))
        val reviewed = change("reviewed", listOf(merged.changeId))
        val resolved = WorkspaceReplicaConflictProgress.resolve(reopened, setOf(field), MergeSide.REMOTE,
            reviewed.changeId, "2026-01-01T00:00:00Z")
        val propagated = WorkspaceReplicaConflictProgress.merge(resolved, left, listOf(first, second, merged, reviewed))
        assertEquals(ConflictStatus.RESOLVED, propagated.status)
        assertEquals(listOf(reviewed.changeId), propagated.fieldResolutions.map { it.changeId })
        assertEquals(setOf(field), WorkspaceReplicaConflictProgress.resolvedFieldIds(propagated))
    }

    /** 同字段并发选择相同版本无需人工重审，但两次正式 Change 证据仍然保留。 */
    @Test
    fun keepsMatchingConcurrentChoicesResolved() {
        val original = conflict("title")
        val field = original.fields.single().fieldId
        val first = change("first")
        val second = change("second")
        val left = WorkspaceReplicaConflictProgress.resolve(original, setOf(field), MergeSide.REMOTE, first.changeId, "2026-01-01T00:00:02Z")
        val right = WorkspaceReplicaConflictProgress.resolve(original, setOf(field), MergeSide.REMOTE, second.changeId, "2026-01-01T00:00:01Z")
        val result = WorkspaceReplicaConflictProgress.merge(left, right, listOf(first, second))
        assertEquals(ConflictStatus.RESOLVED, result.status)
        assertEquals(2, result.fieldResolutions.size)
        assertEquals(setOf(field), WorkspaceReplicaConflictProgress.resolvedFieldIds(result))
    }

    /** 旧历史的完整恢复保持整版已处理语义，不根据缺失的信息伪造逐字段选择。 */
    @Test
    fun preservesLegacyWholeObjectResolution() {
        val current = conflict("title")
        val legacy = current.copy(fields = emptyList())
        val action = change("legacy-restore")
        val restored = WorkspaceReplicaConflictProgress.resolve(legacy, emptySet(), MergeSide.LOCAL,
            action.changeId, "2026-01-01T00:00:01Z")
        val result = WorkspaceReplicaConflictProgress.merge(current, restored, listOf(action))
        assertEquals(restored, result)
        assertEquals(ConflictStatus.RESOLVED, result.status)
        assertTrue(result.fields.isEmpty())
        assertFalse(result.fieldResolutions.isNotEmpty())
    }

    /** 同一双版本在不同目录上下文产生不同解释时保守整体恢复，真实版本调包仍拒绝。 */
    @Test
    fun degradesDifferentExplanationsWithoutLosingResolutionEvidence() {
        val original = conflict("title")
        val action = change("reviewed-field")
        val reviewed = WorkspaceReplicaConflictProgress.resolve(original, setOf(original.fields.single().fieldId),
            MergeSide.LOCAL, action.changeId, "2026-01-01T00:00:01Z")
        val whole = original.copy(fields = emptyList(), reason = WorkspaceReplicaConflictReason.CATALOG_INVARIANT,
            temporaryWinner = MergeSide.REMOTE)
        val result = WorkspaceReplicaConflictProgress.merge(reviewed, whole, listOf(action))
        assertEquals(WorkspaceReplicaConflictReason.CATALOG_INVARIANT, result.reason)
        assertEquals(ConflictStatus.UNRESOLVED, result.status)
        assertTrue(result.fields.isEmpty())
        assertTrue(result.requiresWholeObjectResolution)
        assertEquals(listOf(action.changeId), result.fieldResolutions.map { it.changeId })
        assertEquals(result, WorkspaceReplicaConflictProgress.merge(whole, reviewed, listOf(action)))
        assertTrue(WorkspaceReplicaConflictProgress.merge(result, original, listOf(action)).fields.isEmpty())
        val tampered = whole.copy(remoteSnapshot = whole.remoteSnapshot.copy(payload = JsonPrimitive("different-version")))
        assertFailsWith<IllegalArgumentException> { WorkspaceReplicaConflictProgress.merge(reviewed, tampered, listOf(action)) }
    }

    /**
     * 构造完整双版本和稳定字段证据，避免使用墙钟顺序作为业务因果依据。
     * @param names 可以独立处理的定义字段名称。
     * @return 尚未处理的完整冲突历史。
     */
    private fun conflict(vararg names: String): WorkspaceReplicaConflict {
        val first = JsonObject(names.associateWith { JsonPrimitive("first-$it") })
        val second = JsonObject(names.associateWith { JsonPrimitive("second-$it") })
        return WorkspaceReplicaConflict("progress-conflict", ManifestObjectKind.PROPERTY, ObjectId("progress-object"),
            snapshot(first, "first"), snapshot(second, "second"), temporaryWinner = MergeSide.LOCAL,
            reason = WorkspaceReplicaConflictReason.CONCURRENT_VALUE, createdAt = "2026-01-01T00:00:00Z",
            fields = names.map { name -> WorkspaceReplicaFieldConflict(listOf(ReplicaFieldPathSegment(field = name)),
                localValue = first[name], remoteValue = second[name], temporaryWinner = MergeSide.LOCAL) })
    }

    /**
     * 构造保留字段值的认证快照模型。
     * @param value 该版本的完整字段内容。
     * @param id 版本 head 与 Actor 使用的脱敏身份后缀。
     * @return 可保存在加密历史中的快照。
     */
    private fun snapshot(value: JsonElement, id: String): ObjectSnapshot<JsonElement> = ObjectSnapshot(
        SnapshotHeader(1, ObjectId("progress-object"), ObjectType.PROPERTY, ObjectRevision(ActorId("actor-$id"), 1),
            ChangeId(id), createdAt = "2026-01-01T00:00:00Z", updatedAt = "2026-01-01T00:00:00Z"), value)

    /**
     * 构造通过父边表达先后的正式解决 Change。
     * @param id 当前 Change 的稳定脱敏身份。
     * @param parents 当前动作已观察到的父 heads。
     * @return 不依赖真实时钟的逻辑 Change。
     */
    private fun change(id: String, parents: List<ChangeId> = emptyList()): ObjectChange = ObjectChange(
        ChangeId(id), ObjectId("progress-object"), parents, ActorId("actor-$id"),
        HybridLogicalTime(1, 0, ActorId("actor-$id")), null, TransactionId("transaction-$id"), ChangeKind.MERGE,
        listOf(ChangeOperation("merge-replica-object", payload = JsonPrimitive("selected-value"))))
}
