package com.oruke.tagloom.core.markdown

import com.oruke.tagloom.core.model.AnnotationId
import com.oruke.tagloom.core.model.BaseTableBlockContent
import com.oruke.tagloom.core.model.BaseTableColumn
import com.oruke.tagloom.core.model.BaseTableColumnId
import com.oruke.tagloom.core.model.BaseTableColumnTypes
import com.oruke.tagloom.core.model.BaseTableNormalizer
import com.oruke.tagloom.core.model.BaseTableRow
import com.oruke.tagloom.core.model.BaseTableRowId
import com.oruke.tagloom.core.model.BaseTableTextCell
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.InlineRun
import com.oruke.tagloom.core.model.RichText
import com.oruke.tagloom.core.model.RichTextBlockContent

/**
 * 将带合法分隔行的 Markdown 管道表格转换为 BaseTable，无法无损容纳的宽行保留为原文。
 *
 * 行列身份只由调用方工厂生成；解析期间的序号仅承担排序职责，不参与身份派生。
 */
internal object MarkdownTableParser {
    /**
     * 判断当前位置是否存在列数一致的表头与分隔行。
     *
     * @param lines 规范化后的 Markdown 物理行。
     * @param startIndex 候选表头索引。
     * @return 两行能明确解释为管道表格时为 true。
     */
    fun startsTable(lines: List<String>, startIndex: Int): Boolean = header(lines, startIndex) != null

    /**
     * 解析整张候选表格；短行补空，超出表头宽度的任何一行都会使整段原文保留。
     *
     * @param lines 规范化后的 Markdown 物理行。
     * @param startIndex 候选表头索引。
     * @param blockId 新 Block 的不透明身份。
     * @param orderKey Block 在文档中的排序键。
     * @param options 导入身份与时间配置。
     * @param definitions 文档中可引用的加注定义。
     * @param warnings 接收能力差异与原文回退说明的集合。
     * @param startsNewBlock 判断后续物理行是否启动其它块的共享规则。
     * @return 表格或保留原文的段落及后续索引；不是候选表格时返回 null。
     */
    fun parse(
        lines: List<String>,
        startIndex: Int,
        blockId: BlockId,
        orderKey: String,
        options: MarkdownImportOptions,
        definitions: Map<AnnotationId, String>,
        warnings: MutableList<String>,
        startsNewBlock: (String) -> Boolean,
    ): ParsedMarkdownTable? {
        val header = header(lines, startIndex) ?: return null
        var nextLineIndex = startIndex + 2
        val body = mutableListOf<List<String>>()

        // 先完整检查输入，确保宽行不会在表头和部分数据已转换之后才被静默截断。
        while (nextLineIndex < lines.size) {
            val line = lines[nextLineIndex]
            if (line.isBlank() || startsNewBlock(line) || line == "<!-- tagloom-annotations -->") break
            body += splitRow(line).cells
            nextLineIndex += 1
        }
        if (body.any { cells -> cells.size > header.names.size }) {
            warnings += "表格数据行超过表头列数，已保留整段 Markdown 原文，避免丢失单元格"
            return ParsedMarkdownTable(
                block = Block(
                    id = blockId,
                    type = BlockTypes.PARAGRAPH,
                    orderKey = orderKey,
                    content = RichTextBlockContent(
                        RichText(listOf(InlineRun(lines.subList(startIndex, nextLineIndex).joinToString("\n")))),
                    ),
                ),
                nextLineIndex = nextLineIndex,
            )
        }
        if (header.hasAlignment) {
            warnings += "Markdown 表格的列对齐标记未保存，已使用表格默认对齐"
        }

        // 结构确认后再分配身份并解析行内语义，空单元格交给既有稀疏表格规范化器处理。
        val columns = header.names.mapIndexed { index, name ->
            BaseTableColumn(
                columnId = BaseTableColumnId(options.tableIdFactory()),
                orderKey = index.toString().padStart(10, '0'),
                name = MarkdownInlineCodec.parse(name, definitions, warnings),
                type = BaseTableColumnTypes.TEXT,
            )
        }
        val rows = body.mapIndexed { index, cells ->
            BaseTableRow(
                rowId = BaseTableRowId(options.tableIdFactory()),
                orderKey = index.toString().padStart(10, '0'),
                cells = columns.mapIndexed { columnIndex, column ->
                    column.columnId to BaseTableTextCell(
                        MarkdownInlineCodec.parse(cells.getOrElse(columnIndex) { "" }, definitions, warnings),
                    )
                }.toMap(),
            )
        }
        return ParsedMarkdownTable(
            block = Block(
                id = blockId,
                type = BlockTypes.BASE_TABLE,
                orderKey = orderKey,
                content = BaseTableNormalizer.normalize(BaseTableBlockContent(columns = columns, rows = rows)),
            ),
            nextLineIndex = nextLineIndex,
        )
    }

