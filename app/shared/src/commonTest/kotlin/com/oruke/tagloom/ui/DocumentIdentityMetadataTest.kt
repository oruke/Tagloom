package com.oruke.tagloom.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 验证快照系统时间在 KMP 公共 UI 中使用稳定且安全的紧凑展示。
 */
class DocumentIdentityMetadataTest {
    /**
     * 验证 ISO 时间压缩到分钟并明确 UTC，未知和空格式采用安全回退。
     */
    @Test
    fun formatsSnapshotTimestampWithoutChangingSemantics() {
        assertEquals("2026-07-15 08:23 UTC", "2026-07-15T08:23:46Z".toCompactUtcTimestamp())
        assertEquals("future-format", "future-format".toCompactUtcTimestamp())
        assertEquals("尚未保存", "".toCompactUtcTimestamp())
    }
}
