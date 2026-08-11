package com.oruke.tagloom.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 定义 BaseTable 首版支持的局部列类型。
 *
 * 协议仍以字符串保存类型；未知值必须原样保留并由 UI 降级为只读列。
 */
object BaseTableColumnTypes {
    /** 规范化 RichText 文本。 */
    const val TEXT: String = "text"

    /** 规范十进制文本。 */
    const val NUMBER: String = "number"

    /** 布尔值。 */
    const val BOOLEAN: String = "boolean"

    /** 不含时区的 ISO 日期。 */
    const val DATE: String = "date"

    /** UTC 或带偏移量的 ISO 日期时间。 */
    const val DATETIME: String = "datetime"

    /** 当前客户端能够安全编辑的列类型集合。 */
    val EDITABLE: Set<String> = setOf(TEXT, NUMBER, BOOLEAN, DATE, DATETIME)
}

/**
 * 描述 BaseTable 局部列可保留的稳定选项。
 *
 * 首版五种列类型不会消费选项；该目录仅用于前向兼容，绝不提升为 PropertyDefinition。
 */
@Serializable
data class BaseTableColumnOption(
    /** 表格内部稳定选项 ID。 */
    val id: String,
    /** 用户可见选项名称。 */
    val label: String,
    /** 可选语义色 token。 */
    val colorToken: String? = null,
) {
    init {
        require(id.isNotBlank()) { "BaseTable 列选项 ID 不能为空" }
        require(label.isNotBlank()) { "BaseTable 列选项名称不能为空" }
    }
}

/**
 * 描述 BaseTable 的一个局部列定义。
 */
@Serializable
data class BaseTableColumn(
    /** 表格内部稳定列 ID。 */
    val columnId: BaseTableColumnId,
    /** 表格内部列排序键。 */
    val orderKey: String,
    /** 可编辑的列标题。 */
    val name: RichText,
    /** 局部列类型；未知字符串按只读处理。 */
    val type: String,
    /** 仅属于当前表格的局部选项。 */
    val options: List<BaseTableColumnOption> = emptyList(),
    /** 用户偏好的展示宽度；不改变单元格语义。 */
    val width: Int? = null,
    /** 列是否为等待恢复或清理的内部 tombstone。 */
    val deleted: Boolean = false,
) {
    init {
        require(orderKey.isNotBlank()) { "BaseTable 列 orderKey 不能为空" }
        require(type.isNotBlank()) { "BaseTable 列类型不能为空" }
        require(width == null || width in MIN_WIDTH..MAX_WIDTH) { "BaseTable 列宽超出支持范围" }
        require(options.map(BaseTableColumnOption::id).distinct().size == options.size) {
            "BaseTable 列选项 ID 不能重复"
        }
    }

    companion object {
        /** UI 允许保存的最小列宽。 */
        const val MIN_WIDTH: Int = 80

        /** UI 允许保存的最大列宽。 */
        const val MAX_WIDTH: Int = 720
    }
}

/**
 * 表示 BaseTable 单元格的多态内容。
 *
 * 未知单元格 payload 通过 [UnknownBaseTableCell] 原样保存，旧客户端不得覆盖其内容。
 */
@Serializable(with = BaseTableCellSerializer::class)
sealed interface BaseTableCell

/** 表示 RichText 文本单元格。 */
@Serializable
@SerialName("text")
data class BaseTableTextCell(
    /** 单元格规范化富文本。 */
    val richText: RichText,
) : BaseTableCell

/** 表示 number、date 或 datetime 的规范文本单元格。 */
@Serializable
@SerialName("scalar")
data class BaseTableScalarCell(
    /** 值的协议类型。 */
    val type: String,
    /** 不依赖 Locale 的规范文本。 */
    val value: String,
) : BaseTableCell {
    init {
        require(type in SCALAR_TYPES) { "BaseTable 标量单元格类型不受支持" }
    }

    companion object {
        /** 使用规范文本承载的首版标量类型。 */
        val SCALAR_TYPES: Set<String> = setOf(
            BaseTableColumnTypes.NUMBER,
            BaseTableColumnTypes.DATE,
            BaseTableColumnTypes.DATETIME,
        )
    }
}

