package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.crypto.Argon2idParameters
import com.oruke.tagloom.core.model.ManualPropertyBinding
import com.oruke.tagloom.core.model.NotePropertyValue
import com.oruke.tagloom.core.model.PropertyValueSource
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.core.model.TagId
import com.oruke.tagloom.core.model.TagPropertyBinding
import com.oruke.tagloom.core.workspace.WorkspaceDefaultSchemas
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.editor.resolveEffectiveProperties
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 使用真实加密文件、schema 事务及编辑器入口验证默认属性修正与重启兼容。 */
class JvmNoteTagSchemaMigrationTest {
    /** 验证新建、保存、重启后的普通 Note 只继承描述和普通标签。 */
    @Test
    fun createsPlainNoteWithoutStatusOrDate() = withWorkspace { root, manager ->
        manager.prepareCreation(root, TEST_SECRET)
        val opened = manager.confirmCreation()
        val editor = opened.newEditor()
        editor.createDocument()
        val note = requireNotNull(editor.selectedDocument).note
        assertEquals(
            setOf(WorkspaceDefaults.DEFAULT_TEXT_PROPERTY_ID, WorkspaceDefaults.DEFAULT_TAGS_PROPERTY_ID),
            requireNotNull(editor.resolveEffectiveProperties(note)).properties.map { it.propertyId }.toSet(),
        )
        assertTrue(note.propertyValues.isEmpty())
        val saved = requireNotNull(manager.sessionOrNull()).saveNote(null, note)
        manager.lock()

        val reopened = manager.unlock(root, TEST_SECRET)
        assertEquals(note, reopened.notes.single().snapshot.payload)
        assertEquals(saved.id, reopened.notes.single().id)
        assertEquals(2, reopened.tagDefinitions.single().snapshot.payload.propertyBindings.size)
    }

    /** 验证旧根绑定只迁移一次，既有笔记密文、手动绑定及自定义类型均保留。 */
    @Test
    fun migratesLegacyRootWithoutRewritingNotesOrCustomTypes() = withWorkspace { root, manager ->
        manager.prepareCreation(root, TEST_SECRET)
        val opened = manager.confirmCreation()
        val session = requireNotNull(manager.sessionOrNull())
        val legacyRoot = WorkspaceDefaultSchemas.noteTagDefinition().copy(
            propertyBindings = WorkspaceDefaultSchemas.propertyDefinitions().mapIndexed { order, definition ->
                TagPropertyBinding(definition.id, order = order)
            },
        )
        session.saveTagDefinition(legacyRoot)
        val customType = TagDefinition(
            id = TagId("test-custom-type"),
            name = "示例类型",
            propertyBindings = listOf(TagPropertyBinding(WorkspaceDefaults.DEFAULT_DATE_PROPERTY_ID)),
        )
        session.saveTagDefinition(customType)
        val editor = opened.newEditor()
        editor.createDocument()
        val note = requireNotNull(editor.selectedDocument).note.copy(
            propertyValues = mapOf(
                WorkspaceDefaults.DEFAULT_SELECT_PROPERTY_ID to NotePropertyValue(
                    value = JsonPrimitive(WorkspaceDefaults.DEFAULT_SELECT_DONE_OPTION_ID),
                    source = PropertyValueSource.USER,
                    overridden = true,
                    updatedAt = TEST_TIMESTAMP,
                ),
            ),
            manualPropertyBindings = listOf(ManualPropertyBinding(WorkspaceDefaults.DEFAULT_DATE_PROPERTY_ID)),
        )
        val saved = session.saveNote(null, note)
        val notePath = root.resolve("notes/${saved.id.value}.note.enc")
        val noteBytes = Files.readAllBytes(notePath)
        manager.lock()

        val migrated = manager.unlock(root, TEST_SECRET)
        val rootSnapshot =
            migrated.tagDefinitions.single { it.id.value == WorkspaceDefaults.DEFAULT_NOTE_TAG_ID.value }.snapshot
        assertEquals(WorkspaceDefaultSchemas.noteTagDefinition(), rootSnapshot.payload)
        assertEquals(note, migrated.notes.single().snapshot.payload)
        assertTrue(noteBytes.contentEquals(Files.readAllBytes(notePath)))
        assertEquals(customType, migrated.tagDefinitions.single { it.id.value == customType.id.value }.snapshot.payload)
        assertEquals(4, migrated.propertyDefinitions.size)
        assertFalse(migrated.readOnly)
        assertTrue(migrated.integrityIssues.isEmpty())
        manager.lock()

        val reopened = manager.unlock(root, TEST_SECRET)
        assertEquals(
            rootSnapshot,
            reopened.tagDefinitions.single { it.id.value == WorkspaceDefaults.DEFAULT_NOTE_TAG_ID.value }.snapshot
        )
    }

