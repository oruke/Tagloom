package com.oruke.tagloom.core.model

import kotlin.test.*

/** 验证大型网页头的有界元数据提取，不依赖真实站点或执行页面脚本。 */
class WebPageMetadataParserTest {
    /** 大型内联样式和脚本后的真实头部元数据仍可提取，脚本中的伪标签不参与结果。 */
    @Test
    fun parsesMetadataAfterLargeInlineResources() {
        val html = buildString {
            append("<html><head><style>")
            append(" ".repeat(400 * 1024))
            append("</style><script>")
            append(" ".repeat(350 * 1024))
            append("""const sample = '<meta property="og:title" content="Script value">';</script>""")
            append("""<title>Fallback page</title><meta property="og:title" content="Large page title">""")
            append("""<meta name="description" content="Large page description"></head>""")
            append("""<body><meta property="og:title" content="Body value"></body></html>""")
        }

        assertTrue(html.indexOf("<title>") >= 750 * 1024)
        val metadata = WebPageMetadataParser.parse(html)
        assertEquals("Large page title", metadata.title)
        assertEquals("Large page description", metadata.description)
    }

    /** 头部没有元数据时不从正文中的伪标题或描述补值。 */
    @Test
    fun ignoresMetadataAfterHeadEnds() {
        val html = """<html><head></head><body><title>Body title</title><meta property="og:title" content="Body value"><meta name="description" content="Body description"></body></html>"""

        val metadata = WebPageMetadataParser.parse(html)
        assertNull(metadata.title)
        assertNull(metadata.description)
    }

    /** 字段位于扫描上限之外时安全返回空值，不为超大输入突破有界解析约束。 */
    @Test
    fun leavesMetadataBeyondBoundEmpty() {
        val html = buildString {
            append("<html><head><style>")
            append(" ".repeat(1024 * 1024))
            append("</style>")
            append("""<title>Outside title</title><meta property="og:title" content="Outside value">""")
            append("""<meta name="description" content="Outside description"></head></html>""")
        }

        val metadata = WebPageMetadataParser.parse(html)
        assertNull(metadata.title)
        assertNull(metadata.description)
    }
}
