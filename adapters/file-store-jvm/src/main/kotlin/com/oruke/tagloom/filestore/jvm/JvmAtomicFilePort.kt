package com.oruke.tagloom.filestore.jvm

import java.nio.file.Path

/**
 * 抽象 JVM 原子文件原语，以便故障注入验证磁盘满和持久化失败。
 */
interface JvmAtomicFilePort {
    /**
     * 原子写入完整字节。
     *
     * @param target 最终目标。
     * @param bytes 完整内容。
     */
    fun writeAtomically(
        target: Path,
        bytes: ByteArray,
    )

    /**
     * 原子复制已持久化来源。
     *
     * @param source 来源文件。
     * @param target 最终目标。
     */
    fun copyAtomically(
        source: Path,
        target: Path,
    )

    /**
     * 发布同目录流式临时文件。
     *
     * @param source 已写完临时文件。
     * @param target 最终目标。
     */
    fun publishPreparedFileAtomically(
        source: Path,
        target: Path,
    )

    /**
     * 删除单个文件并持久化父目录。
     *
     * @param target 待删除文件。
     */
    fun deleteDurably(target: Path)

    /**
     * 原子发布完整目录。
     *
     * @param source 来源目录。
     * @param target 不存在目标目录。
     */
    fun moveDirectoryAtomically(
        source: Path,
        target: Path,
    )

    /**
     * 持久化目录元数据。
     *
     * @param directory 目标目录。
     */
    fun forceDirectory(directory: Path)
}
