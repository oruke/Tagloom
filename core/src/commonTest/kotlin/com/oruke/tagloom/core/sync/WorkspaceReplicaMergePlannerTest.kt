package com.oruke.tagloom.core.sync

import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.ChangeId
import com.oruke.tagloom.core.model.InlineRun
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.ObjectRevision
import com.oruke.tagloom.core.model.ObjectSnapshot
import com.oruke.tagloom.core.model.ObjectType
import com.oruke.tagloom.core.model.RichText
import com.oruke.tagloom.core.model.RichTextBlockContent
import com.oruke.tagloom.core.model.SnapshotHeader
import com.oruke.tagloom.core.model.TabBlockContent
import com.oruke.tagloom.core.model.TabsBlockContent
import com.oruke.tagloom.core.model.TransactionId
import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.workspace.ManifestObjectKind
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 用完整确认点候选验证快进、离线正文合并、冲突保留、删除规则与重复同步。 */
class WorkspaceReplicaMergePlannerTest {
    /** 测试使用的稳定对象身份。 */
    private val objectId = ObjectId("replica-note")

    /** 所有冲突共用的脱敏 UTC 创建时间。 */
    private val mergedAt = "2026-01-01T00:00:10Z"

    /** 新对象第一次快进，重复交换相同确认点保持不写入。 */
    @Test
    fun fastForwardsNewObjectAndKeepsRepeatedCheckpoint() {
        val change = change("created", "device-a", 1, noteJson("Stored note"))
        val remote = replica(change, noteJson("Stored note"))
        val first = plan(emptyList(), listOf(remote), listOf(change)).objects.single()
        assertEquals(WorkspaceReplicaAction.FAST_FORWARD, first.action)
        assertEquals(remote.snapshot, first.snapshot)
        val repeated = plan(listOf(remote), listOf(remote), listOf(change))
        assertEquals(WorkspaceReplicaAction.KEEP_LOCAL, repeated.objects.single().action)
        assertTrue(repeated.conflicts.isEmpty())
    }

    /** 同一段落的两处不重叠离线修改自动组合，后续已合并 head 覆盖原分支。 */
    @Test
    fun mergesNonOverlappingTextAndKeepsAlreadyMergedDescendant() {
        val root = change("root", "device-root", 1, noteJson("hello world"))
        val left = change("left", "device-a", 2, noteJson("hello brave world"), listOf(root.changeId))
        val right = change("right", "device-b", 3, noteJson("hello world!"), listOf(root.changeId))
        val history = listOf(root, left, right)
        val first = plan(listOf(replica(left)), listOf(replica(right)), history).objects.single()
        assertEquals(WorkspaceReplicaAction.MERGE, first.action)
        assertEquals("hello brave world!", noteText(first.snapshot.payload))
        assertEquals(listOf(left.changeId, right.changeId), first.parentChangeIds)
        assertTrue(first.conflicts.isEmpty())

        val merge = change("merged", "device-a", 4, first.snapshot.payload, first.parentChangeIds,
            kind = ChangeKind.MERGE, operation = "merge-replica-object")
        val merged = replica(merge, first.snapshot.payload)
        val repeated = plan(listOf(merged), listOf(replica(right)), history + merge)
        assertEquals(WorkspaceReplicaAction.KEEP_LOCAL, repeated.objects.single().action)
        assertTrue(repeated.conflicts.isEmpty())
    }

