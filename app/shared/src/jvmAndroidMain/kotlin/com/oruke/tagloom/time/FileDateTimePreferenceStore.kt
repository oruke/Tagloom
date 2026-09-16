package com.oruke.tagloom.time

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException

/** 将不含笔记内容的显示偏好保存为设备配置，临时文件完整写入后再替换旧配置。 */
class FileDateTimePreferenceStore(
    /** 当前设备配置文件；测试必须注入独立临时目录。 */
    private val file: Path,
) : DateTimePreferenceStore {
    /** 允许后续版本添加字段，并为缺失字段保留系统默认。 */
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** @return 已保存偏好；损坏配置不能阻止解锁和读取笔记。 */
    override suspend fun load(): DateTimePreferences = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString<DateTimePreferences>(Files.readString(file)) }
            .getOrDefault(DateTimePreferences())
    }

    /**
     * 确认写入成功后返回，不修改任何工作区文件。
     * @param preferences 完整的本机显示偏好。
     */
    override suspend fun save(preferences: DateTimePreferences): Unit = withContext(Dispatchers.IO) {
        Files.createDirectories(file.toAbsolutePath().parent)
        val staging = Files.createTempFile(file.toAbsolutePath().parent, "datetime-", ".tmp")
        try {
            Files.writeString(staging, json.encodeToString(preferences))
            try {
                Files.move(staging, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staging, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(staging)
        }
    }
}
