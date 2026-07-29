import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ktor)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

group = "com.oruke.tagloom"
version = "1.0.0"
application {
    mainClass = "com.oruke.tagloom.ApplicationKt"
}

dependencies {
    api(projects.core)
    implementation(projects.adapters.cryptoLibsodium)
    implementation(projects.adapters.fileStoreJvm)
    implementation(libs.logback)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    implementation(libs.ktor.serverContentNegotiation)
    implementation(libs.ktor.serverStatusPages)
    implementation(libs.ktor.serializationKotlinxJson)
    implementation(libs.kotlinx.coroutinesCore)
    implementation(libs.kotlinx.serializationJson)
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.kotlin.testJunit)
}
