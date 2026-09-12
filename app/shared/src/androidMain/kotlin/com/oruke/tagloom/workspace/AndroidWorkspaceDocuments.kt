package com.oruke.tagloom.workspace

import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.oruke.tagloom.filesystem.AndroidFileSystemEnvironment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Android 私有工作区与系统备份文档入口；只把密文备份复制到外部 URI。 */
class AndroidWorkspaceDocuments(
    /** 只保留 Application，用于私有缓存和 ContentResolver。 */
    private val context: Context,
) : WorkspaceDocumentAccess {
    /** 当前绑定的 Activity，用于避免旧宿主解绑新宿主。 */
    private var owner: ComponentActivity? = null
    /** 密文备份导出选择器。 */
    private var createDocument: ActivityResultLauncher<String>? = null
    /** 密文备份导入选择器。 */
    private var openDocument: ActivityResultLauncher<Array<String>>? = null
    /** 同一宿主只允许一个等待结果的文档请求。 */
    private var pending: CompletableDeferred<Uri?>? = null

    /**
     * 在 Activity onCreate 中注册系统文档入口；重建时更新宿主绑定。
     * @param activity 尚未进入 STARTED 的宿主。
     */
    fun attach(activity: ComponentActivity) {
        owner = activity
        createDocument = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) {
            pending?.complete(it)
        }
        openDocument = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) {
            pending?.complete(it)
        }
    }

    /**
     * 释放当前 Activity 引用；配置重建时保留等待结果的文档操作。
     * @param activity 即将销毁的宿主；旧宿主不能解除新绑定。
     */
    fun detach(activity: ComponentActivity) {
        if (owner !== activity) return
        if (!activity.isChangingConfigurations) pending?.complete(null)
        createDocument?.unregister()
        openDocument?.unregister()
        createDocument = null
        openDocument = null
        owner = null
    }

    /**
     * 返回应用私有的新工作区目录；目录非空时由创建服务拒绝覆盖。
     * @return 稳定的本机默认工作区路径。
     */
    override suspend fun creationDirectory(): Path = AndroidFileSystemEnvironment.workspaceDirectory().resolve("default")

    /**
     * 定位最近登记或默认工作区，Recovery Key 只用于重新认证。
     * @return 可重新认证的私有目录。
     */
    override suspend fun recoveryDirectory(): Path =
        DesktopWorkspaceAccessStore().registeredRootOrNull() ?: creationDirectory()

    /**
     * 先生成完整加密备份，再流式写入用户选中的文档。
     * @param writeArchive 生成受认证备份的业务操作。
     * @return 选择取消为 false，写入成功为 true；I/O 失败继续抛出。
     */
    override suspend fun exportBackup(writeArchive: suspend (Path) -> Unit): Boolean {
        val uri = chooseDocument(export = true) ?: return false
        return withContext(Dispatchers.IO) {
            val temporary = Files.createTempFile(context.cacheDir.canonicalFile.toPath(), "encrypted-backup-", ".zip")
            try {
                writeArchive(temporary)
                context.contentResolver.openOutputStream(uri, "wt").use { output ->
                    checkNotNull(output) { "无法打开备份目标" }
                    Files.newInputStream(temporary).use { it.copyTo(output) }
                    output.flush()
                }
                true
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
    }

    /**
     * 把选中的密文备份暂存到私有缓存，在全新私有目录中执行恢复。
     * @param restore 完整恢复和认证流程。
     * @return 取消时为空，否则为已认证工作区结果。
     */
    override suspend fun <T> importBackup(restore: suspend (Path, Path) -> T): T? {
        val uri = chooseDocument(export = false) ?: return null
        return withContext(Dispatchers.IO) {
            val temporary = Files.createTempFile(context.cacheDir.canonicalFile.toPath(), "encrypted-import-", ".zip")
            try {
                context.contentResolver.openInputStream(uri).use { input ->
                    checkNotNull(input) { "无法打开备份文件" }
                    Files.newOutputStream(temporary).use { input.copyTo(it) }
                }
                val parent = AndroidFileSystemEnvironment.workspaceDirectory()
                Files.createDirectories(parent)
                restore(temporary, parent.resolve("restored-" + UUID.randomUUID()))
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
    }

    /**
     * 在主线程发起一次可取消的系统文档交互。
     * @param export true 选择保存位置，false 选择已有备份。
     * @return 用户选择的 URI 或取消时为空。
     */
    private suspend fun chooseDocument(export: Boolean): Uri? = withContext(Dispatchers.Main.immediate) {
        check(owner != null && pending == null) { "文档入口未绑定或已有操作正在进行" }
        val result = CompletableDeferred<Uri?>()
        pending = result
        try {
            if (export) checkNotNull(createDocument).launch("tagloom-backup.tagloom-backup.zip")
            else checkNotNull(openDocument).launch(arrayOf("application/zip", "application/octet-stream"))
            result.await()
        } finally {
            if (pending === result) pending = null
        }
    }
}
