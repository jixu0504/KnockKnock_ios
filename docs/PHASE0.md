# RhodesLink iOS 移植 — Phase 0 执行索引

- **基线**：Android 1.14.27 / versionCode 74，commit `3642d2c`，git tag `v1.14.27-android-baseline`，分支 `ios-port`
- **依据**：`RhodesLink-iOS开发计划书-v1.1-DSH执行版.md` + `RhodesLink-iOS可行性分析报告.md`
- **环境快照（2026-11-22）**：Xcode 27.2 (27B52)；无 iOS 模拟器 runtime；Java 25（无 JDK 21）；无 Android SDK；brew 可用；网络可用；磁盘空闲 49G
- **仓库**：`RhodesLink/`（Android 源，branch `ios-port`）；本目录 `ios-port/` 为 DSH 移植工作区

## 目录约定

| 目录 | 用途 |
|---|---|
| `baseline/` | 基线冻结产物（APK、构建日志、版本快照、测试盘点） |
| `golden/{db,prompts,backup,media,sessions}/` | 金样仓库（schema、提示词、备份包、媒体、会话验证件） |
| `checklist/` | 功能对照清单（52 路由） |
| `audit/` | 路径/字段/平台耦合盘点 |
| `specs/` | 规格卡（spec-card 先行） |
| `handoffs/` | 各代理 handoff.md checkpoint |
| `toolchain/` | 本地工具链（JDK21、Android SDK、konan、gradle-home） |

## 任务板（本波次）

- T1 p0-toolchain ✅ 环境 + 基线 APK + Kotlin 2.1.21 骨架
- T2 baseline ✅ 源码级金样归档（reviewer PASS）
- T3 checklist ✅ 52 路由对照清单（reviewer 修正已落实）
- T4 audit ✅ 路径/字段/耦合盘点（reviewer 修正已落实）
- T5 reviewer ✅ 第 1 批评审 + 备份规格卡评审
- T6 chat-import-feas ✅ 结论：纳入一期（4.0–6.5 代理日）
- T7 a7-presearch ✅ 结论：拆分移植（43–65% < 80% 触发线）
- T8 ios-shell-design ✅ 玻璃壳 Demo 编译通过（视觉待人类签字）
- T9 ios-data-base ✅ 数据底座最小闭环（模拟器 28 测试全绿）
- T10 backup-restore-loop ✅ 备份恢复最小闭环（JVM+iOS 各 24 测试全绿）
- T11 Kotlin 2.3.21 升级 ✅ commit 7cad292（324 单测回归全绿）
- T12 timeline-prototype ✅ 2,000 条消息原型（59.56fps 均值，0.09% 重度掉帧）
- T13 bg-task-probe ✅ 后台任务实验（模拟器系统调度不可用；深触发链 5/5 成功；文案结论支持「前台补算+本地通知」）

## 写域隔离

每个代理只写任务板上声明的目录。仓库内写域仅 T1 拥有；T2–T4 只写 `ios-port/` 下各自目录。合并/评审由 T5 与 lead 把关。

## Gate 0（目标验收）

1. ✅ Android 构建不回归（2.1.21 与 2.3.21 各一次；324 单测全绿）
2. ✅ 数据库打开并完成迁移（iOS 27.2 模拟器实测：fresh 28 表 + 18→24 升级路径）
3. ✅ 真实流式请求可返回可取消（Ktor Darwin + mock SSE：首 token 114ms、取消 1ms、3 类超时）
4. ✅ Android 备份可恢复到 iOS（金样 zip → iOS DB 等价恢复，JVM+iOS 双路径 24 测试）
5. 🔄 2,000 条消息滚动达标（T12 进行中）
6. ✅ 最低 iOS 版本（18+ 基线 / 26+ 增强玻璃）与玻璃验收标准冻结（玻璃设计规范 v1）
