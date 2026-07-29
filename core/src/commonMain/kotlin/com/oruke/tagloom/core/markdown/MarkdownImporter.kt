package com.oruke.tagloom.core.markdown

import com.oruke.tagloom.core.model.Annotation
import com.oruke.tagloom.core.model.AnnotationId
import com.oruke.tagloom.core.model.AnnotationKinds
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockContent
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.CodeBlockContent
import com.oruke.tagloom.core.model.DividerBlockContent
import com.oruke.tagloom.core.model.HeadingBlockContent
import com.oruke.tagloom.core.model.InlineRun
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.RichTextBlockContent
import com.oruke.tagloom.core.model.TodoBlockContent

/**
 * 负责把 Markdown 物理行解析为稳定 Block、RichText 与独立 Annotation。
 */
internal object MarkdownImporter {
    /**
     * 导入一篇 Markdown 文档。
     *
     * @param markdown 待导入 Markdown 文本。
     * @param options 标题、时间和 ID 生成策略。
     * @return 规范化 Note 与非阻断警告。
     */
    fun import(
        markdown: String,
        options: MarkdownImportOptions,
    ): MarkdownImportResult {
        require(options.defaultTitle.isNotBlank()) { "Markdown 默认标题不能为空" }
        require(options.importedAt.isNotBlank()) { "Markdown 导入时间不能为空" }
        val normalizedLines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val definitions = parseAnnotationDefinitions(normalizedLines)
        val contentLines = normalizedLines.filterNot { line -> ANNOTATION_DEFINITION.matches(line) }
        val blocks = mutableListOf<Block>()
        val warnings = mutableListOf<String>()
        var title = options.defaultTitle
        var titlePromoted = false
        var index = 0
        var lineIndex = 0

        // 块解析按物理行推进；代码围栏和连续段落会一次消费多行，避免重复或漏读。
        while (lineIndex < contentLines.size) {
            val line = contentLines[lineIndex]
            if (line.isBlank() || line == "<!-- tagloom-annotations -->") {
                lineIndex += 1
                continue
            }
            if (line.startsWith("```")) {
                val code = parseCodeBlock(contentLines, lineIndex, options.blockIdFactory(index), orderKey(index))
                blocks += code.block
                warnings += code.warnings
                lineIndex = code.nextLineIndex
                index += 1
                continue
            }

            val heading = HEADING.matchEntire(line)
            if (heading != null) {
                val level = heading.groupValues[1].length
                val text = heading.groupValues[2]
                if (level == 1 && options.firstHeadingAsTitle && !titlePromoted && blocks.isEmpty()) {
                    title = MarkdownInlineCodec.parse(text, definitions, warnings).plainText.ifBlank { options.defaultTitle }
                    titlePromoted = true
                } else {
                    blocks += textBlock(
                        id = options.blockIdFactory(index),
                        type = BlockTypes.HEADING,
                        orderKey = orderKey(index),
                        content = HeadingBlockContent(level, MarkdownInlineCodec.parse(text, definitions, warnings)),
                    )
                    index += 1
                }
                lineIndex += 1
                continue
            }

            val parsed = parseSingleLineBlock(line, options.blockIdFactory(index), orderKey(index), definitions, warnings)
            if (parsed != null) {
                blocks += parsed
                index += 1
                lineIndex += 1
                continue
            }

            val paragraph = parseParagraph(contentLines, lineIndex, definitions, warnings)
            blocks += textBlock(
                id = options.blockIdFactory(index),
                type = BlockTypes.PARAGRAPH,
                orderKey = orderKey(index),
                content = RichTextBlockContent(paragraph.richText),
            )
            lineIndex = paragraph.nextLineIndex
            index += 1
        }

        val referencedIds = blocks.flatMap(::annotationIds).toSet()
        val annotations = definitions
            .filterKeys(referencedIds::contains)
            .mapValues { (id, body) ->
                Annotation(
                    id = id,
                    kind = AnnotationKinds.NOTE,
                    body = body,
                    createdAt = options.importedAt,
                    updatedAt = options.importedAt,
                )
            }
        return MarkdownImportResult(
            note = Note(
                title = title,
                directTagIds = emptyList(),
                blocks = blocks,
                annotations = annotations,
            ),
            warnings = warnings.distinct(),
        )
    }

