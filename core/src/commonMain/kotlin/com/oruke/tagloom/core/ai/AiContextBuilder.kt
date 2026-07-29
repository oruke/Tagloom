package com.oruke.tagloom.core.ai

import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.ObjectSnapshot
import com.oruke.tagloom.core.model.ObjectType
import com.oruke.tagloom.core.model.extractPlainText

/** 从已确认 Note 快照确定性构建可预览、硬预算限制且不含附件原名的 AI 上下文。 */
object AiContextBuilder {
    /**
     * 优先选择显式 Note，再按更新时间和随机 ID 稳定排序，并按 Note/Block/字符三重预算裁剪。
     *
     * @param snapshots 候选已确认 Note 快照。
     * @param preferredNoteIds 用户当前打开或明确选中的 Note IDs。
     * @param budget 上下文硬预算。
     * @return 可在远程发送前逐 Block 展示给用户的最小上下文。
     */
    fun build(
        snapshots: List<ObjectSnapshot<Note>>,
        preferredNoteIds: Set<ObjectId> = emptySet(),
        budget: AiContextBudget = AiContextBudget(),
    ): AiContextSelection {
        val eligible = snapshots
            .filter { snapshot -> snapshot.header.objectType == ObjectType.NOTE && !snapshot.header.deleted }
            .distinctBy { snapshot -> snapshot.header.objectId }
            .sortedWith(
                compareBy<ObjectSnapshot<Note>> { snapshot -> snapshot.header.objectId !in preferredNoteIds }
                    .thenByDescending { snapshot -> snapshot.header.updatedAt }
                    .thenBy { snapshot -> snapshot.header.objectId.value },
            )
        val selectedSnapshots = eligible.take(budget.maxNotes)
        val blocks = mutableListOf<AiContextBlock>()
        var characters = 0
        var truncated = eligible.size > selectedSnapshots.size

        selectedSnapshots.forEach snapshotLoop@{ snapshot ->
            snapshot.payload.blocks.forEach blockLoop@{ block ->
                val text = block.extractPlainText(includeAttachmentFileName = false) ?: return@blockLoop
                if (blocks.size >= budget.maxBlocks || characters >= budget.maxCharacters) {
                    truncated = true
                    return@snapshotLoop
                }
                val remaining = budget.maxCharacters - characters
                val selectedText = text.take(remaining)
                if (selectedText.length < text.length) truncated = true
                blocks += AiContextBlock(
                    noteId = snapshot.header.objectId,
                    noteTitle = snapshot.payload.title,
                    blockId = block.id,
                    text = selectedText,
                )
                characters += selectedText.length
            }
        }
        val noteCount = blocks.map(AiContextBlock::noteId).distinct().size
        return AiContextSelection(blocks, noteCount, characters, truncated)
    }
}
