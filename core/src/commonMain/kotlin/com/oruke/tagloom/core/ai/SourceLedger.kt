package com.oruke.tagloom.core.ai

import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.extractPlainText

/**
 * 表示经过 `SourceLedger` 核验切分后的回答文本片段。
 */
sealed interface AgentVerifiedTextSegment {
    /**
     * 普通文本片段（包含模型输出的未登记伪造角标如 `[99]`，均降级为安全纯文本）。
     */
    data class PlainText(
        /** 原始文本内容。 */
        val text: String,
    ) : AgentVerifiedTextSegment

    /**
     * 已在当轮 `SourceLedger` 真实登记的可交互来源角标。
     */
    data class VerifiedCitation(
        /** 角标序号（如 `1` 对应 `[1]`）。 */
        val sourceIndex: Int,
        /** 台账中对应的真实笔记与段落记录。 */
        val entry: AgentSourceEntry,
    ) : AgentVerifiedTextSegment
}

/**
 * 维护单轮 Agent 推理期间真实读取过的笔记与段落来源台账，并提供防幻觉角标核验及实时修订对比。
 */
class SourceLedger {
    /** 按 `(noteId, blockId)` 去重映射至已分配的来源条目。 */
    private val entriesByTarget = linkedMapOf<Pair<ObjectId, BlockId?>, AgentSourceEntry>()

    /**
     * 在台账中登记一处真实注入或工具读取的笔记/段落来源；若同一 `(noteId, blockId)` 已登记则直接复用原编号。
     *
     * @param noteId 来源笔记稳定 ID。
     * @param noteTitle 来源笔记当前标题。
     * @param blockId 可选精确段落 ID。
     * @param revision 读取时笔记的协议 revision。
     * @param rawExcerpt 原段落或笔记纯文本。
     * @return 已分配或已存在的 [AgentSourceEntry]。
     */
    fun register(
        noteId: ObjectId,
        noteTitle: String,
        blockId: BlockId?,
        revision: String,
        rawExcerpt: String,
    ): AgentSourceEntry {
        val key = noteId to blockId
        entriesByTarget[key]?.let { existing -> return existing }
        val normalizedExcerpt = normalizeExcerpt(rawExcerpt)
        val entry = AgentSourceEntry(
            sourceIndex = entriesByTarget.size + 1,
            noteId = noteId,
            noteTitle = noteTitle.ifBlank { "未命名笔记" },
            blockId = blockId,
            revision = revision.ifBlank { "unknown:0" },
            excerpt = normalizedExcerpt,
        )
        entriesByTarget[key] = entry
        return entry
    }

    /**
     * 导出当前轮次按序号升序排列的全部已登记来源列表。
     *
     * @return 不可变来源条目列表。
     */
    fun entries(): List<AgentSourceEntry> = entriesByTarget.values.sortedBy(AgentSourceEntry::sourceIndex)

