package com.oruke.tagloom.ui

import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.editor.EditorDocument
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.platform.WorkspaceNote

/**
 * 合入已确认自动字段或引用，同时保留提交期间产生的逐字段草稿；不改变当前文档选择。
 * @param editor 当前解锁编辑器。
 * @param saved 最新确认的权威对象。
 * @param submitted 提交或网络请求启动时的 UI 文档，新建对象为空。
 */
internal fun mergeConfirmedLinkDocument(editor: TagloomEditorState, saved: WorkspaceNote, submitted: EditorDocument?) {
    val index = editor.documents.indexOfFirst { it.id == saved.id }
    val current = editor.documents.getOrNull(index)
    val base = submitted?.note
    // 复用已有链接属于读取，不能覆盖该链接尚未提交的手动草稿或偷偷升级其旧 revision。
    if (base == null && current?.dirty == true) return
    val note = if (current == null || base == null || current.note == base) saved.note else {
        val local = current.note
        val keys = local.propertyValues.keys + saved.note.propertyValues.keys + base.propertyValues.keys
        val properties = keys.mapNotNull { id ->
            (if (local.propertyValues[id] != base.propertyValues[id]) local.propertyValues[id] else saved.note.propertyValues[id])?.let { id to it }
        }.toMap()
        val localBlocks = local.blocks.associateBy { it.id }
        val baseBlocks = base.blocks.associateBy { it.id }
        val remoteBlocks = saved.note.blocks.associateBy { it.id }
        val blocks = (localBlocks.keys + remoteBlocks.keys).mapNotNull { id ->
            if (localBlocks[id] != baseBlocks[id]) localBlocks[id] else remoteBlocks[id]
        }
        val provenance = saved.note.linkMetadata?.let { source ->
            source.copy(
                automaticTitle = source.automaticTitle && local.title == base.title && local.linkMetadata?.automaticTitle != false,
                automaticDescription = source.automaticDescription && LinkNotePolicy.description(local) == LinkNotePolicy.description(
                    base
                ) && local.linkMetadata?.automaticDescription != false,
            )
        }
        local.copy(
            title = if (local.title != base.title) local.title else saved.note.title,
            propertyValues = properties,
            blocks = blocks,
            linkMetadata = provenance
        )
    }
    val document = EditorDocument(
        saved.id, note, saved.revision, saved.createdAt, saved.updatedAt, persisted = true,
        dirty = note != saved.note, draftVersion = current?.draftVersion ?: 0
    )
    if (index < 0) editor.documents.add(document) else editor.documents[index] = document
}
