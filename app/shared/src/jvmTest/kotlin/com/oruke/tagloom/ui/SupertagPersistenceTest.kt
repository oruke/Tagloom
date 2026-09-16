package com.oruke.tagloom.ui

import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.editor.createDocumentWithSupertag
import com.oruke.tagloom.editor.findSupertags
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.*

/** 使用真实本机加密和事务验证图标兼容、引用保护、失败保留及 tombstone 重开结果。 */
class SupertagPersistenceTest {
    /** 验证旧快照缺省图标、编辑更换和清除、父级与笔记引用保护及最终删除持久化。 */
    @Test
    fun persistsIconsAndGuardsDeletionAcrossReopen() = runBlocking {
        WorkspaceSettingsTestEnvironment().use { environment ->
            val gateway = environment.gateway
            gateway.openRecentWorkspace()
            val old = Json.decodeFromString<TagDefinition>("""{"id":"tag-compat","name":"Legacy example"}""")
            assertNull(old.iconKey)
            gateway.saveTagDefinition(old)
            val parent = gateway.saveTagDefinition(old.copy(iconKey = "book")).definition
            val child = TagDefinition(TagId("tag-child"), "Child example", parentTagIds = listOf(parent.id))
            gateway.saveTagDefinition(child)
            assertFailsWith<IllegalArgumentException> { gateway.deleteTagDefinition(parent.id) }
            assertFailsWith<IllegalArgumentException> {
                gateway.saveTagDefinition(
                    parent.copy(
                        parentTagIds = listOf(
                            child.id
                        )
                    )
                )
            }
            gateway.deleteTagDefinition(child.id)
            val note = gateway.saveNote(
                null,
                Note(title = "Example note", directTagIds = listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID, parent.id))
            )
            assertFailsWith<IllegalArgumentException> { gateway.deleteTagDefinition(parent.id) }
            gateway.saveTagDefinition(parent.copy(name = "Revised example", iconKey = "folder"))
            gateway.lock()
            val reopened = gateway.openRecentWorkspace()
            val saved = reopened.tagDefinitions.single { it.definition.id == parent.id }.definition
            assertEquals("folder", saved.iconKey)
            assertEquals("Revised example", saved.name)
            assertTrue(reopened.tagDefinitions.none { it.definition.id == child.id })
            gateway.saveTagDefinition(saved.copy(iconKey = null))
            gateway.lock()
            assertNull(gateway.openRecentWorkspace().tagDefinitions.single { it.definition.id == parent.id }.definition.iconKey)
            gateway.deleteNote(note.id)
            gateway.deleteTagDefinition(parent.id)
            assertFailsWith<IllegalArgumentException> { gateway.deleteTagDefinition(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID) }
            gateway.lock()
            assertEquals(
                listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID),
                gateway.openRecentWorkspace().tagDefinitions.map { it.definition.id })
        }
    }

    /** 验证失败不更新内存确认定义、过滤结果稳定及笔记草稿也参与删除保护。 */
    @Test
    fun preservesDraftOnRealValidationFailure() = runBlocking {
        WorkspaceSettingsTestEnvironment().use { environment ->
            val opened = environment.gateway.openRecentWorkspace()
            var sequence = 0
            val editor = TagloomEditorState({ prefix -> "$prefix-${sequence++}" }, { "2026-01-01T00:00:00Z" })
            editor.tagDefinitions.addAll(opened.tagDefinitions.map { it.definition })
            editor.propertyDefinitions.addAll(opened.propertyDefinitions.map { it.definition })
            val controller = WorkspaceSupertagController(environment.gateway, editor)
            val tag = TagDefinition(TagId("tag-working"), "Project example", iconKey = "folder")
            assertTrue(controller.save(tag, emptyList(), false))
            val invalid = tag.copy(
                name = "Unconfirmed edit",
                propertyBindings = listOf(TagPropertyBinding(PropertyId("missing-property")))
            )
            assertFalse(controller.save(invalid, emptyList(), false))
            assertTrue(controller.failed)
            assertEquals(tag, editor.tagDefinitions.single { it.id == tag.id })
            assertEquals(listOf(tag), editor.findSupertags(" pRoJeCt "))
            assertEquals(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID, editor.findSupertags("").first().id)
            editor.createDocumentWithSupertag(tag.id)
            assertFalse(controller.delete(tag))
            assertEquals(tag.id, editor.selectedDocument!!.note.directTagIds.last())
            environment.gateway.lock()
            assertEquals(
                tag,
                environment.gateway.openRecentWorkspace().tagDefinitions.single { it.definition.id == tag.id }.definition
            )
        }
    }
}
