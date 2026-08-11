package com.oruke.tagloom.core.model

import com.oruke.tagloom.core.protocol.CanonicalJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 验证 Database Block 只保存独立权威对象的稳定引用。 */
class DatabaseBlockProtocolTest {
    /** 验证 Database 引用经过 Block 多态序列化后保持类型和稳定 ID。 */
    @Test
    fun databaseReferenceRoundTripsThroughBlockSerializer() {
        val block = Block(
            id = BlockId("database-block"),
            type = BlockTypes.DATABASE,
            orderKey = "a000000",
            content = DatabaseBlockContent(DatabaseId("database-main")),
        )

        val encoded = CanonicalJson.encodeToString(Block.serializer(), block)
        val decoded = CanonicalJson.decodeFromString(Block.serializer(), encoded)
        val content = assertIs<DatabaseBlockContent>(decoded.content)

        assertEquals(DatabaseId("database-main"), content.databaseId)
        assertTrue(BlockValidator.validate(decoded).isEmpty())
        assertEquals(encoded, CanonicalJson.encodeToString(Block.serializer(), decoded))
    }
}
