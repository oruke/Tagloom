package com.oruke.tagloom.core.sync

import com.oruke.tagloom.core.model.Annotation
import com.oruke.tagloom.core.model.AnnotationId
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.ManualPropertyBinding
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.NoteNormalizer
import com.oruke.tagloom.core.model.NotePropertyValue
import com.oruke.tagloom.core.model.NoteValidator
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.PropertyId
import com.oruke.tagloom.core.model.PropertyStyle
import com.oruke.tagloom.core.model.TagId
import com.oruke.tagloom.core.protocol.CanonicalJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** 合并 Base/Local/Remote Note，并把无法自动解释的内容保存为冲突证据。 */
object NoteThreeWayMergeService {
    /**
     * 合并同一对象的两个 Change head 候选状态。
     *
     * 因果后继直接采用；并发候选按字段三方合并。不同字段与不同 Block 自动组合，同一 RichText 的非重叠编辑自动合并，其他重叠修改选择确定性临时胜者并生成 [ConflictRecord]。
     *
     * @param objectId 被合并 Note 的随机对象标识。
     * @param base 两侧共同祖先 Note。
     * @param local 当前设备候选状态与 head。
     * @param remote 远端设备候选状态与 head。
     * @param knownChanges 用于判断因果关系的 Change 集合。
     * @param mergedAt 冲突记录创建时间，使用 UTC ISO-8601 文本。
     * @return 可立即成为临时权威状态的 Note 与待解决冲突。
     */
    fun merge(
        objectId: ObjectId,
        base: Note,
        local: NoteMergeVersion,
        remote: NoteMergeVersion,
        knownChanges: Collection<ObjectChange>,
        mergedAt: String,
    ): NoteMergeResult {
        require(local.change.objectId == objectId && remote.change.objectId == objectId) {
            "Note 合并候选必须属于目标对象"
        }
        require(mergedAt.isNotBlank()) { "Note 合并时间不能为空" }
        val causalSuccessor = SyncWinnerResolver.causalSuccessor(local.change, remote.change, knownChanges)
        if (causalSuccessor != null) {
            return NoteMergeResult(
                note = NoteNormalizer.normalize(if (causalSuccessor == MergeSide.LOCAL) local.note else remote.note),
                conflicts = emptyList(),
            )
        }

        val context = MergeContext(
            objectId = objectId,
            localChange = local.change,
            remoteChange = remote.change,
            mergedAt = mergedAt,
            defaultWinner = SyncWinnerResolver.concurrentWinner(local.change, remote.change),
            conflicts = mutableListOf(),
        )
        val merged = Note(
            title = mergeRequired(
                base = base.title,
                local = local.note.title,
                remote = remote.note.title,
                fieldKind = ConflictFieldKind.TITLE,
                fieldPath = "title",
                context = context,
                encode = ::encodeString,
            ),
            directTagIds = mergeTagMembership(base.directTagIds, local.note.directTagIds, remote.note.directTagIds),
            // 来源记录与自动值绑定，发生并发时沿用确定性胜者；后续回填仍校验实际字段值。
            linkMetadata = if (context.defaultWinner == MergeSide.LOCAL) local.note.linkMetadata else remote.note.linkMetadata,
            aiAccessPolicy = ThreeWayValueMerge.merge(base.aiAccessPolicy, local.note.aiAccessPolicy, remote.note.aiAccessPolicy)
                .let { result -> if (!result.conflicted) result.value else if (context.defaultWinner == MergeSide.LOCAL) local.note.aiAccessPolicy else remote.note.aiAccessPolicy },
            propertyValues = mergeMap(
                base = base.propertyValues,
                local = local.note.propertyValues,
                remote = remote.note.propertyValues,
                keyName = PropertyId::value,
                fieldKind = ConflictFieldKind.PROPERTY,
                fieldPrefix = "property",
                context = context,
                serializer = NotePropertyValue.serializer(),
            ),
            manualPropertyBindings = mergeMap(
                base = base.manualPropertyBindings.associateBy(ManualPropertyBinding::propertyId),
                local = local.note.manualPropertyBindings.associateBy(ManualPropertyBinding::propertyId),
                remote = remote.note.manualPropertyBindings.associateBy(ManualPropertyBinding::propertyId),
                keyName = PropertyId::value,
                fieldKind = ConflictFieldKind.MANUAL_PROPERTY_BINDING,
                fieldPrefix = "manual-property",
                context = context,
                serializer = ManualPropertyBinding.serializer(),
            ).values.sortedBy { binding -> binding.propertyId.value },
            propertyStyleOverrides = mergeMap(
                base = base.propertyStyleOverrides,
                local = local.note.propertyStyleOverrides,
                remote = remote.note.propertyStyleOverrides,
                keyName = PropertyId::value,
                fieldKind = ConflictFieldKind.PROPERTY_STYLE,
                fieldPrefix = "property-style",
                context = context,
                serializer = PropertyStyle.serializer(),
            ),
            blocks = mergeBlocks(base.blocks, local.note.blocks, remote.note.blocks, context),
            annotations = mergeMap(
                base = base.annotations,
                local = local.note.annotations,
                remote = remote.note.annotations,
                keyName = AnnotationId::value,
                fieldKind = ConflictFieldKind.ANNOTATION,
                fieldPrefix = "annotation",
                context = context,
                serializer = Annotation.serializer(),
            ),
        )
        val normalized = NoteNormalizer.normalize(merged)
        if (NoteValidator.validate(normalized).isEmpty()) {
            return NoteMergeResult(normalized, context.conflicts)
        }

        // 跨字段不变量失败时不能发布结构损坏的 Note；保留完整三方证据并退回确定性临时胜者。
        val safeWinner = if (context.defaultWinner == MergeSide.LOCAL) local.note else remote.note
        context.conflicts += conflictRecord(
            fieldKind = ConflictFieldKind.NOTE_INVARIANT,
            fieldPath = "note-invariant",
            blockId = null,
            baseValue = encode(Note.serializer(), base),
            localValue = encode(Note.serializer(), local.note),
            remoteValue = encode(Note.serializer(), remote.note),
            winner = context.defaultWinner,
            context = context,
        )
        return NoteMergeResult(NoteNormalizer.normalize(safeWinner), context.conflicts)
    }

