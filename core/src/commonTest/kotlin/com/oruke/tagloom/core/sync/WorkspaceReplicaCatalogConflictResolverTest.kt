package com.oruke.tagloom.core.sync

import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.workspace.ManifestObjectKind
import com.oruke.tagloom.core.workspace.WorkspaceDefaultSchemas
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 验证两份独立合法的目录发生跨对象冲突时，成组选择、完整保留和正文自动合并能够一起成立。 */
class WorkspaceReplicaCatalogConflictResolverTest {
    /** 两侧均具有的第一种业务类型。 */
    private val typeA = TagDefinition(TagId("type-a"), "Type A")

    /** 两侧均具有的第二种业务类型。 */
    private val typeB = TagDefinition(TagId("type-b"), "Type B")

    /** A 改父级为 B、B 改父级为 A 的独立合法操作，不能组合成环，也不能丢弃 Note 非重叠编辑。 */
    @Test
    fun resolvesMergedInheritanceCycleAndPreservesIndependentTextEdits() {
        val aRoot = change("a-root", "root", 1, typeA.id.value, json(TagDefinition.serializer(), typeA))
        val bRoot = change("b-root", "root", 2, typeB.id.value, json(TagDefinition.serializer(), typeB))
        val aLocal = change("a-local", "device-a", 10, typeA.id.value,
            json(TagDefinition.serializer(), typeA.copy(parentTagIds = listOf(typeB.id))), listOf(aRoot.changeId))
        val bRemote = change("b-remote", "device-b", 20, typeB.id.value,
            json(TagDefinition.serializer(), typeB.copy(parentTagIds = listOf(typeA.id))), listOf(bRoot.changeId))
        val noteRoot = change("note-root", "root", 3, "shared-note", json(Note.serializer(), note("hello world")), operation = "replace-note")
        val noteLocal = change("note-local", "device-a", 30, "shared-note", json(Note.serializer(), note("hello brave world")),
            listOf(noteRoot.changeId), operation = "replace-note")
        val noteRemote = change("note-remote", "device-b", 31, "shared-note", json(Note.serializer(), note("hello world!")),
            listOf(noteRoot.changeId), operation = "replace-note")
        val local = baseCatalog() + listOf(tag(aLocal), tag(bRoot), noteEntry(noteLocal))
        val remote = baseCatalog() + listOf(tag(aRoot), tag(bRemote), noteEntry(noteRemote))
        val changes = listOf(aRoot, bRoot, aLocal, bRemote, noteRoot, noteLocal, noteRemote)
        WorkspaceReplicaCatalogValidator.validate(local)
        WorkspaceReplicaCatalogValidator.validate(remote)
        val resolved = resolve(local, remote, changes)
        val reversed = resolve(remote, local, changes, "2026-01-02T00:00:00Z")
        WorkspaceReplicaCatalogValidator.validate(resolved.objects.map { WorkspaceReplicaObject(it.kind, it.snapshot) })
        val a = resolved.objects.single { it.objectId.value == typeA.id.value }
        val b = resolved.objects.single { it.objectId.value == typeB.id.value }
        assertEquals(emptyList(), CanonicalJson.format.decodeFromJsonElement(TagDefinition.serializer(), a.snapshot.payload).parentTagIds)
        assertEquals(listOf(typeA.id), CanonicalJson.format.decodeFromJsonElement(TagDefinition.serializer(), b.snapshot.payload).parentTagIds)
        assertEquals(WorkspaceReplicaAction.MERGE, a.action)
        assertEquals(setOf(aRoot.changeId, aLocal.changeId), a.parentChangeIds.toSet())
        assertEquals(2, resolved.conflicts.size)
        assertTrue(resolved.conflicts.all { it.reason == WorkspaceReplicaConflictReason.CATALOG_INVARIANT })
        assertEquals(resolved.conflicts, reversed.conflicts)
        assertEquals(resolved.objects.map { it.snapshot.payload }, reversed.objects.map { it.snapshot.payload })
        val mergedNote = CanonicalJson.format.decodeFromJsonElement(Note.serializer(),
            resolved.objects.single { it.objectId.value == "shared-note" }.snapshot.payload)
        assertEquals("hello brave world!", (mergedNote.blocks.single().content as RichTextBlockContent).richText.plainText)
    }

