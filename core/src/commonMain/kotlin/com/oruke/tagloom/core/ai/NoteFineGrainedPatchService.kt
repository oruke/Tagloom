package com.oruke.tagloom.core.ai

import com.oruke.tagloom.core.external.ExternalCallException
import com.oruke.tagloom.core.markdown.MarkdownCodec
import com.oruke.tagloom.core.markdown.MarkdownImportOptions
import com.oruke.tagloom.core.model.Annotation
import com.oruke.tagloom.core.model.AnnotationId
import com.oruke.tagloom.core.model.AttachmentBlockContent
import com.oruke.tagloom.core.model.BaseTableBlockContent
import com.oruke.tagloom.core.model.BaseTableColumn
import com.oruke.tagloom.core.model.BaseTableTextCell
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.CodeBlockContent
import com.oruke.tagloom.core.model.DatabaseBlockContent
import com.oruke.tagloom.core.model.DividerBlockContent
import com.oruke.tagloom.core.model.HeadingBlockContent
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.NoteReferenceBlockContent
import com.oruke.tagloom.core.model.RichText
import com.oruke.tagloom.core.model.RichTextBlockContent
import com.oruke.tagloom.core.model.TabBlockContent
import com.oruke.tagloom.core.model.TabsBlockContent
import com.oruke.tagloom.core.model.TodoBlockContent
import com.oruke.tagloom.core.model.UnknownBlockContent
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * 提供内置 Agent Harness 与外部 CLI / MCP / HTTP API 共用的确定性细粒度块补丁与元数据合成服务。
 *
 * 核心不变量：
 * 1. 未被补丁触碰的段落 100% 保留原稳定 `BlockId`、`parentId`、`BaseTable`、`Database`、`Tabs` 页签树及双向链接锚点；
 * 2. `replace_block` 解析出的首个新块直接继承原 `targetBlockId` 与 `parentId`，保持指向该段落的反向链接不断裂；
 * 3. `remove_block` 生成 `deleted = true` 的块级 tombstone，绝不物理抹除历史证据；
 * 4. 任何块替换或移除后自动对齐 `Note.annotations` 的 `orphaned` 状态，确保 `NoteValidator` 零结构错误；
 * 5. 元数据更新严格遵循 ADR-008 单自定义笔记类型约束，并保护 `Note.aiAccessPolicy` 不被任何工具篡改。
 */
object NoteFineGrainedPatchService {
    /**
     * 解析并校验 `patch_note_blocks` 工具参数中的 `operations` 数组。
     *
     * @param args 工具协议参数对象。
     * @return 按输入顺序排列的强类型块操作列表。
     */
    fun parseBlockOperations(args: JsonObject): List<ParsedBlockPatchOperation> =
        NoteFineGrainedPatchParser.parseBlockOperations(args)

    /**
     * 解析并校验 `update_note_metadata` 工具参数。
     *
     * @param args 工具协议参数对象。
     * @return 强类型元数据更新描述。
     */
    fun parseMetadataPatch(args: JsonObject): ParsedMetadataPatch =
        NoteFineGrainedPatchParser.parseMetadataPatch(args)

    /**
     * 在基准 [note] 上应用局部元数据更新（标题、单自定义类型与结构化属性键值）。
     *
     * @param note 基准权威笔记快照载荷。
     * @param patch 解析后的元数据更新指令。
     * @param updatedAt 属性更新时间（UTC ISO-8601）。
     * @return 更新元数据后的 [Note] 草稿。
     */
    fun applyMetadataPatch(
        note: Note,
        patch: ParsedMetadataPatch,
        updatedAt: String,
    ): Note = NoteFineGrainedPatchParser.applyMetadataPatch(note, patch, updatedAt)

