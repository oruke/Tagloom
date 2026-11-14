package com.oruke.tagloom.editor

import com.oruke.tagloom.core.domain.tag.AiAccessPolicyResolver
import com.oruke.tagloom.core.domain.tag.ResolvedAiAccessPolicy
import com.oruke.tagloom.core.markdown.MarkdownCodec
import com.oruke.tagloom.core.markdown.MarkdownImportOptions
import com.oruke.tagloom.core.model.AiAccessPolicy
import com.oruke.tagloom.core.model.AnnotationId
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.workspace.WorkspaceDefaults

/**
 * 更新当前选中笔记的单篇显式 `AiAccessPolicy` 覆盖值（传 `null` 表示恢复跟随笔记类型继承）。
 *
 * @param policyOverride 新的显式策略覆盖值；为空表示清除单篇覆盖。
 */
fun TagloomEditorState.updateSelectedNoteAiAccessPolicy(policyOverride: AiAccessPolicy?) {
    updateSelectedNote { note -> note.copy(aiAccessPolicy = policyOverride) }
}

/**
 * 解析给定笔记在当前编辑器 `tagDefinitions` 目录下的最终有效 `AiAccessPolicy` 与继承来源归因。
 *
 * @param note 待解析笔记载荷。
 * @return 包含有效策略、是否单篇覆盖与决定性继承类型的 [ResolvedAiAccessPolicy]。
 */
fun TagloomEditorState.resolveNoteAiAccessPolicy(note: Note): ResolvedAiAccessPolicy {
    val tagsById = tagDefinitions.associateBy { it.id }
    return AiAccessPolicyResolver(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID).resolveForNote(note, tagsById)
}

/**
 * 将模型回答 Markdown 解析为结构化 Block 并追加到当前选中笔记末尾（保留既有稳定 BlockId 不变）。
 *
 * @param markdown 助手回答 Markdown 文本。
 * @return 成功追加到当前选中笔记时返回 `true`；当前无选中笔记或内容为空时返回 `false`。
 */
fun TagloomEditorState.appendAssistantMarkdownToSelectedNote(markdown: String): Boolean {
    val trimmed = markdown.trim()
    if (trimmed.isEmpty() || selectedDocument == null) return false
    val importedAt = currentEditorTimestamp()
    val imported = MarkdownCodec.import(
        markdown = trimmed,
        options = MarkdownImportOptions(
            defaultTitle = "agent-append",
            firstHeadingAsTitle = false,
            importedAt = importedAt,
            blockIdFactory = { BlockId(createEditorId("block")) },
            tableIdFactory = { createEditorId("table-item") },
        ),
    )
    var changed = false
    updateSelectedNote { note ->
        val isolated = MarkdownPasteAnnotationReducer.isolateImported(
            imported = imported.note,
            existing = note,
            idFactory = { AnnotationId(createEditorId("annotation")) },
        )
        val baseOrderIndex = note.blocks.size
        val rekeyedBlocks = isolated.blocks.mapIndexed { index, block ->
            if (block.parentId == null) {
                block.copy(orderKey = EditorBlockFactory.orderKey(baseOrderIndex + index))
            } else {
                block
            }
        }
        if (rekeyedBlocks.isEmpty()) return@updateSelectedNote note
        changed = true
        note.copy(
            blocks = note.blocks + rekeyedBlocks,
            annotations = note.annotations + isolated.annotations,
        )
    }
    return changed
}

/**
 * 将模型回答 Markdown 导入并创建为一篇全新的工作区笔记草稿，立即选中供自动保存链路落盘。
 *
 * @param suggestedTitle 新笔记建议标题。
 * @param markdown 助手回答 Markdown 正文。
 * @return 新创建笔记的稳定对象 ID。
 */
fun TagloomEditorState.createNoteFromAssistantMarkdown(
    suggestedTitle: String,
    markdown: String,
): ObjectId {
    val importedAt = currentEditorTimestamp()
    val safeTitle = suggestedTitle.trim().ifBlank { contentNames.untitledNote }.take(120)
    val imported = MarkdownCodec.import(
        markdown = markdown.trim(),
        options = MarkdownImportOptions(
            defaultTitle = safeTitle,
            firstHeadingAsTitle = false,
            importedAt = importedAt,
            blockIdFactory = { BlockId(createEditorId("block")) },
            tableIdFactory = { createEditorId("table-item") },
        ),
    )
    val documentId = ObjectId(createEditorId("note"))
    documents += EditorDocument(
        id = documentId,
        note = imported.note.copy(
            title = safeTitle,
            directTagIds = listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID),
        ),
        createdAt = importedAt,
        updatedAt = importedAt,
        persisted = false,
        dirty = true,
    )
    selectDocument(documentId)
    return documentId
}
