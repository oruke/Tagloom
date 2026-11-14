package com.oruke.tagloom.ui.code

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.ui.TagloomChevronDown
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 渲染代码块的轻量工具栏，语言与换行通过调用方保存，复制只传递原文。
 * @param language 当前快照中的语言标识。
 * @param code 复制时使用的代码原文。
 * @param wrap 是否启用自动换行。
 * @param onLanguageChange 保存语言选择的动作。
 * @param onWrapChange 保存换行偏好的动作。
 */
@Suppress("DEPRECATION")
@Composable
internal fun CodeBlockToolbar(
    language: String?,
    code: String,
    wrap: Boolean,
    onLanguageChange: (String?) -> Unit,
    onWrapChange: (Boolean) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(code) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPY_FEEDBACK_MILLIS)
            copied = false
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) { CodeLanguagePicker(language, onLanguageChange) }
        TextButton(
            onClick = { onWrapChange(!wrap) },
            modifier = Modifier.semantics {
                role = Role.Checkbox
                toggleableState = if (wrap) ToggleableState.On else ToggleableState.Off
            },
            colors = ButtonDefaults.textButtonColors(
                contentColor = if (wrap) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        ) {
            Text(stringResource(Res.string.editor_code_wrap), style = MaterialTheme.typography.labelMedium)
        }
        TextButton(
            onClick = {
                for (attempt in 0 until 8) {
                    if (runCatching { clipboard.setText(AnnotatedString(code)) }.isSuccess) break
                    if (attempt < 7) {
                        val mark = kotlin.time.TimeSource.Monotonic.markNow()
                        while (mark.elapsedNow().inWholeMilliseconds < 15L) {
                            // 等待宿主系统剪贴板瞬时占用释放，不引入特定平台线程 API。
                        }
                    }
                }
                copied = true
            },
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        ) {
            Text(
                stringResource(if (copied) Res.string.editor_code_copied else Res.string.editor_code_copy),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/**
 * 在代码块原位选择语言，并支持按名称与 Markdown 别名搜索。
 * @param language 原始语言标识，未知语言原样展示。
 * @param onLanguageChange 用户明确选择后写入语言标识，纯文本写入空值。
 */
@Composable
private fun CodeLanguagePicker(language: String?, onLanguageChange: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val plainText = stringResource(Res.string.editor_code_plain_text)
    val languageDescription = stringResource(Res.string.editor_code_language)
    val languageLabel = CodeLanguageCatalog.displayName(language) ?: plainText
    val languages = remember(query) { CodeLanguageCatalog.search(query) }
    val canonical = CodeLanguageCatalog.tokenizer.resolveLanguageId(language)?.value
    Box {
        TextButton(
            onClick = { query = ""; expanded = true },
            modifier = Modifier.widthIn(max = 200.dp).semantics { contentDescription = languageDescription },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
        ) {
            Text(
                languageLabel,
                modifier = Modifier.weight(1f, fill = false).padding(end = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TagloomChevronDown()
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(280.dp).heightIn(max = 360.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(Res.string.editor_code_language_search)) },
                    singleLine = true,
                )
            }
            if (query.isBlank() || plainText.contains(query.trim(), ignoreCase = true)) {
                DropdownMenuItem(
                    text = { Text(plainText) },
                    modifier = Modifier.semantics { selected = language.isNullOrBlank() },
                    onClick = { expanded = false; onLanguageChange(null) },
                )
            }
            languages.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    modifier = Modifier.semantics { selected = canonical == option.id },
                    onClick = { expanded = false; onLanguageChange(option.id) },
                )
            }
            if (languages.isEmpty() && !plainText.contains(query.trim(), ignoreCase = true)) {
                Text(
                    stringResource(Res.string.editor_code_language_empty),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** 复制成功反馈短暂停留，随后恢复可再次复制的动作标签。 */
private const val COPY_FEEDBACK_MILLIS = 1_800L
