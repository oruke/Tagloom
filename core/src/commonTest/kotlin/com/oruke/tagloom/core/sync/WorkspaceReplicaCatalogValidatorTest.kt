package com.oruke.tagloom.core.sync

import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.workspace.ManifestObjectKind
import com.oruke.tagloom.core.workspace.WorkspaceDefaultSchemas
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 从完整跨对象目录验证同步确认前的引用闭包，兼容历史值和已删除引用目标。 */
class WorkspaceReplicaCatalogValidatorTest {
    /** 测试快照统一使用的 UTC 时间，不依赖主机地区或系统时钟。 */
    private val timestamp = "2026-01-01T00:00:00Z"

    /** 一个批次内的类型、属性、Note、Database 和默认 View 可按任意顺序预验证。 */
    @Test
    fun validatesCompleteCatalogRegardlessOfInputOrder() {
        val catalog = completeCatalog()
        WorkspaceReplicaCatalogValidator.validate(catalog)
        WorkspaceReplicaCatalogValidator.validate(catalog.reversed())
    }

    /** 活动数据集缺失默认 View、来源类型或所属关系时，整个候选目录拒绝确认。 */
    @Test
    fun rejectsIncompleteDatabaseAndViewRelationships() {
        val catalog = completeCatalog()
        assertFailsWith<IllegalArgumentException> {
            WorkspaceReplicaCatalogValidator.validate(catalog.filterNot { it.kind == ManifestObjectKind.VIEW })
        }
        assertFailsWith<IllegalArgumentException> {
            WorkspaceReplicaCatalogValidator.validate(catalog.filterNot { it.kind == ManifestObjectKind.DATABASE })
        }
        assertFailsWith<IllegalArgumentException> {
            WorkspaceReplicaCatalogValidator.validate(catalog.filterNot { it.snapshot.header.objectId == ObjectId("typed-note") })
        }
        assertEquals(catalog, completeCatalog())
    }

    /** 两侧各自合法的类型变更合并成环或失去父级时，预验证阻止发布损坏目录。 */
    @Test
    fun rejectsMissingParentAndCycleInMergedTypeCatalog() {
        val root = baseCatalog()
        val first = TagDefinition(TagId("first-type"), "First", parentTagIds = listOf(TagId("second-type")))
        val second = TagDefinition(TagId("second-type"), "Second", parentTagIds = listOf(first.id))
        val firstEntry = entry(ManifestObjectKind.TAG, first.id.value, TagDefinition.serializer(), first)
        assertFailsWith<IllegalArgumentException> { WorkspaceReplicaCatalogValidator.validate(root + firstEntry) }
        assertFailsWith<IllegalArgumentException> {
            WorkspaceReplicaCatalogValidator.validate(root + firstEntry +
                entry(ManifestObjectKind.TAG, second.id.value, TagDefinition.serializer(), second))
        }
    }

    /** 模板复用完整 Block 树约束，允许空模板和无默认页签的 Tabs，不接受环或悬空父边。 */
    @Test
    fun validatesCompleteTemplateTreeWithoutRequiringNoteDefaultTab() {
        val root = baseCatalog()
        val first = Block(BlockId("template-first"), BlockTypes.PARAGRAPH, orderKey = "a",
            content = RichTextBlockContent(RichText()))
        val second = first.copy(id = BlockId("template-second"), orderKey = "b")
        val type = TagDefinition(TagId("template-type"), "Template")
        WorkspaceReplicaCatalogValidator.validate(root + entry(ManifestObjectKind.TAG, type.id.value, TagDefinition.serializer(), type))
        val tabs = Block(BlockId("template-tabs"), BlockTypes.TABS, orderKey = "a", content = TabsBlockContent)
        val tab = Block(BlockId("template-tab"), BlockTypes.TAB, parentId = tabs.id, orderKey = "a",
            content = TabBlockContent(RichText()))
        WorkspaceReplicaCatalogValidator.validate(root + entry(ManifestObjectKind.TAG, type.id.value, TagDefinition.serializer(),
            type.copy(templateBlocks = listOf(tabs, tab))))
        val invalidTrees = listOf(
            listOf(first.copy(parentId = second.id)),
            listOf(first.copy(parentId = second.id), second.copy(parentId = first.id)),
            listOf(first, second.copy(orderKey = first.orderKey)),
        )
        invalidTrees.forEach { blocks ->
            assertFailsWith<IllegalArgumentException> {
                WorkspaceReplicaCatalogValidator.validate(root + entry(ManifestObjectKind.TAG, type.id.value,
                    TagDefinition.serializer(), type.copy(templateBlocks = blocks)))
            }
        }
    }

