package com.oruke.tagloom

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.oruke.tagloom.platform.WorkspaceOpenResult
import com.oruke.tagloom.platform.WorkspaceServerRecoveryStatus
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.Comparator

/** 为显式 NAS 实机验收保留跨 instrumentation 阶段的独立目录，不使用用户默认工作区。 */
internal class AndroidNasSyncArea(
    /** 外部协调器生成的本次脱敏验收身份，不允许包含路径分隔符。 */
    val runId: String,
) {
    /** 只用于限定应用私有目录与当前应用窗口。 */
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    /** 所有验收运行共享的受控父目录，不保存恢复密钥。 */
    private val parent: Path = target.filesDir.canonicalFile.toPath().resolve("nas-sync-acceptance")
    /** 本次运行唯一允许写入或清理的持久目录。 */
    val root: Path = parent.resolve(runId)
    /** 协调器通过 run-as 放入的一次性秘密输入，读完立即删除。 */
    private val input: Path = target.filesDir.canonicalFile.toPath().resolve("nas-sync-acceptance-input-$runId.json")
    /** 仅记录脱敏阶段名，使网络失败重试不会重复生成新增笔记。 */
    private val progress: Path = root.resolve("completed-phases.json")
    /** 当前运行的非秘密结果，可由宿主通过 run-as 拉取。 */
    private val result: Path = root.resolve("android-result.json")
    /** 实际 Keystore、SQLCipher 和文件系统保留不变，只重定向本次能力容器的目录。 */
    val context: Context = object : ContextWrapper(target) {
        /** @return 当前隔离 Application 路由，不持有测试 Activity。 */
        override fun getApplicationContext(): Context = this
        /** @return 本次运行的本机解锁与服务器登记目录。 */
        override fun getNoBackupFilesDir(): File = root.resolve("private").toFile().apply { mkdirs() }
        /** @return 本次运行的缓存目录。 */
        override fun getCacheDir(): File = root.resolve("cache").toFile().apply { mkdirs() }
        /** @return 本次运行的一般私有文件目录。 */
        override fun getFilesDir(): File = root.resolve("files").toFile().apply { mkdirs() }
    }
    /** 目录选择器替身只提供受控路径，所有业务写入仍由生产网关执行。 */
    val documents = TestWorkspaceDocuments(root)

    init {
        require(runId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))) { "nas_run_id_invalid" }
        check(root.toAbsolutePath().normalize().parent == parent.toAbsolutePath().normalize()) { "nas_area_invalid" }
        Files.createDirectories(root)
    }

    /**
     * 有界读取一次性 JSON 后删除磁盘输入，恢复密钥只在该阶段的内存中使用。
     * @return 不包含部署访问码的当前设备输入。
     */
    fun consumeInput(): AndroidNasSyncInput {
        require(Files.isRegularFile(input) && Files.size(input) in 1L..65_536L) { "nas_input_missing_or_invalid" }
        val bytes = Files.readAllBytes(input)
        Files.delete(input)
        return try {
            val value = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            AndroidNasSyncInput(
                serverUrl = value["serverUrl"]?.jsonPrimitive?.contentOrNull.orEmpty().trim().trimEnd('/'),
                recoveryKey = value["recoveryKey"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                sharedNoteId = value["sharedNoteId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            ).also {
                require(it.serverUrl.startsWith("https://") && it.sharedNoteId.isNotBlank()) { "nas_input_fields_invalid" }
            }
        } finally { bytes.fill(0) }
    }

    /**
     * 查询是否已经完整执行过某一阶段，不把失败或中断标记为已完成。
     * @param phase 外部显式选择的验收阶段。
     * @return 当前运行的已确认阶段是否包含它。
     */
    fun completed(phase: String): Boolean = phase in completedPhases()

    /**
     * 内容、索引和状态全部核对后才保存阶段完成标记。
     * @param phase 本次已完成的验收阶段。
     */
    fun markCompleted(phase: String) {
        Files.writeString(progress, JsonArray((completedPhases() + phase).sorted().map(::JsonPrimitive)).toString())
    }

    /**
     * 写出可由协调器核对的脱敏结果，不输出恢复密钥、设备访问凭据或异常正文。
     * @param phase 当前验收阶段。
     * @param state 已确认笔记，只允许记录当前验收样例。
     * @param status 当前服务器模式状态。
     * @param pendingConflicts 尚未确认的冲突条目数。
     * @param replayed 是否为已完成阶段的幂等重放。
     */
    fun writeResult(phase: String, state: WorkspaceOpenResult, status: WorkspaceServerRecoveryStatus?,
        pendingConflicts: Int, replayed: Boolean) {
        val payload = buildJsonObject {
            put("runId", runId)
            put("phase", phase)
            put("passed", true)
            put("alreadyCompleted", replayed)
            put("mode", status?.mode?.name.orEmpty())
            put("pendingChanges", status?.pendingChanges ?: false)
            put("pendingConflicts", pendingConflicts)
            putJsonArray("notes") {
                state.notes.forEach { note -> add(buildJsonObject {
                    put("id", note.id.value)
                    put("title", note.note.title)
                    putJsonArray("blocks") { note.note.blocks.forEach { block -> add(buildJsonObject {
                        put("id", block.id.value)
                        put("text", AndroidNasSyncFixture.text(block))
                    }) } }
                }) }
            }
        }
        Files.writeString(result, payload.toString())
    }

    /**
     * 保存失败分类而不序列化底层异常，避免 HTTP 或密码库异常带出秘密输入。
     * @param phase 发生失败的阶段。
     * @param errorClass 仅包含异常类名称，不含异常消息。
     * @param frames 已过滤的应用类、方法和行号，不包含消息、cause 或文件路径。
     */
    fun writeFailure(phase: String, errorClass: String, frames: List<String> = emptyList()) {
        Files.writeString(result, buildJsonObject {
            put("runId", runId); put("phase", phase); put("passed", false); put("errorClass", errorClass)
            putJsonArray("applicationFrames") { frames.forEach { add(JsonPrimitive(it)) } }
            put("filesystem", pathDiagnostics())
        }.toString())
    }

    /**
     * 在应用进程自身的挂载命名空间检查私有目录和默认临时目录，只返回布尔、索引和异常类。
     * @return 不含真实绝对路径、目录名、文件内容或任何恢复材料的诊断对象。
     */
    fun pathDiagnostics(): JsonObject = buildJsonObject {
        put("appData", describePath(Path.of(target.applicationInfo.dataDir)))
        put("appPrivate", describePath(context.noBackupFilesDir.toPath()))
        put("appCache", describePath(context.cacheDir.toPath()))
        put("targetCache", describePath(target.cacheDir.toPath()))
        put("serverParent", describePath(context.noBackupFilesDir.canonicalFile.toPath().resolve("server-workspaces")))
        val temporary = System.getProperty("java.io.tmpdir")
        if (temporary != null) put("javaTmp", describePath(Path.of(temporary))) else put("javaTmpAvailable", false)
    }

    /**
     * 将路径和祖先转成可安全输出的检查结果；应用看到的系统别名可能与 adb shell 所在命名空间不同。
     * @param path 由 Context 或 JVM 临时目录属性计算的既有位置，不接受外部路径输入。
     * @return 每层零起始索引、链接与目录标志，以及规范路径是否变化。
     */
    private fun describePath(path: Path): JsonObject = buildJsonObject {
        val normalized = path.toAbsolutePath().normalize()
        val canonical = runCatching { normalized.toFile().canonicalFile.toPath().toAbsolutePath().normalize() }
        val real = runCatching { normalized.toRealPath() }
        put("canonicalAvailable", canonical.isSuccess)
        put("canonicalDiffers", canonical.getOrNull()?.let { it != normalized } ?: false)
        put("canonicalLink", canonical.getOrNull()?.let(Files::isSymbolicLink) ?: false)
        put("realAvailable", real.isSuccess)
        put("realDiffers", real.getOrNull()?.let { it != normalized } ?: false)
        if (canonical.isFailure) put("canonicalErrorClass", canonical.exceptionOrNull()?.javaClass?.simpleName.orEmpty())
        if (real.isFailure) put("realErrorClass", real.exceptionOrNull()?.javaClass?.simpleName.orEmpty())
        putJsonArray("ancestors") {
            var current: Path? = normalized
            var index = 0
            while (current != null) {
                val candidate = requireNotNull(current)
                val attributes = runCatching { Files.readAttributes(candidate, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }
                add(buildJsonObject {
                    put("index", index)
                    put("root", candidate.parent == null)
                    put("link", Files.isSymbolicLink(candidate))
                    put("existsNoFollow", Files.exists(candidate, LinkOption.NOFOLLOW_LINKS))
                    put("directoryNoFollow", Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS))
                    put("attributesAvailable", attributes.isSuccess)
                    put("attributesLink", attributes.getOrNull()?.isSymbolicLink ?: false)
                    if (attributes.isFailure) put("attributesErrorClass", attributes.exceptionOrNull()?.javaClass?.simpleName.orEmpty())
                })
                current = candidate.parent
                index++
            }
        }
    }

    /**
     * 只捕获当前测试应用的可见窗口，不读取其他应用或密钥输入页面。
     * @param name 当前场景的固定脱敏文件名。
     * @return 确实捕获并保存目标应用窗口时为真；其他应用处于前台时为假，不捕获它们。
     */
    fun screenshot(name: String): Boolean {
        require(name.matches(Regex("[A-Za-z0-9_-]+\\.png"))) { "nas_screenshot_name_invalid" }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        if (automation.rootInActiveWindow?.packageName?.toString() != target.packageName) return false
        val directory = target.getExternalFilesDir(null)!!.toPath().resolve("nas-sync-acceptance-$runId")
        Files.createDirectories(directory)
        Files.newOutputStream(directory.resolve(name)).use { stream ->
            checkNotNull(automation.takeScreenshot()).apply { compress(Bitmap.CompressFormat.PNG, 100, stream); recycle() }
        }
        return true
    }

    /** 只清理本次身份限定的隔离目录；调用方必须先关闭生产会话。 */
    fun discard() {
        check(root.toAbsolutePath().normalize().parent == parent.toAbsolutePath().normalize()) { "nas_area_invalid" }
        if (Files.exists(root)) Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        Files.deleteIfExists(input)
    }

    /**
     * 读取当前运行已落盘确认的阶段集合。
     * @return 本次已完成的阶段集合；不存在进度文件时为空。
     */
    private fun completedPhases(): Set<String> = if (Files.exists(progress))
        Json.parseToJsonElement(Files.readString(progress)).jsonArray.map { it.jsonPrimitive.content }.toSet() else emptySet()
}

/** 一次性阶段输入只在测试进程内存中存在，不写入结果或日志。 */
internal data class AndroidNasSyncInput(
    /** 具有可信 TLS 证书的实际 NAS 入口。 */
    val serverUrl: String,
    /** 仅首次恢复需要的本次隔离工作区恢复密钥，其他阶段可以为空。 */
    val recoveryKey: String,
    /** 由 Windows 设备创建并通过正式同步保存的共享笔记身份。 */
    val sharedNoteId: String,
)
