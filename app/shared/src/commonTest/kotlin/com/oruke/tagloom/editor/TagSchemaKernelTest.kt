package com.oruke.tagloom.editor

import com.oruke.tagloom.core.model.DefaultValuePolicy
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.PropertyId
import com.oruke.tagloom.core.model.PropertyTypes
import com.oruke.tagloom.core.model.PropertyValueSource
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.core.model.TagId
import com.oruke.tagloom.core.model.TagPropertyBinding
import com.oruke.tagloom.core.workspace.WorkspaceDefaultSchemas
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 验证编辑器 schema 交互不会绕过标签继承、默认策略和稳定来源等产品内核。
 */
class TagSchemaKernelTest {
    /**
     * 验证关联超级标签时会把 snapshot 默认值及来源写入笔记快照。
     */
    @Test
    fun materializesSnapshotDefaultWhenTagIsAttached() {
        val property = PropertyDefinition(PropertyId("property-priority"), "优先级", PropertyTypes.TEXT)
        val tag = TagDefinition(
            id = TagId("tag-project"),
            name = "项目",
            propertyBindings = listOf(
                TagPropertyBinding(
                    propertyId = property.id,
                    defaultValue = JsonPrimitive("普通"),
                    defaultPolicy = DefaultValuePolicy.SNAPSHOT,
                ),
            ),
        )
        val state = stateWithSchema(property, tag)

        state.setTagAttached(tag.id, attached = true)

        val stored = state.selectedDocument!!.note.propertyValues.getValue(property.id)
        assertEquals(JsonPrimitive("普通"), stored.value)
        assertEquals(PropertyValueSource.TAG_SNAPSHOT, stored.source)
        assertEquals(tag.id, stored.sourceTagId)
        assertFalse(stored.overridden)
    }

    /**
     * 验证动态默认值只参与有效属性解析，不会伪装成笔记已保存值。
     */
    @Test
    fun keepsDynamicDefaultOutsideNoteSnapshot() {
        val property = PropertyDefinition(PropertyId("property-status"), "状态", PropertyTypes.TEXT)
        val tag = TagDefinition(
            id = TagId("tag-task"),
            name = "任务",
            propertyBindings = listOf(
                TagPropertyBinding(
                    propertyId = property.id,
                    defaultValue = JsonPrimitive("待处理"),
                    defaultPolicy = DefaultValuePolicy.DYNAMIC,
                ),
            ),
        )
        val state = stateWithSchema(property, tag)

        state.setTagAttached(tag.id, attached = true)

        assertFalse(property.id in state.selectedDocument!!.note.propertyValues)
        assertEquals(JsonPrimitive("待处理"), state.resolveEffectiveProperties(state.selectedDocument!!.note)!!
            .properties.single { effective -> effective.propertyId == property.id }.value)
    }

