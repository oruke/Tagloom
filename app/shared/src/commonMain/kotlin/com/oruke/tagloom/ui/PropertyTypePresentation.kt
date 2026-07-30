package com.oruke.tagloom.ui

import androidx.compose.runtime.Composable
import com.oruke.tagloom.core.model.PropertyTypes
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 返回属性 schema 类型在当前 Locale 下的紧凑展示名称。
 *
 * @return 用户可识别的属性类型。
 */
@Composable
internal fun String.localizedDisplayName(): String = when (this) {
    PropertyTypes.TEXT -> stringResource(Res.string.editor_property_type_text)
    PropertyTypes.NUMBER -> stringResource(Res.string.editor_property_type_number)
    PropertyTypes.BOOLEAN -> stringResource(Res.string.editor_property_type_boolean)
    PropertyTypes.DATE -> stringResource(Res.string.editor_property_type_date)
    PropertyTypes.DATETIME -> stringResource(Res.string.editor_property_type_datetime)
    PropertyTypes.SELECT -> stringResource(Res.string.editor_property_type_select)
    PropertyTypes.MULTI_SELECT -> stringResource(Res.string.editor_property_type_multi_select)
    PropertyTypes.URL -> stringResource(Res.string.editor_property_type_url)
    PropertyTypes.EMAIL -> stringResource(Res.string.editor_property_type_email)
    PropertyTypes.RELATION -> stringResource(Res.string.editor_property_type_relation)
    PropertyTypes.FILE -> stringResource(Res.string.editor_property_type_file)
    PropertyTypes.FORMULA -> stringResource(Res.string.editor_property_type_formula)
    else -> this
}
