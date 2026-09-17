package com.oruke.tagloom.editor

import com.oruke.tagloom.core.model.DefaultValuePolicy
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.PropertyId
import com.oruke.tagloom.core.model.PropertyStyle
import com.oruke.tagloom.core.model.PropertyTypes
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.core.model.TagId
import com.oruke.tagloom.core.model.TagPropertyBinding
import com.oruke.tagloom.core.workspace.WorkspaceDefaultSchemas
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 验证维护草稿对领域继承结果的映射，不把显示、损坏引用或未完成输入误写为本级绑定。 */
class TagBindingInheritanceTest {
    /** 根属性、菱形祖先和不可继承标记必须与真正应用子标签时的结果一致。 */
    @Test
    fun displaysImplicitRootAndDiamondAncestorsWithoutCopyingBindings() {
        val property = PropertyDefinition(PropertyId("property-shared"), "Shared", PropertyTypes.TEXT)
        val privateProperty = property.copy(id = PropertyId("property-private"), name = "Private")
        val root = WorkspaceDefaultSchemas.noteTagDefinition()
        val ancestor = TagDefinition(TagId("ancestor"), "Ancestor", propertyBindings = listOf(
            TagPropertyBinding(property.id, defaultValue = JsonPrimitive("Parent"), defaultPolicy = DefaultValuePolicy.DYNAMIC),
            TagPropertyBinding(privateProperty.id, inheritable = false),
        ))
        val left = TagDefinition(TagId("left"), "Left", parentTagIds = listOf(ancestor.id))
        val right = TagDefinition(TagId("right"), "Right", parentTagIds = listOf(ancestor.id))
        val child = TagDefinition(TagId("child"), "Child", parentTagIds = listOf(left.id, right.id))
        val tags = listOf(root, ancestor, left, right, child)
        val definitions = WorkspaceDefaultSchemas.propertyDefinitions() + listOf(property, privateProperty)

        val result = resolveTagBindingEditorProperties(child, child.parentTagIds, emptyList(), tags, definitions)

        assertTrue(result.canSave)
        assertEquals(root.propertyBindings.map { it.propertyId }.toSet() + property.id, result.rows.map { it.draft.propertyId }.toSet())
        val inherited = result.rows.single { it.draft.propertyId == property.id }
        assertEquals(listOf(ancestor.id), inherited.sources.map { it.id })
        assertEquals("Parent", inherited.draft.defaultValueText)
        assertTrue(result.rows.none { it.declaredLocally || it.conflicted })
        assertTrue(child.propertyBindings.isEmpty())

        val withoutParents = resolveTagBindingEditorProperties(child, emptyList(), emptyList(), tags, definitions)
        assertEquals(root.propertyBindings.map { it.propertyId }.toSet(), withoutParents.rows.map { it.draft.propertyId }.toSet())
    }

    /** 本级草稿可保持未完成输入、样式和顺序；清除后重新跟随最新父级，移除父级也不丢草稿。 */
    @Test
    fun preservesLocalDraftAndRestoresLatestParentBinding() {
        val property = PropertyDefinition(PropertyId("property-amount"), "Amount", PropertyTypes.NUMBER)
        val parentBinding = TagPropertyBinding(
            property.id, defaultValue = JsonPrimitive(12), defaultPolicy = DefaultValuePolicy.DYNAMIC,
            order = 7, styleOverride = PropertyStyle(showInCard = false),
        )
        val root = WorkspaceDefaultSchemas.noteTagDefinition()
        val parent = TagDefinition(TagId("parent"), "Parent", propertyBindings = listOf(parentBinding))
        val child = TagDefinition(TagId("child"), "Child", parentTagIds = listOf(parent.id))
        val definitions = WorkspaceDefaultSchemas.propertyDefinitions() + property
        val tags = listOf(root, parent, child)
        val draft = parentBinding.toEditorDraft(property).copy(defaultValueText = "-")

        val editing = resolveTagBindingEditorProperties(child, child.parentTagIds, listOf(draft), tags, definitions)
        assertEquals(draft, editing.rows.single { it.draft.propertyId == property.id }.draft)
        assertTrue(editing.rows.single { it.draft.propertyId == property.id }.declaredLocally)
        val detached = resolveTagBindingEditorProperties(child, emptyList(), listOf(draft), tags, definitions)
        assertEquals(draft, detached.rows.single { it.draft.propertyId == property.id }.draft)
        assertTrue(detached.rows.single { it.draft.propertyId == property.id }.sources.isEmpty())

        val changedParent = parent.copy(propertyBindings = listOf(parentBinding.copy(defaultValue = JsonPrimitive(20))))
        val restored = resolveTagBindingEditorProperties(child, child.parentTagIds, emptyList(), listOf(root, changedParent, child), definitions)
            .rows.single { it.draft.propertyId == property.id }
        assertEquals("20.0", restored.draft.defaultValueText)
        assertFalse(restored.declaredLocally)
        assertEquals(parentBinding.styleOverride, restored.draft.styleOverride)
        assertEquals(parentBinding.order, restored.draft.order)
    }

