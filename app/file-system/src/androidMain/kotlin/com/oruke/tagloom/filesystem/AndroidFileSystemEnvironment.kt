package com.oruke.tagloom.filesystem

import android.content.Context
import java.nio.file.Path

/** 保存 Android 应用私有根目录；不持有 Activity、工作区密钥或外部文档 URI。 */
object AndroidFileSystemEnvironment {
    /** Application 私有且不参与自动备份的数据根目录。 */
    private var root: Path? = null

    /**
     * 在 Application 或 Activity 初始化时绑定应用私有目录。
     * @param context 仅使用其 applicationContext，不缓存生命周期较短的宿主。
     */
    fun initialize(context: Context) {
        // 系统私有目录可能经过 Android 的用户目录别名，先在可信 Context 边界解析真实根目录。
        root = context.applicationContext.noBackupFilesDir.canonicalFile.toPath().resolve("workspaces")
    }

    /**
     * 返回宿主已初始化的私有工作区父目录。
     * @return 不向共享 UI 暴露的实际文件路径。
     */
    fun workspaceDirectory(): Path = checkNotNull(root) { "Android 文件能力尚未初始化" }
}
