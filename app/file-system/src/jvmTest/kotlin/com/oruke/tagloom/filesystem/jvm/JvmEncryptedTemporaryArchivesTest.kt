package com.oruke.tagloom.filesystem.jvm

import java.nio.file.Files
import java.nio.file.LinkOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 验证加密临时归档分配器的真实路径规范化与非法前缀拒绝边界。 */
class JvmEncryptedTemporaryArchivesTest {
    /** 正常固定前缀应创建无符号链接叶节点的真实普通 ZIP 文件，并支持调用方清理。 */
    @Test
    fun createsResolvedRegularZipArchiveForValidPrefix() {
        val archive = createEncryptedTemporaryArchive("tagloom-temp-archive-")
        try {
            assertTrue(Files.exists(archive, LinkOption.NOFOLLOW_LINKS))
            assertTrue(Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS))
            assertFalse(Files.isSymbolicLink(archive))
            assertEquals(archive.toRealPath(), archive)
            assertTrue(archive.fileName.toString().startsWith("tagloom-temp-archive-"))
            assertTrue(archive.fileName.toString().endsWith(".zip"))
        } finally {
            Files.deleteIfExists(archive)
        }
    }

    /** 包含目录分隔符、长度越界或非固定 ASCII 字符的前缀必须直接拒绝。 */
    @Test
    fun rejectsUnsafeOrOutOfBoundsPrefixes() {
        val invalidPrefixes = listOf(
            "",
            "ab",
            "a".repeat(81),
            "../tagloom-",
            "nested/tagloom-",
            "nested\\tagloom-",
            "tagloom.archive.",
            "笔记标题前缀-",
        )
        invalidPrefixes.forEach { prefix ->
            assertFailsWith<IllegalArgumentException> {
                createEncryptedTemporaryArchive(prefix)
            }
        }
    }
}
