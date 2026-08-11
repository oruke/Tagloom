package com.oruke.tagloom.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oruke.tagloom.core.model.DatabaseBlockContent
import com.oruke.tagloom.core.model.ObjectId
import org.jetbrains.compose.resources.stringResource
import tagloom.app.shared.generated.resources.Res
import tagloom.app.shared.generated.resources.database_creating
import tagloom.app.shared.generated.resources.database_missing

/**
 * 在正文流中渲染一个 Database 引用 Block。
 *
 * @param controller 已解锁工作区的 Database 目录和写入控制器。
 * @param content Block 保存的稳定 Database 引用。
 * @param onOpenNote 从投影行打开原始 Note 的动作。
 */
@Composable
internal fun DatabaseBlockEditor(
    controller: WorkspaceDatabaseController?,
    content: DatabaseBlockContent,
    onOpenNote: (ObjectId) -> Unit,
) {
    if (controller?.database(content.databaseId) == null) {
        DatabaseBlockStatus(stringResource(Res.string.database_missing))
        return
    }
    DatabaseViewPane(
        controller = controller,
        databaseId = content.databaseId,
        onOpenNote = onOpenNote,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 渲染 Slash 命令已经确认、加密 Database 事务尚未完成的内联状态。 */
@Composable
internal fun DatabaseBlockCreatingPlaceholder() {
    DatabaseBlockStatus(stringResource(Res.string.database_creating))
}

/**
 * 渲染不会覆盖正文数据的 Database Block 状态说明。
 *
 * @param message 创建中或引用不可用的安全提示。
 */
@Composable
private fun DatabaseBlockStatus(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
