package com.oruke.tagloom

import com.oruke.tagloom.core.model.ActorId
import com.oruke.tagloom.core.model.WorkspaceId
import com.oruke.tagloom.core.protocol.CanonicalJson
import com.oruke.tagloom.core.sync.SyncDeviceRegistration
import com.oruke.tagloom.sync.server.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeFully
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.*

/** 验证登记库的真实持久化、一次性迁移、独占租约及不确定写入时的失败关闭。 */
class DeviceRegistryPersistenceTest {
    /** 旧文件仅导入一次，已撤销凭据不能通过重启或重放旧文件复活。 */
    @Test
    fun importsOnlyOnceAndRejectsCorruptionWithoutFallback() = withRegistryDirectory { root ->
        val legacy = root.resolve("legacy.json")
        val token = UUID.randomUUID().toString()
        val other = UUID.randomUUID().toString()
        val workspace = WorkspaceId("sample-workspace")
        val actor = ActorId("sample-device")
        val publicKey = "01".repeat(32)
        Files.writeString(legacy, CanonicalJson.format.encodeToString(listOf(DeviceCredentialRecord(workspace, actor, registryDigest(token), publicKey))))
        DeviceRegistryService(DeviceRegistryStore(root), null, legacy).use { registry ->
            val access = SyncDeviceAccess(SyncDeviceIdentity(workspace, actor), token)
            registry.register(access, ActorId("second-device"), SyncDeviceRegistration(registryDigest(other), publicKey))
            registry.revoke(access, ActorId("second-device"))
            assertFalse(registry.authenticate(SyncDeviceIdentity(workspace, ActorId("second-device")), other))
        }
        // 即使旧部署文件重新包含被撤销设备，重启后依然只认正式登记库。
        Files.writeString(legacy, CanonicalJson.format.encodeToString(listOf(DeviceCredentialRecord(workspace, ActorId("second-device"), registryDigest(other), publicKey))))
        DeviceRegistryService(DeviceRegistryStore(root), null, legacy).use { registry ->
            assertTrue(registry.authenticate(SyncDeviceIdentity(workspace, actor), token))
            assertFalse(registry.authenticate(SyncDeviceIdentity(workspace, ActorId("second-device")), other))
            assertContentEquals(ByteArray(32) { 1 }, registry.signingPublicKey(SyncDeviceIdentity(workspace, ActorId("second-device"))))
        }
        Files.writeString(root.resolve(".server/devices.json"), "{broken")
        DeviceRegistryStore(root).use { storage -> assertFailsWith<Exception> { DeviceRegistryService(storage, null, legacy) } }
    }

    /** 并发登记必须全部持久化，第二个写入实例不能抢占同一库。 */
    @Test
    fun serializesConcurrentEnrollmentAndExcludesSecondWriter() = withRegistryDirectory { root -> runBlocking {
        val token = UUID.randomUUID().toString()
        val workspace = WorkspaceId("sample-workspace")
        val actor = ActorId("sample-device")
        val legacy = root.resolve("legacy.json")
        Files.writeString(legacy, CanonicalJson.format.encodeToString(listOf(DeviceCredentialRecord(workspace, actor, registryDigest(token), "02".repeat(32)))))
        DeviceRegistryService(DeviceRegistryStore(root), null, legacy).use { registry ->
            assertFailsWith<IllegalStateException> { DeviceRegistryStore(root) }
            val access = SyncDeviceAccess(SyncDeviceIdentity(workspace, actor), token)
            coroutineScope {
                (1..12).map { index -> async(Dispatchers.IO) {
                    registry.register(access, ActorId("sample-$index"), SyncDeviceRegistration(registryDigest(UUID.randomUUID().toString()), "02".repeat(32)))
                } }.awaitAll()
            }
            assertEquals(13, registry.list(access, 100, null).devices.size)
        }
        DeviceRegistryService(DeviceRegistryStore(root), null).use { registry ->
            assertEquals(13, registry.list(SyncDeviceAccess(SyncDeviceIdentity(workspace, actor), token), 100, null).devices.size)
        }
    } }

    /** 原子替换后目录刷新失败意味着结果不确定，当前实例必须停止认证并从磁盘重启。 */
    @Test
    fun failsClosedAfterUncertainDurabilityAndRecoversFromDisk() = withRegistryDirectory { root ->
        DeviceRegistryStore(root, object : ServerDirectoryDurability {
            /** @param directory 已完成原子替换但模拟持久化失败的目录。 */
            override fun force(directory: Path) { throw java.io.IOException("injected durability failure") }
        }).use { storage ->
            assertFailsWith<IllegalStateException> { storage.load(null) }
            assertFailsWith<IllegalStateException> { storage.checkAvailable() }
        }
        DeviceRegistryStore(root).use { storage -> assertTrue(storage.load(null).workspaces.isEmpty()) }
    }

    /** 上传流中断必须清理临时文件，不能留下可被发布或下载的半份归档。 */
    @Test
    fun interruptedUploadNeverPublishesAnArchive() = withRegistryDirectory { root -> runBlocking {
        val channel = ByteChannel(autoFlush = true)
        val producer = launch {
            channel.writeFully(ByteArray(128) { 3 })
            channel.cancel(java.io.IOException("injected interrupted upload"))
        }
        assertFailsWith<java.io.IOException> {
            RecoveryArchiveStore(root).receive(WorkspaceId("sample-workspace"), channel, "01".repeat(32))
        }
        producer.join()
        Files.walk(root).use { paths -> assertEquals(0L, paths.filter(Files::isRegularFile).count()) }
    } }

    /**
     * 在独立目录中验证持久化并限制删除范围。
     * @param block 本次验证流程。
     */
    private fun withRegistryDirectory(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("tagloom-registry-test-").toAbsolutePath().normalize()
        try { block(root) } finally {
            check(root.parent == Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize())
            check(root.fileName.toString().startsWith("tagloom-registry-test-"))
            Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}

/** @param text 本次随机生成的访问凭据。 @return 不可直接认证的 SHA-256 摘要。 */
private fun registryDigest(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray())
    .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
