plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
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
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xexpect-actual-classes")
        }
    }

    // iOS-port：真机 + Apple Silicon 模拟器两个 target，导出静态 framework 供 Xcode 集成
    iosArm64()
    val iosSimulatorTarget = iosSimulatorArm64()
    listOf(iosArm64(), iosSimulatorTarget).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }
    // iOS-port：测试固定跑在本机已安装/已启动的 iPhone 17 模拟器上（KGP 默认设备类型
    // 未必存在；显式指定避免 KGP 尝试创建不存在的设备）。
    iosSimulatorTarget.testRuns.configureEach {
        deviceId = "iPhone 17"
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
            implementation(libs.multiplatform.settings)
            implementation(libs.multiplatform.settings.coroutines)
            implementation(libs.koin.core)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.sqldelight.android)
            implementation(libs.androidx.security.crypto)
        }
        iosMain.dependencies {
            implementation(libs.sqldelight.native)
            implementation(libs.ktor.client.darwin)
        }
        // Phase 0 备份恢复闭环测试配置（lead 批准 2026-11-22）：
        // commonTest 双端跑（kotlin-test 依赖 T9 已加）；JVM 侧补 sqlite 内存驱动跑 DB 测试。
        val androidUnitTest by getting {
            dependencies {
                implementation(libs.sqldelight.sqlite)
            }
        }
    }
}

android {
    namespace = "com.rhodes.privatechat.shared"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
