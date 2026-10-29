package com.oruke.tagloom.core.sync

import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.workspace.ManifestObjectKind
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 验证定义字段的真实三方组合、稳定集合身份与在当前结果上的局部恢复。 */
class ReplicaJsonFieldMergeTest {
    /** 类型字段和属性绑定的不同字段分别修改时自动组合，未知字段也保留。 */
    @Test
    fun mergesIndependentDefinitionAndBindingFieldsWithoutLosingUnknownValues() {
        val base = json("""{"name":"Original","iconKey":"note","future":{"left":0,"right":0},"propertyBindings":[{"propertyId":"property-a","required":false,"styleOverride":{"visible":true,"order":0}}]}""")
        val local = json("""{"name":"Renamed","iconKey":"note","future":{"left":1,"right":0},"propertyBindings":[{"propertyId":"property-a","required":true,"styleOverride":{"visible":true,"order":0}}]}""")
        val remote = json("""{"name":"Original","iconKey":"link","future":{"left":0,"right":2},"propertyBindings":[{"propertyId":"property-a","required":false,"styleOverride":{"visible":false,"order":0}}]}""")
        val merged = merge(ManifestObjectKind.TAG, base, local, remote)
        assertTrue(merged.fields.isEmpty())
        assertEquals(json("""{"name":"Renamed","iconKey":"link","future":{"left":1,"right":2},"propertyBindings":[{"propertyId":"property-a","required":true,"styleOverride":{"visible":false,"order":0}}]}"""), merged.payload)
    }

    /** 同一选项名称竞争只生成名称冲突，另一设备的颜色和未知字段仍进入候选。 */
    @Test
    fun reportsOnlyOverlappingOptionFieldAndRetainsOtherMergedEdits() {
        val base = json("""{"name":"State","options":[{"id":"option-a","label":"Before","colorToken":"neutral"}]}""")
        val local = json("""{"name":"Local name","options":[{"id":"option-a","label":"Local","colorToken":"neutral","future":true}]}""")
        val remote = json("""{"name":"State","options":[{"id":"option-a","label":"Remote","colorToken":"accent"}]}""")
        val merged = merge(ManifestObjectKind.PROPERTY, base, local, remote)
        val field = merged.fields.single()
        assertEquals(optionPath("option-a", "label"), field.path)
        assertEquals(JsonPrimitive("Before"), field.baseValue)
        assertEquals(JsonPrimitive("Local"), field.localValue)
        assertEquals(JsonPrimitive("Remote"), field.remoteValue)
        assertEquals(MergeSide.REMOTE, field.temporaryWinner)
        assertEquals(json("""{"name":"Local name","options":[{"id":"option-a","label":"Remote","colorToken":"accent","future":true}]}"""), merged.payload)

        val restored = ReplicaJsonFieldMerge.replace(merged.payload, field.path, field.localValue)
        assertEquals(JsonPrimitive("Local"), ReplicaJsonFieldMerge.read(restored, field.path))
        assertEquals(JsonPrimitive("accent"), ReplicaJsonFieldMerge.read(restored, optionPath("option-a", "colorToken")))
        assertEquals(JsonPrimitive("Local name"), ReplicaJsonFieldMerge.read(restored, path("name")))
    }

    /** 一侧仅重排已有选项、另一侧修改名称时按身份合并，并保留单侧重排。 */
    @Test
    fun mergesOptionsByIdentityAcrossReorderEvenWhenOtherSideWins() {
        val base = json("""{"options":[{"id":"a","label":"A"},{"id":"b","label":"B"}]}""")
        val local = json("""{"options":[{"id":"a","label":"Changed A"},{"id":"b","label":"B"}]}""")
        val remote = json("""{"options":[{"id":"b","label":"B"},{"id":"a","label":"A"}]}""")
        val merged = merge(ManifestObjectKind.PROPERTY, base, local, remote, MergeSide.LOCAL)
        assertTrue(merged.fields.isEmpty())
        assertEquals(json("""{"options":[{"id":"b","label":"B"},{"id":"a","label":"Changed A"}]}"""), merged.payload)
    }

