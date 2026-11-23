# 构建产物 GitHub 备份记录（2026-11-23）

- 仓库：https://github.com/jixu0504/rhodeslink_ios （branch `main`）
- 上传内容（已校验远端大小一致）：
  | 文件 | 大小 | 用途 |
  |---|---|---|
  | `artifacts/device-release-Shared.framework.tar.gz` | 41.3 MB | 真机 iosArm64 release framework |
  | `artifacts/simulator-debug-Shared.framework.tar.gz` | 69.7 MB | 模拟器 iosSimulatorArm64 debug framework |
  | `artifacts/app-1.14.27-baseline.apk` | 93.0 MB | Android 1.14.27 基线 APK |
  | `RESTORE.md` | — | 还原说明与命令 |
- 本地已删除对应文件（framework 目录 + 基线 APK + 临时 staging 仓库），释放约 1GB。
- 还原：见仓库 RESTORE.md；或直接重新构建：
  - `./gradlew :shared:linkReleaseFrameworkIosArm64`（~4-5 分钟）
  - `./gradlew :shared:linkDebugFrameworkIosSimulatorArm64`（~1.5 分钟）
- 注意：`ios-port/scripts/build-device.sh` 在 framework 缺失时会自动重链，无需手动恢复。
- 安全：本次使用的 GitHub token 已暴露在会话中，建议用户使用后立即吊销。
