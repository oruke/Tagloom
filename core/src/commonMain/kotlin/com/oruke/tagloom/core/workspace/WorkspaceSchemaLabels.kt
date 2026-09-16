package com.oruke.tagloom.core.workspace

/** 平台入口为新工作区提供的默认 schema 文案；仅影响初次创建，不用于迁移既有对象。 */
data class WorkspaceSchemaLabels(
    /** 描述属性名称。 */
    val description: String,
    /** 普通标签属性名称。 */
    val tags: String,
    /** 状态属性名称。 */
    val status: String,
    /** 日期属性名称。 */
    val date: String,
    /** 初始待办选项名称。 */
    val todo: String,
    /** 初始完成选项名称。 */
    val done: String,
)
