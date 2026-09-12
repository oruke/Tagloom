package com.oruke.tagloom.workspace

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * 创建桌面文件选择入口。
 * @return 使用 Swing 的文件选择实现。
 */
internal actual fun platformWorkspaceDocuments(): WorkspaceDocumentAccess = DesktopWorkspaceDocuments()

/** 仅负责 Swing 目录、备份文件选择，工作区业务留在共享网关。 */
internal class DesktopWorkspaceDocuments : WorkspaceDocumentAccess {
    /**
     * 选择新工作区目录。
     * @return 用户确认的目录或空。
     */
    override suspend fun creationDirectory(): Path? = chooseDirectory(DesktopWorkspaceDialogText.createWorkspaceDirectoryTitle())

    /**
     * 选择需要恢复访问的工作区目录。
     * @return 用户确认的目录或空。
     */
    override suspend fun recoveryDirectory(): Path? = chooseDirectory(DesktopWorkspaceDialogText.recoverWorkspaceDirectoryTitle())

    /**
     * 选择目标并执行完整备份。
     * @param writeArchive 写入完整备份的操作。
     * @return 完成时为 true，取消时为 false。
     */
    override suspend fun exportBackup(writeArchive: suspend (Path) -> Unit): Boolean {
        val destination = chooseBackupDestination() ?: return false
        writeArchive(destination)
        return true
    }

    /**
     * 选择备份和空目录并执行恢复。
     * @param restore 已认证恢复流程。
     * @return 恢复结果或取消时为空。
     */
    override suspend fun <T> importBackup(restore: suspend (Path, Path) -> T): T? {
        val archive = chooseBackupArchive() ?: return null
        val target = chooseDirectory(DesktopWorkspaceDialogText.restoreWorkspaceDirectoryTitle()) ?: return null
        return restore(archive, target)
    }

    /**
     * 在 Swing 事件线程选择单个目录。
     *
     * @param title 目录选择器标题。
     * @return 用户确认的规范绝对路径；取消时为空。
     */
    private suspend fun chooseDirectory(title: String): Path? = withContext(Dispatchers.Swing) {
        val chooser = JFileChooser().apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
            isMultiSelectionEnabled = false
        }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile.toPath().toAbsolutePath().normalize()
        } else {
            null
        }
    }

    /**
     * 在 Swing 事件线程选择备份保存文件。
     *
     * @return 用户确认的规范绝对路径；取消时为空。
     */
    private suspend fun chooseBackupDestination(): Path? {
        val title = DesktopWorkspaceDialogText.backupDestinationTitle()
        val fileTypeDescription = DesktopWorkspaceDialogText.backupFileTypeDescription()
        return withContext(Dispatchers.Swing) {
            val chooser = JFileChooser().apply {
                dialogTitle = title
                fileSelectionMode = JFileChooser.FILES_ONLY
                isAcceptAllFileFilterUsed = false
                fileFilter = FileNameExtensionFilter(fileTypeDescription, "zip")
                selectedFile = java.io.File(DEFAULT_BACKUP_FILE_NAME)
            }
            if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                normalizeBackupFileName(chooser.selectedFile.toPath())
            } else {
                null
            }
        }
    }

    /**
     * 在 Swing 事件线程选择一个 Tagloom 密文备份。
     *
     * @return 用户确认的规范绝对路径；取消时为空。
     */
    private suspend fun chooseBackupArchive(): Path? {
        val title = DesktopWorkspaceDialogText.backupArchiveTitle()
        val fileTypeDescription = DesktopWorkspaceDialogText.backupFileTypeDescription()
        return withContext(Dispatchers.Swing) {
            val chooser = JFileChooser().apply {
                dialogTitle = title
                fileSelectionMode = JFileChooser.FILES_ONLY
                isAcceptAllFileFilterUsed = false
                fileFilter = FileNameExtensionFilter(fileTypeDescription, "zip")
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                chooser.selectedFile.toPath().toAbsolutePath().normalize()
            } else {
                null
            }
        }
    }

    /**
     * 为未输入约定后缀的备份目标补齐完整文件名。
     *
     * @param selected 用户选择路径。
     * @return 带 `.tagloom-backup.zip` 后缀的规范绝对路径。
     */
    private fun normalizeBackupFileName(selected: Path): Path {
        val normalized = selected.toAbsolutePath().normalize()
        return if (normalized.fileName.toString().endsWith(BACKUP_FILE_SUFFIX, ignoreCase = true)) {
            normalized
        } else {
            normalized.resolveSibling(normalized.fileName.toString() + BACKUP_FILE_SUFFIX)
        }
    }

    private companion object {
        /** 备份保存对话框的默认脱敏文件名。 */
        const val DEFAULT_BACKUP_FILE_NAME: String = "tagloom-backup.tagloom-backup.zip"

        /** Tagloom 备份约定后缀。 */
        const val BACKUP_FILE_SUFFIX: String = ".tagloom-backup.zip"
    }
}