/** 表示布尔单元格。 */
@Serializable
@SerialName("boolean")
data class BaseTableBooleanCell(
    /** 当前布尔值。 */
    val value: Boolean,
) : BaseTableCell

/** 保存当前客户端尚未理解的单元格 payload。 */
@Serializable
@SerialName("unknown")
data class UnknownBaseTableCell(
    /** 未知单元格完整原始 JSON。 */
    val raw: JsonObject,
) : BaseTableCell

/**
 * 编解码 BaseTable 单元格并保留未知类型。
 */
object BaseTableCellSerializer : KSerializer<BaseTableCell> {
    /** 单元格协议使用 JSON 对象描述符。 */
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    /**
     * 把已知单元格编码为带 `_cellType` 的稳定 JSON。
     *
     * @param encoder 目标 JSON 编码器。
     * @param value 待编码单元格。
     */
    override fun serialize(
        encoder: Encoder,
        value: BaseTableCell,
    ) {
        val jsonEncoder = requireNotNull(encoder as? JsonEncoder) { "BaseTableCell 只支持 JSON 序列化" }
        val json = jsonEncoder.json
        val element = when (value) {
            is BaseTableTextCell -> typedJson(
                BaseTableColumnTypes.TEXT,
                json.encodeToJsonElement(BaseTableTextCell.serializer(), value).jsonObject,
            )
            is BaseTableScalarCell -> typedJson(
                value.type,
                buildJsonObject { put("value", value.value) },
            )
            is BaseTableBooleanCell -> typedJson(
                BaseTableColumnTypes.BOOLEAN,
                buildJsonObject { put("value", value.value) },
            )
            is UnknownBaseTableCell -> value.raw
        }
        jsonEncoder.encodeJsonElement(element)
    }

    /**
     * 根据 `_cellType` 解码首版单元格，未知类型原样返回。
     *
     * @param decoder 来源 JSON 解码器。
     * @return 已知单元格或未知只读 payload。
     */
    override fun deserialize(decoder: Decoder): BaseTableCell {
        val jsonDecoder = requireNotNull(decoder as? JsonDecoder) { "BaseTableCell 只支持 JSON 反序列化" }
        val raw = jsonDecoder.decodeJsonElement().jsonObject
        val type = raw[CELL_TYPE_FIELD]?.jsonPrimitive?.content
        return when (type) {
            BaseTableColumnTypes.TEXT -> BaseTableTextCell(
                richText = jsonDecoder.json.decodeFromJsonElement(
                    RichText.serializer(),
                    requireNotNull(raw["richText"]) { "文本单元格缺少 richText" },
                ),
            )
            BaseTableColumnTypes.NUMBER,
            BaseTableColumnTypes.DATE,
            BaseTableColumnTypes.DATETIME,
            -> BaseTableScalarCell(type, requireNotNull(raw["value"]) { "标量单元格缺少 value" }.jsonPrimitive.content)
            BaseTableColumnTypes.BOOLEAN -> BaseTableBooleanCell(
                requireNotNull(raw["value"]) { "布尔单元格缺少 value" }.jsonPrimitive.boolean,
            )
            else -> UnknownBaseTableCell(raw)
        }
    }

    /**
     * 将类型字段置于 payload 前，形成稳定单元格对象。
     *
     * @param type 单元格协议类型。
     * @param payload 类型特有字段。
     * @return 带类型字段的 JSON。
     */
    private fun typedJson(
        type: String,
        payload: JsonObject,
    ): JsonObject = buildJsonObject {
        put(CELL_TYPE_FIELD, type)
        payload.forEach { (key, value) -> put(key, value) }
    }

    /** 单元格协议类型字段。 */
    private const val CELL_TYPE_FIELD: String = "_cellType"
}

/**
 * 描述 BaseTable 内的一行及其稀疏单元格集合。
 */
