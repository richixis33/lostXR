import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("multiplatform") version "2.4.10"
    id("org.jetbrains.compose") version "1.12.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
}

kotlin {
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(compose.material3)
            implementation(compose.components.resources)
            implementation("zone.ien.hig:hig:1.4.1")
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.samrat.xrbridge.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "XR Bridge"
            packageVersion = "1.0.0"
            description = "USB installer for Android XR APK files"
            vendor = "MobileXR Orange"
            macOS {
                bundleID = "com.samrat.xrbridge"
            }
        }
    }
}
