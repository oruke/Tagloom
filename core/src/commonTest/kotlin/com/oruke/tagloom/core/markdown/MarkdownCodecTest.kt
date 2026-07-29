package com.oruke.tagloom.core.markdown

import com.oruke.tagloom.core.model.Annotation
import com.oruke.tagloom.core.model.AnnotationId
import com.oruke.tagloom.core.model.AnnotationKinds
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.InlineMark
import com.oruke.tagloom.core.model.InlineMarkTypes
import com.oruke.tagloom.core.model.InlineRun
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.NoteValidator
import com.oruke.tagloom.core.model.RichText
import com.oruke.tagloom.core.model.RichTextBlockContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 验证 Markdown 的常见 Block、单词级 code、扩展加注和有损提示。
 */
class MarkdownCodecTest {
    /**
     * 验证扩展 Markdown 能导入标题、待办、代码围栏与单词级加注。
     */
    @Test
    fun importsExtendedMarkdownIntoValidNote() {
        val result = MarkdownCodec.import(
            markdown = """
                # 示例笔记

                一段 **粗体**、`code` 与 {{annotation-a|术语}}。

                - [x] 已完成

                ```kotlin
                val answer = 42
                ```

                [^annotation-a]: 术语解释
            """.trimIndent(),
            options = options(),
        )

        assertEquals("示例笔记", result.note.title)
        assertEquals(3, result.note.blocks.size)
        assertTrue(result.note.blocks.first().let { block ->
            val content = block.content as RichTextBlockContent
            content.richText.runs.any { run -> run.marks.any { mark -> mark.type == InlineMarkTypes.CODE } } &&
                content.richText.runs.any { run -> AnnotationId("annotation-a") in run.annotationIds }
        })
        assertEquals("术语解释", result.note.annotations.getValue(AnnotationId("annotation-a")).body)
        assertTrue(NoteValidator.validate(result.note).isEmpty())
    }

    /**
     * 验证扩展模式保留加注，标准模式明确报告加注能力损失。
     */
    @Test
    fun exportsAnnotationsWithoutSilentLoss() {
        val note = Note(
            title = "加注示例",
            directTagIds = emptyList(),
            blocks = listOf(
                Block(
                    id = BlockId("block-a"),
                    type = BlockTypes.PARAGRAPH,
                    orderKey = "0000000000",
                    content = RichTextBlockContent(
                        RichText(
                            listOf(
                                InlineRun("普通 "),
                                InlineRun(
                                    text = "term",
                                    marks = listOf(InlineMark(InlineMarkTypes.CODE)),
                                    annotationIds = listOf(AnnotationId("annotation-a")),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            annotations = mapOf(
                AnnotationId("annotation-a") to Annotation(
                    id = AnnotationId("annotation-a"),
                    kind = AnnotationKinds.DEFINITION,
                    body = "definition",
                    createdAt = IMPORTED_AT,
                    updatedAt = IMPORTED_AT,
                ),
            ),
        )

        val extended = MarkdownCodec.export(note, MarkdownExportMode.TAGLOOM_EXTENDED)
        val standard = MarkdownCodec.export(note, MarkdownExportMode.STANDARD)

        assertTrue("{{annotation-a|`term`}}" in extended.markdown)
        assertTrue("[^annotation-a]: definition" in extended.markdown)
        assertTrue(extended.capabilityLosses.none { loss -> loss.code.contains("annotation") })
        assertTrue(standard.capabilityLosses.any { loss -> loss.code == "annotation_anchor_omitted" })
        assertTrue(standard.capabilityLosses.any { loss -> loss.code == "annotations_omitted" })
    }

    /**
     * 构造测试导入使用的确定性 ID 与时间配置。
     *
     * @return 可重复执行的 Markdown 导入选项。
     */
    private fun options(): MarkdownImportOptions = MarkdownImportOptions(
        defaultTitle = "未命名笔记",
        importedAt = IMPORTED_AT,
        blockIdFactory = { index -> BlockId("block-$index") },
    )

    companion object {
        /** 测试加注使用的固定 UTC 时间。 */
        private const val IMPORTED_AT: String = "2026-07-13T00:00:00Z"
    }
}
