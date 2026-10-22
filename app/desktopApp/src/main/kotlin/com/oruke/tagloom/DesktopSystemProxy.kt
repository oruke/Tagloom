package com.oruke.tagloom

import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * 在装配桌面网络能力前启用系统代理，并保证本机自动化通信不经过代理。
 * 显式 JVM 开关保持优先；重复初始化不叠加选择器。
 */
internal fun enableDesktopSystemProxy() {
    // 先补齐开关再取得默认选择器，JDK 只在首次初始化时读取该配置。
    System.getProperties().putIfAbsent("java.net.useSystemProxies", "true")
    val systemSelector = ProxySelector.getDefault()
    if (systemSelector !is DesktopSystemProxySelector) {
        ProxySelector.setDefault(DesktopSystemProxySelector(systemSelector))
    }
}

/** 仅拦截回环目标，其他代理选择与失败通知完整交还给宿主系统。 */
private class DesktopSystemProxySelector(
    /** 系统原有选择器；宿主没有配置时按直连处理。 */
    private val delegate: ProxySelector?,
) : ProxySelector() {
    /**
     * 按目标地址选择代理，本机通信必须直连且不通过 DNS 判定域名是否位于本机。
     * @param uri 本次请求的目标地址。
     * @return 有序代理列表；回环目标或无系统选择器时仅包含直连。
     */
    override fun select(uri: URI): List<Proxy> =
        if (uri.isLoopbackTarget()) listOf(Proxy.NO_PROXY)
        else delegate?.select(uri).orEmpty().ifEmpty { listOf(Proxy.NO_PROXY) }

    /**
     * 保留系统对代理连接失败的处理，不自行吞掉失败或回退到外网直连。
     * @param uri 请求目标地址。
     * @param address 失败代理的网络地址。
     * @param error 原始连接失败原因。
     */
    override fun connectFailed(uri: URI, address: SocketAddress, error: IOException) {
        delegate?.connectFailed(uri, address, error)
    }
}

/**
 * 判断 localhost 和回环 IP 字面量，避免把域名解析或网络等待放入代理选择流程。
 * @return 目标明确属于本机回环时为真。
 */
private fun URI.isLoopbackTarget(): Boolean {
    val target = host?.removeSurrounding("[", "]")?.substringBefore('%')?.trimEnd('.')?.lowercase()
        ?: return false
    if (target == "localhost" || target.endsWith(".localhost")) return true
    // 含冒号的合法 URI host 是 IPv6 字面量，此处解析不会向 DNS 查询域名。
    if (':' in target) return runCatching { InetAddress.getByName(target).isLoopbackAddress }.getOrDefault(false)
    val octets = target.split('.')
    return octets.size == 4 && octets.first() == "127" && octets.all { octet ->
        octet.isNotEmpty() && octet.all(Char::isDigit) && octet.toIntOrNull()?.let { it in 0..255 } == true
    }
}
