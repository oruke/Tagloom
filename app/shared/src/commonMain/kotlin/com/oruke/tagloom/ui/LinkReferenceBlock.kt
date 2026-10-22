package com.oruke.tagloom.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.core.workspace.LinkNoteSchemas
import com.oruke.tagloom.editor.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 用目标 Note 当前内容渲染引用，主体进笔记，独立按钮开网页；不复制网页数据。
 * @param state 已解锁笔记目录。
 * @param block 当前引用 Block。
 * @param onOpenNote 打开目标内部笔记。
 */
@Composable
internal fun LinkReferenceBlock(state: TagloomEditorState, block: Block, onOpenNote: (ObjectId) -> Unit) {
    val content = block.content as NoteReferenceBlockContent
    val target = state.documents.firstOrNull { it.id == content.noteId }
    val url = target?.note?.let(LinkNotePolicy::address)
    val links = LocalWorkspaceLinks.current
    val uri = LocalUriHandler.current
    var menu by remember(block.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val compact = (block.attributes["referenceDisplay"] as? JsonPrimitive)?.content == "inline"
    Surface(
        Modifier.fillMaxWidth().testTag("note-reference-${block.id.value}"),
        color = MaterialTheme.colorScheme.surface,
        border = if (compact) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = MaterialTheme.shapes.medium
    ) {
        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(
                    Modifier.weight(1f).clickable(enabled = target != null) { onOpenNote(content.noteId) }
                        .testTag("reference-open-${block.id.value}").padding(if (compact) 8.dp else 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (!compact && url != null) Text(
                        url.host,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        target?.note?.title ?: stringResource(Res.string.link_unavailable),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = if (compact) 1 else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!compact && target != null) {
                        val description = LinkNotePolicy.description(target.note)
                        if (description.isNotBlank()) Text(
                            description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        val group = state.propertyDefinitions.firstOrNull { it.id == LinkNoteSchemas.GROUP_ID }
                        val groupId =
                            (target.note.propertyValues[LinkNoteSchemas.GROUP_ID]?.value as? JsonPrimitive)?.content
                        val path = group?.let { PropertyOptionTree.label(it.options, groupId) }.orEmpty()
                        if (path.isNotBlank()) Text(
                            path,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(Modifier.padding(top = if (compact) 0.dp else 6.dp, end = 4.dp)) {
                    if (url != null) IconButton(
                        onClick = { runCatching { uri.openUri(url.value) } },
                        Modifier.size(32.dp).testTag("reference-web-${block.id.value}")
                    ) {
                        Icon(
                            painterResource(Res.drawable.ic_open_external),
                            stringResource(Res.string.link_open_web),
                            Modifier.size(18.dp)
                        )
                    }
                    Box {
                        IconButton(
                            onClick = { menu = true },
                            Modifier.size(32.dp)
                        ) {
                            Icon(
                                painterResource(Res.drawable.ic_more),
                                stringResource(Res.string.link_reference_menu),
                                Modifier.size(18.dp)
                            )
                        }
                        TagloomDropdownMenu(menu, { menu = false }) {
                            TagloomMenuItem(
                                stringResource(Res.string.link_open_note),
                                enabled = target != null,
                                onClick = { menu = false; onOpenNote(content.noteId) })
                            if (url != null) {
                                TagloomMenuItem(
                                    stringResource(Res.string.link_open_web),
                                    onClick = { menu = false; runCatching { uri.openUri(url.value) } })
                                TagloomMenuItem(
                                    stringResource(Res.string.link_refresh),
                                    onClick = { menu = false; links?.refreshInBackground(content.noteId) })
                            }
                            TagloomMenuItem(
                                stringResource(if (compact) Res.string.link_reference_preview else Res.string.link_reference_inline),
                                onClick = {
                                    menu = false; state.updateBlock(block.id) {
                                    it.copy(
                                        attributes = JsonObject(
                                            it.attributes + ("referenceDisplay" to JsonPrimitive(
                                                if (compact) "preview" else "inline"
                                            ))
                                        )
                                    )
                                }
                                })
                            TagloomMenuDivider()
                            if (links?.undoCreation?.let { it.referenceId == block.id && it.sourceId == state.selectedDocumentId } == true) TagloomMenuItem(
                                stringResource(Res.string.link_undo),
                                onClick = { menu = false; scope.launch { links.undoLastCreation() } })
                            TagloomMenuItem(
                                stringResource(Res.string.link_remove_reference),
                                destructive = true,
                                onClick = { menu = false; state.deleteBlock(block.id) })
                        }
                    }
                }
            }
            if (target == null) NoteReferenceTargetPicker(state, block)
        }
    }
}
