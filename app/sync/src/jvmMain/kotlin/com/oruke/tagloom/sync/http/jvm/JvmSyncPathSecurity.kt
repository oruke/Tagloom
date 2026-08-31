package com.oruke.tagloom.sync.http.jvm

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.stream.Collectors

/**
 * 集中约束 JVM 同步副本的目录边界和符号链接语义。
 *
 * 同步目录属于可删除的派生缓存，但其中的密文仍会参与上传和验证；因此不能让目录替换
 * 或符号链接把读写重定向到工作区之外或其他本地文件。
 */
internal object JvmSyncPathSecurity {
    /**
     * 创建并验证一个不经过符号链接的普通目录。
     *
     * @param directory 待创建或验证的目录。
     */
    fun ensureDirectory(directory: Path) {
        val normalized = rejectSymbolicLinks(directory)
        Files.createDirectories(normalized)
        require(Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            "同步路径不是普通目录"
        }
        rejectSymbolicLinks(normalized)
    }

    /**
     * 在受控根目录内解析路径，并拒绝现有路径段中的符号链接。
     *
     * @param root 允许访问的同步根目录。
     * @param relative 相对根目录的词法路径。
     * @return 位于根目录内的绝对规范化路径。
     */
    fun resolveWithin(
        root: Path,
        relative: String,
    ): Path {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val target = normalizedRoot.resolve(relative).normalize()
        require(target.startsWith(normalizedRoot)) { "同步路径越界" }
        return rejectSymbolicLinks(target)
    }

    /**
     * 验证一个路径当前是非符号链接普通文件。
     *
     * @param file 待读取或比较的文件路径。
     */
    fun requireRegularFile(file: Path) {
        rejectSymbolicLinks(file)
        require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            "同步载荷路径不是普通文件"
        }
    }

    /**
     * 扫描目录下的普通文件，并在发现任何符号链接时拒绝整个副本。
     *
     * @param directory 待扫描的非符号链接目录。
     * @return 按路径稳定排序的普通文件集合。
     */
    fun listRegularFiles(directory: Path): List<Path> {
        val normalized = rejectSymbolicLinks(directory)
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            return emptyList()
        }
        require(Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            "同步载荷根路径不是普通目录"
        }
        return Files.walk(normalized).use { stream ->
            stream
                .peek { path ->
                    require(!Files.isSymbolicLink(path)) { "同步副本目录不能包含符号链接" }
                }
                .filter { path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) }
                .sorted()
                .collect(Collectors.toList())
        }
    }

    /**
     * 检查目标及其全部既有父路径没有符号链接。
     *
     * @param path 待检查的绝对或相对路径。
     * @return 规范化绝对路径。
     */
    private fun rejectSymbolicLinks(path: Path): Path {
        val normalized = path.toAbsolutePath().normalize()
        var current: Path? = normalized
        while (current != null) {
            require(!Files.isSymbolicLink(current)) { "同步路径不能经过符号链接" }
            current = current.parent
        }
        return normalized
    }
}
