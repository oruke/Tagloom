package com.oruke.tagloom.editor

import com.oruke.tagloom.core.model.AnnotationId
import com.oruke.tagloom.core.model.AttachmentBlockContent
import com.oruke.tagloom.core.model.BaseTableBlockContent
import com.oruke.tagloom.core.model.BaseTableTextCell
import com.oruke.tagloom.core.model.BlockContent
import com.oruke.tagloom.core.model.CodeBlockContent
import com.oruke.tagloom.core.model.DatabaseBlockContent
import com.oruke.tagloom.core.model.DividerBlockContent
import com.oruke.tagloom.core.model.HeadingBlockContent
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.NoteReferenceBlockContent
import com.oruke.tagloom.core.model.RichText
import com.oruke.tagloom.core.model.RichTextBlockContent
import com.oruke.tagloom.core.model.TabBlockContent
import com.oruke.tagloom.core.model.TabsBlockContent
import com.oruke.tagloom.core.model.TodoBlockContent
import com.oruke.tagloom.core.model.UnknownBlockContent

/**
 * 维护 Markdown 粘贴中的加注身份隔离和锚点生命周期，不删除既有加注正文。
 */
internal object MarkdownPasteAnnotationReducer {
    /**
     * 为与目标笔记冲突的导入加注重新分配身份，并同步改写所有结构化文本锚点。
     *
     * @param imported 已完成 Markdown 解析的临时笔记。
     * @param existing 目标笔记，用于保留既有加注及其身份。
     * @param idFactory 提供不依赖剪贴板文字的全新不透明加注 ID。
     * @return 没有既有身份冲突的导入笔记；原笔记不发生修改。
     */
    fun isolateImported(
        imported: Note,
        existing: Note,
        idFactory: () -> AnnotationId,
    ): Note {
        // 先占用全部输入身份，避免新生成的 ID 与尚未处理的导入定义互相覆盖。
        val reservedIds = (existing.annotations.keys + imported.annotations.keys).toMutableSet()
        val replacements = imported.annotations.keys
            .filter(existing.annotations::containsKey)
            .associateWith {
                idFactory().also { generated ->
                    require(reservedIds.add(generated)) { "粘贴加注 ID 工厂必须生成独立身份" }
                }
            }
        if (replacements.isEmpty()) return imported

        // 加注定义与文本引用使用同一映射，防止表头或单元格继续引用目标笔记的旧正文。
        return imported.copy(
            blocks = imported.blocks.map { block ->
                block.copy(content = mapRichText(block.content) { richText ->
                    richText.copy(runs = richText.runs.map { run ->
                        run.copy(annotationIds = run.annotationIds.map { id -> replacements[id] ?: id })
                    })
                })
            },
            annotations = imported.annotations.values.associate { annotation ->
                val id = replacements[annotation.id] ?: annotation.id
                id to annotation.copy(id = id)
            },
        )
    }

    /**
     * 根据替换后的全部保留锚点更新孤立状态，只改变状态发生转换的加注时间。
     *
     * 锚点范围与 NoteValidator 一致，包含 tombstone 中尚未清理的引用；否则恢复内容
     * 会引用被错误标记为孤立的加注。没有任何锚点时仍保留正文，等待显式清理。
     *
     * @param note 完成 Block 替换和加注合并后的笔记。
     * @param updatedAt 本次粘贴的 UTC ISO-8601 时间。
     * @return 加注孤立状态与保留锚点一致的笔记。
     */
    fun reconcileOrphans(note: Note, updatedAt: String): Note {
        val referencedIds = mutableSetOf<AnnotationId>()
        note.blocks.forEach { block ->
            mapRichText(block.content) { richText ->
                richText.runs.forEach { run -> referencedIds.addAll(run.annotationIds) }
                richText
            }
        }
        return note.copy(annotations = note.annotations.mapValues { (id, annotation) ->
            val orphaned = id !in referencedIds
            if (annotation.orphaned == orphaned) annotation else {
                annotation.copy(orphaned = orphaned, updatedAt = updatedAt)
            }
        })
    }

    /**
     * 遍历支持加注的全部 RichText 位置，同时保留未知内容和非文本单元格。
     *
     * @param content 待映射的结构化 Block 内容。
     * @param transform 单个 RichText 的身份改写或引用收集规则。
     * @return 只替换 RichText 的结构化内容。
     */
    private fun mapRichText(
        content: BlockContent,
        transform: (RichText) -> RichText,
    ): BlockContent = when (content) {
        is RichTextBlockContent -> content.copy(richText = transform(content.richText))
        is HeadingBlockContent -> content.copy(richText = transform(content.richText))
        is TodoBlockContent -> content.copy(richText = transform(content.richText))
        is AttachmentBlockContent -> content.copy(caption = transform(content.caption))
        is TabBlockContent -> content.copy(title = transform(content.title))
        is BaseTableBlockContent -> content.copy(
            title = transform(content.title),
            columns = content.columns.map { column -> column.copy(name = transform(column.name)) },
            rows = content.rows.map { row ->
                row.copy(cells = row.cells.mapValues { (_, cell) ->
                    if (cell is BaseTableTextCell) cell.copy(richText = transform(cell.richText)) else cell
                })
            },
        )
        is CodeBlockContent,
        is DatabaseBlockContent,
        DividerBlockContent,
        is NoteReferenceBlockContent,
        TabsBlockContent,
        is UnknownBlockContent,
        -> content
    }
}
