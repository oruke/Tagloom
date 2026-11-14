package com.oruke.tagloom.core.domain.tag

import com.oruke.tagloom.core.model.AiAccessPolicy
import com.oruke.tagloom.core.model.FieldRef
import com.oruke.tagloom.core.model.FieldRefKinds
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.SystemDatabaseDefinitions
import com.oruke.tagloom.core.model.SystemFieldNames
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.core.model.TagId
import com.oruke.tagloom.core.query.DatabaseProjectionService
import com.oruke.tagloom.core.query.ProjectionNote
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 验证三态 `AiAccessPolicy` 多父继承最严格收敛、单篇覆盖、抗环遍历以及系统内置「全部笔记」投影自排除。
 */
class AiAccessPolicyResolverTest {
    private val rootId = WorkspaceDefaults.DEFAULT_NOTE_TAG_ID
    private val rootDef = TagDefinition(rootId, "笔记")
    private val resolver = AiAccessPolicyResolver(rootId)

    /** 验证多父继承链遵循 `LOCKED > REFERENCE_ONLY > ALLOW` 最严格优先与单篇显式覆盖规则。 */
    @Test
    fun resolvesMultiParentStrictestWinsAndSupportsNoteExplicitOverride() {
        val publicParent = TagDefinition(TagId("tag-public"), "公开资料", aiAccessPolicy = AiAccessPolicy.ALLOW)
        val refParent = TagDefinition(TagId("tag-journal"), "日记随想", aiAccessPolicy = AiAccessPolicy.REFERENCE_ONLY)
        val lockedGrandParent = TagDefinition(TagId("tag-vault"), "机密凭据", aiAccessPolicy = AiAccessPolicy.LOCKED)
        val childOfRefAndPublic = TagDefinition(
            id = TagId("tag-weekly"),
            name = "周记",
            parentTagIds = listOf(publicParent.id, refParent.id),
            aiAccessPolicy = AiAccessPolicy.ALLOW,
        )
        val childUnderVault = TagDefinition(
            id = TagId("tag-incident"),
            name = "故障应急",
            parentTagIds = listOf(childOfRefAndPublic.id, lockedGrandParent.id),
            aiAccessPolicy = AiAccessPolicy.ALLOW,
        )
        val tags = listOf(
            rootDef,
            publicParent,
            refParent,
            lockedGrandParent,
            childOfRefAndPublic,
            childUnderVault,
        ).associateBy(TagDefinition::id)

        val weeklyResolved = resolver.resolveForNote(Note("第45周复盘", listOf(childOfRefAndPublic.id)), tags)
        assertEquals(AiAccessPolicy.REFERENCE_ONLY, weeklyResolved.effectivePolicy)
        assertFalse(weeklyResolved.overriddenOnNote)
        assertEquals(listOf(refParent.id), weeklyResolved.inheritedFromTagIds)

        val incidentResolved = resolver.resolveForNote(Note("恢复预案", listOf(childUnderVault.id)), tags)
        assertEquals(AiAccessPolicy.LOCKED, incidentResolved.effectivePolicy)
        assertFalse(incidentResolved.overriddenOnNote)
        assertEquals(listOf(lockedGrandParent.id), incidentResolved.inheritedFromTagIds)

        val overriddenAllow = resolver.resolveForNote(
            Note("公开演练模板", listOf(childUnderVault.id), aiAccessPolicy = AiAccessPolicy.ALLOW),
            tags,
        )
        assertEquals(AiAccessPolicy.ALLOW, overriddenAllow.effectivePolicy)
        assertTrue(overriddenAllow.overriddenOnNote)
        assertTrue(overriddenAllow.inheritedFromTagIds.isEmpty())
    }

