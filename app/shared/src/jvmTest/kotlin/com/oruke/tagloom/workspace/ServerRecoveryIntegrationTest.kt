package com.oruke.tagloom.workspace

import com.oruke.tagloom.core.crypto.RecoveryKeyCodec
import com.oruke.tagloom.core.crypto.serverRecoveryCredentials
import com.oruke.tagloom.core.model.*
import com.oruke.tagloom.filesystem.jvm.crypto.LibsodiumCryptoProvider
import com.oruke.tagloom.sync.http.ServerRecoveryException
import com.oruke.tagloom.sync.http.jvm.ServerRecoveryClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import kotlin.test.*

/** 从正式网关走真实 TCP 服务，验证完整密文副本能在没有原本机材料的设备上恢复。 */
class ServerRecoveryIntegrationTest {
    /** 验证笔记、类型、属性、查询和身份保留，恢复后并发编辑经同步保留双版本。 */
    @Test
    fun restoresOriginalWorkspaceOnFreshDeviceAndPreservesConcurrentCopy() = ServerRecoveryTestEnvironment().use { env -> runBlocking {
        val first = env.device("first")
        val key = requireNotNull(first.gateway.prepareCreation(null)).recoveryKeyText
        first.gateway.confirmCreation()
        val originalId = requireNotNull(first.manager.sessionOrNull()).workspaceId
        val property = PropertyDefinition(PropertyId("sample-property"), "样例属性", PropertyTypes.TEXT)
        val tag = TagDefinition(TagId("sample-tag"), "样例类型", propertyBindings = listOf(TagPropertyBinding(property.id)))
        first.gateway.savePropertyDefinition(property)
        first.gateway.saveTagDefinition(tag)
        val note = first.gateway.saveNote(null, Note(title = "恢复验证样例", blocks = listOf(
            Block(BlockId("sample-block"), BlockTypes.PARAGRAPH, orderKey = "a", content = RichTextBlockContent(RichText(listOf(InlineRun("密文恢复正文样例"))))),
        ), directTagIds = listOf(tag.id), propertyValues = mapOf(property.id to NotePropertyValue(value = JsonPrimitive("恢复属性样例"), source = PropertyValueSource.USER, updatedAt = first.gateway.currentUtcTimestamp()))))
        val query = first.gateway.saveQuery(SavedQuery(name = "样例查询", text = "恢复"))
        val uploaded = first.gateway.serverRecovery.publish(env.address, env.bootstrap, key)
        assertNotNull(uploaded.uploadedAt)

        val second = env.device("second")
        val restored = requireNotNull(second.gateway.serverRecovery.restore(env.address, key, "123456"))
        assertFalse(restored.readOnly)
        assertEquals(originalId, second.manager.sessionOrNull()?.workspaceId)
        assertEquals(note, restored.notes.single())
        assertEquals(tag, restored.tagDefinitions.single { it.definition.id == tag.id }.definition)
        assertEquals(property, restored.propertyDefinitions.single { it.definition.id == property.id }.definition)
        assertEquals(query, restored.savedQueries.single())
        assertTrue(second.gateway.recentWorkspaceOrNull()?.requiresPin == true)
        second.gateway.lock()
        assertFailsWith<Exception> { second.gateway.unlockRecentWorkspace("654321") }
        assertEquals(note, second.gateway.unlockRecentWorkspace("123456").notes.single())
        val updated = second.gateway.saveNote(note.id, note.note.copy(title = "新设备编辑样例"), note.revision)
        second.gateway.serverRecovery.publish(env.address, "", "")

        val local = first.gateway.saveNote(note.id, note.note.copy(title = "旧设备离线编辑样例"), note.revision)
        assertFalse(first.gateway.serverRecovery.publish(env.address, "", "").pendingChanges)
        val conflicts = first.gateway.serverRecovery.conflicts().filter { it.objectId == note.id && !it.resolved }
        assertTrue(conflicts.isNotEmpty(), "并发标题编辑必须保留双方版本供核对")
        val retainedTitles = conflicts.flatMap { listOf(it.localTitle, it.remoteTitle) }.toSet()
        assertTrue(retainedTitles.containsAll(listOf(local.note.title, updated.note.title)))
        val merged = requireNotNull(first.gateway.reload()).notes.single()
        val third = env.device("third")
        assertEquals(merged.note, third.gateway.serverRecovery.restore(env.address, key, null)?.notes?.single()?.note)
        second.gateway.serverRecovery.updateIfChanged()
        assertEquals(merged.note, requireNotNull(second.gateway.reload()).notes.single().note)
        assertTrue(second.gateway.recentWorkspaceOrNull()?.requiresPin == true)

        // 人工可核对：服务器文件及本机配置中没有原 Recovery Key、标题、属性值或恢复 Bearer。
        val crypto = LibsodiumCryptoProvider().also { it.initialize() }
        val material = RecoveryKeyCodec(crypto).decode(key)
        val recoveryAccess = try { material.serverRecoveryCredentials(crypto).accessToken } finally { material.destroy() }
        val forbidden = listOf(key, recoveryAccess, "恢复验证样例", "密文恢复正文样例", "恢复属性样例")
        Files.walk(env.serverRoot).use { paths -> paths.filter(Files::isRegularFile).forEach { file ->
            if (file.fileName.toString().endsWith(".lock")) {
                // Windows 独占锁禁止读取锁文件；它必须是零字节，不能承载业务内容。
                assertEquals(0L, Files.size(file))
            } else {
                val bytes = Files.readAllBytes(file).decodeToString()
                forbidden.forEach { assertFalse(bytes.contains(it), "服务器只能存放密文和公开认证描述") }
            }
        } }
        Files.walk(first.root.resolve("remote")).use { paths -> paths.filter(Files::isRegularFile).forEach { file ->
            val bytes = Files.readAllBytes(file).decodeToString()
            assertFalse(bytes.contains(env.bootstrap)); assertFalse(bytes.contains(key)); assertFalse(bytes.contains("signingSeed"))
        } }
    } }

