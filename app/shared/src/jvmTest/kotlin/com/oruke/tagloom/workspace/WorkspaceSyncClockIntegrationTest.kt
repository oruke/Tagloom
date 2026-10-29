package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.model.ObjectId
import com.oruke.tagloom.core.sync.HybridLogicalTime
import com.oruke.tagloom.core.sync.ObjectChange
import com.oruke.tagloom.core.workspace.ManifestObjectKind
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 从真实加密历史核对恢复和重开后的首次离线写入，不依赖内存时钟单测代替业务链路。 */
class WorkspaceSyncClockIntegrationTest {
    /** 未来父设备的确认点被旧物理时钟设备恢复后，两次首次离线写入都必须推进因果时间。 */
    @Test
    fun observesAuthenticatedFutureHistoryBeforeFirstOfflineSaveAfterRestoreAndReopen() = ServerRecoveryTestEnvironment().use { env ->
        runBlocking {
            val futureTime = System.currentTimeMillis() + 3_600_000L
            val first = env.device("clock-first") { futureTime }
            val key = requireNotNull(first.gateway.prepareCreation(null)).recoveryKeyText
            first.gateway.confirmCreation()
            val original = first.gateway.saveNote(null, Note(title = "Future clock baseline", directTagIds = emptyList()))
            val parent = first.latestClockChange(original.id)
            first.gateway.serverRecovery.publish(env.address, env.bootstrap, key)
            var oldPhysicalTime = 1_000L
            val second = env.device("clock-second") { oldPhysicalTime }
            second.gateway.serverRecovery.restore(env.address, key, null)

            // 尚未调用双向同步，第一次离线保存就必须观察归档中通过 AEAD 认证的父 HLC。
            val changed = second.gateway.saveNote(original.id, original.note.copy(title = "Restored offline edit"), original.revision)
            val restoredChange = second.latestClockChange(original.id)
            assertTrue(parent.changeId in restoredChange.parentChangeIds)
            assertClockAfterParent(restoredChange.hybridLogicalTime, parent.hybridLogicalTime)
            second.gateway.lock()
            oldPhysicalTime = 500L
            assertEquals(changed.note, second.gateway.openRecentWorkspace().notes.single().note)
            second.gateway.saveNote(changed.id, changed.note.copy(title = "Reopened offline edit"), changed.revision)
            val reopenedChange = second.latestClockChange(original.id)
            assertTrue(restoredChange.changeId in reopenedChange.parentChangeIds)
            assertClockAfterParent(reopenedChange.hybridLogicalTime, restoredChange.hybridLogicalTime)
        }
    }
}

/**
 * 通过实际 manifest、信封和摘要认证读取当前 Note 的逻辑 head。
 * @param id 当前已确认笔记身份。
 * @return 对应真实密文历史的最后一次逻辑 Change。
 */
private suspend fun ServerRecoveryTestDevice.latestClockChange(id: ObjectId): ObjectChange {
    val session = requireNotNull(manager.sessionOrNull())
    return session.sessionMutex.withLock {
        val local = DesktopReplicaCodec(session).local()
        val head = local.objects.single { it.kind == ManifestObjectKind.NOTE && it.snapshot.header.objectId == id }.snapshot.header.lastChangeId
        local.changes.single { it.changeId == head }
    }
}

/**
 * 核对物理和逻辑分量严格超过父版本，不允许仅靠 Actor 的字典序掩盖时钟未推进。
 * @param next 已保存子 Change 的时间。
 * @param parent 已认证父 Change 的时间。
 */
private fun assertClockAfterParent(next: HybridLogicalTime, parent: HybridLogicalTime) {
    assertTrue(next.physicalTimeMillis > parent.physicalTimeMillis ||
        next.physicalTimeMillis == parent.physicalTimeMillis && next.logicalCounter > parent.logicalCounter)
}
