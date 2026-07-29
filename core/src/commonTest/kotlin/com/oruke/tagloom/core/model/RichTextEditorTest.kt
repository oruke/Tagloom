package com.oruke.tagloom.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 验证 RichText 字符区间样式、加注与普通输入的语义保留。
 */
class RichTextEditorTest {
    /**
     * 验证只给一个单词应用 code，不影响两侧文本。
     */
    @Test
    fun appliesCodeToSingleWord() {
        val edited = RichTextEditor.addMark(
            richText = RichText(listOf(InlineRun("alpha beta gamma"))),
            startInclusive = 6,
            endExclusive = 10,
            mark = InlineMark(InlineMarkTypes.CODE),
        )

        assertEquals(listOf("alpha ", "beta", " gamma"), edited.runs.map(InlineRun::text))
        assertTrue(edited.runs[0].marks.isEmpty())
        assertEquals(InlineMarkTypes.CODE, edited.runs[1].marks.single().type)
        assertTrue(edited.runs[2].marks.isEmpty())
    }

    /**
     * 验证加注只锚定选区，随后在选区外输入不会扩展加注。
     */
    @Test
    fun annotationRangeDoesNotExpandDuringAdjacentInput() {
        val annotationId = AnnotationId("annotation-a")
        val annotated = RichTextEditor.attachAnnotation(
            richText = RichText(listOf(InlineRun("alpha beta"))),
            startInclusive = 6,
            endExclusive = 10,
            annotationId = annotationId,
        )
        val typed = RichTextEditor.replacePlainTextPreservingStyles(annotated, "alpha beta!")

        assertEquals("alpha beta!", typed.plainText)
        assertEquals(listOf(annotationId), typed.runs.first { run -> run.text == "beta" }.annotationIds)
        assertTrue(typed.runs.last().annotationIds.isEmpty())
    }

    /**
     * 验证样式内部输入继承 Mark，而两侧既有文本语义保持不变。
     */
    @Test
    fun insertedTextInheritsMarkAtCaret() {
        val source = RichText(
            listOf(
                InlineRun("a"),
                InlineRun("bc", marks = listOf(InlineMark(InlineMarkTypes.BOLD))),
                InlineRun("d"),
            ),
        )
        val edited = RichTextEditor.replacePlainTextPreservingStyles(source, "abXcd")

        assertEquals("abXcd", edited.plainText)
        assertTrue(edited.runs.first { run -> "X" in run.text }.marks.any { mark -> mark.type == InlineMarkTypes.BOLD })
    }
}