    /** 两侧分别移动不同模板 Block 都合法，但合并成环时必须成组回退并保存完整证据。 */
    @Test
    fun resolvesCycleCreatedByIndependentTemplateBlockMoves() {
        val first = Block(BlockId("template-first"), BlockTypes.PARAGRAPH, orderKey = "a",
            content = RichTextBlockContent(RichText()))
        val second = first.copy(id = BlockId("template-second"), orderKey = "b")
        val baseType = typeA.copy(templateBlocks = listOf(first, second))
        val root = change("template-root", "root", 1, typeA.id.value, json(TagDefinition.serializer(), baseType))
        val left = change("template-left", "device-a", 10, typeA.id.value,
            json(TagDefinition.serializer(), baseType.copy(templateBlocks = listOf(first.copy(parentId = second.id), second))),
            listOf(root.changeId))
        val right = change("template-right", "device-b", 20, typeA.id.value,
            json(TagDefinition.serializer(), baseType.copy(templateBlocks = listOf(first, second.copy(parentId = first.id)))),
            listOf(root.changeId))
        val local = baseCatalog() + tag(left)
        val remote = baseCatalog() + tag(right)
        val history = listOf(root, left, right)
        WorkspaceReplicaCatalogValidator.validate(local)
        WorkspaceReplicaCatalogValidator.validate(remote)
        val unsafe = WorkspaceReplicaMergePlanner.plan(local, remote, history, "2026-01-01T00:01:00Z") { _, _, _, _ -> "template-conflict" }
        assertFailsWith<IllegalArgumentException> {
            WorkspaceReplicaCatalogValidator.validate(unsafe.objects.map { WorkspaceReplicaObject(it.kind, it.snapshot) })
        }
        val resolved = resolve(local, remote, history)
        val reversed = resolve(remote, local, history)
        WorkspaceReplicaCatalogValidator.validate(resolved.objects.map { WorkspaceReplicaObject(it.kind, it.snapshot) })
        val type = resolved.objects.single { it.objectId.value == typeA.id.value }
        assertEquals(right.operations.single().payload, type.snapshot.payload)
        assertEquals(WorkspaceReplicaAction.MERGE, type.action)
        assertEquals(setOf(left.changeId, right.changeId), type.parentChangeIds.toSet())
        val conflict = resolved.conflicts.single()
        assertEquals(WorkspaceReplicaConflictReason.CATALOG_INVARIANT, conflict.reason)
        assertTrue(conflict.fields.isEmpty())
        assertEquals(left.operations.single().payload, conflict.localSnapshot.payload)
        assertEquals(right.operations.single().payload, conflict.remoteSnapshot.payload)
        assertEquals(resolved.conflicts, reversed.conflicts)
        assertEquals(resolved.objects.map { it.snapshot.payload }, reversed.objects.map { it.snapshot.payload })
    }

    /** 首选目录删除了另一侧独有笔记引用的类型时，尝试另一份完整目录并保留独有笔记。 */
    @Test
    fun triesOtherCatalogWhenPreferredSideBreaksExclusiveNoteReference() {
        val aRoot = change("a-root", "root", 1, typeA.id.value, json(TagDefinition.serializer(), typeA))
        val bRoot = change("b-root", "root", 2, typeB.id.value, json(TagDefinition.serializer(), typeB))
        val bDeleted = change("b-deleted", "device-a", 40, typeB.id.value, bRoot.operations.single().payload!!,
            listOf(bRoot.changeId), kind = ChangeKind.DELETE)
        val localNote = change("note-local", "device-a", 30, "only-local", json(Note.serializer(), note("Local", typeA.id)), operation = "replace-note")
        val remoteNote = change("note-remote", "device-b", 31, "only-remote", json(Note.serializer(), note("Remote", typeB.id)), operation = "replace-note")
        val local = baseCatalog() + listOf(tag(aRoot), tag(bDeleted, deleted = true), noteEntry(localNote))
        val remote = baseCatalog() + listOf(tag(aRoot), tag(bRoot), noteEntry(remoteNote))
        val resolved = resolve(local, remote, listOf(aRoot, bRoot, bDeleted, localNote, remoteNote))
        val keptType = resolved.objects.single { it.objectId.value == typeB.id.value }
        assertFalse(keptType.snapshot.header.deleted)
        assertEquals(WorkspaceReplicaAction.MERGE, keptType.action)
        assertEquals(2, resolved.objects.count { it.kind == ManifestObjectKind.NOTE })
        assertEquals(1, resolved.conflicts.size)
        WorkspaceReplicaCatalogValidator.validate(resolved.objects.map { WorkspaceReplicaObject(it.kind, it.snapshot) })
    }

