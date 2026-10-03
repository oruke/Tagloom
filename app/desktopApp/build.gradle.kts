import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(projects.app.shared)
    implementation(projects.app.automation)

    implementation(compose.desktop.currentOs)
}

compose.desktop {
    application {
        mainClass = "com.oruke.tagloom.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "com.oruke.tagloom"
            packageVersion = "1.0.0"
            modules("java.desktop", "java.logging", "java.naming", "java.sql", "java.xml")

            // 品牌图标（生成自 brand/logo-05-solid-a.svg，见 brand/README.md）
            val iconsRoot = project.file("desktop-icons")
            windows {
                iconFile.set(iconsRoot.resolve("icon-windows.ico"))
            }
            linux {
                iconFile.set(iconsRoot.resolve("icon-linux.png"))
            }
            macOS {
                iconFile.set(iconsRoot.resolve("icon-mac.icns"))
            }
        }
    }
}
