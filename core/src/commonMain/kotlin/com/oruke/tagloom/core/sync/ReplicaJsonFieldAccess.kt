package com.oruke.tagloom.core.sync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 使用有界字段路径在当前 JSON 上读取或局部替换，不创建已经删除的中间对象。 */
internal object ReplicaJsonFieldAccess {
    /**
     * 读取当前字段，不用数组位置推测稳定成员。
     * @param payload 当前完整业务 JSON。
     * @param path 字段或成员身份路径。
     * @return 目标 JSON；缺失或其父级已删除时为空，显式 JsonNull 原样返回。
     */
    fun read(payload: JsonElement, path: List<ReplicaFieldPathSegment>): JsonElement? {
        require(path.size <= ReplicaJsonFieldMerge.MAX_PATH_DEPTH) { "同步字段路径超过支持范围" }
        var current: JsonElement? = payload
        path.forEach { segment ->
            current = when {
                current == null -> null
                segment.field != null -> (current as? JsonObject)?.get(segment.field)
                else -> {
                    val array = current as? JsonArray ?: return null
                    selectorIndex(array, segment)?.let(array::get)
                }
            }
        }
        return current
    }

    /**
     * 在现存父级中替换字段，删除只移除目标，不清理其它对象或成员。
     * @param payload 当前完整业务 JSON。
     * @param path 字段或成员身份路径。
     * @param value 新值，Kotlin null 表示删除；根 payload 不允许删除。
     * @return 只改变目标路径的完整业务 JSON。
     */
    fun replace(payload: JsonElement, path: List<ReplicaFieldPathSegment>, value: JsonElement?): JsonElement {
        require(path.size <= ReplicaJsonFieldMerge.MAX_PATH_DEPTH) { "同步字段路径超过支持范围" }
        return requireNotNull(replaceNode(payload, path, 0, value)) { "不能通过字段恢复删除根对象" }
    }

    /**
     * 重建路径上的父节点，避免恢复旧字段时覆盖其它已合并内容。
     * @param current 当前已有节点；仅终点允许不存在。
     * @param path 完整有界路径。
     * @param depth 当前路径层次。
     * @param value 终点新值或删除标记。
     * @return 重建的节点或终点删除标记。
     */
    private fun replaceNode(
        current: JsonElement?, path: List<ReplicaFieldPathSegment>, depth: Int, value: JsonElement?,
    ): JsonElement? {
        if (depth == path.size) return value
        val segment = path[depth]
        if (segment.field != null) {
            val source = current as? JsonObject ?: error("同步字段父对象已不存在")
            val updated = replaceNode(source[segment.field], path, depth + 1, value)
            return JsonObject(source.toMutableMap().apply {
                if (updated == null) remove(segment.field) else put(segment.field, updated)
            })
        }
        val source = current as? JsonArray ?: error("同步字段父集合已不存在")
        val index = selectorIndex(source, segment)
        val updated = replaceNode(index?.let(source::get), path, depth + 1, value)
        if (updated != null) {
            val id = (updated as? JsonObject)?.get(requireNotNull(segment.identityField)) as? JsonPrimitive
            require(id != null && id.isString && id.content == segment.identityValue) { "恢复字段不能改变集合成员身份" }
        }
        return JsonArray(source.toMutableList().apply {
            when {
                index != null && updated == null -> removeAt(index)
                index != null -> set(index, requireNotNull(updated))
                updated != null -> add(updated)
            }
        })
    }

    /**
     * 验证选择器集合的稳定身份唯一，并定位目标；重复项不得被 firstOrNull 隐藏。
     * @param array 当前成员集合。
     * @param segment 包含身份字段与目标值的选择器。
     * @return 唯一匹配项位置；不存在时为空。
     */
    private fun selectorIndex(array: JsonArray, segment: ReplicaFieldPathSegment): Int? {
        val identityField = requireNotNull(segment.identityField)
        val identities = mutableSetOf<String>()
        var selected: Int? = null
        array.forEachIndexed { index, element ->
            val id = (element as? JsonObject)?.get(identityField) as? JsonPrimitive
            require(id != null && id.isString) { "同步字段选择器缺少稳定文本身份" }
            require(identities.add(id.content)) { "同步字段选择器匹配的集合包含重复身份" }
            if (id.content == segment.identityValue) selected = index
        }
        return selected
    }
}
