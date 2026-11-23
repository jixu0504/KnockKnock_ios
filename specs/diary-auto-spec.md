# T61 规格：干员日记自动生成 + 干员列表单独控制（iOS 移植）

> 代理：impl-diary-auto ｜ 任务：task-61 ｜ 分支：`ios-port`（HEAD 74ce7d1）｜ Kotlin 2.3.21
> Android oracle：`app/.../viewmodel/MainViewModel.kt`、`automation/DailyContentScheduler.kt`、
> `ui/diary/DiaryScreen.kt`、`shared/.../settings/SettingsRepository.kt`、`golden/db/schema/Diaries.sq`
> iOS 侧：新增 `shared/src/iosMain/.../iosbridge/DiaryAutoGlue.kt`、`Bridge/DiaryAutoClient.swift`、
> `Features/Diary/DiaryAutoManageView.swift`、`Features/Diary/DiaryAutoAutomation.swift`；
> 最小改动 `DiaryBridge.kt`（+`listByOperator`）、`IosAppGraph.kt`（接线 + 自检钩子）、
> `AppGraphClient.swift`（+1 getter）、`DiaryView.swift`（+1 工具栏入口 / 自动打开 / 返回刷新）。
> 纪律：`git` 不提交；不改 `A7Glue.kt`（T56/T60 写域）、不改 Chat/Moments/Settings 页。

## 1. 用户问题 1：原版（Android）是自动安排生成日记的吗？

**结论：不是。Android 原版没有任何自动生成日记的调度或触发；日记只在用户打开日记页并点击
「查看日记 / 再生成一篇」时按需生成。**

| 证据 | file:line | 说明 |
|---|---|---|
| `generateDiary(operatorId, auto = false, onResult)` | `MainViewModel.kt:3305` | 唯一日记生成入口；`auto` 默认 false |
| 唯一调用点（日记页按钮） | `ui/diary/DiaryScreen.kt:312`、`:344` | 均只传 `operatorId` → `auto=false` |
| `auto=true` 的语义（未启用） | `MainViewModel.kt:3362-3363` | 若 auto：生成**昨天**日记；昨天已有日记则直接跳过；生成 `date=yesterdayStr`、`version` 同日递增 |
| `generateDiarySync`（auto 便捷封装） | `MainViewModel.kt:3340` | **全仓无调用者**（死代码） |
| `DiaryGenerationOutcome.kt` | `viewmodel/shared/DiaryGenerationOutcome.kt:6-17` | 仅把失败收敛为 null / 传播取消，不涉及调度 |
| `autoDiaryEnabled` 设置 | `shared/.../settings/SettingsRepository.kt:526`（默认 **false**） | 全仓**只有声明/迁移/备份白名单**（:1565/:1588/:1611 省流档重置），**没有任何读取点** |
| `dailyDiaryOperatorLimit` 设置 | `SettingsRepository.kt:530`（默认 **3**，0..20） | 同上：**没有任何读取点**（死设置） |
| `DailyContentScheduler` | `automation/DailyContentScheduler.kt:26-29` | 只调度 `plan` / `moment` / `private` / `private_follow_up`，**没有日记任务** |
| 字数限制设置 | `ui/settings/StorySettingsScreen.kt:45-46` | `diary_min_chars` 默认 50（20..500）、`diary_max_chars` 默认 300（100..800），只在手动生成时用 |

因此：原版**没有**「每日自动为某些干员生成日记」的机制，也不存在「每日生成人数」的实际限制
（`daily_diary_operator_limit` 是未接线的半成品设置）；用户看到的日记全部是手动补写/历史遗留。
iOS 侧按「原版默认关闭」对齐：`diary_auto_enabled` 与 `diary_auto_<operatorId>` 默认 **false**，
由用户显式开启，并新增「日记生成管理」页做逐干员控制（见 §2）。

## 2. iOS 功能设计（T61）

### 2.1 设置键（SettingsRepository 通用 get/put，无需改 commonMain）

| 键 | 类型 | 默认 | 语义 |
|---|---|---|---|
| `diary_auto_enabled` | Boolean | `false` | 总开关；关闭时 `tick`/自动逻辑直接返回，不产生 AI 调用 |
| `diary_auto_<operatorId>` | Boolean | `false` | 单干员自动生成开关（147 位干员各自独立） |
| `daily_diary_operator_limit` | Int | 3 | 每轮 tick 最多生成人数（复用 oracle 键，0..20） |
| `diary_min_chars` / `diary_max_chars` | Int | 50 / 300 | 日记字数范围（复用 oracle 键；提示词约束 + 本地截断保护） |

生效规则：`干员自动生成生效 = 总开关 && 单干员开关`。

### 2.2 生成管线（`DiaryAutoGlue.generateForOperator(operatorId, force)`）

