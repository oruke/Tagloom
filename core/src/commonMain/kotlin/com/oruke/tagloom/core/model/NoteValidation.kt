package com.oruke.tagloom.core.model

import kotlinx.serialization.Serializable

/**
 * 定义笔记结构校验返回的稳定错误码。
 */
object NoteValidationCodes {
    /** Block 的父节点不存在。 */
    const val MISSING_PARENT: String = "missing_parent"

    /** Block 直接或间接把自己作为祖先。 */
    const val PARENT_CYCLE: String = "parent_cycle"

    /** 同一父节点下存在重复排序键。 */
    const val DUPLICATE_SIBLING_ORDER: String = "duplicate_sibling_order"

    /** InlineRun 引用了笔记中不存在的加注。 */
    const val MISSING_ANNOTATION: String = "missing_annotation"

    /** 加注仍被正文引用，却被错误标记为孤立。 */
    const val REFERENCED_ANNOTATION_ORPHANED: String = "referenced_annotation_orphaned"

    /** 加注已经没有正文锚点，却尚未进入待清理状态。 */
    const val UNREFERENCED_ANNOTATION_ACTIVE: String = "unreferenced_annotation_active"
}

/**
 * 描述笔记树、Block payload 或加注引用中的单个结构问题。
 */
@Serializable
data class NoteValidationIssue(
    /** 稳定错误码，供恢复界面和调用方分支判断。 */
    val code: String,
    /** 关联 Block；问题不属于具体 Block 时为空。 */
    val blockId: BlockId? = null,
    /** 关联加注；问题不属于具体加注时为空。 */
    val annotationId: AnnotationId? = null,
    /** 面向开发者和恢复界面的中文说明。 */
    val message: String,
)

/**
 * 校验笔记 Block 树、payload 匹配关系和行内加注引用完整性。
 *
 * 校验器不修改输入。写入流程必须先规范化，再校验，并在存在任何问题时拒绝发布权威快照。
 */
object NoteValidator {
    /**
     * 对笔记执行完整的本地结构校验。
     *
     * @param note 待验证的笔记 payload。
     * @return 按笔记 Block 和加注顺序稳定排列的问题列表；空列表表示结构有效。
     */
    fun validate(note: Note): List<NoteValidationIssue> {
        val issues = mutableListOf<NoteValidationIssue>()
        val blocksById = note.blocks.associateBy(Block::id)

        // 先校验单个节点，避免后续树遍历把不存在的父节点误判为循环。
        note.blocks.forEach { block ->
            issues += BlockValidator.validate(block).map { issue ->
                NoteValidationIssue(
                    code = issue.code,
                    blockId = issue.blockId,
                    message = issue.message,
                )
            }
            if (block.parentId != null && block.parentId !in blocksById) {
                issues += NoteValidationIssue(
                    code = NoteValidationCodes.MISSING_PARENT,
                    blockId = block.id,
                    message = "Block ${block.id.value} 的父节点 ${block.parentId.value} 不存在",
                )
            }
        }

        issues += findParentCycles(note.blocks, blocksById)
        issues += findDuplicateSiblingOrders(note.blocks)
        issues += validateAnnotations(note)
        return issues
    }

    /**
     * 沿父边检测 Block 树中的直接和间接循环。
     *
     * @param blocks 按快照顺序排列的 Block。
     * @param blocksById 用于解析父节点的 Block 映射。
     * @return 每个参与循环的 Block 对应一个稳定问题。
     */
    private fun findParentCycles(
        blocks: List<Block>,
        blocksById: Map<BlockId, Block>,
    ): List<NoteValidationIssue> {
        val cyclicIds = mutableSetOf<BlockId>()

        blocks.forEach { origin ->
            val path = mutableListOf<BlockId>()
            val pathIndexes = mutableMapOf<BlockId, Int>()
            var current: Block? = origin

            // 父边每个节点最多只有一条；记录本次路径即可精确找出环成员，不把指向环的前缀误报为环。
            while (current != null) {
                val repeatedAt = pathIndexes[current.id]
                if (repeatedAt != null) {
                    cyclicIds += path.subList(repeatedAt, path.size)
                    break
                }
                pathIndexes[current.id] = path.size
                path += current.id
                current = current.parentId?.let(blocksById::get)
            }
        }

        return blocks
            .filter { block -> block.id in cyclicIds }
            .map { block ->
                NoteValidationIssue(
                    code = NoteValidationCodes.PARENT_CYCLE,
                    blockId = block.id,
                    message = "Block ${block.id.value} 位于父子循环中",
                )
            }
    }

