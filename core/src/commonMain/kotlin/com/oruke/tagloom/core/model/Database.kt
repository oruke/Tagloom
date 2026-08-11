package com.oruke.tagloom.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** 定义 QuerySpec 过滤节点的稳定类型名称。 */
object FilterExprKinds {
    /** 恒为真。 */
    const val TRUE: String = "true"

    /** 全部子条件成立。 */
    const val ALL: String = "all"

    /** 任意子条件成立。 */
    const val ANY: String = "any"

    /** 反转唯一子条件。 */
    const val NOT: String = "not"

    /** 对标题和正文执行全文匹配。 */
    const val FULL_TEXT: String = "full-text"

    /** 对稳定字段执行类型化谓词。 */
    const val PREDICATE: String = "predicate"

    /** 当前客户端可编辑的过滤节点类型。 */
    val EDITABLE: Set<String> = setOf(TRUE, ALL, ANY, NOT, FULL_TEXT, PREDICATE)
}

/** 定义 QuerySpec 字段引用的稳定类型名称。 */
object FieldRefKinds {
    /** Note 系统字段。 */
    const val SYSTEM: String = "system"

    /** Note 当前应用对象类型。 */
    const val OBJECT_TYPE: String = "object-type"

    /** 工作区 PropertyDefinition。 */
    const val PROPERTY: String = "property"
}

/** 定义 QuerySpec 可引用的 Note 系统字段。 */
object SystemFieldNames {
    /** Note 标题。 */
    const val TITLE: String = "title"

    /** 快照创建时间。 */
    const val CREATED_AT: String = "created-at"

    /** 快照更新时间。 */
    const val UPDATED_AT: String = "updated-at"

    /** 系统字段稳定集合。 */
    val ALL: Set<String> = setOf(TITLE, CREATED_AT, UPDATED_AT)
}

/** 定义 QuerySpec 首版谓词运算符。 */
object QueryPredicateOperators {
    /** 等于。 */
    const val EQ: String = "eq"

    /** 不等于。 */
    const val NE: String = "ne"

    /** 值为空。 */
    const val IS_EMPTY: String = "is-empty"

    /** 值非空。 */
    const val IS_NOT_EMPTY: String = "is-not-empty"

    /** 文本包含。 */
    const val CONTAINS: String = "contains"

    /** 大于。 */
    const val GT: String = "gt"

    /** 大于等于。 */
    const val GTE: String = "gte"

    /** 小于。 */
    const val LT: String = "lt"

    /** 小于等于。 */
    const val LTE: String = "lte"

    /** 单选值位于候选集合。 */
    const val IN: String = "in"

    /** 单选值不在候选集合。 */
    const val NOT_IN: String = "not-in"

    /** 多选至少包含一个候选。 */
    const val CONTAINS_ANY: String = "contains-any"

    /** 多选包含全部候选。 */
    const val CONTAINS_ALL: String = "contains-all"

    /** 对象类型精确匹配。 */
    const val IS_EXACT: String = "is-exact"

    /** 对象类型是目标后代。 */
    const val IS_DESCENDANT_OF: String = "is-descendant-of"

    /** 当前客户端支持的运算符。 */
    val EDITABLE: Set<String> = setOf(
        EQ,
        NE,
        IS_EMPTY,
        IS_NOT_EMPTY,
        CONTAINS,
        GT,
        GTE,
        LT,
        LTE,
        IN,
        NOT_IN,
        CONTAINS_ANY,
        CONTAINS_ALL,
        IS_EXACT,
        IS_DESCENDANT_OF,
    )
}

/**
 * 使用稳定 ID 描述 QuerySpec 的一个字段引用。
 *
 * 非当前 kind 使用的字段必须为空；未知 kind 会被保留并使所属视图进入只读兼容状态。
 */
@Serializable
data class FieldRef(
    /** 字段引用类型。 */
    val kind: String,
    /** 系统字段名称。 */
    val systemField: String? = null,
    /** PropertyDefinition 稳定 ID。 */
    val propertyId: PropertyId? = null,
)

