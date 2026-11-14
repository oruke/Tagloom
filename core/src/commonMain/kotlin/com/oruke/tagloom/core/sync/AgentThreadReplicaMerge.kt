package com.oruke.tagloom.core.sync

import com.oruke.tagloom.core.ai.AgentCompactionItem
import com.oruke.tagloom.core.ai.AgentContextRef
import com.oruke.tagloom.core.ai.AgentMutationProposal
import com.oruke.tagloom.core.ai.AgentProposalItem
import com.oruke.tagloom.core.ai.AgentProposalStatus
import com.oruke.tagloom.core.ai.AgentThread
import com.oruke.tagloom.core.ai.AgentThreadMessage
import com.oruke.tagloom.core.ai.AgentThreadValidator
import com.oruke.tagloom.core.protocol.CanonicalJson
import kotlin.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 执行 `AgentThread` 加密快照载荷的细粒度三方合并。
 *
 * 合并规则（严格满足交换律方向无关性）：
 * 1. 消息与压缩摘要节点按稳定 `id` 及 `(createdAt, id)` 因果时间顺序执行保序并集；
 * 2. 活跃压缩游标 `activeCompactionId` 在合法候选节点中取消息位置更晚（因果更晚）的一项；
 * 3. 会话累计计费账单 `cumulativeUsage` 由合并后的全部回合与压缩节点 `usage` 确定性重算汇总，绝不被单侧覆盖；
 * 4. `title`、`archived`、`selectedModelId`、`thinkingEffort`、`retrievalScope` 按独立标量字段执行三方合并；
 * 5. `lastMessageAt` 自动对齐单侧更新或双端并发消息中的更晚真实消息时间戳。
 */
internal object AgentThreadReplicaMerge {
    /** 当前已知的 `AgentThread` 顶层协议字段集合。 */
    private val knownFields = setOf(
        "schemaVersion",
        "id",
        "title",
        "createdAt",
        "lastMessageAt",
        "archived",
        "selectedModelId",
        "thinkingEffort",
        "retrievalScope",
        "activeContextRefs",
        "messages",
        "proposals",
        "compactions",
        "activeCompactionId",
        "cumulativeUsage",
        "latestContextBreakdown",
    )