    /**
     * 合并直接标签集合中的成员布尔值。
     *
     * @param base 共同祖先直接标签。
     * @param local 当前设备直接标签。
     * @param remote 远端设备直接标签。
     * @return 按 Tag ID 稳定排序的三方集合。
     */
    private fun mergeTagMembership(
        base: List<TagId>,
        local: List<TagId>,
        remote: List<TagId>,
    ): List<TagId> {
        val baseSet = base.toSet()
        val localSet = local.toSet()
        val remoteSet = remote.toSet()
        return (baseSet + localSet + remoteSet).filter { tagId ->
            ThreeWayValueMerge.merge(tagId in baseSet, tagId in localSet, tagId in remoteSet).value
        }.sortedBy(TagId::value)
    }

    /**
     * 合并以稳定 ID 为键的可空值 Map，并为重叠修改创建冲突记录。
     *
     * @param K Map 键类型。
     * @param V Map 值类型。
     * @param base 共同祖先 Map。
     * @param local 当前设备 Map。
     * @param remote 远端设备 Map。
     * @param keyName 稳定且不含敏感显示文本的键名函数。
     * @param fieldKind 冲突字段类型。
     * @param fieldPrefix 冲突路径前缀。
     * @param context 当前合并上下文。
     * @param serializer Map 值 serializer。
     * @return 合并后的稳定插入顺序 Map。
     */
    private fun <K, V> mergeMap(
        base: Map<K, V>,
        local: Map<K, V>,
        remote: Map<K, V>,
        keyName: (K) -> String,
        fieldKind: ConflictFieldKind,
        fieldPrefix: String,
        context: MergeContext,
        serializer: KSerializer<V>,
    ): Map<K, V> = buildMap {
        (base.keys + local.keys + remote.keys).distinct().sortedBy(keyName).forEach { key ->
            val merged = mergeNullable(
                base = base[key],
                local = local[key],
                remote = remote[key],
                fieldKind = fieldKind,
                fieldPath = "$fieldPrefix:${keyName(key)}",
                blockId = null,
                context = context,
                encode = { value -> value?.let { encode(serializer, it) } ?: JsonNull },
            )
            if (merged != null) {
                put(key, merged)
            }
        }
    }

    /**
     * 按 Block ID 合并并根据 parent/orderKey/ID 稳定排列。
     *
     * @param base 共同祖先 Blocks。
     * @param local 当前设备 Blocks。
     * @param remote 远端设备 Blocks。
     * @param context 当前合并上下文。
     * @return 合并后的 Blocks。
     */
    private fun mergeBlocks(
        base: List<Block>,
        local: List<Block>,
        remote: List<Block>,
        context: MergeContext,
    ): List<Block> {
        val baseById = base.associateBy(Block::id)
        val localById = local.associateBy(Block::id)
        val remoteById = remote.associateBy(Block::id)
        return (baseById.keys + localById.keys + remoteById.keys)
            .distinct()
            .sortedBy(BlockId::value)
            .mapNotNull { blockId ->
                mergeBlock(baseById[blockId], localById[blockId], remoteById[blockId], blockId, context)
            }
            .sortedWith(compareBy({ it.parentId?.value.orEmpty() }, Block::orderKey, { it.id.value }))
    }