    /** 验证带用户约束的根属性不会被旧默认绑定迁移覆盖。 */
    @Test
    fun preservesCustomizedRootBinding() = withWorkspace { root, manager ->
        manager.prepareCreation(root, TEST_SECRET)
        manager.confirmCreation()
        val customized = WorkspaceDefaultSchemas.noteTagDefinition().copy(
            propertyBindings = WorkspaceDefaultSchemas.noteTagDefinition().propertyBindings +
                    TagPropertyBinding(WorkspaceDefaults.DEFAULT_SELECT_PROPERTY_ID, required = true),
        )
        requireNotNull(manager.sessionOrNull()).saveTagDefinition(customized)
        manager.lock()
        assertEquals(customized, manager.unlock(root, TEST_SECRET).tagDefinitions.single().snapshot.payload)
    }

    /**
     * 用真实打开结果填充生产编辑器，确保测试包含 schema 到新建笔记的调用链。
     *
     * @return 已加载认证 schema 的空编辑器。
     */
    private fun DesktopWorkspaceOpenState.newEditor(): TagloomEditorState {
        var nextId = 0
        return TagloomEditorState({ prefix -> "$prefix-${nextId++}" }, { TEST_TIMESTAMP }).also { editor ->
            editor.loadWorkspaceDocuments(
                loadedDocuments = emptyList(),
                loadedTagDefinitions = tagDefinitions.map { it.snapshot.payload },
                loadedPropertyDefinitions = propertyDefinitions.map { it.snapshot.payload },
            )
        }
    }

    /**
     * 在独立临时工作区执行真实业务流程，最终锁定并仅清理本测试目录。
     *
     * @param scenario 接收测试根目录和真实 Manager 的验证流程。
     */
    private fun withWorkspace(scenario: suspend (Path, DesktopWorkspaceManager) -> Unit) = runBlocking {
        val root = Files.createTempDirectory(TEST_PREFIX).toAbsolutePath().normalize()
        val ids = AtomicInteger()
        val manager = DesktopWorkspaceManager(
            idFactory = { "test-object-${ids.incrementAndGet()}" },
            currentTimeMillis = { 1_789_200_000_000L + ids.get() },
            timestampProvider = { TEST_TIMESTAMP },
            argon2idFactory = { salt -> Argon2idParameters(salt, memoryKiB = 8, iterations = 1, parallelism = 1) },
        )
        try {
            scenario(root, manager)
        } finally {
            manager.lock()
            check(root.parent == Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize())
            check(root.fileName.toString().startsWith(TEST_PREFIX))
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private companion object {
        /** 仅用于隔离测试工作区的脱敏口令。 */
        const val TEST_SECRET = "test-only-migration-material"

        /** 确定性快照时间。 */
        const val TEST_TIMESTAMP = "2026-09-12T00:00:00Z"

        /** 确保清理只命中本测试创建的目录。 */
        const val TEST_PREFIX = "tagloom-note-schema-test-"
    }
}