    /**
     * 检测同一父节点下会导致非确定性显示顺序的重复 orderKey。
     *
     * @param blocks 待检查的全部 Block。
     * @return 对每个重复排序键中的 Block 生成的问题列表。
     */
    private fun findDuplicateSiblingOrders(blocks: List<Block>): List<NoteValidationIssue> =
        blocks
            .groupBy { block -> block.parentId to block.orderKey }
            .values
            .filter { siblings -> siblings.size > 1 }
            .flatten()
            .map { block ->
                NoteValidationIssue(
                    code = NoteValidationCodes.DUPLICATE_SIBLING_ORDER,
                    blockId = block.id,
                    message = "Block ${block.id.value} 与同级 Block 使用了重复 orderKey ${block.orderKey}",
                )
            }

    /**
     * 校验 InlineRun 的加注引用与 Annotation 的孤立状态一致。
     *
     * @param note 待检查的笔记。
     * @return 缺失引用和孤立状态不一致的问题列表。
     */
    private fun validateAnnotations(note: Note): List<NoteValidationIssue> {
        val references = collectAnnotationReferences(note.blocks)
        val issues = mutableListOf<NoteValidationIssue>()

        references.forEach { reference ->
            if (reference.annotationId !in note.annotations) {
                issues += NoteValidationIssue(
                    code = NoteValidationCodes.MISSING_ANNOTATION,
                    blockId = reference.blockId,
                    annotationId = reference.annotationId,
                    message = "Block ${reference.blockId.value} 引用了不存在的加注 ${reference.annotationId.value}",
                )
            }
        }

        val referencedIds = references.mapTo(mutableSetOf(), AnnotationReference::annotationId)
        note.annotations.values.forEach { annotation ->
            when {
                annotation.id in referencedIds && annotation.orphaned -> {
                    issues += NoteValidationIssue(
                        code = NoteValidationCodes.REFERENCED_ANNOTATION_ORPHANED,
                        annotationId = annotation.id,
                        message = "加注 ${annotation.id.value} 仍有正文锚点，不能标记为孤立",
                    )
                }

                annotation.id !in referencedIds && !annotation.orphaned -> {
                    issues += NoteValidationIssue(
                        code = NoteValidationCodes.UNREFERENCED_ANNOTATION_ACTIVE,
                        annotationId = annotation.id,
                        message = "加注 ${annotation.id.value} 已无正文锚点，应进入待清理状态",
                    )
                }
            }
        }
        return issues
    }

    /**
     * 从所有支持 RichText 的 Block 内容中收集加注引用。
     *
     * @param blocks 笔记中的全部 Block。
     * @return 保留 Block 来源的加注引用列表。
     */
    private fun collectAnnotationReferences(blocks: List<Block>): List<AnnotationReference> =
        blocks.flatMap { block ->
            val richText = when (val content = block.content) {
                is RichTextBlockContent -> content.richText
                is HeadingBlockContent -> content.richText
                is TodoBlockContent -> content.richText
                is AttachmentBlockContent -> content.caption
                is CodeBlockContent,
                DividerBlockContent,
                is NoteReferenceBlockContent,
                is UnknownBlockContent,
                -> null
            }
            richText
                ?.runs
                .orEmpty()
                .flatMap { run -> run.annotationIds }
                .distinct()
                .map { annotationId -> AnnotationReference(block.id, annotationId) }
        }

    /**
     * 保存加注引用及其来源 Block，便于生成可定位的校验问题。
     */
    private data class AnnotationReference(
        /** 引用所在的 Block。 */
        val blockId: BlockId,
        /** 被引用的加注。 */
        val annotationId: AnnotationId,
    )
}
