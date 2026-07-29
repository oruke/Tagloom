package com.oruke.tagloom.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 验证 RichText 规范化和未知 Block 的向前兼容行为。
 */
class RichTextNormalizerTest {
    /**
     * 验证 Mark、属性、加注和相邻 Run 会生成唯一稳定表示。
     */
    @Test
    fun normalizeProducesStableRuns() {
        val annotationA = AnnotationId("annotation-a")
        val annotationB = AnnotationId("annotation-b")
        val source = RichText(
            runs = listOf(
                InlineRun(text = ""),
                InlineRun(
                    text = "Tag",
                    marks = listOf(
                        InlineMark(
                            type = InlineMarkTypes.LINK,
                            attributes = linkedMapOf("target" to "note", "kind" to "internal"),
                        ),
                        InlineMark(type = InlineMarkTypes.CODE),
                        InlineMark(type = InlineMarkTypes.CODE),
                    ),
                    annotationIds = listOf(annotationB, annotationA, annotationA),
                ),
                InlineRun(
                    text = "loom",
                    marks = listOf(
                        InlineMark(type = InlineMarkTypes.CODE),
                        InlineMark(
                            type = InlineMarkTypes.LINK,
                            attributes = linkedMapOf("kind" to "internal", "target" to "note"),
                        ),
                    ),
                    annotationIds = listOf(annotationA, annotationB),
                ),
            ),
        )

        val normalized = RichTextNormalizer.normalize(source)

        assertEquals(1, normalized.runs.size)
        assertEquals("Tagloom", normalized.plainText)
        assertEquals(
            listOf(InlineMarkTypes.CODE, InlineMarkTypes.LINK),
            normalized.runs.single().marks.map(InlineMark::type),
        )
        assertEquals(listOf(annotationA, annotationB), normalized.runs.single().annotationIds)
        assertEquals(
            listOf("kind", "target"),
            normalized.runs.single().marks.last().attributes.keys.toList(),
        )
    }

    /**
     * 验证未知 Mark 不会在规范化过程中被丢弃。
     */
    @Test
    fun normalizePreservesUnknownMark() {
        val unknown = InlineMark(
            type = "future-mark",
            attributes = mapOf("mode" to "future"),
        )

        val normalized = RichTextNormalizer.normalize(
            RichText(runs = listOf(InlineRun(text = "内容", marks = listOf(unknown)))),
        )

        assertEquals(listOf(unknown), normalized.runs.single().marks)
    }

    /**
     * 验证未知 Block payload 可以通过领域模型原样保存。
     */
    @Test
    fun unknownBlockContentKeepsRawJson() {
        val raw = buildJsonObject {
            put("_contentType", "future-content")
            put("payload", JsonPrimitive("保留"))
        }
        val block = Block(
            id = BlockId("block-future"),
            type = "future-block",
            orderKey = "a1",
            content = UnknownBlockContent(raw),
        )

        assertIs<UnknownBlockContent>(block.content)
        assertEquals(raw, block.content.raw)
        assertEquals(emptyList(), BlockValidator.validate(block))
    }
}