1. **幂等前置**：读 `diaries` 中 `(operatorId, 北京今天)`；`force=false` 且已存在 → 直接返回原记录
   （`created=false`，不写库、不调模型）；`force=true` → 当天写新 `version`（= oracle「再生成一篇」版本递增语义）。
2. **上下文组装**（对齐 `MainViewModel.generateDiaryTextLocked` 的可用字段）：
   - 昨天（北京时间）私聊事实：该干员私聊会话中昨天 `timestamp` 的消息，最近 12 条（`PRIVATE_CHAT` 注入权限控制）；
   - 昨天群聊事实 + 近期群聊滚动摘要：该干员所在群会话（`members` 含 id/名字）昨天消息 + `short_term_memory`
     （`GROUP_CHAT` 权限控制，条数 = `diaryGroupSummaryCount`）；另有群/私聊 daily summary；
   - 长期印象：`getLongTermImpression`；
   - 关系事件：`relationships` 关系对象 + 对方公开记忆锚点（`RELATIONSHIP` 权限控制，条数 =
     `diaryRelationEventCount`；shared 未移植 relation_events 表，见 §4）；
   - 今日状态（地点/活动/情绪，仅决定口吻）；用户档案（`user_name/user_gender/user_signature`）；
   - 字数设置 → `DIARY_MIN_CHARS` / `DIARY_MAX_CHARS` 占位符。
3. **提示词**：`SettingsRepository.resolvePromptTemplate("diary", …)`（含编辑器自定义/版本升级语义）→
   `PromptRender.buildCachePromptLayers`（稳定前缀 + 易变资料分层）+ `buildNaturalRuntimeContext` +
   DATA_BOUNDARY + 【本轮任务】；与 oracle `buildContentGenerationMessages`（`MainViewModel.kt:535`）同构。
4. **模型**：真实供应商（`RealProviderStreamingClient`，surface=`diary`，90s 超时）；
   未配置供应商 → 本地确定性**演示内容**（同 operator+日期 → 同一篇，字数落在设置范围内）。
   自检路径用确定性 **mock** 内容（仍走真实提示词/规范化/DB 链路）。
5. **规范化**：`PlainCommentContentNormalizer`（去代码栅栏/JSON 包装/引号/「以下是日记」前缀、
   拒绝 AI 免责声明与未替换占位符）→ 超过 `diary_max_chars` 保守截断；第三人称/空内容重试一次
   （oracle `looksThirdPersonDiary` 同判定）。
6. **写库**：`ChatRepository.insertDiary(Diary(operatorId, operatorName, content, date=北京今天, createdAt))`，
   `queueMemory = memoryV2Enabled && diaryMemoryGenerationEnabled`（= oracle 记忆队列语义）。
7. **未读语义不变**：写入后 `diaries.createdAt > diary_read_at_<operatorId>` 自动成为 T51 的干员未读。

### 2.3 每日 tick（`DiaryAutoGlue.tick(limitOverride)`）

- 总开关关闭 / 上限 0 → 直接返回（message 说明原因，无 AI 调用）；
- 只处理「单干员开关开启」的干员；逐个检查 `(operatorId, 北京今天)` 是否已有日记，缺失才生成；
- **每次 tick 最多生成 `min(limitOverride>0 ? limitOverride : daily_diary_operator_limit, 20)` 位**，
  避免一次把 147 人全生成（对齐 oracle 未接线的 `daily_diary_operator_limit=3` 语义）；
- 返回 `DiaryTickDto(generated/enabledCount/skippedExisting/failed/limit/demoMode/elapsedMs/message)`。
- 触发点（iOS 无 WorkManager，前台补算）：进入「干员日记」页 / 进入「日记生成管理」页时各调一次
  `tick(0)`；管理页另有「补齐今日日记」按钮。**不做后台定时**（见 §4 限制 1）。

### 2.4 桥 API（`DiaryAutoGlue`，`@ObjCName(swiftName="DiaryAutoGlue")`）

回主线程回调；错误经第二参数回传（null = 成功）；DTO 只用简单类型。

| 方法 | 回调 | 说明 |
|---|---|---|
| `status(onResult)` | `(DiaryAutoStatusDto, String?)` | 总开关 + 147 干员条目（开关 / 最近日记时间/日期/字数 / 今日是否已生成）+ 计数 + 上限 + 演示模式 |
| `setMasterEnabled(enabled, onDone)` | `(Int, String?)` | 写总开关；回调开启干员数 |
| `setOperatorAuto(operatorId, enabled, onDone)` | `(Boolean, String?)` | 写单干员开关；回调生效状态 |
| `setAllOperatorAuto(enabled, onDone)` | `(Int, String?)` | 全部开启/关闭（同时写总开关，避免"开了 147 个却没开总开关"） |
| `generateForOperator(operatorId, force, onResult)` | `(DiaryGenerateResultDto?, String?)` | 立即生成 / 同日幂等 / force 当天新版本 |
| `tick(limitOverride, onDone)` | `(DiaryTickDto, String?)` | 每日补齐（限人数） |
| `debugRunSelfTest(onDone)` | `(String)` | `--diary-auto-selftest` 端到端自检 |
| `debugLastSelfTestReport(onResult)` | `(String)` | 最近一次自检报告（调试用） |

