package com.oruke.tagloom.editor

import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.core.model.TagId
import com.oruke.tagloom.core.workspace.WorkspaceDefaults

/**
 * 按名称检索超级标签，并始终将内置 Note 放在首位。
 * @param query 用户输入；忽略首尾空白和大小写。
 * @return 排序稳定的匹配目录，不推断任何图标。
 */
internal fun TagloomEditorState.findSupertags(query: String): List<TagDefinition> = tagDefinitions
    .filter { it.name.contains(query.trim(), ignoreCase = true) }
    .sortedWith(compareBy<TagDefinition> { it.id != WorkspaceDefaults.DEFAULT_NOTE_TAG_ID }
        .thenBy { it.name.lowercase() }.thenBy { it.id.value })

/**
 * 只在用户确认超级标签后创建笔记，再应用其默认值；取消选择不经过此入口。
 * @param tagId 已确认目录中的超级标签 ID。
 */
internal fun TagloomEditorState.createDocumentWithSupertag(tagId: TagId) {
    require(tagDefinitions.any { it.id == tagId }) { "missing-supertag" }
    createDocument()
    if (tagId != WorkspaceDefaults.DEFAULT_NOTE_TAG_ID) setAppliedObjectType(tagId)
    filterText = ""
}

/**
 * 判断笔记是否直接或经父级继承引用超级标签。
 * @param note 待检查笔记。
 * @param tagId 被引用的超级标签。
 * @return 笔记的可达继承闭包包含目标时为 true。
 */
internal fun TagloomEditorState.referencesSupertag(note: Note, tagId: TagId): Boolean {
    val visited = mutableSetOf<TagId>()
    val pending = note.directTagIds.toMutableList()
    while (pending.isNotEmpty()) {
        val current = pending.removeAt(pending.lastIndex)
        if (!visited.add(current)) continue
        if (current == tagId) return true
        pending += tagDefinitions.firstOrNull { it.id == current }?.parentTagIds.orEmpty()
    }
    return tagId == WorkspaceDefaults.DEFAULT_NOTE_TAG_ID
}
