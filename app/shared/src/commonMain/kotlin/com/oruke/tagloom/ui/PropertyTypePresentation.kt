package com.oruke.tagloom.ui

import com.oruke.tagloom.core.model.PropertyTypes

/**
 * 返回属性 schema 类型的紧凑中文名称。
 *
 * @return 用户可识别的属性类型。
 */
internal fun String.displayName(): String = when (this) {
    PropertyTypes.TEXT -> "文本"
    PropertyTypes.NUMBER -> "数字"
    PropertyTypes.BOOLEAN -> "复选框"
    PropertyTypes.DATE -> "日期"
    PropertyTypes.DATETIME -> "日期时间"
    PropertyTypes.SELECT -> "单选"
    PropertyTypes.MULTI_SELECT -> "多选"
    PropertyTypes.URL -> "链接"
    PropertyTypes.EMAIL -> "邮箱"
    PropertyTypes.RELATION -> "关系"
    PropertyTypes.FILE -> "文件"
    PropertyTypes.FORMULA -> "公式"
    else -> this
}
