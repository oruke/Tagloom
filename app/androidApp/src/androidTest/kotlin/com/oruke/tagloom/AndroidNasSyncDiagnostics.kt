package com.oruke.tagloom

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** 只记录现场验收的固定阶段及线程帧，不读取异常消息、局部变量、凭据或其他应用。 */
internal class AndroidNasSyncDiagnostics(
    /** 本次运行限定的私有目录，诊断与工作区密文分开保存。 */
    private val area: AndroidNasSyncArea,
    /** 协调器已经验证过的固定验收阶段。 */
    private val phase: String,
) : Closeable {
    /** 启动当前 JUnit 方法的 instrumentation 线程，避免按可能变化的线程名称猜测。 */
    private val testThread = Thread.currentThread()
    /** 写出进度与快照时串行访问诊断文件，不参与任何生产业务锁。 */
    private val outputLock = Any()
    /** 当前固定进度名称，供测试线程挂起时的后台诊断读取。 */
    private val currentStage = AtomicReference("created")
    /** 阶段结束会立即唤醒诊断线程，不让它跨 instrumentation 阶段继续采样。 */
    private val finished = CountDownLatch(1)
    /** 仅观察本进程的守护线程；三十秒后最多采集一次有界线程栈。 */
    private val watchdog = thread(name = "nas-acceptance-diagnostics", isDaemon = true) {
        if (!finished.await(30, TimeUnit.SECONDS)) runCatching { captureThreads() }
    }

    /**
     * 在等待操作前后持久化固定进度，使无 Activity 的挂起可以区分网络、会话与界面阶段。
     * @param stage 仅由测试源码提供的小写固定标识，不接受 URL、路径或用户内容。
     */
    fun mark(stage: String) {
        require(stage.matches(Regex("[a-z][a-z0-9_]{0,79}"))) { "nas_diagnostic_stage_invalid" }
        currentStage.set(stage)
        synchronized(outputLock) {
            Files.writeString(area.root.resolve("android-progress.jsonl"), buildJsonObject {
                put("phase", phase)
                put("stage", stage)
            }.toString() + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("nasAcceptanceStage", stage)
        })
    }

    /**
     * 有界保存测试、主线程及协程工作线程的状态和类方法行号，不序列化 Thread 或异常对象。
     *
     * 固定三十秒观察点只用于定位停在哪个 await；它不会中断请求或代替业务超时判定。
     */
    private fun captureThreads() {
        val selected = Thread.getAllStackTraces().entries.filter { (candidate, _) ->
            candidate === testThread || candidate.name == "main" || candidate.name.startsWith("DefaultDispatcher-worker-")
        }.sortedBy { (candidate, _) -> if (candidate === testThread) 0 else if (candidate.name == "main") 1 else 2 }.take(32)
        val snapshot = buildJsonObject {
            put("phase", phase)
            put("stage", currentStage.get())
            put("elapsedSeconds", 30)
            putJsonArray("threads") {
                selected.forEach { (candidate, frames) -> add(buildJsonObject {
                    put("role", if (candidate === testThread) "instrumentation" else if (candidate.name == "main") "main" else "dispatcher")
                    put("state", candidate.state.name)
                    putJsonArray("frames") {
                        frames.take(32).forEach { frame -> add(JsonPrimitive("${frame.className}.${frame.methodName}:${frame.lineNumber}")) }
                    }
                }) }
            }
        }
        synchronized(outputLock) {
            Files.writeString(area.root.resolve("android-thread-diagnostics.json"), snapshot.toString())
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("nasAcceptanceThreadSnapshot", "captured")
            putString("nasAcceptanceStage", currentStage.get())
        })
    }

    /** 结束后台观察；不等待或取消生产协程，也不改变工作区状态。 */
    override fun close() {
        finished.countDown()
        watchdog.join(1_000)
    }
}
