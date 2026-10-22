package com.oruke.tagloom.ui

import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import com.oruke.tagloom.core.workspace.WorkspaceManifestCodec
import com.oruke.tagloom.core.workspace.LinkNoteSchemas
import com.oruke.tagloom.platform.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.test.*

/** 使用真实密文和网络覆盖旧响应、撤销引用保护以及协议升级后的轮换与备份。 */
class LinkNoteSafetyIntegrationTest {
    /** 新 URL 先完成后，旧 URL 的延迟结果不能覆盖新标题或来源记录。 */
    @Test
    fun discardsLateResponsesAfterUrlChanges() = runBlocking {
        WorkspaceSettingsTestEnvironment().use { environment ->
            LinkMetadataTestServer().use { server ->
                val gateway = environment.gateway
                gateway.openRecentWorkspace()
                val links = gateway.links
                val note = links.create(
                    CreateLinkNoteRequest(
                        server.url("/slow"),
                        typeName = "Link example",
                        groupName = "Group example"
                    )
                ).link
                val old = async(Dispatchers.IO) { links.refresh(note.id) }
                assertTrue(server.started.await(5, TimeUnit.SECONDS))
                gateway.saveNote(
                    note.id, note.note.copy(
                        propertyValues = note.note.propertyValues +
                                (LinkNoteSchemas.URL_ID to note.note.propertyValues.getValue(LinkNoteSchemas.URL_ID)
                                    .copy(value = JsonPrimitive(server.url("/new"))))
                    ), note.revision
                )
                assertEquals("New page example", links.refresh(note.id).note.title)
                server.release.countDown()
                assertEquals("New page example", old.await().note.title)
                assertEquals("New page example", gateway.reload().notes.single { it.id == note.id }.note.title)
            }
        }
    }

    /** 撤销新建能同时写 tombstone；另一个 Note 已引用时只撤销本次来源引用。 */
    @Test
    fun undoesCreationWithoutDeletingSharedOrEditedTargets() = runBlocking {
        WorkspaceSettingsTestEnvironment().use { environment ->
            LinkMetadataTestServer().use { server ->
                val gateway = environment.gateway
                gateway.openRecentWorkspace()
                val sourceBlock = BlockId("undo-source")
                val source = gateway.saveNote(
                    null, Note(
                        "Undo source example", listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID), blocks = listOf(
                            Block(
                                sourceBlock,
                                BlockTypes.PARAGRAPH,
                                orderKey = "0000000000",
                                content = RichTextBlockContent(RichText())
                            ),
                        )
                    )
                )
                val first = gateway.links.create(
                    CreateLinkNoteRequest(
                        server.url("/page"),
                        source = LinkNoteSource(source.id, source.note, sourceBlock, source.revision),
                        typeName = "Link example",
                        groupName = "Group example"
                    )
                )
                gateway.links.refresh(first.link.id)
                val removed = gateway.links.undo(
                    LinkCreationUndo(
                        first.link.id,
                        first.link.note,
                        first.link.createdAt,
                        true,
                        source.id,
                        sourceBlock
                    )
                )
                assertEquals(first.link.id, removed.deletedLinkId)
                assertTrue(removed.source!!.note.blocks.single { it.id == sourceBlock }.deleted)
                assertTrue(Files.exists(environment.root.resolve("workspace/notes/${first.link.id.value}.note.enc")))
                val second = gateway.links.create(
                    CreateLinkNoteRequest(
                        server.url("/new"),
                        typeName = "Link example",
                        groupName = "Group example"
                    )
                ).link
                gateway.saveNote(
                    null, Note(
                        "Other reference example", listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID), blocks = listOf(
                            Block(
                                BlockId("other-reference"),
                                BlockTypes.NOTE_REFERENCE,
                                orderKey = "0000000000",
                                content = NoteReferenceBlockContent(second.id)
                            ),
                        )
                    )
                )
                assertNull(
                    gateway.links.undo(
                        LinkCreationUndo(
                            second.id,
                            second.note,
                            second.createdAt,
                            true,
                            null,
                            null
                        )
                    ).deletedLinkId
                )
                val third = gateway.links.create(
                    CreateLinkNoteRequest(
                        server.url("/edited"),
                        typeName = "Link example",
                        groupName = "Group example"
                    )
                ).link
                gateway.saveNote(third.id, third.note.copy(title = "Manual retained example"), third.revision)
                assertNull(
                    gateway.links.undo(
                        LinkCreationUndo(
                            third.id,
                            third.note,
                            third.createdAt,
                            true,
                            null,
                            null
                        )
                    ).deletedLinkId
                )
                gateway.lock()
                val loaded = gateway.openRecentWorkspace()
                assertTrue(loaded.notes.none { it.id == first.link.id })
                assertTrue(loaded.notes.any { it.id == second.id })
                assertEquals("Manual retained example", loaded.notes.single { it.id == third.id }.note.title)
            }
        }
    }

    /** v2 门禁随真实主密钥轮换与密文备份保留，不因维护流程退回旧读取版本。 */
    @Test
    fun retainsVersionGateAcrossKeyRotationAndBackup(): Unit = runBlocking {
        WorkspaceSettingsTestEnvironment().use { environment ->
            LinkMetadataTestServer().use { server ->
                val gateway = environment.gateway
                gateway.openRecentWorkspace()
                val created = gateway.links.create(
                    CreateLinkNoteRequest(
                        server.url("/page"),
                        typeName = "Link example",
                        groupName = "Group example"
                    )
                ).link
                gateway.links.refresh(created.id)
                gateway.rotateMasterKey(null)
                assertEquals(
                    2,
                    requireNotNull(gateway.sessionOrNull()).unlocked.file.publicHeader.manifestFormatVersion
                )
                assertTrue(gateway.createBackup())
                ZipFile(environment.root.resolve("backup.zip").toFile()).use { zip ->
                    val manifest = zip.entries().asSequence().single { it.name.endsWith("manifest.enc") }
                    val bytes = zip.getInputStream(manifest).use { it.readBytes() }
                    assertEquals(2, WorkspaceManifestCodec.decode(bytes).publicHeader.manifestFormatVersion)
                }
                gateway.lock()
                val restored = gateway.openRecentWorkspace().notes.single { it.id == created.id }.note
                assertEquals("Metadata page example", restored.title)
                assertNotNull(restored.linkMetadata)
            }
        }
    }
}
