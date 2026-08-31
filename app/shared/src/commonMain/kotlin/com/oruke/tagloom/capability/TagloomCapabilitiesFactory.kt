package com.oruke.tagloom.capability

/**
 * 创建当前宿主的能力容器。
 *
 * 能力容器由宿主 actual 工厂装配。JVM actual 已连接三项 JVM 能力；Android/iOS actual
 * 仍保留 TODO。调用方只依赖这里的跨平台接口，不把平台实现泄漏到 commonMain。
 *
 * @return 当前宿主的能力集合。
 */
expect fun createTagloomCapabilities(): TagloomCapabilities
