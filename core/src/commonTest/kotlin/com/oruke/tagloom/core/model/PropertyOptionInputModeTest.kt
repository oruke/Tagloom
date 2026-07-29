package com.oruke.tagloom.core.model

import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.workspace.WorkspaceDefaultSchemas
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 验证 select 选项输入模式的快照兼容性与内置普通标签 schema。
 */
class PropertyOptionInputModeTest {
    /**
     * 验证旧快照缺少新字段时仍按固定选项解释，避免升级后改变既有属性语义。
     */
    @Test
    fun missingInputModeDefaultsToFixed() {
        val definition = CanonicalJson.format.decodeFromString(
            PropertyDefinition.serializer(),
            """{"id":"property-status","name":"状态","type":"select","options":[]}""",
        )

        assertEquals(PropertyOptionInputMode.FIXED, definition.optionInputMode)
    }

    /**
     * 验证内置普通标签使用自由多选，并绑定到只读 Note 根类型。
     */
    @Test
    fun ordinaryTagsUseFreeMultiSelectSchema() {
        val definition = WorkspaceDefaultSchemas.ordinaryTagsPropertyDefinition()
        val rootType = WorkspaceDefaultSchemas.noteTagDefinition()

        assertEquals(WorkspaceDefaults.DEFAULT_TAGS_PROPERTY_ID, definition.id)
        assertEquals(PropertyTypes.MULTI_SELECT, definition.type)
        assertEquals(PropertyOptionInputMode.FREE, definition.optionInputMode)
        assertTrue(rootType.propertyBindings.any { binding -> binding.propertyId == definition.id })
    }
}
