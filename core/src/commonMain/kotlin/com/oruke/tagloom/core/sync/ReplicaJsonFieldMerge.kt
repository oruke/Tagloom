package com.oruke.tagloom.core.sync

import com.oruke.tagloom.core.workspace.ManifestObjectKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 保存定义对象按字段合并的候选与不能自动决定的最小字段，不执行持久化或目录校验。 */
data class ReplicaJsonFieldMergeResult(
    /** 组合独立修改后的完整 payload。 */
    val payload: JsonElement,
    /** 只包含双方均偏离共同基线且值不同的字段。 */
    val fields: List<WorkspaceReplicaFieldConflict>,
)

/**
 * 按 JSON 字段和已知稳定集合身份合并定义对象，并保留未知字段。
 *
 * 数组下标不代表身份；没有明确业务身份的数组保持原子，模板正文以完整 Block 为最小单元。
 */
object ReplicaJsonFieldMerge {
    /** 合并和字段访问允许的最大嵌套层数，防止异常认证数据耗尽调用栈。 */
    internal const val MAX_PATH_DEPTH: Int = 64

    /**
     * 使用真实共同祖先组合两侧定义对象，冲突字段沿用调用方确定的稳定胜者。
     *
     * @param kind 定义对象的 manifest 类别。
     * @param base 可认证的共同祖先完整 payload。
     * @param local 本轮本地完整 payload。
     * @param remote 本轮远端完整 payload。
     * @param winner 无法安全组合时选择的稳定一侧。
     * @return 完整候选与可独立选择版本的字段证据；领域合法性由目录校验器继续验证。
     */
    fun merge(
        kind: ManifestObjectKind,
        base: JsonElement,
        local: JsonElement,
        remote: JsonElement,
        winner: MergeSide,
    ): ReplicaJsonFieldMergeResult {
        val fields = mutableListOf<WorkspaceReplicaFieldConflict>()
        val payload = mergeNode(kind, emptyList(), base, local, remote, winner, fields)
        return ReplicaJsonFieldMergeResult(requireNotNull(payload), fields)
    }

    /**
     * 读取普通字段或稳定集合成员，缺失和显式 JSON null 保持不同。
     * @param payload 当前完整 payload。
     * @param path 字段与稳定身份组成的路径，空路径读取整个对象。
     * @return 目标值；不存在时为 Kotlin null。
     */
    fun read(payload: JsonElement, path: List<ReplicaFieldPathSegment>): JsonElement? =
        ReplicaJsonFieldAccess.read(payload, path)

    /**
     * 在当前 payload 上仅替换一个字段，保留其余已合并或后续编辑的内容。
     * @param payload 当前完整 payload。
     * @param path 字段与稳定身份组成的路径，空路径表示整个 payload。
     * @param value 选择的值；Kotlin null 删除目标，JsonNull 写入显式空值。
     * @return 替换后的完整 payload；父级已不存在、身份不匹配或选择器重复时拒绝修改。
     */
    fun replace(payload: JsonElement, path: List<ReplicaFieldPathSegment>, value: JsonElement?): JsonElement =
        ReplicaJsonFieldAccess.replace(payload, path, value)