    /**
     * 基于共同祖先 [basePayload] 合并本地 [localPayload] 与远端 [remotePayload] 的会话载荷。
     *
     * @param basePayload 唯一最近共同祖先 payload。
     * @param localPayload 本地会话 payload。
     * @param remotePayload 远端会话 payload。
     * @param winner 并发平局时的确定性胜者方向。
     * @return 合并后的完整 JSON payload、独立字段冲突证据及是否仍需保留冲突历史。
     */
    fun merge(
        basePayload: JsonElement,
        localPayload: JsonElement,
        remotePayload: JsonElement,
        winner: MergeSide,
    ): NoteReplicaPayloadMergeResult {
        val base = decode(basePayload)
        val local = decode(localPayload)
        val remote = decode(remotePayload)
        val conflicts = mutableListOf<WorkspaceReplicaFieldConflict>()

        fun <T> mergeScalarField(
            fieldName: String,
            baseVal: T,
            localVal: T,
            remoteVal: T,
        ): T {
            val result = ThreeWayValueMerge.merge(baseVal, localVal, remoteVal)
            if (!result.conflicted) return result.value
            val path = listOf(ReplicaFieldPathSegment(field = fieldName))
            conflicts += WorkspaceReplicaFieldConflict(
                path = path,
                baseValue = ReplicaJsonFieldMerge.read(basePayload, path),
                localValue = ReplicaJsonFieldMerge.read(localPayload, path),
                remoteValue = ReplicaJsonFieldMerge.read(remotePayload, path),
                temporaryWinner = winner,
            )
            return if (winner == MergeSide.LOCAL) localVal else remoteVal
        }

        val mergedMessages = mergeMessages(base.messages, local.messages, remote.messages, winner)
        val mergedMessageIds = mergedMessages.mapTo(linkedSetOf(), AgentThreadMessage::id)
        val messageIndexById = mergedMessages.withIndex().associate { (idx, msg) -> msg.id to idx }
        val mergedProposals = mergeProposals(
            base = base.proposals,
            local = local.proposals,
            remote = remote.proposals,
            validMessageIds = mergedMessageIds,
            winner = winner,
        )
        val mergedCompactions = mergeCompactions(
            base = base.compactions,
            local = local.compactions,
            remote = remote.compactions,
            validMessageIds = mergedMessageIds,
            winner = winner,
        )
        val validCompactionById = mergedCompactions.associateBy(AgentCompactionItem::id)
        val mergedActiveCompactionId = resolveActiveCompactionCursor(
            baseId = base.activeCompactionId,
            localId = local.activeCompactionId,
            remoteId = remote.activeCompactionId,
            compactionsById = validCompactionById,
            messageIndexById = messageIndexById,
        )
        val mergedActiveRefs = mergeContextRefs(base.activeContextRefs, local.activeContextRefs, remote.activeContextRefs, winner)
        val mergedLastMessageAt = resolveLastMessageAt(
            baseTime = base.lastMessageAt,
            localTime = local.lastMessageAt,
            remoteTime = remote.lastMessageAt,
            mergedMessages = mergedMessages,
        )
        val mergedBreakdown = when {
            local.latestContextBreakdown == base.latestContextBreakdown -> remote.latestContextBreakdown
            remote.latestContextBreakdown == base.latestContextBreakdown -> local.latestContextBreakdown
            else -> if (winner == MergeSide.LOCAL) local.latestContextBreakdown else remote.latestContextBreakdown
        }

        val draftThread = AgentThread(
            schemaVersion = maxOf(local.schemaVersion, remote.schemaVersion),
            id = local.id,
            title = mergeScalarField("title", base.title, local.title, remote.title),
            createdAt = minTimestamp(local.createdAt, remote.createdAt),
            lastMessageAt = mergedLastMessageAt,
            archived = mergeScalarField("archived", base.archived, local.archived, remote.archived),
            selectedModelId = mergeScalarField("selectedModelId", base.selectedModelId, local.selectedModelId, remote.selectedModelId),
            thinkingEffort = mergeScalarField("thinkingEffort", base.thinkingEffort, local.thinkingEffort, remote.thinkingEffort),
            retrievalScope = mergeScalarField("retrievalScope", base.retrievalScope, local.retrievalScope, remote.retrievalScope),
            activeContextRefs = mergedActiveRefs,
            messages = mergedMessages,
            proposals = mergedProposals,
            compactions = mergedCompactions,
            activeCompactionId = mergedActiveCompactionId,
            latestContextBreakdown = mergedBreakdown,
        )
        val mergedThread = draftThread.copy(cumulativeUsage = draftThread.recomputeCumulativeUsage())
        if (AgentThreadValidator.validate(mergedThread).isNotEmpty()) {
            val fallback = if (winner == MergeSide.LOCAL) localPayload else remotePayload
            return NoteReplicaPayloadMergeResult(fallback, emptyList(), conflicted = true)
        }
        val unknown = ReplicaJsonFieldMerge.merge(
            kind = com.oruke.tagloom.core.workspace.ManifestObjectKind.AGENT_THREAD,
            base = unknownFields(basePayload),
            local = unknownFields(localPayload),
            remote = unknownFields(remotePayload),
            winner = winner,
        )
        conflicts += unknown.fields
        val encoded = CanonicalJson.format.encodeToJsonElement(AgentThread.serializer(), mergedThread).jsonObject
        val combined = JsonObject(unknown.payload.jsonObject + encoded)
        return NoteReplicaPayloadMergeResult(
            payload = combined,
            fields = conflicts.distinctBy { it.fieldId }.sortedBy { it.fieldId },
            conflicted = conflicts.isNotEmpty(),
        )
    }