    /**
     * 在基准 [note] 上依次执行局部块操作列表，完整保留未触碰段落的稳定 `BlockId` 与结构化子树。
     *
     * @param note 基准权威笔记快照载荷。
     * @param operations 待执行的局部块操作列表。
     * @param importedAt 用于新导入加注的 UTC ISO-8601 时间戳。
     * @param blockIdFactory 生成新插入段落稳定不透明 ID 的工厂。
     * @param tableIdFactory 生成新导入表格稳定不透明 ID 的工厂。
     * @return 应用局部块补丁并对齐同级排序键与加注孤立状态后的新 [Note]。
     */
    fun applyBlockOperations(
        note: Note,
        operations: List<ParsedBlockPatchOperation>,
        importedAt: String,
        blockIdFactory: () -> BlockId,
        tableIdFactory: () -> String,
    ): Note {
        var blocks = note.blocks.toList()
        val extraAnnotations = linkedMapOf<AnnotationId, Annotation>()

        for (op in operations) {
            when (op.kind) {
                AgentProposalItemKind.REPLACE_BLOCK -> {
                    val targetId = requireNotNull(op.targetBlockId)
                    val target = blocks.singleOrNull { it.id == targetId && !it.deleted }
                        ?: throw ExternalCallException("not_found")
                    requirePatchableLeafOrContentBlock(target, blocks, note.defaultTabId)
                    val imported = importFragment(requireNotNull(op.markdown), importedAt, blockIdFactory, tableIdFactory)
                    extraAnnotations.putAll(imported.annotations)
                    val replacementSiblings = imported.blocks.mapIndexed { idx, parsed ->
                        if (idx == 0) {
                            parsed.copy(id = target.id, parentId = target.parentId)
                        } else {
                            parsed.copy(parentId = target.parentId)
                        }
                    }
                    blocks = replaceActiveSiblingAndReindex(blocks, target, replacementSiblings)
                }

                AgentProposalItemKind.INSERT_AFTER_BLOCK -> {
                    val anchorId = requireNotNull(op.targetBlockId)
                    val anchor = blocks.singleOrNull { it.id == anchorId && !it.deleted }
                        ?: throw ExternalCallException("not_found")
                    if (anchor.type == BlockTypes.TABS) {
                        throw ExternalCallException("block_operation_invalid")
                    }
                    val imported = importFragment(requireNotNull(op.markdown), importedAt, blockIdFactory, tableIdFactory)
                    extraAnnotations.putAll(imported.annotations)
                    blocks = if (anchor.type == BlockTypes.TAB) {
                        val children = imported.blocks.map { it.copy(parentId = anchor.id) }
                        insertBoundarySiblingsAndReindex(blocks, parentId = anchor.id, newBlocks = children, prepend = false)
                    } else {
                        val siblings = imported.blocks.map { it.copy(parentId = anchor.parentId) }
                        insertAfterActiveSiblingAndReindex(blocks, anchor, siblings)
                    }
                }

                AgentProposalItemKind.PREPEND_BLOCKS -> {
                    val imported = importFragment(requireNotNull(op.markdown), importedAt, blockIdFactory, tableIdFactory)
                    extraAnnotations.putAll(imported.annotations)
                    val rootNew = imported.blocks.map { it.copy(parentId = null) }
                    blocks = insertBoundarySiblingsAndReindex(blocks, parentId = null, newBlocks = rootNew, prepend = true)
                }

                AgentProposalItemKind.APPEND_BLOCKS -> {
                    val imported = importFragment(requireNotNull(op.markdown), importedAt, blockIdFactory, tableIdFactory)
                    extraAnnotations.putAll(imported.annotations)
                    val rootNew = imported.blocks.map { it.copy(parentId = null) }
                    blocks = insertBoundarySiblingsAndReindex(blocks, parentId = null, newBlocks = rootNew, prepend = false)
                }

                AgentProposalItemKind.REMOVE_BLOCK -> {
                    val targetId = requireNotNull(op.targetBlockId)
                    val target = blocks.singleOrNull { it.id == targetId && !it.deleted }
                        ?: throw ExternalCallException("not_found")
                    requirePatchableLeafOrContentBlock(target, blocks, note.defaultTabId)
                    val subtreeIds = collectSubtreeIds(target.id, blocks)
                    blocks = blocks.map { candidate ->
                        if (candidate.id in subtreeIds) candidate.copy(deleted = true) else candidate
                    }
                }

                else -> throw ExternalCallException("invalid_arguments")
            }
        }

        val mergedAnnotations = reconcileAnnotations(blocks, note.annotations, extraAnnotations)
        return note.copy(blocks = blocks, annotations = mergedAnnotations)
    }

