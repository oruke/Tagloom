package com.oruke.tagloom

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform