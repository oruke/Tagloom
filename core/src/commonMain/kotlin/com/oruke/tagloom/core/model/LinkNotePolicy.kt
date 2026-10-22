package com.oruke.tagloom.core.model

import com.oruke.tagloom.core.workspace.LinkNoteSchemas
import com.oruke.tagloom.core.workspace.WorkspaceDefaults
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 链接笔记创建、URL 校验及自动字段合并规则，所有调用入口复用同一来源保护。 */
object LinkNotePolicy {
    /**
     * 读取笔记的网页地址，未设置或非字符串值返回空。
     * @param note 待读取笔记。
     * @return 规范化地址。
     */
    fun address(note: Note): LinkUrl? = (note.propertyValues[LinkNoteSchemas.URL_ID]?.value as? JsonPrimitive)
        ?.contentOrNull?.let(LinkUrl::parse)

    /**
     * 读取继承描述，不另建 Note.description 字段。
     * @param note 当前笔记。
     * @return 描述文本，无值时为空。
     */
    fun description(note: Note): String =
        (note.propertyValues[WorkspaceDefaults.DEFAULT_TEXT_PROPERTY_ID]?.value as? JsonPrimitive)
            ?.contentOrNull.orEmpty()

    /**
     * 构造以网址为初始标题的新链接，网页信息缺失时仍可直接编辑标题和描述。
     * @param url 已校验网址。
     * @param groupId 分组身份或无值。
     * @param timestamp 当前 UTC 确认时间。
     * @return 可交给统一事务的新笔记。
     */
    fun create(url: LinkUrl, groupId: String?, timestamp: String): Note = Note(
        title = url.value, directTagIds = listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID, LinkNoteSchemas.TYPE_ID),
        propertyValues = buildMap {
            put(
                LinkNoteSchemas.URL_ID,
                NotePropertyValue(
                    JsonPrimitive(url.value),
                    PropertyValueSource.USER,
                    overridden = true,
                    updatedAt = timestamp
                )
            )
            if (groupId != null) put(
                LinkNoteSchemas.GROUP_ID,
                NotePropertyValue(
                    JsonPrimitive(groupId),
                    PropertyValueSource.USER,
                    overridden = true,
                    updatedAt = timestamp
                )
            )
        },
        linkMetadata = LinkMetadataProvenance(url.value, title = url.value),
    )

    /**
     * 对用户或外部入口修改关闭相应自动来源，防止稍后网络结果覆盖编辑。
     * @param previous 最近确认的笔记，创建时为空。
     * @param next 待写入笔记。
     * @return 保留手动字段来源的笔记。
     */
    fun protectManualEdits(previous: Note?, next: Note): Note {
        // 已确认的手动来源不能由普通 Note 替换请求重新伪装成自动来源。
        val source = previous?.linkMetadata ?: next.linkMetadata ?: return next
        return next.copy(
            linkMetadata = source.copy(
                automaticTitle = source.automaticTitle && next.linkMetadata?.automaticTitle != false && (previous == null || next.title == previous.title),
                automaticDescription = source.automaticDescription && next.linkMetadata?.automaticDescription != false && (previous == null || description(
                    next
                ) == description(previous)),
            )
        )
    }

    /**
     * 校验 URL 与层级分组值，空分组必须以缺少显式值表示。
     * @param note 待确认笔记。
     * @param groupDefinition 当前已确认分组目录。
     */
    fun validate(note: Note, groupDefinition: PropertyDefinition?) {
        require(address(note) != null) { "link-url-invalid" }
        val group = note.propertyValues[LinkNoteSchemas.GROUP_ID]?.value ?: return
        val id = (group as? JsonPrimitive)?.takeIf { it.isString }?.content
        require(groupDefinition?.options?.any { it.id == id } == true) { "link-group-invalid" }
    }

    /**
     * 按当前 URL 和字段来源合并网络结果；旧 URL 结果不产生写入。
     * @param note 最新已确认笔记。
     * @param requestedUrl 本次请求地址。
     * @param metadata 已净化的网页信息。
     * @param timestamp 新属性更新时间。
     * @return 合并结果；地址已改变或缺少自动来源时返回原笔记。
     */
    fun mergeMetadata(note: Note, requestedUrl: String, metadata: WebPageMetadata, timestamp: String): Note {
        if (address(note)?.value != requestedUrl) return note
        val source = note.linkMetadata ?: return note
        val automaticTitle = source.automaticTitle && note.title == source.title
        val title = if (automaticTitle) metadata.title?.ifBlank { null }
            ?: note.title else note.title
        val currentDescription = description(note)
        val automaticDescription = source.automaticDescription && currentDescription == source.description
        val description =
            if (automaticDescription) metadata.description.orEmpty() else currentDescription
        val values = note.propertyValues.toMutableMap()
        if (description != currentDescription) {
            if (description.isEmpty()) values.remove(WorkspaceDefaults.DEFAULT_TEXT_PROPERTY_ID)
            else values[WorkspaceDefaults.DEFAULT_TEXT_PROPERTY_ID] =
                NotePropertyValue(JsonPrimitive(description), PropertyValueSource.SYSTEM, updatedAt = timestamp)
        }
        return note.copy(
            title = title, propertyValues = values, linkMetadata = source.copy(
                url = requestedUrl, title = if (automaticTitle) title else source.title,
                description = if (automaticDescription) description else source.description,
                automaticTitle = automaticTitle, automaticDescription = automaticDescription,
            )
        )
    }
}
