package com.oruke.tagloom.core.ai

import com.oruke.tagloom.core.agent.AgentToolName
import com.oruke.tagloom.core.external.ExternalCallException
import com.oruke.tagloom.core.model.AiAccessPolicy
import com.oruke.tagloom.core.model.Annotation
import com.oruke.tagloom.core.model.AnnotationId
import com.oruke.tagloom.core.model.AnnotationKinds
import com.oruke.tagloom.core.model.BaseTableBlockContent
import com.oruke.tagloom.core.model.BaseTableColumn
import com.oruke.tagloom.core.model.BaseTableColumnId
import com.oruke.tagloom.core.model.BaseTableColumnTypes
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.CodeBlockContent
import com.oruke.tagloom.core.model.DatabaseBlockContent
import com.oruke.tagloom.core.model.DatabaseId
import com.oruke.tagloom.core.model.InlineRun
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.NoteNormalizer
import com.oruke.tagloom.core.model.NotePropertyValue
import com.oruke.tagloom.core.model.NoteValidator
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.PropertyId
import com.oruke.tagloom.core.model.PropertyValueSource
import com.oruke.tagloom.core.model.RichText
import com.oruke.tagloom.core.model.RichTextBlockContent
import com.oruke.tagloom.core.model.TabBlockContent
import com.oruke.tagloom.core.model.TabsBlockContent
import com.oruke.tagloom.core.model.TagId
import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.sync.AgentThreadReplicaMerge
import com.oruke.tagloom.core.sync.MergeSide
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 验证阶段三细粒度块补丁、元数据更新、提案合并状态机与会话副本三方合并契约。
 */
class Stage3PatchAndProposalTest {
    /** 验证块补丁保留未触碰块稳定 ID、结构化子树、双链锚点并自动对账孤立加注。 */
    @Test
    fun patchNoteBlocksPreservesStableIdsSubtreesAndReconcilesAnnotations() {
        val annId = AnnotationId("ann-1")
        val initialNote = Note(
            title = "工作区双向同步设计",
            directTagIds = listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID, TagId("tag-spec")),
            aiAccessPolicy = AiAccessPolicy.REFERENCE_ONLY,
            defaultTabId = BlockId("blk-tab-1"),
            annotations = mapOf(
                annId to Annotation(
                    id = annId,
                    kind = AnnotationKinds.NOTE,
                    body = "批注详情",
                    createdAt = "2026-11-14T00:00:00Z",
                    updatedAt = "2026-11-14T00:00:00Z",
                    orphaned = false,
                ),
            ),
            blocks = listOf(
                Block(
                    id = BlockId("blk-sync-1"),
                    type = BlockTypes.PARAGRAPH,
                    orderKey = "0000000000",
                    content = RichTextBlockContent(
                        RichText(
                            listOf(
                                InlineRun(
                                    text = "原始首段带批注",
                                    annotationIds = listOf(annId),
                                ),
                            ),
                        ),
                    ),
                ),
                Block(
                    id = BlockId("blk-sync-2"),
                    type = BlockTypes.PARAGRAPH,
                    orderKey = "0000000001",
                    content = RichTextBlockContent(text("待替换第二段")),
                ),
                Block(
                    id = BlockId("blk-table-1"),
                    type = BlockTypes.BASE_TABLE,
                    orderKey = "0000000002",
                    content = BaseTableBlockContent(
                        title = text("核验矩阵"),
                        columns = listOf(
                            BaseTableColumn(
                                columnId = BaseTableColumnId("col-1"),
                                orderKey = "0000000000",
                                name = text("阶段"),
                                type = BaseTableColumnTypes.TEXT,
                            ),
                        ),
                        rows = emptyList(),
                    ),
                ),
                Block(
                    id = BlockId("blk-db-1"),
                    type = BlockTypes.DATABASE,
                    orderKey = "0000000003",
                    content = DatabaseBlockContent(databaseId = DatabaseId("db-fixed")),
                ),
                Block(
                    id = BlockId("blk-code-del"),
                    type = BlockTypes.CODE,
                    orderKey = "0000000004",
                    content = CodeBlockContent(language = "kotlin", text = "val obsolete = true"),
                ),
                Block(
                    id = BlockId("blk-tabs-root"),
                    type = BlockTypes.TABS,
                    orderKey = "0000000005",
                    content = TabsBlockContent,
                ),
                Block(
                    id = BlockId("blk-tab-1"),
                    parentId = BlockId("blk-tabs-root"),
                    type = BlockTypes.TAB,
                    orderKey = "0000000000",
                    content = TabBlockContent(text("默认页签")),
                ),
                Block(
                    id = BlockId("blk-tab-child-1"),
                    parentId = BlockId("blk-tab-1"),
                    type = BlockTypes.PARAGRAPH,
                    orderKey = "0000000000",
                    content = RichTextBlockContent(text("页签内部正文")),
                ),
            ),
        )

