package com.oruke.tagloom.core.workspace

import com.oruke.tagloom.core.model.*

/** 链接预置类型的稳定身份与最小 schema；名称只在创建时取当前语言。 */
object LinkNoteSchemas {
    /** 链接类型稳定身份，不按显示名称识别。 */
    val TYPE_ID = TagId("builtin-link-note-type")

    /** 必填网页地址属性。 */
    val URL_ID = PropertyId("builtin-link-url-property")

    /** 可空的层级单选分组属性。 */
    val GROUP_ID = PropertyId("builtin-link-group-property")

    /**
     * 构造链接类型专属属性，不生成状态、日期或抓取状态。
     * @param groupName 当前语言中的分组名称。
     * @return URL 与空层级目录。
     */
    fun properties(groupName: String): List<PropertyDefinition> = listOf(
        PropertyDefinition(URL_ID, "URL", PropertyTypes.URL),
        PropertyDefinition(
            GROUP_ID,
            groupName,
            PropertyTypes.SELECT,
            optionInputMode = PropertyOptionInputMode.FREE,
            hierarchical = true
        ),
    )

    /**
     * 构造继承 Note 的普通链接类型。
     * @param name 当前语言中的链接类型名称。
     * @return 绑定 URL 和可空分组的定义。
     */
    fun tag(name: String): TagDefinition = TagDefinition(
        id = TYPE_ID, name = name, parentTagIds = listOf(WorkspaceDefaults.DEFAULT_NOTE_TAG_ID), iconKey = "link",
        propertyBindings = listOf(TagPropertyBinding(URL_ID, required = true), TagPropertyBinding(GROUP_ID, order = 1)),
    )
}
