package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.model.Note
import com.oruke.tagloom.platform.WorkspaceStorageMode
import com.oruke.tagloom.sync.http.jvm.ServerRecoveryClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 用真实 TCP 屏障验证相同密文新确认点及传输排队取消，不能以 Mock 掩盖请求竞争。 */
class WorkspaceSyncTransferRegressionTest {
    /** 同一密文归档合法发布为更高版本后，本机修改仍可同步，不能停留在旧 CAS 版本。 */
    @Test
    fun acceptsNewerRemoteRevisionForIdenticalCiphertextBeforeNextLocalSave() = ServerRecoveryTestEnvironment().use { env ->
        runBlocking {
            val source = env.device("same-cipher-source")
            val key = requireNotNull(source.gateway.prepareCreation(null)).recoveryKeyText
            source.gateway.confirmCreation()
            val original = source.gateway.saveNote(null, Note(title = "Same ciphertext baseline", directTagIds = emptyList()))
            source.gateway.serverRecovery.publish(env.address, env.bootstrap, key)
            val session = requireNotNull(source.manager.sessionOrNull())
            val access = requireNotNull(ServerRecoveryAccessStore(source.root.resolve("remote")).read(requireNotNull(source.manager.rootOrNull()), session.workspaceId))
            try {
                ServerRecoveryClient(env.address, allowLoopbackHttp = true).use { client ->
                    val before = requireNotNull(client.latestSnapshot(session.workspaceId, access.actorId, access.token))
                    val archive = env.root.resolve("same-ciphertext.zip")
                    client.download(before, access.actorId, access.token, archive)
                    val repeated = client.upload(session.workspaceId, access.actorId, access.token, before.revision,
                        access.recoveryId, access.recoveryTokenSha256, archive)
                    assertEquals(before.sha256, repeated.sha256)
                    assertEquals(before.revision + 1, repeated.revision)
                    val changed = source.gateway.saveNote(original.id, original.note.copy(title = "Same ciphertext local update"), original.revision)
                    assertFalse(requireNotNull(source.gateway.serverRecovery.updateIfChanged()).pendingChanges)
                    val after = requireNotNull(client.latestSnapshot(session.workspaceId, access.actorId, access.token))
                    assertTrue(after.revision > repeated.revision)
                    val recovered = env.device("same-cipher-restored").gateway.serverRecovery.restore(env.address, key, null)
                    assertEquals(changed.note, recovered?.notes?.single()?.note)
                    source.gateway.lock()
                    assertEquals(changed.note, source.gateway.openRecentWorkspace().notes.single().note)
                }
            } finally { access.destroy() }
        }
    }

    /** 首个请求在真实 HTTP 中挂起且第二个已启动时，切本地必须使两者失效并保持本地写入。 */
    @Test
    fun switchingToLocalCancelsActiveAndQueuedTransfersWithoutUploading() = ServerRecoveryTestEnvironment().use { env ->
        runBlocking {
            val source = env.device("cancel-source")
            val key = requireNotNull(source.gateway.prepareCreation(null)).recoveryKeyText
            source.gateway.confirmCreation()
            val original = source.gateway.saveNote(null, Note(title = "Cancellation baseline", directTagIds = emptyList()))
            source.gateway.serverRecovery.publish(env.address, env.bootstrap, key)
            val local = source.gateway.saveNote(original.id, original.note.copy(title = "Local pending edit"), original.revision)
            val uploadsBefore = env.requests.uploadCalls
            val barrier = env.requests.blockNextMetadata()
            val active = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                try { source.gateway.serverRecovery.updateIfChanged() } catch (_: CancellationException) { }
            }
            barrier.awaitEntered()
            val queued = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                try { source.gateway.serverRecovery.updateIfChanged() } catch (_: CancellationException) { }
            }
            try {
                val mode = withTimeout(10_000) { source.gateway.serverRecovery.useLocalMode() }
                assertEquals(WorkspaceStorageMode.LOCAL, mode?.mode)
            } finally { barrier.release() }
            withTimeout(10_000) { active.join(); queued.join() }
            assertEquals(uploadsBefore, env.requests.uploadCalls)
            assertEquals(local.note, requireNotNull(source.gateway.reload()).notes.single().note)
            val localState = source.gateway.serverRecovery.updateIfChanged()
            assertEquals(WorkspaceStorageMode.LOCAL, localState?.mode)
            assertTrue(localState?.pendingChanges == true)
            assertEquals(uploadsBefore, env.requests.uploadCalls)

            // 同步队列失效后，重开只恢复本机已确认编辑，服务器仍保留原基线。
            source.gateway.lock()
            assertEquals(local.note, source.gateway.openRecentWorkspace().notes.single().note)
            assertEquals(WorkspaceStorageMode.LOCAL, source.gateway.serverRecovery.status()?.mode)
            assertEquals(WorkspaceStorageMode.LOCAL, source.gateway.serverRecovery.updateIfChanged()?.mode)
            assertEquals(uploadsBefore, env.requests.uploadCalls)
            val remote = env.device("cancel-remote").gateway.serverRecovery.restore(env.address, key, null)
            assertEquals(original.note, remote?.notes?.single()?.note)
        }
    }
}
