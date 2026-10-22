package com.oruke.tagloom.core.model

/** 规范化后的网页地址与展示域名，不接受凭据、脚本、文件协议或相对路径。 */
data class LinkUrl(
    /** 保留路径、查询和片段的规范化 HTTP(S) 网址。 */
    val value: String,
    /** 仅用于展示的主机名。 */
    val host: String,
) {
    companion object {
        /**
         * 解析录入地址；没有协议的完整域名补 HTTPS，显式 HTTP 可访问本机服务。
         * @param input 原始用户输入。
         * @return 有效地址；校验失败为空。
         */
        fun parse(input: String): LinkUrl? {
            val trimmed = input.trim()
            if (trimmed.isEmpty() || trimmed.length > 8192 || trimmed.any { it.isWhitespace() || it in "<>\\\"" }) return null
            if (trimmed.indices.any {
                    trimmed[it] == '%' && (it + 2 >= trimmed.length || trimmed.substring(
                        it + 1,
                        it + 3
                    ).any { digit -> digit.digitToIntOrNull(16) == null })
                }) return null
            val explicit = trimmed.contains("://")
            if (!explicit && trimmed.startsWith('/')) return null
            val full = if (explicit) trimmed else "https://$trimmed"
            val match = Regex("(?i)^(https?)://([^/?#]+)(.*)$").matchEntire(full) ?: return null
            val scheme = match.groupValues[1].lowercase()
            val authority = match.groupValues[2]
            if ('@' in authority) return null
            val bracket = authority.startsWith('[')
            val host = if (bracket) authority.substringBefore(']').takeIf { authority.contains(']') }?.plus(']')
                ?: return null else authority.substringBefore(':').lowercase()
            if (host.isBlank() || host == "." || (!explicit && '.' !in host) ||
                (!bracket && !Regex("[\\p{L}\\p{N}._-]+").matches(host))
            ) return null
            val port = authority.substring(host.length).takeIf { it.isNotEmpty() }
            if (port != null && (!port.startsWith(':') || (port.drop(1).toIntOrNull() ?: 0) !in 1..65535)) return null
            val portNumber = port?.drop(1)?.toIntOrNull()
            val normalizedPort =
                portNumber?.takeUnless { (scheme == "https" && it == 443) || (scheme == "http" && it == 80) }
                    ?.let { ":$it" }.orEmpty()
            val suffix =
                match.groupValues[3].let { if (it.isEmpty() || it.startsWith('?') || it.startsWith('#')) "/$it" else it }
            return LinkUrl("$scheme://${host.lowercase()}$normalizedPort$suffix", host.lowercase())
        }
    }
}
