package com.oruke.tagloom.index

/**
 * JVM 索引能力装配占位。
 *
 * @return 当前阶段不返回索引实现。
 */
actual fun createIndexEngine(): IndexEngine =
    TODO("JVM 索引能力尚未装配")

/**
 * JVM 搜索能力装配占位。
 *
 * @return 当前阶段不返回搜索实现。
 */
actual fun createSearchEngine(): SearchEngine =
    TODO("JVM 搜索能力尚未装配")
