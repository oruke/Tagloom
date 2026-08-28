import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    androidLibrary {
        namespace = "com.oruke.tagloom.app.index"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        withHostTest { }
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
