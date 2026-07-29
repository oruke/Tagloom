package com.oruke.tagloom.core.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 提供协议对象的稳定 JSON 编解码。
 *
 * 编码前递归排序对象键，配合领域模型规范化生成可比较、可哈希的确定性 payload。
 */
@OptIn(ExperimentalSerializationApi::class)
object CanonicalJson {
    /**
     * Tagloom 协议共享的 JSON 配置。
     *
     * 关闭异常调试输入，避免解析失败消息意外携带笔记正文。
     */
    val format: Json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
        isLenient = false
        allowStructuredMapKeys = false
        exceptionsWithDebugInfo = false
    }

    /**
     * 将协议对象编码为键顺序稳定的紧凑 JSON。
     *
     * @param serializer 对象对应的 kotlinx serializer。
     * @param value 待编码的协议对象。
     * @return 可用于快照 payload 和哈希输入的稳定 JSON 文本。
     */
    fun <T> encodeToString(
        serializer: KSerializer<T>,
        value: T,
    ): String {
        val source = format.encodeToJsonElement(serializer, value)
        val canonical = canonicalize(source)
        return format.encodeToString(JsonElement.serializer(), canonical)
    }

    /**
     * 从 JSON 文本恢复协议对象。
     *
     * @param serializer 目标对象对应的 kotlinx serializer。
     * @param value 待解析的 JSON 文本。
     * @return 解析得到的协议对象。
     */
    fun <T> decodeFromString(
        serializer: KSerializer<T>,
        value: String,
    ): T = format.decodeFromString(serializer, value)

    /**
     * 递归排序 JSON 对象键，同时保持数组顺序和原始值不变。
     *
     * @param element 待规范化的 JSON 元素。
     * @return 键顺序稳定的 JSON 元素。
     */
    private fun canonicalize(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> buildJsonObject {
            element.entries.sortedBy { (key, _) -> key }.forEach { (key, value) ->
                put(key, canonicalize(value))
            }
        }

        is JsonArray -> buildJsonArray {
            element.forEach { value -> add(canonicalize(value)) }
        }

        else -> element
    }
}
