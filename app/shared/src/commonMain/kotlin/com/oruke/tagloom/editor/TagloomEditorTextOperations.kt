package com.oruke.tagloom.editor

import com.oruke.tagloom.core.model.Annotation
import com.oruke.tagloom.core.model.AnnotationId
import com.oruke.tagloom.core.model.AnnotationKinds
import com.oruke.tagloom.core.model.RichTextEditor

/**
 * 在活动选区切换指定 Mark 类型。
 *
 * @param markType InlineMarkTypes 中的稳定类型。
 */
fun TagloomEditorState.toggleSelectionMark(markType: String) {
    val active = selection.takeIf(EditorSelection::isNotEmpty) ?: return
    updateBlock(requireNotNull(active.blockId)) { block ->
        val richText = block.editableRichText() ?: return@updateBlock block
        block.withEditableRichText(RichTextMarkReducer.toggle(richText, active.start, active.end, markType))
    }
}

/**
 * 判断当前非空选区是否完整应用了指定 Mark。
 *
 * @param markType Inline Mark 稳定类型。
 * @return 选区内每个相交 Run 都包含该 Mark 时为 true。
 */
fun TagloomEditorState.selectionHasMark(markType: String): Boolean {
    val active = selection.takeIf(EditorSelection::isNotEmpty) ?: return false
    val block = selectedDocument?.note?.blocks?.singleOrNull { candidate -> candidate.id == active.blockId }
        ?: return false
    val richText = block.editableRichText() ?: return false
    val runs = RichTextSelectionReducer.intersectingRuns(richText, active.start, active.end)
    return runs.isNotEmpty() && runs.all { run -> run.marks.any { mark -> mark.type == markType } }
}

/**
 * 为当前选区设置一个带参数 Mark，并替换该范围内同类型旧值。
 *
 * @param markType Inline Mark 稳定类型。
 * @param attributes 颜色 token 等稳定参数。
 */
fun TagloomEditorState.setSelectionMark(
    markType: String,
    attributes: Map<String, String>,
) {
    val active = selection.takeIf(EditorSelection::isNotEmpty) ?: return
    updateBlock(requireNotNull(active.blockId)) { block ->
        val richText = block.editableRichText() ?: return@updateBlock block
        block.withEditableRichText(
            RichTextMarkReducer.set(richText, active.start, active.end, markType, attributes),
        )
    }
}

/**
 * 清除当前选区内指定类型的全部 Mark。
 *
 * @param markType 待清除稳定类型。
 */
fun TagloomEditorState.clearSelectionMark(markType: String) {
    val active = selection.takeIf(EditorSelection::isNotEmpty) ?: return
    updateBlock(requireNotNull(active.blockId)) { block ->
        val richText = block.editableRichText() ?: return@updateBlock block
        block.withEditableRichText(
            RichTextEditor.removeMark(richText, active.start, active.end, markType),
        )
    }
}

/**
 * 返回当前选区指定 Mark 属性的一致值。
 *
 * @param markType Inline Mark 稳定类型。
 * @param attributeKey 待读取属性键。
 * @return 所有相交 Run 值相同时返回该值，否则为空。
 */
fun TagloomEditorState.selectionMarkAttribute(
    markType: String,
    attributeKey: String,
): String? {
    val active = selection.takeIf(EditorSelection::isNotEmpty) ?: return null
    val block = selectedDocument?.note?.blocks?.singleOrNull { candidate -> candidate.id == active.blockId }
        ?: return null
    val richText = block.editableRichText() ?: return null
    val values = RichTextSelectionReducer.intersectingRuns(richText, active.start, active.end)
        .map { run -> run.marks.firstOrNull { mark -> mark.type == markType }?.attributes?.get(attributeKey) }
        .distinct()
    return values.singleOrNull()
}

/**
 * 把链接草稿应用到活动选区，并替换该范围内已有 Link Mark。
 */
fun TagloomEditorState.applyLinkToSelection() {
    val active = selection.takeIf(EditorSelection::isNotEmpty) ?: return
    val href = linkDraft.trim()
    if (href.isEmpty()) {
        return
    }
    updateBlock(requireNotNull(active.blockId)) { block ->
        val richText = block.editableRichText() ?: return@updateBlock block
        block.withEditableRichText(RichTextMarkReducer.setLink(richText, active.start, active.end, href))
    }
    linkDraft = ""
}

/**
 * 把选区工具栏草稿创建为独立 Annotation 并锚定到当前选区。
 */
fun TagloomEditorState.addAnnotationToSelection() {
    val active = selection.takeIf(EditorSelection::isNotEmpty) ?: return
    val body = annotationDraft.trim()
    if (body.isEmpty()) {
        return
    }
    val annotationId = AnnotationId(createEditorId("annotation"))
    val timestamp = currentEditorTimestamp()
    updateSelectedNote { note ->
        val blocks = note.blocks.map { block ->
            if (block.id != active.blockId) {
                block
            } else {
                val richText = block.editableRichText() ?: return@map block
                block.withEditableRichText(
                    RichTextEditor.attachAnnotation(richText, active.start, active.end, annotationId),
                )
            }
        }
        note.copy(
            blocks = blocks,
            annotations = note.annotations + (
                annotationId to Annotation(
                    id = annotationId,
                    kind = AnnotationKinds.NOTE,
                    body = body,
                    createdAt = timestamp,
                    updatedAt = timestamp,
                )
                ),
        )
    }
    annotationDraft = ""
}

/**
 * 更新独立加注正文，不改变其行内锚点。
 *
 * @param annotationId 目标加注。
 * @param body 新正文。
 */
fun TagloomEditorState.updateAnnotation(
    annotationId: AnnotationId,
    body: String,
) {
    updateSelectedNote { note ->
        AnnotationReducer.updateBody(note, annotationId, body, currentEditorTimestamp())
    }
}

/**
 * 移除指定加注的全部行内锚点，并把正文保留为待清理孤立状态。
 *
 * @param annotationId 目标加注。
 */
fun TagloomEditorState.detachAnnotation(annotationId: AnnotationId) {
    updateSelectedNote { note ->
        AnnotationReducer.detachAnchors(note, annotationId, currentEditorTimestamp())
    }
}

/**
 * 在用户明确确认后永久删除无锚点的孤立加注正文。
 *
 * @param annotationId 目标加注。
 * @param confirmed 二次确认状态。
 */
fun TagloomEditorState.deleteOrphanedAnnotation(
    annotationId: AnnotationId,
    confirmed: Boolean,
) {
    updateSelectedNote { note -> AnnotationReducer.deleteOrphaned(note, annotationId, confirmed) }
}
