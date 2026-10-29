package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.domain.tag.TagResolution
import com.oruke.tagloom.core.domain.tag.TagResolver
import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.query.DatabaseProjectionService
import com.oruke.tagloom.core.query.ProjectionNote
import com.oruke.tagloom.core.sync.ConflictStatus
import com.oruke.tagloom.core.sync.WorkspaceReplicaConflictReason
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import com.oruke.tagloom.platform.WorkspaceOpenResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 真实双设备验证结构化目录、引用、查询，以及跨对象继承循环的整目录冲突回退。 */
class WorkspaceStructuredSyncIntegrationTest {
    /** Database/View、类型属性、引用与保存查询通过同步拉取，反向更新和重开后仍可查询。 */
    @Test
    fun transfersDatabaseViewsAndCatalogsThroughRealGatewayAndPersistsAfterRestart() = ServerRecoveryTestEnvironment().use { env ->
        runBlocking {
            val first = env.device("structured-first")
            val key = requireNotNull(first.gateway.prepareCreation(null)).recoveryKeyText
            first.gateway.confirmCreation()
            first.gateway.serverRecovery.publish(env.address, env.bootstrap, key)
            val second = env.device("structured-second")
            second.gateway.serverRecovery.restore(env.address, key, null)

            // 第二台已打开共同基线，以下对象必须经新同步拉取，不能由初次恢复夹带。
            val property = PropertyDefinition(PropertyId("structured-field"), "Structured field", PropertyTypes.TEXT)
            val tag = TagDefinition(TagId("structured-type"), "Structured type", propertyBindings = listOf(TagPropertyBinding(property.id)))
            first.gateway.savePropertyDefinition(property)
            first.gateway.saveTagDefinition(tag)
            val databaseId = DatabaseId("structured-database")
            val initialViewId = ViewId("structured-initial-view")
            val database = DatabaseDefinition(databaseId, "Structured collection", tag.id, creationTypeId = tag.id, defaultViewId = initialViewId)
            val view = ViewDefinition(initialViewId, databaseId, "Structured table", "a",
                query = QuerySpec(filter = FilterExpr(kind = FilterExprKinds.FULL_TEXT, text = "structuredrow")),
                presentation = ViewPresentation(fields = listOf(
                    ViewField(FieldRef(FieldRefKinds.SYSTEM, systemField = SystemFieldNames.TITLE), "a"),
                    ViewField(FieldRef(FieldRefKinds.PROPERTY, propertyId = property.id), "b"),
                )))
            first.gateway.createDatabase(database, view)
            val row = first.gateway.saveNote(null, Note(title = "structuredrow sample", directTagIds = listOf(tag.id),
                propertyValues = mapOf(property.id to NotePropertyValue(JsonPrimitive("Synced field value"), PropertyValueSource.USER, updatedAt = first.gateway.currentUtcTimestamp())),
                blocks = listOf(structuredParagraph("row-body", "db synchronized body", "a"))))
            val reference = first.gateway.saveNote(null, Note(title = "Structured source reference", directTagIds = emptyList(), blocks = listOf(
                Block(BlockId("database-reference"), BlockTypes.DATABASE, orderKey = "a", content = DatabaseBlockContent(databaseId)),
                Block(BlockId("note-reference"), BlockTypes.NOTE_REFERENCE, orderKey = "b", content = NoteReferenceBlockContent(row.id)),
            )))
            val query = first.gateway.saveQuery(SavedQuery(name = "Structured saved query", text = "structuredrow"))
            first.gateway.serverRecovery.updateIfChanged()
            second.gateway.serverRecovery.updateIfChanged()
            val pulled = requireNotNull(second.gateway.reload())
            assertEquals(database, pulled.databaseDefinitions.single().definition)
            assertEquals(view, pulled.viewDefinitions.single().definition)
            assertTrue(pulled.tagDefinitions.any { it.definition == tag })
            assertTrue(pulled.propertyDefinitions.any { it.definition == property })
            assertEquals(reference.note, pulled.notes.single { it.id == reference.id }.note)
            assertEquals(row.id, second.gateway.search("structuredrow").single().noteId)
            assertEquals(row.id, requireNotNull(second.manager.sessionOrNull()).runSavedQuery(query.id).single().noteId)
            assertStructuredProjection(pulled, database, view, row.id)

            // 同一真实网关反向创建独立 View，再修改默认视图和原 View，不直接写快照文件。
            val extraView = view.copy(id = ViewId("structured-secondary-view"), name = "Secondary table", orderKey = "b")
            second.gateway.createViewDefinition(extraView)
            val changedView = view.copy(name = "Updated gallery", presentation = view.presentation.copy(kind = ViewPresentationKinds.GALLERY))
            second.gateway.saveViewDefinition(changedView)
            val changedDatabase = database.copy(name = "Updated structured collection", defaultViewId = extraView.id, includeDescendants = false)
            second.gateway.saveDatabaseDefinition(changedDatabase)
            second.gateway.serverRecovery.updateIfChanged()
            first.gateway.serverRecovery.updateIfChanged()
            val returned = requireNotNull(first.gateway.reload())
            assertEquals(changedDatabase, returned.databaseDefinitions.single().definition)
            assertEquals(setOf(changedView, extraView), returned.viewDefinitions.map { it.definition }.toSet())
            assertStructuredProjection(returned, changedDatabase, changedView, row.id)
            first.gateway.lock()
            second.gateway.lock()
            val reopenedFirst = first.gateway.openRecentWorkspace()
            val reopenedSecond = second.gateway.openRecentWorkspace()
            assertEquals(reopenedFirst.databaseDefinitions, reopenedSecond.databaseDefinitions)
            assertEquals(reopenedFirst.viewDefinitions, reopenedSecond.viewDefinitions)
            assertEquals(changedDatabase, reopenedFirst.databaseDefinitions.single().definition)
            assertEquals(setOf(changedView, extraView), reopenedFirst.viewDefinitions.map { it.definition }.toSet())
            assertStructuredProjection(reopenedSecond, changedDatabase, changedView, row.id)
            assertEquals(row.id, requireNotNull(first.manager.sessionOrNull()).runSavedQuery(query.id).single().noteId)
            assertEquals(reference.id, first.capabilities.searchEngine.backlinks(row.id, null).single().sourceNoteId)
        }
    }

