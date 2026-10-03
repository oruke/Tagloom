package com.oruke.tagloom.automation

import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import com.oruke.tagloom.workspace.*
import kotlinx.serialization.json.*

/** 在真实隔离工作区准备固定归档，用于验证 Agent 经 MCP 组合查询、属性、代码和双链的能力。 */
internal object McpReadWorkflowScenario {
    /** 已完成、计划中和取消状态的稳定选项定义。 */
    private val statusId = PropertyId("mcp-evaluation-status")
    /** 任务工作量，仅用脱敏整数。 */
    private val effortId = PropertyId("mcp-evaluation-effort")
    /** 与界面本地化无关的固定目标日期。 */
    private val dateId = PropertyId("mcp-evaluation-date")
    /** 继承状态默认值的任务类型。 */
    private val taskType = TagId("mcp-evaluation-task")

    /**
     * 通过生产服务确认 schema、笔记及保存查询；评估阶段只允许读取这些固定资料。
     * @param session 当前测试的真实已解锁工作区。
     */
    suspend fun seed(session: DesktopWorkspaceSession) {
        // 阶段 1：建立真实继承和稳定选项，不依赖调用方猜测属性 ID 或选项标签。
        session.savePropertyDefinition(PropertyDefinition(statusId, "Delivery status", PropertyTypes.SELECT,
            options = listOf(PropertyOption("planned", "Planned"), PropertyOption("done", "Confirmed"),
                PropertyOption("canceled", "Canceled"))))
        session.savePropertyDefinition(PropertyDefinition(effortId, "Effort", PropertyTypes.NUMBER))
        session.savePropertyDefinition(PropertyDefinition(dateId, "Target date", PropertyTypes.DATE))
        val baseType = TagId("mcp-evaluation-item")
        session.saveTagDefinition(TagDefinition(baseType, "Delivery item", propertyBindings = listOf(
            TagPropertyBinding(statusId, defaultValue = JsonPrimitive("planned"), defaultPolicy = DefaultValuePolicy.SNAPSHOT),
            TagPropertyBinding(effortId), TagPropertyBinding(dateId),
        )))
        session.saveTagDefinition(TagDefinition(taskType, "Release task", parentTagIds = listOf(baseType)))
        // 阶段 2：固定归档有完成项、取消项和依赖关系；另一个周期的数据用于检验范围判断。
        val migration = task(session, "Storage migration", "done", 5, "2026-02-07", listOf(
            Block(BlockId("evaluation-code"), BlockTypes.CODE, orderKey = "a", content = CodeBlockContent("kotlin", "val sample = 1")),
        ))
        val client = task(session, "Client adapter", "done", 3, "2026-02-08")
        val proof = task(session, "Storage proof", "done", 2, "2026-02-06")
        val review = task(session, "Release review", "planned", 1, "2026-02-10", references(listOf(migration.id, client.id)))
        val polish = task(session, "Deferred polish", "canceled", 8, "2026-02-09")
        session.saveNote(null, Note("Release record", listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID),
            blocks = references(listOf(migration.id, client.id, proof.id, review.id, polish.id))))
        task(session, "Next cycle", "planned", 99, "2026-04-01")
        // 阶段 3：保存查询由真实索引解释，后续答案必须从 MCP 返回值推导。
        session.saveQuery(SavedQuery("Confirmed release work", tagIds = listOf(taskType),
            propertyFilters = listOf(SavedQueryPropertyFilter(statusId, "\"done\""))))
    }

    /**
     * 确认一篇带类型、属性和可选依赖的固定任务。
     * @param session 生产工作区服务。
     * @param title 脱敏任务标题。
     * @param status 已确认的选项 ID。
     * @param effort 固定工作量。
     * @param date ISO 目标日期。
     * @param blocks 代码或结构化依赖块。
     * @return 已确认笔记及其随机对象 ID。
     */
    private suspend fun task(session: DesktopWorkspaceSession, title: String, status: String, effort: Int,
                             date: String, blocks: List<Block> = emptyList()): DesktopStoredNote {
        val values = mapOf(statusId to JsonPrimitive(status), effortId to JsonPrimitive(effort), dateId to JsonPrimitive(date))
            .mapValues { (_, value) -> NotePropertyValue(value, PropertyValueSource.USER, updatedAt = "2026-02-01T00:00:00Z") }
        return session.saveNote(null, Note(title, listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID, taskType),
            propertyValues = values, blocks = blocks))
    }

    /**
     * 构造固定归档与任务间的结构化链接，由生产索引派生双链。
     * @param ids 已确认的目标笔记 ID。
     * @return 顺序稳定且没有明文路径的引用块。
     */
    private fun references(ids: List<ObjectId>): List<Block> = ids.mapIndexed { index, id ->
        Block(BlockId("evaluation-reference-$index"), BlockTypes.NOTE_REFERENCE, orderKey = ('a' + index).toString(),
            content = NoteReferenceBlockContent(id))
    }
}
