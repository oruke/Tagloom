package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.core.sync.MergeSide
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 通过真实服务和两个独立加密工作区，验证同字段的相反确认不会被无内容变化的 Change 吞掉。 */
class WorkspaceOpposingResolutionSyncIntegrationTest {
    /** 确认临时值与选择另一值并发时重新待核对，再次明确选择后内容和历史跨设备收敛。 */
    @Test
    fun reopensOpposingChoicesEvenWhenOneConfirmationDoesNotChangePayload() = ServerRecoveryTestEnvironment().use { env ->
        runBlocking {
            val first = env.device("opposing-first")
            val key = requireNotNull(first.gateway.prepareCreation(null)).recoveryKeyText
            first.gateway.confirmCreation()
            val original = first.gateway.saveNote(null, Note("Initial choice title", emptyList()))
            first.gateway.serverRecovery.publish(env.address, env.bootstrap, key)
            val second = env.device("opposing-second")
            second.gateway.serverRecovery.restore(env.address, key, null)
            first.gateway.saveNote(original.id, original.note.copy(title = "First choice title"), original.revision)
            second.gateway.saveNote(original.id, original.note.copy(title = "Second choice title"), original.revision)
            first.gateway.serverRecovery.updateIfChanged()
            second.gateway.serverRecovery.updateIfChanged()
            first.gateway.serverRecovery.updateIfChanged()
            second.gateway.serverRecovery.updateIfChanged()

            val firstConflict = first.gateway.serverRecovery.conflicts().single()
            val secondConflict = second.gateway.serverRecovery.conflicts().single()
            assertEquals(firstConflict.id, secondConflict.id)
            val field = firstConflict.fields.single()
            assertEquals("TITLE", field.labelKey)
            val before = requireNotNull(first.gateway.reload()).notes.single().note
            val keepRemote = before.title == firstConflict.remoteTitle
            val wantedTitle = if (keepRemote) firstConflict.remoteTitle else firstConflict.localTitle

            // 设备一确认当前值，产生真实解决 Change，但业务 payload 与共同祖先完全相同。
            first.gateway.serverRecovery.resolveConflict(firstConflict.id, keepRemote, firstConflict.currentRevision, field.id)
            assertEquals(before, requireNotNull(first.gateway.reload()).notes.single().note)
            // 设备二尚未看到上一步，选择同一字段的另一个版本。
            second.gateway.serverRecovery.resolveConflict(secondConflict.id, !keepRemote, secondConflict.currentRevision, field.id)
            assertTrue(first.gateway.serverRecovery.conflicts().single().resolved)
            assertTrue(second.gateway.serverRecovery.conflicts().single().resolved)
            first.gateway.serverRecovery.updateIfChanged()
            second.gateway.serverRecovery.updateIfChanged()
            first.gateway.serverRecovery.updateIfChanged()
            second.gateway.serverRecovery.updateIfChanged()

            for (device in listOf(first, second)) {
                val reopened = device.gateway.serverRecovery.conflicts().single()
                assertFalse(reopened.resolved)
                assertFalse(reopened.fields.single().resolved)
                val session = requireNotNull(device.manager.sessionOrNull())
                val evidence = session.sessionMutex.withLock { loadReplicaConflicts(session).single() }
                assertEquals(2, evidence.fieldResolutions.size)
                assertEquals(setOf(MergeSide.LOCAL, MergeSide.REMOTE), evidence.fieldResolutions.map { it.selectedSide }.toSet())
            }

            // 在已合并 revision 上再次审阅，这次 Change 因果覆盖之前的两个确认，允许关闭冲突。
            val reviewed = first.gateway.serverRecovery.conflicts().single()
            first.gateway.serverRecovery.resolveConflict(reviewed.id, keepRemote, reviewed.currentRevision, field.id)
            first.gateway.serverRecovery.updateIfChanged()
            second.gateway.serverRecovery.updateIfChanged()
            first.gateway.serverRecovery.updateIfChanged()
            for (device in listOf(first, second)) {
                assertEquals(wantedTitle, requireNotNull(device.gateway.reload()).notes.single().note.title)
                val resolved = device.gateway.serverRecovery.conflicts().single()
                assertTrue(resolved.resolved)
                assertTrue(resolved.fields.single().resolved)
                val session = requireNotNull(device.manager.sessionOrNull())
                val evidence = session.sessionMutex.withLock { loadReplicaConflicts(session).single() }
                assertEquals(1, evidence.fieldResolutions.size)
                device.gateway.lock()
                assertEquals(wantedTitle, device.gateway.openRecentWorkspace().notes.single().note.title)
                assertTrue(device.gateway.serverRecovery.conflicts().single().resolved)
            }
        }
    }
}