    /** 两边分别合法的父级修改联合成环时，整目录回退收敛且独立正文修改与双版本证据保留。 */
    @Test
    fun resolvesCrossDeviceInheritanceCycleByWholeCatalogAndPreservesNoteEdits() = ServerRecoveryTestEnvironment().use { env ->
        runBlocking {
            val first = env.device("catalog-first")
            val key = requireNotNull(first.gateway.prepareCreation(null)).recoveryKeyText
            first.gateway.confirmCreation()
            val root = WorkspaceDefaults.DEFAULT_NOTE_TAG_ID
            val tagA = TagDefinition(TagId("catalog-type-a"), "Catalog A", parentTagIds = listOf(root))
            val tagB = TagDefinition(TagId("catalog-type-b"), "Catalog B", parentTagIds = listOf(root))
            first.gateway.saveTagDefinition(tagA)
            first.gateway.saveTagDefinition(tagB)
            val note = first.gateway.saveNote(null, Note(title = "Catalog content sample", directTagIds = listOf(tagA.id), blocks = listOf(
                structuredParagraph("catalog-block-a", "initial first", "a"),
                structuredParagraph("catalog-block-b", "initial second", "b"),
            )))
            first.gateway.serverRecovery.publish(env.address, env.bootstrap, key)
            val second = env.device("catalog-second")
            second.gateway.serverRecovery.restore(env.address, key, null)
            val changedA = tagA.copy(parentTagIds = listOf(tagB.id))
            val changedB = tagB.copy(parentTagIds = listOf(tagA.id))
            first.gateway.saveTagDefinition(changedA)
            second.gateway.saveTagDefinition(changedB)
            first.gateway.saveNote(note.id, note.note.copy(blocks = listOf(
                structuredParagraph("catalog-block-a", "catalogalpha first edit", "a"), note.note.blocks[1],
            )), note.revision)
            second.gateway.saveNote(note.id, note.note.copy(blocks = listOf(
                note.note.blocks[0], structuredParagraph("catalog-block-b", "catalogbeta second edit", "b"),
            )), note.revision)
            first.gateway.serverRecovery.updateIfChanged()
            second.gateway.serverRecovery.updateIfChanged()
            first.gateway.serverRecovery.updateIfChanged()
            second.gateway.serverRecovery.updateIfChanged()

            for (device in listOf(first, second)) {
                val state = requireNotNull(device.gateway.reload())
                assertLegalStructuredCatalog(state, tagA.id, tagB.id)
                val content = state.notes.single { it.id == note.id }.note.blocks.map { it.extractPlainText() }
                assertEquals(listOf("catalogalpha first edit", "catalogbeta second edit"), content)
                assertEquals(note.id, device.gateway.search("catalogalpha").single().noteId)
                assertEquals(note.id, device.gateway.search("catalogbeta").single().noteId)
                val session = requireNotNull(device.manager.sessionOrNull())
                val conflicts = session.sessionMutex.withLock { loadReplicaConflicts(session) }
                    .filter { it.reason == WorkspaceReplicaConflictReason.CATALOG_INVARIANT }
                assertTrue(conflicts.isNotEmpty())
                for ((id, expected) in listOf(tagA.id to setOf(tagA, changedA), tagB.id to setOf(tagB, changedB))) {
                    val conflict = conflicts.single { it.objectId == ObjectId(id.value) }
                    val versions = setOf(
                        CanonicalJson.format.decodeFromJsonElement(TagDefinition.serializer(), conflict.localSnapshot.payload),
                        CanonicalJson.format.decodeFromJsonElement(TagDefinition.serializer(), conflict.remoteSnapshot.payload),
                    )
                    assertEquals(expected, versions)
                    assertEquals(ConflictStatus.UNRESOLVED, conflict.status)
                }
            }
            val before = requireNotNull(first.gateway.reload())
            assertEquals(before.tagDefinitions, requireNotNull(second.gateway.reload()).tagDefinitions)
            first.gateway.lock()
            second.gateway.lock()
            val firstReopened = first.gateway.openRecentWorkspace()
            val secondReopened = second.gateway.openRecentWorkspace()
            assertLegalStructuredCatalog(firstReopened, tagA.id, tagB.id)
            assertLegalStructuredCatalog(secondReopened, tagA.id, tagB.id)
            assertEquals(firstReopened.tagDefinitions, secondReopened.tagDefinitions)
            assertEquals(before.notes.single().note, secondReopened.notes.single().note)
            assertTrue(second.gateway.serverRecovery.conflicts().any { !it.resolved })
        }
    }
}

