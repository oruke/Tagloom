package com.oruke.tagloom.ui

import com.oruke.tagloom.core.ai.AgentMutationProposal
import com.oruke.tagloom.core.ai.AgentProposalItem
import com.oruke.tagloom.core.ai.AgentProposalItemKind
import com.oruke.tagloom.core.model.AttachmentBlockContent
import com.oruke.tagloom.core.model.BaseTableBlockContent
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.CodeBlockContent
import com.oruke.tagloom.core.model.DatabaseBlockContent
import com.oruke.tagloom.core.model.DividerBlockContent
import com.oruke.tagloom.core.model.HeadingBlockContent
import com.oruke.tagloom.core.model.NoteReferenceBlockContent
import com.oruke.tagloom.core.model.RichTextBlockContent
import com.oruke.tagloom.core.model.TabBlockContent
import com.oruke.tagloom.core.model.TabsBlockContent
import com.oruke.tagloom.core.model.TodoBlockContent
import com.oruke.tagloom.core.model.UnknownBlockContent
import com.oruke.tagloom.editor.EditorDocument

/**
 * 表示双方位共享原位 Diff 画布中的有序渲染条目（确保全屏右侧面板与编辑页左侧正文 100% 复用同一结构）。
 */
internal sealed interface DiffCanvasRow {
    /** 列表稳定键。 */
    val key: String

    /** 单个可逐项「采纳 / 跳过」的元数据或块级补丁卡片。 */
    data class PatchItemRow(val item: AgentProposalItem) : DiffCanvasRow {
        override val key: String = "patch-${item.id}"
    }

    /** 未被补丁触碰、100% 保留稳定 `BlockId` 的上下文段落。 */
    data class UntouchedBlockRow(val block: Block) : DiffCanvasRow {
        override val key: String = "untouched-${block.id.value}"
    }
}

/**
 * 将提案中的元数据变更、前插/替换/后插/追加块补丁与原文档未触碰的活动段落按真实文档顺序拼装成统一序列。
 *
 * @param proposal 当前审阅提案。
 * @param targetDocument 编辑器中的目标文档快照。
 * @return 按阅读顺序排列的 [DiffCanvasRow] 列表。
 */
internal fun buildDiffCanvasRows(
    proposal: AgentMutationProposal,
    targetDocument: EditorDocument?,
): List<DiffCanvasRow> = buildList {
    val metadataItems = proposal.items.filter { it.isMetadataChange && it.kind != AgentProposalItemKind.TITLE }
    metadataItems.forEach { add(DiffCanvasRow.PatchItemRow(it)) }

    val blockItems = proposal.items.filter { it.isBlockChange }
    val consumedIds = hashSetOf<String>()

    blockItems.filter { it.kind == AgentProposalItemKind.PREPEND_BLOCKS }.forEach {
        consumedIds += it.id
        add(DiffCanvasRow.PatchItemRow(it))
    }

    val activeBlocks = targetDocument?.note?.blocks
        ?.filterNot { it.deleted }
        ?.sortedWith(compareBy<Block> { it.parentId?.value.orEmpty() }.thenBy { it.orderKey }.thenBy { it.id.value })
        .orEmpty()

    for (block in activeBlocks) {
        val directReplacements = blockItems.filter {
            it.id !in consumedIds &&
                (it.kind == AgentProposalItemKind.REPLACE_BLOCK || it.kind == AgentProposalItemKind.REMOVE_BLOCK) &&
                it.targetBlockId == block.id
        }
        if (directReplacements.isNotEmpty()) {
            directReplacements.forEach {
                consumedIds += it.id
                add(DiffCanvasRow.PatchItemRow(it))
            }
        } else {
            add(DiffCanvasRow.UntouchedBlockRow(block))
        }

        blockItems.filter {
            it.id !in consumedIds &&
                it.kind == AgentProposalItemKind.INSERT_AFTER_BLOCK &&
                it.targetBlockId == block.id
        }.forEach {
            consumedIds += it.id
            add(DiffCanvasRow.PatchItemRow(it))
        }
    }

    blockItems.filter { it.id !in consumedIds }.forEach {
        consumedIds += it.id
        add(DiffCanvasRow.PatchItemRow(it))
    }
}

/**
 * 提取未改动段落的可读上下文摘要文本。
 *
 * @param block 待展示段落。
 * @return 格式化后的纯文本摘要。
 */
internal fun formatBlockContextText(block: Block): String = when (val content = block.content) {
    is RichTextBlockContent -> content.richText.plainText.ifBlank { " " }
    is HeadingBlockContent -> "#".repeat(content.level) + " " + content.richText.plainText
    is TodoBlockContent -> (if (content.checked) "[x] " else "[ ] ") + content.richText.plainText
    is CodeBlockContent -> content.text
    is AttachmentBlockContent -> "[${block.type}] ${content.caption.plainText}".trim()
    is BaseTableBlockContent -> "[BaseTable] ${content.title.plainText} (${content.rows.size} rows)"
    is DatabaseBlockContent -> "[Database] ${content.databaseId.value}"
    DividerBlockContent -> "---"
    is NoteReferenceBlockContent -> "[[${content.noteId.value}]]"
    TabsBlockContent -> "[Tabs]"
    is TabBlockContent -> "[Tab] ${content.title.plainText}"
    is UnknownBlockContent -> "[${block.type}]"
}
