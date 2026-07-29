package com.oruke.tagloom.core.sync

import com.oruke.tagloom.core.model.AnnotationId
import com.oruke.tagloom.core.model.InlineMark
import com.oruke.tagloom.core.model.InlineMarkTypes
import com.oruke.tagloom.core.model.InlineRun
import com.oruke.tagloom.core.model.RichText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 验证字符内容、行内样式与加注锚点参与同一套三方合并。 */
class RichTextThreeWayMergeTest {
    /** 验证两个互不重叠的文本插入可以自动组合。 */
    @Test
    fun nonOverlappingTextInsertionsAreCombined() {
        val result = RichTextThreeWayMerge.merge(
            base = richText("hello world"),
            local = richText("hello brave world"),
            remote = richText("hello world!"),
        )

        assertFalse(result.conflicted)
        assertEquals("hello brave world!", result.merged?.plainText)
    }

    /** 验证不同单词上的 code 样式与加注能够自动组合且不会降级为纯文本。 */
    @Test
    fun nonOverlappingStyleAndAnnotationChangesAreCombined() {
        val code = InlineMark(InlineMarkTypes.CODE)
        val annotationId = AnnotationId("annotation-beta")
        val result = RichTextThreeWayMerge.merge(
            base = richText("alpha beta"),
            local = RichText(
                listOf(
                    InlineRun("alpha", marks = listOf(code)),
                    InlineRun(" beta"),
                ),
            ),
            remote = RichText(
                listOf(
                    InlineRun("alpha "),
                    InlineRun("beta", annotationIds = listOf(annotationId)),
                ),
            ),
        )

        assertFalse(result.conflicted)
        assertEquals(listOf(code), result.merged?.runs?.first()?.marks)
        assertEquals(listOf(annotationId), result.merged?.runs?.last()?.annotationIds)
    }

    /** 验证同一区间的不同替换不会静默覆盖任何一侧。 */
    @Test
    fun overlappingReplacementsProduceConflict() {
        val result = RichTextThreeWayMerge.merge(
            base = richText("hello world"),
            local = richText("hello earth"),
            remote = richText("hello moon"),
        )

        assertTrue(result.conflicted)
        assertNull(result.merged)
    }

    /**
     * 创建无样式 RichText。
     *
     * @param text 文本内容。
     * @return 单 Run RichText。
     */
    private fun richText(text: String): RichText = RichText(listOf(InlineRun(text)))
}
