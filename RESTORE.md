# RhodesLink iOS 构建产物备份

> 上传时间：2026-11-23 · 基线 Android 1.14.27 / versionCode 74 · 分支 `ios-port`

| 文件 | 大小 | 用途 | 还原位置 |
|---|---|---|---|
| `artifacts/device-release-Shared.framework.tar.gz` | 43 MB | 真机（iosArm64）release framework | `RhodesLink/shared/build/bin/iosArm64/releaseFramework/` |
| `artifacts/simulator-debug-Shared.framework.tar.gz` | 73 MB | 模拟器（iosSimulatorArm64）debug framework | `RhodesLink/shared/build/bin/iosSimulatorArm64/debugFramework/` |
| `artifacts/app-1.14.27-baseline.apk` | 97 MB | Android 1.14.27 冻结基线 APK | `ios-port/baseline/app-1.14.27-baseline.apk` |

## 还原命令

```bash
cd /path/to/workspace
mkdir -p RhodesLink/shared/build/bin/iosArm64/releaseFramework RhodesLink/shared/build/bin/iosSimulatorArm64/debugFramework
tar -xzf artifacts/device-release-Shared.framework.tar.gz -C RhodesLink/shared/build/bin/iosArm64/releaseFramework/
tar -xzf artifacts/simulator-debug-Shared.framework.tar.gz -C RhodesLink/shared/build/bin/iosSimulatorArm64/debugFramework/
cp artifacts/app-1.14.27-baseline.apk ios-port/baseline/
```

## 或者直接重新构建（推荐，构建机已具备完整工具链）

```bash
cd RhodesLink
source ../ios-port/toolchain/env.sh
./gradlew :shared:linkReleaseFrameworkIosArm64        # 真机 release（约 4-5 分钟）
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64 # 模拟器 debug（约 1.5 分钟）
```

> 说明：本仓库仅存放构建产物（二进制大文件），源码在主工作区 `RhodesLink/`（分支 `ios-port`）。
> 工具链（JDK21 / Android SDK / K/N / Gradle 缓存，共约 5.4GB）不在此备份，可按 `ios-port/docs/实机测试准备手册.md` 重建。