    /** 同时在相邻位置新增的不同选项均保留，交换设备方向后得到相同顺序。 */
    @Test
    fun preservesConcurrentAdditionsWithDeterministicOrdering() {
        val base = json("""{"options":[{"id":"a","label":"A"},{"id":"z","label":"Z"}]}""")
        val local = json("""{"options":[{"id":"a","label":"A"},{"id":"left","label":"Left"},{"id":"z","label":"Z"}]}""")
        val remote = json("""{"options":[{"id":"a","label":"A"},{"id":"right","label":"Right"},{"id":"z","label":"Z"}]}""")
        val merged = merge(ManifestObjectKind.PROPERTY, base, local, remote, MergeSide.LOCAL)
        val reversed = merge(ManifestObjectKind.PROPERTY, base, remote, local, MergeSide.REMOTE)
        assertTrue(merged.fields.isEmpty())
        assertEquals(merged.payload, reversed.payload)
        assertEquals(json("""{"options":[{"id":"a","label":"A"},{"id":"left","label":"Left"},{"id":"right","label":"Right"},{"id":"z","label":"Z"}]}"""), merged.payload)
    }

    /** 删除与修改同一个成员时保留整成员冲突，不丢弃同时新增的其它选项。 */
    @Test
    fun keepsDeleteUpdateConflictAtMemberIdentityAndCanRestoreOnlyThatMember() {
        val base = json("""{"options":[{"id":"a","label":"Before"},{"id":"b","label":"B"}]}""")
        val local = json("""{"options":[{"id":"b","label":"B"},{"id":"c","label":"New C"}]}""")
        val remote = json("""{"options":[{"id":"a","label":"Updated"},{"id":"b","label":"Updated B"}]}""")
        val merged = merge(ManifestObjectKind.PROPERTY, base, local, remote, MergeSide.LOCAL)
        val field = merged.fields.single()
        assertEquals(optionPath("a"), field.path)
        assertNull(field.localValue)
        assertNull(ReplicaJsonFieldMerge.read(merged.payload, field.path))
        val restored = ReplicaJsonFieldMerge.replace(merged.payload, field.path, field.remoteValue)
        assertEquals(JsonPrimitive("Updated B"), ReplicaJsonFieldMerge.read(restored, optionPath("b", "label")))
        assertEquals(JsonPrimitive("New C"), ReplicaJsonFieldMerge.read(restored, optionPath("c", "label")))
        assertEquals(field.remoteValue, ReplicaJsonFieldMerge.read(restored, field.path))
        assertEquals(merged.payload, ReplicaJsonFieldMerge.replace(restored, field.path, null))
    }

    /** 类型集合的独立新增和删除按成员资格组合，不把列表位置当成冲突。 */
    @Test
    fun mergesParentAndSavedQueryTagSetsAndFiltersByStableIdentity() {
        val tag = merge(ManifestObjectKind.TAG,
            json("""{"parentTagIds":["base-a","base-b"]}"""),
            json("""{"parentTagIds":["base-b","added-local"]}"""),
            json("""{"parentTagIds":["base-a","base-b","added-remote"]}"""))
        assertTrue(tag.fields.isEmpty())
        assertEquals(json("""{"parentTagIds":["added-local","added-remote","base-b"]}"""), tag.payload)

        val savedQuery = merge(ManifestObjectKind.SAVED_QUERY,
            json("""{"tagIds":["base"],"propertyFilters":[{"propertyId":"p","canonicalValue":"1"}]}"""),
            json("""{"tagIds":["base","left"],"propertyFilters":[{"propertyId":"p","canonicalValue":"2"}]}"""),
            json("""{"tagIds":["base","right"],"propertyFilters":[{"propertyId":"p","canonicalValue":"1"},{"propertyId":"q","canonicalValue":"true"}]}"""))
        assertTrue(savedQuery.fields.isEmpty())
        assertEquals(JsonPrimitive("2"), ReplicaJsonFieldMerge.read(savedQuery.payload, listOf(
            ReplicaFieldPathSegment(field = "propertyFilters"),
            ReplicaFieldPathSegment(identityField = "propertyId", identityValue = "p"),
            ReplicaFieldPathSegment(field = "canonicalValue"),
        )))
        assertEquals(json("""["base","left","right"]"""), ReplicaJsonFieldMerge.read(savedQuery.payload, path("tagIds")))
    }