    /**
     * 按稳定消息 ID 三方合并成员集合，并按因果时间戳与 ID 确定性排序。
     *
     * @param base 基线消息列表。
     * @param local 本地消息列表。
     * @param remote 远端消息列表。
     * @param winner 同 ID 内容不一致时的平局胜者。
     * @return 保序并集后的消息列表。
     */
    private fun mergeMessages(
        base: List<AgentThreadMessage>,
        local: List<AgentThreadMessage>,
        remote: List<AgentThreadMessage>,
        winner: MergeSide,
    ): List<AgentThreadMessage> {
        val baseMap = base.associateBy(AgentThreadMessage::id)
        val localMap = local.associateBy(AgentThreadMessage::id)
        val remoteMap = remote.associateBy(AgentThreadMessage::id)
        val retainedIds = (baseMap.keys + localMap.keys + remoteMap.keys).filter { id ->
            ThreeWayValueMerge.merge(id in baseMap, id in localMap, id in remoteMap).value
        }
        val mergedById = retainedIds.associateWith { id ->
            chooseMember(baseMap[id], localMap[id], remoteMap[id], winner)
        }
        val baseRank = base.withIndex().associate { (idx, msg) -> msg.id to idx }
        return mergedById.values.sortedWith { left, right ->
            val leftBase = baseRank[left.id]
            val rightBase = baseRank[right.id]
            if (leftBase != null && rightBase != null && leftBase != rightBase) {
                leftBase.compareTo(rightBase)
            } else {
                val timeCmp = compareTimestamps(left.createdAt, right.createdAt)
                if (timeCmp != 0) timeCmp else left.id.compareTo(right.id)
            }
        }
    }

    /**
     * 按稳定提案 ID 执行三方合并，对同提案并发操作按 `APPLIED > DISCARDED > PENDING` 状态单调性与逐项采纳标志收敛。
     *
     * @param base 基线提案列表。
     * @param local 本地提案列表。
     * @param remote 远端提案列表。
     * @param validMessageIds 合并后有效的助手消息 ID 集合。
     * @param winner 并发平局胜者。
     * @return 保序且满足协议不变量的提案列表。
     */
    private fun mergeProposals(
        base: List<AgentMutationProposal>,
        local: List<AgentMutationProposal>,
        remote: List<AgentMutationProposal>,
        validMessageIds: Set<String>,
        winner: MergeSide,
    ): List<AgentMutationProposal> {
        val baseMap = base.associateBy(AgentMutationProposal::id)
        val localMap = local.associateBy(AgentMutationProposal::id)
        val remoteMap = remote.associateBy(AgentMutationProposal::id)
        val retainedIds = (baseMap.keys + localMap.keys + remoteMap.keys).filter { id ->
            ThreeWayValueMerge.merge(id in baseMap, id in localMap, id in remoteMap).value
        }
        return retainedIds
            .map { id -> mergeSingleProposal(baseMap[id], localMap[id], remoteMap[id], winner) }
            .filter { proposal -> proposal.messageId in validMessageIds }
            .sortedWith { left, right ->
                val timeCmp = compareTimestamps(left.createdAt, right.createdAt)
                if (timeCmp != 0) timeCmp else left.id.compareTo(right.id)
            }
    }

    /**
     * 合并单个提案的三方状态与逐项 `accepted` 选择。
     *
     * @param base 基线提案。
     * @param local 本地提案。
     * @param remote 远端提案。
     * @param winner 并发平局胜者。
     * @return 收敛后的提案对象。
     */
    private fun mergeSingleProposal(
        base: AgentMutationProposal?,
        local: AgentMutationProposal?,
        remote: AgentMutationProposal?,
        winner: MergeSide,
    ): AgentMutationProposal {
        if (local == null) return requireNotNull(remote)
        if (remote == null) return local
        if (local == remote) return local
        if (local == base) return remote
        if (remote == base) return local
        if (local.status == AgentProposalStatus.APPLIED || remote.status == AgentProposalStatus.APPLIED) {
            return when {
                local.status == AgentProposalStatus.APPLIED && remote.status != AgentProposalStatus.APPLIED -> local
                remote.status == AgentProposalStatus.APPLIED && local.status != AgentProposalStatus.APPLIED -> remote
                else -> if (winner == MergeSide.LOCAL) local else remote
            }
        }
        if (local.status == AgentProposalStatus.DISCARDED || remote.status == AgentProposalStatus.DISCARDED) {
            return when {
                local.status == AgentProposalStatus.DISCARDED && remote.status != AgentProposalStatus.DISCARDED -> local
                remote.status == AgentProposalStatus.DISCARDED && local.status != AgentProposalStatus.DISCARDED -> remote
                else -> if (winner == MergeSide.LOCAL) local else remote
            }
        }
        val preferred = if (winner == MergeSide.LOCAL) local else remote
        val baseItems = base?.items.orEmpty().associateBy(AgentProposalItem::id)
        val localItems = local.items.associateBy(AgentProposalItem::id)
        val remoteItems = remote.items.associateBy(AgentProposalItem::id)
        val mergedItems = preferred.items.map { item ->
            val lItem = localItems[item.id]
            val rItem = remoteItems[item.id]
            if (lItem != null && rItem != null) {
                val bAccepted = baseItems[item.id]?.accepted ?: true
                val mergedAcc = ThreeWayValueMerge.merge(bAccepted, lItem.accepted, rItem.accepted)
                val finalAccepted = if (!mergedAcc.conflicted) mergedAcc.value else item.accepted
                item.copy(accepted = finalAccepted)
            } else {
                item
            }
        }
        return preferred.copy(
            items = mergedItems,
            updatedAt = maxTimestamp(local.updatedAt, remote.updatedAt),
        )
    }