    /**
     * 解析围栏代码块，并显式返回下一个待处理物理行。
     *
     * @param lines Markdown 物理行。
     * @param startIndex 围栏起始行。
     * @param blockId 新 Block ID。
     * @param orderKey 稳定排序键。
     * @return 代码 Block、警告与后续行索引。
     */
    private fun parseCodeBlock(
        lines: List<String>,
        startIndex: Int,
        blockId: BlockId,
        orderKey: String,
    ): ParsedCodeBlock {
        val language = lines[startIndex].removePrefix("```").trim().ifBlank { null }
        val codeLines = mutableListOf<String>()
        var lineIndex = startIndex + 1
        while (lineIndex < lines.size && !lines[lineIndex].startsWith("```")) {
            codeLines += lines[lineIndex]
            lineIndex += 1
        }
        val warnings = if (lineIndex >= lines.size) {
            listOf("代码围栏没有结束标记，已导入到文档末尾")
        } else {
            lineIndex += 1
            emptyList()
        }
        return ParsedCodeBlock(
            block = Block(
                id = blockId,
                type = BlockTypes.CODE,
                orderKey = orderKey,
                content = CodeBlockContent(language = language, text = codeLines.joinToString("\n")),
            ),
            nextLineIndex = lineIndex,
            warnings = warnings,
        )
    }

    /**
     * 解析连续普通物理行为一个段落。
     *
     * @param lines Markdown 物理行。
     * @param startIndex 当前起始行。
     * @param definitions 可引用加注定义。
     * @param warnings 可追加警告。
     * @return 段落 RichText 与后续行索引。
     */
    private fun parseParagraph(
        lines: List<String>,
        startIndex: Int,
        definitions: Map<AnnotationId, String>,
        warnings: MutableList<String>,
    ): ParsedParagraph {
        val paragraphLines = mutableListOf<String>()
        var lineIndex = startIndex
        while (
            lineIndex < lines.size &&
            lines[lineIndex].isNotBlank() &&
            !isBlockStart(lines[lineIndex]) &&
            lines[lineIndex] != "<!-- tagloom-annotations -->"
        ) {
            paragraphLines += lines[lineIndex]
            lineIndex += 1
        }
        if (paragraphLines.isEmpty()) {
            paragraphLines += lines[startIndex]
            lineIndex = startIndex + 1
        }
        return ParsedParagraph(
            richText = MarkdownInlineCodec.parse(paragraphLines.joinToString(" "), definitions, warnings),
            nextLineIndex = lineIndex,
        )
    }

    /**
     * 解析列表、待办、引用和分隔线等单行块。
     *
     * @param line 当前物理行。
     * @param blockId 新 Block ID。
     * @param orderKey 稳定排序键。
     * @param definitions 可引用加注定义。
     * @param warnings 可追加导入警告。
     * @return 已识别 Block；普通段落返回 null。
     */
    private fun parseSingleLineBlock(
        line: String,
        blockId: BlockId,
        orderKey: String,
        definitions: Map<AnnotationId, String>,
        warnings: MutableList<String>,
    ): Block? {
        if (DIVIDER.matches(line.trim())) {
            return Block(blockId, BlockTypes.DIVIDER, orderKey = orderKey, content = DividerBlockContent)
        }
        val todo = TODO.matchEntire(line)
        if (todo != null) {
            return textBlock(
                blockId,
                BlockTypes.TODO,
                orderKey,
                TodoBlockContent(
                    checked = todo.groupValues[1].equals("x", ignoreCase = true),
                    richText = MarkdownInlineCodec.parse(todo.groupValues[2], definitions, warnings),
                ),
            )
        }
        val bullet = BULLET.matchEntire(line)
        if (bullet != null) {
            return textBlock(
                blockId,
                BlockTypes.BULLETED_LIST,
                orderKey,
                RichTextBlockContent(MarkdownInlineCodec.parse(bullet.groupValues[1], definitions, warnings)),
            )
        }
        val numbered = NUMBERED.matchEntire(line)
        if (numbered != null) {
            return textBlock(
                blockId,
                BlockTypes.NUMBERED_LIST,
                orderKey,
                RichTextBlockContent(MarkdownInlineCodec.parse(numbered.groupValues[1], definitions, warnings)),
            )
        }
        val quote = QUOTE.matchEntire(line)
        if (quote != null) {
            return textBlock(
                blockId,
                BlockTypes.QUOTE,
                orderKey,
                RichTextBlockContent(MarkdownInlineCodec.parse(quote.groupValues[1], definitions, warnings)),
            )
        }
        return null
    }

