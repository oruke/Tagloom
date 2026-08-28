package com.oruke.tagloom.capability

/**
 * 创建当前宿主的能力容器。
 *
 * 当前阶段只建立能力边界，各宿主 actual 均直接 TODO；调用方不得把 demo 当作生产能力。
 *
 * @return 当前宿主的能力集合。
 */
expect fun createTagloomCapabilities(): TagloomCapabilities
