package com.oruke.tagloom.filesystem.jvm

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * 为密文备份、同步下载和恢复分配随机临时归档，并返回它的真实路径。
 *
 * Android 的 JVM 默认缓存目录可能保留系统用户目录别名；只规范化此处刚创建的普通文件，
 * 不对用户提供的备份目标或工作区路径执行真实路径解析，避免掩盖其中应拒绝的符号链接。
 * @param prefix 不包含目录分隔符的固定用途前缀，不得传入笔记标题或用户文件名。
 * @return 已创建、没有符号链接叶节点的临时 ZIP 真实路径；调用方只写密文并负责最终删除。
 */
fun createEncryptedTemporaryArchive(prefix: String): Path {
    require(prefix.matches(Regex("[A-Za-z0-9_-]{3,80}"))) { "临时归档用途前缀无效" }
    val temporary = Files.createTempFile(prefix, ".zip")
    return try {
        require(Files.isRegularFile(temporary, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(temporary)) {
            "临时归档不是普通文件"
        }
        // 默认临时目录由平台选择，可能经过系统别名；解析刚创建的随机文件不会放宽业务路径的拒绝规则。
        temporary.toRealPath().also { real ->
            require(Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(real)) {
                "临时归档真实路径不是普通文件"
            }
        }
    } catch (error: Throwable) {
        Files.deleteIfExists(temporary)
        throw error
    }
}
