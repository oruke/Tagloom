package com.oruke.tagloom.editor

import com.oruke.tagloom.core.model.Annotation
import com.oruke.tagloom.core.model.AnnotationId
import com.oruke.tagloom.core.model.AnnotationKinds
import com.oruke.tagloom.core.model.BaseTableBlockContent
import com.oruke.tagloom.core.model.BaseTableTextCell
import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.HeadingBlockContent
import com.oruke.tagloom.core.model.InlineRun
import com.oruke.tagloom.core.model.NoteValidator
import com.oruke.tagloom.core.model.RichText
import com.oruke.tagloom.core.model.RichTextBlockContent
import com.oruke.tagloom.core.model.TodoBlockContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 验证真实 Markdown 粘贴操作保留旧加注正文、维护孤立状态并隔离导入身份。 */
class MarkdownPasteAnnotationTest {
    /** 表格替换最后一个旧锚点后保留正文并标记孤立，使最终笔记通过快照结构校验。 */
    @Test
    fun keepsAnnotationBodyWhenTableReplacesItsLastAnchor() {
        val state = createAnnotatedState()
        val target = state.selectedDocument!!.note.blocks.single()

        assertNotNull(state.pasteMarkdownAtBlock(target.id, 0, ORIGINAL_TEXT.length, TABLE))

        val note = state.selectedDocument!!.note
        val annotation = note.annotations.getValue(ORIGINAL_ID)
        assertEquals(ORIGINAL_BODY, annotation.body)
        assertEquals(CREATED_AT, annotation.createdAt)
        assertEquals(PASTED_AT, annotation.updatedAt)
        assertTrue(annotation.orphaned)
        assertEquals(BlockTypes.BASE_TABLE, note.blocks.first().type)
        assertTrue(NoteValidator.validate(note).isEmpty())
    }

    /** 其它表格的标题、列名或单元格仍引用旧加注时，不将加注错误标记为孤立。 */
    @Test
    fun keepsAnnotationActiveWhileAnotherTableStillReferencesIt() {
        val state = createAnnotatedState()
        val target = state.selectedDocument!!.note.blocks.single()
        assertNotNull(state.pasteMarkdownAtBlock(target.id, ORIGINAL_TEXT.length, ORIGINAL_TEXT.length, TABLE))
        state.updateSelectedNote { note ->
            note.copy(blocks = note.blocks.map { block ->
                val table = block.content as? BaseTableBlockContent ?: return@map block
                val reference = RichText(listOf(InlineRun("保留锚点", annotationIds = listOf(ORIGINAL_ID))))
                block.copy(content = table.copy(
                    title = reference,
                    columns = table.columns.map { it.copy(name = reference) },
                    rows = table.rows.map { row ->
                        row.copy(cells = row.cells.mapValues { BaseTableTextCell(reference) })
                    },
                ))
            })
        }

        assertNotNull(state.pasteMarkdownAtBlock(target.id, 0, ORIGINAL_TEXT.length, TABLE))

        val note = state.selectedDocument!!.note
        val annotation = note.annotations.getValue(ORIGINAL_ID)
        assertFalse(annotation.orphaned)
        assertEquals(ORIGINAL_BODY, annotation.body)
        assertEquals(CREATED_AT, annotation.updatedAt)
        assertTrue(NoteValidator.validate(note).isEmpty())
    }

