package com.oruke.tagloom.core.sync

import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.ChangeId
import com.oruke.tagloom.core.model.InlineRun
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.RichText
import com.oruke.tagloom.core.model.RichTextBlockContent
import com.oruke.tagloom.core.model.TransactionId
import com.oruke.tagloom.core.protocol.CanonicalJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive

/** 验证 Note 级三方合并、临时胜者与完整冲突证据。 */
class NoteThreeWayMergeServiceTest {
    /** 测试 Note 对象。 */
    private val objectId = ObjectId("note-merge")

    /** 测试段落 Block。 */
    private val blockId = BlockId("block-body")

    /** 验证同一 RichText 内的非重叠编辑能直接进入合并快照。 */
    @Test
    fun nonOverlappingRichTextEditsProduceMergedNoteWithoutConflict() {
        val root = change("change-root", "actor-root", physicalTime = 1)
        val localChange = change("change-local", "actor-local", physicalTime = 10, parent = root.changeId)
        val remoteChange = change("change-remote", "actor-remote", physicalTime = 11, parent = root.changeId)
        val result = NoteThreeWayMergeService.merge(
            objectId = objectId,
            base = note("hello world"),
            local = NoteMergeVersion(note("hello brave world"), localChange),
            remote = NoteMergeVersion(note("hello world!"), remoteChange),
            knownChanges = listOf(root, localChange, remoteChange),
            mergedAt = "2026-07-13T00:00:00Z",
        )

        assertEquals("hello brave world!", result.note.blocks.single().richText())
        assertTrue(result.conflicts.isEmpty())
    }

    /** 验证重叠编辑采用较晚 HLC 作为临时值，并保留可解码的三方 Block。 */
    @Test
    fun overlappingRichTextEditsPreserveBaseLocalAndRemote() {
        val root = change("change-root", "actor-root", physicalTime = 1)
        val localChange = change("change-local", "actor-local", physicalTime = 10, parent = root.changeId)
        val remoteChange = change("change-remote", "actor-remote", physicalTime = 11, parent = root.changeId)
        val base = note("hello world")
        val local = note("hello earth")
        val remote = note("hello moon")

        val result = NoteThreeWayMergeService.merge(
            objectId = objectId,
            base = base,
            local = NoteMergeVersion(local, localChange),
            remote = NoteMergeVersion(remote, remoteChange),
            knownChanges = listOf(root, localChange, remoteChange),
            mergedAt = "2026-07-13T00:00:00Z",
        )

        val conflict = result.conflicts.single()
        assertEquals("hello moon", result.note.blocks.single().richText())
        assertEquals(MergeSide.REMOTE, conflict.temporaryWinner)
        assertEquals(base.blocks.single(), CanonicalJson.format.decodeFromJsonElement(Block.serializer(), conflict.baseValue))
        assertEquals(local.blocks.single(), CanonicalJson.format.decodeFromJsonElement(Block.serializer(), conflict.localValue))
        assertEquals(remote.blocks.single(), CanonicalJson.format.decodeFromJsonElement(Block.serializer(), conflict.remoteValue))
    }

    /** 验证同一 HLC 的删除与更新竞争时更新获胜，但删除候选仍进入冲突证据。 */
    @Test
    fun updateWinsConcurrentDeletionAtSameTime() {
        val root = change("change-root", "actor-root", physicalTime = 1)
        val localChange = change("change-delete", "actor-z", physicalTime = 10, parent = root.changeId)
        val remoteChange = change("change-update", "actor-a", physicalTime = 10, parent = root.changeId)
        val base = note("before")
        val local = base.copy(blocks = listOf(base.blocks.single().copy(deleted = true)))
        val remote = note("after")

        val result = NoteThreeWayMergeService.merge(
            objectId = objectId,
            base = base,
            local = NoteMergeVersion(local, localChange),
            remote = NoteMergeVersion(remote, remoteChange),
            knownChanges = listOf(root, localChange, remoteChange),
            mergedAt = "2026-07-13T00:00:00Z",
        )

        assertEquals("after", result.note.blocks.single().richText())
        assertEquals(MergeSide.REMOTE, result.conflicts.single().temporaryWinner)
    }

    /**
     * 创建包含一个段落的 Note。
     *
     * @param text 段落文本。
     * @return 测试 Note。
     */
    private fun note(text: String): Note = Note(
        title = "Merge note",
        directTagIds = emptyList(),
        blocks = listOf(
            Block(
                id = blockId,
                type = BlockTypes.PARAGRAPH,
                orderKey = "a",
                content = RichTextBlockContent(RichText(listOf(InlineRun(text)))),
            ),
        ),
    )

    /**
     * 读取测试段落纯文本。
     *
     * @return 段落纯文本。
     */
    private fun Block.richText(): String = (content as RichTextBlockContent).richText.plainText

    /**
     * 创建同一对象的测试 Change。
     *
     * @param id Change ID。
     * @param actor Actor ID。
     * @param physicalTime HLC 物理时间。
     * @param parent 可选父 Change。
     * @return 测试 Change。
     */
    private fun change(
        id: String,
        actor: String,
        physicalTime: Long,
        parent: ChangeId? = null,
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
            kind = ChangeKind.UPDATE,
            operations = listOf(ChangeOperation("edit", payload = JsonPrimitive(id))),
        )
    }
}
