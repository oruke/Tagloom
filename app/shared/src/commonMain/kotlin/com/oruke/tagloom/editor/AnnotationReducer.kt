package com.oruke.tagloom.editor

import com.oruke.tagloom.core.model.AnnotationId
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.RichText
import com.oruke.tagloom.core.model.RichTextNormalizer

/**
 * 以纯函数维护 Annotation 正文、锚点移除和显式孤立清理。
 */
internal object AnnotationReducer {
    /**
     * 更新独立加注正文，不改变任何锚点。
     *
     * @param note 当前 Note。
     * @param annotationId 目标加注。
     * @param body 新正文。
     * @param updatedAt 更新时间。
     * @return 更新后的 Note。
     */
    fun updateBody(
        note: Note,
        annotationId: AnnotationId,
        body: String,
        updatedAt: String,
    ): Note {
        require(body.isNotBlank()) { "加注正文不能为空" }
        val current = note.annotations[annotationId] ?: throw IllegalArgumentException("加注不存在")
        return note.copy(
            annotations = note.annotations + (
                annotationId to current.copy(body = body.trim(), updatedAt = updatedAt)
                ),
        )
    }

    /**
     * 从全部 RichText Run 移除指定锚点，并保留正文为 orphaned 等待确认清理。
     *
     * @param note 当前 Note。
     * @param annotationId 目标加注。
     * @param updatedAt 更新时间。
     * @return 锚点已移除但正文仍保留的 Note。
     */
    fun detachAnchors(
        note: Note,
        annotationId: AnnotationId,
        updatedAt: String,
    ): Note {
        val current = note.annotations[annotationId] ?: throw IllegalArgumentException("加注不存在")
        val blocks = note.blocks.map { block ->
            val richText = block.editableRichText() ?: return@map block
            val runs = richText.runs.map { run ->
                run.copy(annotationIds = run.annotationIds.filterNot { id -> id == annotationId })
            }
            block.withEditableRichText(RichTextNormalizer.normalize(RichText(runs)))
        }
        return note.copy(
            blocks = blocks,
            annotations = note.annotations + (
                annotationId to current.copy(orphaned = true, updatedAt = updatedAt)
                ),
        )
    }

    /**
     * 在用户二次确认后永久移除无锚点的孤立加注正文。
     *
     * @param note 当前 Note。
     * @param annotationId 目标加注。
     * @param confirmed 用户是否明确确认。
     * @return 清理后的 Note。
     */
    fun deleteOrphaned(
        note: Note,
        annotationId: AnnotationId,
        confirmed: Boolean,
    ): Note {
        require(confirmed) { "永久清理加注正文需要明确确认" }
        val current = note.annotations[annotationId] ?: throw IllegalArgumentException("加注不存在")
        require(current.orphaned) { "仍有锚点的加注不能永久清理" }
        val stillReferenced = note.blocks.any { block ->
            block.editableRichText()?.runs?.any { run -> annotationId in run.annotationIds } == true
        }
        require(!stillReferenced) { "加注仍被正文引用" }
        return note.copy(annotations = note.annotations - annotationId)
    }
}
