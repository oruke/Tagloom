package com.oruke.tagloom.core.model

import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import kotlinx.serialization.json.JsonPrimitive

/**
 * 定义系统内置且受防误删保护的「全部笔记」根类型 Database 投影规约。
 *
 * 绑定工作区根类型 [WorkspaceDefaults.DEFAULT_NOTE_TAG_ID]（`includeDescendants = true`），
 * 通过既有 `DatabaseProjectionService` 全量查询活动笔记，并自动排除系统容器页自身行以避免自指循环。
 */
object SystemDatabaseDefinitions {
    /** 系统内置「全部笔记」页面身份，不允许作为普通笔记删除。 */
    val ALL_NOTES_PAGE_ID: ObjectId = WorkspaceDefaults.BUILTIN_ALL_NOTES_PAGE_ID

    /** 系统内置「全部笔记」Database 稳定标识。 */
    val ALL_NOTES_DATABASE_ID: DatabaseId = DatabaseId("builtin-all-notes-database")

    /** 系统内置「全部笔记」默认表格视图标识。 */
    val ALL_NOTES_TABLE_VIEW_ID: ViewId = ViewId("builtin-all-notes-view-table")

    /** 系统内置「全部笔记」看板视图标识。 */
    val ALL_NOTES_KANBAN_VIEW_ID: ViewId = ViewId("builtin-all-notes-view-kanban")

    /** 系统内置「全部笔记」画廊视图标识。 */
    val ALL_NOTES_GALLERY_VIEW_ID: ViewId = ViewId("builtin-all-notes-view-gallery")

    /**
     * 判断给定页面或笔记对象 ID 是否为受保护的系统内置页面。
     *
     * @param objectId 待检查对象 ID。
     * @return 命中「全部笔记」内置容器身份时为真。
     */
    fun isProtectedSystemPage(objectId: ObjectId): Boolean = objectId == ALL_NOTES_PAGE_ID

    /**
     * 判断给定 Database ID 是否为受保护的系统默认「全部笔记」数据集。
     *
     * @param databaseId 待检查数据集 ID。
     * @return 命中系统内置「全部笔记」数据集时为真。
     */
    fun isProtectedSystemDatabase(databaseId: DatabaseId): Boolean = databaseId == ALL_NOTES_DATABASE_ID

    /**
     * 构造绑定根 `Note` 类型及其全部子类型的系统默认「全部笔记」[DatabaseDefinition]。
     *
     * @param name 当前语言环境下的展示名称（默认 `"全部笔记"`）。
     * @param defaultViewId 当前激活的默认视图 ID。
     * @return 防误删的根类型全量投影数据集定义。
     */
    fun createAllNotesDatabase(
        name: String = "全部笔记",
        defaultViewId: ViewId = ALL_NOTES_TABLE_VIEW_ID,
    ): DatabaseDefinition = DatabaseDefinition(
        id = ALL_NOTES_DATABASE_ID,
        name = name.ifBlank { "全部笔记" },
        sourceTypeId = WorkspaceDefaults.DEFAULT_NOTE_TAG_ID,
        includeDescendants = true,
        creationTypeId = WorkspaceDefaults.DEFAULT_NOTE_TAG_ID,
        defaultViewId = defaultViewId,
    )