`DiaryBridge` 追加 `listByOperator(operatorId, unreadOnly, onResult)`：按干员筛选的日记列表
（排序/未读语义与 `list` 一致；供后续干员维度视图/管理页深链复用）。

### 2.5 UI（`Features/Diary/DiaryAutoManageView.swift`）

从「干员日记」页工具栏（`slider.horizontal.3`，accessibility「日记生成管理」）进入。

- 顶部卡：总开关 Toggle（关闭时提示"所有干员都不会自动写日记"）、已开启/今日已生成/每轮上限统计、
  演示模式提示（未配置供应商时）；
- 快捷区：「全部开启」「全部关闭」「补齐今日日记（本轮最多 N 位）」（生成中 spinner）；
- 列表：147 位干员（`RoleAvatar` 头像 + 名字 + 「最近日记：今天 HH:mm · N 字 / M/d · N 字 / 还没有日记」+
  「今日已生成」徽标 + 单干员 Toggle + 「立即生成」按钮）；行内生成中 spinner / 失败红字；
- 搜索栏（按名字/id 过滤，147 行可用）、下拉刷新、工具栏刷新；
- 状态：加载态、错误态（原因 + 重试）、空态（无干员时引导先去角色页）；
- 生成完成后自动 `status` 刷新；返回日记列表时刷新（新日记出现在列表/未读）。

## 3. 自检（`--diary-auto-selftest`）

启动参数同时触发两段自检，对用户路径零影响：

1. **Kotlin 桥层**（`IosAppGraph` 启动钩子 → `DiaryAutoGlue.debugRunSelfTest`）：
   开启前两位干员 + 总开关 → 立即生成（mock provider 走真实提示词/规范化/DB）→ `diaries` 行存在、
   正文长度在 `diary_min_chars..diary_max_chars` → 非 force 再生成返回同一 id/version（幂等）→
   tick 为第二位补齐、重复 tick 不再生成 → 单干员开关关闭后 tick 不生成 → 总开关关闭后 tick 不生成 →
   status 聚合（147 位、开关、今日已生成、上限）。结果写
   `Application Support/t61-diary-auto-selftest.log` + NSLog `[RhodesLink][T61SELFTEST]`。
2. **Swift UI 链路**（`DiaryAutoManageView` 自动打开 → `DiaryAutoAutomation`）：等待桥层自检完成 →
   断言列表 147 位 / 已开启 ≥2 / 今日已生成 ≥2 / 总开关开 / 列表行有「今日已生成」→「全部开启」按钮
   链路（147 位）→ 恢复初始 2 位 → 对第一位「立即生成」（force）→ 断言生成后 id/字数 → 写
   `Documents/diary-auto-selftest.log`。

主机侧：`xcrun simctl install/launch`（显式 udid）→ 定时截图（列表/开关/生成后）→ 取两份日志 +
`sqlite3 rhodes.db` 核对 `diaries` 行 + `defaults read` 核对 `diary_auto_*` 键。

## 4. 限制 / 与 oracle 的已知差异

1. **无后台定时**：iOS 不移植 Android WorkManager；自动生成的触发点是用户打开日记页/管理页时的前台
   tick（进程在前台才补算）。未来可接 BGTaskScheduler 调 `tick`。
2. **生成的是「今天」的日记**：oracle 的 auto 分支（未接线）生成「昨天」的；本卡按任务要求生成当日
   1 篇（同日幂等键 operatorId + 北京日期）。
3. **关系事件是近似**：shared 未移植 Android `relation_events` 表，`RELATION_EVENTS` 用
   relationships + 对方公开记忆锚点（空时回退关系备注/类型）近似 oracle `getRelationEvents`。
4. **普通内容字数**：oracle 的 `PlainGeneratedContentNormalizer` 不校验 min/max；iOS 额外做
   `diary_max_chars` 保守截断，min 不足时仅提示（`DiaryGenerateResultDto.message`），不做填充。
5. **无单条删除**：`Diaries.sq` 无按 id 删除查询；自检/截图夹具日记（正文含「T61自检」）可用
   「设置 → 数据管理 → 清理日记」或完整备份恢复清除。
6. **Token 统计**：日记生成不计入排行榜 token 分类（RankingBridge 分类未含 diary，oracle 有
   `trackTokens("diary")`；如需可后续在 shared 侧统一）。
7. **演示模式**：未配置供应商时生成确定性演示内容（非真实 AI）；UI 页头明确提示，配置供应商后
   下一轮自动走真实模型。
