package com.oruke.tagloom.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.platform.WorkspaceStorageMode
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.*

/**
 * 首次使用及设置共用的模式选择器，使用原生单选语义与足够的点击区域。
 * @param selected 当前选中模式。
 * @param onSelect 用户选择另一模式，调用方负责实际确认切换。
 * @param enabled 是否允许切换。
 */
@Composable
internal fun WorkspaceModeSelector(selected: WorkspaceStorageMode, onSelect: (WorkspaceStorageMode) -> Unit, enabled: Boolean) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        WorkspaceStorageMode.entries.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = selected == mode, onClick = { onSelect(mode) }, enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index, WorkspaceStorageMode.entries.size),
                modifier = Modifier.testTag("workspace-mode-${mode.name.lowercase()}"),
                icon = { Icon(painterResource(if (selected == mode) Res.drawable.ic_check else if (mode == WorkspaceStorageMode.LOCAL) Res.drawable.ic_folder else Res.drawable.ic_cloud),
                    contentDescription = null, modifier = Modifier.size(18.dp)) },
            ) { Text(stringResource(if (mode == WorkspaceStorageMode.LOCAL) Res.string.workspace_mode_local else Res.string.workspace_mode_server)) }
        }
    }
}