    /**
     * 构造「全部笔记」页面动态筛选与多视图切换所需的标准 [ViewDefinition]。
     *
     * @param presentationKind 视图展示形态（`table` / `kanban` / `gallery`）。
     * @param searchQuery 可选全文过滤关键字。
     * @param filterTagId 可选笔记类型（含子类型闭包）筛选 ID。
     * @param sortByUpdatedDescending 为真时按更新时间降序排列，否则按标题升序排列。
     * @param availableProperties 当前工作区可用属性定义，用于自动选择看板单选分组字段及常用展示列。
     * @return 可直接传入 `DatabaseProjectionService.project` 的完整视图定义。
     */
    fun createAllNotesView(
        presentationKind: String = ViewPresentationKinds.TABLE,
        searchQuery: String = "",
        filterTagId: TagId? = null,
        sortByUpdatedDescending: Boolean = true,
        availableProperties: Collection<PropertyDefinition> = emptyList(),
    ): ViewDefinition {
        val clauses = buildList {
            val trimmedSearch = searchQuery.trim()
            if (trimmedSearch.isNotEmpty()) {
                add(FilterExpr(kind = FilterExprKinds.FULL_TEXT, text = trimmedSearch))
            }
            if (filterTagId != null && filterTagId != WorkspaceDefaults.DEFAULT_NOTE_TAG_ID) {
                add(
                    FilterExpr(
                        kind = FilterExprKinds.PREDICATE,
                        field = FieldRef(kind = FieldRefKinds.OBJECT_TYPE),
                        operator = QueryPredicateOperators.IS_DESCENDANT_OF,
                        value = JsonPrimitive(filterTagId.value),
                    ),
                )
            }
        }
        val filter = when (clauses.size) {
            0 -> FilterExpr(kind = FilterExprKinds.TRUE)
            1 -> clauses.single()
            else -> FilterExpr(kind = FilterExprKinds.ALL, children = clauses)
        }
        val sort = if (sortByUpdatedDescending) {
            SortSpec(
                field = FieldRef(kind = FieldRefKinds.SYSTEM, systemField = SystemFieldNames.UPDATED_AT),
                direction = SortDirection.DESCENDING,
            )
        } else {
            SortSpec(
                field = FieldRef(kind = FieldRefKinds.SYSTEM, systemField = SystemFieldNames.TITLE),
                direction = SortDirection.ASCENDING,
            )
        }
        val kanbanGroupPropertyId = availableProperties
            .firstOrNull { property -> property.type == PropertyTypes.SELECT }
            ?.id
            ?: WorkspaceDefaults.DEFAULT_SELECT_PROPERTY_ID
        val visibleFields = buildList {
            add(ViewField(field = FieldRef(kind = FieldRefKinds.OBJECT_TYPE), orderKey = "a0"))
            add(
                ViewField(
                    field = FieldRef(kind = FieldRefKinds.SYSTEM, systemField = SystemFieldNames.AI_ACCESS_POLICY),
                    orderKey = "a1",
                ),
            )
            if (availableProperties.any { it.id == WorkspaceDefaults.DEFAULT_TAGS_PROPERTY_ID }) {
                add(
                    ViewField(
                        field = FieldRef(
                            kind = FieldRefKinds.PROPERTY,
                            propertyId = WorkspaceDefaults.DEFAULT_TAGS_PROPERTY_ID,
                        ),
                        orderKey = "a2",
                    ),
                )
            }
            if (availableProperties.any { it.id == kanbanGroupPropertyId }) {
                add(
                    ViewField(
                        field = FieldRef(kind = FieldRefKinds.PROPERTY, propertyId = kanbanGroupPropertyId),
                        orderKey = "a3",
                    ),
                )
            }
            add(
                ViewField(
                    field = FieldRef(kind = FieldRefKinds.SYSTEM, systemField = SystemFieldNames.UPDATED_AT),
                    orderKey = "a4",
                ),
            )
        }
        val normalizedKind = when (presentationKind) {
            ViewPresentationKinds.KANBAN -> ViewPresentationKinds.KANBAN
            ViewPresentationKinds.GALLERY -> ViewPresentationKinds.GALLERY
            else -> ViewPresentationKinds.TABLE
        }
        val viewId = when (normalizedKind) {
            ViewPresentationKinds.KANBAN -> ALL_NOTES_KANBAN_VIEW_ID
            ViewPresentationKinds.GALLERY -> ALL_NOTES_GALLERY_VIEW_ID
            else -> ALL_NOTES_TABLE_VIEW_ID
        }
        return ViewDefinition(
            id = viewId,
            databaseId = ALL_NOTES_DATABASE_ID,
            name = normalizedKind,
            orderKey = "a0",
            query = QuerySpec(filter = filter, sorts = listOf(sort)),
            presentation = ViewPresentation(
                kind = normalizedKind,
                fields = visibleFields,
                groupByPropertyId = if (normalizedKind == ViewPresentationKinds.KANBAN) kanbanGroupPropertyId else null,
            ),
        )
    }
}
