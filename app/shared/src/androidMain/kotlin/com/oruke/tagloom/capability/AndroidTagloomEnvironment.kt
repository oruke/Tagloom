package com.oruke.tagloom.capability

import android.content.Context
import com.oruke.tagloom.filesystem.AndroidFileSystemEnvironment
import com.oruke.tagloom.index.AndroidIndexEnvironment

/** 绑定 Android Application 环境；会话和密钥不放入全局对象。 */
object AndroidTagloomEnvironment {
    /** 仅持有 Application，避免 Activity 泄漏。 */
    private var application: Context? = null

    /**
     * 初始化 Android 运行目录，重复调用同一 Application 安全。
     * @param context 只保留其 applicationContext。
     */
    fun initialize(context: Context) {
        application = context.applicationContext
        AndroidFileSystemEnvironment.initialize(context)
        AndroidIndexEnvironment.initialize(context)
    }

    /**
     * 返回 Android 平台工厂使用的 Application 环境。
     * @return 初始化后的 Application Context。
     */
    fun context(): Context = checkNotNull(application) { "Android 能力环境尚未初始化" }
}
