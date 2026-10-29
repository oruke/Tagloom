package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.sync.HybridLogicalTime

/**
 * 在单一工作区写入会话内维持单调 HLC，使新变更严格晚于已观察的父版本。
 *
 * 调用方必须持有同一会话锁；本类不自行加锁，也不以本机回拨时间覆盖已观察的未来时间。
 * @param actorId 当前会话写入者的稳定身份。
 * @param physicalTimeMillis 提供 UTC Unix 毫秒；负值按零处理以保持协议时间非负。
 */
internal class WorkspaceHybridClock(
    /** 每次生成事件时写入的作者身份，不继承远端父版本的作者。 */
    private val actorId: ActorId,
    /** 当前宿主的物理时钟，只在生成本机事件时读取。 */
    private val physicalTimeMillis: () -> Long,
) {
    /** 所有已观察父版本和已生成本机事件中的最大物理时间；尚无观察时为空。 */
    private var observedPhysicalMillis: Long? = null
    /** 最大物理时间对应的最大逻辑计数，不能因较旧父版本的到达而回退。 */
    private var observedLogicalCounter: Int = 0

    /**
     * 推进父版本的时间上界；不生成事件，不消耗逻辑序号。
     * @param times 已认证且属于本次会话可见历史的父版本时间集合，允许为空。
     */
    fun observe(times: Collection<HybridLogicalTime>) {
        times.forEach { time ->
            val previous = observedPhysicalMillis
            if (previous == null || time.physicalTimeMillis > previous ||
                time.physicalTimeMillis == previous && time.logicalCounter > observedLogicalCounter) {
                observedPhysicalMillis = time.physicalTimeMillis
                observedLogicalCounter = time.logicalCounter
            }
        }
    }

    /**
     * 生成严格晚于全部已观察时间的本机事件；同毫秒和物理回拨均通过逻辑计数前进。
     *
     * 逻辑计数耗尽时把物理分量前进一毫秒，避免 Int 溢出产生负值；全域耗尽则拒绝写入，
     * 不能让 Long 回绕后把因果更晚的变更标记为更早。
     * @return 归属当前写入作者的新单调 HLC。
     */
    fun next(): HybridLogicalTime {
        val physicalNow = physicalTimeMillis().coerceAtLeast(0L)
        val previous = observedPhysicalMillis
        val nextPhysical: Long
        val nextLogical: Int
        if (previous == null || physicalNow > previous) {
            nextPhysical = physicalNow
            nextLogical = 0
        } else if (observedLogicalCounter == Int.MAX_VALUE) {
            check(previous < Long.MAX_VALUE) { "hybrid_clock_exhausted" }
            nextPhysical = previous + 1L
            nextLogical = 0
        } else {
            nextPhysical = previous
            nextLogical = observedLogicalCounter + 1
        }
        observedPhysicalMillis = nextPhysical
        observedLogicalCounter = nextLogical
        return HybridLogicalTime(nextPhysical, nextLogical, actorId)
    }
}
