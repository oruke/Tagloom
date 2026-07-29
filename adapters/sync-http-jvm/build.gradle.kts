import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
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

dependencies {
    implementation(projects.core)
    implementation(projects.adapters.fileStoreJvm)
    implementation(libs.kotlinx.coroutinesCore)
    implementation(libs.kotlinx.serializationJson)
    testImplementation(projects.adapters.cryptoLibsodium)
    testImplementation(projects.server)
    testImplementation(libs.ktor.serverNetty)
    testImplementation(libs.kotlin.testJunit)
}