/**
 * 表示 QuerySpec 的版本化过滤 AST 节点。
 *
 * 使用显式 kind 而非 sealed discriminator，使旧客户端能够无损保存未知节点并拒绝编辑。
 */
@Serializable
data class FilterExpr(
    /** 节点类型。 */
    val kind: String = FilterExprKinds.TRUE,
    /** All/Any 的有序子节点。 */
    val children: List<FilterExpr> = emptyList(),
    /** Not 的唯一子节点。 */
    val child: FilterExpr? = null,
    /** FullText 使用的查询文本。 */
    val text: String? = null,
    /** Predicate 使用的稳定字段引用。 */
    val field: FieldRef? = null,
    /** Predicate 使用的稳定运算符。 */
    val operator: String? = null,
    /** Predicate 的单个类型化 JSON 值。 */
    val value: JsonElement? = null,
    /** Predicate 的候选值集合。 */
    val values: List<JsonElement> = emptyList(),
)

/** 定义 QuerySpec 排序方向。 */
@Serializable
enum class SortDirection {
    /** 升序。 */
    ASCENDING,

    /** 降序。 */
    DESCENDING,
}

/** 定义空值在排序中的位置。 */
@Serializable
enum class NullPlacement {
    /** 空值位于非空值之前。 */
    FIRST,

    /** 空值位于非空值之后。 */
    LAST,
}

/** 描述 QuerySpec 的一个稳定字段排序。 */
@Serializable
data class SortSpec(
    /** 排序字段。 */
    val field: FieldRef,
    /** 升序或降序。 */
    val direction: SortDirection = SortDirection.ASCENDING,
    /** 空值排序位置。 */
    val nullPlacement: NullPlacement = NullPlacement.LAST,
)

/**
 * 表示 SavedQuery、Database View 与工作区查询共用的版本化 AST。
 */
