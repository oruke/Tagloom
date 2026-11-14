package com.oruke.tagloom.core.query

import com.oruke.tagloom.core.domain.property.EffectivePropertyResolver
import com.oruke.tagloom.core.domain.tag.AiAccessPolicyResolver
import com.oruke.tagloom.core.domain.tag.TagResolution
import com.oruke.tagloom.core.domain.tag.TagResolver
import com.oruke.tagloom.core.model.AiAccessPolicy
import com.oruke.tagloom.core.model.DatabaseDefinition
import com.oruke.tagloom.core.model.DatasetProjection
import com.oruke.tagloom.core.model.DatasetProjectionColumn
import com.oruke.tagloom.core.model.DatasetProjectionRow
import com.oruke.tagloom.core.model.FieldRef
import com.oruke.tagloom.core.model.FieldRefKinds
import com.oruke.tagloom.core.model.FilterExpr
import com.oruke.tagloom.core.model.FilterExprKinds
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.NullPlacement
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.ObjectRevision
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.PropertyId
import com.oruke.tagloom.core.model.QueryPredicateOperators
import com.oruke.tagloom.core.model.SortDirection
import com.oruke.tagloom.core.model.SortSpec
import com.oruke.tagloom.core.model.SystemDatabaseDefinitions
import com.oruke.tagloom.core.model.SystemFieldNames
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.core.model.TagId
import com.oruke.tagloom.core.model.ViewDefinition
import com.oruke.tagloom.core.model.extractPlainText
import com.oruke.tagloom.core.model.objectTypeState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * 描述投影器可消费的一篇已确认 Note。
 */
data class ProjectionNote(
    /** Note 稳定对象 ID。 */
    val id: ObjectId,
    /** 当前权威 payload。 */
    val note: Note,
    /** 当前对象 revision。 */
    val revision: ObjectRevision? = null,
    /** 首次确认时间。 */
    val createdAt: String = "",
    /** 最近确认时间。 */
    val updatedAt: String = "",
)

/**
 * 从已确认 Note 与工作区 schema 构建可删除、可重建的 Database 投影。
 *
 * 该服务不持久化任何结果，也不提供绕过 Note 写入路径的单元格更新。
 *
 * @param rootTypeId 工作区根 Note 类型。
 */
