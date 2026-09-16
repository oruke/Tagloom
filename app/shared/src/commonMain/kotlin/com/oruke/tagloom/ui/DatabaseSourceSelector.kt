package com.oruke.tagloom.ui
import androidx.compose.foundation.layout.size

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.model.DatabaseDefinition
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.Res
import tagloom.app.shared.generated.resources.database_include_descendants
import tagloom.app.shared.generated.resources.database_source_supertag

/**
 * 在 Database Block 内联切换来源对象类型，不再要求创建前经过独立页面或弹窗。
 *
 * @param controller Database 定义写入控制器。
 * @param database 当前 Block 引用的 Database 定义。
 * @param sourceName 当前来源类型的用户可见名称。
 * @param enabled 工作区可写且没有其他 Database 事务时为 true。
 */
@Composable
internal fun DatabaseSourceSelector(
    controller: DatabaseEditorController,
    database: DatabaseDefinition,
    sourceName: String,
    enabled: Boolean,
) {
    var menuOpen by remember(database.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Box {
        Row(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), MaterialTheme.shapes.extraSmall)
                .clickable(enabled = enabled) { menuOpen = true }
                .padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = sourceName.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TagloomChevronDown(Modifier.size(10.dp).padding(start = 4.dp))
        }
        TagloomDropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            TagloomMenuItem(
                text = stringResource(Res.string.database_include_descendants),
                leadingIcon = if (database.includeDescendants) {
                    { Text("✓", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                } else null,
                selected = database.includeDescendants,
                onClick = {
                    scope.launch {
                        controller.updateDatabase(database.id) { current ->
                            current.copy(includeDescendants = !current.includeDescendants)
                        }
                    }
                    menuOpen = false
                },
            )
            TagloomMenuDivider()
            TagloomMenuSectionHeader(title = stringResource(Res.string.database_source_supertag))
            controller.tagDefinitions().forEach { tag ->
                val selected = tag.id == database.sourceTypeId
                TagloomMenuItem(
                    text = tag.name,
                    leadingIcon = if (selected) {
                        { Text("✓", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                    } else null,
                    selected = selected,
                    onClick = {
                        menuOpen = false
                        scope.launch {
                            controller.updateDatabase(database.id) { current ->
                                current.copy(sourceTypeId = tag.id, creationTypeId = tag.id)
                            }
                        }
                    },
                )
            }
        }
    }
}