    /** 两份目录各自缺少另一侧独有 Note 所需的活动类型时，不能通过丢弃 Note 或伪造旧版本掩盖不变量。 */
    @Test
    fun failsClosedWhenNeitherCatalogCanPreserveAllExclusiveObjects() {
        val aRoot = change("a-root", "root", 1, typeA.id.value, json(TagDefinition.serializer(), typeA))
        val bRoot = change("b-root", "root", 2, typeB.id.value, json(TagDefinition.serializer(), typeB))
        val aDeleted = change("a-deleted", "device-b", 40, typeA.id.value, aRoot.operations.single().payload!!,
            listOf(aRoot.changeId), kind = ChangeKind.DELETE)
        val bDeleted = change("b-deleted", "device-a", 30, typeB.id.value, bRoot.operations.single().payload!!,
            listOf(bRoot.changeId), kind = ChangeKind.DELETE)
        val localNote = change("note-local", "device-a", 31, "only-local", json(Note.serializer(), note("Local", typeA.id)), operation = "replace-note")
        val remoteNote = change("note-remote", "device-b", 41, "only-remote", json(Note.serializer(), note("Remote", typeB.id)), operation = "replace-note")
        val local = baseCatalog() + listOf(tag(aRoot), tag(bDeleted, deleted = true), noteEntry(localNote))
        val remote = baseCatalog() + listOf(tag(aDeleted, deleted = true), tag(bRoot), noteEntry(remoteNote))
        val before = local.toList()
        val error = assertFailsWith<IllegalStateException> {
            resolve(local, remote, listOf(aRoot, bRoot, aDeleted, bDeleted, localNote, remoteNote))
        }
        assertEquals("sync_schema_invalid", error.message)
        assertEquals(before, local)
        WorkspaceReplicaCatalogValidator.validate(local)
        WorkspaceReplicaCatalogValidator.validate(remote)
    }

    /** 原始副本本身不完整时不允许使用目录选择把损坏输入伪装成合法并发。 */
    @Test
    fun rejectsIndependentlyInvalidCatalogBeforeResolving() {
        val local = baseCatalog()
        val remote = local.filterNot { it.kind == ManifestObjectKind.PROPERTY }
        assertEquals("sync_schema_invalid", assertFailsWith<IllegalStateException> { resolve(local, remote, emptyList()) }.message)
    }

    /**
     * 通过真实目录冲突入口生成稳定规划。
     * @param local 本地完整候选。
     * @param remote 远端完整候选。
     * @param changes 两侧 Change 并集。
     * @param at 本轮设备时间，不参与完整冲突身份。
     * @return 通过全目录预验证的对象决定。
     */
    private fun resolve(local: List<WorkspaceReplicaObject>, remote: List<WorkspaceReplicaObject>, changes: List<ObjectChange>,
        at: String = "2026-01-01T00:01:00Z"): WorkspaceReplicaPlan =
        WorkspaceReplicaCatalogConflictResolver.plan(local, remote, changes, at) { _, id, left, right ->
            "conflict-${id.value}-${listOfNotNull(left?.value, right?.value).sorted().joinToString("-")}"
        }

    /** @return 真实根类型与基础共享属性构成的合法最小目录。 */
    private fun baseCatalog(): List<WorkspaceReplicaObject> = WorkspaceDefaultSchemas.propertyDefinitions().map {
        entry(ManifestObjectKind.PROPERTY, it.id.value, PropertyDefinition.serializer(), it)
    } + WorkspaceDefaultSchemas.noteTagDefinition().let { entry(ManifestObjectKind.TAG, it.id.value, TagDefinition.serializer(), it) }

