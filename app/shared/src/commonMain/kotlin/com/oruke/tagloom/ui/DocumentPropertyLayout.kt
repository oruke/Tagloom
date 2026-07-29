package com.oruke.tagloom.ui

import com.oruke.tagloom.core.domain.property.EffectiveProperty

/**
 * 区分属性在标题下动态属性区中的确定性展示位置。
 */
internal enum class DocumentPropertyPlacement {
    /** 描述下方默认展开。 */
    PRIMARY,

    /** 收纳在“更多属性”入口内。 */
    MORE,

    /** 常规编辑器中隐藏但继续保留值和绑定。 */
    HIDDEN,
}

/**
 * 按合并后的属性样式计算展示位置。
 *
 * @return 隐藏、更多或默认展开位置。
 */
internal fun EffectiveProperty.documentPlacement(): DocumentPropertyPlacement = when {
    style.visible == false -> DocumentPropertyPlacement.HIDDEN
    style.collapsed == true -> DocumentPropertyPlacement.MORE
    else -> DocumentPropertyPlacement.PRIMARY
}
