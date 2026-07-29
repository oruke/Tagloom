package com.oruke.tagloom.core.model

/**
 * 使用非空字段覆盖当前属性样式。
 *
 * @param overlay 优先级更高的样式覆盖。
 * @return 合并后的属性样式。
 */
fun PropertyStyle.merge(overlay: PropertyStyle): PropertyStyle = PropertyStyle(
    icon = overlay.icon ?: icon,
    colorToken = overlay.colorToken ?: colorToken,
    display = overlay.display ?: display,
    order = overlay.order ?: order,
    visible = overlay.visible ?: visible,
    showInCard = overlay.showInCard ?: showInCard,
    showInList = overlay.showInList ?: showInList,
    collapsed = overlay.collapsed ?: collapsed,
    dateFormat = overlay.dateFormat ?: dateFormat,
    numberFormat = overlay.numberFormat ?: numberFormat,
    optionStyle = overlay.optionStyle ?: optionStyle,
    relationDisplay = overlay.relationDisplay ?: relationDisplay,
)
