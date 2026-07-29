package com.oruke.tagloom.core.index

import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.InlineMark
import com.oruke.tagloom.core.model.InlineMarkTypes
import com.oruke.tagloom.core.model.InlineRun
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.NotePropertyValue
import com.oruke.tagloom.core.model.NoteReferenceBlockContent
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.ObjectRevision
import com.oruke.tagloom.core.model.ObjectSnapshot
import com.oruke.tagloom.core.model.ObjectType
import com.oruke.tagloom.core.model.PropertyId
import com.oruke.tagloom.core.model.PropertyValueSource
import com.oruke.tagloom.core.model.RichText
import com.oruke.tagloom.core.model.RichTextBlockContent
import com.oruke.tagloom.core.model.SnapshotHeader
import com.oruke.tagloom.core.model.TagId
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** 验证权威 Note 快照到派生索引文档的确定性投影。 */
class NoteIndexDocumentFactoryTest {
    /**
     * 验证正文、规范属性、Block 引用、行内链接和 relation 链接能够同时提取。
     */
    @Test
    fun projectsSearchableContentAndStructuredLinks() {
        val relationPropertyId = PropertyId("related-notes")
        val snapshot = ObjectSnapshot(
            header = header(),
            payload = Note(
                title = "索引测试",
                directTagIds = listOf(TagId("work")),
                propertyValues = mapOf(
                    PropertyId("metadata") to NotePropertyValue(
                        value = buildJsonObject {
                            put("z", 2)
                            put("a", 1)
                        },
                        source = PropertyValueSource.USER,
                        updatedAt = NOW,
                    ),
                    relationPropertyId to NotePropertyValue(
                        value = buildJsonArray {
                            add(JsonPrimitive("related-note"))
                            add(
                                buildJsonObject {
                                    put("noteId", "related-block-note")
                                    put("blockId", "related-block")
                                },
                            )
                        },
                        source = PropertyValueSource.USER,
                        updatedAt = NOW,
                    ),
                ),
                blocks = listOf(
                    Block(
                        id = BlockId("source-block"),
                        type = BlockTypes.PARAGRAPH,
                        orderKey = "a",
                        content = RichTextBlockContent(
                            RichText(
                                listOf(
                                    InlineRun("正文 "),
                                    InlineRun(
                                        text = "内部链接",
                                        marks = listOf(
                                            InlineMark(
                                                type = InlineMarkTypes.LINK,
                                                attributes = mapOf(
                                                    "href" to "tagloom-note:inline-note#inline-block",
                                                ),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                    Block(
                        id = BlockId("reference-block"),
                        type = BlockTypes.NOTE_REFERENCE,
                        orderKey = "b",
                        content = NoteReferenceBlockContent(
                            noteId = ObjectId("referenced-note"),
                            blockId = BlockId("referenced-block"),
                        ),
                    ),
                    Block(
                        id = BlockId("deleted-block"),
                        type = BlockTypes.PARAGRAPH,
                        orderKey = "c",
                        content = RichTextBlockContent(RichText(listOf(InlineRun("不应进入索引")))),
                        deleted = true,
                    ),
                ),
            ),
        )

        val document = NoteIndexDocumentFactory.fromSnapshot(
            snapshot = snapshot,
            relationPropertyIds = setOf(relationPropertyId),
        )

        assertEquals("正文 内部链接", document.bodyText)
        assertEquals("{\"a\":1,\"z\":2}", document.propertyValues.getValue(PropertyId("metadata")))
        assertEquals(4, document.outboundLinks.size)
        assertEquals(
            setOf(
                IndexLinkTypes.INLINE_LINK,
                IndexLinkTypes.NOTE_REFERENCE,
                IndexLinkTypes.PROPERTY_RELATION,
            ),
            document.outboundLinks.map(IndexedNoteLink::linkType).toSet(),
        )
        assertFalse(document.deleted)
    }

    /**
     * 构造索引投影测试使用的确定性 Note 协议头。
     *
     * @return 未删除 Note 的固定快照头。
     */
    private fun header(): SnapshotHeader = SnapshotHeader(
        schemaVersion = 1,
        objectId = ObjectId("source-note"),
        objectType = ObjectType.NOTE,
        revision = ObjectRevision(ActorId("device-a"), 7),
        createdAt = NOW,
        updatedAt = NOW,
    )

    private companion object {
        /** 测试快照使用的固定 UTC 时间。 */
        const val NOW: String = "2026-07-13T00:00:00Z"
    }
}