    /**
     * 从完整 Tag Change 创建认证候选。
     * @param change 保存真实完整 Tag payload 的逻辑 Change。
     * @param deleted 是否为删除版本。
     * @return Tag 目录候选。
     */
    private fun tag(change: ObjectChange, deleted: Boolean = false): WorkspaceReplicaObject = entry(ManifestObjectKind.TAG,
        change.objectId.value, TagDefinition.serializer(), CanonicalJson.format.decodeFromJsonElement(TagDefinition.serializer(),
            requireNotNull(change.operations.single().payload)), change, deleted)

    /**
     * 从完整 Note Change 创建认证候选。
     * @param change 保存真实完整 Note payload 的逻辑 Change。
     * @return Note 目录候选。
     */
    private fun noteEntry(change: ObjectChange): WorkspaceReplicaObject = entry(ManifestObjectKind.NOTE, change.objectId.value,
        Note.serializer(), CanonicalJson.format.decodeFromJsonElement(Note.serializer(), requireNotNull(change.operations.single().payload)), change)

    /**
     * 构造一段正文，类型引用可用于检查独有对象在目录选择后是否仍然完整。
     * @param text 段落内容。
     * @param type 笔记直接类型，默认使用根 Note。
     * @return 测试领域 Note。
     */
    private fun note(text: String, type: TagId = WorkspaceDefaults.DEFAULT_NOTE_TAG_ID): Note = Note("Catalog note", listOf(type),
        blocks = listOf(Block(BlockId("body"), BlockTypes.PARAGRAPH, orderKey = "a",
            content = RichTextBlockContent(RichText(listOf(InlineRun(text)))))))

    /**
     * 构造保留头部身份和完整 payload 的对象候选。
     * @param T 领域类型。
     * @param kind manifest 类别。
     * @param id 真实稳定对象身份。
     * @param serializer 领域 serializer。
     * @param payload 完整领域对象。
     * @param head 该快照的逻辑 head，根目录测试定义可为空。
     * @param deleted 删除状态。
     * @return 可供预验证的完整候选。
     */
    private fun <T> entry(kind: ManifestObjectKind, id: String, serializer: KSerializer<T>, payload: T,
        head: ObjectChange? = null, deleted: Boolean = false): WorkspaceReplicaObject = WorkspaceReplicaObject(kind,
        ObjectSnapshot(SnapshotHeader(1, ObjectId(id), when (kind) {
            ManifestObjectKind.TAG -> ObjectType.TAG
            ManifestObjectKind.PROPERTY -> ObjectType.PROPERTY
            else -> ObjectType.NOTE
        }, ObjectRevision(head?.actorId ?: ActorId("root"), head?.hybridLogicalTime?.physicalTimeMillis ?: 0),
            head?.changeId, createdAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:${(head?.hybridLogicalTime?.physicalTimeMillis ?: 0).toString().padStart(2, '0')}Z",
            deleted = deleted), json(serializer, payload)))

    /**
     * 为真实对象构造包含完整替换操作的 Change。
     * @param id 稳定 Change 身份。
     * @param actor 设备身份。
     * @param time 确定性测试 HLC。
     * @param objectId 业务对象身份。
     * @param payload 完整业务 payload。
     * @param parents 完整逻辑父边。
     * @param kind 更新或删除语义。
     * @param operation 替换操作名称。
     * @return 真实纯合并协议 Change。
     */
    private fun change(id: String, actor: String, time: Long, objectId: String, payload: JsonElement,
        parents: List<ChangeId> = emptyList(), kind: ChangeKind = ChangeKind.UPDATE, operation: String = "replace-tag-definition") =
        ObjectChange(ChangeId(id), ObjectId(objectId), parents, ActorId(actor), HybridLogicalTime(time, 0, ActorId(actor)),
            null, TransactionId("transaction-$id"), kind, listOf(ChangeOperation(operation, payload = payload)))

    /**
     * 通过领域 serializer 生成协议 JSON，避免测试构造第二套 payload。
     * @param T 当前领域类型。
     * @param serializer 领域 serializer。
     * @param value 完整领域对象。
     * @return 协议 JSON。
     */
    private fun <T> json(serializer: KSerializer<T>, value: T): JsonElement = CanonicalJson.format.encodeToJsonElement(serializer, value)
}
