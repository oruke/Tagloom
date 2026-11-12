package com.oruke.tagloom.workspace

import com.oruke.tagloom.capability.TagloomCapabilities
import com.oruke.tagloom.core.model.Block
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.BlockTypes
import com.oruke.tagloom.core.model.InlineRun
import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.model.RichText
import com.oruke.tagloom.core.model.RichTextBlockContent
import com.oruke.tagloom.filesystem.jvm.JvmWorkspaceFileSystem
import com.oruke.tagloom.filesystem.jvm.crypto.LibsodiumCryptoProvider
import com.oruke.tagloom.platform.WorkspaceNote
import com.oruke.tagloom.platform.WorkspaceStorageMode
import com.oruke.tagloom.platform.WorkspaceSyncConflict
import com.oruke.tagloom.sync.createSyncEngine
import com.oruke.tagloom.sync.http.ServerRecoveryException
import com.sun.jna.platform.win32.Crypt32Util
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 从显式私有配置执行 Windows 与真实 NAS 的分阶段验收，不作为默认 JUnit 测试运行。
 * @param args 仅接受配置文件路径；为空时读取 TAGLOOM_NAS_ACCEPTANCE_CONFIG，秘密不进入命令行。
 */
fun main(args: Array<String>) {
    quietNasAcceptanceLogging()
    val input = readNasAcceptanceConfiguration(args)
    val path = input.path
    val config = input.configuration
    require(config.runId.matches(Regex("[a-zA-Z0-9_-]{1,64}"))) { "invalid_run_id" }
    require(config.serverUrl.startsWith("https://")) { "https_required" }
    val area = requireNotNull(path.parent)
    require(area.fileName.toString() == config.runId && area.parent.fileName.toString() == "nas-acceptance" &&
        area.parent.parent.fileName.toString() == ".gradle" && !Files.isSymbolicLink(area)) { "invalid_acceptance_area" }
    val runner = NasAcceptancePhases(config, area)
    try {
        runBlocking { runner.run() }
        println("NAS_ACCEPTANCE_PASS phase=${config.phase} run=${config.runId}")
    } catch (failure: Throwable) {
        val code = nasAcceptanceErrorCode(failure)
        // 只保留代码位置，不输出异常消息、请求地址或敏感 cause，便于区分本地恢复与传输故障。
        val origin = failure.stackTrace.take(8).joinToString(" -> ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
        System.err.println("NAS_ACCEPTANCE_ORIGIN $origin")
        runner.writeFailure(code)
        // 底层异常可能含请求或路径；仅输出稳定分类，不把敏感 cause 交给 Gradle 日志展开。
        error("NAS_ACCEPTANCE_FAIL phase=${config.phase} code=$code")
    }
}

/** 通过正式网关、DPAPI、libsodium 和 SQLite 提供一个独立的 Windows 验收设备。 */
internal class NasAcceptanceDesktopDevice(
    /** 本轮明确创建的私有验收目录，不使用默认用户登记。 */
    val root: Path,
) : AutoCloseable {
    /** 明确路由至本轮工作区的文档入口。 */
    private val documents = NasAcceptanceDocuments(root)
    /** 正式平台密码学实现；不替换算法或生产 KDF。 */
    private val crypto = LibsodiumCryptoProvider()
    /** 独立会话仍持有生产单写入协调器。 */
    val manager = DesktopWorkspaceManager(cryptoProvider = crypto)
    /** 正式网关使用独立本机登记及服务器访问目录，保持用户数据不受影响。 */
    val gateway = DesktopWorkspaceGateway(manager,
        DesktopWorkspaceLocalAccessService(DesktopWorkspaceAccessStore(root.resolve("access.json"), cryptoProvider = crypto)),
        documents, recoveryAccessDirectory = root.resolve("remote"))
    /** 查询直接读取当前确认快照的加密派生索引。 */
    private val index = JvmWorkspaceIndexEngine(gateway)
    /** 可以交给生产 App 根节点作界面验收的正式能力集合。 */
    val capabilities = TagloomCapabilities(gateway, JvmWorkspaceFileSystem(documents.workspace), index, index, createSyncEngine())

    /** 只关闭当前设备会话和网络能力，保留密文状态供下一阶段或人工复核。 */
    override fun close() = runBlocking { gateway.lock(); capabilities.syncEngine.close() }
}

/** 提供固定的隔离文档位置，不弹系统目录选择器或访问默认用户工作区。 */
private class NasAcceptanceDocuments(
    /** 此设备独占的验收根目录。 */
    private val root: Path,
) : WorkspaceDocumentAccess {
    /** 本地新建或重新认证的工作区目录。 */
    val workspace: Path = root.resolve("workspace")
    /** 返回新建工作区的受控目录。 @return 本轮明确的空工作区目标。 */
    override suspend fun creationDirectory(): Path = workspace
    /** 返回中断后重新认证的同一受控工作区目录。 @return 本轮已创建的同一工作区，供中断后恢复登记。 */
    override suspend fun recoveryDirectory(): Path = workspace
    /** 将密文备份写出到受控验收目录。 @param writeArchive 正式密文归档导出流程。 @return 导出成功时为真。 */
    override suspend fun exportBackup(writeArchive: suspend (Path) -> Unit): Boolean {
        writeArchive(root.resolve("backup.zip")); return true
    }
    /** 本验收不执行外部归档导入，固定返回空结果。 @param restore 正式导入流程。 @return 始终为空。 */
    override suspend fun <T> importBackup(restore: suspend (Path, Path) -> T): T? = null
}

/** 持久化阶段状态并协调真实 NAS 的正式同步入口，不直接读写业务快照。 */
private class NasAcceptancePhases(
    /** 当前阶段的显式配置。 */
    private val config: NasAcceptanceConfiguration,
    /** 受控的本轮临时根目录。 */
    private val area: Path,
) {
    /** 秘密状态始终由当前 Windows 账户的 DPAPI 保护。 */
    private val statePath = area.resolve("state.dpapi")
    /** 本地设备的独立数据目录。 */
    private val deviceRoot = area.resolve("windows")
    /** 当前阶段的认证状态；不写入日志或明文结果。 */
    private var state: NasAcceptanceState? = readState()

    /** 执行一个可重入阶段，完成后通过当前已确认状态生成脱敏结果。 */
    suspend fun run() {
        Files.createDirectories(deviceRoot)
        NasAcceptanceDesktopDevice(deviceRoot).use { device ->
            if (config.phase == "init") initialize(device) else open(device)
            val current = requireNotNull(state)
            check(current.runId == config.runId && current.serverUrl == config.serverUrl) { "acceptance_identity_changed" }
            when (config.phase) {
                "init" -> Unit
                "verify_android" -> verifyAndroid(device)
                "offline_edit" -> editOffline(device)
                "merge_publish" -> device.gateway.serverRecovery.publish(config.serverUrl, "", "")
                "merge_verify" -> {
                    device.gateway.serverRecovery.updateIfChanged()
                    verifyTexts(device, WINDOWS_OFFLINE, ANDROID_OFFLINE)
                }
                "conflict_edit" -> editConflict(device)
                "conflict_verify" -> {
                    device.gateway.serverRecovery.publish(config.serverUrl, "", "")
                    val conflict = unresolvedBlockConflict(device)
                    saveState(requireNotNull(state).copy(conflictId = conflict.id))
                }
                "resolve" -> resolve(device)
                "verify_resolved" -> verifyResolved(device)
                "reopen_verify" -> {
                    device.gateway.lock()
                    device.gateway.openRecentWorkspace()
                    verifyResolved(device)
                }
                "ui_verify" -> {
                    verifyResolved(device)
                    verifyNasSyncDesktopUi(device, config.serverUrl, config.runId, requireNotNull(state).sharedNoteId, area.resolve("windows-ui.png"))
                }
                "cleanup" -> device.gateway.lock()
                else -> error("unknown_acceptance_phase")
            }
            saveState(requireNotNull(state).copy(completedPhases = requireNotNull(state).completedPhases + config.phase))
            if (config.phase != "cleanup") writeResult(device) else writeSuccess(emptyList(), emptyList(), 0, 0, null)
            writeHandoff()
        }
    }

    /**
     * 创建并发布首个测试对象；中断重跑时复用已确认工作区及预先保存的随机笔记身份。
     * @param device 当前唯一 Windows 验收设备。
     */
    private suspend fun initialize(device: NasAcceptanceDesktopDevice) {
        val manifest = deviceRoot.resolve("workspace/manifest.enc")
        if (Files.exists(manifest)) {
            check(state != null) { "acceptance_state_missing" }
            open(device)
        } else {
            val prepared = requireNotNull(device.gateway.prepareCreation(null))
            saveState(NasAcceptanceState(config.runId, config.serverUrl, prepared.recoveryKeyText,
                device.gateway.createOpaqueId("nas-acceptance-shared-note")))
            device.gateway.confirmCreation()
        }
        val active = requireNotNull(state)
        if (device.gateway.reload().notes.none { it.id.value == active.sharedNoteId }) {
            device.gateway.saveNote(ObjectId(active.sharedNoteId), Note("NAS sync acceptance ${config.runId}", emptyList(), blocks = listOf(
                paragraph(FIRST_BLOCK, BASE_FIRST, "a"), paragraph(SECOND_BLOCK, BASE_SECOND, "b"))))
        }
        device.gateway.serverRecovery.publish(config.serverUrl, config.bootstrapToken, active.recoveryKey)
    }

    /**
     * 通过正式账户登记重开；只有本次新建工作区的中断登记才使用已保存恢复密钥重新认证。
     * @param device 当前唯一 Windows 验收设备。
     */
    private suspend fun open(device: NasAcceptanceDesktopDevice) {
        val current = requireNotNull(state) { "acceptance_state_missing" }
        if (device.gateway.recentWorkspaceOrNull() == null) {
            check(Files.exists(deviceRoot.resolve("workspace/manifest.enc"))) { "acceptance_workspace_missing" }
            requireNotNull(device.gateway.unlockWithRecovery(current.recoveryKey, null))
        } else device.gateway.openRecentWorkspace()
    }

    /**
     * 核对 Android 经 NAS 写回的正文、新增笔记和真实全文索引。
     * @param device 当前已解锁设备。
     */
    private suspend fun verifyAndroid(device: NasAcceptanceDesktopDevice) {
        device.gateway.serverRecovery.updateIfChanged()
        verifyTexts(device, BASE_FIRST, ANDROID_INITIAL)
        val addition = device.gateway.reload().notes.single { it.note.title == "nas-android-addition-${config.runId}" }
        check(addition.note.blocks.any { it.plainText() == "nas-android-added-body" }) { "android_addition_body_mismatch" }
        check(device.gateway.search("nas-android-added-body").any { it.noteId == addition.id }) { "android_addition_index_missing" }
    }

    /**
     * 在本地模式修改第一块，重复运行不会覆盖后续已经合并的状态。
     * @param device 当前已解锁设备。
     */
    private suspend fun editOffline(device: NasAcceptanceDesktopDevice) {
        if (config.phase in requireNotNull(state).completedPhases) return
        device.gateway.serverRecovery.useLocalMode()
        val note = shared(device)
        device.gateway.saveNote(note.id, note.note.replaceFirst(WINDOWS_OFFLINE), note.revision)
        check(device.gateway.serverRecovery.status()?.mode == WorkspaceStorageMode.LOCAL) { "local_mode_not_saved" }
    }

    /**
     * 从双方合并后的共同状态产生同一 Block 的离线分支。
     * @param device 当前已解锁设备。
     */
    private suspend fun editConflict(device: NasAcceptanceDesktopDevice) {
        if (config.phase in requireNotNull(state).completedPhases) return
        verifyTexts(device, WINDOWS_OFFLINE, ANDROID_OFFLINE)
        device.gateway.serverRecovery.useLocalMode()
        val note = shared(device)
        device.gateway.saveNote(note.id, note.note.replaceFirst(WINDOWS_CONFLICT), note.revision)
    }

    /**
     * 逐字段选择 Android 的冲突正文，并继续通过正式 CAS 同步发布处理结果。
     * @param device 当前已解锁设备。
     */
    private suspend fun resolve(device: NasAcceptanceDesktopDevice) {
        device.gateway.serverRecovery.updateIfChanged()
        val unresolved = device.gateway.serverRecovery.conflicts().filter { it.objectId.value == requireNotNull(state).sharedNoteId && !it.resolved }
        if (unresolved.isNotEmpty()) {
            val conflict = unresolvedBlockConflict(device)
            val field = conflict.fields.single { !it.resolved }
            // 字段预览是 Markdown，样例连字符由正式导出器转义；权威正文仍在 verifyTexts 中逐字核对。
            val expectedPreview = ANDROID_CONFLICT.replace("-", "\\-")
            val useRemote = field.remoteContent.contains(expectedPreview)
            check((if (useRemote) field.remoteContent else field.localContent).contains(expectedPreview)) { "android_conflict_value_missing" }
            device.gateway.serverRecovery.resolveConflict(conflict.id, useRemote, conflict.currentRevision, field.id)
            saveState(requireNotNull(state).copy(conflictId = conflict.id))
        }
        device.gateway.serverRecovery.updateIfChanged()
        verifyResolved(device)
    }

    /**
     * 核对选择内容、完整历史处理状态和可重建索引，不把同步返回成功当作内容正确。
     * @param device 当前已解锁设备。
     */
    private suspend fun verifyResolved(device: NasAcceptanceDesktopDevice) {
        device.gateway.serverRecovery.updateIfChanged()
        verifyTexts(device, ANDROID_CONFLICT, ANDROID_OFFLINE)
        val conflicts = device.gateway.serverRecovery.conflicts().filter { it.objectId.value == requireNotNull(state).sharedNoteId }
        check(conflicts.isNotEmpty() && conflicts.all { it.resolved && it.fields.all { field -> field.resolved } }) { "conflict_history_not_resolved" }
        check(device.gateway.search(ANDROID_CONFLICT).any { it.noteId.value == requireNotNull(state).sharedNoteId }) { "resolved_index_missing" }
    }

    /**
     * 读取完整双版本，确认冲突精确定位为一个 Block 且包含双方真实正文。
     * @param device 当前已解锁设备。
     * @return 可供显式选择的最新冲突。
     */
    private suspend fun unresolvedBlockConflict(device: NasAcceptanceDesktopDevice): WorkspaceSyncConflict {
        val conflict = device.gateway.serverRecovery.conflicts().single { it.objectId.value == requireNotNull(state).sharedNoteId && !it.resolved }
        val field = conflict.fields.single { !it.resolved }
        check(field.labelKey == "BLOCK") { "unexpected_conflict_scope" }
        check(listOf(field.localContent, field.remoteContent).any { it.contains(WINDOWS_CONFLICT.replace("-", "\\-")) } &&
            listOf(field.localContent, field.remoteContent).any { it.contains(ANDROID_CONFLICT.replace("-", "\\-")) }) { "conflict_versions_missing" }
        return conflict
    }

    /**
     * 从正式网关重读共享对象。
     * @param device 当前已解锁设备。
     * @return 当前确认笔记，禁止复用保存前旧候选。
     */
    private suspend fun shared(device: NasAcceptanceDesktopDevice): WorkspaceNote =
        device.gateway.reload().notes.single { it.id.value == requireNotNull(state).sharedNoteId }

    /**
     * 核对稳定 Block 身份下的两份确认正文。
     * @param device 当前已解锁设备。
     * @param first 第一块期望值。
     * @param second 第二块期望值。
     */
    private suspend fun verifyTexts(device: NasAcceptanceDesktopDevice, first: String, second: String) {
        val blocks = shared(device).note.blocks.associateBy { it.id.value }
        check(blocks[FIRST_BLOCK]?.plainText() == first && blocks[SECOND_BLOCK]?.plainText() == second) { "shared_content_mismatch" }
    }

    /** 读取经当前 Windows 账户 DPAPI 认证的阶段状态。 @return DPAPI 认证的阶段状态；尚未初始化时为空。 */
    private fun readState(): NasAcceptanceState? = readNasAcceptanceProtectedState(statePath, NasAcceptanceState.serializer())

    /**
     * 原子保存 DPAPI 状态，暂存文件也始终是密文。
     * @param updated 本轮新的阶段状态。
     */
    private fun saveState(updated: NasAcceptanceState) {
        val plaintext = nasAcceptanceJson.encodeToString(NasAcceptanceState.serializer(), updated).encodeToByteArray()
        try { writeProtected(statePath, plaintext) } finally { plaintext.fill(0) }
        state = updated
    }

    /** 仅供父级协调器通过 Windows 账户在内存解密后把恢复材料送入 Android 私有输入。 */
    private fun writeHandoff() {
        val current = requireNotNull(state)
        val plaintext = nasAcceptanceJson.encodeToString(NasAcceptanceHandoff.serializer(), NasAcceptanceHandoff(
            current.runId, current.serverUrl, current.recoveryKey, current.sharedNoteId)).encodeToByteArray()
        try { writeProtected(area.resolve("handoff.dpapi"), plaintext) } finally { plaintext.fill(0) }
    }

    /**
     * 生成不含密钥和访问凭据的结果，供父级协调后续阶段。
     * @param device 当前已解锁设备。
     */
    private suspend fun writeResult(device: NasAcceptanceDesktopDevice) {
        val notes = device.gateway.reload().notes
        val current = shared(device)
        val conflicts = device.gateway.serverRecovery.conflicts().filter { it.objectId == current.id }
        writeSuccess(notes.map { it.id.value }, current.note.blocks.map { it.plainText() },
            conflicts.count { !it.resolved }, conflicts.count { it.resolved }, device.manager.sessionOrNull()?.workspaceId?.value)
    }

    /**
     * 保存经过最小化的成功证据，不输出账户或文件路径。
     * @param noteIds 已确认笔记的随机身份。
     * @param blockTexts 本轮脱敏 fixture 正文。
     * @param pending 尚待处理冲突数。
     * @param resolved 已处理历史数。
     * @param workspaceId 仅供核对 NAS 密文目录的随机工作区身份。
     */
    private fun writeSuccess(noteIds: List<String>, blockTexts: List<String>, pending: Int, resolved: Int, workspaceId: String?) {
        Files.writeString(area.resolve("windows-result.json"), nasAcceptanceJson.encodeToString(NasAcceptanceResult.serializer(),
            NasAcceptanceResult(config.runId, config.phase, true, state?.sharedNoteId, noteIds, blockTexts, pending, resolved, workspaceId)))
    }

    /**
     * 保存稳定失败分类，不持久化底层异常文本或响应体。
     * @param code 已收敛且不含敏感内容的错误分类。
     */
    fun writeFailure(code: String) {
        Files.writeString(area.resolve("windows-result.json"), nasAcceptanceJson.encodeToString(NasAcceptanceResult.serializer(),
            NasAcceptanceResult(config.runId, config.phase, false, state?.sharedNoteId, errorCode = code)))
    }
}

/** 仅由 Windows 当前账户解密的本轮状态，避免中断或重跑创建多个工作区。 */
@Serializable
private data class NasAcceptanceState(
    /** 隔离运行身份。 */
    val runId: String,
    /** 绑定的实际服务器，不允许阶段间静默更换。 */
    val serverUrl: String,
    /** 仅本测试工作区的恢复密钥，始终进入 DPAPI 密文。 */
    val recoveryKey: String,
    /** 初始化前已分配的随机共享笔记身份。 */
    val sharedNoteId: String,
    /** 已确认完成的阶段，防止重复编辑覆盖后续结果。 */
    val completedPhases: Set<String> = emptySet(),
    /** 供后续核对的原冲突随机身份。 */
    val conflictId: String? = null,
)

/** 父级协调器一次性送入 Android 私有目录的恢复材料，文件本体采用 DPAPI。 */
@Serializable
private data class NasAcceptanceHandoff(
    /** 隔离运行身份。 */
    val runId: String,
    /** 实际 NAS HTTPS 地址。 */
    val serverUrl: String,
    /** 只供 Android 首次恢复的本轮密钥。 */
    val recoveryKey: String,
    /** 稳定随机共享笔记身份。 */
    val sharedNoteId: String,
)

/** 可审阅的最小结果，不包含恢复材料、Token 或设备保护凭据。 */
@Serializable
private data class NasAcceptanceResult(
    /** 隔离运行身份。 */
    val runId: String,
    /** 本次实际执行的阶段。 */
    val phase: String,
    /** 全部断言满足时为真。 */
    val pass: Boolean,
    /** 后续阶段使用的随机笔记身份。 */
    val sharedNoteId: String? = null,
    /** 当前确认的随机笔记身份集合。 */
    val noteIds: List<String> = emptyList(),
    /** 只包含本轮脱敏样例的确认正文。 */
    val blockTexts: List<String> = emptyList(),
    /** 待核对的冲突数。 */
    val pendingConflicts: Int = 0,
    /** 已处理但仍保留的历史数。 */
    val resolvedConflicts: Int = 0,
    /** 实际 NAS 密文目录使用的随机身份。 */
    val workspaceId: String? = null,
    /** 不含底层异常文本的稳定失败分类。 */
    val errorCode: String? = null,
)

/** 本测试协议使用规范 JSON，忽略父协调器附加的非必要配置键。 */
private val nasAcceptanceJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }
/** 共享第一块稳定身份。 */
private const val FIRST_BLOCK = "nas-shared-first"
/** 共享第二块稳定身份。 */
private const val SECOND_BLOCK = "nas-shared-second"
/** 初始第一块脱敏正文。 */
private const val BASE_FIRST = "nas-base-first"
/** 初始第二块脱敏正文。 */
private const val BASE_SECOND = "nas-base-second"
/** Android 首次写回的脱敏正文。 */
private const val ANDROID_INITIAL = "nas-android-initial-edit"
/** Windows 离线独立修改。 */
private const val WINDOWS_OFFLINE = "nas-windows-offline-first"
/** Android 离线独立修改。 */
private const val ANDROID_OFFLINE = "nas-android-offline-second"
/** Windows 同块竞争内容。 */
private const val WINDOWS_CONFLICT = "nas-windows-conflict"
/** 用户在验收中明确选择的 Android 同块竞争内容。 */
private const val ANDROID_CONFLICT = "nas-android-conflict"

