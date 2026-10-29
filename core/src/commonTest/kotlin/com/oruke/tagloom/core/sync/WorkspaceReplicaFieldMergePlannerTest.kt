package com.oruke.tagloom.core.sync

import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.workspace.ManifestObjectKind
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 从完整 Change DAG 入口验证字段级规划、稳定选择器及冲突证据的设备方向无关性。 */
class WorkspaceReplicaFieldMergePlannerTest {
    /** 测试候选共用的脱敏稳定对象身份。 */
    private val objectId = ObjectId("field-merge-object")
    /** 测试确认及冲突记录的 UTC 时间。 */
    private val timestamp = "2026-01-01T00:00:10Z"

    /** 同一类型的名称与图标分别修改可以自动组合，未知扩展字段也不丢失。 */
    @Test
    fun mergesIndependentTagFieldsAndKeepsUnknownFields() {
        val base = encode(TagDefinition.serializer(), TagDefinition(TagId(objectId.value), "Original")) as JsonObject
        val local = JsonObject(base + ("name" to JsonPrimitive("Renamed")))
        val remote = JsonObject(base + mapOf("iconKey" to JsonPrimitive("bookmark"),
            "future" to buildJsonObject { put("enabled", true) }))
        val result = merge(ManifestObjectKind.TAG, base, local, remote)
        val payload = result.snapshot.payload.jsonObject
        assertEquals(JsonPrimitive("Renamed"), payload["name"])
        assertEquals(JsonPrimitive("bookmark"), payload["iconKey"])
        assertEquals(remote["future"], payload["future"])
        assertTrue(result.conflicts.isEmpty())
    }

    /** 稳定选项身份允许同一选项不同字段合并，与数组重排及另一个选项的新增无关。 */
    @Test
    fun mergesOptionLabelColorAndIndependentAdditionByIdentity() {
        val property = PropertyDefinition(PropertyId(objectId.value), "Options", PropertyTypes.SELECT,
            options = listOf(PropertyOption("one", "One"), PropertyOption("two", "Two")))
        val base = encode(PropertyDefinition.serializer(), property)
        val local = encode(PropertyDefinition.serializer(), property.copy(options = listOf(
            PropertyOption("two", "Two"), PropertyOption("one", "First"))))
        val remote = encode(PropertyDefinition.serializer(), property.copy(options = listOf(
            PropertyOption("one", "One", colorToken = "blue"), PropertyOption("two", "Two"), PropertyOption("three", "Three"))))
        val result = merge(ManifestObjectKind.PROPERTY, base, local, remote)
        val merged = CanonicalJson.format.decodeFromJsonElement(PropertyDefinition.serializer(), result.snapshot.payload)
        assertEquals(PropertyOption("one", "First", colorToken = "blue"), merged.options.single { it.id == "one" })
        assertEquals(setOf("one", "two", "three"), merged.options.map { it.id }.toSet())
        assertTrue(result.conflicts.isEmpty())
    }

    /** 选择冲突标题只替换当前标题，已经自动组合的两段独立正文仍然完整保留。 */
    @Test
    fun locatesTitleConflictWithoutRollingBackMergedBody() {
        val base = note("Original", "First", "Second")
        val local = note("Title one", "First edited", "Second")
        val remote = note("Title two", "First", "Second edited")
        val result = merge(ManifestObjectKind.NOTE, base, local, remote)
        val conflict = result.conflicts.single()
        val field = conflict.fields.single()
        assertEquals(listOf(ReplicaFieldPathSegment(field = "title")), field.path)
        assertEquals(JsonPrimitive("Original"), field.baseValue)
        val restored = ReplicaJsonFieldMerge.replace(result.snapshot.payload, field.path, field.localValue)
        val note = CanonicalJson.format.decodeFromJsonElement(Note.serializer(), restored)
        assertEquals("Title one", note.title)
        assertEquals(listOf("First edited", "Second edited"), note.blocks.map { (it.content as RichTextBlockContent).richText.plainText })
    }

    /** 多项 Note 冲突以字段和 Block 身份定位，双方对调仍产生同一套稳定版本证据。 */
    @Test
    fun normalizesNoteFieldEvidenceWhenDevicesAreReversed() {
        val base = note("Original", "First", "Second")
        val local = note("Title one", "First version one", "Second")
        val remote = note("Title two", "First version two", "Second")
        val forward = merge(ManifestObjectKind.NOTE, base, local, remote)
        val reverse = merge(ManifestObjectKind.NOTE, base, local, remote, reverse = true)
        assertEquals(forward.snapshot.payload, reverse.snapshot.payload)
        assertEquals(forward.conflicts, reverse.conflicts)
        val fields = forward.conflicts.single().fields
        assertEquals(2, fields.size)
        assertTrue(fields.any { it.path == listOf(ReplicaFieldPathSegment(field = "title")) })
        val block = fields.single { it.path.first().field == "blocks" }
        assertEquals(ReplicaFieldPathSegment(identityField = "id", identityValue = "first"), block.path.last())
        assertEquals("first", block.localValue?.jsonObject?.get("id")?.jsonPrimitive?.content)
    }

