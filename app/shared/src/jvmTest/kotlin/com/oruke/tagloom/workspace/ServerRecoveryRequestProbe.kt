package com.oruke.tagloom.workspace

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** 仅在真实测试 Netty 管线中观测请求，不替换客户端、网络或正式业务路由。 */
internal class ServerRecoveryRequestProbe {
    /** 已到达服务端的归档上传请求次数，不包含元数据查询。 */
    private val uploads = AtomicInteger()
    /** 尚未被下一次元数据 HTTP 请求领取的阻塞点。 */
    private val pendingMetadata = AtomicReference<ServerRecoveryRequestBarrier?>()
    /** 所有本测试创建的阻塞点，退出时必须全部释放。 */
    private val barriers = ConcurrentLinkedQueue<ServerRecoveryRequestBarrier>()
    /** 用于核对取消后的旧任务是否再次发起了真实上传。 */
    val uploadCalls: Int get() = uploads.get()

    /** @return 只阻塞下一次真实元数据请求的可控屏障。 */
    fun blockNextMetadata(): ServerRecoveryRequestBarrier = ServerRecoveryRequestBarrier().also {
        check(pendingMetadata.compareAndSet(null, it))
        barriers += it
    }

    /**
     * 在真实服务器收到请求后计数，并在测试指定的位置等待释放。
     * @param method 真实 HTTP 方法名称。
     * @param path 实际路由路径，不含访问凭据或查询正文。
     */
    suspend fun observe(method: String, path: String) {
        if (method == "PUT" && path.endsWith("/recovery-snapshot")) uploads.incrementAndGet()
        if (method == "GET" && path.endsWith("/recovery-snapshot/metadata")) pendingMetadata.getAndSet(null)?.awaitRelease()
    }

    /** 解除所有测试屏障，保证失败路径也不会让服务关闭等待悬空请求。 */
    fun releaseAll() { barriers.forEach { it.release() } }
}

/** 挂起一次真实元数据请求，分别暴露进入通知与释放动作。 */
internal class ServerRecoveryRequestBarrier {
    /** 服务端已经接收到真实请求，调用方据此启动并发排队场景。 */
    private val entered = CompletableDeferred<Unit>()
    /** 测试取消或模式切换完成后才放行服务器请求。 */
    private val released = CompletableDeferred<Unit>()

    /** 等待真实请求进入，超时使测试有界失败。 */
    suspend fun awaitEntered() { withTimeout(10_000) { entered.await() } }

    /** 服务端通知已进入，并有界等待测试释放；不构造任何假响应。 */
    suspend fun awaitRelease() {
        entered.complete(Unit)
        withTimeout(15_000) { released.await() }
    }

    /** 幂等释放当前真实请求，包括测试失败的清理路径。 */
    fun release() { released.complete(Unit) }
}
