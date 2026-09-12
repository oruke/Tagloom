package com.oruke.tagloom.index

import android.content.Context
import java.nio.file.Path

/** 为无工作区的临时索引提供应用私有缓存路径。 */
object AndroidIndexEnvironment {
    /** Application 的缓存目录，不持有 Activity。 */
    private var cache: Path? = null

    /**
     * 绑定应用私有缓存。
     * @param context 仅使用 applicationContext。
     */
    fun initialize(context: Context) { cache = context.applicationContext.cacheDir.canonicalFile.toPath() }

    /**
     * 返回临时加密索引父目录。
     * @return 已初始化的私有缓存目录。
     */
    fun cacheDirectory(): Path = checkNotNull(cache) { "Android 索引能力尚未初始化" }
}
