package com.oruke.tagloom.core.ai

/**
 * 校验 [AgentThread] 加密快照载荷的协议完整性与内部引用一致性。
 */
object AgentThreadValidator {
    /** 安全不透明标识符正则。 */
    private val OPAQUE_ID_REGEX: Regex = Regex("^[A-Za-z0-9_.:-]{1,128}$")

    /**
     * 校验给定 [AgentThread] 是否符合当前客户端可安全持久化与同步的结构不变量。
     *
     * @param thread 待校验会话载荷。
     * @return 违规错误码列表；为空表示校验通过。
     */
    fun validate(thread: AgentThread): List<String> {
        val issues = mutableListOf<String>()
        if (thread.schemaVersion <= 0) {
            issues += "invalid_schema_version"
        }
        if (!OPAQUE_ID_REGEX.matches(thread.id.value)) {
            issues += "invalid_thread_id"
        }
        if (thread.title.isBlank() || thread.title.length > MAX_TITLE_LENGTH) {
            issues += "invalid_thread_title"
        }
        if (thread.createdAt.isBlank() || thread.lastMessageAt.isBlank()) {
            issues += "blank_thread_timestamp"
        }
        val messageIds = linkedSetOf<String>()
        thread.messages.forEach { message ->
            if (!OPAQUE_ID_REGEX.matches(message.id)) {
                issues += "invalid_message_id"
            }
            if (!messageIds.add(message.id)) {
                issues += "duplicate_message_id"
            }
            if (message.role != AiChatRole.USER && message.role != AiChatRole.ASSISTANT) {
                issues += "invalid_message_role"
            }
            if (message.createdAt.isBlank()) {
                issues += "blank_message_timestamp"
            }
            val sourceIndices = mutableSetOf<Int>()
            message.sources.forEach { source ->
                if (source.sourceIndex <= 0 || !sourceIndices.add(source.sourceIndex)) {
                    issues += "invalid_source_index"
                }
                if (source.revision.isBlank()) {
                    issues += "blank_source_revision"
                }
            }
            message.contextRefs.forEach { ref ->
                validateContextRef(ref, issues)
            }
        }
        thread.activeContextRefs.forEach { ref ->
            validateContextRef(ref, issues)
        }
        val compactionIds = linkedSetOf<String>()
        thread.compactions.forEach { item ->
            if (!OPAQUE_ID_REGEX.matches(item.id)) {
                issues += "invalid_compaction_id"
            }
            if (!compactionIds.add(item.id)) {
                issues += "duplicate_compaction_id"
            }
            if (item.upToMessageId !in messageIds) {
                issues += "dangling_compaction_cursor"
            }
            if (item.createdAt.isBlank()) {
                issues += "blank_compaction_timestamp"
            }
            if (item.freedTokens < 0 || item.triggerPercent !in 0..100) {
                issues += "invalid_compaction_metrics"
            }
        }
        if (thread.activeCompactionId != null && thread.activeCompactionId !in compactionIds) {
            issues += "missing_active_compaction"
        }
        if (thread.latestContextBreakdown.systemTokens < 0 ||
            thread.latestContextBreakdown.toolsTokens < 0 ||
            thread.latestContextBreakdown.messageTokens < 0
        ) {
            issues += "negative_context_breakdown"
        }
        return issues.distinct()
    }

    /**
     * 校验单条 `@` 上下文引用的必填目标组合。
     *
     * @param ref 待校验引用。
     * @param issues 问题收集列表。
     */
    private fun validateContextRef(ref: AgentContextRef, issues: MutableList<String>) {
        if (!OPAQUE_ID_REGEX.matches(ref.id) || ref.label.isBlank()) {
            issues += "invalid_context_ref"
            return
        }
        val validTarget = when (ref.kind) {
            AgentContextRefKind.NOTE -> ref.noteId != null
            AgentContextRefKind.BLOCKS -> ref.noteId != null && ref.blockIds.isNotEmpty()
            AgentContextRefKind.TAG_SCOPE -> ref.tagId != null
            AgentContextRefKind.SAVED_QUERY_SCOPE -> ref.queryId != null
        }
        if (!validTarget) {
            issues += "incomplete_context_ref_target"
        }
    }

    /** 会话标题最大字符长度上限。 */
    const val MAX_TITLE_LENGTH: Int = 200
}
