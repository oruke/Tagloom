package com.oruke.tagloom.core.workspace

import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.TagId
import com.oruke.tagloom.core.model.PropertyId

/**
 * 定义所有工作区必须具备的稳定内置对象身份。
 */
object WorkspaceDefaults {
    /** 新建 Note 默认关联的根标签 ID；标签名称仍只存在于加密快照。 */
    val DEFAULT_NOTE_TAG_ID: TagId = TagId("default-note-tag")

    /** 工作区级 Agent 连接、模型上下文窗口与运行偏好单例配置快照的稳定对象 ID。 */
    val DEFAULT_WORKSPACE_CONFIG_ID: ObjectId = ObjectId("workspace-config")

    /** MVP 文本属性的稳定 ID。 */
    val DEFAULT_TEXT_PROPERTY_ID: PropertyId = PropertyId("default-note-text-property")

    /** 普通标签自由多选属性的稳定 ID。 */
    val DEFAULT_TAGS_PROPERTY_ID: PropertyId = PropertyId("default-note-tags-property")

    /** MVP 单选属性的稳定 ID。 */
    val DEFAULT_SELECT_PROPERTY_ID: PropertyId = PropertyId("default-note-select-property")

    /** MVP 日期属性的稳定 ID。 */
    val DEFAULT_DATE_PROPERTY_ID: PropertyId = PropertyId("default-note-date-property")

    /** 默认单选属性的待办选项 ID。 */
    const val DEFAULT_SELECT_TODO_OPTION_ID: String = "todo"

    /** 默认单选属性的完成选项 ID。 */
    const val DEFAULT_SELECT_DONE_OPTION_ID: String = "done"
}
