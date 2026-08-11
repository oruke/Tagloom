package com.oruke.tagloom.core.query

import com.oruke.tagloom.core.model.DatabaseDefinition
import com.oruke.tagloom.core.model.DatabaseId
import com.oruke.tagloom.core.model.DatabaseProtocolValidator
import com.oruke.tagloom.core.model.DefaultValuePolicy
import com.oruke.tagloom.core.model.FieldRef
import com.oruke.tagloom.core.model.FieldRefKinds
import com.oruke.tagloom.core.model.FilterExpr
import com.oruke.tagloom.core.model.FilterExprKinds
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.NotePropertyValue
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.PropertyId
import com.oruke.tagloom.core.model.PropertyTypes
import com.oruke.tagloom.core.model.PropertyValueSource
import com.oruke.tagloom.core.model.QueryPredicateOperators
import com.oruke.tagloom.core.model.QuerySpec
import com.oruke.tagloom.core.model.SortDirection
import com.oruke.tagloom.core.model.SortSpec
import com.oruke.tagloom.core.model.SystemFieldNames
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.core.model.TagId
import com.oruke.tagloom.core.model.TagPropertyBinding
import com.oruke.tagloom.core.model.ViewDefinition
import com.oruke.tagloom.core.model.ViewField
import com.oruke.tagloom.core.model.ViewId
import com.oruke.tagloom.core.model.ViewPresentation
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 验证 Database/View 协议只投影 Note，并复用对象类型与有效属性语义。
 */
class DatabaseProjectionServiceTest {
    /** 根 Note 类型。 */
    private val rootId = TagId("note")

    /** Database 来源父类型。 */
    private val projectId = TagId("project")

    /** 来源后代类型。 */
    private val taskId = TagId("task")

    /** 测试状态属性。 */
    private val statusId = PropertyId("status")

    /**
     * 验证后代 Note 会进入来源集合，动态默认属性参与过滤，并按系统字段稳定排序。
     */
    @Test
    fun projectionUsesInheritedTypeAndEffectivePropertyValues() {
        val database = database()
        val view = view(
            filter = FilterExpr(
                kind = FilterExprKinds.PREDICATE,
                field = FieldRef(FieldRefKinds.PROPERTY, propertyId = statusId),
                operator = QueryPredicateOperators.EQ,
                value = JsonPrimitive("active"),
            ),
        )
        val projection = DatabaseProjectionService(rootId).project(
            database = database,
            view = view,
            notes = listOf(
                projectionNote("note-z", "Zeta", taskId),
                projectionNote("note-a", "Alpha", taskId),
                projectionNote("note-done", "Done", taskId, "done"),
            ),
            tags = tags(),
            properties = properties(),
        )

        assertEquals(listOf("Zeta", "Alpha"), projection.rows.map { row -> row.title })
        assertEquals(JsonPrimitive("active"), projection.rows.first().values[FieldRef(FieldRefKinds.PROPERTY, propertyId = statusId)])
        assertEquals(2, projection.columns.size)
        assertTrue(projection.columns.all { column -> column.writable })
    }

    /**
     * 验证精确来源不会误收后代，并且未知展示/过滤节点只进入只读兼容状态。
     */
    @Test
    fun exactSourceAndUnknownProtocolRemainConservative() {
        val database = database().copy(includeDescendants = false)
        val unknownView = view().copy(
            query = QuerySpec(filter = FilterExpr(kind = "future-filter")),
            presentation = ViewPresentation(kind = "timeline"),
        )
        val projection = DatabaseProjectionService(rootId).project(
            database = database,
            view = unknownView,
            notes = listOf(projectionNote("note-task", "Task", taskId)),
            tags = tags(),
            properties = properties(),
        )
        val issues = DatabaseProtocolValidator.validate(unknownView)

        assertTrue(projection.rows.isEmpty())
        assertTrue(issues.any { issue -> issue.code == "unknown_filter_kind" && issue.readOnlyCompatibility })
        assertTrue(issues.any { issue -> issue.code == "unknown_presentation_kind" && issue.readOnlyCompatibility })
    }

    /**
     * 创建测试 Database。
     *
     * @return 来源为 Project、创建类型为 Task 的定义。
     */
    private fun database(): DatabaseDefinition = DatabaseDefinition(
        id = DatabaseId("database-projects"),
        name = "项目数据集",
        sourceTypeId = projectId,
        creationTypeId = taskId,
        defaultViewId = ViewId("view-table"),
    )

    /**
     * 创建带标题和状态列的 Table View。
     *
     * @param filter 查询过滤节点。
     * @return 可投影的视图定义。
     */
    private fun view(filter: FilterExpr = FilterExpr()): ViewDefinition = ViewDefinition(
        id = ViewId("view-table"),
        databaseId = DatabaseId("database-projects"),
        name = "全部任务",
        orderKey = "a0",
        query = QuerySpec(
            filter = filter,
            sorts = listOf(
                SortSpec(
                    field = FieldRef(FieldRefKinds.SYSTEM, systemField = SystemFieldNames.TITLE),
                    direction = SortDirection.DESCENDING,
                ),
            ),
        ),
        presentation = ViewPresentation(
            fields = listOf(
                ViewField(FieldRef(FieldRefKinds.SYSTEM, systemField = SystemFieldNames.TITLE), "a0"),
                ViewField(FieldRef(FieldRefKinds.PROPERTY, propertyId = statusId), "a1"),
            ),
        ),
    )

    /**
     * 创建测试 Note 投影输入。
     *
     * @param id Note 对象 ID。
     * @param title Note 标题。
     * @param typeId 唯一应用类型。
     * @param status 可选显式状态值。
     * @return 已确认 Note 摘要。
     */
    private fun projectionNote(
        id: String,
        title: String,
        typeId: TagId,
        status: String? = null,
    ): ProjectionNote = ProjectionNote(
        id = ObjectId(id),
        note = Note(
            title = title,
            directTagIds = listOf(rootId, typeId),
            propertyValues = status?.let { value ->
                mapOf(
                    statusId to NotePropertyValue(
                        value = JsonPrimitive(value),
                        source = PropertyValueSource.USER,
                        overridden = true,
                        updatedAt = "2026-08-10T00:00:00Z",
                    ),
                )
            }.orEmpty(),
        ),
    )

    /**
     * 创建根、Project 与 Task 类型图。
     *
     * @return 父级在前的标签定义。
     */
    private fun tags(): List<TagDefinition> = listOf(
        TagDefinition(rootId, "Note"),
        TagDefinition(
            id = projectId,
            name = "Project",
            parentTagIds = listOf(rootId),
            propertyBindings = listOf(
                TagPropertyBinding(
                    propertyId = statusId,
                    defaultValue = JsonPrimitive("active"),
                    defaultPolicy = DefaultValuePolicy.DYNAMIC,
                ),
            ),
        ),
        TagDefinition(taskId, "Task", parentTagIds = listOf(projectId)),
    )

    /**
     * 创建状态属性定义。
     *
     * @return 单选状态属性集合。
     */
    private fun properties(): List<PropertyDefinition> = listOf(
        PropertyDefinition(statusId, "状态", PropertyTypes.SELECT),
    )
}