    /**
     * 从文档脚注定义提取合法加注正文。
     *
     * @param lines Markdown 物理行。
     * @return 以 AnnotationId 索引的加注正文。
     */
    private fun parseAnnotationDefinitions(lines: List<String>): Map<AnnotationId, String> = buildMap {
        lines.forEach { line ->
            val match = ANNOTATION_DEFINITION.matchEntire(line) ?: return@forEach
            val annotationId = runCatching { AnnotationId(match.groupValues[1]) }.getOrNull() ?: return@forEach
            put(annotationId, MarkdownInlineCodec.unescapePlain(match.groupValues[2]))
        }
    }

    /**
     * 判断一行是否会启动非段落 Block。
     *
     * @param line 待检查物理行。
     * @return 标题、围栏、列表、引用或分隔线时为 `true`。
     */
    private fun isBlockStart(line: String): Boolean =
        line.startsWith("```") ||
            HEADING.matches(line) ||
            TODO.matches(line) ||
            BULLET.matches(line) ||
            NUMBERED.matches(line) ||
            QUOTE.matches(line) ||
            DIVIDER.matches(line.trim())

    /**
     * 构造结构化文本 Block。
     *
     * @param id Block ID。
     * @param type Block 类型。
     * @param orderKey 稳定排序键。
     * @param content 已解析结构化内容。
     * @return 新文本 Block。
     */
    private fun textBlock(
        id: BlockId,
        type: String,
        orderKey: String,
        content: BlockContent,
    ): Block = Block(id = id, type = type, orderKey = orderKey, content = content)

    /**
     * 返回按导入顺序单调递增且词法可排序的 key。
     *
     * @param index 从零开始的 Block 序号。
     * @return 固定宽度十进制排序键。
     */
    private fun orderKey(index: Int): String = index.toString().padStart(10, '0')

    /**
     * 收集 Block 中全部行内加注锚点。
     *
     * @param block 待扫描 Block。
     * @return 去重前的 AnnotationId 列表。
     */
    private fun annotationIds(block: Block): List<AnnotationId> = when (val content = block.content) {
        is RichTextBlockContent -> content.richText.runs.flatMap(InlineRun::annotationIds)
        is HeadingBlockContent -> content.richText.runs.flatMap(InlineRun::annotationIds)
        is TodoBlockContent -> content.richText.runs.flatMap(InlineRun::annotationIds)
        else -> emptyList()
    }

    /** 受支持一级到六级标题。 */
    private val HEADING: Regex = Regex("^(#{1,6})\\s+(.+)$")

    /** GitHub 风格待办项。 */
    private val TODO: Regex = Regex("^-\\s+\\[([ xX])]\\s+(.+)$")

    /** 无序列表项。 */
    private val BULLET: Regex = Regex("^[-*+]\\s+(.+)$")

    /** 有序列表项。 */
    private val NUMBERED: Regex = Regex("^\\d+[.)]\\s+(.+)$")

    /** 引用段落。 */
    private val QUOTE: Regex = Regex("^>\\s?(.+)$")

    /** 常见 Markdown 分隔线。 */
    private val DIVIDER: Regex = Regex("^(?:---+|___+|\\*\\*\\*+)$")

    /** Tagloom 扩展加注正文脚注。 */
    private val ANNOTATION_DEFINITION: Regex = Regex("^\\[\\^([A-Za-z0-9_-]{1,128})]:\\s*(.*)$")
}

/**
 * 保存一次围栏代码块解析结果。
 */
private data class ParsedCodeBlock(
    /** 已解析代码 Block。 */
    val block: Block,
    /** 下一个待解析物理行。 */
    val nextLineIndex: Int,
    /** 未闭合围栏等非阻断警告。 */
    val warnings: List<String>,
)

/**
 * 保存一次连续段落解析结果。
 */
private data class ParsedParagraph(
    /** 已规范化段落富文本。 */
    val richText: com.oruke.tagloom.core.model.RichText,
    /** 下一个待解析物理行。 */
    val nextLineIndex: Int,
)
