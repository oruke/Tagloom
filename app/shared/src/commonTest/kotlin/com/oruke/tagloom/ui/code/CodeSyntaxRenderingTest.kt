package com.oruke.tagloom.ui.code

import com.oruke.tagloom.core.markdown.MarkdownCodec
import com.oruke.tagloom.core.markdown.MarkdownImportOptions
import com.oruke.tagloom.core.model.BlockId
import com.oruke.tagloom.core.model.CodeBlockContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 验证真实 Markdown 语言别名、嵌套词法及纯文本降级经过显示层后不改变原文。 */
class CodeSyntaxRenderingTest {
    /** 常用代码和配置语言从 Markdown 导入后均产生语法颜色，并保留原文偏移。 */
    @Test
    fun highlightsImportedLanguagesWithoutRewritingCode() {
        val examples = mapOf(
            "kt" to "fun greet() = println(\"你好 🌿\") // greeting",
            "js" to "const answer = 42; // answer\nconsole.log(\"hello\");",
            "ts" to "const name: string = \"sample\";",
            "py" to "def greet():\n    return \"hello\" # greeting",
            "sql" to "SELECT title FROM notes WHERE count > 2;",
            "json" to "{\"language\": \"Kotlin\", \"enabled\": true, \"count\": 42}",
            "yaml" to "language: kotlin\nenabled: true # flag",
            "sh" to "#!/bin/sh\necho \"hello\" # greeting",
            "rust" to "fn main() { let count = 42; println!(\"hello\"); }",
            "java" to "public class Example { String name = \"sample\"; }",
            "diff" to "@@ -1 +1 @@\n-old value\n+new value",
            "markdown" to "## Example\n**strong** and [details](page.md)",
        )
        examples.forEach { (language, code) ->
            val note = MarkdownCodec.import(
                "```$language\n$code\n```",
                MarkdownImportOptions(
                    "示例",
                    importedAt = "2026-01-01T00:00:00Z",
                    blockIdFactory = { BlockId("sample-$it") }),
            ).note
            val content = note.blocks.single().content as CodeBlockContent
            val highlighted = CodeSyntaxHighlighter.highlight(content.text, content.language, CodeSyntaxPalette.Light)
            assertEquals(code, highlighted.text, language)
            assertTrue(highlighted.spanStyles.isNotEmpty(), "$language 应有真实语法颜色")
            assertTrue(highlighted.spanStyles.all { it.start >= 0 && it.end <= code.length })
        }
    }

    /** HTML 内的脚本与多行注释都从完整原文分词，切换主题只替换颜色。 */
    @Test
    fun handlesEmbeddedLanguagesAndBothThemes() {
        val code = "<script>\n/* const hidden = 1; */\nconst label = \"🌿 示例\";\n</script>"
        val light = CodeSyntaxHighlighter.highlight(code, "html", CodeSyntaxPalette.Light)
        val dark = CodeSyntaxHighlighter.highlight(code, "html", CodeSyntaxPalette.Dark)
        assertEquals(code, light.text)
        assertEquals(code, dark.text)
        assertEquals(light.spanStyles.map { it.start to it.end }, dark.spanStyles.map { it.start to it.end })
        assertTrue(light.spanStyles.any { it.item.color == CodeSyntaxPalette.Light.keyword })
        assertTrue(light.spanStyles.any { it.item.color == CodeSyntaxPalette.Light.comment })
        assertTrue(dark.spanStyles.any { it.item.color == CodeSyntaxPalette.Dark.string })
    }

    /** 未知语言、空语言与语言菜单检索均不误改用户保存的标识或原文。 */
    @Test
    fun keepsUnknownLanguagesEditableAndResolvesAliases() {
        val code = "\tunknown syntax 🌿\r\n    trailing spaces  \n"
        listOf(null, "", "custom-language").forEach { language ->
            val highlighted = CodeSyntaxHighlighter.highlight(code, language, CodeSyntaxPalette.Light)
            assertEquals(code, highlighted.text)
            assertTrue(highlighted.spanStyles.isEmpty())
        }
        assertEquals("custom-language", CodeLanguageCatalog.displayName("custom-language"))
        assertEquals("Kotlin", CodeLanguageCatalog.displayName("kt"))
        assertTrue(CodeLanguageCatalog.search("js").any { it.id == "javascript" })
        assertTrue(CodeLanguageCatalog.search("C++").any { it.id == "cpp" })
    }
}
