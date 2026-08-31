package com.oruke.tagloom.filesystem.jvm

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

/**
 * 提供同目录临时文件、文件 force、原子替换和目录持久化的 JVM 文件原语。
 */
class JvmAtomicFileOperations(
    /** 当前操作系统的目录持久化实现。 */
    private val directoryDurability: JvmDirectoryDurability = JvmDirectoryDurabilityFactory.create(),
) : JvmAtomicFilePort {
    /**
     * 把完整字节写入同目录临时文件并原子替换目标。
     *
     * @param target 最终目标路径。
     * @param bytes 完整密文或非敏感事务控制记录。
     */
    override fun writeAtomically(
        target: Path,
        bytes: ByteArray,
    ) {
        val parent = requireNotNull(target.parent) { "原子写入目标必须有父目录" }
        createDirectoriesDurably(parent)
        val temporary = parent.resolve(".${target.fileName}.${UUID.randomUUID()}.tmp")
        try {
            FileChannel.open(
                temporary,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            ).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) {
                    channel.write(buffer)
                }
                channel.force(true)
            }
            moveAtomically(temporary, target)
            forceDirectory(parent)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /**
     * 保留来源文件并通过同目录临时副本原子替换目标。
     *
     * @param source 已完整持久化的加密来源文件。
     * @param target 最终目标路径。
     */
    override fun copyAtomically(
        source: Path,
        target: Path,
    ) {
        requireRegularFile(source)
        val parent = requireNotNull(target.parent) { "原子复制目标必须有父目录" }
        createDirectoriesDurably(parent)
        val temporary = parent.resolve(".${target.fileName}.${UUID.randomUUID()}.tmp")
        try {
            Files.copy(source, temporary)
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel -> channel.force(true) }
            moveAtomically(temporary, target)
            forceDirectory(parent)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /**
     * 发布一个已在目标同目录完整写入的文件，并持久化重命名后的目录项。
     *
     * 该入口用于无法一次性放入内存的流式产物，例如密文备份归档。来源文件必须由调用方
     * 以 `CREATE_NEW` 创建并完成内容校验，避免把任意外部路径当作可信临时文件移动。
     *
     * @param source 已完成写入且与目标同目录的普通文件。
     * @param target 最终目标文件；存在时会被原子替换。
     */
    override fun publishPreparedFileAtomically(
        source: Path,
        target: Path,
    ) {
        val sourceParent = requireNotNull(source.parent).toAbsolutePath().normalize()
        val targetParent = requireNotNull(target.parent).toAbsolutePath().normalize()
        require(sourceParent == targetParent) { "流式产物必须在目标同目录发布" }
        requireRegularFile(source)
        requireDirectory(targetParent)
        FileChannel.open(source, StandardOpenOption.WRITE).use { channel -> channel.force(true) }
        moveAtomically(source, target)
        forceDirectory(targetParent)
    }

    /**
     * 删除单个目标并持久化其父目录元数据。
     *
     * @param target 待删除文件。
     */
    override fun deleteDurably(target: Path) {
        target.parent?.let(::requireDirectory)
        if (Files.deleteIfExists(target)) {
            target.parent?.let(::forceDirectory)
        }
    }

    /**
     * 在同一文件系统内原子移动完整目录并持久化源、目标父目录。
     *
     * @param source 待隔离事务目录。
     * @param target 不存在的隔离目标目录。
     */
    override fun moveDirectoryAtomically(
        source: Path,
        target: Path,
    ) {
        val sourceParent = requireNotNull(source.parent)
        val targetParent = requireNotNull(target.parent)
        requireDirectory(source)
        require(!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { "原子移动目标必须不存在" }
        createDirectoriesDurably(targetParent)
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (exception: AtomicMoveNotSupportedException) {
            throw IllegalStateException("工作区文件系统不支持原子隔离", exception)
        }
        forceDirectory(sourceParent)
        forceDirectory(targetParent)
    }

    /**
     * 要求底层文件系统支持同文件系统原子替换，不以非原子 move 静默降级。
     *
     * @param source 同目录临时文件。
     * @param target 最终目标。
     */
    private fun moveAtomically(
        source: Path,
        target: Path,
    ) {
        try {
            Files.move(
                source,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (exception: AtomicMoveNotSupportedException) {
            throw IllegalStateException("工作区文件系统不支持原子替换", exception)
        }
    }

    /**
     * 在平台允许时 force 目录句柄，确保重命名元数据跨崩溃持久化。
     *
     * Windows/JDK 若不支持打开目录句柄会显式失败，调用方不得把提交报告为成功。
     *
     * @param directory 已发生创建、替换或删除的目录。
     */
    override fun forceDirectory(directory: Path) {
        requireDirectory(directory)
        directoryDurability.force(directory)
    }

    /**
     * 逐级创建缺失目录，并在每次创建后 force 父目录项。
     *
     * @param directory 必须存在的目标目录。
     */
    private fun createDirectoriesDurably(directory: Path) {
        val normalized = directory.toAbsolutePath().normalize()
        require(!Files.isSymbolicLink(normalized)) { "目录不能是符号链接" }
        if (Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) return
        require(!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) { "目标路径不是目录" }
        val missing = mutableListOf<Path>()
        var current: Path? = normalized
        while (current != null && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            missing.add(current)
            current = current.parent
        }
        current?.let(::requireDirectory)
        missing.asReversed().forEach { path ->
            Files.createDirectory(path)
            path.parent?.let(::forceDirectory)
        }
    }

    /**
     * 要求路径是不经过符号链接的普通文件。
     *
     * @param path 待读取或复制的来源文件。
     */
    private fun requireRegularFile(path: Path) {
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
            "文件来源必须是普通文件"
        }
    }

    /**
     * 要求路径是不经过符号链接的普通目录。
     *
     * @param path 待使用的目录路径。
     */
    private fun requireDirectory(path: Path) {
        require(!Files.isSymbolicLink(path)) { "目录不能是符号链接" }
        require(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) { "目录路径不是普通目录" }
    }
}
