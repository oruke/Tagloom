package com.oruke.tagloom.ui

import com.oruke.tagloom.core.external.ExternalCallException
import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.core.workspace.LinkNoteSchemas
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import com.oruke.tagloom.platform.*
import com.oruke.tagloom.workspace.loadNotes
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** 从真实网关、加密文件、索引与本机网页服务验证链接创建、恢复与并发保护。 */
class LinkNotePersistenceTest {
    /** 原子创建来源引用、规范地址去重、冲突无残留，元数据与类型在重开后仍存在。 */
    @Test
    fun createsReferencesAndRecoversEncryptedLinkNotes() = runBlocking {
        WorkspaceSettingsTestEnvironment().use { environment ->
            LinkMetadataTestServer().use { server ->
                val gateway = environment.gateway
                gateway.openRecentWorkspace()
                val anchor = BlockId("source-paragraph")
                val source = gateway.saveNote(
                    null, Note(
                        "Source example", listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID), blocks = listOf(
                            Block(
                                anchor,
                                BlockTypes.PARAGRAPH,
                                orderKey = "0000000000",
                                content = RichTextBlockContent(RichText())
                            ),
                        )
                    )
                )
                val links = requireNotNull(gateway.links)
                val created = links.create(
                    CreateLinkNoteRequest(
                        server.url("/redirect"),
                        source = LinkNoteSource(source.id, source.note, anchor, source.revision),
                        typeName = "Link example",
                        groupName = "Group example"
                    )
                )
                assertFalse(created.reused)
                assertEquals(
                    setOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID, LinkNoteSchemas.TYPE_ID),
                    created.link.note.directTagIds.toSet()
                )
                assertEquals(setOf(LinkNoteSchemas.URL_ID), created.link.note.propertyValues.keys)
                assertEquals(
                    setOf(LinkNoteSchemas.URL_ID, LinkNoteSchemas.GROUP_ID),
                    created.type.propertyBindings.map { it.propertyId }.toSet()
                )
                assertEquals(
                    created.link.id,
                    (created.source!!.note.blocks.single { it.id == anchor }.content as NoteReferenceBlockContent).noteId
                )
                assertNotNull(created.nextBlockId)
                val fetched = links.refresh(created.link.id)
                assertEquals("Metadata page example", fetched.note.title)
                assertEquals("A & B source description", LinkNotePolicy.description(fetched.note))
                val second = links.create(
                    CreateLinkNoteRequest(
                        server.url("/redirect"),
                        source = LinkNoteSource(
                            source.id,
                            created.source!!.note,
                            created.nextBlockId!!,
                            created.source!!.revision
                        ),
                        typeName = "Link example",
                        groupName = "Group example"
                    )
                )
                assertTrue(second.reused)
                assertEquals(created.link.id, second.link.id)
                assertEquals(2, gateway.reload()!!.notes.size)
                val countBefore = gateway.reload()!!.notes.size
                val conflict = assertFailsWith<ExternalCallException> {
                    links.create(
                        CreateLinkNoteRequest(
                            server.url("/new"),
                            source = LinkNoteSource(source.id, source.note, anchor, source.revision),
                            typeName = "Link example",
                            groupName = "Group example"
                        )
                    )
                }
                assertEquals("revision_conflict", conflict.code)
                assertEquals(countBefore, gateway.reload()!!.notes.size)
                val session = requireNotNull(gateway.sessionOrNull())
                assertEquals(2, session.unlocked.file.publicHeader.manifestFormatVersion)
                assertEquals(2, session.loadNotes().single { it.id == created.link.id }.snapshot.header.schemaVersion)
                val hits = gateway.search(server.url("/redirect"))
                assertTrue(hits.any { it.noteId == created.link.id })
                assertTrue(session.backlinks(created.link.id).any { it.sourceNoteId == source.id })
                Files.walk(environment.root.resolve("workspace")).use { paths ->
                    paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".enc") }
                        .forEach { file ->
                            val ciphertext = Files.readAllBytes(file).toString(Charsets.ISO_8859_1)
                            assertFalse(ciphertext.contains("Metadata page example"))
                            assertFalse(ciphertext.contains(server.url("/redirect")))
                        }
                }
                gateway.lock()
                val recovered = gateway.openRecentWorkspace()
                val note = recovered.notes.single { it.id == created.link.id }.note
                assertEquals("Metadata page example", note.title)
                assertNotNull(note.linkMetadata)
                assertEquals(
                    2,
                    recovered.notes.single { it.id == source.id }.note.blocks.count { !it.deleted && it.content is NoteReferenceBlockContent })
            }
        }
    }

    /** 真实网络等待期间保存手动字段，晚到结果与重试均不得覆盖；旧 URL 回调失效。 */
    @Test
    fun preservesManualFieldsAndDiscardsOldUrlResponses() = runBlocking {
        WorkspaceSettingsTestEnvironment().use { environment ->
            LinkMetadataTestServer().use { server ->
                val gateway = environment.gateway
                gateway.openRecentWorkspace()
                val links = requireNotNull(gateway.links)
                val created = links.create(
                    CreateLinkNoteRequest(
                        server.url("/slow"),
                        typeName = "Link example",
                        groupName = "Group example"
                    )
                ).link
                val fetching = async(Dispatchers.IO) { links.refresh(created.id) }
                assertTrue(server.started.await(5, TimeUnit.SECONDS))
                val edited = gateway.saveNote(
                    created.id, created.note.copy(
                        title = "Manual title example", propertyValues = created.note.propertyValues +
                                (WorkspaceDefaults.DEFAULT_TEXT_PROPERTY_ID to NotePropertyValue(
                                    JsonPrimitive("Manual description example"),
                                    PropertyValueSource.USER,
                                    updatedAt = TIME
                                ))
                    ), created.revision
                )
                server.release.countDown()
                val afterFetch = fetching.await()
                assertEquals(edited.note.title, afterFetch.note.title)
                assertEquals("Manual description example", LinkNotePolicy.description(afterFetch.note))
                assertFalse(afterFetch.note.linkMetadata!!.automaticTitle)
                val changedUrl = gateway.saveNote(
                    created.id, afterFetch.note.copy(
                        propertyValues = afterFetch.note.propertyValues +
                                (LinkNoteSchemas.URL_ID to afterFetch.note.propertyValues.getValue(LinkNoteSchemas.URL_ID)
                                    .copy(value = JsonPrimitive(server.url("/new"))))
                    ), afterFetch.revision
                )
                val refreshed = links.refresh(created.id)
                assertEquals("Manual title example", refreshed.note.title)
                assertEquals("Manual description example", LinkNotePolicy.description(refreshed.note))
                assertEquals(LinkNotePolicy.address(changedUrl.note)!!.value, refreshed.note.linkMetadata!!.url)
                gateway.lock()
                assertFalse(gateway.openRecentWorkspace().notes.single { it.id == created.id }.note.linkMetadata!!.automaticDescription)
            }
        }
    }

    /** 分组改名和移动保留 ID，已用项与有子项禁删；null 按统一入口清空并持久化。 */
    @Test
    fun maintainsHierarchicalGroupsAndNullableValues() = runBlocking {
        WorkspaceSettingsTestEnvironment().use { environment ->
            LinkMetadataTestServer().use { server ->
                val gateway = environment.gateway
                gateway.openRecentWorkspace()
                val created = gateway.links!!.create(
                    CreateLinkNoteRequest(
                        server.url("/page"),
                        typeName = "Link example",
                        groupName = "Group example"
                    )
                )
                val options = listOf(
                    PropertyOption("tech", "Engineering"),
                    PropertyOption("design", "Design"),
                    PropertyOption("leaf", "CSS", parentOptionId = "tech"),
                    PropertyOption("other", "CSS", parentOptionId = "design")
                )
                val groups =
                    gateway.savePropertyDefinition(created.properties.single { it.id == LinkNoteSchemas.GROUP_ID }
                        .copy(options = options)).definition
                val value = NotePropertyValue(JsonPrimitive("leaf"), PropertyValueSource.USER, updatedAt = TIME)
                val grouped = gateway.saveNote(
                    created.link.id,
                    created.link.note.copy(propertyValues = created.link.note.propertyValues + (groups.id to value)),
                    created.link.revision
                )
                assertFailsWith<IllegalArgumentException> { gateway.savePropertyDefinition(groups.copy(options = options.filterNot { it.id == "leaf" })) }
                assertFailsWith<IllegalArgumentException> { gateway.savePropertyDefinition(groups.copy(options = options.filterNot { it.id == "tech" })) }
                val moved = gateway.savePropertyDefinition(groups.copy(options = options.map {
                    if (it.id == "leaf") it.copy(
                        label = "Styles",
                        parentOptionId = "design"
                    ) else it
                })).definition
                assertEquals("Design / Styles", PropertyOptionTree.label(moved.options, "leaf"))
                assertEquals(
                    "leaf",
                    (gateway.reload()!!.notes.single { it.id == grouped.id }.note.propertyValues[groups.id]!!.value as JsonPrimitive).content
                )
                val cleared = gateway.saveNote(
                    grouped.id,
                    grouped.note.copy(propertyValues = grouped.note.propertyValues + (groups.id to value.copy(value = JsonNull))),
                    grouped.revision
                )
                assertFalse(groups.id in cleared.note.propertyValues)
                gateway.savePropertyDefinition(moved.copy(options = moved.options.filterNot { it.id == "leaf" }))
                gateway.lock()
                val reopened = gateway.openRecentWorkspace()
                assertFalse(groups.id in reopened.notes.single { it.id == grouped.id }.note.propertyValues)
                assertEquals(
                    "design",
                    reopened.propertyDefinitions.single { it.definition.id == groups.id }.definition.options.single { it.id == "other" }.parentOptionId
                )
            }
        }
    }

    /** 获取失败保留链接并原位重试，锁定取消真实在途连接且不回填旧会话。 */
    @Test
    fun retriesWithoutDuplicatesAndCancelsOnLock() = runBlocking {
        WorkspaceSettingsTestEnvironment().use { environment ->
            LinkMetadataTestServer().use { server ->
                val gateway = environment.gateway
                gateway.openRecentWorkspace()
                val links = gateway.links!!
                val first = links.create(
                    CreateLinkNoteRequest(
                        server.url("/retry"),
                        typeName = "Link example",
                        groupName = "Group example"
                    )
                ).link
                assertFailsWith<IllegalStateException> { links.refresh(first.id) }
                assertEquals(1, gateway.reload()!!.notes.size)
                server.fail.set(false)
                assertEquals("Metadata page example", links.refresh(first.id).note.title)
                assertEquals(1, gateway.reload()!!.notes.size)
                val slow = links.create(
                    CreateLinkNoteRequest(
                        server.url("/slow"),
                        typeName = "Link example",
                        groupName = "Group example"
                    )
                ).link
                val request = async(Dispatchers.IO) { links.refresh(slow.id) }
                assertTrue(server.started.await(5, TimeUnit.SECONDS))
                gateway.lock()
                assertFailsWith<CancellationException> { withTimeout(3000) { request.await() } }
                server.release.countDown()
                assertEquals(
                    slow.note.title,
                    gateway.openRecentWorkspace().notes.single { it.id == slow.id }.note.title
                )
            }
        }
    }

    companion object {
        /** 确定性的脱敏属性更新时间。 */
        private const val TIME = "2026-01-01T00:00:00Z"
    }
}