    /** 同一个属性值的删除与修改不把缺失混为 JSON null，选择器保持使用属性稳定身份。 */
    @Test
    fun keepsMissingPropertyDistinctFromExplicitNull() {
        val propertyId = PropertyId("property-one")
        val value = NotePropertyValue(JsonNull, PropertyValueSource.USER, updatedAt = timestamp)
        val base = Note("Property example", emptyList(), propertyValues = mapOf(propertyId to value))
        val local = base.copy(propertyValues = emptyMap())
        val remote = base.copy(propertyValues = mapOf(propertyId to value.copy(value = JsonPrimitive("changed"))))
        val result = merge(ManifestObjectKind.NOTE, encode(Note.serializer(), base),
            encode(Note.serializer(), local), encode(Note.serializer(), remote))
        val field = result.conflicts.single().fields.single()
        assertEquals(listOf(ReplicaFieldPathSegment(field = "propertyValues"), ReplicaFieldPathSegment(field = propertyId.value)), field.path)
        assertEquals(null, field.localValue)
        assertEquals(JsonNull, field.baseValue?.jsonObject?.get("value"))
        assertEquals(JsonPrimitive("changed"), field.remoteValue?.jsonObject?.get("value"))
    }

    /** 网页来源记录单侧更新时，另一侧较新的正文修改不能使来源记录回退。 */
    @Test
    fun preservesIndependentLinkMetadataChange() {
        val base = Note("Link example", emptyList(), linkMetadata = LinkMetadataProvenance("https://example.invalid", title = "Before"))
        val local = base.copy(linkMetadata = base.linkMetadata?.copy(title = "After"))
        val remote = base.copy(blocks = listOf(paragraph("first", "a", "Body change")))
        val result = merge(ManifestObjectKind.NOTE, encode(Note.serializer(), base),
            encode(Note.serializer(), local), encode(Note.serializer(), remote))
        val merged = CanonicalJson.format.decodeFromJsonElement(Note.serializer(), result.snapshot.payload)
        assertEquals("After", merged.linkMetadata?.title)
        assertEquals("Body change", (merged.blocks.single().content as RichTextBlockContent).richText.plainText)
        assertTrue(result.conflicts.isEmpty())
    }

    /** Note 未知顶层扩展的独立修改自动组合，冲突路径仍可直接作用于完整 Note。 */
    @Test
    fun preservesAndLocatesUnknownTopLevelNoteFields() {
        val base = JsonObject(note("Original", "First", "Second").jsonObject +
            ("future" to buildJsonObject { put("color", "base"); put("enabled", false) }))
        val local = JsonObject(base + ("future" to buildJsonObject { put("color", "left"); put("enabled", true) }))
        val remote = JsonObject(base + ("future" to buildJsonObject { put("color", "right"); put("enabled", false) }))
        val result = merge(ManifestObjectKind.NOTE, base, local, remote)
        val field = result.conflicts.single().fields.single()
        assertEquals(listOf(ReplicaFieldPathSegment(field = "future"), ReplicaFieldPathSegment(field = "color")), field.path)
        val restored = ReplicaJsonFieldMerge.replace(result.snapshot.payload, field.path, field.localValue)
        assertEquals(JsonPrimitive("left"), restored.jsonObject.getValue("future").jsonObject["color"])
        assertEquals(JsonPrimitive(true), restored.jsonObject.getValue("future").jsonObject["enabled"])
    }

    /** 无真实共同祖先的定义冲突明确回退整对象，不提供猜造的字段恢复范围。 */
    @Test
    fun retainsWholeSchemaVersionsWhenBaseIsMissing() {
        val local = change("left", "device-a", 2, buildJsonObject { put("name", "Left") },
            listOf(ChangeId("missing")), ManifestObjectKind.TAG)
        val remote = change("right", "device-b", 3, buildJsonObject { put("name", "Right") },
            listOf(ChangeId("missing")), ManifestObjectKind.TAG)
        val result = WorkspaceReplicaMergePlanner.plan(listOf(replica(ManifestObjectKind.TAG, local)),
            listOf(replica(ManifestObjectKind.TAG, remote)), listOf(local, remote), timestamp) { _, _, _, _ -> "missing-base" }
        assertEquals(WorkspaceReplicaConflictReason.NO_COMMON_ANCESTOR, result.conflicts.single().reason)
        assertTrue(result.conflicts.single().fields.isEmpty())
    }

