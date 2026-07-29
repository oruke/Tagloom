package com.oruke.tagloom.editor

import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.PropertyId
import com.oruke.tagloom.core.model.PropertyOptionInputMode
import com.oruke.tagloom.core.model.PropertyTypes
import com.oruke.tagloom.core.workspace.WorkspaceDefaultSchemas
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 验证自由单选和多选会持久化稳定选项目录，并复用同名选项。
 */
class FreePropertyOptionTest {
    /**
     * 验证首次输入创建稳定选项，应用后写入 Note，再次输入会复用而不重复创建。
     */
    @Test
    fun createsSelectsAndReusesFreeOption() {
        val definition = PropertyDefinition(
            id = PropertyId("property-topics"),
            name = "主题",
            type = PropertyTypes.MULTI_SELECT,
            optionInputMode = PropertyOptionInputMode.FREE,
        )
        val state = stateWith(definition)

        val created = state.planFreePropertyOption(definition.id, " Kotlin ")
        assertTrue(created.requiresPersistence)
        state.applyFreePropertyOption(created.definition, created.optionId)

        assertEquals(listOf(created.optionId), state.propertyEditor.stringListValue(state.selectedDocument!!.note, definition.id))
        assertEquals("Kotlin", state.propertyDefinitions.single { current -> current.id == definition.id }.options.single().label)

        val reused = state.planFreePropertyOption(definition.id, "kOtLiN")
        assertFalse(reused.requiresPersistence)
        assertEquals(created.optionId, reused.optionId)
        assertEquals(1, reused.definition.options.size)
    }

    /**
     * 验证固定选项属性拒绝从值编辑器扩充 schema。
     */
    @Test
    fun fixedPropertyRejectsFreeOptionCreation() {
        val definition = PropertyDefinition(
            id = PropertyId("property-status"),
            name = "状态",
            type = PropertyTypes.SELECT,
        )
        val state = stateWith(definition)

        assertFailsWith<IllegalArgumentException> {
            state.planFreePropertyOption(definition.id, "新增")
        }
    }

    /**
     * 验证旧工作区尚无普通标签定义时仍能规划内置定义，供平台先行持久化。
     */
    @Test
    fun plansBuiltInTagSchemaForLegacyWorkspace() {
        val state = stateWith()

        val plan = state.planFreePropertyOption(WorkspaceDefaults.DEFAULT_TAGS_PROPERTY_ID, "设计")

        assertTrue(plan.requiresPersistence)
        assertEquals(WorkspaceDefaults.DEFAULT_TAGS_PROPERTY_ID, plan.definition.id)
        assertEquals("设计", plan.definition.options.single().label)
    }

    /**
     * 构造包含一篇已确认 Note 和可选属性定义的确定性编辑器状态。
     *
     * @param definitions 当前测试需要加载的属性定义。
     * @return 已选择测试 Note 的编辑器状态。
     */
    private fun stateWith(vararg definitions: PropertyDefinition): TagloomEditorState {
        var idCounter = 0
        val state = TagloomEditorState(
            idFactory = { prefix -> "$prefix-${idCounter++}" },
            timestampProvider = { "2026-07-15T08:00:00Z" },
        )
        state.loadWorkspaceDocuments(
            loadedDocuments = listOf(
                EditorDocument(
                    id = ObjectId("note-test"),
                    note = Note(
                        title = "测试",
                        directTagIds = listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID),
                    ),
                    createdAt = "2026-07-15T07:00:00Z",
                    updatedAt = "2026-07-15T07:00:00Z",
                ),
            ),
            loadedTagDefinitions = listOf(WorkspaceDefaultSchemas.noteTagDefinition()),
            loadedPropertyDefinitions = definitions.toList(),
        )
        return state
    }
}