/**
 * 构造已规范化的脱敏段落，稳定 ID 用于真实三方合并。
 * @param id 当前笔记内稳定 Block ID。
 * @param text 完整测试正文。
 * @param orderKey 该段落的正文排序键。
 * @return 通过网关保存的富文本段落。
 */
private fun structuredParagraph(id: String, text: String, orderKey: String): Block =
    Block(BlockId(id), BlockTypes.PARAGRAPH, orderKey = orderKey, content = RichTextBlockContent(RichText(listOf(InlineRun(text)))))

/**
 * 从真实同步并重读的目录与 Note 构建产品同款数据投影，核对可查询的行及属性。
 * @param state 已认证的工作区当前确认状态。
 * @param database 预期收到的数据集定义。
 * @param view 预期收到的视图定义。
 * @param noteId 必须命中的数据行稳定 ID。
 */
private fun assertStructuredProjection(state: WorkspaceOpenResult, database: DatabaseDefinition, view: ViewDefinition, noteId: ObjectId) {
    val projection = DatabaseProjectionService(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID).project(
        database, view,
        state.notes.map { ProjectionNote(it.id, it.note, it.revision, it.createdAt, it.updatedAt) },
        state.tagDefinitions.map { it.definition }, state.propertyDefinitions.map { it.definition },
    )
    assertEquals(noteId, projection.rows.single().noteId)
    assertEquals(2, projection.columns.size)
    assertTrue(projection.rows.single().values.values.contains(JsonPrimitive("Synced field value")))
}

/**
 * 用正式继承解析器核对整目录合法，显式拒绝双方父级修改拼成环的结果。
 * @param state 同步后或重开后的认证状态。
 * @param firstId 交叉修改的第一个类型。
 * @param secondId 交叉修改的第二个类型。
 */
private fun assertLegalStructuredCatalog(state: WorkspaceOpenResult, firstId: TagId, secondId: TagId) {
    assertFalse(state.readOnly)
    val tags = state.tagDefinitions.associate { it.definition.id to it.definition }
    assertTrue(TagResolver(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID).resolve(listOf(firstId, secondId), tags) is TagResolution.Success)
    assertFalse(secondId in tags.getValue(firstId).parentTagIds && firstId in tags.getValue(secondId).parentTagIds)
}