    /**
     * 将提案中所有勾选为 `accepted = true` 的变更项按顺序合成到基准 [baseNote] 上。
     *
     * @param baseNote 基准笔记载荷（`CREATE_NOTE` 时为空白初始笔记）。
     * @param acceptedItems 用户确认采纳的细粒度变更项列表。
     * @param updatedAt 本次合成使用的 UTC ISO-8601 时间戳。
     * @param blockIdFactory 分配新段落稳定 ID 的工厂。
     * @param tableIdFactory 分配新表格稳定 ID 的工厂。
     * @return 应用所选变更项后的 [Note] 草稿。
     */
    fun applyAcceptedProposalItems(
        baseNote: Note,
        acceptedItems: List<AgentProposalItem>,
        updatedAt: String,
        blockIdFactory: () -> BlockId,
        tableIdFactory: () -> String,
    ): Note {
        var working = baseNote
        val blockOps = mutableListOf<ParsedBlockPatchOperation>()
        for (item in acceptedItems) {
            when (item.kind) {
                AgentProposalItemKind.TITLE -> {
                    val title = item.newTitle?.trim().takeUnless { it.isNullOrEmpty() }
                        ?: throw ExternalCallException("invalid_arguments")
                    working = working.copy(title = title)
                }
                AgentProposalItemKind.CUSTOM_TAG -> {
                    working = applyMetadataPatch(
                        working,
                        ParsedMetadataPatch(
                            hasCustomTagChange = true,
                            customTagId = if (item.clearCustomTag) null else item.newCustomTagId,
                        ),
                        updatedAt,
                    )
                }
                AgentProposalItemKind.PROPERTY -> {
                    val propId = requireNotNull(item.propertyId) { "missing_property_id" }
                    working = applyMetadataPatch(
                        working,
                        ParsedMetadataPatch(
                            propertyUpdates = mapOf(propId to (item.newPropertyValue ?: JsonNull)),
                        ),
                        updatedAt,
                    )
                }
                AgentProposalItemKind.REPLACE_BLOCK,
                AgentProposalItemKind.INSERT_AFTER_BLOCK,
                AgentProposalItemKind.PREPEND_BLOCKS,
                AgentProposalItemKind.APPEND_BLOCKS,
                AgentProposalItemKind.REMOVE_BLOCK,
                -> blockOps += ParsedBlockPatchOperation(
                    kind = item.kind,
                    targetBlockId = item.targetBlockId,
                    markdown = item.markdown,
                )
            }
        }
        if (blockOps.isNotEmpty()) {
            working = applyBlockOperations(working, blockOps, updatedAt, blockIdFactory, tableIdFactory)
        }
        return working.copy(aiAccessPolicy = baseNote.aiAccessPolicy)
    }

    /**
     * 校验局部补丁不允许直接摧毁 `TABS` 容器、`TAB` 页签节点、默认页签或仍挂载活动子块的父节点。
     *
     * @param target 待替换或待移除的目标块。
     * @param allBlocks 笔记全部块列表。
     * @param defaultTabId 笔记默认页签 ID。
     */
    private fun requirePatchableLeafOrContentBlock(
        target: Block,
        allBlocks: List<Block>,
        defaultTabId: BlockId?,
    ) {
        if (target.type == BlockTypes.TABS || target.type == BlockTypes.TAB || target.id == defaultTabId) {
            throw ExternalCallException("block_operation_invalid")
        }
        val hasActiveChildren = allBlocks.any { !it.deleted && it.parentId == target.id }
        if (hasActiveChildren) {
            throw ExternalCallException("block_operation_invalid")
        }
    }

    /**
     * 调用现有 `MarkdownCodec` 将局部 Markdown 解析为非空根级结构化块列表。
     *
     * @param markdown 局部 Markdown 片段。
     * @param importedAt 新导入加注使用的时间戳。
     * @param blockIdFactory 新段落稳定 ID 分配器。
     * @param tableIdFactory 新表格稳定 ID 分配器。
     * @return 解析后的临时 [Note] 载体。
     */
    private fun importFragment(
        markdown: String,
        importedAt: String,
        blockIdFactory: () -> BlockId,
        tableIdFactory: () -> String,
    ): Note {
        if (markdown.length > NoteFineGrainedPatchParser.MAX_PATCH_MARKDOWN_LENGTH) {
            throw ExternalCallException("invalid_arguments")
        }
        val imported = MarkdownCodec.import(
            markdown = markdown,
            options = MarkdownImportOptions(
                defaultTitle = "patch",
                firstHeadingAsTitle = false,
                importedAt = importedAt,
                blockIdFactory = { blockIdFactory() },
                tableIdFactory = tableIdFactory,
            ),
        ).note
        if (imported.blocks.isEmpty()) {
            throw ExternalCallException("invalid_arguments")
        }
        return imported
    }

