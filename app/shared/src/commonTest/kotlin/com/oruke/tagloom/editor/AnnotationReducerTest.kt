package com.oruke.tagloom.editor

import com.oruke.tagloom.core.model.Annotation
import com.oruke.tagloom.core.model.AnnotationId
import com.oruke.tagloom.core.model.AnnotationKinds
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.InlineRun
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.NoteValidator
import com.oruke.tagloom.core.model.RichText
import com.oruke.tagloom.core.model.RichTextBlockContent
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 验证移除 Annotation 锚点不会静默销毁正文。
 */
class AnnotationReducerTest {
    /**
     * 验证锚点移除后正文进入 orphaned，且只有确认后才能永久清理。
     */
    @Test
    fun retainsBodyUntilConfirmedCleanup() {
        val id = AnnotationId("annotation-one")
        val note = Note(
            title = "加注",
            directTagIds = emptyList(),
            blocks = listOf(
                Block(
                    id = BlockId("block-one"),
                    type = BlockTypes.PARAGRAPH,
                    orderKey = "0000000000",
                    content = RichTextBlockContent(
                        RichText(listOf(InlineRun("词语", annotationIds = listOf(id)))),
                    ),
                ),
            ),
            annotations = mapOf(
                id to Annotation(
                    id = id,
                    kind = AnnotationKinds.NOTE,
                    body = "保留正文",
                    createdAt = "2026-07-13T00:00:00Z",
                    updatedAt = "2026-07-13T00:00:00Z",
                ),
            ),
        )

        val detached = AnnotationReducer.detachAnchors(note, id, "2026-07-13T01:00:00Z")
        assertTrue(detached.annotations.getValue(id).orphaned)
        assertTrue(NoteValidator.validate(detached).isEmpty())
        assertFailsWith<IllegalArgumentException> { AnnotationReducer.deleteOrphaned(detached, id, confirmed = false) }
        assertTrue(AnnotationReducer.deleteOrphaned(detached, id, confirmed = true).annotations.isEmpty())
    }
}
