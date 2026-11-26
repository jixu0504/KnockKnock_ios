# T51 实现 handoff：干员日记（列表 / 未读 / 阅读）

> 代理：impl-diary ｜ 任务：task-51（加急第 2 批）｜ 状态：**实现完成，xcodebuild 编译通过，iPhone 17 模拟器（专用 T51-sim）端到端实跑 PASS（2026-11-23）**
> 规格卡：`ios-port/specs/diary-spec.md`（本卡产出）｜ Android oracle：`ui/diary/DiaryScreen.kt:74`、`MainViewModel.kt:3312-3338`、`SettingsRepository.kt:1079-1083`、`golden/db/schema/Diaries.sq`
> git：**未提交**（lead 统一提交）。写域：`RhodesLink/shared/src/iosMain/.../iosbridge/{DiaryBridge.kt 新增, IosAppGraph.kt 最小 patch}`、`RhodesLink/iosApp/Features/Diary/**`（新增）、`RhodesLink/iosApp/Features/Common/RouteView.swift`（仅 `.diary` 分支）、`RhodesLink/iosApp/Bridge/{DiaryClient.swift 新增, AppGraphClient.swift 追加 1 个 getter}`、`ios-port/{specs,handoffs}`。
> 未改：Settings / Roles / Chat / FeaturesView / AppRouter / `commonMain`（Diaries.sq / DiaryRepository 零改动）。

## 1. 交付摘要

| 能力 | 实现 | 数据源 |
|---|---|---|
| 日记列表 | 跨干员平铺、createdAt 倒序；角色头像（`RoleAvatar` 内置头像/首字占位）、标题（干员名 · 日期）、摘要（前 96 字）、时间、未读红点/徽标 | 真实 `diaries` 表 + `operators.avatarUri` |
| 未读筛选 | 全部 / 未读 分段筛选（桥 `list(unreadOnly=true)` 服务端过滤）+ 未读干员数徽标 | `settings.diary_read_at_<op>` |
| 详情阅读 | 全文（可选中复制）、打开即标记已读、已读徽标、返回列表刷新未读点 | `diaries.content` + `SettingsRepository.putDiaryReadAt` |
| 状态 | 空态（全部/未读两种文案）、加载态、错误态（原因 + 重试）、下拉刷新、工具栏刷新 | — |
| 路由 | `RouteView.swift` 的 `.diary` 分支 → `DiaryView()`（替换 PlaceholderView，仅该分支） | 入口/深链不变（功能 Tab、`rhodes://features/diary`） |
| 自检 | `--diary-selftest`：桥层端到端（夹具/列表/未读/详情/已读/DB/settings）+ Swift UI 链路（列表未读 → 详情阅读 → 未读清零）→ 双日志 + 截图 | 真实 DB / settings，无 mock |

## 2. 文件清单

| 侧 | 类型 | 文件 | 说明 |
|---|---|---|---|
| shared iosMain | 新增 | `iosbridge/DiaryBridge.kt`（~470 行） | `@ObjCName(swiftName="DiaryBridge")`：list/detail/markRead/unreadCount + 3 DTO + 自检夹具/自检；复用 `ChatRepository`（getAllDiariesForBackup/getAllDiaryEntries/getDiaryCount/insertDiary）+ `SettingsRepository.getDiaryReadAt/putDiaryReadAt` |
| shared iosMain | 修改（追加） | `iosbridge/IosAppGraph.kt` | +`diaryBridge` 字段、+`diary()` getter（最小 patch，未动其它桥） |
| iosApp Swift | 新增 | `Bridge/DiaryClient.swift` | Swift 包装：`DiaryEntryItem/DiaryDetailItem/DiarySnapshotItem` + `loadDiaryList/loadDiaryDetail/markDiaryRead/diaryUnreadCount/runDiarySelfTest/ensureDiaryFixture`（async/await，回调转 continuation） |
| iosApp Swift | 修改（追加） | `Bridge/AppGraphClient.swift` | +`diaryBridge: DiaryBridge { graph.diary() }`（1 个 getter） |
| iosApp Swift | 新增 | `Features/Diary/DiaryView.swift` | 列表页：头部卡（共 N 篇/未读 M 位 + 全部/未读筛选）、行、空/加载/错误态、下拉刷新、`--diary-selftest` UI 自动化 |
| iosApp Swift | 新增 | `Features/Diary/DiaryDetailView.swift` | 详情页：全文 + 打开即 markRead + 已读徽标 + 错误重试 |
| iosApp Swift | 新增 | `Features/Diary/DiaryAutomation.swift` | `--diary-selftest` 日志/结论（Documents/diary-selftest.log + NSLog） |
| iosApp Swift | 修改（替换） | `Features/Common/RouteView.swift` | `.diary` 分支 → `DiaryView()`（T50 同期改 `.moments`，两分支互不干扰） |
| 文档 | 新增 | `ios-port/specs/diary-spec.md` | 规格卡 |
| 证据 | 新增 | `ios-port/handoffs/evidence/t51-*` | 见 §4 |

## 3. 关键设计