    /**
     * 按稳定压缩节点 ID 三方合并摘要节点并过滤悬空游标。
     *
     * @param base 基线压缩节点。
     * @param local 本地压缩节点。
     * @param remote 远端压缩节点。
     * @param validMessageIds 合并后有效的消息 ID 集合。
     * @param winner 平局胜者。
     * @return 合法有序的压缩节点列表。
     */
    private fun mergeCompactions(
        base: List<AgentCompactionItem>,
        local: List<AgentCompactionItem>,
        remote: List<AgentCompactionItem>,
        validMessageIds: Set<String>,
        winner: MergeSide,
    ): List<AgentCompactionItem> {
        val baseMap = base.associateBy(AgentCompactionItem::id)
        val localMap = local.associateBy(AgentCompactionItem::id)
        val remoteMap = remote.associateBy(AgentCompactionItem::id)
        val retainedIds = (baseMap.keys + localMap.keys + remoteMap.keys).filter { id ->
            ThreeWayValueMerge.merge(id in baseMap, id in localMap, id in remoteMap).value
        }
        return retainedIds
            .map { id -> chooseMember(baseMap[id], localMap[id], remoteMap[id], winner) }
            .filter { item -> item.upToMessageId in validMessageIds }
            .sortedWith { left, right ->
                val timeCmp = compareTimestamps(left.createdAt, right.createdAt)
                if (timeCmp != 0) timeCmp else left.id.compareTo(right.id)
            }
    }

    /**
     * 选择最新活跃压缩边界：单侧修改直接采用，双端并发压缩时取消息位置更晚（因果更晚）的节点。
     *
     * @param baseId 基线压缩游标。
     * @param localId 本地压缩游标。
     * @param remoteId 远端压缩游标。
     * @param compactionsById 合并后有效的压缩节点映射。
     * @param messageIndexById 合并后消息顺序下标映射。
     * @return 最终活跃压缩节点 ID。
     */
    private fun resolveActiveCompactionCursor(
        baseId: String?,
        localId: String?,
        remoteId: String?,
        compactionsById: Map<String, AgentCompactionItem>,
        messageIndexById: Map<String, Int>,
    ): String? {
        val threeWay = ThreeWayValueMerge.merge(baseId, localId, remoteId)
        if (!threeWay.conflicted) {
            return threeWay.value?.takeIf { it in compactionsById }
        }
        val candidates = listOfNotNull(localId, remoteId).distinct().mapNotNull(compactionsById::get)
        return candidates.maxWithOrNull { left, right ->
            val leftPos = messageIndexById[left.upToMessageId] ?: -1
            val rightPos = messageIndexById[right.upToMessageId] ?: -1
            when {
                leftPos != rightPos -> leftPos.compareTo(rightPos)
                else -> {
                    val timeCmp = compareTimestamps(left.createdAt, right.createdAt)
                    if (timeCmp != 0) timeCmp else left.id.compareTo(right.id)
                }
            }
        }?.id
    }