    /** 验证异常含环标签图在解析时有界终止且不会降级漏掉严格策略，并准确计算类型策略变更影响面。 */
    @Test
    fun resistsTagGraphCyclesAndPreviewsDescendantInheritanceImpact() {
        val tagA = TagDefinition(TagId("cycle-a"), "A", parentTagIds = listOf(TagId("cycle-b")), aiAccessPolicy = AiAccessPolicy.ALLOW)
        val tagB = TagDefinition(TagId("cycle-b"), "B", parentTagIds = listOf(TagId("cycle-a")), aiAccessPolicy = AiAccessPolicy.LOCKED)
        val child = TagDefinition(TagId("cycle-c"), "C", parentTagIds = listOf(tagA.id), aiAccessPolicy = AiAccessPolicy.ALLOW)
        val tags = mapOf(
            rootId to rootDef,
            tagA.id to tagA,
            tagB.id to tagB,
            child.id to child,
        )

        val cyclicResolved = resolver.resolveForNote(Note("环路笔记", listOf(child.id)), tags)
        assertEquals(AiAccessPolicy.LOCKED, cyclicResolved.effectivePolicy)

        val cleanParent = TagDefinition(TagId("type-meeting"), "会议纪要", aiAccessPolicy = AiAccessPolicy.ALLOW)
        val cleanChild = TagDefinition(TagId("type-retro"), "复盘会", parentTagIds = listOf(cleanParent.id))
        val cleanTags = mapOf(
            rootId to rootDef,
            cleanParent.id to cleanParent,
            cleanChild.id to cleanChild,
        )
        val notes = listOf(
            ObjectId("n-1") to Note("例会1", listOf(cleanParent.id)),
            ObjectId("n-2") to Note("复盘1", listOf(cleanChild.id)),
            ObjectId("n-3") to Note("已单独禁读", listOf(cleanChild.id), aiAccessPolicy = AiAccessPolicy.LOCKED),
        )
        val impact = resolver.previewTagImpact(cleanParent.id, AiAccessPolicy.REFERENCE_ONLY, cleanTags, notes)
        assertEquals(AiAccessPolicy.REFERENCE_ONLY, impact.effectiveTypePolicy)
        assertEquals(setOf(cleanParent.id, cleanChild.id), impact.descendantTagIds)
        assertEquals(2, impact.inheritingNoteCount)
        assertEquals(2, impact.changedNoteCount)
    }

    /** 验证系统内置「全部笔记」Database 保护常量、有效策略投影列与系统页自身自动排除。 */
    @Test
    fun protectsBuiltInAllNotesPageAndExcludesSelfFromProjection() {
        assertTrue(SystemDatabaseDefinitions.isProtectedSystemPage(SystemDatabaseDefinitions.ALL_NOTES_PAGE_ID))
        assertTrue(SystemDatabaseDefinitions.isProtectedSystemDatabase(SystemDatabaseDefinitions.ALL_NOTES_DATABASE_ID))

        val db = SystemDatabaseDefinitions.createAllNotesDatabase()
        val tableView = SystemDatabaseDefinitions.createAllNotesView()
        val projectionService = DatabaseProjectionService(rootId)
        val projected = projectionService.project(
            database = db,
            view = tableView,
            notes = listOf(
                ProjectionNote(
                    id = SystemDatabaseDefinitions.ALL_NOTES_PAGE_ID,
                    note = Note("全部笔记", listOf(rootId)),
                    createdAt = "2026-11-13T00:00:00Z",
                    updatedAt = "2026-11-13T00:00:00Z",
                ),
                ProjectionNote(
                    id = ObjectId("user-note-1"),
                    note = Note("用户真实笔记", listOf(rootId), aiAccessPolicy = AiAccessPolicy.REFERENCE_ONLY),
                    createdAt = "2026-11-13T01:00:00Z",
                    updatedAt = "2026-11-13T02:00:00Z",
                ),
            ),
            tags = listOf(rootDef),
            properties = emptyList(),
        )
        assertEquals(listOf(ObjectId("user-note-1")), projected.rows.map { it.noteId })
        val aiPolicyField = FieldRef(kind = FieldRefKinds.SYSTEM, systemField = SystemFieldNames.AI_ACCESS_POLICY)
        assertEquals(JsonPrimitive(AiAccessPolicy.REFERENCE_ONLY.name), projected.rows.single().values[aiPolicyField])
    }
}