    /**
     * 验证表头与分隔行语法，避免普通管道文本或 Setext 标题被误认为单列表格。
     *
     * @param lines Markdown 物理行。
     * @param startIndex 候选表头索引。
     * @return 合法表头信息；缺少管道或列数不匹配时返回 null。
     */
    private fun header(lines: List<String>, startIndex: Int): MarkdownTableHeader? {
        val nameLine = lines.getOrNull(startIndex) ?: return null
        val delimiterLine = lines.getOrNull(startIndex + 1) ?: return null
        // 四空格及制表符前缀属于缩进代码，不应在其中新增表格块。
        if (nameLine.startsWith("    ") || nameLine.startsWith('\t') ||
            delimiterLine.startsWith("    ") || delimiterLine.startsWith('\t')
        ) return null
        val names = splitRow(nameLine)
        val delimiters = splitRow(delimiterLine)
        if (!names.hasPipe && !delimiters.hasPipe) return null
        if (names.cells.size != delimiters.cells.size || delimiters.cells.any { !DELIMITER_CELL.matches(it) }) return null
        return MarkdownTableHeader(names.cells, delimiters.cells.any { ':' in it })
    }

    /**
     * 拆分物理行中未转义的管道，并保留空单元格与 Tagloom 加注内部的管道。
     *
     * GFM 要求代码与强调内部的字面管道也必须转义，因此这里不会把代码范围当成分列例外。
     *
     * @param line 单个表格物理行。
     * @return 去掉可选边缘管道后的单元格和显式管道标记。
     */
    private fun splitRow(line: String): MarkdownTableCells {
        val text = line.trim()
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var hasPipe = false
        var lastPipeIndex = -1
        var index = 0
        while (index < text.length) {
            val character = text[index]
            when {
                character == '\\' && index + 1 < text.length -> {
                    current.append(character).append(text[index + 1])
                    index += 2
                }
                text.startsWith("{{", index) -> {
                    val anchor = ANNOTATION_ANCHOR.find(text, index)?.takeIf { it.range.first == index }
                    if (anchor != null) {
                        current.append(anchor.value)
                        index = anchor.range.last + 1
                    } else {
                        current.append(character)
                        index += 1
                    }
                }
                character == '|' -> {
                    cells += current.toString().trim()
                    current.clear()
                    hasPipe = true
                    lastPipeIndex = index
                    index += 1
                }
                else -> {
                    current.append(character)
                    index += 1
                }
            }
        }
        cells += current.toString().trim()
        if (text.startsWith('|')) cells.removeAt(0)
        if (lastPipeIndex == text.lastIndex && cells.size > 1) cells.removeAt(cells.lastIndex)
        return MarkdownTableCells(cells, hasPipe)
    }

    /** GFM 分隔单元格允许一个以上连字符及可选左右冒号。 */
    private val DELIMITER_CELL: Regex = Regex(":?-+:?")

    /** Tagloom 加注内部管道不分列；花括号显式转义以兼容 Android ICU 正则。 */
    private val ANNOTATION_ANCHOR: Regex = Regex("\\{\\{[A-Za-z0-9_-]{1,128}\\|.+?\\}\\}")
}

/** 保存已确认表格的列名与无法表达的对齐状态。 */
private data class MarkdownTableHeader(
    /** 每个表头单元格的 Markdown 行内文本。 */
    val names: List<String>,
    /** 是否存在当前 BaseTable 无法保存的显式列对齐。 */
    val hasAlignment: Boolean,
)

/** 保存一次物理行分列结果，显式管道用于区别普通文字与单列表格。 */
private data class MarkdownTableCells(
    /** 去掉可选边缘管道并裁剪边缘空格的单元格。 */
    val cells: List<String>,
    /** 物理行是否含未转义且不属于加注结构的管道。 */
    val hasPipe: Boolean,
)

/** 保存一张 Markdown 表格的转换结果与下一条未消费物理行。 */
internal data class ParsedMarkdownTable(
    /** 成功转换的 BaseTable；无法无损转换时为保留原文的段落。 */
    val block: Block,
    /** 下一个待解析物理行索引。 */
    val nextLineIndex: Int,
)
