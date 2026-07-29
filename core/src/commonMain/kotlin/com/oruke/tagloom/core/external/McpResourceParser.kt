package com.oruke.tagloom.core.external

/** 定义 Tagloom MCP 可以按权限读取的资源类型。 */
enum class McpResourceKind {
    /** `note://<id>`。 */
    NOTE,

    /** `tag://<id>`。 */
    TAG,

    /** 固定 `workspace://schema`。 */
    WORKSPACE_SCHEMA,

    /** `query://saved/<id>`。 */
    SAVED_QUERY,
}

/** 描述解析后的 MCP 资源身份。 */
data class McpResourceIdentity(
    /** 资源类型。 */
    val kind: McpResourceKind,
    /** 随机资源 ID；workspace schema 使用固定 `schema`。 */
    val resourceId: String,
)

/** 严格解析不允许路径穿越、查询参数或 fragment 的 Tagloom MCP resource URI。 */
object McpResourceParser {
    /**
     * 解析受支持 MCP resource URI。
     *
     * @param uri MCP 客户端请求的 URI。
     * @return 支持且身份格式有效时返回资源；否则为空。
     */
    fun parse(uri: String): McpResourceIdentity? {
        if ('?' in uri || '#' in uri || ".." in uri || '\\' in uri) return null
        return when {
            uri == "workspace://schema" -> McpResourceIdentity(McpResourceKind.WORKSPACE_SCHEMA, "schema")
            uri.startsWith("note://") -> opaque(uri.removePrefix("note://"))
                ?.let { id -> McpResourceIdentity(McpResourceKind.NOTE, id) }
            uri.startsWith("tag://") -> opaque(uri.removePrefix("tag://"))
                ?.let { id -> McpResourceIdentity(McpResourceKind.TAG, id) }
            uri.startsWith("query://saved/") -> opaque(uri.removePrefix("query://saved/"))
                ?.let { id -> McpResourceIdentity(McpResourceKind.SAVED_QUERY, id) }
            else -> null
        }
    }

    /**
     * 验证资源使用不含标题或路径的随机标识。
     *
     * @param value URI 中的资源 ID。
     * @return 格式有效时原值，否则为空。
     */
    private fun opaque(value: String): String? = value.takeIf { candidate ->
        candidate.matches(Regex("[A-Za-z0-9_-]{1,128}"))
    }
}