    /**
     * 在同一 `parentId` 活动同级序列中原位替换目标块，并仅重排该同级序列的十位 `orderKey`。
     *
     * @param allBlocks 当前全部块列表。
     * @param target 待替换目标块。
     * @param replacements 替换后置入同位置的块序列。
     * @return 合并后的新块列表。
     */
    private fun replaceActiveSiblingAndReindex(
        allBlocks: List<Block>,
        target: Block,
        replacements: List<Block>,
    ): List<Block> {
        val activeSiblings = allBlocks
            .filter { !it.deleted && it.parentId == target.parentId }
            .sortedWith(compareBy<Block> { it.orderKey }.thenBy { it.id.value })
        val idx = activeSiblings.indexOfFirst { it.id == target.id }
        if (idx < 0) throw ExternalCallException("not_found")
        val updatedSiblings = buildList {
            addAll(activeSiblings.subList(0, idx))
            addAll(replacements)
            addAll(activeSiblings.subList(idx + 1, activeSiblings.size))
        }.mapIndexed { index, block -> block.copy(orderKey = formatOrderKey(index)) }
        return mergeUpdatedSiblings(allBlocks, target.parentId, setOf(target.id), updatedSiblings)
    }

    /**
     * 在指定活动同级块 `anchor` 之后插入新块并重排该同级序列的 `orderKey`。
     *
     * @param allBlocks 当前全部块列表。
     * @param anchor 插入锚点块。
     * @param inserted 待插入的新块序列。
     * @return 合并后的新块列表。
     */
    private fun insertAfterActiveSiblingAndReindex(
        allBlocks: List<Block>,
        anchor: Block,
        inserted: List<Block>,
    ): List<Block> {
        val activeSiblings = allBlocks
            .filter { !it.deleted && it.parentId == anchor.parentId }
            .sortedWith(compareBy<Block> { it.orderKey }.thenBy { it.id.value })
        val idx = activeSiblings.indexOfFirst { it.id == anchor.id }
        if (idx < 0) throw ExternalCallException("not_found")
        val updatedSiblings = buildList {
            addAll(activeSiblings.subList(0, idx + 1))
            addAll(inserted)
            addAll(activeSiblings.subList(idx + 1, activeSiblings.size))
        }.mapIndexed { index, block -> block.copy(orderKey = formatOrderKey(index)) }
        return mergeUpdatedSiblings(allBlocks, anchor.parentId, emptySet(), updatedSiblings)
    }

    /**
     * 在指定 `parentId` 的同级活动列表头部或尾部插入新块并重排 `orderKey`。
     *
     * @param allBlocks 当前全部块列表。
     * @param parentId 父级容器 ID（根级为空）。
     * @param newBlocks 待插入的新块列表。
     * @param prepend 为真时插入到同级头部，否则追加至尾部。
     * @return 合并后的新块列表。
     */
    private fun insertBoundarySiblingsAndReindex(
        allBlocks: List<Block>,
        parentId: BlockId?,
        newBlocks: List<Block>,
        prepend: Boolean,
    ): List<Block> {
        val activeSiblings = allBlocks
            .filter { !it.deleted && it.parentId == parentId }
            .sortedWith(compareBy<Block> { it.orderKey }.thenBy { it.id.value })
        val combined = if (prepend) newBlocks + activeSiblings else activeSiblings + newBlocks
        val updatedSiblings = combined.mapIndexed { index, block -> block.copy(orderKey = formatOrderKey(index)) }
        return mergeUpdatedSiblings(allBlocks, parentId, emptySet(), updatedSiblings)
    }