    /**
     * 合并单个 Block，优先自动组合不相交字段和非重叠 RichText 编辑。
     *
     * @param base 共同祖先 Block。
     * @param local 当前设备 Block。
     * @param remote 远端设备 Block。
     * @param blockId 稳定 Block ID。
     * @param context 当前合并上下文。
     * @return 合并 Block；双方一致删除物理条目时为空。
     */
    private fun mergeBlock(
        base: Block?,
        local: Block?,
        remote: Block?,
        blockId: BlockId,
        context: MergeContext,
    ): Block? {
        if (local == remote) return local
        if (local == base) return remote
        if (remote == base) return local
        if (base == null || local == null || remote == null) {
            return mergeNullable(
                base = base,
                local = local,
                remote = remote,
                fieldKind = ConflictFieldKind.BLOCK,
                fieldPath = "block:${blockId.value}",
                blockId = blockId,
                context = context,
                encode = { value -> value?.let { encode(Block.serializer(), it) } ?: JsonNull },
            )
        }

        val localChanged = local != base
        val remoteChanged = remote != base
        val deleteUpdateCompetition =
            localChanged && remoteChanged &&
                ((local.deleted && !remote.deleted) || (remote.deleted && !local.deleted))
        if (deleteUpdateCompetition) {
            return blockConflict(base, local, remote, blockId, context)
        }

        val type = ThreeWayValueMerge.merge(base.type, local.type, remote.type)
        val parentId = ThreeWayValueMerge.merge(base.parentId, local.parentId, remote.parentId)
        val orderKey = ThreeWayValueMerge.merge(base.orderKey, local.orderKey, remote.orderKey)
        val attributes = ThreeWayValueMerge.merge(base.attributes, local.attributes, remote.attributes)
        val deleted = ThreeWayValueMerge.merge(base.deleted, local.deleted, remote.deleted)
        val content = BlockContentThreeWayMerge.merge(base.content, local.content, remote.content)
        if (
            type.conflicted || parentId.conflicted || orderKey.conflicted ||
            attributes.conflicted || deleted.conflicted || content.conflicted
        ) {
            return blockConflict(base, local, remote, blockId, context)
        }
        return Block(
            id = blockId,
            type = type.value,
            parentId = parentId.value,
            orderKey = orderKey.value,
            content = content.value,
            attributes = attributes.value,
            deleted = deleted.value,
        )
    }

    /**
     * 为无法自动合并的整个 Block 创建证据并选择临时胜者。
     *
     * @param base 共同祖先 Block。
     * @param local 当前设备 Block。
     * @param remote 远端设备 Block。
     * @param blockId 稳定 Block ID。
     * @param context 当前合并上下文。
     * @return 确定性临时胜者 Block。
     */
    private fun blockConflict(
        base: Block,
        local: Block,
        remote: Block,
        blockId: BlockId,
        context: MergeContext,
    ): Block {
        val winner = SyncWinnerResolver.concurrentWinner(
            localChange = context.localChange,
            remoteChange = context.remoteChange,
            localDeletes = local.deleted,
            remoteDeletes = remote.deleted,
        )
        context.conflicts += conflictRecord(
            fieldKind = ConflictFieldKind.BLOCK,
            fieldPath = "block:${blockId.value}",
            blockId = blockId,
            baseValue = encode(Block.serializer(), base),
            localValue = encode(Block.serializer(), local),
            remoteValue = encode(Block.serializer(), remote),
            winner = winner,
            context = context,
        )
        return if (winner == MergeSide.LOCAL) local else remote
    }

    /**
     * 合并必有值字段并在冲突时记录 Base/Local/Remote。
     *
     * @param T 字段类型。
     * @param base 共同祖先值。
     * @param local 当前设备值。
     * @param remote 远端设备值。
     * @param fieldKind 冲突字段类型。
     * @param fieldPath 稳定字段路径。
     * @param context 当前合并上下文。
     * @param encode 字段 JSON 编码器。
     * @return 自动合并或临时胜者值。
     */
    private fun <T> mergeRequired(
        base: T,
        local: T,
        remote: T,
        fieldKind: ConflictFieldKind,
        fieldPath: String,
        context: MergeContext,
        encode: (T) -> JsonElement,
    ): T = mergeNullable(
        base = base,
        local = local,
        remote = remote,
        fieldKind = fieldKind,
        fieldPath = fieldPath,
        blockId = null,
        context = context,
        encode = { value -> value?.let(encode) ?: JsonNull },
    )!!

