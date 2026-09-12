package com.oruke.tagloom.workspace

import java.nio.file.Path

/** 本地工作区和密文备份的宿主入口；外部 URI 不进入事务层。 */
interface WorkspaceDocumentAccess {
    /**
     * 获取新工作区目录，Android 使用应用私有路径。
     * @return 用户取消时为空。
     */
    suspend fun creationDirectory(): Path?

    /**
     * 获取需要重新认证的已有工作区目录。
     * @return 没有选择或不存在时为空。
     */
    suspend fun recoveryDirectory(): Path?

    /**
     * 把完整密文备份发送到用户选择的位置。
     * @param writeArchive 在受控路径生成完整备份的业务操作。
     * @return 用户确认且复制完成为 true。
     */
    suspend fun exportBackup(writeArchive: suspend (Path) -> Unit): Boolean

    /**
     * 把选中的密文备份送入恢复流程，宿主负责临时文件清理。
     * @param restore 使用备份路径和空目标目录执行恢复的操作。
     * @return 取消时为空，否则为已确认恢复结果。
     */
    suspend fun <T> importBackup(restore: suspend (Path, Path) -> T): T?
}

/**
 * 获取宿主已绑定的文档入口。
 * @return 当前宿主的目录和备份交互实现。
 */
internal expect fun platformWorkspaceDocuments(): WorkspaceDocumentAccess
