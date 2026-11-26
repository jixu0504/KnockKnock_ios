// shared/build.gradle.kts — 【本地 iOS 构建脚手架】
// 上游原文件已备份为 build.gradle.kts.upstream（含 Android target / Compose Multiplatform / AGP 配置）。
// 本机无 Android SDK，且 shared 源码 0 处 androidx.compose import，
// 因此这里仅保留 iOS 两个 target（iosArm64 + iosSimulatorArm64），用于链接 Shared.framework 供 Xcode 工程使用。
// 恢复上游配置：`cp shared/build.gradle.kts.upstream shared/build.gradle.kts`
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

sqldelight {
    databases {
        create("RhodesDatabase") {
            packageName.set("com.rhodes.privatechat.shared.db")
        }
    }
}

kotlin {
    // iOS-port：真机 + Apple Silicon 模拟器两个 target，导出静态 framework 供 Xcode 集成
    val iosArm64Target = iosArm64()
    val iosSimulatorTarget = iosSimulatorArm64()
    listOf(iosArm64Target, iosSimulatorTarget).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
            implementation(libs.multiplatform.settings)
            implementation(libs.multiplatform.settings.coroutines)
            implementation(libs.koin.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        iosMain.dependencies {
            implementation(libs.sqldelight.native)
            implementation(libs.ktor.client.darwin)
        }
    }
}