# T61 实现 handoff：干员日记自动生成 + 干员列表单独控制（iOS）

> 代理：impl-diary-auto ｜ 任务：task-61 ｜ 分支：`ios-port`（HEAD 74ce7d1，工作区含 T59/T60 并行改动）｜ Kotlin 2.3.21
> 状态：**实现完成**；shared framework 已链接（`Shared.h` 含 T61 导出）；`xcodebuild` **BUILD SUCCEEDED**；
> iPhone 17 专用模拟器 **T61-sim（`22A12D65-74B2-4451-A933-D7B4828F1D45`）** 端到端实跑：
> 桥层自检 **RESULT PASS（19/19）**、UI 链路自检 **UI RESULT PASS**（2026-11-23）。
> git：**未提交**（lead 统一提交）。
> 规格卡：`ios-port/specs/diary-auto-spec.md`（本卡产出，含 oracle 结论与设计）。

## 1. 用户问题 1 结论：原版（Android）**不会**自动安排生成日记

- 唯一入口 `MainViewModel.generateDiary(operatorId, auto=false)`（`MainViewModel.kt:3305`），
  唯一调用点是日记页按钮「查看日记 / 再生成一篇」（`DiaryScreen.kt:312`、`:344`），**均传 auto=false**；
- `auto=true` 分支（生成**昨天**日记、昨日已存在则跳过）在 `MainViewModel.kt:3362-3363`，
  其便捷封装 `generateDiarySync`（`:3340`）**全仓无调用者**；
- 设置 `autoDiaryEnabled`（`SettingsRepository.kt:526`，默认 **false**）与
  `dailyDiaryOperatorLimit`（`:530`，默认 **3**）**全仓没有任何读取点**（仅声明/迁移/备份白名单）；
- `DailyContentScheduler`（`automation/DailyContentScheduler.kt:26-29`）只调度 `plan/moment/private/private_follow_up`，
  **无日记任务**。
- 字数设置：`diary_min_chars` 默认 50、`diary_max_chars` 默认 300（`StorySettingsScreen.kt:45-46`），仅手动生成时使用。

→ 原版日记全部是用户手动补写；没有「每日自动生成」「每日生成人数限制」的实际实现。
iOS 因此按 oracle 默认关闭：`diary_auto_enabled` / `diary_auto_<operatorId>` 默认 **false**，由用户显式开启。

## 2. 交付清单

| 侧 | 类型 | 文件 | 说明 |
|---|---|---|---|
| shared iosMain | 新增 | `iosbridge/DiaryAutoGlue.kt`（~940 行） | `@ObjCName(swiftName="DiaryAutoGlue")`：`status` / `setMasterEnabled` / `setOperatorAuto` / `setAllOperatorAuto` / `generateForOperator(operatorId, force)` / `tick(limitOverride)` / `debugRunSelfTest` + 4 个 DTO；提示词组装复用 `PromptTemplates("diary")`+`PromptRender`+`PromptPlaceholderRegistry`，规范化复用 `PlainCommentContentNormalizer`，模型走 `RealProviderStreamingClient`（未配置供应商→本地确定性演示内容），自检走确定性 mock（仍走真实链路） |
| shared iosMain | 修改（追加） | `iosbridge/DiaryBridge.kt` | +`listByOperator(operatorId, unreadOnly, onResult)`：按干员筛选日记列表（排序/未读语义同 list） |
| shared iosMain | 修改（追加） | `iosbridge/IosAppGraph.kt` | +`diaryAutoGlue` 字段、+`diaryAuto()` getter、+`--diary-auto-selftest` 启动钩子（`maybeRunDiaryAutoSelfTest`）。与 T60 在同一文件的另一区域各自追加，已核对互不影响 |
| iosApp Swift | 新增 | `Bridge/DiaryAutoClient.swift` | Swift 包装：4 个 Item struct + `loadDiaryAutoStatus/setDiaryAutoMaster/setDiaryOperatorAuto/setAllDiaryAuto/generateDiaryNow/runDiaryAutoTick`（async/await，Kotlin 回调转 continuation；KotlinBoolean→boolValue、KotlinInt→intValue） |
| iosApp Swift | 修改（追加） | `Bridge/AppGraphClient.swift` | +`diaryAutoBridge` getter（1 行） |
| iosApp Swift | 新增 | `Features/Diary/DiaryAutoManageView.swift`（~500 行） | 「日记生成管理」页：总开关 / 全部开启关闭 / 补齐今日 / 147 干员行（头像+名字+最近日记时间/字数+今日已生成徽标+单干员 Toggle+立即生成）/ 生成中与失败状态 / 搜索 / 刷新 / 空态/错误态 + `--diary-auto-selftest` UI 自动化 |
| iosApp Swift | 新增 | `Features/Diary/DiaryAutoAutomation.swift` | 自检日志/结论（Documents/diary-auto-selftest.log + NSLog） |
| iosApp Swift | 修改（最小） | `Features/Diary/DiaryView.swift` | 工具栏 +1 入口（slider 图标→管理页）；`--diary-auto-selftest` 自动打开管理页；进入日记页时一次 `tick(0)`（总开关关时桥内立即返回）；从管理页返回刷新列表 |
| 文档 | 新增 | `ios-port/specs/diary-auto-spec.md` | 规格卡（oracle 结论 + 设计 + 限制） |
| 证据 | 新增 | `ios-port/handoffs/evidence/t61-*` | 见 §4 |

