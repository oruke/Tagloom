package com.oruke.tagloom.index

import com.oruke.tagloom.core.crypto.SecretKeyKind
import com.oruke.tagloom.core.crypto.SecretKeyMaterial
import com.oruke.tagloom.core.index.IndexLinkTypes
import com.oruke.tagloom.core.index.IndexedNoteLink
import com.oruke.tagloom.core.index.NoteIndexDocument
import com.oruke.tagloom.core.index.SearchIndexCheckpoint
import com.oruke.tagloom.core.index.SearchIndexQuery
import com.oruke.tagloom.core.model.AiAccessPolicy
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.TagId
import com.oruke.tagloom.index.jvm.JvmSqliteIndexEngine
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 验证加密 SQLite 派生索引在分页前执行 `AiAccessPolicy` 过滤、统计被排除受保护篇数及双链源头策略隔离。
 */
class AiPolicySqliteIndexFilterTest {
    /** 验证高分 `LOCKED` / `REFERENCE_ONLY` 笔记不会挤占 `limit = 1` 分页窗口，且反链自动排除受保护来源笔记。 */
    @Test
    fun filtersProtectedNotesBeforePaginationAndIsolatesBacklinks() = runBlocking {
        val dbPath = Files.createTempDirectory("tagloom-ai-policy-index").resolve("local.sqlite")
        val key = SecretKeyMaterial.from(SecretKeyKind.INDEX, ByteArray(32) { index -> (index + 7).toByte() })
        val engine = JvmSqliteIndexEngine.open(dbPath, key)
        try {
            val targetId = ObjectId("note-target")
            val targetBlock = BlockId("blk-target")
            val docs = listOf(
                NoteIndexDocument(
                    noteId = ObjectId("note-locked-top"),
                    revision = "dev:1",
                    title = "architecture secret architecture",
                    bodyText = "architecture architecture key rotation",
                    updatedAt = "2026-11-14T00:04:00Z",
                    directTagIds = listOf(TagId("note")),
                    propertyValues = emptyMap(),
                    outboundLinks = listOf(
                        IndexedNoteLink(
                            sourceBlockId = BlockId("b-lock"),
                            targetNoteId = targetId,
                            targetBlockId = targetBlock,
                            linkType = IndexLinkTypes.INLINE_LINK,
                        ),
                    ),
                    deleted = false,
                    aiAccessPolicy = AiAccessPolicy.LOCKED,
                ),
                NoteIndexDocument(
                    noteId = ObjectId("note-ref-only"),
                    revision = "dev:1",
                    title = "architecture private draft",
                    bodyText = "architecture draft memo",
                    updatedAt = "2026-11-14T00:03:00Z",
                    directTagIds = listOf(TagId("note")),
                    propertyValues = emptyMap(),
                    outboundLinks = listOf(
                        IndexedNoteLink(
                            sourceBlockId = BlockId("b-ref"),
                            targetNoteId = targetId,
                            targetBlockId = targetBlock,
                            linkType = IndexLinkTypes.INLINE_LINK,
                        ),
                    ),
                    deleted = false,
                    aiAccessPolicy = AiAccessPolicy.REFERENCE_ONLY,
                ),
                NoteIndexDocument(
                    noteId = ObjectId("note-allow"),
                    revision = "dev:1",
                    title = "public overview",
                    bodyText = "mentions architecture once",
                    updatedAt = "2026-11-14T00:02:00Z",
                    directTagIds = listOf(TagId("note")),
                    propertyValues = emptyMap(),
                    outboundLinks = listOf(
                        IndexedNoteLink(
                            sourceBlockId = BlockId("b-allow"),
                            targetNoteId = targetId,
                            targetBlockId = targetBlock,
                            linkType = IndexLinkTypes.INLINE_LINK,
                        ),
                    ),
                    deleted = false,
                    aiAccessPolicy = AiAccessPolicy.ALLOW,
                ),
                NoteIndexDocument(
                    noteId = targetId,
                    revision = "dev:1",
                    title = "target specification",
                    bodyText = "target body",
                    updatedAt = "2026-11-14T00:01:00Z",
                    directTagIds = listOf(TagId("note")),
                    propertyValues = emptyMap(),
                    outboundLinks = emptyList(),
                    deleted = false,
                    aiAccessPolicy = AiAccessPolicy.ALLOW,
                ),
            )
            engine.rebuild(docs, SearchIndexCheckpoint("manifest-policy", 4))

            val aiFilteredHits = engine.search(
                SearchIndexQuery(
                    text = "architecture",
                    limit = 1,
                    allowedAiPolicies = setOf(AiAccessPolicy.ALLOW),
                ),
            )
            assertEquals(listOf(ObjectId("note-allow")), aiFilteredHits.map { it.noteId })

            val skippedCount = engine.countExcludedByPolicy(
                SearchIndexQuery(
                    text = "architecture",
                    allowedAiPolicies = setOf(AiAccessPolicy.ALLOW),
                ),
            )
            assertEquals(2, skippedCount)

            val aiBacklinks = engine.backlinks(
                targetNoteId = targetId,
                targetBlockId = targetBlock,
                allowedAiPolicies = setOf(AiAccessPolicy.ALLOW),
            )
            assertEquals(listOf(ObjectId("note-allow")), aiBacklinks.map { it.sourceNoteId })

            val unrestrictedBacklinks = engine.backlinks(targetNoteId = targetId, targetBlockId = targetBlock)
            assertEquals(3, unrestrictedBacklinks.size)
        } finally {
            engine.close()
        }
    }
}
