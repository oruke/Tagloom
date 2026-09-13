package com.oruke.tagloom.ui.code

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 仅生成显示层的词法颜色；原文、选区和加密保存内容始终保持独立。 */
internal object CodeSyntaxHighlighter {
    /**
     * 为原文生成本地词法高亮，未知语言或分词失败自然退化为纯文本。
     * @param code 包括空白与换行的原始代码。
     * @param language 快照中的语言标识或 Markdown 别名。
     * @param palette 与当前主题相符的语法配色。
     * @return 文本和 UTF-16 偏移均与原文一致的带颜色字符串。
     */
    fun highlight(code: String, language: String?, palette: CodeSyntaxPalette): AnnotatedString =
        buildAnnotatedString {
            append(code)
            CodeLanguageCatalog.tokenizer.tokenize(code, language).forEach { token ->
                palette.colorFor(token.role.value)?.let { color ->
                    addStyle(SpanStyle(color = color), token.start, token.endExclusive)
                }
            }
        }
}

/**
 * 在后台重算语法颜色，仅将当前文本对应的结果应用到输入框。
 * @param code 当前输入原文。
 * @param language 当前语言选择。
 * @param palette 当前语法主题。
 * @return 保持输入法组合区和选区偏移不变的视觉变换。
 */
@Composable
internal fun rememberCodeVisualTransformation(
    code: String,
    language: String?,
    palette: CodeSyntaxPalette,
): VisualTransformation {
    // 语言或主题变化时立刻丢弃旧配色；缓存只活在当前代码块的组合生命周期中。
    var highlighted by remember(language, palette) { mutableStateOf(AnnotatedString("")) }
    LaunchedEffect(code, language, palette) {
        highlighted = withContext(Dispatchers.Default) {
            CodeSyntaxHighlighter.highlight(code, language, palette)
        }
    }
    return remember(highlighted) {
        VisualTransformation { text ->
            // 快速输入时后台结果可能暂时落后，不能把旧偏移套到新文本，更不能修改 TextFieldValue。
            TransformedText(if (highlighted.text == text.text) highlighted else text, OffsetMapping.Identity)
        }
    }
}
