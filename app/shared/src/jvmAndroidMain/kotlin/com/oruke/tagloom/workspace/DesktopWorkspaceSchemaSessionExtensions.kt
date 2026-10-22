package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.core.model.TagId
import com.oruke.tagloom.core.model.TransactionId
import com.oruke.tagloom.core.sync.HybridLogicalTime
import com.oruke.tagloom.core.transaction.WorkspaceSchemaWriteContext
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import kotlinx.coroutines.sync.withLock

/**
 * 在同一会话锁内检查活动引用并提交超级标签删除，防止检查与写入之间出现竞争引用。
 * @param tagId 待删除超级标签；内置 Note 永远不可删除。
 */
suspend fun DesktopWorkspaceSession.deleteTagDefinition(tagId: TagId) = sessionMutex.withLock {
    requireWritable()
    require(tagId != WorkspaceDefaults.DEFAULT_NOTE_TAG_ID && tagId != com.oruke.tagloom.core.workspace.LinkNoteSchemas.TYPE_ID) { "builtin-supertag-readonly" }
    // 先读取权威快照；损坏目录无法证明无引用，因此拒绝删除。
    val schema = schemaStore.inspect(unlocked)
    val queries = savedQueryLoader.inspect(unlocked)
    val databases = databaseStore.load(unlocked)
    require(schema.issues.isEmpty() && queries.issues.isEmpty() && databases.issues.isEmpty()) { "reference-check-failed" }
    require(schema.tags.none { tagId in it.payload.parentTagIds }) { "supertag-has-children" }
    require(loadActiveNotes().none { tagId in it.snapshot.payload.directTagIds }) { "supertag-has-notes" }
    require(queries.snapshots.none { tagId in it.payload.tagIds }) { "supertag-has-queries" }
    require(databases.databases.none { it.payload.sourceTypeId == tagId }) { "supertag-has-databases" }
    // 删除也通过事务协调器确认；失败时重新读取 manifest，避免会话使用过期确认点。
    val result = try {
        schemaStore.deleteTag(unlocked, tagId, nextSchemaWriteContext())
    } catch (error: Throwable) {
        runCatching { refreshManifest() }
        throw error
    }
    refreshManifest(result.encryptedManifest)
}

/**
 * 创建或更新一个工作区标签定义，并确认其 Change 与加密快照。
 *
 * @param definition 待保存完整标签定义。
 * @return 新确认标签定义。
 */
suspend fun DesktopWorkspaceSession.saveTagDefinition(definition: TagDefinition): DesktopStoredTagDefinition =
    sessionMutex.withLock {
        requireWritable()
        val result = try {
            schemaStore.saveTag(unlocked, definition, nextSchemaWriteContext())
        } catch (error: Throwable) {
            runCatching { refreshManifest() }
            throw error
        }
        refreshManifest(result.encryptedManifest)
        DesktopStoredTagDefinition(result.snapshot)
    }

/**
 * 创建或更新一个工作区属性定义，并确认其 Change 与加密快照。
 *
 * @param definition 待保存完整属性定义。
 * @return 新确认属性定义。
 */
suspend fun DesktopWorkspaceSession.savePropertyDefinition(definition: PropertyDefinition): DesktopStoredPropertyDefinition =
    sessionMutex.withLock {
        requireWritable()
        val catalog = schemaStore.load(unlocked)
        require(catalog.issues.isEmpty()) { "schema-integrity-failure" }
        when (definition.id) {
            com.oruke.tagloom.core.workspace.LinkNoteSchemas.GROUP_ID -> require(definition.type == com.oruke.tagloom.core.model.PropertyTypes.SELECT && definition.hierarchical) { "link-schema-incompatible" }
            com.oruke.tagloom.core.workspace.LinkNoteSchemas.URL_ID -> require(definition.type == com.oruke.tagloom.core.model.PropertyTypes.URL) { "link-schema-incompatible" }
        }
        com.oruke.tagloom.core.domain.property.PropertyOptionChangePolicy.requireSafeUpdate(
            catalog.properties.firstOrNull { it.payload.id == definition.id }?.payload, definition,
            loadActiveNotes().map { it.snapshot.payload }, catalog.tags.map { it.payload },
        )
        val result = try {
            schemaStore.saveProperty(unlocked, definition, nextSchemaWriteContext())
        } catch (error: Throwable) {
            runCatching { refreshManifest() }
            throw error
        }
        refreshManifest(result.encryptedManifest)
        DesktopStoredPropertyDefinition(result.snapshot)
    }


/**
 * 消耗下一个 Actor 序号并生成 schema 写入上下文。
 *
 * 序号在提交失败时也不会复用，避免重试产生相同 revision 身份。
 *
 * @return 当前写入唯一上下文。
 */
internal fun DesktopWorkspaceSession.nextSchemaWriteContext(): WorkspaceSchemaWriteContext =
    WorkspaceSchemaWriteContext(
        actorId = actorId,
        actorSequence = nextActorSequence++,
        transactionId = TransactionId(idFactory()),
        hybridLogicalTime = HybridLogicalTime(currentTimeMillis(), 0, actorId),
        committedAt = timestampProvider(),
    )
