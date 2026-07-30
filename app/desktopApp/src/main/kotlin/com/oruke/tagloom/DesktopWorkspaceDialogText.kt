package com.oruke.tagloom

import org.jetbrains.compose.resources.getString
import tagloom.app.shared.generated.resources.*

/**
 * 集中提供 Swing 原生文件选择器需要的本地化文案。
 *
 * Swing 对话框运行在桌面平台边界，但仍直接读取共享 Compose 资源，避免在桌面 Gateway
 * 中复制中文和英文文本，或让原生对话框与共享 UI 使用两套 Locale 规则。
 */
internal object DesktopWorkspaceDialogText {
    /**
     * 返回选择空目录创建工作区的对话框标题。
     *
     * @return 当前系统语言对应的标题。
     */
    suspend fun createWorkspaceDirectoryTitle(): String =
        getString(Res.string.desktop_dialog_create_workspace_directory)

    /**
     * 返回使用 Recovery Key 连接既有工作区的目录选择器标题。
     *
     * @return 当前系统语言对应的标题。
     */
    suspend fun recoverWorkspaceDirectoryTitle(): String =
        getString(Res.string.desktop_dialog_recover_workspace_directory)

    /**
     * 返回恢复密文备份时选择空目录的对话框标题。
     *
     * @return 当前系统语言对应的标题。
     */
    suspend fun restoreWorkspaceDirectoryTitle(): String =
        getString(Res.string.desktop_dialog_restore_workspace_directory)

    /**
     * 返回创建密文备份时选择目标文件的对话框标题。
     *
     * @return 当前系统语言对应的标题。
     */
    suspend fun backupDestinationTitle(): String =
        getString(Res.string.desktop_dialog_save_backup)

    /**
     * 返回选择既有密文备份时的对话框标题。
     *
     * @return 当前系统语言对应的标题。
     */
    suspend fun backupArchiveTitle(): String =
        getString(Res.string.desktop_dialog_choose_backup)

    /**
     * 返回 Swing 文件过滤器中展示的 Tagloom 备份类型名称。
     *
     * @return 当前系统语言对应的文件类型名称。
     */
    suspend fun backupFileTypeDescription(): String =
        getString(Res.string.desktop_file_type_backup)
}
