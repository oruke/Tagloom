package com.oruke.tagloom

import androidx.activity.ComponentActivity

/**
 * 仅供 debug instrumentation 的空界面宿主，不创建能力容器、不读取默认工作区，也不处理外部输入。
 *
 * debug manifest 通过平台 DUMP 权限限制外部启动；测试借用已持有该权限的 shell 启动空窗口，
 * 再由 ActivityScenario 注入隔离能力和生产 App。此类与注册均不进入 release 构建。
 */
class NasAcceptanceActivity : ComponentActivity()
