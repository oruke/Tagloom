package com.oruke.tagloom.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.model.DatabaseId
import com.oruke.tagloom.core.model.ViewId
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.Res
import tagloom.app.shared.generated.resources.database_add_view
import tagloom.app.shared.generated.resources.database_default_new_view_name
import tagloom.app.shared.generated.resources.database_save_view_name

/**
 * 渲染 Database 的独立视图标签、创建入口和当前视图重命名交互。
 *
 * @param controller Database 目录控制器。
 * @param databaseId 当前 Database ID。
 * @param selectedViewId 当前 Database Block 会话中选择的 View ID。
 * @param onSelectView 更新当前 Block 会话 View 的动作。
 * @param modifier 外部布局修饰符。
 */
@Composable
internal fun DatabaseViewTabs(
    controller: WorkspaceDatabaseController,
    databaseId: DatabaseId,
    selectedViewId: ViewId,
    onSelectView: (ViewId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ownViews = controller.viewsFor(databaseId)
    val selectedView = ownViews.firstOrNull { view -> view.id == selectedViewId } ?: return
    val scope = rememberCoroutineScope()
    val defaultViewName = stringResource(Res.string.database_default_new_view_name, ownViews.size + 1)
    var viewNameDraft by remember(selectedView.id, selectedView.name) { mutableStateOf(selectedView.name) }
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ownViews.forEach { view ->
            TextButton(onClick = { onSelectView(view.id) }) {
                Text(
                    view.name,
                    color = if (view.id == selectedView.id) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontWeight = if (view.id == selectedView.id) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
        OutlinedButton(
            onClick = {
                scope.launch {
                    controller.createView(databaseId, selectedView.id, defaultViewName)?.let(onSelectView)
                }
            },
            enabled = !controller.busy,
        ) {
            Text("+ ${stringResource(Res.string.database_add_view)}")
        }
        BasicTextField(
            value = viewNameDraft,
            onValueChange = { value -> viewNameDraft = value },
            modifier = Modifier.width(148.dp)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                .padding(horizontal = 9.dp, vertical = 7.dp),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        )
        TextButton(
            onClick = {
                scope.launch {
                    controller.updateView(selectedView.id) { current -> current.copy(name = viewNameDraft.trim()) }
                }
            },
            enabled = viewNameDraft.isNotBlank() && viewNameDraft != selectedView.name && !controller.busy,
        ) {
            Text(stringResource(Res.string.database_save_view_name))
        }
    }
}
