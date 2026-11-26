# T38 实现 handoff：iOS 设置「数据与备份」UI 接线（可用软件里程碑 2）

> 代理：impl-backup-ui ｜ 任务：task-38 ｜ 状态：**实现完成，xcodebuild 编译通过，iPhone 17 模拟器两轮端到端实跑通过（2026-11-23）**
> 规格卡：`ios-port/specs/backup-ui-spec.md`（本卡产出）｜ 上游：T10 `backup-restore-minimal-spec.md`、T30 `chat-import-impl-spec.md`、T31 `backup-full-restore-spec.md`、T29 `ios-bridge-spec.md`
> git：**未提交**（lead 统一提交）。写域：`RhodesLink/iosApp`、`ios-port/{specs,handoffs}`；另因 UI 需要新增 1 个共享域层 writer + 1 个 iosMain 桥（见 §2，已与 T37 协调）。

## 1. 交付摘要

设置页新增二级子页「数据与备份」（`rhodes://settings/backup` 深链可直达），四块能力全部接真实 shared 域层、无 mock：

| 能力 | 管线 | UI 结果展示 |
|---|---|---|
| 完整备份导出 | `BackupBridge.exportToFile`（T29）+ 导出前 `PRAGMA wal_checkpoint(FULL)`（Swift SQLite3） | 文件大小 / SHA-256 / SQLite 头 / `integrity_check` / 29 表计数；`fileExporter` 可存到「文件」App |
| 完整备份导入 | `BackupBridge.restoreFromFile` 两阶段（`.restore-pending`，冷启动换入） | **恢复前确认弹窗**（覆盖风险 + 文件自检明细）、进度/状态、待恢复卡、「撤销待恢复」、**恢复后自检报告**（换入后自动比对期望/实际表计数 + `integrity_check`） |
| 角色卡导出/导入 | T30 `OperatorCardExporter/Writer/Reader/Importer`（NEW 模式） | 角色选择器、导出（大小/SHA/关系数/是否含头像）、导入预览+确认+结果（新 id/关系导入/跳过数） |
| 聊天记录导出/导入 | T30 `ChatMergeRestorer` + **本卡新增 `ChatExportWriter`**（T30 只交 Reader） | 会话选择器、导出（消息/媒体/存档数 + SHA）、导入预览+确认+进度+合并结果（新增/去重/媒体/存档 + 告警） |

错误路径统一红色提示条 + 明确中文原因，App 不崩：非 ZIP/截断 ZIP/跨格式/`formatVersion`/scope 不符/文件不存在/写盘失败/导入失败回滚均有实测（见 §4 错误矩阵 18 项 PASS）。

## 2. 写域与文件清单