未改：`A7Glue.kt`（T56/T60 写域）、Chat/Moments/Settings 页、`FeaturesView.swift`、commonMain（`.sq`/DAO/Repository 零改动）、`RouteView.swift/AppRouter`（管理页从日记页 push，不需要新路由）。

## 3. 关键设计

1. **设置键**：`diary_auto_enabled`（总开关，默认 false）、`diary_auto_<operatorId>`（默认 false）、
   复用 `daily_diary_operator_limit`（默认 3，每轮 tick 上限）、`diary_min_chars`/`diary_max_chars`。
   生效 = 总开关 && 单干员开关。
2. **生成管线**：同日幂等前置（`operatorId + 北京日期`，force 时同日写新 version）→ 组装昨天私聊事实/
   群聊事实+滚动摘要/长期印象/关系事件（relationships+公开锚点近似）/今日状态 → `buildCachePromptLayers`
   + `buildNaturalRuntimeContext` + DATA_BOUNDARY（与 oracle `buildContentGenerationMessages:535` 同构）→
   真实模型（90s 超时）或演示/mock 内容 → `PlainCommentContentNormalizer` 规范化（第三人称/空内容重试一次，
   超 max 保守截断）→ `insertDiary`（`queueMemory = memoryV2Enabled && diaryMemoryGenerationEnabled`）→
   T51 未读语义自动生效。
3. **tick**：总开关关/上限 0 直接返回（无 AI 调用）；仅处理开启且今天缺失的干员；每次最多
   `daily_diary_operator_limit`（可注入覆盖，封顶 20）位，避免一次 147 人全生成。
4. **触发点（iOS 无 WorkManager）**：进入「干员日记」页 / 「日记生成管理」页时各一次 tick（前台补算）；
   管理页「补齐今日日记」按钮可手动补。**不做后台定时**（记录为限制）。
5. **UI 刷新**：生成完成后自动 reload status；从管理页返回日记列表刷新（新日记立即出现在 T51 列表/未读）。

## 4. 验证证据（T61-sim，显式 udid `22A12D65-74B2-4451-A933-D7B4828F1D45`）

### 4.1 构建

| 步骤 | 结果 | 证据 |
|---|---|---|
| shared framework（`Shared.h` 含 `DiaryAutoGlue`/4 DTO/`listByOperator`；工作区 shared 链接产物被 xcodebuild 与真机运行实际使用） | 可编译/链接，真实 App 运行验证 | `evidence/t61-framework-header-check.txt`（header 导出清单 + mtime）；`evidence/t61-framework-link.log`（link 任务成功/UP-TO-DATE） |
| `xcodebuild build -project iosApp/RhodesLink.xcodeproj -scheme RhodesLink -destination "generic/platform=iOS Simulator" -derivedDataPath iosApp/DerivedData-t61 CODE_SIGNING_ALLOWED=NO` | **BUILD SUCCEEDED**（含 T59/T60 工作区改动） | `evidence/t61-xcodebuild.log` |

> 期间 T59/T60 曾被 `DiaryAutoClient.swift` 的 `KotlinBoolean` 回调转换阻塞；已修
> （`effective.boolValue` / `count.intValue`）并通知两路并行代理，随后两路均恢复构建。最终 Swift 修复后再次
> `xcodebuild` 两次 BUILD SUCCEEDED。

### 4.2 桥层自检（`--diary-auto-selftest`，mock 内容走真实提示词/规范化/DB/幂等/开关链路）

```bash
DEV=22A12D65-74B2-4451-A933-D7B4828F1D45     # T61-sim（专用，避免并行 launch 覆盖启动参数）
xcrun simctl install $DEV iosApp/DerivedData-t61/Build/Products/Debug-iphonesimulator/RhodesLink.app
xcrun simctl launch $DEV com.rhodeslink.app --open-url rhodes://features/diary --diary-auto-selftest
```