    /**
     * 验证选择新的超级标签会替换旧应用类型，同时保留根 Note 身份。
     */
    @Test
    fun replacesPreviousAppliedObjectType() {
        val project = TagDefinition(TagId("tag-project"), "项目")
        val page = TagDefinition(TagId("tag-page"), "页面")
        val state = stateWithSchema(tags = listOf(project, page))

        state.setAppliedObjectType(project.id)
        state.setAppliedObjectType(page.id)

        assertEquals(
            listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID, page.id),
            state.selectedDocument!!.note.directTagIds,
        )
    }

    /**
     * 验证旧多类型快照只有用户明确选择后才会收敛为唯一应用类型。
     */
    @Test
    fun resolvesLegacyMultipleTypesOnlyAfterExplicitSelection() {
        val project = TagDefinition(TagId("tag-project"), "项目")
        val page = TagDefinition(TagId("tag-page"), "页面")
        val state = stateWithSchema(
            tags = listOf(project, page),
            directTagIds = listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID, project.id, page.id),
        )

        assertEquals(3, state.selectedDocument!!.note.directTagIds.size)

        state.setAppliedObjectType(page.id)

        assertEquals(
            listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID, page.id),
            state.selectedDocument!!.note.directTagIds,
        )
    }

    /**
     * 验证标签定义编辑会拒绝直接或间接继承环。
     */
    @Test
    fun rejectsInheritanceCycleBeforePersistence() {
        val parent = TagDefinition(TagId("tag-parent"), "父标签")
        val child = TagDefinition(TagId("tag-child"), "子标签", parentTagIds = listOf(parent.id))
        val state = stateWithSchema(tags = listOf(parent, child))

        assertFailsWith<IllegalArgumentException> {
            state.buildTagDefinitionDraft(
                existing = parent,
                name = parent.name,
                parentTagIds = listOf(child.id),
                propertyBindings = emptyList(),
            )
        }
    }

    /**
     * 验证内置 Note 根类型只能查看，不能通过对象类型编辑器改名。
     */
    @Test
    fun rejectsEditingDefaultNoteRootType() {
        val state = stateWithSchema()
        val rootType = WorkspaceDefaultSchemas.noteTagDefinition()

        assertFailsWith<IllegalArgumentException> {
            state.buildTagDefinitionDraft(
                existing = rootType,
                name = "Page",
                parentTagIds = emptyList(),
                propertyBindings = rootType.propertyBindings,
            )
        }
    }

    /**
     * 验证把全局属性加入标签时会保留已有绑定并追加确定顺序。
     */
    @Test
    fun bindsWorkspacePropertyToSuperTag() {
        val existingProperty = PropertyDefinition(PropertyId("property-existing"), "已有", PropertyTypes.TEXT)
        val newProperty = PropertyDefinition(PropertyId("property-new"), "新增", PropertyTypes.DATE)
        val tag = TagDefinition(
            id = TagId("tag-project"),
            name = "项目",
            propertyBindings = listOf(TagPropertyBinding(existingProperty.id, order = 0)),
        )
        val state = stateWithSchema(properties = listOf(existingProperty, newProperty), tags = listOf(tag))

        val updated = state.bindPropertyToTag(tag.id, newProperty.id)

        assertEquals(listOf(existingProperty.id, newProperty.id), updated.propertyBindings.map { binding -> binding.propertyId })
        assertEquals(1, updated.propertyBindings.last().order)
        assertTrue(updated.propertyBindings.last().inheritable)
    }

    /**
     * 构造包含一个已确认空笔记和指定 schema 的确定性编辑器状态。
     *
     * @param properties 附加属性定义。
     * @param tags 附加标签定义。
     * @param directTagIds 测试笔记直接对象类型，用于覆盖旧快照兼容场景。
     * @return 已选中测试笔记的编辑器状态。
     */
    private fun stateWithSchema(
        properties: List<PropertyDefinition> = emptyList(),
        tags: List<TagDefinition> = emptyList(),
        directTagIds: List<TagId> = listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID),
    ): TagloomEditorState {
        val state = TagloomEditorState(
            idFactory = { prefix -> "$prefix-test" },
            timestampProvider = { "2026-07-14T00:00:00Z" },
        )
        state.loadWorkspaceDocuments(
            loadedDocuments = listOf(
                EditorDocument(
                    id = ObjectId("note-test"),
                    note = Note(title = "测试", directTagIds = directTagIds),
                    persisted = true,
                ),
            ),
            loadedTagDefinitions = listOf(WorkspaceDefaultSchemas.noteTagDefinition()) + tags,
            loadedPropertyDefinitions = WorkspaceDefaultSchemas.propertyDefinitions() + properties,
        )
        return state
    }

    /**
     * 构造只包含一个附加属性和标签的测试状态。
     *
     * @param property 附加属性。
     * @param tag 附加标签。
     * @return 已加载对应 schema 的编辑器状态。
     */
    private fun stateWithSchema(
        property: PropertyDefinition,
        tag: TagDefinition,
    ): TagloomEditorState = stateWithSchema(properties = listOf(property), tags = listOf(tag))
}