| 侧 | 类型 | 文件 | 说明 |
|---|---|---|---|
| shared commonMain | 新增 | `backup/chatimport/ChatExportWriter.kt`（308 行） | RhodesLinkChatExport v1 writer：单会话快照 → 冻结容器（manifest/conversation/media/内容派生 mediaId），复用 `BackupZipWriter`/`BackupCrypto`；T30 只交 Reader，本卡补齐写侧 |
| shared iosMain | 新增 | `iosbridge/DataBackupBridge.kt`（~560 行） | 角色卡导出/检视/导入 + 聊天导出/检视/导入 + `cancelPendingRestore()`；8 个简单 DTO；异常全部转中文 error |
| shared iosMain | 修改（追加） | `iosbridge/IosAppGraph.kt` | +1 字段 `dataBackupBridge`、+1 getter `dataBackup()`（T37 同期追加 `providers()`，两卡 edit 共存） |
| shared iosMain | 修改（修 bug） | `iosbridge/BackupBridge.kt` | **T29 遗留崩溃修复**：`NSLog("... %@", Kotlin String)` 在 K/N 2.3 不自动桥接 NSString，两阶段恢复换入时 SIGSEGV → 改 `NSString.create(string = ...)`（2 处 + import）。实测冷启动换入已从崩溃变为 `pending restore applied=true` |
| iosApp Swift | 新增 | `Features/Settings/BackupSettingsView.swift`（~680 行） | 子页 UI：4 张能力卡 + 恢复后自检卡 + 错误卡 + 自检矩阵卡；`fileImporter`/`fileExporter` |
| iosApp Swift | 新增 | `Features/Settings/BackupAutomation.swift` | `--backup-selftest` 两轮矩阵（角色卡/聊天/完整备份/两阶段恢复/撤销/错误路径）；`BackupAutomationState` 供页面展示 |
| iosApp Swift | 新增 | `Bridge/DataBackupClient.swift` | Swift 薄包装：`exportFullBackup/stageFullRestore/cancelPendingRestore/evaluateRestoreSelfCheck` + 角色卡/聊天 async 方法 + Swift 模型 |
| iosApp Swift | 新增 | `Bridge/BackupSelfCheck.swift` | SQLite3 C API 校验（`immutable=1` URI 打开静态备份；现网库普通打开）、`PRAGMA wal_checkpoint`、恢复后自检报告（文件持久化）、`backup-selftest.log` |
| iosApp Swift | 修改（追加） | `App/AppRouter.swift` | `Route.settingsBackup` + logDescription + 深链 `rhodes://settings/backup` |
| iosApp Swift | 修改（追加） | `Features/Common/RouteView.swift` | `.settingsBackup: BackupSettingsView()` |
| iosApp Swift | 修改（替换占位） | `Features/Settings/SettingsView.swift` | 「备份与恢复」占位行 → 「数据与备份」行；+`--settings-route-rows` 截图滚动钩子（ScrollViewReader，仅该启动参数生效） |
| iosApp Swift | 修改（追加） | `Bridge/AppGraphClient.swift` | `backupBridge`/`dataBackupBridge` 访问器（T37 同文件 provider 扩展共存） |
| 文档 | 新增 | `ios-port/specs/backup-ui-spec.md` | 本卡规格卡 |
| 证据 | 新增 | `ios-port/handoffs/evidence/t38-*` | 见 §4 |

> 并行协作：T37（impl-provider）同期改 `AppRouter/RouteView/SettingsView/AppGraphClient/RhodesLinkApp` 与 iosMain 多文件。约定「T37 先改完 → 本卡用 edit 追加/替换」，最终工作树两卡改动共存，xcodebuild 通过。

## 3. 关键设计

1. **完整备份 = SQLite 快照（T29 BackupBridge 语义）**：导出前 Swift 用 SQLite3 对现网库做 `PRAGMA wal_checkpoint(FULL)`（补强 T29 handoff 已知限制 4：运行中复制会漏 WAL 帧），再 `BackupBridge.exportToFile` 复制 + SQLite 头校验；导出后做 `integrity_check` + 29 表计数展示。
2. **导入 = 两阶段恢复（task 卡指定）**：选文件 → SQLite 头/integrity 预检 → **确认弹窗** → 暂存 `.restore-pending` → 提示重启生效，可撤销；冷启动 `IosAppGraph.init → BackupBridge.applyPendingRestoreIfAny` 换入。
3. **恢复后自检**：暂存时把 pending 文件 `integrity_check` + 8 张关键表计数 + 现网计数写入 `Documents/backup-restore-marker.json`（**文件持久化，不用 UserDefaults**——`simctl terminate` 是 SIGKILL，UserDefaults 异步写会丢标记，实测踩坑过一次）；下次冷启动换入后，页面检测 pending 已消失 → 比对「换入前期望 vs 现网实际」→ 写 `backup-restore-report.json` 并展示（passed/mismatch/integrity）。自检不翻转已完成的换入（T10/T31 口径）。
4. **静态备份文件用 `immutable=1` 打开**：导出的 `.db`/pending 是 WAL 库的单文件副本（无 `-shm/-wal`），普通只读打开 `PRAGMA integrity_check` 会 `SQLITE_CANTOPEN: unable to open database file`（实测）；`file:…?immutable=1` 是 SQLite 官方备份校验方式。现网库用普通读写连接（可见 WAL 最新提交，只执行读语句）。
5. **聊天导出 writer 与 T30 格式逐字段对齐**：`format=rhodes-chat/formatVersion=1/scope=conversation/layout=single_payload`；`mediaId="image/<sha256 全 64hex>"`、`archivePath="media/images/<sha24>.<ext>"`；manifest 最后写；导出后经 `ChatExportReader.validate` 自证可读（与 iOS 导入端同一校验器）。
6. **媒体解析（导出）**：operator/session avatarUri、消息 content 内递归扫描 `imageUri`；内置头像数字串 1..147 读 App Bundle `avatar_<N>.png`；运行时媒体 ID 走 `MediaRepository.resolve`；不可解析引用保留原值（镜像 Android `ChatExportService` skippedUris 语义）。
7. **角色卡导入固定 NEW 模式**（新 UUID + 名称去重「（导入）」），不覆盖现有角色；桥已支持三模式，UI 未开放（规格 §6 已知限制 4）。
8. **自动化钩子**：`--backup-selftest` + `--open-url rhodes://settings/backup`；第一轮矩阵，第二轮冷启动换入+自检；日志 `Documents/backup-selftest.log`，产物 `Documents/BackupExports/`。两条启动参数对用户路径零影响。
9. **文件持久化用 `.atomic` 写**、日志内联写文件；UI 回调全 MainActor。