    /** 相同区间的修改保留完整双方快照与真实基线，序列化后仍可恢复败方。 */
    @Test
    fun retainsCompleteSnapshotsForOverlappingText() {
        val root = change("root", "device-root", 1, noteJson("hello world"))
        val left = change("left", "device-a", 2, noteJson("hello earth"), listOf(root.changeId))
        val right = change("right", "device-b", 3, noteJson("hello moon"), listOf(root.changeId))
        val local = replica(left)
        val remote = replica(right)
        val decision = plan(listOf(local), listOf(remote), listOf(root, left, right)).objects.single()
        assertEquals("hello moon", noteText(decision.snapshot.payload))
        val conflict = decision.conflicts.single()
        assertEquals(local.snapshot, conflict.localSnapshot)
        assertEquals(remote.snapshot, conflict.remoteSnapshot)
        assertEquals(root.operations.single().payload, conflict.basePayload)
        assertEquals(MergeSide.REMOTE, conflict.temporaryWinner)
        assertEquals(WorkspaceReplicaConflictReason.CONCURRENT_VALUE, conflict.reason)
        val restored = CanonicalJson.decodeFromString(WorkspaceReplicaConflict.serializer(),
            CanonicalJson.encodeToString(WorkspaceReplicaConflict.serializer(), conflict))
        assertEquals("hello earth", noteText(restored.localSnapshot.payload))
        assertEquals(ConflictStatus.UNRESOLVED, restored.status)
    }

    /** 对象级删除与更新同 HLC 时更新获胜，较晚删除仍可胜出且不丢更新版本。 */
    @Test
    fun handlesDeletedSnapshotHeaderWithoutLosingUpdate() {
        val root = change("root", "device-root", 1, noteJson("before"))
        val deletion = change("deleted", "device-z", 5, noteJson("before"), listOf(root.changeId), ChangeKind.DELETE)
        val update = change("updated", "device-a", 5, noteJson("after"), listOf(root.changeId))
        val updated = plan(listOf(replica(deletion, deleted = true)), listOf(replica(update)),
            listOf(root, deletion, update)).objects.single()
        assertFalse(updated.snapshot.header.deleted)
        assertEquals("after", noteText(updated.snapshot.payload))
        assertEquals(WorkspaceReplicaConflictReason.DELETE_UPDATE, updated.conflicts.single().reason)
        assertTrue(updated.conflicts.single().localSnapshot.header.deleted)

        val laterDeletion = deletion.copy(hybridLogicalTime = deletion.hybridLogicalTime.copy(physicalTimeMillis = 6))
        val deleted = plan(listOf(replica(laterDeletion, deleted = true)), listOf(replica(update)),
            listOf(root, laterDeletion, update)).objects.single()
        assertTrue(deleted.snapshot.header.deleted)
        assertEquals("after", noteText(deleted.conflicts.single().remoteSnapshot.payload))
    }

    /** 非 Note 对象并发采用确定性整对象选择，交换本地远端方向仍选择同一版本。 */
    @Test
    fun preservesBothSchemaVersionsAndChoosesSameWinnerOnBothDevices() {
        val base = change("root", "device-root", 1, JsonPrimitive("base"), operation = "replace-property-definition")
        val left = change("left", "device-a", 2, JsonPrimitive("local schema"), listOf(base.changeId),
            operation = "replace-property-definition")
        val right = change("right", "device-b", 3, JsonPrimitive("remote schema"), listOf(base.changeId),
            operation = "replace-property-definition")
        val local = replica(left, kind = ManifestObjectKind.PROPERTY)
        val remoteVersion = replica(right, kind = ManifestObjectKind.PROPERTY)
        val remote = remoteVersion.copy(snapshot = remoteVersion.snapshot.copy(
            header = remoteVersion.snapshot.header.copy(updatedAt = "2026-01-01T00:00:12Z")))
        val history = listOf(base, left, right)
        val forward = plan(listOf(local), listOf(remote), history).objects.single()
        val reversed = plan(listOf(remote), listOf(local), history, "2026-01-02T00:00:00Z").objects.single()
        assertEquals(JsonPrimitive("remote schema"), forward.snapshot.payload)
        assertEquals(forward.snapshot.payload, reversed.snapshot.payload)
        assertEquals(forward.parentChangeIds, reversed.parentChangeIds)
        assertEquals(forward.conflicts.single().conflictId, reversed.conflicts.single().conflictId)
        assertEquals(forward.conflicts, reversed.conflicts)
        assertEquals(remote.snapshot.header.updatedAt, forward.conflicts.single().createdAt)
        assertEquals(local.snapshot, forward.conflicts.single().localSnapshot)
        assertEquals(remote.snapshot, forward.conflicts.single().remoteSnapshot)
    }

