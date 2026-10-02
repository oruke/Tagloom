package com.oruke.tagloom.sync.server

import com.oruke.tagloom.core.protocol.CanonicalJson
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.*
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.builtins.ListSerializer

/**
 * 独占并原子发布服务端设备登记库；写入结果不确定时停止当前实例，防止旧内存状态覆盖新文件。
 * @param dataRoot 服务端数据根目录。
 * @param durability 当前操作系统的目录落盘原语。
 */
internal class DeviceRegistryStore(
    dataRoot: Path,
    /** 负责原子重命名后的目录元数据持久化。 */
    private val durability: ServerDirectoryDurability = ServerDirectoryDurabilityFactory.create(),
) : AutoCloseable {
    /** 与工作区密文目录分离的服务端状态目录。 */
    private val directory = dataRoot.toAbsolutePath().normalize().resolve(".server")
    /** 唯一权威认证快照。 */
    private val target = directory.resolve("devices.json")
    /** 生命周期内持有的进程写入租约通道。 */
    private val channel: FileChannel
    /** 防止两个服务进程同时修改同一登记库。 */
    private val lease: FileLock
    /** 写入失败或关闭后禁止使用该实例。 */
    private var available = true

    init {
        Files.createDirectories(directory)
        require(Files.isDirectory(directory, NOFOLLOW_LINKS)) { "设备登记目录无效" }
        channel = FileChannel.open(directory.resolve("devices.lock"), CREATE, WRITE, NOFOLLOW_LINKS)
        lease = try {
            channel.tryLock() ?: error("设备登记库已被其他服务占用")
        } catch (failure: Throwable) {
            channel.close()
            throw IllegalStateException("无法取得设备登记库写入租约", failure)
        }
    }

    /**
     * 读取权威快照；只有尚无登记库时才导入旧凭据，损坏文件绝不回退。
     * @param legacyFile 可选的旧版凭据文件。
     * @return 已验证版本的登记快照。
     */
    fun load(legacyFile: Path?): DeviceRegistrySnapshot {
        checkAvailable()
        if (Files.exists(target, NOFOLLOW_LINKS)) {
            require(Files.isRegularFile(target, NOFOLLOW_LINKS) && Files.size(target) <= MAX_REGISTRY_BYTES) { "设备登记库无效" }
            return CanonicalJson.decodeFromString(DeviceRegistrySnapshot.serializer(), Files.readString(target))
                .also { require(it.version == 1) { "设备登记库版本不受支持" } }
        }
        val records = if (legacyFile == null) emptyList() else {
            require(Files.isRegularFile(legacyFile, NOFOLLOW_LINKS) && Files.size(legacyFile) <= MAX_REGISTRY_BYTES) { "旧设备凭据文件无效" }
            CanonicalJson.decodeFromString(ListSerializer(DeviceCredentialRecord.serializer()), Files.readString(legacyFile))
        }
        val now = Instant.now().toString()
        val state = DeviceRegistrySnapshot(workspaces = records.groupBy { it.workspaceId }.map { (workspaceId, devices) ->
            require(devices.map { it.actorId }.distinct().size == devices.size) { "旧设备凭据包含重复身份" }
            RegisteredSyncWorkspace(workspaceId, devices.map {
                RegisteredSyncDevice(it.actorId, it.tokenSha256, it.signingPublicKeyHex, now)
            })
        })
        save(state)
        return state
    }

    /**
     * 先强制写入临时文件，再原子替换并刷新目录；失败后必须重启恢复。
     * @param state 即将确认的完整认证快照。
     */
    fun save(state: DeviceRegistrySnapshot) {
        checkAvailable()
        val bytes = CanonicalJson.encodeToString(DeviceRegistrySnapshot.serializer(), state).encodeToByteArray()
        require(bytes.size <= MAX_REGISTRY_BYTES) { "设备登记库超过容量限制" }
        val temporary = directory.resolve(".${UUID.randomUUID()}.tmp")
        try {
            FileChannel.open(temporary, CREATE_NEW, WRITE).use { output ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) output.write(buffer)
                output.force(true)
            }
            Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
            durability.force(directory)
        } catch (failure: Throwable) {
            // rename 可能已经成功；禁止继续使用旧内存认证状态，重启后只认磁盘确认点。
            available = false
            throw IllegalStateException("设备登记持久化失败，需要重启恢复", failure)
        } finally {
            bytes.fill(0)
            Files.deleteIfExists(temporary)
        }
    }

    /** 检查当前实例是否仍可提供确定的认证状态。 */
    fun checkAvailable() { check(available) { "设备登记库当前不可用" } }

    /** 使认证状态失效并释放进程写入租约。 */
    override fun close() {
        available = false
        try { if (lease.isValid) lease.release() } finally { channel.close() }
    }

    private companion object {
        /** 防止异常登记文件导致无界反序列化。 */
        const val MAX_REGISTRY_BYTES = 16 * 1024 * 1024
    }
}