## 4. 验证证据（2026-11-23，iPhone 17 模拟器 A63CD17A… / iOS 27.2）

### 4.1 构建

| 项 | 结果 | 证据 |
|---|---|---|
| `:shared:linkDebugFrameworkIosSimulatorArm64`（含本卡 2 个新 Kotlin 文件 + T37 改动） | **BUILD SUCCESSFUL** | `evidence/t38-framework-link.log` |
| `xcodebuild build`（generic iOS Simulator，工作区内 DerivedData，CODE_SIGNING_ALLOWED=NO） | **BUILD SUCCEEDED**，0 error | `evidence/t38-build.log` |

### 4.2 两轮端到端（`xcrun simctl`，显式 udid，未用 dsh-ios 工具）

```bash
DEV=A63CD17A-E1F9-4C5C-9B8C-92DEB43862FA
xcrun simctl install $DEV iosApp/DerivedData/Build/Products/Debug-iphonesimulator/RhodesLink.app
# 第一轮：矩阵 + 暂存恢复
xcrun simctl launch $DEV com.rhodeslink.app --open-url "rhodes://settings/backup" --backup-selftest
# 冷启动第二轮：换入 + 恢复后自检
xcrun simctl terminate $DEV com.rhodeslink.app
xcrun simctl launch $DEV com.rhodeslink.app --open-url "rhodes://settings/backup" --backup-selftest
# 截图：设置页入口行
xcrun simctl launch $DEV com.rhodeslink.app --tab settings --settings-route-rows
```