    /**
     * 将重排后的某级活动同级列表合回完整块列表，保留其他父层级与已删除 tombstone 块不变。
     *
     * @param allBlocks 原始全部块列表。
     * @param parentId 本次重排的父级容器 ID。
     * @param removedOrReplacedIds 被原位替换掉的旧块 ID 集合。
     * @param updatedActiveSiblings 重排后的新同级活动块列表。
     * @return 合并后的完整块列表。
     */
    private fun mergeUpdatedSiblings(
        allBlocks: List<Block>,
        parentId: BlockId?,
        removedOrReplacedIds: Set<BlockId>,
        updatedActiveSiblings: List<Block>,
    ): List<Block> {
        val retainedOthers = allBlocks.filter { block ->
            block.deleted || (block.parentId != parentId && block.id !in removedOrReplacedIds)
        }
        return retainedOthers + updatedActiveSiblings
    }

    /**
     * 递归收集指定块及其全部后代块的 ID 集合。
     *
     * @param rootId 子树根块 ID。
     * @param allBlocks 笔记全部块列表。
     * @return 包含根块及所有后代的 ID 集合。
     */
    private fun collectSubtreeIds(rootId: BlockId, allBlocks: List<Block>): Set<BlockId> {
        val visited = linkedSetOf(rootId)
        val childrenByParent = allBlocks.groupBy(Block::parentId)
        val queue = ArrayDeque<BlockId>()
        queue.add(rootId)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            childrenByParent[current].orEmpty().forEach { child ->
                if (visited.add(child.id)) {
                    queue.add(child.id)
                }
            }
        }
        return visited
    }

    /**
     * 合并既有与新导入加注，并严格对齐当前块集合中的引用状态以避免孤立状态不一致。
     *
     * @param blocks 更新后的全部块列表。
     * @param existing 原笔记既有加注映射。
     * @param imported 新导入 Markdown 片段产生的加注映射。
     * @return 引用与孤立状态均符合 `NoteValidator` 的加注映射。
     */
    private fun reconcileAnnotations(
        blocks: List<Block>,
        existing: Map<AnnotationId, Annotation>,
        imported: Map<AnnotationId, Annotation>,
    ): Map<AnnotationId, Annotation> {
        val combined = existing + imported
        if (combined.isEmpty()) return emptyMap()
        val referenced = collectAllReferencedAnnotationIds(blocks)
        return combined.mapValues { (id, annotation) ->
            val shouldBeOrphaned = id !in referenced
            if (annotation.orphaned == shouldBeOrphaned) annotation else annotation.copy(orphaned = shouldBeOrphaned)
        }
    }

    /**
     * 提取全部块内容引用的 `AnnotationId` 集合（与 `NoteValidator` 口径严格一致）。
     *
     * @param blocks 笔记全部块列表。
     * @return 被任意块富文本 run 引用的加注 ID 集合。
     */
    private fun collectAllReferencedAnnotationIds(blocks: List<Block>): Set<AnnotationId> =
        blocks.flatMapTo(linkedSetOf()) { block ->
            val richTexts = when (val content = block.content) {
                is RichTextBlockContent -> listOf(content.richText)
                is HeadingBlockContent -> listOf(content.richText)
                is TodoBlockContent -> listOf(content.richText)
                is AttachmentBlockContent -> listOf(content.caption)
                is TabBlockContent -> listOf(content.title)
                is BaseTableBlockContent -> buildList {
                    add(content.title)
                    addAll(content.columns.map(BaseTableColumn::name))
                    addAll(content.rows.flatMap { row -> row.cells.values.mapNotNull { (it as? BaseTableTextCell)?.richText } })
                }
                is CodeBlockContent,
                is DatabaseBlockContent,
                DividerBlockContent,
                is NoteReferenceBlockContent,
                TabsBlockContent,
                is UnknownBlockContent,
                -> emptyList()
            }
            richTexts.flatMap(RichText::runs).flatMap { it.annotationIds }
        }

    /**
     * 将从零开始的同级块下标格式化为十位零填充字典序键。
     *
     * @param index 同级块顺序下标。
     * @return 十位数字排序键（如 `0000000000`）。
     */
    private fun formatOrderKey(index: Int): String = index.toString().padStart(10, '0')
}
