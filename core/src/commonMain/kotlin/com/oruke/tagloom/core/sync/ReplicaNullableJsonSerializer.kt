package com.oruke.tagloom.core.sync

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/** 用存在标志包装冲突值，避免 nullable JSON 在反序列化时把 JsonNull 误认为字段缺失。 */
object ReplicaNullableJsonSerializer : KSerializer<JsonElement?> {
    /** 使用非空包装对象的结构，保证显式 JSON null 可以往返。 */
    override val descriptor: SerialDescriptor = ReplicaFieldValueEnvelope.serializer().descriptor

    /**
     * 编码是否存在以及原始字段值，不把敏感值写到包装对象以外。
     * @param encoder 加密历史使用的序列化编码器。
     * @param value 待编码值；Kotlin null 为缺失，JsonNull 为存在的空值。
     */
    override fun serialize(encoder: Encoder, value: JsonElement?) {
        encoder.encodeSerializableValue(ReplicaFieldValueEnvelope.serializer(), ReplicaFieldValueEnvelope(value != null, value ?: JsonNull))
    }

    /**
     * 从存在标志恢复缺失或原始 JSON，禁止通过 nullable 解码推测语义。
     * @param decoder 已认证历史的反序列化解码器。
     * @return 缺失时为 Kotlin null；其余情况保留原始 JSON，包括 JsonNull。
     */
    override fun deserialize(decoder: Decoder): JsonElement? {
        val envelope = decoder.decodeSerializableValue(ReplicaFieldValueEnvelope.serializer())
        return envelope.value.takeIf { envelope.present }
    }
}

/** 在加密字段证据内显式表达字段是否存在；不进入服务器公开元数据。 */
@Serializable
private data class ReplicaFieldValueEnvelope(
    /** 原始字段或成员是否存在。 */
    val present: Boolean,
    /** 存在时的原始 JSON；缺失时使用无语义的 JsonNull 占位。 */
    val value: JsonElement = JsonNull,
)
