package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.sync.HybridLogicalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 验证写入会话 HLC 在同毫秒、物理回拨、父版本领先与计数耗尽时保持因果单调。 */
class WorkspaceHybridClockTest {
    /** 同毫秒和回拨持续增加逻辑分量，物理时钟超越历史后才恢复零逻辑分量。 */
    @Test
    fun advancesWithinSameMillisecondAndAcrossPhysicalClockRollback() {
        var now = 1_000L
        val actor = ActorId("clock-local")
        val clock = WorkspaceHybridClock(actor) { now }
        assertEquals(HybridLogicalTime(1_000, 0, actor), clock.next())
        assertEquals(HybridLogicalTime(1_000, 1, actor), clock.next())
        now = 900
        assertEquals(HybridLogicalTime(1_000, 2, actor), clock.next())
        now = 1_001
        assertEquals(HybridLogicalTime(1_001, 0, actor), clock.next())
    }

    /** 本机时间落后父版本时使用父最大值再前进，迟到的旧父版本不会令时钟倒退。 */
    @Test
    fun observesFutureParentsAndRetainsLocalAuthorship() {
        val actor = ActorId("clock-local")
        val remoteActor = ActorId("clock-remote")
        val clock = WorkspaceHybridClock(actor) { 100L }
        clock.observe(listOf(
            HybridLogicalTime(500, 4, remoteActor),
            HybridLogicalTime(400, 100, remoteActor),
            HybridLogicalTime(500, 2, remoteActor),
        ))
        assertEquals(HybridLogicalTime(500, 5, actor), clock.next())
        clock.observe(listOf(HybridLogicalTime(500, 1, remoteActor)))
        clock.observe(emptyList())
        assertEquals(HybridLogicalTime(500, 6, actor), clock.next())
    }

    /** 逻辑计数耗尽时提升物理分量；全域耗尽稳定拒绝而不产生负数时间。 */
    @Test
    fun carriesLogicalOverflowIntoPhysicalTimeAndRejectsTotalExhaustion() {
        val actor = ActorId("clock-local")
        val clock = WorkspaceHybridClock(actor) { 1_000L }
        clock.observe(listOf(HybridLogicalTime(1_000, Int.MAX_VALUE, ActorId("clock-parent"))))
        assertEquals(HybridLogicalTime(1_001, 0, actor), clock.next())
        assertEquals(HybridLogicalTime(1_001, 1, actor), clock.next())
        clock.observe(listOf(HybridLogicalTime(Long.MAX_VALUE, Int.MAX_VALUE, actor)))
        assertEquals("hybrid_clock_exhausted", assertFailsWith<IllegalStateException> { clock.next() }.message)
        assertEquals("hybrid_clock_exhausted", assertFailsWith<IllegalStateException> { clock.next() }.message)
    }
}
