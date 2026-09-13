package com.oruke.tagloom.ui.search

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.oruke.tagloom.editor.search.NoteSearchMatch
import com.oruke.tagloom.editor.search.NoteSearchState
import com.oruke.tagloom.editor.search.NoteSearchTarget

/** 当前笔记的临时查找上下文，只向编辑画布内的文本字段传播。 */
internal val LocalNoteSearch = staticCompositionLocalOf<NoteSearchState?> { null }

/** 测试和辅助技术可用于识别目标的稳定字段信息，不包含正文。 */
internal val NoteSearchTargetKey = SemanticsPropertyKey<NoteSearchTarget>("NoteSearchTarget")

/** 当前字段是否包含正在定位的匹配。 */
internal val NoteSearchActiveKey = SemanticsPropertyKey<Boolean>("NoteSearchActive")

/** 把查找高亮与字符定位接入已有文本控件，不接触业务文本值。 */
internal data class NoteSearchDecoration(
    /** 定位字段及滚动祖先所需的修饰符。 */
    val modifier: Modifier,
    /** 接收文本布局，用于定位匹配字符而非仅定位整个长 Block。 */
    val onTextLayout: (TextLayoutResult) -> Unit,
    /** 原文到显示文本的高亮动作，保留既有富文本和代码样式。 */
    val highlight: (AnnotatedString) -> AnnotatedString,
) {
    /**
     * 把查找样式叠加到现有代码或文本视觉变换之后。
     * @param base 保持原文 UTF-16 偏移的已有变换。
     * @return 同时包含原有样式与查找高亮的显示变换。
     */
    fun transformation(base: VisualTransformation = VisualTransformation.None): VisualTransformation =
        VisualTransformation {
            val transformed = base.filter(it)
            TransformedText(highlight(transformed.text), OffsetMapping.Identity)
        }
}

/**
 * 根据字段身份显示全部匹配，并在根列表就绪后将当前匹配字符滚入视口。
 * @param target 当前可见字段的稳定定位；为空时不参与查找。
 * @param editable 当前字段是否是可恢复焦点的真实输入框；静态页签标题为 false。
 * @param onRestoreSelection 正文或代码输入框获得返回焦点后，恢复其本地字符选区的动作。
 * @return 可附加到现有 Text 或 BasicTextField 的显示能力。
 */
@Composable
internal fun rememberNoteSearchDecoration(
    target: NoteSearchTarget?,
    editable: Boolean = true,
    onRestoreSelection: ((Int, Int) -> Unit)? = null
): NoteSearchDecoration {
    val search = LocalNoteSearch.current
    val matches = search?.matches.orEmpty().filter { it.target == target }
    val active = search?.active?.takeIf { it.target == target }
    val sourceText = search?.texts?.firstOrNull { it.target == target }?.text
    val requester = remember { BringIntoViewRequester() }
    val focusRequester = remember(target) { FocusRequester() }
    val restoreSelection by rememberUpdatedState(onRestoreSelection)
    LaunchedEffect(search?.focusReturnVersion, search?.revealedFocusVersion) {
        if (editable && target != null && search != null && !search.isOpen && search.focusReturnVersion > 0 &&
            search.focusReturnVersion == search.revealedFocusVersion && search.returnFocusTarget == target
        ) {
            focusRequester.requestFocus()
            // 先聚焦再恢复非空选区，避免 BasicTextField 在仍失焦时把恢复的选区折叠为插入点。
            search.returnSelection?.takeIf { it.blockId == target.blockId }
                ?.let { restoreSelection?.invoke(it.start, it.end) }
            // 恢复请求只执行一次，避免此字段日后被 LazyColumn 重新创建时再次抢走焦点。
            search.revealedFocusVersion = -1
        }
    }
    val focusModifier = if (!editable || target == null) Modifier else Modifier.focusRequester(focusRequester)
        .onFocusChanged { search?.updateTextFocus(target, it.isFocused) }
    var layout by remember(target) { mutableStateOf<TextLayoutResult?>(null) }
    var fieldPosition by remember(target) { mutableStateOf<Offset?>(null) }
    val onMatchPosition by rememberUpdatedState(LocalNoteSearchMatchPosition.current)
    val inactiveBackground = MaterialTheme.colorScheme.primaryContainer
    val inactiveForeground = MaterialTheme.colorScheme.onPrimaryContainer
    val activeBackground = MaterialTheme.colorScheme.primary
    val activeForeground = MaterialTheme.colorScheme.onPrimary
    val navigationVersion = search?.navigationVersion
    val revealedVersion = search?.revealedVersion
    val layoutText = layout?.layoutInput?.text?.text
    LaunchedEffect(active, fieldPosition, layoutText) {
        val match = active ?: return@LaunchedEffect
        val result = layout ?: return@LaunchedEffect
        val position = fieldPosition ?: return@LaunchedEffect
        if (result.layoutInput.text.text == sourceText && match.end <= result.layoutInput.text.length) {
            onMatchPosition?.invoke(match, result.getBoundingBox(match.start).translate(position))
        }
    }
    LaunchedEffect(active, navigationVersion, revealedVersion, layoutText) {
        val result = layout ?: return@LaunchedEffect
        if (active != null && navigationVersion == revealedVersion && result.layoutInput.text.text == sourceText && active.end <= result.layoutInput.text.length) {
            // 根 LazyColumn 先创建目标项，再由字符矩形继续驱动嵌套表格或代码的横向滚动。
            val first = result.getBoundingBox(active.start)
            val last = result.getBoundingBox(active.end - 1)
            val bounds = if (first.top == last.top) Rect(
                minOf(first.left, last.left),
                first.top,
                maxOf(first.right, last.right),
                first.bottom
            ) else first
            requester.bringIntoView(bounds.inflate(6f))
        }
    }
    return NoteSearchDecoration(
        modifier = if (target == null) Modifier else focusModifier.bringIntoViewRequester(requester)
            .onGloballyPositioned { fieldPosition = it.positionInRoot() }
            .semantics { this[NoteSearchTargetKey] = target; this[NoteSearchActiveKey] = active != null },
        onTextLayout = { layout = it },
        highlight = { text ->
            if (text.text != sourceText) text else highlightNoteMatches(
                text,
                matches,
                active,
                inactiveBackground,
                inactiveForeground,
                activeBackground,
                activeForeground
            )
        },
    )
}

/**
 * 对可见原文叠加查找色，所有样式与注解只存在于显示结果。
 * @param text 带有既有富文本或代码颜色的原文。
 * @param matches 当前字段的匹配范围。
 * @param active 当前匹配。
 * @param background 普通匹配底色。
 * @param foreground 普通匹配文字色。
 * @param activeBackground 当前匹配底色。
 * @param activeForeground 当前匹配文字色。
 * @return 字符与偏移均不变的高亮文本。
 */
internal fun highlightNoteMatches(
    text: AnnotatedString,
    matches: List<NoteSearchMatch>,
    active: NoteSearchMatch?,
    background: Color,
    foreground: Color,
    activeBackground: Color,
    activeForeground: Color
): AnnotatedString {
    if (matches.isEmpty()) return text
    return AnnotatedString.Builder(text).apply {
        matches.filter { it.start >= 0 && it.end <= text.length }.forEach { match ->
            val current = match == active
            addStyle(
                SpanStyle(
                    color = if (current) activeForeground else foreground,
                    background = if (current) activeBackground else background
                ), match.start, match.end
            )
            addStringAnnotation("note-search", if (current) "active" else "match", match.start, match.end)
        }
    }.toAnnotatedString()
}