@Serializable
data class BaseTableRow(
    /** 表格内部稳定行 ID。 */
    val rowId: BaseTableRowId,
    /** 表格内部行排序键。 */
    val orderKey: String,
    /** 以稳定 ColumnId 为键的非空单元格。 */
    val cells: Map<BaseTableColumnId, BaseTableCell> = emptyMap(),
    /** 行是否为等待恢复或清理的内部 tombstone。 */
    val deleted: Boolean = false,
) {
    init {
        require(orderKey.isNotBlank()) { "BaseTable 行 orderKey 不能为空" }
    }
}

/**
 * 表示 Note Block 内权威保存的 BaseTable 完整内容。
 */
@Serializable
@SerialName("base-table")
data class BaseTableBlockContent(
    /** BaseTable 内容 schema 版本。 */
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    /** 表格可选标题。 */
    val title: RichText = RichText(),
    /** 全部活动列和内部 tombstone 列。 */
    val columns: List<BaseTableColumn>,
    /** 全部活动行和内部 tombstone 行。 */
    val rows: List<BaseTableRow>,
) : BlockContent {
    init {
        require(schemaVersion > 0) { "BaseTable schemaVersion 必须大于零" }
        require(columns.map(BaseTableColumn::columnId).distinct().size == columns.size) {
            "BaseTable ColumnId 不能重复"
        }
        require(rows.map(BaseTableRow::rowId).distinct().size == rows.size) { "BaseTable RowId 不能重复" }
    }

    companion object {
        /** 当前客户端可编辑的 BaseTable schema 版本。 */
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}

/**
 * 把 BaseTable 转换为确定性、稀疏且可哈希的内容表示。
 */
object BaseTableNormalizer {
    /**
     * 规范化表格标题、列标题、文本单元格和集合顺序。
     *
     * 空文本和空标量单元格会被省略；内部 tombstone 保留，直到同步与历史策略允许清理。
     *
     * @param content 待规范化表格内容。
     * @return 稳定协议表示。
     */
    fun normalize(content: BaseTableBlockContent): BaseTableBlockContent = content.copy(
        title = RichTextNormalizer.normalize(content.title),
        columns = content.columns
            .map { column ->
                column.copy(
                    name = RichTextNormalizer.normalize(column.name),
                    options = column.options.sortedBy(BaseTableColumnOption::id),
                )
            }
            .sortedWith(compareBy(BaseTableColumn::orderKey, { column -> column.columnId.value })),
        rows = content.rows
            .map { row ->
                row.copy(
                    cells = row.cells.entries
                        .sortedBy { (columnId, _) -> columnId.value }
                        .mapNotNull { (columnId, cell) -> normalizeCell(cell)?.let { normalized -> columnId to normalized } }
                        .toMap(),
                )
            }
            .sortedWith(compareBy(BaseTableRow::orderKey, { row -> row.rowId.value })),
    )

    /**
     * 规范化单个单元格并识别可省略的空值。
     *
     * @param cell 原始单元格。
     * @return 规范化单元格；语义为空时返回 null。
     */
    private fun normalizeCell(cell: BaseTableCell): BaseTableCell? = when (cell) {
        is BaseTableTextCell -> RichTextNormalizer.normalize(cell.richText)
            .takeUnless { richText -> richText.runs.isEmpty() }
            ?.let(::BaseTableTextCell)
        is BaseTableScalarCell -> cell.takeUnless { scalar -> scalar.value.isBlank() }
        is BaseTableBooleanCell -> cell
        is UnknownBaseTableCell -> cell
    }
}

/** 描述 BaseTable 内容中的一个稳定校验问题。 */
@Serializable
data class BaseTableValidationIssue(
    /** 稳定错误码。 */
    val code: String,
    /** 关联行；不属于具体行时为空。 */
    val rowId: BaseTableRowId? = null,
    /** 关联列；不属于具体列时为空。 */
    val columnId: BaseTableColumnId? = null,
    /** 面向恢复界面的安全说明。 */
    val message: String,
)

/**
 * 校验 BaseTable 局部 schema、排序键与单元格类型一致性。
 */
object BaseTableValidator {
    /**
     * 对一个 BaseTable 内容执行完整校验。
     *
     * 未知 schema 或列类型不会被判为损坏；它们必须在 UI 中只读保留。
     *
     * @param content 待校验内容。
     * @return 稳定排序的问题列表。
     */
    fun validate(content: BaseTableBlockContent): List<BaseTableValidationIssue> {
        val issues = mutableListOf<BaseTableValidationIssue>()
        duplicateOrderKeys(content.columns.filterNot(BaseTableColumn::deleted).map { it.columnId to it.orderKey })
            .forEach { columnId ->
                issues += BaseTableValidationIssue("duplicate_column_order", columnId = columnId, message = "活动列排序键重复")
            }
        duplicateOrderKeys(content.rows.filterNot(BaseTableRow::deleted).map { it.rowId to it.orderKey })
            .forEach { rowId ->
                issues += BaseTableValidationIssue("duplicate_row_order", rowId = rowId, message = "活动行排序键重复")
            }

        val columnsById = content.columns.associateBy(BaseTableColumn::columnId)
        content.rows.forEach { row ->
            row.cells.forEach { (columnId, cell) ->
                val column = columnsById[columnId]
                when {
                    column == null -> issues += BaseTableValidationIssue(
                        "missing_cell_column",
                        row.rowId,
                        columnId,
                        "单元格引用了不存在的列",
                    )
                    !cellMatchesColumn(cell, column.type) -> issues += BaseTableValidationIssue(
                        "cell_type_mismatch",
                        row.rowId,
                        columnId,
                        "单元格类型与列类型不一致",
                    )
                    cell is BaseTableScalarCell && !validScalar(cell) -> issues += BaseTableValidationIssue(
                        "invalid_scalar_value",
                        row.rowId,
                        columnId,
                        "单元格不是规范的数字或日期文本",
                    )
                }
            }
        }
        return issues.sortedWith(compareBy({ issue -> issue.rowId?.value.orEmpty() }, { issue -> issue.columnId?.value.orEmpty() }, BaseTableValidationIssue::code))
    }

    /**
     * 返回重复排序键涉及的全部身份。
     *
     * @param T 行或列 ID 类型。
     * @param values 身份与排序键列表。
     * @return 参与重复的身份。
     */
    private fun <T> duplicateOrderKeys(values: List<Pair<T, String>>): List<T> {
        val duplicates = values.groupBy(Pair<T, String>::second).filterValues { entries -> entries.size > 1 }.keys
        return values.filter { (_, orderKey) -> orderKey in duplicates }.map(Pair<T, String>::first)
    }

    /**
     * 判断单元格 payload 是否可由目标列类型解释。
     *
     * @param cell 单元格内容。
     * @param columnType 列类型字符串。
     * @return 已知类型匹配或未知内容可保留时为 true。
     */
    private fun cellMatchesColumn(
        cell: BaseTableCell,
        columnType: String,
    ): Boolean = when (cell) {
        is BaseTableTextCell -> columnType == BaseTableColumnTypes.TEXT
        is BaseTableScalarCell -> cell.type == columnType
        is BaseTableBooleanCell -> columnType == BaseTableColumnTypes.BOOLEAN
        is UnknownBaseTableCell -> columnType !in BaseTableColumnTypes.EDITABLE
    }

    /**
     * 校验首版标量使用的规范文本格式。
     *
     * @param cell 标量单元格。
     * @return 文本格式可稳定跨平台解释时为 true。
     */
    private fun validScalar(cell: BaseTableScalarCell): Boolean = when (cell.type) {
        BaseTableColumnTypes.NUMBER -> NUMBER_PATTERN.matches(cell.value)
        BaseTableColumnTypes.DATE -> DATE_PATTERN.matches(cell.value)
        BaseTableColumnTypes.DATETIME -> DATETIME_PATTERN.matches(cell.value)
        else -> false
    }

    /** 不使用指数或 Locale 分隔符的十进制文本。 */
    private val NUMBER_PATTERN: Regex = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?")

    /** `YYYY-MM-DD` 日期文本。 */
    private val DATE_PATTERN: Regex = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")

    /** 带秒和 UTC/偏移的 ISO 日期时间文本。 */
    private val DATETIME_PATTERN: Regex = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2})")
}