class DatabaseProjectionService(
    private val rootTypeId: TagId,
) {
    /** 复用工作区标签继承语义。 */
    private val tagResolver = TagResolver(rootTypeId)

    /** 复用笔记与类型多父祖先链 AI 访问控制策略解析器。 */
    private val aiPolicyResolver = AiAccessPolicyResolver(rootTypeId)

    /** 复用属性默认值、继承与冲突语义。 */
    private val propertyResolver = EffectivePropertyResolver()

    /**
     * 计算一个 Database View 的当前派生投影。
     *
     * @param database 数据集来源定义。
     * @param view 当前展示视图。
     * @param notes 已确认且已授权可读的 Note。
     * @param tags 已确认标签定义。
     * @param properties 已确认属性定义。
     * @param indexStale 有效类型/属性索引是否落后。
     * @return 按 QuerySpec 稳定筛选排序的投影。
     */
    fun project(
        database: DatabaseDefinition,
        view: ViewDefinition,
        notes: Collection<ProjectionNote>,
        tags: Collection<TagDefinition>,
        properties: Collection<PropertyDefinition>,
        indexStale: Boolean = false,
    ): DatasetProjection {
        require(view.databaseId == database.id) { "View 不属于目标 Database" }
        val tagsById = tags.associateBy(TagDefinition::id)
        val propertiesById = properties.associateBy(PropertyDefinition::id)
        val candidates = notes.mapNotNull { source ->
            buildCandidate(source, database, tagsById, propertiesById)
        }
        val rows = candidates
            .filter { candidate -> matchesFilter(view.query.filter, candidate) }
            .sortedWith(queryComparator(view.query.sorts))
            .map { candidate ->
                candidate.toProjectionRow(view.presentation.fields.map { field -> field.field })
            }
        val columns = view.presentation.fields
            .filter { field -> field.visible }
            .sortedWith(compareBy({ field -> field.orderKey }, { field -> field.field.stableKey() }))
            .map { field ->
                DatasetProjectionColumn(
                    field = field.field,
                    label = fieldLabel(field.field, propertiesById),
                    writable = field.field.kind == FieldRefKinds.PROPERTY ||
                        field.field.kind == FieldRefKinds.SYSTEM && field.field.systemField == SystemFieldNames.TITLE,
                )
            }
        return DatasetProjection(database, view, columns, rows, indexStale)
    }

    /**
     * 解析 Note 的类型闭包与有效属性，并应用 Database 来源边界。
     *
     * @param source 当前 Note 快照摘要。
     * @param database 数据集来源定义。
     * @param tags 标签定义映射。
     * @param properties 属性定义映射。
     * @return 可参与 QuerySpec 的候选；类型图无效或来源不匹配时为空。
     */
    private fun buildCandidate(
        source: ProjectionNote,
        database: DatabaseDefinition,
        tags: Map<TagId, TagDefinition>,
        properties: Map<PropertyId, PropertyDefinition>,
    ): DatabaseCandidate? {
        if (SystemDatabaseDefinitions.isProtectedSystemPage(source.id)) {
            return null
        }
        val resolution = tagResolver.resolve(source.note.directTagIds, tags) as? TagResolution.Success ?: return null
        val appliedType = source.note.objectTypeState(rootTypeId).appliedTypeId ?: rootTypeId
        val sourceMatches = if (database.includeDescendants) {
            resolution.tags.any { resolved -> resolved.tagId == database.sourceTypeId }
        } else {
            appliedType == database.sourceTypeId
        }
        if (!sourceMatches) {
            return null
        }
        val effective = propertyResolver.resolve(source.note, resolution, tags, properties)
        val effectiveAiPolicy = aiPolicyResolver.resolveForNote(source.note, tags).effectivePolicy
        val values = effective.properties.associate { property -> property.propertyId to property.value }
        val searchableText = buildString {
            append(source.note.title)
            source.note.blocks.mapNotNull { block -> block.extractPlainText() }.forEach { text ->
                append('\n')
                append(text)
            }
        }
        return DatabaseCandidate(
            source = source,
            appliedTypeId = appliedType,
            resolvedTypeIds = resolution.tags.mapTo(mutableSetOf()) { resolved -> resolved.tagId },
            effectiveAiPolicy = effectiveAiPolicy,
            propertyValues = values,
            searchableText = searchableText,
        )
    }

    /**
     * 递归计算一个过滤节点。
     *
     * 未知节点在协议校验阶段会让视图进入只读；投影阶段保守返回 false，避免错误扩大结果集。
     *
     * @param expr 过滤节点。
     * @param candidate Note 候选。
     * @return 当前候选是否满足条件。
     */
    private fun matchesFilter(
        expr: FilterExpr,
        candidate: DatabaseCandidate,
    ): Boolean = when (expr.kind) {
        FilterExprKinds.TRUE -> true
        FilterExprKinds.ALL -> expr.children.all { child -> matchesFilter(child, candidate) }
        FilterExprKinds.ANY -> expr.children.any { child -> matchesFilter(child, candidate) }
        FilterExprKinds.NOT -> expr.child?.let { child -> !matchesFilter(child, candidate) } ?: false
        FilterExprKinds.FULL_TEXT -> expr.text
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { text -> candidate.searchableText.contains(text, ignoreCase = true) }
            ?: false
        FilterExprKinds.PREDICATE -> matchesPredicate(expr, candidate)
        else -> false
    }

    /**
     * 计算一个类型化字段谓词。
     *
     * @param expr Predicate 节点。
     * @param candidate Note 候选。
     * @return 谓词是否成立。
     */
    private fun matchesPredicate(
        expr: FilterExpr,
        candidate: DatabaseCandidate,
    ): Boolean {
        val field = expr.field ?: return false
        val operator = expr.operator ?: return false
        val actual = candidate.value(field)
        return when (operator) {
            QueryPredicateOperators.IS_EMPTY -> actual == null || actual is JsonNull || actual.contentText().isEmpty()
            QueryPredicateOperators.IS_NOT_EMPTY -> actual != null && actual !is JsonNull && actual.contentText().isNotEmpty()
            QueryPredicateOperators.EQ -> actual.semanticEquals(expr.value)
            QueryPredicateOperators.NE -> !actual.semanticEquals(expr.value)
            QueryPredicateOperators.CONTAINS -> actual.contentText().contains(expr.value.contentText(), ignoreCase = true)
            QueryPredicateOperators.GT -> compareValues(actual, expr.value) > 0
            QueryPredicateOperators.GTE -> compareValues(actual, expr.value) >= 0
            QueryPredicateOperators.LT -> compareValues(actual, expr.value) < 0
            QueryPredicateOperators.LTE -> compareValues(actual, expr.value) <= 0
            QueryPredicateOperators.IN -> expr.values.any(actual::semanticEquals)
            QueryPredicateOperators.NOT_IN -> expr.values.none(actual::semanticEquals)
            QueryPredicateOperators.CONTAINS_ANY -> actual.arrayValues().any { item -> expr.values.any(item::semanticEquals) }
            QueryPredicateOperators.CONTAINS_ALL -> expr.values.all { expected -> actual.arrayValues().any(expected::semanticEquals) }
            QueryPredicateOperators.IS_EXACT -> candidate.appliedTypeId.value == expr.value.contentText()
            QueryPredicateOperators.IS_DESCENDANT_OF -> field.kind == FieldRefKinds.OBJECT_TYPE &&
                candidate.resolvedTypeIds.any { typeId -> typeId.value == expr.value.contentText() }
            else -> false
        }
    }

    /**
     * 构造遵守 QuerySpec 优先级、空值位置与 Note ID 兜底的稳定比较器。
     *
     * @param sorts 排序规则。
     * @return Database 候选比较器。
     */
    private fun queryComparator(sorts: List<SortSpec>): Comparator<DatabaseCandidate> = Comparator { left, right ->
        sorts.forEach { sort ->
            val comparison = compareNullable(left.value(sort.field), right.value(sort.field), sort)
            if (comparison != 0) {
                return@Comparator comparison
            }
        }
        left.source.id.value.compareTo(right.source.id.value)
    }

    /**
     * 按一条 SortSpec 比较两个可空字段值。
     *
     * @param left 左值。
     * @param right 右值。
     * @param sort 排序规则。
     * @return 标准比较结果。
     */
    private fun compareNullable(
        left: JsonElement?,
        right: JsonElement?,
        sort: SortSpec,
    ): Int {
        val leftEmpty = left == null || left is JsonNull
        val rightEmpty = right == null || right is JsonNull
        val base = when {
            leftEmpty && rightEmpty -> 0
            leftEmpty -> if (sort.nullPlacement == NullPlacement.FIRST) -1 else 1
            rightEmpty -> if (sort.nullPlacement == NullPlacement.FIRST) 1 else -1
            else -> compareValues(left, right)
        }
        return if (sort.direction == SortDirection.ASCENDING) base else -base
    }

    /**
     * 优先按数字、布尔，再按规范文本比较 JSON 值。
     *
     * @param left 左值。
     * @param right 右值。
     * @return 标准比较结果。
     */
    private fun compareValues(
        left: JsonElement?,
        right: JsonElement?,
    ): Int {
        val leftPrimitive = left as? JsonPrimitive
        val rightPrimitive = right as? JsonPrimitive
        val leftNumber = leftPrimitive?.doubleOrNull
        val rightNumber = rightPrimitive?.doubleOrNull
        if (leftNumber != null && rightNumber != null) {
            return leftNumber.compareTo(rightNumber)
        }
        val leftBoolean = leftPrimitive?.booleanOrNull
        val rightBoolean = rightPrimitive?.booleanOrNull
        if (leftBoolean != null && rightBoolean != null) {
            return leftBoolean.compareTo(rightBoolean)
        }
        return left.contentText().compareTo(right.contentText(), ignoreCase = true)
    }

    /**
     * 返回当前 Locale 无关的字段标题。
     *
     * UI 可以对系统字段二次本地化；领域层只提供稳定回退。
     *
     * @param field 字段引用。
     * @param properties 属性定义映射。
     * @return 字段标题。
     */
    private fun fieldLabel(
        field: FieldRef,
        properties: Map<PropertyId, PropertyDefinition>,
    ): String = when (field.kind) {
        FieldRefKinds.SYSTEM -> field.systemField.orEmpty()
        FieldRefKinds.OBJECT_TYPE -> "object-type"
        FieldRefKinds.PROPERTY -> field.propertyId?.let(properties::get)?.name ?: field.propertyId?.value.orEmpty()
        else -> field.stableKey()
    }

    /**
     * 保存投影期间已解析的 Note 查询上下文。
     */
    private data class DatabaseCandidate(
        /** 权威 Note 摘要。 */
        val source: ProjectionNote,
        /** 当前唯一应用对象类型。 */
        val appliedTypeId: TagId,
        /** 有效类型闭包，包含根与祖先。 */
        val resolvedTypeIds: Set<TagId>,
        /** 收敛后的有效 AI 访问控制策略。 */
        val effectiveAiPolicy: AiAccessPolicy,
        /** 采用 EffectivePropertyResolver 语义的属性值。 */
        val propertyValues: Map<PropertyId, JsonElement?>,
        /** 标题与可搜索 Block 拼接文本。 */
        val searchableText: String,
    ) {
        /**
         * 按 FieldRef 读取投影值。
         *
         * @param field 稳定字段引用。
         * @return 当前值；未知字段为空。
         */
        fun value(field: FieldRef): JsonElement? = when (field.kind) {
            FieldRefKinds.SYSTEM -> when (field.systemField) {
                SystemFieldNames.TITLE -> JsonPrimitive(source.note.title)
                SystemFieldNames.CREATED_AT -> JsonPrimitive(source.createdAt)
                SystemFieldNames.UPDATED_AT -> JsonPrimitive(source.updatedAt)
                SystemFieldNames.AI_ACCESS_POLICY -> JsonPrimitive(effectiveAiPolicy.name)
                else -> null
            }
            FieldRefKinds.OBJECT_TYPE -> JsonPrimitive(appliedTypeId.value)
            FieldRefKinds.PROPERTY -> field.propertyId?.let(propertyValues::get)
            else -> null
        }

        /**
         * 转换为不包含查询中间状态的公开投影行。
         *
         * @param fields 当前 View 需要投影的字段。
         * @return 当前 Note 投影行。
         */
        fun toProjectionRow(fields: List<FieldRef>): DatasetProjectionRow = DatasetProjectionRow(
            noteId = source.id,
            revision = source.revision,
            title = source.note.title,
            values = fields.distinct().associateWith(::value),
        )
    }
}

