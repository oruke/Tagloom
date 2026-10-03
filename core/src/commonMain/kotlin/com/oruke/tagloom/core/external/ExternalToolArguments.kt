package com.oruke.tagloom.core.external

import kotlinx.serialization.json.*

/** 统一解析外部工具参数，不把无效参数值写入错误或日志。 */
object ExternalToolArguments {
    /**
     * 读取一个严格字符串参数。
     * @param args 协议参数对象。
     * @param key 参数名称。
     * @param required 是否要求存在且非空。
     * @return 字符串或缺省空值。
     */
    fun string(args: JsonObject, key: String, required: Boolean = false): String? {
        val value = args[key] ?: return if (required) throw ExternalCallException("invalid_arguments") else null
        val primitive = value as? JsonPrimitive ?: throw ExternalCallException("invalid_arguments")
        if (!primitive.isString || (required && primitive.content.isBlank())) throw ExternalCallException("invalid_arguments")
        return primitive.content
    }

    /**
     * 读取有界整型参数。
     * @param args 协议参数对象。
     * @param key 参数名称。
     * @param default 缺省值。
     * @param range 允许范围。
     * @return 已校验数值。
     */
    fun integer(args: JsonObject, key: String, default: Int, range: IntRange): Int {
        val value = args[key] ?: return default
        val primitive = value as? JsonPrimitive ?: throw ExternalCallException("invalid_arguments")
        val number = primitive.takeUnless { it.isString }?.intOrNull ?: throw ExternalCallException("invalid_arguments")
        if (number !in range) throw ExternalCallException("invalid_arguments")
        return number
    }

    /**
     * 读取不重复的有界字符串数组。
     * @param args 协议参数对象。
     * @param key 参数名称。
     * @param maximum 最大元素数。
     * @return 字符串列表，缺省为空。
     */
    fun strings(args: JsonObject, key: String, maximum: Int = 100): List<String> {
        val values = args[key] ?: return emptyList()
        val array = values as? JsonArray ?: throw ExternalCallException("invalid_arguments")
        if (array.size > maximum) throw ExternalCallException("invalid_arguments")
        val result = array.map { element ->
            (element as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.content
                ?: throw ExternalCallException("invalid_arguments")
        }
        if (result.distinct().size != result.size) throw ExternalCallException("invalid_arguments")
        return result
    }

    /**
     * 校验不含路径与业务语义的协议 ID。
     * @param value 请求中的 ID。
     * @return 校验后的原值。
     */
    fun opaque(value: String): String {
        if (!value.matches(Regex("[A-Za-z0-9_-]{1,128}"))) throw ExternalCallException("invalid_arguments")
        return value
    }
}