    /**
     * 合并单个可缺失节点，只有双方都修改时才递归细化对象或稳定集合。
     * @param kind 所属定义对象类别。
     * @param path 当前稳定路径。
     * @param base 基线值，Kotlin null 表示字段或成员不存在。
     * @param local 本地值。
     * @param remote 远端值。
     * @param winner 重叠修改的稳定胜者。
     * @param fields 收集无法自动处理的字段证据。
     * @return 组合后的值；Kotlin null 表示删除。
     */
    private fun mergeNode(
        kind: ManifestObjectKind,
        path: List<ReplicaFieldPathSegment>,
        base: JsonElement?,
        local: JsonElement?,
        remote: JsonElement?,
        winner: MergeSide,
        fields: MutableList<WorkspaceReplicaFieldConflict>,
    ): JsonElement? {
        require(path.size <= MAX_PATH_DEPTH) { "同步字段嵌套超过支持范围" }
        val simple = ThreeWayValueMerge.merge(base, local, remote)
        if (!simple.conflicted) return simple.value
        // 模板正文以 Block 为权威单元，不能拆开 type/content 后构造无效模板。
        val templateBlock = kind == ManifestObjectKind.TAG && path.size == 2 && path.first().field == "templateBlocks"
        if (!templateBlock && local is JsonObject && remote is JsonObject && (base == null || base is JsonObject)) {
            val baseObject = base as? JsonObject
            val keys = (baseObject.orEmpty().keys + local.keys + remote.keys).sorted()
            return JsonObject(buildMap {
                keys.forEach { key ->
                    val value = mergeNode(kind, path + ReplicaFieldPathSegment(field = key),
                        baseObject?.get(key), local[key], remote[key], winner, fields)
                    if (value != null) put(key, value)
                }
            })
        }
        if (local is JsonArray && remote is JsonArray && (base == null || base is JsonArray)) {
            val baseArray = base as? JsonArray ?: JsonArray(emptyList())
            val identityField = collectionIdentity(kind, path)
            if (identityField != null) return mergeCollection(kind, path, identityField, baseArray, local, remote, winner, fields)
            if (isIdentitySet(kind, path)) return mergeIdentitySet(baseArray, local, remote)
        }
        fields += WorkspaceReplicaFieldConflict(path, base, local, remote, winner)
        return if (winner == MergeSide.LOCAL) local else remote
    }

    /**
     * 按稳定身份组合新增、删除与字段修改，不将当前数组位置写入冲突路径。
     * @param kind 所属定义对象类别。
     * @param path 当前集合路径。
     * @param identityField 集合元素中承载稳定身份的字段名。
     * @param base 共同祖先集合。
     * @param local 本地集合。
     * @param remote 远端集合。
     * @param winner 重叠成员或重排的稳定胜者。
     * @param fields 需要用户处理的字段证据。
     * @return 独立合并内容并保留确定性展示顺序的集合。
     */
    private fun mergeCollection(
        kind: ManifestObjectKind,
        path: List<ReplicaFieldPathSegment>,
        identityField: String,
        base: JsonArray,
        local: JsonArray,
        remote: JsonArray,
        winner: MergeSide,
        fields: MutableList<WorkspaceReplicaFieldConflict>,
    ): JsonArray {
        val baseById = indexCollection(base, identityField)
        val localById = indexCollection(local, identityField)
        val remoteById = indexCollection(remote, identityField)
        val merged = buildMap {
            (baseById.keys + localById.keys + remoteById.keys).sorted().forEach { id ->
                val selector = ReplicaFieldPathSegment(identityField = identityField, identityValue = id)
                val value = mergeNode(kind, path + selector, baseById[id], localById[id], remoteById[id], winner, fields)
                if (value != null) put(id, value)
            }
        }
        val order = collectionOrder(baseById.keys.toList(), localById.keys.toList(), remoteById.keys.toList(), merged.keys, winner)
        return JsonArray(order.map { merged.getValue(it) })
    }

    /**
     * 校验稳定集合的身份完整且唯一，拒绝把重复成员误认为同一个实体。
     * @param array 一个已认证的集合候选。
     * @param identityField 成员身份字段名。
     * @return 保持原展示顺序的身份映射。
     */
    private fun indexCollection(array: JsonArray, identityField: String): Map<String, JsonObject> {
        val result = linkedMapOf<String, JsonObject>()
        array.forEach { element ->
            val item = element as? JsonObject ?: error("同步稳定集合成员不是对象")
            val id = item[identityField] as? JsonPrimitive
            require(id != null && id.isString) { "同步稳定集合成员缺少文本身份" }
            require(result.put(id.content, item) == null) { "同步稳定集合包含重复身份" }
        }
        return result
    }

