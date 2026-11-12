package com.oruke.tagloom

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.oruke.tagloom.capability.AndroidTagloomEnvironment
import com.oruke.tagloom.capability.TagloomCapabilities
import com.oruke.tagloom.capability.createAndroidTagloomCapabilities
import com.oruke.tagloom.core.index.SearchIndexQuery
import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.model.WorkspaceId
import com.oruke.tagloom.platform.WorkspaceStorageMode
import com.oruke.tagloom.sync.http.ServerRecoveryException
import com.oruke.tagloom.sync.http.jvm.ServerRecoveryClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * 显式运行的 NAS 现场验收，不用 Host 测试或本机回环服务替代真实 Android 与远端同步。
 *
 * 普通设备回归缺少 runId/phase 时明确记为未执行；现场验收必须按类名和阶段显式调用。
 */
@RunWith(AndroidJUnit4::class)
class AndroidNasSyncAcceptanceTest {
    /** 只在显式现场身份下检查应用进程看到的目录属性，不建立网络或打开工作区。 */
    @Test
    fun probeFilesystemPathBoundaries() {
        val runId = InstrumentationRegistry.getArguments().getString("tagloom.nas.runId")
        assumeTrue("Live filesystem probe requires explicit runId", !runId.isNullOrBlank())
        val area = AndroidNasSyncArea(requireNotNull(runId))
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("nasFilesystemDiagnostics", area.pathDiagnostics().toString())
        })
    }

    /**
     * 用生产 HTTPS 客户端访问真实认证接口；随机无效凭据被拒绝，证明设备应用的 TLS 与 HTTP 路径可达。
     *
     * 未提供公开服务器地址时明确记为未执行，不修改 DNS、代理、证书或工作区状态。
     */
    @Test
    fun probeHttpsConnectivity(): Unit = runBlocking {
        val address = InstrumentationRegistry.getArguments().getString("tagloom.nas.serverUrl")
        assumeTrue("Live NAS HTTPS probe requires explicit serverUrl", !address.isNullOrBlank())
        val code = try {
            // 使用独立的随机身份和测试访问码，不读取本机工作区，也不接触任何实际设备凭据。
            withTimeout(45_000) {
                ServerRecoveryClient(requireNotNull(address)).use { client ->
                    client.latestSnapshot(WorkspaceId(UUID.randomUUID().toString()),
                        ActorId(UUID.randomUUID().toString()), UUID.randomUUID().toString())
                }
            }
            "unexpected_success"
        } catch (failure: ServerRecoveryException) {
            failure.code
        } catch (failure: Throwable) {
            val causes = generateSequence(failure) { it.cause }.take(6)
                .map { it.javaClass.simpleName }.distinct().joinToString(">")
            throw AssertionError("NAS_HTTPS_PROBE_FAIL code=transport_failed causes=$causes")
        }
        // 正式客户端将 401/403 收敛为此稳定分类，不读取或打印认证响应正文。
        check(code == "authentication_failed") { "NAS_HTTPS_PROBE_FAIL code=$code" }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("nasHttpsProbe", "passed")
            putString("nasHttpsProbeResponse", "authentication_failed")
        })
    }

    /** 从一次性私有输入执行一个真实阶段，成功前不写完成标记，失败不打印输入或底层响应。 */
    @Test
    fun runExplicitLivePhase() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val runId = arguments.getString("tagloom.nas.runId")
        val phase = arguments.getString("tagloom.nas.phase")
        assumeTrue("Live NAS acceptance requires explicit runId and phase", runId != null || phase != null)
        check(!runId.isNullOrBlank() && !phase.isNullOrBlank()) { "nas_phase_arguments_invalid" }
        val selectedPhase = requireNotNull(phase)
        check(selectedPhase in PHASES) { "nas_phase_arguments_invalid" }
        val area = AndroidNasSyncArea(runId)
        val diagnostics = AndroidNasSyncDiagnostics(area, selectedPhase)
        var capabilities: TagloomCapabilities? = null
        try {
            diagnostics.mark("phase_started")
            if (selectedPhase == "cleanup") {
                area.discard()
            } else {
                diagnostics.mark("input_read_started")
                val input = area.consumeInput()
                diagnostics.mark("capabilities_create_started")
                AndroidTagloomEnvironment.initialize(area.context)
                val active = createAndroidTagloomCapabilities(area.documents)
                capabilities = active
                diagnostics.mark("capabilities_created")
                // 只有已确认的样例写入可以幂等跳过，验证阶段每次都重新联网、核对持久化或运行 UI。
                // 否则宿主强制重验时可能只读旧标记，错误地把服务器重启后的未执行报告为通过。
                val replayed = selectedPhase in IDEMPOTENT_WRITE_PHASES && area.completed(selectedPhase)
                // 业务 suspend 调用与 ActivityScenario 的阻塞等待分开，避免 UI 生命周期等待占住 runBlocking 事件循环。
                runBlocking {
                    diagnostics.mark("recent_workspace_lookup_started")
                    val existing = active.workspace.recentWorkspaceOrNull() != null
                    diagnostics.mark("recent_workspace_lookup_finished")
                    if (existing) {
                        diagnostics.mark("workspace_open_started")
                        active.workspace.openRecentWorkspace()
                        diagnostics.mark("workspace_open_finished")
                    }
                    check(existing || selectedPhase == "restore_edit") { "nas_fixture_not_initialized" }
                    if (existing) check(active.workspace.serverRecovery?.status()?.serverUrl == input.serverUrl) { "nas_fixture_server_mismatch" }
                    if (!replayed) {
                        diagnostics.mark("business_phase_started")
                        performPhase(selectedPhase, active, area, input, diagnostics)
                        diagnostics.mark("business_phase_finished")
                    }
                }
                if (!replayed && selectedPhase == "reopen_verify") {
                    // 由普通 instrumentation 线程启动生产 App，复用已通过的编辑器实体机测试模式。
                    AndroidNasSyncUi.verify(active, area, input.serverUrl, diagnostics)
                }
                runBlocking {
                    diagnostics.mark("final_state_verification_started")
                    val state = requireNotNull(active.workspace.reload())
                    val shared = AndroidNasSyncFixture.shared(state, input, runId)
                    if (selectedPhase == "reopen_verify") AndroidNasSyncFixture.assertTexts(shared.note,
                        AndroidNasSyncFixture.ANDROID_CONFLICT, AndroidNasSyncFixture.ANDROID_OFFLINE)
                    val remote = requireNotNull(active.workspace.serverRecovery)
                    val conflicts = remote.conflicts()
                    // 尤其是重新打开阶段，真实 UI、联网完成及持久化核对全部通过之后才允许写成功结果。
                    area.writeResult(selectedPhase, state, remote.status(), conflicts.count { !it.resolved }, replayed)
                    area.markCompleted(selectedPhase)
                    diagnostics.mark("result_confirmed")
                }
            }
            instrumentation.sendStatus(0, Bundle().apply {
                putString("nasAcceptancePhase", selectedPhase)
                putString("nasAcceptanceRunId", runId)
                putString("nasAcceptanceResult", "passed")
            })
        } catch (error: Throwable) {
            val frames = error.stackTrace.filter { it.className.startsWith("com.oruke.tagloom") }.take(8)
                .map { "${it.className}.${it.methodName}:${it.lineNumber}" }
            val classification = error.javaClass.simpleName
            runCatching { area.writeFailure(selectedPhase, classification, frames) }
            instrumentation.sendStatus(0, Bundle().apply {
                putString("nasFailureClass", classification)
                putString("nasFailureFrames", frames.joinToString(";"))
                runCatching { area.pathDiagnostics().toString() }.getOrNull()?.let { putString("nasFilesystemDiagnostics", it) }
            })
            // 不附带底层 cause，防止网络或解析异常把一次性恢复材料带到 instrumentation 输出。
            throw AssertionError("NAS Android phase failed [$selectedPhase]: $classification frames=${frames.joinToString(";")}")
        } finally {
            try {
                if (selectedPhase != "cleanup") diagnostics.mark("resources_close_started")
                runBlocking {
                    capabilities?.workspace?.lock()
                    capabilities?.syncEngine?.close()
                }
            } finally {
                AndroidTagloomEnvironment.initialize(instrumentation.targetContext)
                if (selectedPhase != "cleanup") diagnostics.mark("resources_closed")
                diagnostics.close()
            }
        }
    }

    /**
     * 通过生产网关编排单个阶段，所有修改仍经过共享事务和 revision 门禁。
     * @param phase 协调器显式选择的已知阶段。
     * @param active 持有本阶段唯一写入租约的正式 Android 能力。
     * @param area 本次持久隔离目录。
     * @param input 已消费并删除磁盘副本的一次性参数。
     * @param diagnostics 只记录固定等待阶段及脱敏线程帧的观察器。
     */
    private suspend fun performPhase(phase: String, active: TagloomCapabilities, area: AndroidNasSyncArea,
        input: AndroidNasSyncInput, diagnostics: AndroidNasSyncDiagnostics) {
        val workspace = active.workspace
        val server = requireNotNull(workspace.serverRecovery)
        when (phase) {
            "restore_edit" -> {
                check(input.recoveryKey.isNotBlank()) { "nas_recovery_material_missing" }
                // 首次恢复之后若网络发布失败，重开同一隔离目录继续确认，不能重复恢复或复制新增笔记。
                val restored = if (workspace.recentWorkspaceOrNull() == null)
                    requireNotNull(server.restore(input.serverUrl, input.recoveryKey, null)) else requireNotNull(workspace.reload())
                val shared = AndroidNasSyncFixture.shared(restored, input, area.runId)
                val firstText = AndroidNasSyncFixture.text(shared.note.blocks.single { it.id.value == AndroidNasSyncFixture.FIRST_BLOCK })
                val secondText = AndroidNasSyncFixture.text(shared.note.blocks.single { it.id.value == AndroidNasSyncFixture.SECOND_BLOCK })
                check(firstText == "nas-base-first" && secondText in setOf("nas-base-second", "nas-android-initial-edit")) { "nas_restore_resume_state_mismatch" }
                if (secondText != "nas-android-initial-edit") workspace.saveNote(shared.id, AndroidNasSyncFixture.replace(shared.note,
                    AndroidNasSyncFixture.SECOND_BLOCK, "nas-android-initial-edit"), shared.revision)
                if (restored.notes.none { it.note.title == "nas-android-addition-${area.runId}" })
                    workspace.saveNote(null, AndroidNasSyncFixture.addition(area.runId))
                server.updateIfChanged()
                val verified = AndroidNasSyncFixture.shared(requireNotNull(workspace.reload()), input, area.runId)
                AndroidNasSyncFixture.assertTexts(verified.note, "nas-base-first", "nas-android-initial-edit")
                check(server.status()?.mode == WorkspaceStorageMode.SERVER && server.status()?.pendingChanges == false) { "nas_publish_unconfirmed" }
            }
            "offline_edit" -> {
                val shared = AndroidNasSyncFixture.shared(requireNotNull(workspace.reload()), input, area.runId)
                server.useLocalMode()
                workspace.saveNote(shared.id, AndroidNasSyncFixture.replace(shared.note,
                    AndroidNasSyncFixture.SECOND_BLOCK, AndroidNasSyncFixture.ANDROID_OFFLINE), shared.revision)
                check(server.status()?.mode == WorkspaceStorageMode.LOCAL) { "nas_local_mode_missing" }
            }
            "merge_verify" -> {
                server.publish(input.serverUrl, "", "")
                server.updateIfChanged()
                val shared = AndroidNasSyncFixture.shared(requireNotNull(workspace.reload()), input, area.runId)
                AndroidNasSyncFixture.assertTexts(shared.note, AndroidNasSyncFixture.WINDOWS_OFFLINE, AndroidNasSyncFixture.ANDROID_OFFLINE)
                check(server.conflicts().none { !it.resolved }) { "nas_independent_blocks_conflicted" }
            }
            "conflict_edit" -> {
                val shared = AndroidNasSyncFixture.shared(requireNotNull(workspace.reload()), input, area.runId)
                AndroidNasSyncFixture.assertTexts(shared.note, AndroidNasSyncFixture.WINDOWS_OFFLINE, AndroidNasSyncFixture.ANDROID_OFFLINE)
                server.useLocalMode()
                workspace.saveNote(shared.id, AndroidNasSyncFixture.replace(shared.note,
                    AndroidNasSyncFixture.FIRST_BLOCK, AndroidNasSyncFixture.ANDROID_CONFLICT), shared.revision)
            }
            "conflict_publish" -> {
                server.publish(input.serverUrl, "", "")
                check(server.status()?.mode == WorkspaceStorageMode.SERVER && server.status()?.pendingChanges == false) { "nas_conflict_publish_unconfirmed" }
            }
            "conflict_verify" -> {
                server.updateIfChanged()
                val shared = AndroidNasSyncFixture.shared(requireNotNull(workspace.reload()), input, area.runId)
                val conflict = server.conflicts().single { it.objectId == shared.id && !it.resolved }
                check(conflict.fields.single().labelKey == "BLOCK") { "nas_conflict_field_mismatch" }
                val versions = listOf(conflict.fields.single().localContent, conflict.fields.single().remoteContent)
                // 字段视图使用正式 Markdown 导出，连字符预览需按相同转义核对；正文另行逐字验证。
                check(versions.any { it.contains(AndroidNasSyncFixture.WINDOWS_CONFLICT.replace("-", "\\-")) } &&
                    versions.any { it.contains(AndroidNasSyncFixture.ANDROID_CONFLICT.replace("-", "\\-")) }) { "nas_conflict_evidence_missing" }
            }
            "resolve_verify", "reopen_verify" -> {
                diagnostics.mark("remote_update_started")
                server.updateIfChanged()
                diagnostics.mark("remote_update_finished")
                var shared = AndroidNasSyncFixture.shared(requireNotNull(workspace.reload()), input, area.runId)
                AndroidNasSyncFixture.assertTexts(shared.note, AndroidNasSyncFixture.ANDROID_CONFLICT, AndroidNasSyncFixture.ANDROID_OFFLINE)
                val resolved = server.conflicts()
                check(resolved.isNotEmpty() && resolved.all { it.resolved && it.fields.all { field -> field.resolved } }) {
                    "nas_resolution_not_received"
                }
                diagnostics.mark("workspace_relock_started")
                workspace.lock()
                diagnostics.mark("workspace_relock_finished")
                diagnostics.mark("workspace_reopen_started")
                shared = AndroidNasSyncFixture.shared(workspace.openRecentWorkspace(), input, area.runId)
                diagnostics.mark("workspace_reopen_finished")
                AndroidNasSyncFixture.assertTexts(shared.note, AndroidNasSyncFixture.ANDROID_CONFLICT, AndroidNasSyncFixture.ANDROID_OFFLINE)
                check(active.searchEngine.search(SearchIndexQuery(text = "conflict")).any { it.noteId == shared.id }) { "nas_reopened_index_mismatch" }
                if (phase == "reopen_verify") {
                    // App 从自己的生命周期入口重新打开同一隔离工作区，真实设备操作不使用 UI 回调替身。
                    diagnostics.mark("ui_workspace_lock_started")
                    workspace.lock()
                    diagnostics.mark("ui_workspace_lock_finished")
                }
            }
            else -> error("nas_phase_not_supported")
        }
    }

    private companion object {
        /** 重跑时禁止覆盖后续状态的已确认样例写入；网络发布及所有验证阶段必须重新执行。 */
        val IDEMPOTENT_WRITE_PHASES: Set<String> = setOf("restore_edit", "offline_edit", "conflict_edit")
        /** 唯一允许的阶段，输入不能成为任意文件或应用命令。 */
        val PHASES: Set<String> = setOf("restore_edit", "offline_edit", "merge_verify", "conflict_edit", "conflict_publish",
            "conflict_verify", "resolve_verify", "reopen_verify", "cleanup")
    }
}
