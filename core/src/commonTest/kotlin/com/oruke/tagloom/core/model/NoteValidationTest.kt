package com.oruke.tagloom.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 验证笔记规范化、Block 树和加注引用契约。
 */
class NoteValidationTest {
    /**
     * 验证规范化会覆盖所有支持 RichText 的 payload，并保持 Block 身份不变。
     */
    @Test
    fun normalizeCoversAllRichTextPayloads() {
        val mark = InlineMark(InlineMarkTypes.CODE)
        val sourceRuns = listOf(
            InlineRun(text = "Tag", marks = listOf(mark)),
            InlineRun(text = "loom", marks = listOf(mark)),
        )
        val note = noteOf(
            blocks = listOf(
                block("paragraph", "a", RichTextBlockContent(RichText(sourceRuns))),
                block("heading", "b", HeadingBlockContent(2, RichText(sourceRuns))),
                block("todo", "c", TodoBlockContent(false, RichText(sourceRuns))),
                block(
                    "file",
                    "d",
                    AttachmentBlockContent(
                        blobId = BlobId("blob-a"),
                        fileName = "example.txt",
                        caption = RichText(sourceRuns),
                    ),
                ),
            ),
        )

        val normalized = NoteNormalizer.normalize(note)

        assertEquals(note.blocks.map(Block::id), normalized.blocks.map(Block::id))
        normalized.blocks.forEach { normalizedBlock ->
            val richText = when (val content = normalizedBlock.content) {
                is RichTextBlockContent -> content.richText
                is HeadingBlockContent -> content.richText
                is TodoBlockContent -> content.richText
                is AttachmentBlockContent -> content.caption
                else -> error("测试夹具包含了不支持 RichText 的内容")
            }
            assertEquals(1, richText.runs.size)
            assertEquals("Tagloom", richText.plainText)
        }
    }

    /**
     * 验证缺失父节点、父子循环和同级排序冲突都会被定位。
     */
    @Test
    fun validateDetectsBrokenBlockTree() {
        val first = block(
            type = "paragraph",
            orderKey = "same",
            content = RichTextBlockContent(RichText()),
            id = BlockId("sibling-first"),
        )
        val second = block(
            type = "paragraph",
            orderKey = "same",
            content = RichTextBlockContent(RichText()),
            id = BlockId("sibling-second"),
        )
        val cycleA = block(
            type = "paragraph",
            orderKey = "cycle-a",
            content = RichTextBlockContent(RichText()),
            parentId = BlockId("cycle-b"),
            id = BlockId("cycle-a"),
        )
        val cycleB = block(
            type = "paragraph",
            orderKey = "cycle-b",
            content = RichTextBlockContent(RichText()),
            parentId = cycleA.id,
            id = BlockId("cycle-b"),
        )
        val missing = block(
            type = "paragraph",
            orderKey = "missing",
            content = RichTextBlockContent(RichText()),
            parentId = BlockId("not-found"),
        )

        val issues = NoteValidator.validate(noteOf(listOf(first, second, cycleA, cycleB, missing)))

        assertEquals(2, issues.count { it.code == NoteValidationCodes.DUPLICATE_SIBLING_ORDER })
        assertEquals(2, issues.count { it.code == NoteValidationCodes.PARENT_CYCLE })
        assertEquals(1, issues.count { it.code == NoteValidationCodes.MISSING_PARENT })
    }

    /**
     * 验证 tombstone 可保留删除前排序键，不与复用该位置的活动 Block 冲突。
     */
    @Test
    fun validateAllowsTombstoneToRetainActiveSiblingOrder() {
        val deleted = block(
            type = "paragraph",
            orderKey = "same",
            content = RichTextBlockContent(RichText()),
            id = BlockId("deleted"),
        ).copy(deleted = true)
        val active = block(
            type = "paragraph",
            orderKey = "same",
            content = RichTextBlockContent(RichText()),
            id = BlockId("active"),
        )

        val issues = NoteValidator.validate(noteOf(listOf(deleted, active)))

        assertTrue(issues.none { it.code == NoteValidationCodes.DUPLICATE_SIBLING_ORDER })
    }

    /**
     * 验证缺失加注与孤立状态不一致均会被拒绝。
     */
    @Test
    fun validateDetectsAnnotationReferenceFailures() {
        val missingId = AnnotationId("annotation-missing")
        val referencedOrphanId = AnnotationId("annotation-referenced-orphan")
        val activeWithoutAnchorId = AnnotationId("annotation-active-without-anchor")
        val richText = RichText(
            listOf(InlineRun("内容", annotationIds = listOf(missingId, referencedOrphanId))),
        )
        val annotations = listOf(
            annotation(referencedOrphanId, orphaned = true),
            annotation(activeWithoutAnchorId, orphaned = false),
        ).associateBy(Annotation::id)

        val issues = NoteValidator.validate(
            noteOf(
                blocks = listOf(block("paragraph", "a", RichTextBlockContent(richText))),
                annotations = annotations,
            ),
        )

        assertTrue(issues.any { it.code == NoteValidationCodes.MISSING_ANNOTATION && it.annotationId == missingId })
        assertTrue(
            issues.any {
                it.code == NoteValidationCodes.REFERENCED_ANNOTATION_ORPHANED &&
                    it.annotationId == referencedOrphanId
            },
        )
        assertTrue(
            issues.any {
                it.code == NoteValidationCodes.UNREFERENCED_ANNOTATION_ACTIVE &&
                    it.annotationId == activeWithoutAnchorId
            },
        )
    }

    /**
     * 创建测试用最小笔记。
     *
     * @param blocks 笔记 Block。
     * @param annotations 笔记加注映射。
     * @return 满足实体构造约束的笔记。
     */
    private fun noteOf(
        blocks: List<Block>,
        annotations: Map<AnnotationId, Annotation> = emptyMap(),
    ): Note = Note(
        title = "示例",
        directTagIds = emptyList(),
        blocks = blocks,
        annotations = annotations,
    )

    /**
     * 创建带唯一默认 ID 的测试 Block。
     *
     * @param type Block 类型。
     * @param orderKey 同级排序键。
     * @param content Block payload。
     * @param parentId 父 Block。
     * @param id Block 标识。
     * @return 测试 Block。
     */
    private fun block(
        type: String,
        orderKey: String,
        content: BlockContent,
        parentId: BlockId? = null,
        id: BlockId = BlockId("block-$type-$orderKey"),
    ): Block = Block(
        id = id,
        type = type,
        parentId = parentId,
        orderKey = orderKey,
        content = content,
    )

    /**
     * 创建测试加注。
     *
     * @param id 加注标识。
     * @param orphaned 是否处于待清理状态。
     * @return 测试加注。
     */
    private fun annotation(
        id: AnnotationId,
        orphaned: Boolean,
    ): Annotation = Annotation(
        id = id,
        kind = AnnotationKinds.NOTE,
        body = "说明",
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
        orphaned = orphaned,
    )
}
