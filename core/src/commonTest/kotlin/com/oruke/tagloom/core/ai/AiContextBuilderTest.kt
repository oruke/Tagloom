package com.oruke.tagloom.core.ai

import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.model.AttachmentBlockContent
import com.oruke.tagloom.core.model.BlobId
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.InlineRun
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.ObjectRevision
import com.oruke.tagloom.core.model.ObjectSnapshot
import com.oruke.tagloom.core.model.ObjectType
import com.oruke.tagloom.core.model.RichText
import com.oruke.tagloom.core.model.RichTextBlockContent
import com.oruke.tagloom.core.model.SnapshotHeader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 验证 AI 上下文只选择显式候选、遵守硬预算并默认排除附件原名。 */
class AiContextBuilderTest {
    /** 验证显式 Note 优先、附件只包含 caption 且字符预算不会被突破。 */
    @Test
    fun prioritizesExplicitNoteAndExcludesAttachmentFileName() {
        val preferredId = ObjectId("note-preferred")
        val preferred = snapshot(
            noteId = preferredId,
            updatedAt = "2026-07-12T00:00:00Z",
            blocks = listOf(
                paragraph("alpha"),
                Block(
                    id = BlockId("block-attachment"),
                    type = BlockTypes.FILE,
                    orderKey = "b",
                    content = AttachmentBlockContent(
                        blobId = BlobId("blob-private"),
                        fileName = "private-contract.pdf",
                        caption = RichText(listOf(InlineRun("caption"))),
                    ),
                ),
            ),
        )
        val newer = snapshot(
            noteId = ObjectId("note-newer"),
            updatedAt = "2026-07-13T00:00:00Z",
            blocks = listOf(paragraph("newer")),
        )

        val selection = AiContextBuilder.build(
            snapshots = listOf(newer, preferred),
            preferredNoteIds = setOf(preferredId),
            budget = AiContextBudget(maxNotes = 1, maxBlocks = 2, maxCharacters = 9),
        )

        assertEquals(setOf(preferredId), selection.blocks.map(AiContextBlock::noteId).toSet())
        assertEquals(9, selection.characterCount)
        assertTrue(selection.truncated)
        assertFalse(selection.blocks.joinToString { it.text }.contains("private-contract"))
    }

    /**
     * 创建测试 Note 快照。
     *
     * @param noteId Note ID。
     * @param updatedAt 快照更新时间。
     * @param blocks Note Blocks。
     * @return 测试快照。
     */
    private fun snapshot(
        noteId: ObjectId,
        updatedAt: String,
        blocks: List<Block>,
    ): ObjectSnapshot<Note> = ObjectSnapshot(
        header = SnapshotHeader(
            schemaVersion = 1,
            objectId = noteId,
            objectType = ObjectType.NOTE,
            revision = ObjectRevision(ActorId("actor-ai"), 1L),
            createdAt = "2026-07-10T00:00:00Z",
            updatedAt = updatedAt,
        ),
        payload = Note("Context note", emptyList(), blocks = blocks),
    )

    /**
     * 创建段落 Block。
     *
     * @param text 段落文本。
     * @return 测试段落。
     */
    private fun paragraph(text: String): Block = Block(
        id = BlockId("block-$text"),
        type = BlockTypes.PARAGRAPH,
        orderKey = "a",
        content = RichTextBlockContent(RichText(listOf(InlineRun(text)))),
    )
}