    /** 验证密钥轮换后必须显式更新服务器副本；旧密钥随后失效且新密钥可恢复原对象。 */
    @Test
    fun publishesNewRecoveryIdentityAfterMasterKeyRotation() = ServerRecoveryTestEnvironment().use { env -> runBlocking {
        val source = env.device("source")
        val oldKey = requireNotNull(source.gateway.prepareCreation(null)).recoveryKeyText
        source.gateway.confirmCreation()
        val saved = source.gateway.saveNote(null, Note(title = "轮换恢复样例", directTagIds = emptyList()))
        source.gateway.serverRecovery.publish(env.address, env.bootstrap, oldKey)
        val rotated = source.gateway.rotateMasterKey(null)
        assertTrue(source.gateway.serverRecovery.status()?.requiresRecoveryKey == true)
        assertFailsWith<Exception> { source.gateway.serverRecovery.publish(env.address, "", oldKey) }
        source.gateway.serverRecovery.publish(env.address, "", rotated.recoveryKeyText)
        val oldDevice = env.device("old-key")
        assertEquals("authentication_failed", assertFailsWith<ServerRecoveryException> {
            oldDevice.gateway.serverRecovery.restore(env.address, oldKey, null)
        }.code)
        assertNull(oldDevice.gateway.recentWorkspaceOrNull())
        assertFalse(Files.exists(oldDevice.workspaceRoot.resolve("manifest.enc")))
        val recovered = env.device("new-key").gateway.serverRecovery.restore(env.address, rotated.recoveryKeyText, null)
        assertEquals(saved.id, recovered?.notes?.single()?.id)
        assertEquals(saved.note, recovered?.notes?.single()?.note)
    } }

    /** 生产传输拒绝明文及携带 URL 凭据的地址，测试开关也不允许非回环主机。 */
    @Test
    fun refusesUnsafeServerAddresses() {
        assertFailsWith<ServerRecoveryException> { ServerRecoveryClient("http://example.invalid") }
        assertFailsWith<ServerRecoveryException> { ServerRecoveryClient("http://example.invalid", allowLoopbackHttp = true) }
        assertFailsWith<ServerRecoveryException> { ServerRecoveryClient("https://example.invalid/?token=sample") }
        assertFailsWith<ServerRecoveryException> { ServerRecoveryClient("https://sample@example.invalid") }
    }
}