/**
 * 创建正式 RichText 段落 fixture，不包含真实业务数据。
 * @param id 稳定 Block 身份。
 * @param text 当前阶段的脱敏内容。
 * @param orderKey 稳定正文顺序。
 * @return 通过生产事务保存的 Block。
 */
private fun paragraph(id: String, text: String, orderKey: String): Block =
    Block(BlockId(id), BlockTypes.PARAGRAPH, orderKey = orderKey, content = RichTextBlockContent(RichText(listOf(InlineRun(text)))))

/**
 * 拼接段落内全部规范化 InlineRun 的文本内容。
 * @receiver 待核对正文的段落 Block。
 * @return 当前段落完整文字，非预期正文类型直接失败。
 */
private fun Block.plainText(): String = (content as RichTextBlockContent).richText.runs.joinToString("") { it.text }

/**
 * 只编辑共享第一块，保留第二块和其它已合并字段。
 * @receiver 当前已确认笔记。
 * @param text 本阶段的新脱敏正文。
 * @return 仅目标 Block 内容变化的 Note。
 */
private fun Note.replaceFirst(text: String): Note = copy(blocks = blocks.map {
    if (it.id.value == FIRST_BLOCK) it.copy(content = RichTextBlockContent(RichText(listOf(InlineRun(text))))) else it
})