    /** 模板中不同 Block 的编辑自动组合，同一 Block 的结构竞争保留为整 Block 冲突。 */
    @Test
    fun treatsTemplateBlocksAsIndependentAtomicMembers() {
        val base = json("""{"templateBlocks":[{"id":"a","type":"paragraph","content":{"text":"A"}},{"id":"b","type":"paragraph","content":{"text":"B"}}]}""")
        val local = json("""{"templateBlocks":[{"id":"a","type":"heading","content":{"text":"A"}},{"id":"b","type":"paragraph","content":{"text":"Local B"}}]}""")
        val remote = json("""{"templateBlocks":[{"id":"a","type":"paragraph","content":{"text":"Remote A"}},{"id":"b","type":"paragraph","content":{"text":"B"}}]}""")
        val merged = merge(ManifestObjectKind.TAG, base, local, remote)
        assertEquals(listOf(ReplicaFieldPathSegment(field = "templateBlocks"),
            ReplicaFieldPathSegment(identityField = "id", identityValue = "a")), merged.fields.single().path)
        assertEquals(JsonPrimitive("Local B"), ReplicaJsonFieldMerge.read(merged.payload, listOf(
            ReplicaFieldPathSegment(field = "templateBlocks"), ReplicaFieldPathSegment(identityField = "id", identityValue = "b"),
            ReplicaFieldPathSegment(field = "content"), ReplicaFieldPathSegment(field = "text"),
        )))
    }

    /** 无身份数组保持原子竞争，同时仍组合 View 的独立名称和展示设置。 */
    @Test
    fun keepsUnidentifiedArraysAtomicAndMergesOtherViewFields() {
        val base = json("""{"name":"View","query":{"sorts":["base"]},"presentation":{"kind":"table","future":false}}""")
        val local = json("""{"name":"Renamed","query":{"sorts":["local"]},"presentation":{"kind":"table","future":false}}""")
        val remote = json("""{"name":"View","query":{"sorts":["remote"]},"presentation":{"kind":"gallery","future":true}}""")
        val merged = merge(ManifestObjectKind.VIEW, base, local, remote)
        assertEquals(path("query", "sorts"), merged.fields.single().path)
        assertEquals(json("""{"name":"Renamed","query":{"sorts":["remote"]},"presentation":{"kind":"gallery","future":true}}"""), merged.payload)
    }

    /** 显式空值与移除字段分别记录和恢复，不能把缺失序列化为 JSON null。 */
    @Test
    fun distinguishesMissingFieldFromExplicitNull() {
        val base = json("""{"description":null,"name":"Before"}""")
        val local = json("""{"name":"Renamed"}""")
        val remote = json("""{"description":"Remote","name":"Before"}""")
        val merged = merge(ManifestObjectKind.DATABASE, base, local, remote, MergeSide.LOCAL)
        val conflict = merged.fields.single()
        assertEquals(JsonNull, conflict.baseValue)
        assertNull(conflict.localValue)
        assertNull(ReplicaJsonFieldMerge.read(merged.payload, path("description")))
        val explicitNull = ReplicaJsonFieldMerge.replace(merged.payload, path("description"), JsonNull)
        assertEquals(JsonNull, ReplicaJsonFieldMerge.read(explicitNull, path("description")))
        assertFalse(explicitNull == merged.payload)
        assertEquals(merged.payload, ReplicaJsonFieldMerge.replace(explicitNull, path("description"), null))
        val serialized = CanonicalJson.encodeToString(WorkspaceReplicaFieldConflict.serializer(), conflict)
        val decoded = CanonicalJson.decodeFromString(WorkspaceReplicaFieldConflict.serializer(), serialized)
        assertEquals(JsonNull, decoded.baseValue)
        assertNull(decoded.localValue)
    }

    /** 读取和恢复依赖稳定选择器，重排及之后的无关编辑不改变目标。 */
    @Test
    fun restoresFieldOnCurrentPayloadAfterReorderingAndUnrelatedEdits() {
        val current = json("""{"name":"Later name","options":[{"id":"b","label":"Later B"},{"id":"a","label":"Current A","future":"kept"}]}""")
        val restored = ReplicaJsonFieldMerge.replace(current, optionPath("a", "label"), JsonPrimitive("Chosen A"))
        assertEquals(json("""{"name":"Later name","options":[{"id":"b","label":"Later B"},{"id":"a","label":"Chosen A","future":"kept"}]}"""), restored)
        assertEquals(current, ReplicaJsonFieldMerge.read(current, emptyList()))
        assertEquals(restored, ReplicaJsonFieldMerge.replace(current, emptyList(), restored))
    }

