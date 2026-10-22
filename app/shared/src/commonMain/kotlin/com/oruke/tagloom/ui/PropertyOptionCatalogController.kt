package com.oruke.tagloom.ui

import androidx.compose.runtime.*
import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.editor.upsertPropertyDefinition
import com.oruke.tagloom.platform.WorkspaceGateway
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** 共享选项目录维护编排，先事务确认 schema，再让笔记或录入草稿引用其身份。 */
internal class PropertyOptionCatalogController(
    /** 已装配的权威写入端口，纯预览时为空。 */
    private val gateway: WorkspaceGateway?,
    /** 当前解锁会话目录和未保存笔记。 */
    private val editor: TagloomEditorState,
) {
    /** 锁定或换工作区后拒绝把旧目录结果重新放回 UI。 */
    private var epoch = 0

    /** 目录确认期间阻止重复提交。 */
    var busy by mutableStateOf(false)
        private set

    /** 仅记录脱敏失败状态，表单草稿由 UI 保留。 */
    var failed by mutableStateOf(false)
        private set

    /**
     * 同时检查内存草稿与权威快照，再保存完整目录。
     * @param definition 修改后的完整属性定义。
     * @return 已确认且更新目录时为真。
     */
    suspend fun save(definition: PropertyDefinition): Boolean {
        if (busy) return false
        val marker = epoch
        busy = true; failed = false
        return try {
            val previous = editor.propertyDefinitions.firstOrNull { it.id == definition.id }
            val removed = previous?.options.orEmpty().map { it.id }.toSet() - definition.options.map { it.id }.toSet()
            require(
                previous?.options.orEmpty().none { it.parentOptionId in removed }) { "property-option-has-children" }
            require(editor.documents.none {
                usedOptions(
                    it.note,
                    definition.id
                ).any { id -> id in removed }
            }) { "property-option-in-use" }
            val saved = gateway?.savePropertyDefinition(definition)?.definition ?: definition
            if (marker != epoch) return false
            editor.upsertPropertyDefinition(saved)
            true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (marker == epoch) failed = true; false
        } finally {
            if (marker == epoch) busy = false
        }
    }

    /** 锁定时失效旧回调并清理目录操作状态。 */
    fun clear() {
        epoch++; busy = false; failed = false
    }

    /**
     * 返回当前内存笔记实际使用的选项集合，供删除按钮解释禁用原因。
     * @param id 属性身份。
     * @return 被笔记或草稿引用的稳定选项 ID。
     */
    fun used(id: PropertyId): Set<String> = editor.documents.flatMap { usedOptions(it.note, id) }.toSet()
}

/**
 * 从单选或多选属性提取选项身份，空值不转换为“未分组”选项。
 * @param note 当前笔记。
 * @param id 属性身份。
 * @return 实际使用身份集合。
 */
internal fun usedOptions(note: Note, id: PropertyId): Set<String> = when (val value = note.propertyValues[id]?.value) {
    is JsonPrimitive -> if (value.isString) setOf(value.content) else emptySet()
    is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.takeIf { item -> item.isString }?.content }.toSet()
    else -> emptySet()
}

/** 共享层级选择与维护使用的会话目录编排，避免 UI 另写一条持久化路径。 */
internal val LocalPropertyOptionCatalog = staticCompositionLocalOf<PropertyOptionCatalogController?> { null }
