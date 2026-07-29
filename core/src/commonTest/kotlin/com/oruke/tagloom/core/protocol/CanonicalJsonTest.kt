package com.oruke.tagloom.core.protocol

import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.ObjectRevision
import com.oruke.tagloom.core.model.ObjectSnapshot
import com.oruke.tagloom.core.model.ObjectType
import com.oruke.tagloom.core.model.SnapshotHeader
import com.oruke.tagloom.core.model.TagId
import com.oruke.tagloom.core.model.UnknownBlockContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 验证权威快照的稳定 JSON 和未知 Block 往返兼容。
 */
class CanonicalJsonTest {
    /**
     * 验证相同语义的 Map 输入顺序不会改变快照 JSON。
     */
    @Test
    fun snapshotEncodingSortsJsonObjectKeys() {
        val first = snapshot(
            attributes = linkedMapOf("z" to JsonPrimitive(1), "a" to JsonPrimitive(2)),
        )
        val second = snapshot(
            attributes = linkedMapOf("a" to JsonPrimitive(2), "z" to JsonPrimitive(1)),
        )
        val serializer = ObjectSnapshot.serializer(Note.serializer())

        val firstJson = CanonicalJson.encodeToString(serializer, first)
        val secondJson = CanonicalJson.encodeToString(serializer, second)

        assertEquals(firstJson, secondJson)
        assertTrue(firstJson.indexOf("\"a\"") < firstJson.indexOf("\"z\""))
    }

    /**
     * 验证未来 Block 内容经过旧客户端解码和编码后仍保留原字段。
     */
    @Test
    fun unknownBlockContentRoundTrips() {
        val serializer = ObjectSnapshot.serializer(Note.serializer())
        val source = snapshot(
            attributes = linkedMapOf("future" to JsonPrimitive("value")),
        )

        val encoded = CanonicalJson.encodeToString(serializer, source)
        val decoded = CanonicalJson.decodeFromString(serializer, encoded)
        val unknown = assertIs<UnknownBlockContent>(decoded.payload.blocks.single().content)

        assertEquals(JsonPrimitive("future-block-content"), unknown.raw["_contentType"])
        assertEquals(JsonPrimitive("value"), unknown.raw["future"])
        assertEquals(encoded, CanonicalJson.encodeToString(serializer, decoded))
    }

    /**
     * 创建包含未知 Block 内容的测试快照。
     *
     * @param attributes 未知 Block payload 字段。
     * @return 可用于稳定序列化验证的笔记快照。
     */
    private fun snapshot(
        attributes: Map<String, JsonPrimitive>,
    ): ObjectSnapshot<Note> {
        val raw = buildJsonObject {
            put("_contentType", "future-block-content")
            attributes.forEach { (key, value) -> put(key, value) }
        }
        return ObjectSnapshot(
            header = SnapshotHeader(
                schemaVersion = 1,
                objectId = ObjectId("note-1"),
                objectType = ObjectType.NOTE,
                revision = ObjectRevision(ActorId("device-1"), 1),
                createdAt = "2026-07-13T00:00:00Z",
                updatedAt = "2026-07-13T00:00:00Z",
            ),
            payload = Note(
                title = "测试",
                directTagIds = listOf(TagId("note")),
                blocks = listOf(
                    Block(
                        id = BlockId("block-1"),
                        type = "future-block",
                        orderKey = "a1",
                        content = UnknownBlockContent(raw),
                    ),
                ),
            ),
        )
    }
}
