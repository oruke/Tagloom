package com.oruke.tagloom.platform

import com.oruke.tagloom.core.workspace.WorkspaceSchemaLabels
import org.jetbrains.compose.resources.getString
import tagloom.app.shared.generated.resources.*

/**
 * 在新工作区初始化前解析当前平台语言的默认属性及选项名称。
 * @return 只用于本次初始化事务的本地化文案。
 */
internal suspend fun localizedWorkspaceSchemaLabels(): WorkspaceSchemaLabels = WorkspaceSchemaLabels(
    description = getString(Res.string.workspace_default_description),
    tags = getString(Res.string.workspace_default_tags),
    status = getString(Res.string.workspace_default_status),
    date = getString(Res.string.workspace_default_date),
    todo = getString(Res.string.workspace_default_todo),
    done = getString(Res.string.workspace_default_done),
)
