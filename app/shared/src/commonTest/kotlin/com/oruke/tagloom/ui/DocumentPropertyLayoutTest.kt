package com.oruke.tagloom.ui

import com.oruke.tagloom.core.domain.property.EffectiveProperty
import com.oruke.tagloom.core.domain.property.EffectivePropertyValueSource
import com.oruke.tagloom.core.model.PropertyId
import com.oruke.tagloom.core.model.PropertyStyle
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 验证属性展示分组只由最终合并样式决定且结果稳定。
 */
class DocumentPropertyLayoutTest {
    /**
     * 验证隐藏优先于折叠，未声明样式时默认展开。
     */
    @Test
    fun resolvesPrimaryMoreAndHiddenPlacements() {
        assertEquals(DocumentPropertyPlacement.PRIMARY, property(PropertyStyle()).documentPlacement())
        assertEquals(DocumentPropertyPlacement.MORE, property(PropertyStyle(collapsed = true)).documentPlacement())
        assertEquals(
            DocumentPropertyPlacement.HIDDEN,
            property(PropertyStyle(visible = false, collapsed = true)).documentPlacement(),
        )
    }

    /**
     * 构造只用于展示分组测试的最小有效属性。
     *
     * @param style 待验证最终样式。
     * @return 可调用展示分组规则的有效属性。
     */
    private fun property(style: PropertyStyle): EffectiveProperty = EffectiveProperty(
        propertyId = PropertyId("property-test"),
        definition = null,
        binding = null,
        sourceTagId = null,
        value = null,
        valueSource = EffectivePropertyValueSource.NONE,
        requiresSnapshot = false,
        style = style,
        conflicted = false,
    )
}