    /** 重复选择器、身份替换和删除根节点均拒绝，已删除成员的子字段也不能凭空复建。 */
    @Test
    fun rejectsAmbiguousSelectorsIdentityMutationAndMissingParents() {
        val duplicate = json("""{"options":[{"id":"a","label":"First"},{"id":"a","label":"Second"}]}""")
        assertFailsWith<IllegalArgumentException> { ReplicaJsonFieldMerge.read(duplicate, optionPath("a", "label")) }
        assertFailsWith<IllegalArgumentException> { ReplicaJsonFieldMerge.replace(duplicate, optionPath("a"), null) }
        val current = json("""{"options":[{"id":"a","label":"A"}]}""")
        assertFailsWith<IllegalArgumentException> {
            ReplicaJsonFieldMerge.replace(current, optionPath("a"), json("""{"id":"other","label":"A"}"""))
        }
        assertFailsWith<IllegalArgumentException> { ReplicaJsonFieldMerge.replace(current, emptyList(), null) }
        assertFailsWith<IllegalStateException> {
            ReplicaJsonFieldMerge.replace(current, optionPath("missing", "label"), JsonPrimitive("Chosen"))
        }
        assertEquals(current, ReplicaJsonFieldMerge.replace(current, optionPath("missing"), null))
        assertFailsWith<IllegalArgumentException> {
            merge(ManifestObjectKind.PROPERTY, current, duplicate, json("""{"options":[{"id":"a","label":"Remote"}]}"""))
        }
    }

    /** 路径深度受限，避免异常冲突证据在读取或恢复时造成无界递归。 */
    @Test
    fun rejectsPathsBeyondSupportedDepth() {
        val path = List(ReplicaJsonFieldMerge.MAX_PATH_DEPTH + 1) { ReplicaFieldPathSegment(field = "nested") }
        assertFailsWith<IllegalArgumentException> { ReplicaJsonFieldMerge.read(json("{}"), path) }
        assertFailsWith<IllegalArgumentException> { ReplicaJsonFieldMerge.replace(json("{}"), path, JsonPrimitive("value")) }
    }

    /** 对调设备方向同时对调稳定胜者后，候选与冲突路径仍完全一致。 */
    @Test
    fun producesSymmetricPayloadAndStableConflictIdentity() {
        val base = json("""{"name":"Before","defaultStyle":{"visible":true,"order":0},"options":[]}""")
        val local = json("""{"name":"Local","defaultStyle":{"visible":false,"order":0},"options":[{"id":"left","label":"Left"}]}""")
        val remote = json("""{"name":"Remote","defaultStyle":{"visible":true,"order":2},"options":[{"id":"right","label":"Right"}]}""")
        val forward = merge(ManifestObjectKind.PROPERTY, base, local, remote)
        val reversed = merge(ManifestObjectKind.PROPERTY, base, remote, local, MergeSide.LOCAL)
        assertEquals(forward.payload, reversed.payload)
        assertEquals(forward.fields.map { it.fieldId }, reversed.fields.map { it.fieldId })
        assertEquals(forward.fields.single().localValue, reversed.fields.single().remoteValue)
        assertEquals(forward.fields.single().remoteValue, reversed.fields.single().localValue)
    }

    /**
     * 从脱敏协议样例读取原始 JSON，不经过会丢弃未知键的领域解码。
     * @param text 完整测试 JSON 文本。
     * @return 可交给真实字段合并器的 JSON。
     */
    private fun json(text: String): JsonElement = CanonicalJson.format.parseToJsonElement(text)

    /**
     * 调用完整字段合并入口。
     * @param kind 当前定义类别。
     * @param base 共同祖先。
     * @param local 本地候选。
     * @param remote 远端候选。
     * @param winner 确定性重叠字段胜者。
     * @return 合并后的完整业务候选及字段证据。
     */
    private fun merge(kind: ManifestObjectKind, base: JsonElement, local: JsonElement, remote: JsonElement,
        winner: MergeSide = MergeSide.REMOTE): ReplicaJsonFieldMergeResult =
        ReplicaJsonFieldMerge.merge(kind, base, local, remote, winner)

    /**
     * 创建只含普通对象字段的测试路径。
     * @param fields 从根节点开始的字段名。
     * @return 对应的协议路径段。
     */
    private fun path(vararg fields: String): List<ReplicaFieldPathSegment> = fields.map { ReplicaFieldPathSegment(field = it) }

    /**
     * 用稳定选项身份创建字段路径，避免测试依赖数组重排前的位置。
     * @param id 目标选项的稳定身份。
     * @param fields 成员内部可选字段路径，缺省时表示整个成员。
     * @return 以 options 和稳定成员选择器开始的完整路径。
     */
    private fun optionPath(id: String, vararg fields: String): List<ReplicaFieldPathSegment> =
        path("options") + ReplicaFieldPathSegment(identityField = "id", identityValue = id) + path(*fields)
}