        var seq = 100
        val opsArgs = buildJsonObject {
            put(
                "operations",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("op", "replace_block")
                            put("blockId", "blk-sync-1")
                            put("markdown", "替换后首段（不再引用旧批注）")
                        },
                    )
                    add(
                        buildJsonObject {
                            put("op", "insert_after_block")
                            put("blockId", "blk-sync-2")
                            put("markdown", "紧跟在第二段之后的新增说明")
                        },
                    )
                    add(
                        buildJsonObject {
                            put("op", "prepend_blocks")
                            put("markdown", "> 顶部导语")
                        },
                    )
                    add(
                        buildJsonObject {
                            put("op", "append_blocks")
                            put("markdown", "- 尾部补充项")
                        },
                    )
                    add(
                        buildJsonObject {
                            put("op", "remove_block")
                            put("blockId", "blk-code-del")
                        },
                    )
                },
            )
        }
        val parsedOps = NoteFineGrainedPatchService.parseBlockOperations(opsArgs)
        val patched = NoteNormalizer.normalize(
            NoteFineGrainedPatchService.applyBlockOperations(
                note = initialNote,
                operations = parsedOps,
                importedAt = "2026-11-14T06:00:00Z",
                blockIdFactory = { BlockId("blk-gen-${seq++}") },
                tableIdFactory = { "tbl-gen-${seq++}" },
            ),
        )

        assertEquals(emptyList(), NoteValidator.validate(patched))
        val replacedFirst = patched.blocks.single { it.id == BlockId("blk-sync-1") && !it.deleted }
        assertTrue((replacedFirst.content as RichTextBlockContent).richText.plainText.contains("替换后首段"))
        assertNotNull(patched.blocks.singleOrNull { it.id == BlockId("blk-table-1") && !it.deleted })
        assertNotNull(patched.blocks.singleOrNull { it.id == BlockId("blk-db-1") && !it.deleted })
        assertNotNull(patched.blocks.singleOrNull { it.id == BlockId("blk-tab-child-1") && !it.deleted })
        val removed = patched.blocks.single { it.id == BlockId("blk-code-del") }
        assertTrue(removed.deleted)
        assertTrue(patched.annotations.getValue(annId).orphaned)
        assertFailsWith<ExternalCallException> {
            NoteFineGrainedPatchService.applyBlockOperations(
                note = patched,
                operations = listOf(
                    ParsedBlockPatchOperation(
                        kind = AgentProposalItemKind.REMOVE_BLOCK,
                        targetBlockId = BlockId("blk-tab-1"),
                    ),
                ),
                importedAt = "2026-11-14T06:00:00Z",
                blockIdFactory = { BlockId("blk-x") },
                tableIdFactory = { "tbl-x" },
            )
        }
    }

    /** 验证仅采纳部分变更项时精确应用勾选子集且绝不篡改 AI 访问策略。 */
    @Test
    fun partialAcceptedProposalItemsApplyCleanlyWithoutMutatingAiPolicy() {
        val base = Note(
            title = "旧标题",
            directTagIds = listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID, TagId("tag-old")),
            propertyValues = mapOf(
                PropertyId("prop-status") to NotePropertyValue(
                    value = JsonPrimitive("draft"),
                    source = PropertyValueSource.USER,
                    overridden = true,
                    updatedAt = "2026-11-14T00:00:00Z",
                ),
                PropertyId("prop-owner") to NotePropertyValue(
                    value = JsonPrimitive("Alice"),
                    source = PropertyValueSource.USER,
                    overridden = true,
                    updatedAt = "2026-11-14T00:00:00Z",
                ),
            ),
            aiAccessPolicy = AiAccessPolicy.REFERENCE_ONLY,
            blocks = listOf(
                Block(
                    id = BlockId("blk-1"),
                    type = BlockTypes.PARAGRAPH,
                    orderKey = "0000000000",
                    content = RichTextBlockContent(text("第一段保持原样")),
                ),
                Block(
                    id = BlockId("blk-2"),
                    type = BlockTypes.PARAGRAPH,
                    orderKey = "0000000001",
                    content = RichTextBlockContent(text("第二段旧内容")),
                ),
            ),
        )

        val items = listOf(
            AgentProposalItem(
                id = "item-title",
                kind = AgentProposalItemKind.TITLE,
                toolLabel = "update_note_metadata",
                label = "标题",
                newTitle = "被跳过的新标题",
                accepted = false,
            ),
            AgentProposalItem(
                id = "item-prop-status",
                kind = AgentProposalItemKind.PROPERTY,
                toolLabel = "update_note_metadata",
                label = "属性 · 状态",
                propertyId = PropertyId("prop-status"),
                newPropertyValue = JsonPrimitive("accepted"),
                accepted = true,
            ),
            AgentProposalItem(
                id = "item-prop-clear",
                kind = AgentProposalItemKind.PROPERTY,
                toolLabel = "update_note_metadata",
                label = "属性 · 清空负责人",
                propertyId = PropertyId("prop-owner"),
                newPropertyValue = JsonNull,
                accepted = true,
            ),
            AgentProposalItem(
                id = "item-blk-2",
                kind = AgentProposalItemKind.REPLACE_BLOCK,
                toolLabel = "patch_note_blocks",
                label = "替换段落 · blk-2",
                targetBlockId = BlockId("blk-2"),
                markdown = "第二段新内容",
                accepted = true,
            ),
        )

        val acceptedOnly = items.filter { it.accepted }
        val result = NoteFineGrainedPatchService.applyAcceptedProposalItems(
            baseNote = base,
            acceptedItems = acceptedOnly,
            updatedAt = "2026-11-14T06:05:00Z",
            blockIdFactory = { BlockId("blk-generated") },
            tableIdFactory = { "tbl-generated" },
        )

        assertEquals("旧标题", result.title)
        assertEquals(JsonPrimitive("accepted"), result.propertyValues[PropertyId("prop-status")]?.value)
        assertFalse(PropertyId("prop-owner") in result.propertyValues)
        assertEquals(AiAccessPolicy.REFERENCE_ONLY, result.aiAccessPolicy)
        assertEquals("第一段保持原样", (result.blocks.single { it.id == BlockId("blk-1") }.content as RichTextBlockContent).richText.plainText)
        assertEquals("第二段新内容", (result.blocks.single { it.id == BlockId("blk-2") }.content as RichTextBlockContent).richText.plainText)
    }

    /** 构造单文本 Run 的 [RichText]。 */
    private fun text(content: String): RichText = RichText(listOf(InlineRun(text = content)))
}