    /**
     * 按稳定 `id` 三方合并活跃 `@` 引用列表。
     *
     * @param base 基线引用列表。
     * @param local 本地引用列表。
     * @param remote 远端引用列表。
     * @param winner 平局胜者。
     * @return 合并后的引用列表。
     */
    private fun mergeContextRefs(
        base: List<AgentContextRef>,
        local: List<AgentContextRef>,
        remote: List<AgentContextRef>,
        winner: MergeSide,
    ): List<AgentContextRef> {
        val baseMap = base.associateBy(AgentContextRef::id)
        val localMap = local.associateBy(AgentContextRef::id)
        val remoteMap = remote.associateBy(AgentContextRef::id)
        val retainedIds = (baseMap.keys + localMap.keys + remoteMap.keys).filter { id ->
            ThreeWayValueMerge.merge(id in baseMap, id in localMap, id in remoteMap).value
        }
        return retainedIds
            .sorted()
            .map { id -> chooseMember(baseMap[id], localMap[id], remoteMap[id], winner) }
    }

    /**
     * 合并 `lastMessageAt`：无冲突时采用单侧更新，双端并发更新时取更晚时间戳，不产生伪冲突。
     *
     * @param baseTime 基线消息活跃时间。
     * @param localTime 本地消息活跃时间。
     * @param remoteTime 远端消息活跃时间。
     * @param mergedMessages 合并后的消息集合。
     * @return 最终消息活跃时间。
     */
    private fun resolveLastMessageAt(
        baseTime: String,
        localTime: String,
        remoteTime: String,
        mergedMessages: List<AgentThreadMessage>,
    ): String {
        val threeWay = ThreeWayValueMerge.merge(baseTime, localTime, remoteTime)
        val candidate = if (!threeWay.conflicted) {
            threeWay.value
        } else {
            maxTimestamp(localTime, remoteTime)
        }
        val latestMessageTime = mergedMessages.map(AgentThreadMessage::createdAt)
            .reduceOrNull(::maxTimestamp)
        return if (latestMessageTime != null) maxTimestamp(candidate, latestMessageTime) else candidate
    }

    /**
     * 在同一 ID 的三方集合成员之间选择合并值。
     *
     * @param base 基线成员。
     * @param local 本地成员。
     * @param remote 远端成员。
     * @param winner 并发平局胜者。
     * @return 合并选中的成员实例。
     */
    private fun <T : Any> chooseMember(base: T?, local: T?, remote: T?, winner: MergeSide): T = when {
        local == null -> requireNotNull(remote)
        remote == null -> local
        local == remote -> local
        local == base -> remote
        remote == base -> local
        else -> if (winner == MergeSide.LOCAL) local else remote
    }

    /**
     * 比较两个 ISO-8601 时间戳（解析失败时回退字典序，保持确定性）。
     *
     * @param left 左侧时间文本。
     * @param right 右侧时间文本。
     * @return 标准比较整数。
     */
    private fun compareTimestamps(left: String, right: String): Int = try {
        Instant.parse(left).compareTo(Instant.parse(right))
    } catch (_: IllegalArgumentException) {
        left.compareTo(right)
    }

    /**
     * 返回两个时间戳中较晚的一项。
     *
     * @param left 左侧时间文本。
     * @param right 右侧时间文本。
     * @return 较晚的时间戳字符串。
     */
    private fun maxTimestamp(left: String, right: String): String =
        if (compareTimestamps(left, right) >= 0) left else right

    /**
     * 返回两个时间戳中较早的一项。
     *
     * @param left 左侧时间文本。
     * @param right 右侧时间文本。
     * @return 较早的时间戳字符串。
     */
    private fun minTimestamp(left: String, right: String): String =
        if (compareTimestamps(left, right) <= 0) left else right

    /**
     * 解码已认证的 `AgentThread` JSON。
     *
     * @param payload 待解码 JSON 元素。
     * @return 领域 [AgentThread] 对象。
     */
    private fun decode(payload: JsonElement): AgentThread =
        CanonicalJson.format.decodeFromJsonElement(AgentThread.serializer(), payload)

    /**
     * 提取未知顶层前向兼容字段。
     *
     * @param payload 原始 JSON 元素。
     * @return 仅包含未知顶层键的 JSON 对象。
     */
    private fun unknownFields(payload: JsonElement): JsonObject =
        JsonObject(payload.jsonObject.filterKeys { it !in knownFields })
}