/**
 * 以密文临时文件执行原子替换，避免状态中断留下明文恢复材料。
 * @param target 仅本轮状态文件。
 * @param plaintext 调用方随后清零的短期字节。
 */
private fun writeProtected(target: Path, plaintext: ByteArray) {
    val temporary = target.resolveSibling("${target.fileName}.pending")
    Files.write(temporary, Crypt32Util.cryptProtectData(plaintext))
    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}

/**
 * 只提取可公开的失败分类，避免把网络响应、路径或明文内容带进验收日志。
 * @param error 真实执行失败。
 * @return 适合脱敏结果的稳定分类。
 */
private fun nasAcceptanceErrorCode(error: Throwable): String {
    val candidate = if (error is ServerRecoveryException) error.code else error.message
    return candidate?.takeIf { it.matches(Regex("[a-zA-Z0-9_]{1,100}")) } ?: error.javaClass.simpleName
}

/** 在首次创建 SQLCipher 连接前关闭底层 TRACE，禁止测试日志输出派生密钥和 SQL 参数。 */
private fun quietNasAcceptanceLogging() {
    val factory = Class.forName("org.slf4j.LoggerFactory").getMethod("getILoggerFactory").invoke(null)
    val logger = factory.javaClass.getMethod("getLogger", String::class.java).invoke(factory, "ROOT")
    val level = Class.forName("ch.qos.logback.classic.Level")
    logger.javaClass.getMethod("setLevel", level).invoke(logger, level.getField("WARN").get(null))
}
