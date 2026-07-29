package com.oruke.tagloom.editor

import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.PropertyId
import com.oruke.tagloom.core.model.PropertyOption
import com.oruke.tagloom.core.model.PropertyOptionInputMode
import com.oruke.tagloom.core.model.PropertyTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * 验证属性定义编辑会保留既有选项身份并拒绝危险 schema 变更。
 */
class PropertySchemaEditingTest {
    /**
     * 验证重命名保留既有选项 ID，而新增选项获得新的稳定 ID。
     */
    @Test
    fun preservesExistingOptionIdsAndAllocatesNewOnes() {
        var counter = 0
        val state = TagloomEditorState(
            idFactory = { prefix -> "$prefix-${counter++}" },
            timestampProvider = { "2026-07-14T00:00:00Z" },
        )
        val existing = sampleDefinition()

        val updated = state.buildPropertyDefinitionDraft(
            existing = existing,
            name = " 进度 ",
            type = PropertyTypes.SELECT,
            optionDrafts = listOf(
                PropertyOptionDraft(id = "option-open", label = "待处理", colorToken = "blue"),
                PropertyOptionDraft(id = null, label = "稍后"),
            ),
        )

        assertEquals(existing.id, updated.id)
        assertEquals("进度", updated.name)
        assertEquals("option-open", updated.options[0].id)
        assertEquals("blue", updated.options[0].colorToken)
        assertNotEquals("option-open", updated.options[1].id)
    }

    /**
     * 验证已有属性不能在没有迁移策略时改变类型。
     */
    @Test
    fun rejectsExistingPropertyTypeChanges() {
        val state = newState()

        assertFailsWith<IllegalArgumentException> {
            state.buildPropertyDefinitionDraft(
                existing = sampleDefinition(),
                name = "进度",
                type = PropertyTypes.TEXT,
                optionDrafts = emptyList(),
            )
        }
    }

    /**
     * 验证选项名称按不区分大小写拒绝重复。
     */
    @Test
    fun rejectsDuplicateOptionLabels() {
        val state = newState()

        assertFailsWith<IllegalArgumentException> {
            state.buildPropertyDefinitionDraft(
                existing = sampleDefinition(),
                name = "进度",
                type = PropertyTypes.SELECT,
                optionDrafts = listOf(
                    PropertyOptionDraft(id = "option-open", label = "Open"),
                    PropertyOptionDraft(id = null, label = "open"),
                ),
            )
        }
    }

    /**
     * 验证自由输入属性允许从空选项目录开始，后续选项由值编辑器持久化补充。
     */
    @Test
    fun freeInputPropertyAllowsEmptyInitialCatalog() {
        val state = newState()

        val definition = state.buildPropertyDefinitionDraft(
            existing = null,
            name = "主题",
            type = PropertyTypes.MULTI_SELECT,
            optionInputMode = PropertyOptionInputMode.FREE,
            optionDrafts = emptyList(),
        )

        assertEquals(PropertyOptionInputMode.FREE, definition.optionInputMode)
        assertEquals(emptyList(), definition.options)
    }

    /**
     * 构造测试使用的单选属性定义。
     *
     * @return 带一个既有选项的属性定义。
     */
    private fun sampleDefinition(): PropertyDefinition = PropertyDefinition(
        id = PropertyId("property-status"),
        name = "状态",
        type = PropertyTypes.SELECT,
        options = listOf(PropertyOption("option-open", "进行中", "blue")),
    )

    /**
     * 构造不依赖平台时间或随机源的测试编辑器状态。
     *
     * @return 可执行 schema 校验的确定性编辑器状态。
     */
    private fun newState(): TagloomEditorState = TagloomEditorState(
        idFactory = { prefix -> "$prefix-test" },
        timestampProvider = { "2026-07-14T00:00:00Z" },
    )
}