| # | 验收项 | 结果 | 证据 |
|---|---|---|---|
| 1 | 设置页可进入「数据与备份」子页 | ✅ OCR：设置页出现「模型供应商 / **数据与备份** / 提示词设置」行；深链直达子页 | `t38-settings-row.png`、`t38-ocr.txt` |
| 2 | 完整备份导出 + 格式校验 | ✅ 430KB `.db`，头 `SQLite format 3\0`、`integrity_check=ok`、29 表、operators=158/sessions=4/messages=128/memories=1/relationships=3；SHA-256 与日志一致 | `t38-exports/rhodes-backup-*.db`、`t38-verify.txt` §1 |
| 3 | 两阶段恢复暂存 + 撤销 + 再次暂存 | ✅ 暂存 pending 438KB（自检 ok）→ 撤销后 pending 删除、现网未动 → 再暂存 | `t38-selftest-round1.log` |
| 4 | 冷启动换入 | ✅ `[IosBridge][Backup] pending restore applied=true`；pending 消失；DB 自检 ok | `t38-selftest-round2.log`、`t38-verify.txt` §7 |
| 5 | **恢复后自检结果展示** | ✅ passed=true、mismatches=[]、integrity=ok、期望=实际（8 张表逐项相等）；页面显示「上次恢复：自检通过」 | `t38-backup-round2.png`、`t38-restore-report.json`、`t38-selftest-round2.log` |
| 6 | 角色卡导出/导入 | ✅ zip 三件（`data/operator.json` + `media/avatar.jpg` + `manifest.json`，逐条目 size+SHA-256 PASS）；导入后新建角色、关系计数展示 | `t38-exports/operator-*.rhodes-operator`、`t38-verify.txt` §3、`t38-selftest-round1.log` |
| 7 | 聊天记录导出/导入 | ✅ zip 三件（`conversation.json` + `media/images/<sha24>.bin` + `manifest.json`，manifest/媒体/逐条目校验 PASS）；导入幂等（新增 0/去重 2/媒体 1） | `t38-exports/RhodesLinkChatExport-*.zip`、`t38-verify.txt` §2、`t38-selftest-round1.log` |
| 8 | 坏包/错误路径不崩 | ✅ 矩阵 **18 PASS / 0 FAIL**（见下表） | `t38-selftest-round1.log` |
| 9 | 截图证据 | ✅ 3 张 PNG + OCR 文本 | `t38-*.png`、`t38-ocr.txt` |

### 4.3 错误路径矩阵（第一轮自动化，18 项 PASS）

| 场景 | 实测提示 |
|---|---|
| 非 SQLite 当完整备份恢复 | 「备份文件不是合法 SQLite」 |
| 备份文件当聊天包检视 | 「聊天包无效：ZIP 结尾记录缺失」 |
| 聊天包当角色包检视 | 「角色包无效：不支持的角色包格式」 |
| 角色包当聊天包检视（**格式/版本不符**） | 「聊天包无效：不支持的聊天导出类型：rhodes-operator」 |
| 截断 ZIP 检视 | 「聊天包无效：ZIP 结尾记录缺失」 |
| 截断 ZIP 导入 | 「ZIP 结尾记录缺失」（success=false，DB 未写） |
| 角色包文件不存在 | 「角色包无效：角色卡文件不存在或不可读」 |
| 导出不存在会话 | 「聊天记录导出失败：找不到要导出的会话」 |
| 导出不存在角色 | 「角色卡导出失败：找不到要导出的角色」 |
| 完整备份暂存 JSON 过滤等 | 全部走 UI 红色提示条（错误卡文案「操作未完成（数据未受影响）」） |

## 5. 已知限制 / 遗留

1. **完整备份导入只接 `.db` 快照格式**（与 T29 BackupBridge/两条启动卡一致）；Android `.rbackup` zip 的在线恢复（T31 `BackupFullRestoreCoordinator`，全类目字段级重放）域层已就绪但本卡未接 UI —— 归 Phase 7「在线恢复接线」。
2. **恢复后自检 = 表计数 + integrity_check**，不是 T31 全类目字段级自检；在线恢复接线时替换为 `BackupFullSelfCheck` 结果。
3. 聊天导出媒体引用保留原值（不重写 content，镜像 Android `ChatExportService`）；导入端按 `originalUri` 重写为运行时媒体 ID。
4. 角色卡导入 UI 固定 NEW 模式（桥支持 PERSONA/FULL_REPLACE，未开放选择）。
5. `fileExporter` 存「文件」App 需用户交互；自动化证据以沙盒 `Documents/BackupExports/` 产物为准（同一字节源）。
6. **修复了 T29 遗留崩溃** `BackupBridge.applyPendingRestoreIfAny` 的 `NSLog %@`（K/N 2.3 NSString 桥接）；该路径在 T29 时代从未被执行过，本卡首次打通两阶段恢复时暴露。修复为 2 处 `NSString.create(string = ...)`，cold-start 实测通过。
7. 测试环境注意：本机同时 booted iPhone 17 与 iPhone 17e（T37），`simctl … booted` 会歧义选错设备；本卡所有命令最终使用显式 udid。另 `simctl install` 会重建数据容器，证据采集在 install 后重新解析容器路径。
8. `BackupLog` 每进程写整份内存日志（第二轮日志只含第二轮内容），故第一轮/第二轮日志分别作为 `t38-selftest-round1.log` / `t38-selftest-round2.log` 归档。

