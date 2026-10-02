package com.oruke.tagloom.workspace

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.UUID

/** 为服务器工作区分配应用私有目录，恢复失败时只清理本次分配的目标。 */
internal class ServerWorkspaceDirectoryStore(
    /** 与用户手选本地目录隔离的受控父目录。 */
    private val directory: Path,
) {
    /** @return 尚不存在的独立工作区路径，不提示用户选择本地文件夹。 */
    fun allocate(): Path {
        Files.createDirectories(directory)
        check(Files.isDirectory(directory, NOFOLLOW_LINKS))
        return directory.toAbsolutePath().normalize().resolve(UUID.randomUUID().toString())
    }

    /**
     * 清理失败恢复的临时目录；必须先关闭会话，且不跟随符号链接。
     * @param target 本次 allocate 返回的路径，绝不接受服务器或用户输入的路径。
     */
    fun discard(target: Path) {
        check(target.parent == directory.toAbsolutePath().normalize())
        check(runCatching { UUID.fromString(target.fileName.toString()) }.isSuccess)
        if (Files.exists(target, NOFOLLOW_LINKS)) {
            Files.walk(target).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}