    /**
     * 合并可空字段并在重叠修改时保留完整冲突证据。
     *
     * @param T 字段类型。
     * @param base 共同祖先值。
     * @param local 当前设备值。
     * @param remote 远端设备值。
     * @param fieldKind 冲突字段类型。
     * @param fieldPath 稳定字段路径。
     * @param blockId 可选目标 Block。
     * @param context 当前合并上下文。
     * @param encode 可空字段 JSON 编码器。
     * @return 自动合并或临时胜者值。
     */
    private fun <T> mergeNullable(
        base: T?,
        local: T?,
        remote: T?,
        fieldKind: ConflictFieldKind,
        fieldPath: String,
        blockId: BlockId?,
        context: MergeContext,
        encode: (T?) -> JsonElement,
    ): T? {
        val automatic = ThreeWayValueMerge.merge(base, local, remote)
        if (!automatic.conflicted) return automatic.value
        val winner = SyncWinnerResolver.concurrentWinner(
            localChange = context.localChange,
            remoteChange = context.remoteChange,
            localDeletes = local == null,
            remoteDeletes = remote == null,
        )
        context.conflicts += conflictRecord(
            fieldKind = fieldKind,
            fieldPath = fieldPath,
            blockId = blockId,
            baseValue = encode(base),
            localValue = encode(local),
            remoteValue = encode(remote),
            winner = winner,
            context = context,
        )
        return if (winner == MergeSide.LOCAL) local else remote
    }

    /**
     * 构造不含敏感显示名的确定性冲突记录。
     *
     * @param fieldKind 字段类型。
     * @param fieldPath 稳定字段路径。
     * @param blockId 可选 Block ID。
     * @param baseValue 共同祖先 JSON。
     * @param localValue 当前设备 JSON。
     * @param remoteValue 远端设备 JSON。
     * @param winner 临时胜者。
     * @param context 当前合并上下文。
     * @return 尚未解决的冲突记录。
     */
    private fun conflictRecord(
        fieldKind: ConflictFieldKind,
        fieldPath: String,
        blockId: BlockId?,
        baseValue: JsonElement,
        localValue: JsonElement,
        remoteValue: JsonElement,
        winner: MergeSide,
        context: MergeContext,
    ): ConflictRecord {
        val changePair = listOf(context.localChange.changeId.value, context.remoteChange.changeId.value).sorted()
        return ConflictRecord(
            conflictId = listOf(context.objectId.value, fieldKind.name.lowercase(), fieldPath, changePair[0], changePair[1])
                .joinToString(separator = "-"),
            objectId = context.objectId,
            blockId = blockId,
            fieldKind = fieldKind,
            fieldPath = fieldPath,
            baseValue = baseValue,
            localValue = localValue,
            remoteValue = remoteValue,
            temporaryWinner = winner,
            localChangeId = context.localChange.changeId,
            remoteChangeId = context.remoteChange.changeId,
            createdAt = context.mergedAt,
        )
    }

    /**
     * 使用协议 JSON 编码敏感冲突值；调用方必须把结果放入加密冲突文件。
     *
     * @param T 值类型。
     * @param serializer 值 serializer。
     * @param value 待编码值。
     * @return JSON 元素。
     */
    private fun <T> encode(
        serializer: KSerializer<T>,
        value: T,
    ): JsonElement = CanonicalJson.format.encodeToJsonElement(serializer, value)

    /**
     * 编码字符串冲突值。
     *
     * @param value 待编码字符串。
     * @return JSON 字符串。
     */
    private fun encodeString(value: String): JsonElement = JsonPrimitive(value)

    /** 保存一次 Note 合并共享的身份、胜者和冲突累积器。 */
    private data class MergeContext(
        /** 被合并对象。 */
        val objectId: ObjectId,
        /** 当前设备 Change head。 */
        val localChange: ObjectChange,
        /** 远端设备 Change head。 */
        val remoteChange: ObjectChange,
        /** 冲突创建时间。 */
        val mergedAt: String,
        /** 非删除字段的默认临时胜者。 */
        val defaultWinner: MergeSide,
        /** 当前已生成冲突。 */
        val conflicts: MutableList<ConflictRecord>,
    )
}