    /**
     * 创建有真实共同祖先的两条并发分支，并从正式规划器入口计算结果。
     * @param kind 对象目录类别。
     * @param base 共同祖先完整 payload。
     * @param local 版本一完整 payload。
     * @param remote 版本二完整 payload。
     * @param reverse 是否反转本机与远端输入方向。
     * @return 单对象规划结果。
     */
    private fun merge(kind: ManifestObjectKind, base: JsonElement, local: JsonElement, remote: JsonElement,
        reverse: Boolean = false): WorkspaceReplicaDecision {
        val root = change("root", "device-root", 1, base, emptyList(), kind)
        val left = change("left", "device-a", 2, local, listOf(root.changeId), kind)
        val right = change("right", "device-b", 3, remote, listOf(root.changeId), kind)
        return WorkspaceReplicaMergePlanner.plan(listOf(replica(kind, if (reverse) right else left)),
            listOf(replica(kind, if (reverse) left else right)), listOf(root, left, right), timestamp) { _, _, first, second ->
            "conflict-" + listOfNotNull(first?.value, second?.value).sorted().joinToString("-")
        }.objects.single()
    }

    /**
     * 为给定对象类别创建可被规划器重放的完整替换 Change。
     * @param id Change 身份。
     * @param actor 设备身份。
     * @param time HLC 测试物理时间。
     * @param payload 完整业务 JSON。
     * @param parents 真实父节点。
     * @param kind 对象目录类别。
     * @return 已包含完整 payload 的测试 Change。
     */
    private fun change(id: String, actor: String, time: Long, payload: JsonElement, parents: List<ChangeId>,
        kind: ManifestObjectKind): ObjectChange {
        val protocol = when (kind) {
            ManifestObjectKind.NOTE -> "note"
            ManifestObjectKind.TAG -> "tag-definition"
            ManifestObjectKind.PROPERTY -> "property-definition"
            else -> error("未覆盖的测试对象类别")
        }
        return ObjectChange(ChangeId(id), objectId, parents, ActorId(actor), HybridLogicalTime(time, 0, ActorId(actor)),
            null, TransactionId("transaction-$id"), ChangeKind.UPDATE, listOf(ChangeOperation("replace-$protocol", payload = payload)))
    }

    /**
     * 用真实快照头包装一份完整测试候选。
     * @param kind 对象类别。
     * @param change 产生此候选的 head。
     * @return 可直接进入规划器的候选。
     */
    private fun replica(kind: ManifestObjectKind, change: ObjectChange): WorkspaceReplicaObject = WorkspaceReplicaObject(kind,
        ObjectSnapshot(SnapshotHeader(1, objectId, when (kind) {
            ManifestObjectKind.NOTE -> ObjectType.NOTE
            ManifestObjectKind.TAG -> ObjectType.TAG
            else -> ObjectType.PROPERTY
        }, ObjectRevision(change.actorId, change.hybridLogicalTime.physicalTimeMillis), change.changeId,
            createdAt = timestamp, updatedAt = timestamp), requireNotNull(change.operations.single().payload)))

    /**
     * 构造含两个独立正文 Block 的 Note payload。
     * @param title 标题。
     * @param first 第一段正文。
     * @param second 第二段正文。
     * @return 完整 Note JSON。
     */
    private fun note(title: String, first: String, second: String): JsonElement = encode(Note.serializer(),
        Note(title, emptyList(), blocks = listOf(paragraph("first", "a", first), paragraph("second", "b", second))))

    /**
     * 构造合法且具有稳定身份的文本段落。
     * @param id Block 身份。
     * @param order 同级顺序。
     * @param text 正文。
     * @return 规范段落 Block。
     */
    private fun paragraph(id: String, order: String, text: String): Block = Block(BlockId(id), BlockTypes.PARAGRAPH,
        orderKey = order, content = RichTextBlockContent(RichText(listOf(InlineRun(text)))))

    /**
     * 使用生产协议配置编码测试对象。
     * @param T 业务对象类型。
     * @param serializer 对象序列化器。
     * @param value 业务对象。
     * @return 完整协议 JSON。
     */
    private fun <T> encode(serializer: KSerializer<T>, value: T): JsonElement = CanonicalJson.format.encodeToJsonElement(serializer, value)
}
