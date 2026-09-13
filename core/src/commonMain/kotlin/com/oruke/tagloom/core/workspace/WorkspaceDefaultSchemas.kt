package com.oruke.tagloom.core.workspace

import com.oruke.tagloom.core.model.DefaultValuePolicy
import com.oruke.tagloom.core.model.PropertyDefinition
import com.oruke.tagloom.core.model.PropertyOption
import com.oruke.tagloom.core.model.PropertyOptionInputMode
import com.oruke.tagloom.core.model.PropertyTypes
import com.oruke.tagloom.core.model.TagDefinition
import com.oruke.tagloom.core.model.TagPropertyBinding

/**
 * 构造每个新工作区都以加密快照保存的最小 Note 标签与基础属性 schema。
 */
object WorkspaceDefaultSchemas {
    /**
     * 返回 MVP 描述、普通标签、单选和日期属性定义。
     *
     * @return 按界面顺序排列的四个内置属性定义。
     */
    fun propertyDefinitions(): List<PropertyDefinition> = listOf(
        PropertyDefinition(
            id = WorkspaceDefaults.DEFAULT_TEXT_PROPERTY_ID,
            name = "描述",
            type = PropertyTypes.TEXT,
        ),
        ordinaryTagsPropertyDefinition(),
        PropertyDefinition(
            id = WorkspaceDefaults.DEFAULT_SELECT_PROPERTY_ID,
            name = "状态",
            type = PropertyTypes.SELECT,
            options = listOf(
                PropertyOption(WorkspaceDefaults.DEFAULT_SELECT_TODO_OPTION_ID, "待办", "status-pending"),
                PropertyOption(WorkspaceDefaults.DEFAULT_SELECT_DONE_OPTION_ID, "完成", "status-success"),
            ),
        ),
        PropertyDefinition(
            id = WorkspaceDefaults.DEFAULT_DATE_PROPERTY_ID,
            name = "日期",
            type = PropertyTypes.DATE,
        ),
    )

    /**
     * 返回普通标签使用的内置自由多选属性。
     *
     * 该构造也用于旧工作区尚未落盘该定义时渲染兼容入口；首次创建标签后仍须通过统一
     * schema 事务确认，不能把这个返回值当作已持久化事实。
     *
     * @return 使用稳定内置 ID 且允许自由创建选项的属性定义。
     */
    fun ordinaryTagsPropertyDefinition(): PropertyDefinition = PropertyDefinition(
        id = WorkspaceDefaults.DEFAULT_TAGS_PROPERTY_ID,
        name = "标签",
        type = PropertyTypes.MULTI_SELECT,
        optionInputMode = PropertyOptionInputMode.FREE,
    )

    /**
     * 返回仅绑定描述和普通标签、不隐式写入默认值的根 Note 标签。
     *
     * @return 新工作区默认标签定义。
     */
    fun noteTagDefinition(): TagDefinition = TagDefinition(
        id = WorkspaceDefaults.DEFAULT_NOTE_TAG_ID,
        name = "Note",
        propertyBindings = listOf(
            WorkspaceDefaults.DEFAULT_TEXT_PROPERTY_ID,
            WorkspaceDefaults.DEFAULT_TAGS_PROPERTY_ID,
        ).mapIndexed { index, propertyId ->
            TagPropertyBinding(
                propertyId = propertyId,
                defaultPolicy = DefaultValuePolicy.NONE,
                order = index,
            )
        },
    )
}