    /** 不一致的同级父级必须显式确认；选择无默认值也能覆盖，恢复后冲突重新出现。 */
    @Test
    fun requiresExplicitLocalOverrideForConflictingParents() {
        val property = PropertyDefinition(PropertyId("property-stage"), "Stage", PropertyTypes.TEXT)
        val root = WorkspaceDefaultSchemas.noteTagDefinition()
        val first = TagDefinition(TagId("first"), "First", propertyBindings = listOf(
            TagPropertyBinding(property.id, defaultValue = JsonPrimitive("First"), defaultPolicy = DefaultValuePolicy.SNAPSHOT),
        ))
        val second = TagDefinition(TagId("second"), "Second", propertyBindings = listOf(
            first.propertyBindings.single().copy(defaultValue = JsonPrimitive("Second")),
        ))
        val child = TagDefinition(TagId("child"), "Child", parentTagIds = listOf(second.id, first.id))
        val tags = listOf(root, first, second, child)
        val definitions = WorkspaceDefaultSchemas.propertyDefinitions() + property

        val conflicted = resolveTagBindingEditorProperties(child, child.parentTagIds, emptyList(), tags, definitions)
        assertFalse(conflicted.canSave)
        assertEquals(listOf(first.id, second.id), conflicted.rows.single { it.conflicted }.sources.map { it.id })

        val override = second.propertyBindings.single().toEditorDraft(property).copy(defaultPolicy = DefaultValuePolicy.NONE)
        val resolved = resolveTagBindingEditorProperties(child, child.parentTagIds, listOf(override), tags, definitions)
        assertTrue(resolved.canSave)
        assertTrue(resolved.rows.none { it.conflicted })
        assertEquals(null, override.toConfirmedBinding(property, override.order).defaultValue)
        assertFalse(resolveTagBindingEditorProperties(child, child.parentTagIds, emptyList(), tags, definitions).canSave)
    }

    /** 继承图损坏时本级草稿仍存在，缺失的继承定义也不能静默跳过或允许保存。 */
    @Test
    fun preservesBrokenReferencesAndReportsInvalidInheritance() {
        val root = WorkspaceDefaultSchemas.noteTagDefinition()
        val broken = TagPropertyBinding(PropertyId("missing-property"), defaultValue = JsonPrimitive("Preserved"))
        val child = TagDefinition(TagId("child"), "Child", propertyBindings = listOf(broken))
        val drafts = listOf(broken.toEditorDraft(null))
        val definitions = WorkspaceDefaultSchemas.propertyDefinitions()
        val cycle = resolveTagBindingEditorProperties(child, listOf(child.id), drafts, listOf(root, child), definitions)
        assertFalse(cycle.canSave)
        assertTrue(cycle.inheritanceErrors.isNotEmpty())
        assertEquals(drafts, cycle.rows.map { it.draft })

        val missingParent = resolveTagBindingEditorProperties(child, listOf(TagId("missing-parent")), drafts, listOf(root, child), definitions)
        assertFalse(missingParent.canSave)
        assertEquals(drafts, missingParent.rows.map { it.draft })

        val parent = TagDefinition(TagId("parent"), "Parent", propertyBindings = listOf(broken))
        val inherited = resolveTagBindingEditorProperties(child, listOf(parent.id), emptyList(), listOf(root, parent, child), definitions)
        assertFalse(inherited.canSave)
        assertEquals(broken.defaultValue.toString(), inherited.rows.single { it.definition == null }.draft.defaultValueText)
    }
}