    /** 被压缩或丢失的祖先不得用当前 Note 冒充，候选只能临时选择并完整保留。 */
    @Test
    fun doesNotInventBaseWhenCommonAncestorCannotBeReplayed() {
        val missing = ChangeId("missing-root")
        val left = change("left", "device-a", 2, noteJson("hello brave world"), listOf(missing))
        val right = change("right", "device-b", 3, noteJson("hello world!"), listOf(missing))
        val result = plan(listOf(replica(left)), listOf(replica(right)), listOf(left, right)).objects.single()
        assertEquals("hello world!", noteText(result.snapshot.payload))
        assertEquals(WorkspaceReplicaConflictReason.NO_COMMON_ANCESTOR, result.conflicts.single().reason)
        assertEquals(null, result.conflicts.single().basePayload)
    }

    /** 新格式完整 Merge Change 可作为下一轮离线编辑的真实共同基线。 */
    @Test
    fun replaysCompleteMergeOperationAsNextCommonBase() {
        val merged = change("merged", "device-root", 2, noteJson("hello world"),
            kind = ChangeKind.MERGE, operation = "merge-replica-object")
        val left = change("left", "device-a", 3, noteJson("hello brave world"), listOf(merged.changeId))
        val right = change("right", "device-b", 4, noteJson("hello world!"), listOf(merged.changeId))
        val result = plan(listOf(replica(left)), listOf(replica(right)), listOf(merged, left, right)).objects.single()
        assertEquals("hello brave world!", noteText(result.snapshot.payload))
        assertTrue(result.conflicts.isEmpty())
    }

    /** 并发产生相同业务值仍连接两个 heads，避免每次同步都重复处理同一分叉。 */
    @Test
    fun joinsConcurrentEqualValuesWithoutCreatingConflict() {
        val left = change("left", "device-a", 2, noteJson("same"))
        val right = change("right", "device-b", 3, noteJson("same"))
        val result = plan(listOf(replica(left)), listOf(replica(right)), listOf(left, right)).objects.single()
        assertEquals(WorkspaceReplicaAction.MERGE, result.action)
        assertEquals(listOf(left.changeId, right.changeId), result.parentChangeIds)
        assertTrue(result.conflicts.isEmpty())
    }

    /** 默认页签保持不变时，正文离线合并不能因旧合并器遗漏字段而将其清空。 */
    @Test
    fun preservesDefaultTabWhileMergingTextInsideIt() {
        val root = change("root", "device-root", 1, tabsJson("hello world"))
        val left = change("left", "device-a", 2, tabsJson("hello brave world"), listOf(root.changeId))
        val right = change("right", "device-b", 3, tabsJson("hello world!"), listOf(root.changeId))
        val result = plan(listOf(replica(left)), listOf(replica(right)), listOf(root, left, right)).objects.single()
        val note = CanonicalJson.format.decodeFromJsonElement(Note.serializer(), result.snapshot.payload)
        assertEquals(BlockId("tab"), note.defaultTabId)
        assertEquals("hello brave world!", (note.blocks.single { it.id == BlockId("body") }.content as RichTextBlockContent).richText.plainText)
        assertTrue(result.conflicts.isEmpty())
    }

    /**
     * 以稳定冲突身份执行真实纯规划入口。
     * @param local 本地完整候选。
     * @param remote 远端完整候选。
     * @param history 已认证 Change 集合。
     * @param at 当前设备本轮合并时间，不能改变稳定冲突历史。
     * @return 对象合并规划。
     */
    private fun plan(local: List<WorkspaceReplicaObject>, remote: List<WorkspaceReplicaObject>, history: List<ObjectChange>, at: String = mergedAt) =
        WorkspaceReplicaMergePlanner.plan(local, remote, history, at) { _, _, left, right ->
            "conflict-" + listOfNotNull(left?.value, right?.value).sorted().joinToString("-")
        }

