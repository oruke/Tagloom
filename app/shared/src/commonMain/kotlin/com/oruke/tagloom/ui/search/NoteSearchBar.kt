package com.oruke.tagloom.ui.search

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.SolidColor

import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.editor.search.NoteSearchState
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 渲染图标下方的紧凑查找浮层，宽屏单行、窄屏分开输入与导航。
 * @param search 当前笔记的会话查找状态。
 * @param onClose 关闭并把键盘焦点交还编辑器宿主。
 */
@Composable
internal fun NoteSearchBar(search: NoteSearchState, onClose: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.small,
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth().testTag("note-search-bar")
    ) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            val compact = maxWidth < 440.dp
            if (compact) {
                Column(Modifier.fillMaxWidth()) {
                    NoteSearchQuery(search, Modifier.fillMaxWidth())
                    NoteSearchActions(search, onClose, compact = true)
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    NoteSearchQuery(search, Modifier.weight(1f))
                    NoteSearchActions(search, onClose, compact = false)
                }
            }
        }
    }
}

/**
 * 接收字面量关键词，重复打开时全选关键词，输入焦点不随结果导航转移。
 * @param search 当前笔记的查找会话。
 * @param modifier 根据可用宽度分配的输入框布局。
 */
@Composable
private fun NoteSearchQuery(search: NoteSearchState, modifier: Modifier) {
    val requester = remember { FocusRequester() }
    var value by remember(search) { mutableStateOf(TextFieldValue(search.query)) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(search.focusVersion) {
        value = TextFieldValue(search.query, selection = TextRange(0, search.query.length))
        requester.requestFocus()
    }
    val placeholder = stringResource(Res.string.editor_find_placeholder)
    BasicTextField(
        value = value,
        onValueChange = { value = it; search.updateQuery(it.text) },
        modifier = modifier.heightIn(min = 40.dp).testTag("note-search-input").focusRequester(requester)
            .onFocusChanged { focused = it.isFocused }
            .border(
                1.dp,
                if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                MaterialTheme.shapes.extraSmall,
            )
            .semantics { contentDescription = placeholder }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                    search.move(event.isShiftPressed)
                    true
                } else false
            },
        decorationBox = { input ->
            Box(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
                if (value.text.isEmpty()) Text(
                    placeholder,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                input()
            }
        },
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { search.move() }),
    )
}

/**
 * 在同一行展示结果计数及导航，窄屏时独占第二行以保留输入空间。
 * @param search 当前笔记的查找会话。
 * @param onClose 关闭并恢复宿主焦点。
 * @param compact 是否使用窄屏的整行布局和触摸目标。
 */
@Composable
private fun NoteSearchActions(search: NoteSearchState, onClose: () -> Unit, compact: Boolean) {
    val count = stringResource(Res.string.editor_find_count, search.activeIndex + 1, search.matches.size)
    val description =
        if (search.matches.isEmpty() && search.query.isNotEmpty()) stringResource(Res.string.editor_find_empty)
        else stringResource(Res.string.editor_find_count_description, search.activeIndex + 1, search.matches.size)
    val matchCase = stringResource(Res.string.editor_find_match_case)
    val actionSize = if (compact) 48.dp else 40.dp
    Row(if (compact) Modifier.fillMaxWidth() else Modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            count,
            modifier = Modifier.padding(horizontal = 8.dp).testTag("note-search-count")
                .semantics { contentDescription = description; liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.labelMedium
        )
        if (compact) Spacer(Modifier.weight(1f))
        IconToggleButton(
            checked = search.matchCase, onCheckedChange = { search.toggleCase() }, modifier = Modifier.size(actionSize)
                .semantics { contentDescription = matchCase }) {
            Text(
                "Aa",
                style = MaterialTheme.typography.labelMedium,
                color = if (search.matchCase) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        NoteSearchButton(
            stringResource(Res.string.editor_find_previous),
            0,
            search.matches.isNotEmpty(),
            actionSize
        ) { search.move(backwards = true) }
        NoteSearchButton(
            stringResource(Res.string.editor_find_next),
            1,
            search.matches.isNotEmpty(),
            actionSize
        ) { search.move() }
        NoteSearchButton(stringResource(Res.string.editor_find_close), 2, true, actionSize, onClose)
    }
}

/**
 * 用同一笔画绘制上一处、下一处和关闭动作，保留原生按钮语义。
 * @param label 本地化无障碍名称。
 * @param icon 0 为向上，1 为向下，2 为关闭。
 * @param enabled 当前动作是否可用。
 * @param actionSize 当前设备所需的点击区域尺寸。
 * @param onClick 点击动作。
 */
@Composable
private fun NoteSearchButton(
    label: String,
    icon: Int,
    enabled: Boolean,
    actionSize: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit
) {
    IconButton(
        onClick,
        enabled = enabled,
        modifier = Modifier.size(actionSize).semantics { contentDescription = label }) {
        val color = LocalContentColor.current
        Canvas(Modifier.size(18.dp)) {
            val stroke = 1.6.dp.toPx()
            if (icon == 2) {
                drawLine(
                    color,
                    Offset(size.width * .25f, size.height * .25f),
                    Offset(size.width * .75f, size.height * .75f),
                    stroke,
                    StrokeCap.Round
                )
                drawLine(
                    color,
                    Offset(size.width * .75f, size.height * .25f),
                    Offset(size.width * .25f, size.height * .75f),
                    stroke,
                    StrokeCap.Round
                )
            } else {
                val outer = if (icon == 0) .65f else .35f
                val center = 1f - outer
                drawLine(
                    color,
                    Offset(size.width * .22f, size.height * outer),
                    Offset(size.width * .5f, size.height * center),
                    stroke,
                    StrokeCap.Round
                )
                drawLine(
                    color,
                    Offset(size.width * .5f, size.height * center),
                    Offset(size.width * .78f, size.height * outer),
                    stroke,
                    StrokeCap.Round
                )
            }
        }
    }
}