`Application Support/t61-diary-auto-selftest.log`：**RESULT PASS**（19 项，摘要）：
fixture `operators=mon3tr,misery disabled=scout range=50~300 limit=3 mockProvider=true` →
`generate.created id=1` → `generate.lengthInRange len=194` → `db.rowExists/db.content/db.dateIsToday` →
`idempotent.sameId`（同 id，created=false）→ `idempotent.versionStable` → `tick.generatedSecond generated=1` →
`tick.idempotent generated=0 skipped=2` → `switch.off.tickNotGenerated`（关闭开关后 tick 不生成）→
`master.off.tickZero`（总开关关）→ `status.total=147` / `enabled=2` / `today=2` / `lastDiaryAt` / `limit=3`。

### 4.3 UI 链路自检（`Documents/diary-auto-selftest.log`）

**UI RESULT PASS**：列表 147 位、已开启 2 位、今日已生成 2 位、总开关开、列表行含「今日已生成」→
「全部开启」链路 147 位 → 恢复初始 2 位 + 总开关 → 第一位「立即生成」`id=3 len=183 version=2` →
`ui.master.restored` / `ui.enabled.restored=2`。

### 4.4 主机侧数据核对（`evidence/t61-db-settings-check.txt`）

```
id|operatorId|operatorName|date|version|createdAt|length|content前缀
1|mon3tr|M3|2026-11-23|1|1795438026106|194|T61自检 今天照常完成了训练…
2|misery|Misery|2026-11-23|1|1795438026186|194|T61自检 今天照常完成了训练…
3|mon3tr|M3|2026-11-23|2|1795438050685|183|晚上写工作日志的时候发现今天记下的东西比昨天多…
count: total=3, operators=2, dates=1   ← 同日幂等：多次 tick 未产生重复日期行，force 生成才 +version
NSUserDefaults: diary_auto_enabled=true; diary_auto_mon3tr=true; diary_auto_misery=true; 其余 false; daily_diary_operator_limit=3
```

### 4.5 截图（`xcrun simctl io ... screenshot`）

| 文件 | 内容 |
|---|---|
| `evidence/t61-auto-list.png` | 管理页初始态：总开关开、已开启 2/147、今日已生成 2 位、M3/Misery「今日已生成」+ 最近日记字数、Scout 关闭 |
| `evidence/t61-auto-allon.png` | 「全部开启」后：已开启 **147/147**，全部 Toggle 打开 |
| `evidence/t61-auto-after-generate.png` | 自检 **PASS**、生成后最近日记更新（M3 183 字 v2）、状态恢复 2 位开启 |
| `evidence/t61-diary-list-after.png` | 返回 T51 干员日记列表：共 3 篇 / 未读干员 2 位（自动生成的日记进入列表与未读语义） |

另：`evidence/t61-nslog.txt`（`[RhodesLink][T61SELFTEST]` NSLog 全量）。

## 5. 已知限制 / 与 oracle 差异

1. **无后台定时**：iOS 不移植 WorkManager；tick 仅在进入日记页/管理页时前台补算。未来可接 BGTaskScheduler。
2. **生成「今天」而非「昨天」**：按任务要求当日 1 篇；oracle 未接线的 auto 分支原本生成昨天。
3. **关系事件为近似**：shared 未移植 Android `relation_events` 表 → 用 relationships + 对方公开记忆锚点
   （空时回退关系备注）近似 oracle `getRelationEvents`。
4. **字数**：oracle 规范化器不校验 min/max；iOS 对超 max 保守截断，不足 min 时仅返回 message 提示（不填充）。
5. **无单条删除**：`Diaries.sq` 无按 id 删除；自检/截图夹具日记（正文含「T61自检」）可用
   「设置→数据管理→清理日记」或完整备份恢复清除。
6. **Token 统计**：日记生成未计入排行榜 token 分类（oracle 有 `trackTokens("diary")`；iOS RankingBridge
   分类未含 diary，后续可统一）。
7. **自检会改设置**：`--diary-auto-selftest` 结束时保留 master=true + mon3tr/misery 开启（供截图），
   仅测试模拟器路径；对普通用户启动路径零影响（无参数时不注册任何行为）。
8. **并行写域**：`IosAppGraph.kt` 与 T60 同文件不同区域追加（已实际编译通过）；`AppGraphClient.swift`
   +1 getter、`DiaryView.swift` 仅工具栏/任务/返回刷新小改。