    /** 同名剪贴板加注获得新身份，所有文本和表格锚点同时改写，已有加注保持原样。 */
    @Test
    fun remapsConflictingAnnotationAcrossTextAndTableReferences() {
        val state = createAnnotatedState()
        val originalNote = state.selectedDocument!!.note
        val target = originalNote.blocks.single()
        val markdown = """
            ## {{a|新标题}}

            {{a|新正文}}

            - [ ] {{a|新待办}}

            | {{a|新表头}} |
            | --- |
            | {{a|新单元格}} |

            [^a]: 新加注正文
        """.trimIndent()

        assertNotNull(state.pasteMarkdownAtBlock(target.id, ORIGINAL_TEXT.length, ORIGINAL_TEXT.length, markdown))

        val note = state.selectedDocument!!.note
        assertEquals(2, note.annotations.size)
        assertEquals(originalNote.annotations.getValue(ORIGINAL_ID), note.annotations.getValue(ORIGINAL_ID))
        assertEquals(listOf(ORIGINAL_ID), note.blocks.first().editableRichText()!!.runs.single().annotationIds)
        val importedId = note.annotations.keys.single { it != ORIGINAL_ID }
        assertNotEquals(ORIGINAL_ID, importedId)
        assertEquals("新加注正文", note.annotations.getValue(importedId).body)
        assertFalse(note.annotations.getValue(importedId).orphaned)

        val heading = note.blocks.map { it.content }.filterIsInstance<HeadingBlockContent>().single()
        val paragraph = note.blocks.map { it.content }.filterIsInstance<RichTextBlockContent>()
            .single { it.richText.plainText == "新正文" }
        val todo = note.blocks.map { it.content }.filterIsInstance<TodoBlockContent>().single()
        val table = note.blocks.map { it.content }.filterIsInstance<BaseTableBlockContent>().single()
        val cell = table.rows.single().cells.values.single() as BaseTableTextCell
        listOf(heading.richText, paragraph.richText, todo.richText, table.columns.single().name, cell.richText)
            .forEach { richText -> assertEquals(listOf(importedId), richText.runs.single().annotationIds) }
        assertTrue(NoteValidator.validate(note).isEmpty())
    }

    /** 已经孤立的同名旧加注也不能被剪贴板覆盖或意外重新激活。 */
    @Test
    fun preservesOrphanedBodyWhenPastedAnnotationUsesTheSameId() {
        val state = createAnnotatedState()
        state.updateSelectedNote { note ->
            note.copy(
                blocks = note.blocks.map { it.withEditableRichText(RichText()) },
                annotations = note.annotations.mapValues { (_, annotation) -> annotation.copy(orphaned = true) },
            )
        }
        val original = state.selectedDocument!!.note.annotations.getValue(ORIGINAL_ID)
        val target = state.selectedDocument!!.note.blocks.single()

        assertNotNull(state.pasteMarkdownAtBlock(
            target.id, 0, 0,
            "| A |\n| --- |\n| {{a|新锚点}} |\n\n[^a]: 新加注正文",
        ))

        val note = state.selectedDocument!!.note
        assertEquals(original, note.annotations.getValue(ORIGINAL_ID))
        val imported = note.annotations.values.single { it.id != ORIGINAL_ID }
        assertFalse(imported.orphaned)
        assertEquals("新加注正文", imported.body)
        assertTrue(NoteValidator.validate(note).isEmpty())
    }

    /**
     * 装配带脱敏既有加注的生产编辑器状态，生成身份只使用测试计数器。
     *
     * @return 含一个已被正文引用的加注和一个段落的编辑状态。
     */
    private fun createAnnotatedState(): TagloomEditorState {
        var nextId = 0
        return TagloomEditorState(
            idFactory = { prefix -> "$prefix-${nextId++}" },
            timestampProvider = { PASTED_AT },
        ).apply {
            createDocument()
            updateSelectedNote { note ->
                note.copy(
                    blocks = note.blocks.map { block ->
                        block.withEditableRichText(RichText(listOf(
                            InlineRun(ORIGINAL_TEXT, annotationIds = listOf(ORIGINAL_ID)),
                        )))
                    },
                    annotations = mapOf(ORIGINAL_ID to Annotation(
                        id = ORIGINAL_ID,
                        kind = AnnotationKinds.NOTE,
                        body = ORIGINAL_BODY,
                        createdAt = CREATED_AT,
                        updatedAt = CREATED_AT,
                    )),
                )
            }
        }
    }

    private companion object {
        /** 用于构造显式身份冲突的脱敏加注 ID。 */
        val ORIGINAL_ID: AnnotationId = AnnotationId("a")
        /** 被选区替换的脱敏正文。 */
        const val ORIGINAL_TEXT: String = "原文"
        /** 不能因粘贴而丢失的旧加注正文。 */
        const val ORIGINAL_BODY: String = "原加注正文"
        /** 既有加注的脱敏创建时间。 */
        const val CREATED_AT: String = "2026-01-01T00:00:00Z"
        /** 本次粘贴的脱敏操作时间。 */
        const val PASTED_AT: String = "2026-01-02T00:00:00Z"
        /** 不包含加注的标准单列管道表格。 */
        const val TABLE: String = "| A |\n| --- |\n| x |"
    }
}