@Serializable
data class QuerySpec(
    /** QuerySpec schema 版本。 */
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    /** 根过滤节点。 */
    val filter: FilterExpr = FilterExpr(),
    /** 按优先级排列的排序规则。 */
    val sorts: List<SortSpec> = emptyList(),
) {
    init {
        require(schemaVersion > 0) { "QuerySpec schemaVersion 必须大于零" }
    }

    companion object {
        /** 当前客户端可编辑的 QuerySpec schema。 */
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}

/** 定义 Database View 的展示种类。 */
object ViewPresentationKinds {
    /** 行列数据表。 */
    const val TABLE: String = "table"

    /** 按可写单选属性分组的看板。 */
    const val KANBAN: String = "kanban"

    /** 带有限封面与字段的卡片画廊。 */
    const val GALLERY: String = "gallery"

    /** 当前客户端可编辑和展示的视图种类。 */
    val EDITABLE: Set<String> = setOf(TABLE, KANBAN, GALLERY)
}

/**
 * 描述 View 中一个字段的展示配置。
 */
@Serializable
data class ViewField(
    /** 稳定字段引用。 */
    val field: FieldRef,
    /** 视图内部排序键。 */
    val orderKey: String,
    /** 字段是否可见。 */
    val visible: Boolean = true,
    /** Table 列宽或 Gallery 字段宽度提示。 */
    val width: Int? = null,
) {
    init {
        require(orderKey.isNotBlank()) { "ViewField orderKey 不能为空" }
        require(width == null || width in 64..720) { "ViewField 宽度超出支持范围" }
    }
}

/**
 * 描述 Database View 的展示配置，不包含任何查询语义。
 */
@Serializable
data class ViewPresentation(
    /** table、kanban、gallery 或未知前向兼容值。 */
    val kind: String = ViewPresentationKinds.TABLE,
    /** Table/Gallery 展示字段。 */
    val fields: List<ViewField> = emptyList(),
    /** Kanban 分组使用的可写单选属性。 */
    val groupByPropertyId: PropertyId? = null,
    /** Gallery 可选封面字段。 */
    val coverPropertyId: PropertyId? = null,
)

/**
 * 表示一个工作区 Database 的权威来源与默认行为。
 */
@Serializable
data class DatabaseDefinition(
    /** 数据集稳定 ID。 */
    val id: DatabaseId,
    /** 用户可见名称。 */
    val name: String,
    /** 单一来源对象类型。 */
    val sourceTypeId: TagId,
    /** 是否包含来源类型的全部后代。 */
    val includeDescendants: Boolean = true,
    /** 在该数据集中新建 Note 时使用的对象类型。 */
    val creationTypeId: TagId,
    /** 打开数据集时选择的默认视图。 */
    val defaultViewId: ViewId,
) {
    init {
        require(name.isNotBlank()) { "Database 名称不能为空" }
    }
}

/**
 * 表示独立于 DatabaseDefinition 保存的权威视图配置。
 */
@Serializable
data class ViewDefinition(
    /** 视图稳定 ID。 */
    val id: ViewId,
    /** 单向指向所属 Database。 */
    val databaseId: DatabaseId,
    /** 用户可见视图名称。 */
    val name: String,
    /** 同一 Database 内的视图排序键。 */
    val orderKey: String,
    /** 只描述筛选和排序的类型化查询。 */
    val query: QuerySpec = QuerySpec(),
    /** 只描述 Table/Kanban/Gallery 的展示配置。 */
    val presentation: ViewPresentation = ViewPresentation(),
) {
    init {
        require(name.isNotBlank()) { "View 名称不能为空" }
        require(orderKey.isNotBlank()) { "View orderKey 不能为空" }
    }
}

/** 描述 Database/View 协议中的一个校验或兼容问题。 */
@Serializable
data class DatabaseProtocolIssue(
    /** 稳定错误码。 */
    val code: String,
    /** 是否仅要求只读兼容，而非判定对象损坏。 */
    val readOnlyCompatibility: Boolean,
    /** 不包含用户数据的安全说明。 */
    val message: String,
)

/**
 * 校验 Database、View 与 QuerySpec 的引用形态和前向兼容状态。
 */
object DatabaseProtocolValidator {
    /**
     * 校验一个 Database 与其视图集合的关系。
     *
     * @param database 数据集定义。
     * @param views 当前已认证视图集合。
     * @return 关系和协议问题。
     */
    fun validate(
        database: DatabaseDefinition,
        views: Collection<ViewDefinition>,
    ): List<DatabaseProtocolIssue> {
        val issues = mutableListOf<DatabaseProtocolIssue>()
        val ownViews = views.filter { view -> view.databaseId == database.id }
        if (ownViews.none { view -> view.id == database.defaultViewId }) {
            issues += DatabaseProtocolIssue("missing_default_view", false, "Database 默认视图不存在")
        }
        ownViews.groupBy(ViewDefinition::orderKey).values.filter { group -> group.size > 1 }.forEach {
            issues += DatabaseProtocolIssue("duplicate_view_order", false, "同一 Database 的活动视图排序键重复")
        }
        ownViews.forEach { view -> issues += validate(view) }
        return issues
    }

    /**
     * 校验单个 View 的 QuerySpec 和展示配置。
     *
     * @param view 待校验视图。
     * @return 结构问题与只读兼容提示。
     */
    fun validate(view: ViewDefinition): List<DatabaseProtocolIssue> {
        val issues = mutableListOf<DatabaseProtocolIssue>()
        if (view.query.schemaVersion != QuerySpec.CURRENT_SCHEMA_VERSION) {
            issues += DatabaseProtocolIssue("unknown_query_schema", true, "QuerySpec 版本超出当前客户端编辑能力")
        }
        issues += validateFilter(view.query.filter)
        view.query.sorts.forEach { sort -> issues += validateField(sort.field) }
        if (view.presentation.kind !in ViewPresentationKinds.EDITABLE) {
            issues += DatabaseProtocolIssue("unknown_presentation_kind", true, "视图展示类型超出当前客户端编辑能力")
        }
        if (view.presentation.kind == ViewPresentationKinds.KANBAN && view.presentation.groupByPropertyId == null) {
            issues += DatabaseProtocolIssue("missing_kanban_group", false, "Kanban 必须指定可写单选分组属性")
        }
        view.presentation.fields.forEach { field -> issues += validateField(field.field) }
        return issues
    }

    /**
     * 递归校验过滤 AST。
     *
     * @param expr 当前过滤节点。
     * @return 当前节点及后代问题。
     */
    private fun validateFilter(expr: FilterExpr): List<DatabaseProtocolIssue> {
        if (expr.kind !in FilterExprKinds.EDITABLE) {
            return listOf(DatabaseProtocolIssue("unknown_filter_kind", true, "过滤节点超出当前客户端编辑能力"))
        }
        val issues = mutableListOf<DatabaseProtocolIssue>()
        when (expr.kind) {
            FilterExprKinds.TRUE -> Unit
            FilterExprKinds.ALL,
            FilterExprKinds.ANY,
            -> expr.children.forEach { child -> issues += validateFilter(child) }
            FilterExprKinds.NOT -> if (expr.child == null) {
                issues += DatabaseProtocolIssue("missing_not_child", false, "Not 过滤节点缺少子条件")
            } else {
                issues += validateFilter(expr.child)
            }
            FilterExprKinds.FULL_TEXT -> if (expr.text.isNullOrBlank()) {
                issues += DatabaseProtocolIssue("empty_full_text", false, "全文条件不能为空")
            }
            FilterExprKinds.PREDICATE -> {
                if (expr.field == null || expr.operator == null) {
                    issues += DatabaseProtocolIssue("incomplete_predicate", false, "字段谓词缺少字段或运算符")
                } else {
                    issues += validateField(expr.field)
                    if (expr.operator !in QueryPredicateOperators.EDITABLE) {
                        issues += DatabaseProtocolIssue("unknown_predicate_operator", true, "谓词运算符超出当前客户端编辑能力")
                    }
                }
            }
        }
        return issues
    }

    /**
     * 校验稳定字段引用的字段组合。
     *
     * @param field 待检查引用。
     * @return 字段组合问题。
     */
    private fun validateField(field: FieldRef): List<DatabaseProtocolIssue> {
        val valid = when (field.kind) {
            FieldRefKinds.SYSTEM -> field.systemField in SystemFieldNames.ALL && field.propertyId == null
            FieldRefKinds.OBJECT_TYPE -> field.systemField == null && field.propertyId == null
            FieldRefKinds.PROPERTY -> field.systemField == null && field.propertyId != null
            else -> return listOf(DatabaseProtocolIssue("unknown_field_kind", true, "字段引用类型超出当前客户端编辑能力"))
        }
        return if (valid) emptyList() else listOf(DatabaseProtocolIssue("invalid_field_ref", false, "字段引用参数不完整"))
    }
}

/** 描述 Database 派生投影中的一列。 */
data class DatasetProjectionColumn(
    /** 稳定字段引用。 */
    val field: FieldRef,
    /** 解锁会话内的用户可见列名。 */
    val label: String,
    /** 当前字段是否允许写回对应 Note。 */
    val writable: Boolean,
)

/** 描述 Database 派生投影中的一行 Note。 */
data class DatasetProjectionRow(
    /** 对应权威 Note ID。 */
    val noteId: ObjectId,
    /** 当前 Note revision；写回时必须校验。 */
    val revision: ObjectRevision?,
    /** 固定标题列文本。 */
    val title: String,
    /** 按 FieldRef 稳定键保存的派生单元格值。 */
    val values: Map<FieldRef, JsonElement?>,
)

/**
 * 表示可从已确认 Note 快照重建的 Database 当前投影。
 */
data class DatasetProjection(
    /** 投影来源 Database。 */
    val database: DatabaseDefinition,
    /** 当前展示 View。 */
    val view: ViewDefinition,
    /** 固定标题列之外的可见列。 */
    val columns: List<DatasetProjectionColumn>,
    /** 通过授权、来源类型和 QuerySpec 的 Note 行。 */
    val rows: List<DatasetProjectionRow>,
    /** 派生结构索引是否落后于权威快照。 */
    val indexStale: Boolean = false,
)
