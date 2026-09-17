package com.oruke.tagloom.editor

import com.oruke.tagloom.core.model.*
import kotlin.test.*

/** 验证任意同级落点仍保持子树、内容和 tombstone，并防御过期目标。 */
class BlockReorderingTest {
    /** 多行父块跨多个同级移动，既不丢失后代也不复活已删除数据。 */
    @Test
    fun movesWholeSubtreeAndRepairsDuplicateOrderKeys() {
        val first = block("first")
        val parent = block("parent")
        val child = block("child").copy(parentId = parent.id)
        val last = block("last").copy(orderKey = "1")
        val removed = block("removed").copy(deleted = true)
        val original = listOf(first, parent, child, last, removed)
        val moved = reorderSiblingBlocks(original, parent.id, last.id, after = true)
        assertEquals(
            listOf("first", "last", "parent", "child"),
            BlockTreeReducer.orderedActive(moved).map { it.id.value })
        assertEquals(child, moved.single { it.id == child.id })
        assertEquals(removed, moved.single { it.id == removed.id })
        assertEquals(original.associate { it.id to it.content }, moved.associate { it.id to it.content })
        assertTrue(NoteValidator.validate(Note("Example", emptyList(), blocks = moved)).isEmpty())
        val returned = reorderSiblingBlocks(moved, parent.id, first.id, after = false)
        assertEquals(
            listOf("parent", "child", "first", "last"),
            BlockTreeReducer.orderedActive(returned).map { it.id.value })
    }

    /** 失效、跨父级、自身或无变化的目标都不得制造新的编辑结果。 */
    @Test
    fun ignoresInvalidAndUnchangedDrops() {
        val first = block("first")
        val child = block("child").copy(parentId = first.id)
        val second = block("second").copy(orderKey = "1")
        val blocks = listOf(first, child, second)
        assertSame(blocks, reorderSiblingBlocks(blocks, first.id, first.id, true))
        assertSame(blocks, reorderSiblingBlocks(blocks, first.id, child.id, true))
        assertSame(blocks, reorderSiblingBlocks(blocks, first.id, BlockId("missing"), false))
        assertSame(blocks, reorderSiblingBlocks(blocks, first.id, second.id, false))
    }

    /**
     * 构造带非空内容的合成段落。
     * @param id 测试稳定身份与正文。
     * @return 使用相同初始顺序键的 Block，用于覆盖旧快照重复键。
     */
    private fun block(id: String): Block = Block(
        BlockId(id), BlockTypes.PARAGRAPH, orderKey = "0",
        content = RichTextBlockContent(RichText(listOf(InlineRun(id))))
    )
}
