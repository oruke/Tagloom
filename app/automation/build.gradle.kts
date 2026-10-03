import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    application
}

kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget = JvmTarget.JVM_11 }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

application {
    mainClass = "com.oruke.tagloom.automation.MainKt"
    applicationName = "tagloom"
}

// 真实工作区初始化会读取 Compose 本地化资源，测试需要当前宿主的 Skiko 原生依赖。
// 保持为 testRuntimeOnly，CLI/MCP 分发物仍不依赖 Compose 或桌面 UI。
val desktopTestArchitecture = when (providers.systemProperty("os.arch").get()) {
    "aarch64", "arm64" -> "arm64"
    else -> "x64"
}
val desktopTestOs = when {
    providers.systemProperty("os.name").get().startsWith("Windows") -> "windows"
    providers.systemProperty("os.name").get().startsWith("Mac") -> "macos"
    else -> "linux"
}

dependencies {
    api(projects.core)
    implementation(libs.kotlinx.coroutinesCore)
    implementation(libs.kotlinx.serializationJson)
    implementation(libs.ktor.serverNetty)
    implementation(libs.ktor.serverContentNegotiation)
    implementation(libs.ktor.serverStatusPages)
    implementation(libs.ktor.serializationKotlinxJson)
    implementation(libs.ktor.clientCio)
    implementation(libs.mcp.sdkServer)
    testImplementation(libs.kotlin.testJunit)
    // 入口验收只在测试中装配真实客户端工作区，生产适配器不依赖 UI 或存储实现。
    testImplementation(projects.app.shared)
    testRuntimeOnly("org.jetbrains.compose.desktop:desktop-jvm-$desktopTestOs-$desktopTestArchitecture:${libs.versions.composeMultiplatform.get()}")
}

tasks.test {
    dependsOn(tasks.installDist)
    systemProperty("tagloom.cli.home", layout.buildDirectory.dir("install/tagloom").get().asFile.absolutePath)
    // 独立 SDK 验收需要显式准备 Node 测试依赖，不给常规 JVM 测试增加 npm 或网络要求。
    exclude("**/McpClientAcceptanceTest.class")
}

// 只在测试目录运行官方 TypeScript 客户端和 Inspector，生产 CLI/MCP 依赖保持不变。
tasks.register<Test>("mcpClientTest") {
    group = "verification"
    description = "Runs real encrypted-workspace acceptance through independent MCP clients."
    dependsOn(tasks.testClasses, tasks.installDist)
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching("com.oruke.tagloom.automation.McpClientAcceptanceTest") }
    systemProperty("tagloom.cli.home", layout.buildDirectory.dir("install/tagloom").get().asFile.absolutePath)
    val clientHome = providers.gradleProperty("mcpClientHome")
        .orElse(layout.buildDirectory.dir("mcp-client-acceptance").map { it.asFile.absolutePath })
    systemProperty("tagloom.mcp.client.home", clientHome.get())
    val sdkPackage = file(clientHome.get()).resolve("node_modules/@modelcontextprotocol/sdk/package.json")
    doFirst {
        check(sdkPackage.isFile) {
            "Prepare the independent MCP test dependencies as documented in docs/local-automation.md."
        }
    }
}