## 6. 复现命令

```bash
cd /Users/jackchen/Documents/deepseek-harness/default-workspace
source ios-port/toolchain/env.sh
cd RhodesLink
pgrep -fl gradle; pgrep -fl xcodebuild        # 并发纪律
./gradlew --no-daemon :shared:linkDebugFrameworkIosSimulatorArm64
cd iosApp
TMPDIR="$PWD/tmp" xcodebuild build -project RhodesLink.xcodeproj -scheme RhodesLink \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath "$PWD/DerivedData" CODE_SIGNING_ALLOWED=NO

DEV=A63CD17A-E1F9-4C5C-9B8C-92DEB43862FA   # iPhone 17（勿用 booted：当前有两台 booted）
xcrun simctl install $DEV DerivedData/Build/Products/Debug-iphonesimulator/RhodesLink.app
xcrun simctl launch $DEV com.rhodeslink.app --open-url "rhodes://settings/backup" --backup-selftest
# 等待 ~30s（第一轮矩阵）→ 截图；再 terminate + 同命令启动（第二轮冷启动换入+自检）
CONTAINER=$(xcrun simctl get_app_container $DEV com.rhodeslink.app data)
cat "$CONTAINER/Documents/backup-selftest.log"
cat "$CONTAINER/Documents/backup-restore-report.json"
ls -la "$CONTAINER/Documents/BackupExports"
```

## 7. 工作记录

- 2026-11-23 08:17 claim task-38；通读 `impl-ios-graph.md`、`impl-backup-full.md`、`backup-restore-minimal-spec.md`、`chat-import-impl-spec.md`、`backup-full-restore-spec.md`、`ios-bridge-spec.md` 与既有 iosApp/iosMain 桥。
- 08:18 与 T37（impl-provider）对齐写域：其先改 4 个 Swift 文件 + IosAppGraph/ChatEngineBridge/IosDtos，本卡随后用 edit 追加；本卡新增 iosMain/commonMain 文件不与其重叠。
- 08:20-08:26 新增 `ChatExportWriter.kt`、`DataBackupBridge.kt`；append IosAppGraph `dataBackup()`；quick compile 通过（其间 T37 ProviderStreamingClient 半成品报错，本人文件 0 error）。
- 08:27-08:35 新增 4 个 Swift 文件 + 4 处追加接线；xcodebuild 迭代 6 次（fileImporter 重载、guard fall-through、T37 tab 重构 stale `memoryPath/rolesPath`）至 BUILD SUCCEEDED。
- 08:36-08:41 两阶段恢复 E2E 首次打通，发现 T29 `NSLog` SIGSEGV（crash log 定位到 `BackupBridge.applyPendingRestoreIfAny`）→ 修复 → 重链 framework + 重建 app。
- 08:41-08:42 发现 `simctl install` 重建数据容器 + `booted` 双设备歧义，改为显式 udid + install 后重新解析容器。
- 08:42-08:46 修正 UserDefaults 标记丢失（SIGKILL）→ 改文件持久化；修正 View `.task` 顺序（自动化先于页面自检消费标记）；最终两轮干净实跑：round1 矩阵 18 PASS / 0 FAIL、round2 换入 + 恢复后自检 passed。
- 08:47-08:48 设置页入口截图（新增 `--settings-route-rows` 滚动钩子）、OCR、容器内 SQLite/ZIP/SHA-256 校验、证据归档。
- 收尾：`/tmp` 无残留构建；git 未提交（lead 统一提交）。
