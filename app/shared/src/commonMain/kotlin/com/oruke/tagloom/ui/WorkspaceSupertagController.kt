package com.oruke.tagloom.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import com.oruke.tagloom.editor.TagloomEditorState
import com.oruke.tagloom.editor.referencesSupertag
import com.oruke.tagloom.editor.setAppliedObjectType
import com.oruke.tagloom.editor.upsertPropertyDefinition
import com.oruke.tagloom.editor.upsertTagDefinition
import com.oruke.tagloom.editor.validateTagDefinition
import com.oruke.tagloom.platform.WorkspaceGateway
import kotlinx.coroutines.CancellationException

/** 在 UI 与统一工作区事务之间编排超级标签维护，保存失败保留编辑草稿。 */
internal class WorkspaceSupertagController(
    /** 当前会话权威写入入口；仅内存预览可为空。 */
    private val gateway: WorkspaceGateway?,
    /** 当前解锁会话的笔记和目录。 */
    private val editor: TagloomEditorState,
) {
    /** 是否有维护事务在执行，用于阻止重复提交和中途离开。 */
    var busy by mutableStateOf(false)
        private set

    /** 最近一次维护是否失败；异常细节不直接展示或记录。 */
    var failed by mutableStateOf(false)
        private set

    /** 清除上一次操作结果，新编辑会话不会继承旧错误。 */
    fun clearError() {
        failed = false
    }

    /**
     * 先确认新属性目录，再保存完整超级标签；只有确认成功才关闭表单或应用到笔记。
     * @param definition 待确认超级标签。
     * @param newProperties 表单中新增且尚未持久化的属性。
     * @param attach 保存后是否应用到当前笔记。
     * @return 保存成功时为 true；失败时保持原表单可重试。
     */
    suspend fun save(definition: TagDefinition, newProperties: List<PropertyDefinition>, attach: Boolean): Boolean {
        if (busy) return false
        busy = true
        failed = false
        return try {
            require(definition.id != WorkspaceDefaults.DEFAULT_NOTE_TAG_ID)
            require(editor.validateTagDefinition(definition).isEmpty())
            require(editor.tagDefinitions.none { it.id != definition.id && it.name.equals(definition.name, true) })
            // 属性先确认，失败重试复用草稿 ID；不会产生引用不存在属性的超级标签。
            for (property in newProperties) {
                val saved = gateway?.savePropertyDefinition(property)?.definition ?: property
                editor.upsertPropertyDefinition(saved)
            }
            val saved = gateway?.saveTagDefinition(definition)?.definition ?: definition
            editor.upsertTagDefinition(saved)
            if (attach) editor.setAppliedObjectType(saved.id)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
            false
        } finally {
            busy = false
        }
    }

    /**
     * 删除未被笔记草稿或子超级标签引用的定义，持久层会在会话锁内再次检查权威引用。
     * @param definition 用户确认删除的超级标签。
     * @return tombstone 已确认且内存目录已更新时为 true。
     */
    suspend fun delete(definition: TagDefinition): Boolean {
        if (busy) return false
        busy = true
        failed = false
        return try {
            require(definition.id != WorkspaceDefaults.DEFAULT_NOTE_TAG_ID)
            require(editor.documents.none { editor.referencesSupertag(it.note, definition.id) })
            require(editor.tagDefinitions.none { definition.id in it.parentTagIds })
            gateway?.deleteTagDefinition(definition.id)
            editor.tagDefinitions.removeAll { it.id == definition.id }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
            false
        } finally {
            busy = false
        }
    }
}