1. **未读语义按 oracle**：干员维度，`MAX(diaries.createdAt) > settings.getDiaryReadAt(opId)`（`MainViewModel.getUnreadDiaryCount` 同源）；`markRead` = `putDiaryReadAt(opId, 该干员最新 createdAt)`（`markDiaryRead`）；键 `diary_read_at_<operatorId>`（NSUserDefaults Long）。打开详情即标记，返回列表未读点消失。
2. **列表平铺 vs Android 干员聚合**：`diaries` 表无「标题/已读」列，Android 先聚合干员再按「未读 > 有日记 > 最新时间 > 名字」排序；iOS 按任务卡要求平铺「按时间倒序」，未读筛选 = 所属干员未读（干员维度语义不变）。
3. **标题/摘要为桥派生**：标题 =「干员名 · 日期」，摘要 = 正文压平空白前 96 字（不落库，无 schema 改动）。
4. **最小 shared 改动**：未新增/修改任何 `.sq` 查询；`DiaryBridge` 在内存中按 `operatorId` 分组算最新 createdAt，避免触碰 `commonMain`（与 T48/T49/T50 并行写域隔离）。
5. **自检两段式**：桥层 `debugRunSelfTest` 覆盖真实 DB/settings 断言并把夹具重新置未读；Swift `DiaryAutomation` 随后驱动真实 UI（列表未读 → 自动打开详情 → 详情 markRead → 回列表未读清零），写 `Documents/diary-selftest.log`（含桥报告）。
6. **夹具幂等**：仅当 DB 无日记时写入 1 条「T51自检」日记（北京时间今天，amiya；已存在则复用），不污染有数据环境；无单条删除查询，夹具保留说明见规格 §5。

## 4. 验证证据（iPhone 17 专用模拟器 T51-sim `06EBBE8E-C6A5-4AD4-BE70-A19922028C4F`，显式 udid）

### 4.1 构建

| 步骤 | 结果 | 证据 |
|---|---|---|
| `:shared:linkDebugFrameworkIosSimulatorArm64`（`--no-daemon`，排队纪律） | **BUILD SUCCESSFUL**，`DiaryBridge`/`DiaryEntryDto`/`DiaryDetailDto` 已进 `Shared.h` | `evidence/t51-framework-link.log`；`grep DiaryBridge Shared.framework/Headers/Shared.h` |
| `xcodebuild build`（generic iOS Simulator，DerivedData-t51，CODE_SIGNING_ALLOWED=NO） | **BUILD SUCCEEDED**，0 error | `evidence/t51-xcodebuild.log` |

### 4.2 端到端自检（`--diary-selftest`，真实 SQLite + settings）

```bash
DEV=06EBBE8E-C6A5-4AD4-BE70-A19922028C4F   # T51-sim（专用，避免与 T45/T48/T49 并行 launch 覆盖参数）
xcrun simctl install $DEV DerivedData-t51/Build/Products/Debug-iphonesimulator/RhodesLink.app
xcrun simctl launch $DEV com.rhodeslink.app --open-url rhodes://features/diary --diary-selftest
```

- 桥层日志 `Application Support/t51-diary-selftest.log`：**RESULT PASS**（15 项 PASS（含 `db.count expected>=1`）：fixture 创建、列表非空/倒序/字段完整、未读标记、unreadOnly、详情全文/日期一致、`markRead.latest`、未读清零、DB 行数/按 id 读取一致）。
- UI 自动化日志 `Documents/diary-selftest.log`：**UI RESULT PASS**（列表 1 条/未读 1 位 → 自动打开 id=1 → `detail.markRead remaining=0` → 回列表 `stillUnread=false`、详情全文 len=97）。
- 主机侧核对：
  - `sqlite3 .../rhodes.db "select id,operatorId,operatorName,date,version,createdAt,length(content) from diaries"` → `1|amiya|阿米娅|2026-11-23|1|1795410846221|97`（`evidence/t51-db-settings-check.txt`）；
  - `Library/Preferences/com.rhodeslink.app.plist` → `diary_read_at_amiya = 1795410846221`（= 该干员最新 createdAt，与自检日志 `settings.diary_read_at_amiya` 一致）。
- 截图（`xcrun simctl io ... screenshot`）：
  - `evidence/t51-diary-list.png`：列表未读态（红点 + 「1 未读」+「干员们的内心独白 共 1 篇 · 未读干员 1 位」+ T51 自检卡）；
  - `evidence/t51-diary-detail.png`：详情阅读态（全文 + 第 1 篇 + 已读徽标）；
  - 日志拷贝：`evidence/t51-diary-selftest-bridge.log`（桥层 RESULT PASS）、`evidence/t51-diary-selftest-ui.log`（UI RESULT PASS）、`evidence/t51-db-settings-check.txt`（主机侧 DB/NSUserDefaults 核对）。

> 并行环境注意：本卡首次在共享 iPhone 17（A63CD…）上验证时被其它批次（T45/T48/T49）同时 launch 覆盖启动参数；改用专用 `T51-sim` 后一次 PASS。另：iOS 27.2 模拟器 `simctl launch` 在 App 已运行时不会替换启动参数，复跑前先 `terminate`（或 `uninstall`）。

## 5. 已知限制

1. **单条删除未实现**（任务卡「删除可选」）：`Diaries.sq` 只有按干员/过期/全量删除，无按 id 删除；写域不含 `.sq`，故详情页无删除入口。后续需要时补 `deleteDiaryById` + `DiaryRepository.deleteDiary`。
2. 标题/摘要为派生展示；AI 重新生成（`generateDiary`）不在本卡（属 A7 生成规则波次）。
3. 未读为干员维度：列表把未读干员的全部历史日记标未读，打开任一篇即整位干员已读（oracle 语义）。
4. 功能 Tab 的日记角标仍未接线（`FeaturesView.swift` 属 T48/T50 写域）；桥 `unreadCount()` 已就绪，后续 1 行接线即可。
5. 自检夹具日记会保留在 DB（仅 DB 为空时写入 1 条，含「T51自检」标记）；可用数据管理清理日记/完整恢复清除。
6. 并行写域：`RouteView.swift` 为追加最小 patch（只替换 `.diary` 分支），T50 的 `.moments` 分支未受影响；`AppGraphClient.swift` 追加 1 getter，未见冲突。
