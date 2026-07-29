package com.oruke.tagloom.core.model

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 验证单一应用对象类型不会破坏旧快照兼容和笔记属性数据。
 */
class NoteObjectTypeTest {
    /**
     * 验证旧快照中的多个非根直接类型会进入待确认状态而不被静默裁剪。
     */
    @Test
    fun preservesLegacyCandidatesUntilExplicitSelection() {
        val rootId = TagId("type-note")
        val projectId = TagId("type-project")
        val pageId = TagId("type-page")
        val note = Note(
            title = "旧笔记",
            directTagIds = listOf(rootId, projectId, pageId),
        )

        val state = note.objectTypeState(rootId)

        assertNull(state.appliedTypeId)
        assertEquals(listOf(projectId, pageId), state.candidateTypeIds)
        assertTrue(state.requiresResolution)
        assertEquals(listOf(rootId, projectId, pageId), note.directTagIds)
    }

    /**
     * 验证显式切换类型只规范化类型关联，不删除旧类型产生的属性值。
     */
    @Test
    fun switchesTypeWithoutDeletingPropertyValues() {
        val rootId = TagId("type-note")
        val oldTypeId = TagId("type-project")
        val nextTypeId = TagId("type-page")
        val propertyId = PropertyId("property-status")
        val storedValue = NotePropertyValue(
            value = JsonPrimitive("active"),
            source = PropertyValueSource.TAG_SNAPSHOT,
            sourceTagId = oldTypeId,
            updatedAt = "2026-07-15T00:00:00Z",
        )
        val note = Note(
            title = "切换类型",
            directTagIds = listOf(rootId, oldTypeId),
            propertyValues = mapOf(propertyId to storedValue),
        )

        val switched = note.withAppliedObjectType(rootId, nextTypeId)

        assertEquals(listOf(rootId, nextTypeId), switched.directTagIds)
        assertEquals(storedValue, switched.propertyValues[propertyId])
        assertEquals(nextTypeId, switched.objectTypeState(rootId).appliedTypeId)
        assertFalse(switched.objectTypeState(rootId).requiresResolution)
    }
}
