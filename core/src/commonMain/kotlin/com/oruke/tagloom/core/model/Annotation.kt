package com.oruke.tagloom.core.model

import kotlinx.serialization.Serializable

/**
 * 定义 Tagloom 已知的行内加注类型名称。
 */
object AnnotationKinds {
    /** 普通补充说明。 */
    const val NOTE: String = "note"

    /** 面向审阅流程的评论。 */
    const val COMMENT: String = "comment"

    /** 术语或概念定义。 */
    const val DEFINITION: String = "definition"
}

/**
 * 表示笔记快照中可被一个或多个 InlineRun 引用的独立加注。
 */
@Serializable
data class Annotation(
    /** 加注的稳定标识。 */
    val id: AnnotationId,
    /** 加注类型；未知类型必须原样保留。 */
    val kind: String,
    /** 加注正文。 */
    val body: String,
    /** 加注创建时间，使用 UTC ISO-8601 文本。 */
    val createdAt: String,
    /** 加注最近更新时间，使用 UTC ISO-8601 文本。 */
    val updatedAt: String,
    /** 锚点被删除后是否等待用户确认清理。 */
    val orphaned: Boolean = false,
) {
    init {
        require(kind.isNotBlank()) { "加注类型不能为空" }
        require(createdAt.isNotBlank()) { "加注创建时间不能为空" }
        require(updatedAt.isNotBlank()) { "加注更新时间不能为空" }
    }
}
