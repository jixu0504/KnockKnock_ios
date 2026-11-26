// settings.gradle.kts — 【本地 iOS 构建脚手架】
// 说明：上游仓库（GitHub `jixu0504/rhodeslink_ios`）不包含 Gradle 脚手架（settings/wrapper/版本目录），
// 本文件为在本机重建 shared → Shared.framework 链接链路而新增（见 docs/敲敲UI改版计划书-v1.1.md 第八章）。
pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        mavenCentral()
    }
}

rootProject.name = "rhodeslink"
include(":shared")