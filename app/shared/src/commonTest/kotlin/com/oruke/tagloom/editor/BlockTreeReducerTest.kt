package com.oruke.tagloom.editor

import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.NoteValidator
import com.oruke.tagloom.core.model.RichText
import com.oruke.tagloom.core.model.RichTextBlockContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 验证 Block 树移动、嵌套和 tombstone 不改变稳定 ID 或破坏结构。
 */
class BlockTreeReducerTest {
    /**
     * 验证同级重排只交换顺序键且保持 ID。
     */
    @Test
    fun movesSiblingWithoutChangingIdentity() {
        val blocks = listOf(block("a", null, 0), block("b", null, 1))
        val moved = BlockTreeReducer.move(blocks, BlockId("b"), BlockMoveDirection.UP)
        assertEquals(listOf("b", "a"), BlockTreeReducer.orderedActive(moved).map { block -> block.id.value })
        assertEquals(setOf("a", "b"), moved.map { block -> block.id.value }.toSet())
    }

    /**
     * 验证缩进、提升后父边和同级顺序仍可通过 Note 校验。
     */
    @Test
    fun indentsAndOutdentsValidTree() {
        val blocks = listOf(block("a", null, 0), block("b", null, 1), block("c", null, 2))
        val indented = BlockTreeReducer.indent(blocks, BlockId("b"))
        assertEquals(BlockId("a"), indented.single { block -> block.id == BlockId("b") }.parentId)
        val outdented = BlockTreeReducer.outdent(indented, BlockId("b"))
        assertEquals(null, outdented.single { block -> block.id == BlockId("b") }.parentId)
        assertTrue(NoteValidator.validate(Note("树", emptyList(), blocks = outdented)).isEmpty())
    }

    /**
     * 验证删除父 Block 会级联 tombstone 后代而不销毁内容。
     */
    @Test
    fun tombstonesDescendants() {
        val blocks = listOf(block("a", null, 0), block("b", BlockId("a"), 0), block("c", null, 1))
        val deleted = BlockTreeReducer.tombstone(blocks, BlockId("a"))
        assertTrue(deleted.single { block -> block.id == BlockId("a") }.deleted)
        assertTrue(deleted.single { block -> block.id == BlockId("b") }.deleted)
        assertEquals(listOf("c"), BlockTreeReducer.orderedActive(deleted).map { block -> block.id.value })
        assertEquals(blocks[1].content, deleted.single { block -> block.id == BlockId("b") }.content)
    }

    /**
     * 构造一个最小段落 Block。
     *
     * @param id Block ID。
     * @param parentId 父 Block。
     * @param order 同级顺序。
     * @return 新 Block。
     */
    private fun block(
        id: String,
        parentId: BlockId?,
        order: Int,
    ): Block = Block(
        id = BlockId(id),
        type = BlockTypes.PARAGRAPH,
        parentId = parentId,
        orderKey = order.toString().padStart(10, '0'),
        content = RichTextBlockContent(RichText()),
    )
}
