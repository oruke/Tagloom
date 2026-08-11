package com.oruke.tagloom.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.model.DatabaseDefinition
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.Res
import tagloom.app.shared.generated.resources.database_change_source
import tagloom.app.shared.generated.resources.database_source_summary

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
    controller: WorkspaceDatabaseController,
    database: DatabaseDefinition,
    sourceName: String,
    enabled: Boolean,
) {
    var menuOpen by remember(database.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Box {
        TextButton(
            onClick = { menuOpen = true },
            enabled = enabled,
        ) {
            Text(
                stringResource(Res.string.database_source_summary, sourceName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            Text(
                stringResource(Res.string.database_change_source),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            controller.tagDefinitions().forEach { tag ->
                DropdownMenuItem(
                    text = {
                        Text(
                            tag.name,
                            color = if (tag.id == database.sourceTypeId) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    },
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
