package com.oruke.tagloom.editor

import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.CodeBlockContent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** 代码 Block 的自动换行显示偏好，缺省开启。 */
private const val CODE_WRAP_ATTRIBUTE = "codeWrap"

/**
 * 更新代码语言并通过原有笔记编辑事务保存，保留代码原文及其他属性。
 * @param blockId 目标代码 Block。
 * @param language 语言标识；空值表示纯文本，未知语言标识仍可保存。
 */
internal fun TagloomEditorState.updateCodeBlockLanguage(blockId: BlockId, language: String?) {
    updateBlock(blockId) { block ->
        val content = block.content as? CodeBlockContent ?: return@updateBlock block
        block.copy(content = content.copy(language = language?.trim()?.takeIf(String::isNotEmpty)))
    }
}

/**
 * 更新代码块的换行偏好，不向正文插入换行符，也不覆盖其他扩展属性。
 * @param blockId 目标代码 Block。
 * @param wrap 是否按可用宽度自动换行。
 */
internal fun TagloomEditorState.updateCodeBlockWrap(blockId: BlockId, wrap: Boolean) {
    updateBlock(blockId) { block ->
        if (block.content !is CodeBlockContent) return@updateBlock block
        val attributes = if (wrap) block.attributes - CODE_WRAP_ATTRIBUTE
        else block.attributes + (CODE_WRAP_ATTRIBUTE to JsonPrimitive(false))
        block.copy(attributes = JsonObject(attributes))
    }
}

/**
 * 读取代码块的显示偏好；旧笔记与无法识别的属性值均保持默认自动换行。
 * @return 是否启用软换行，不影响代码原文。
 */
internal fun Block.wrapsCodeLines(): Boolean =
    (attributes[CODE_WRAP_ATTRIBUTE] as? JsonPrimitive)?.booleanOrNull ?: true