    /**
     * 创建一个持有真实快照头的测试候选。
     * @param change 候选对应的 head。
     * @param payload 完整业务 JSON，默认取 Change operation。
     * @param deleted 对象级删除标记。
     * @param kind 对象 manifest 类别。
     * @return 可交给规划器的完整候选。
     */
    private fun replica(
        change: ObjectChange,
        payload: JsonElement = requireNotNull(change.operations.single().payload),
        deleted: Boolean = false,
        kind: ManifestObjectKind = ManifestObjectKind.NOTE,
    ): WorkspaceReplicaObject = WorkspaceReplicaObject(kind, ObjectSnapshot(
        SnapshotHeader(1, objectId, if (kind == ManifestObjectKind.NOTE) ObjectType.NOTE else ObjectType.PROPERTY,
            ObjectRevision(change.actorId, change.hybridLogicalTime.physicalTimeMillis), change.changeId,
            createdAt = "2026-01-01T00:00:00Z", updatedAt = mergedAt, deleted = deleted), payload,
    ))

    /**
     * 创建保存完整 payload 的脱敏 Change。
     * @param id 逻辑 Change ID。
     * @param actor 产生该版本的设备。
     * @param time HLC 物理时间。
     * @param payload 完整业务 JSON。
     * @param parents 逻辑父 heads。
     * @param kind 创建、更新、删除或合并语义。
     * @param operation 完整替换操作协议名。
     * @return 测试逻辑 Change。
     */
    private fun change(
        id: String,
        actor: String,
        time: Long,
        payload: JsonElement,
        parents: List<ChangeId> = emptyList(),
        kind: ChangeKind = ChangeKind.UPDATE,
        operation: String = "replace-note",
    ): ObjectChange = ObjectChange(ChangeId(id), objectId, parents, ActorId(actor), HybridLogicalTime(time, 0, ActorId(actor)),
        null, TransactionId("transaction-$id"), kind, listOf(ChangeOperation(operation, payload = payload)))

    /**
     * 创建只有一个文本段落的规范测试 Note。
     * @param text 段落文本。
     * @return 完整 Note JSON。
     */
    private fun noteJson(text: String): JsonElement = CanonicalJson.format.encodeToJsonElement(Note.serializer(), Note(
        title = "Replica example", directTagIds = emptyList(), blocks = listOf(Block(BlockId("body"), BlockTypes.PARAGRAPH,
            orderKey = "a", content = RichTextBlockContent(RichText(listOf(InlineRun(text)))))),
    ))

    /**
     * 构造默认页签下含正文的完整 Note，用于防止已知扩展字段在同步合并中丢失。
     * @param text 默认页签内段落文本。
     * @return 带 Tabs 树和默认页签的完整 Note JSON。
     */
    private fun tabsJson(text: String): JsonElement {
        val paragraph = CanonicalJson.format.decodeFromJsonElement(Note.serializer(), noteJson(text)).blocks.single()
        return CanonicalJson.format.encodeToJsonElement(Note.serializer(), Note("Tabs", emptyList(),
            defaultTabId = BlockId("tab"), blocks = listOf(
                Block(BlockId("tabs"), BlockTypes.TABS, orderKey = "a", content = TabsBlockContent),
                Block(BlockId("tab"), BlockTypes.TAB, parentId = BlockId("tabs"), orderKey = "a",
                    content = TabBlockContent(RichText(listOf(InlineRun("Overview"))))),
                paragraph.copy(parentId = BlockId("tab")),
            )))
    }

    /**
     * 解码规划结果并读取用户可见的段落内容。
     * @param payload 完整 Note JSON。
     * @return 单段落纯文本。
     */
    private fun noteText(payload: JsonElement): String =
        (CanonicalJson.format.decodeFromJsonElement(Note.serializer(), payload).blocks.single().content as RichTextBlockContent)
            .richText.plainText
}