/**
 * 返回字段引用的稳定比较键。
 *
 * @return 不依赖显示名称的协议键。
 */
private fun FieldRef.stableKey(): String = listOf(kind, systemField.orEmpty(), propertyId?.value.orEmpty()).joinToString("|")

/**
 * 将 JSON 原子值转换为比较和包含操作使用的文本。
 *
 * @return null 使用空串，数组使用逗号连接，其他值使用原始内容。
 */
private fun JsonElement?.contentText(): String = when (this) {
    null,
    JsonNull,
    -> ""
    is JsonPrimitive -> contentOrNull.orEmpty()
    is JsonArray -> joinToString(",") { element -> element.contentText() }
    else -> toString()
}

/**
 * 按原子语义比较 JSON 值。
 *
 * @param other 右侧值。
 * @return 两值在 QuerySpec 中是否相等。
 */
private fun JsonElement?.semanticEquals(other: JsonElement?): Boolean = when {
    this == null || this is JsonNull -> other == null || other is JsonNull
    this is JsonPrimitive && other is JsonPrimitive -> contentOrNull == other.contentOrNull
    else -> this == other
}

/**
 * 将 JSON 数组转换为值列表；非数组按单值集合处理。
 *
 * @return 可用于 containsAny/containsAll 的值列表。
 */
private fun JsonElement?.arrayValues(): List<JsonElement?> = when (this) {
    null,
    JsonNull,
    -> emptyList()
    is JsonArray -> toList()
    else -> listOf(this)
}
