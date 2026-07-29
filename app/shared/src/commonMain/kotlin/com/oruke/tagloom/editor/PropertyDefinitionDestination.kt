package com.oruke.tagloom.editor

import com.oruke.tagloom.core.model.TagId

/**
 * 描述新属性定义确认后要进入的产品层级。
 *
 * 属性定义始终属于加密工作区 schema；该目标只决定它是立即绑定当前笔记、绑定超级标签，
 * 还是仅进入工作区目录等待后续复用。
 */
sealed interface PropertyDefinitionDestination {
    /** 创建定义并仅在当前笔记建立手动属性绑定。 */
    data object CurrentNote : PropertyDefinitionDestination

    /** 仅创建工作区级定义，不立即绑定任何对象。 */
    data object Workspace : PropertyDefinitionDestination

    /**
     * 创建定义并把它加入指定超级标签的属性 schema。
     *
     * @property tagId 接收新属性绑定的标签 ID。
     */
    data class Tag(
        /** 接收新属性绑定的标签 ID。 */
        val tagId: TagId,
    ) : PropertyDefinitionDestination
}
