package com.oruke.tagloom

import com.oruke.tagloom.sync.server.DeviceTokenAuthenticator
import com.oruke.tagloom.sync.server.JvmBlindSyncStore
import com.oruke.tagloom.sync.server.installSyncServer
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.nio.file.Path

/** 启动自部署 Tagloom 盲同步 HTTP 服务。 */
fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

/**
 * 从部署环境加载密文存储目录和仅含 Token 摘要的设备凭据文件。
 *
 * 必须显式提供 `TAGLOOM_SYNC_CREDENTIALS_FILE`，避免以开放注册或默认口令启动同步节点。
 */
fun Application.module() {
    val credentialsPath = System.getenv(CREDENTIALS_FILE_ENV)
        ?.takeIf(String::isNotBlank)
        ?.let(Path::of)
        ?: error("必须配置 $CREDENTIALS_FILE_ENV")
    val dataPath = System.getenv(DATA_DIRECTORY_ENV)
        ?.takeIf(String::isNotBlank)
        ?.let(Path::of)
        ?: Path.of("data")
    installSyncServer(
        store = JvmBlindSyncStore(dataPath),
        authenticator = DeviceTokenAuthenticator.fromCredentialFile(credentialsPath),
    )
}

/** 设备 Token 摘要凭据文件环境变量。 */
private const val CREDENTIALS_FILE_ENV: String = "TAGLOOM_SYNC_CREDENTIALS_FILE"

/** 盲同步密文数据目录环境变量。 */
private const val DATA_DIRECTORY_ENV: String = "TAGLOOM_SYNC_DATA_DIR"