    companion object {
        /** 匹配正文中的数字方括号引用（如 `[1]`、`[12]`），避免误伤 Markdown 链接 `[文本](url)`。 */
        private val CITATION_PATTERN: Regex = Regex("\\[(\\d{1,3})](?!\\()")

        /** 单条来源摘录最大保留字符数。 */
        private const val MAX_EXCERPT_LENGTH: Int = 220

        /**
         * 将助手回答文本按已登记的 [sources] 切分为普通文本与真实可交互角标。
         *
         * 未在 [sources] 中登记的编号（如模型幻觉捏造的 `[9]`）一律原样保留在 [AgentVerifiedTextSegment.PlainText] 中。
         *
         * @param content 模型回答原文。
         * @param sources 该消息关联的真实来源台账条目。
         * @return 切分合并后的有序片段列表。
         */
        fun parseSegments(
            content: String,
            sources: Collection<AgentSourceEntry>,
        ): List<AgentVerifiedTextSegment> {
            if (content.isEmpty()) return emptyList()
            if (sources.isEmpty()) return listOf(AgentVerifiedTextSegment.PlainText(content))
            val byIndex = sources.associateBy(AgentSourceEntry::sourceIndex)
            val rawSegments = mutableListOf<AgentVerifiedTextSegment>()
            var cursor = 0
            for (match in CITATION_PATTERN.findAll(content)) {
                val range = match.range
                val number = match.groupValues[1].toIntOrNull()
                val verified = number?.let(byIndex::get)
                if (verified != null) {
                    if (range.first > cursor) {
                        rawSegments += AgentVerifiedTextSegment.PlainText(content.substring(cursor, range.first))
                    }
                    rawSegments += AgentVerifiedTextSegment.VerifiedCitation(verified.sourceIndex, verified)
                    cursor = range.last + 1
                }
            }
            if (cursor < content.length) {
                rawSegments += AgentVerifiedTextSegment.PlainText(content.substring(cursor))
            }
            return mergeAdjacentPlainText(rawSegments)
        }

        /**
         * 将台账记录与当前已解锁工作区中的最新权威笔记状态比对，诚实反映段落存续与修订变化。
         *
         * @param entry 历史登记的来源条目。
         * @param currentNote 当前工作区中的活动笔记载荷；已删除或不存在时传 `null`。
         * @param currentRevision 当前工作区笔记协议 revision；不存在时传 `null`。
         * @return [AgentCitationStatus.VERIFIED]、[AgentCitationStatus.UPDATED] 或 [AgentCitationStatus.REMOVED]。
         */
        fun evaluateLiveStatus(
            entry: AgentSourceEntry,
            currentNote: Note?,
            currentRevision: String?,
        ): AgentCitationStatus {
            if (currentNote == null) {
                return AgentCitationStatus.REMOVED
            }
            val targetBlockId = entry.blockId
            if (targetBlockId != null) {
                val block = currentNote.blocks.firstOrNull { candidate ->
                    candidate.id == targetBlockId && !candidate.deleted
                } ?: return AgentCitationStatus.REMOVED
                val currentExcerpt = normalizeExcerpt(block.extractPlainText().orEmpty())
                return if (currentExcerpt == entry.excerpt) {
                    AgentCitationStatus.VERIFIED
                } else {
                    AgentCitationStatus.UPDATED
                }
            }
            return if (currentRevision == null || currentRevision == entry.revision) {
                AgentCitationStatus.VERIFIED
            } else {
                AgentCitationStatus.UPDATED
            }
        }

        /**
         * 压缩连续空白并截断过长摘录。
         *
         * @param raw 原始段落文本。
         * @return 规范化后的单行摘录。
         */
        fun normalizeExcerpt(raw: String): String {
            val squashed = raw.replace(Regex("\\s+"), " ").trim()
            return if (squashed.length <= MAX_EXCERPT_LENGTH) {
                squashed
            } else {
                squashed.take(MAX_EXCERPT_LENGTH) + "…"
            }
        }

        /**
         * 合并相邻纯文本片段，避免未登记方括号导致碎片化文本节点。
         *
         * @param segments 初始切分列表。
         * @return 紧凑片段列表。
         */
        private fun mergeAdjacentPlainText(
            segments: List<AgentVerifiedTextSegment>,
        ): List<AgentVerifiedTextSegment> {
            if (segments.size <= 1) return segments
            val merged = mutableListOf<AgentVerifiedTextSegment>()
            val buffer = StringBuilder()
            segments.forEach { segment ->
                when (segment) {
                    is AgentVerifiedTextSegment.PlainText -> buffer.append(segment.text)
                    is AgentVerifiedTextSegment.VerifiedCitation -> {
                        if (buffer.isNotEmpty()) {
                            merged += AgentVerifiedTextSegment.PlainText(buffer.toString())
                            buffer.clear()
                        }
                        merged += segment
                    }
                }
            }
            if (buffer.isNotEmpty()) {
                merged += AgentVerifiedTextSegment.PlainText(buffer.toString())
            }
            return merged
        }
    }
}