    /** 类型绑定和手动属性绑定都必须在同一候选目录找到共享定义。 */
    @Test
    fun rejectsMissingSharedPropertyBindings() {
        val catalog = completeCatalog()
        assertFailsWith<IllegalArgumentException> {
            WorkspaceReplicaCatalogValidator.validate(catalog.filterNot {
                it.snapshot.header.objectId.value == WorkspaceDefaults.DEFAULT_TEXT_PROPERTY_ID.value
            })
        }
        val note = Note("Manual binding", listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID),
            manualPropertyBindings = listOf(ManualPropertyBinding(PropertyId("missing-property"))))
        assertFailsWith<IllegalArgumentException> {
            WorkspaceReplicaCatalogValidator.validate(baseCatalog() + entry(ManifestObjectKind.NOTE, "manual-note", Note.serializer(), note))
        }
    }

    /** 删除目标引用和历史未填必填值保持合法，未知属性值不会被同步擅自重解释。 */
    @Test
    fun preservesDeletedReferencesAndHistoricalPropertyValues() {
        val root = baseCatalog()
        val type = TagDefinition(TagId("legacy-type"), "Legacy", propertyBindings = listOf(
            TagPropertyBinding(WorkspaceDefaults.DEFAULT_TEXT_PROPERTY_ID, required = true),
        ))
        val target = entry(ManifestObjectKind.NOTE, "deleted-target", Note.serializer(),
            Note("Deleted target", listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID)), deleted = true)
        val note = Note("Preserved note", listOf(type.id),
            propertyValues = mapOf(PropertyId("legacy-unknown-property") to NotePropertyValue(
                JsonObject(mapOf("future-format" to JsonPrimitive("retained"))), PropertyValueSource.USER, updatedAt = timestamp)),
            blocks = listOf(Block(BlockId("reference"), BlockTypes.NOTE_REFERENCE, orderKey = "a",
                content = NoteReferenceBlockContent(ObjectId("deleted-target")))))
        WorkspaceReplicaCatalogValidator.validate(root + target +
            entry(ManifestObjectKind.TAG, type.id.value, TagDefinition.serializer(), type) +
            entry(ManifestObjectKind.NOTE, "legacy-note", Note.serializer(), note))
    }

    /** 已删除对象保留历史引用，未来视图和查询版本只要求只读兼容而非拒绝整个确认点。 */
    @Test
    fun preservesTombstonePayloadAndForwardCompatibleView() {
        val root = baseCatalog()
        val retiredType = TagDefinition(TagId("retired-type"), "Retired", parentTagIds = listOf(TagId("old-parent")))
        val removed = entry(ManifestObjectKind.TAG, retiredType.id.value, TagDefinition.serializer(), retiredType, deleted = true)
        val note = entry(ManifestObjectKind.NOTE, "removed-note", Note.serializer(), Note("Removed", listOf(retiredType.id)), deleted = true)
        WorkspaceReplicaCatalogValidator.validate(root + removed + note)

        val catalog = completeCatalog().map { item ->
            if (item.kind != ManifestObjectKind.VIEW) item else {
                val view = CanonicalJson.format.decodeFromJsonElement(ViewDefinition.serializer(), item.snapshot.payload)
                item.copy(snapshot = item.snapshot.copy(payload = CanonicalJson.format.encodeToJsonElement(ViewDefinition.serializer(),
                    view.copy(query = QuerySpec(schemaVersion = 2, filter = FilterExpr(kind = "future-filter")),
                        presentation = ViewPresentation(kind = "future-view")))))
            }
        }
        WorkspaceReplicaCatalogValidator.validate(catalog)
    }

    /** 认证后仍必须核对 payload 身份与头部，避免合法密文被错误路由到另一目录身份。 */
    @Test
    fun rejectsPayloadIdentityAndHeaderTypeMismatch() {
        val catalog = completeCatalog()
        val tag = catalog.first { it.snapshot.header.objectId == ObjectId("typed-note") }
        assertFailsWith<IllegalArgumentException> {
            WorkspaceReplicaCatalogValidator.validate(catalog.filterNot { it == tag } +
                tag.copy(snapshot = tag.snapshot.copy(header = tag.snapshot.header.copy(objectId = ObjectId("wrong-type-id")))))
        }
        assertFailsWith<IllegalArgumentException> {
            WorkspaceReplicaCatalogValidator.validate(catalog.filterNot { it == tag } +
                tag.copy(snapshot = tag.snapshot.copy(header = tag.snapshot.header.copy(objectType = ObjectType.NOTE))))
        }
    }

    /**
     * 构造完整根类型与共享基础属性，不依赖任何平台装配。
     * @return 真实领域默认定义构成的完整最小目录。
     */
    private fun baseCatalog(): List<WorkspaceReplicaObject> = WorkspaceDefaultSchemas.propertyDefinitions().map { property ->
        entry(ManifestObjectKind.PROPERTY, property.id.value, PropertyDefinition.serializer(), property)
    } + WorkspaceDefaultSchemas.noteTagDefinition().let { tag -> entry(ManifestObjectKind.TAG, tag.id.value, TagDefinition.serializer(), tag) }

    /**
     * 构造具有跨对象关系的候选目录，故意让 Note 未填写类型的必填属性。
     * @return 类型、Note、Database 与默认 View 均完整的候选并集。
     */
    private fun completeCatalog(): List<WorkspaceReplicaObject> {
        val type = TagDefinition(TagId("typed-note"), "Typed note", propertyBindings = listOf(
            TagPropertyBinding(WorkspaceDefaults.DEFAULT_TEXT_PROPERTY_ID, required = true)))
        val database = DatabaseDefinition(DatabaseId("example-database"), "Dataset", type.id,
            creationTypeId = type.id, defaultViewId = ViewId("example-view"))
        val view = ViewDefinition(database.defaultViewId, database.id, "View", "a")
        val note = Note("Note", listOf(type.id))
        return baseCatalog() + listOf(
            entry(ManifestObjectKind.NOTE, "example-note", Note.serializer(), note),
            entry(ManifestObjectKind.VIEW, view.id.value, ViewDefinition.serializer(), view),
            entry(ManifestObjectKind.DATABASE, database.id.value, DatabaseDefinition.serializer(), database),
            entry(ManifestObjectKind.TAG, type.id.value, TagDefinition.serializer(), type),
        )
    }

    /**
     * 以真实 serializer 构造保存完整头部和 payload 的认证候选测试模型。
     * @param T 领域 payload 类型。
     * @param kind manifest 对象类别。
     * @param id 当前对象稳定身份。
     * @param serializer 领域 payload serializer。
     * @param payload 待编码的完整对象。
     * @param deleted 是否作为 tombstone 保留。
     * @return 可交给完整目录预验证的对象候选。
     */
    private fun <T> entry(
        kind: ManifestObjectKind,
        id: String,
        serializer: KSerializer<T>,
        payload: T,
        deleted: Boolean = false,
    ): WorkspaceReplicaObject {
        val type = when (kind) {
            ManifestObjectKind.NOTE -> ObjectType.NOTE
            ManifestObjectKind.TAG -> ObjectType.TAG
            ManifestObjectKind.PROPERTY -> ObjectType.PROPERTY
            ManifestObjectKind.DATABASE -> ObjectType.DATABASE
            ManifestObjectKind.VIEW -> ObjectType.VIEW
            ManifestObjectKind.SAVED_QUERY -> ObjectType.SAVED_QUERY
            else -> error("测试不包含非快照对象")
        }
        return WorkspaceReplicaObject(kind, ObjectSnapshot(SnapshotHeader(1, ObjectId(id), type,
            ObjectRevision(ActorId("example-device"), 1), createdAt = timestamp, updatedAt = timestamp, deleted = deleted),
            CanonicalJson.format.encodeToJsonElement(serializer, payload)))
    }
}
