package com.oruke.tagloom.core.model

import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.workspace.LinkNoteSchemas
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

/** 验证链接身份、网页信息来源保护和层级目录的确定性协议，不依赖网络或平台。 */
class LinkNoteDomainTest {
    /** 完整域名补协议，路径和查询保持语义；不可执行或含凭据地址不进入链接记录。 */
    @Test
    fun normalizesOnlyWebAddresses() {
        assertEquals("https://example.org/", LinkUrl.parse(" EXAMPLE.ORG ")?.value)
        assertEquals("https://example.org/a?B=1#C", LinkUrl.parse("HTTPS://EXAMPLE.ORG:443/a?B=1#C")?.value)
        assertEquals("https://example.org:8443/", LinkUrl.parse("example.org:8443")?.value)
        listOf(
            "hello",
            "/relative",
            "javascript:alert(1)",
            "file:///sample",
            "ftp://example.org/a",
            "https://user:pass@example.org/",
            "https://bad host/"
        ).forEach { assertNull(LinkUrl.parse(it)) }
    }

    /** 标题优先 OpenGraph，描述和数字实体被净化，脚本中的伪造 meta 不参与结果。 */
    @Test
    fun parsesBoundedHeadAndPreservesManualFields() {
        val html =
            """<head><script>const a='<meta property="og:title" content="Ignore">'</script><title>Fallback</title><meta content='A &amp; B &#x4E2D;' property='og:title'><meta name="description" content="  One   line &quot;only&quot; "></head><body><meta property="og:title" content="Body"></body>"""
        val metadata = WebPageMetadataParser.parse(html)
        assertEquals("A & B 中", metadata.title)
        assertEquals("One line \"only\"", metadata.description)
        val initial = LinkNotePolicy.create(LinkUrl.parse("example.org")!!, null, TIME)
        val loaded = LinkNotePolicy.mergeMetadata(initial, initial.linkMetadata!!.url, metadata, TIME)
        assertEquals(metadata.title, loaded.title)
        val edited = LinkNotePolicy.protectManualEdits(
            loaded, loaded.copy(
                title = "User title", propertyValues = loaded.propertyValues +
                        (WorkspaceDefaults.DEFAULT_TEXT_PROPERTY_ID to NotePropertyValue(
                            JsonPrimitive("User description"),
                            PropertyValueSource.USER,
                            updatedAt = TIME
                        ))
            )
        )
        val repeated = LinkNotePolicy.mergeMetadata(
            edited,
            edited.linkMetadata!!.url,
            WebPageMetadata("Replacement", "Replacement"),
            TIME
        )
        assertEquals("User title", repeated.title)
        assertEquals("User description", LinkNotePolicy.description(repeated))
        assertFalse(repeated.linkMetadata!!.automaticTitle)
        val mergedManual = loaded.copy(title = "Merged manual title")
        val firstRetry = LinkNotePolicy.mergeMetadata(mergedManual, loaded.linkMetadata!!.url, metadata, TIME)
        assertFalse(firstRetry.linkMetadata!!.automaticTitle)
        assertEquals(
            "Merged manual title",
            LinkNotePolicy.mergeMetadata(firstRetry, loaded.linkMetadata!!.url, metadata, TIME).title
        )
        val changedAddress = edited.copy(
            propertyValues = edited.propertyValues + (LinkNoteSchemas.URL_ID to edited.propertyValues.getValue(
                LinkNoteSchemas.URL_ID
            ).copy(value = JsonPrimitive("https://example.org/new")))
        )
        assertEquals(
            changedAddress,
            LinkNotePolicy.mergeMetadata(changedAddress, "https://example.org/", metadata, TIME)
        )
        assertEquals(
            repeated,
            CanonicalJson.decodeFromString(Note.serializer(), CanonicalJson.encodeToString(Note.serializer(), repeated))
        )
    }

    /** 不同父级可有同名项，稳定 ID 路径与子树计算不依赖显示名称。 */
    @Test
    fun validatesTreeAndSupportsDuplicateLeafLabels() {
        val options = listOf(
            PropertyOption("a", "Engineering"),
            PropertyOption("b", "Design"),
            PropertyOption("a1", "CSS", parentOptionId = "a"),
            PropertyOption("b1", "CSS", parentOptionId = "b")
        )
        val definition =
            PropertyDefinition(PropertyId("group"), "Group", PropertyTypes.SELECT, options, hierarchical = true)
        assertEquals("Design / CSS", PropertyOptionTree.label(definition.options, "b1"))
        assertEquals(setOf("a", "a1"), PropertyOptionTree.descendants(options, "a"))
        assertFailsWith<IllegalArgumentException> { definition.copy(hierarchical = false) }
        assertFailsWith<IllegalArgumentException> {
            definition.copy(
                options = options + PropertyOption(
                    "other",
                    "css",
                    parentOptionId = "a"
                )
            )
        }
        assertFailsWith<IllegalArgumentException> {
            definition.copy(
                options = listOf(
                    PropertyOption(
                        "a",
                        "A",
                        parentOptionId = "b"
                    ), PropertyOption("b", "B", parentOptionId = "a")
                )
            )
        }
        assertFailsWith<IllegalArgumentException> {
            definition.copy(
                options = listOf(
                    PropertyOption(
                        "a",
                        "A",
                        parentOptionId = "missing"
                    )
                )
            )
        }
        val encoded = CanonicalJson.encodeToString(PropertyDefinition.serializer(), definition)
        assertEquals(definition, CanonicalJson.decodeFromString(PropertyDefinition.serializer(), encoded))
        val legacy = CanonicalJson.decodeFromString(
            PropertyDefinition.serializer(),
            """{"id":"legacy","name":"Flat","type":"select","options":[{"id":"first","label":"First"}]}"""
        )
        assertFalse(legacy.hierarchical)
        assertNull(legacy.options.single().parentOptionId)
    }

    companion object {
        /** 脱敏、确定性的协议时间样例。 */
        private const val TIME = "2026-01-01T00:00:00Z"
    }
}