    /**
     * 合并集合成员资格，保留独立新增并尊重相对基线的删除；集合展示使用稳定排序。
     * @param base 基线身份集合。
     * @param local 本地身份集合。
     * @param remote 远端身份集合。
     * @return 不重复且与设备方向无关的稳定身份数组。
     */
    private fun mergeIdentitySet(base: JsonArray, local: JsonArray, remote: JsonArray): JsonArray {
        val baseIds = identitySet(base)
        val localIds = identitySet(local)
        val remoteIds = identitySet(remote)
        val merged = (baseIds + localIds + remoteIds).filter { id ->
            ThreeWayValueMerge.merge(id in baseIds, id in localIds, id in remoteIds).value
        }.sorted()
        return JsonArray(merged.map(::JsonPrimitive))
    }

    /**
     * 将不允许重复的文本身份数组转为集合，避免无效候选在合并时被静默去重。
     * @param array 待检查的身份数组。
     * @return 该数组的唯一身份集合。
     */
    private fun identitySet(array: JsonArray): Set<String> {
        val ids = array.map { element ->
            val value = element as? JsonPrimitive
            require(value != null && value.isString) { "同步身份集合只允许文本成员" }
            value.content
        }
        require(ids.distinct().size == ids.size) { "同步身份集合包含重复成员" }
        return ids.toSet()
    }

    /**
     * 对仍存在的基线成员做三方顺序选择，再按另一分支的相邻成员位置补入新增项。
     *
     * 单侧重排应保留；双方重排不一致时沿用稳定胜者的展示顺序，成员字段仍分别合并。
     * @param base 基线展示顺序。
     * @param local 本地展示顺序。
     * @param remote 远端展示顺序。
     * @param present 合并后仍存在的成员身份。
     * @param winner 并发重排的稳定胜者。
     * @return 覆盖全部当前成员的确定性顺序。
     */
    private fun collectionOrder(
        base: List<String>, local: List<String>, remote: List<String>, present: Set<String>, winner: MergeSide,
    ): List<String> {
        val common = base.toSet().intersect(local.toSet()).intersect(remote.toSet()).intersect(present)
        val baseOrder = base.filter { it in common }
        val localOrder = local.filter { it in common }
        val remoteOrder = remote.filter { it in common }
        val preferLocal = when {
            localOrder == baseOrder && remoteOrder != baseOrder -> false
            remoteOrder == baseOrder && localOrder != baseOrder -> true
            else -> winner == MergeSide.LOCAL
        }
        val primary = if (preferLocal) local else remote
        val secondary = if (preferLocal) remote else local
        val result = primary.filter { it in present }.toMutableList()
        secondary.forEachIndexed { index, id ->
            if (id in present && id !in result) {
                val next = secondary.drop(index + 1).firstOrNull { it in result }
                if (next == null) result.add(id) else result.add(result.indexOf(next), id)
            }
        }
        result.addAll((present - result.toSet()).sorted())
        return result
    }

    /**
     * 只为协议已经定义唯一身份的根集合启用逐成员合并。
     * @param kind 定义对象类别。
     * @param path 当前字段路径。
     * @return 成员身份字段；未知或无身份集合返回空。
     */
    private fun collectionIdentity(kind: ManifestObjectKind, path: List<ReplicaFieldPathSegment>): String? {
        if (path.size != 1) return null
        return when (kind to path.single().field) {
            ManifestObjectKind.TAG to "propertyBindings", ManifestObjectKind.SAVED_QUERY to "propertyFilters" -> "propertyId"
            ManifestObjectKind.TAG to "templateBlocks", ManifestObjectKind.PROPERTY to "options" -> "id"
            else -> null
        }
    }

    /**
     * 判断当前数组是否具有集合语义，不能将未知数组顺序误当成可丢弃的展示顺序。
     * @param kind 定义对象类别。
     * @param path 当前字段路径。
     * @return 已知父类型或保存查询类型集合时为真。
     */
    private fun isIdentitySet(kind: ManifestObjectKind, path: List<ReplicaFieldPathSegment>): Boolean = path.size == 1 &&
        (kind == ManifestObjectKind.TAG && path.single().field == "parentTagIds" ||
            kind == ManifestObjectKind.SAVED_QUERY && path.single().field == "tagIds")
}
