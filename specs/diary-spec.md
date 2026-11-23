# T51 规格：干员日记（iOS 移植，Phase 6 内容生态加急）

> 代理：impl-diary（task-51）｜分支 ios-port｜Kotlin 2.3.21｜状态：实现完成（桥 + 列表/详情 + 路由 + 自检 + 证据）
> Android oracle：`app/src/main/java/com/rhodes/privatechat/ui/diary/DiaryScreen.kt:74`（页面）、
> `MainViewModel.kt:3312-3338`（未读/标记已读）、`SettingsRepository.kt:1079-1083`（`diary_read_at_*`）、
> `repo/data/DiaryRepository.kt`（`diaries` 表读写）、`golden/db/schema/Diaries.sq`（表结构）。
> 纪律：RouteView 只改 `.diary` 分支；不碰 Settings/Roles/Chat 页；`git` 不提交。

## 1. 范围与数据

| 项 | 结论 |
|---|---|
| 数据表 | `diaries(id, operatorId, operatorName, content, date, version, createdAt)`（与 `golden/db/schema/Diaries.sq` 一致；**无标题列、无已读列**） |
| 列表 | 跨干员平铺：`ORDER BY createdAt DESC, id DESC`；`unreadOnly=true` 时只保留所属干员未读的条目 |
| 展示字段 | 角色名 = `diaries.operatorName`（回退 `operators.name`）；头像 = `operators.avatarUri`（"1".."147" 内置 Bundle 头像，空 → 首字占位）；标题 =「干员名 · 日期」（表无标题列，桥派生）；摘要 = 正文压平空白后前 96 字；时间 = `createdAt` |
| 未读 | 干员维度：该干员 `MAX(diaries.createdAt) > settings.getDiaryReadAt(operatorId)`；未读计数 = 未读干员数（= Android `getUnreadDiaryCount()`） |
| 标记已读 | `settings.putDiaryReadAt(operatorId, 该干员最新 createdAt)`（= Android `markDiaryRead`，键 `diary_read_at_<operatorId>`，NSUserDefaults Long） |
| 详情 | `diaries.content` 全文（按 id 读取） |
| 删除 | **本卡不提供单条删除**：`Diaries.sq` 只有按干员/过期/全量删除，无按 id 删除查询；T51 写域不含 `Diaries.sq`/`DiaryRepository`（见 §6 限制） |

### 1.1 共享层改动（写域内）

| 文件 | 改动 |
|---|---|
| `shared/src/iosMain/.../iosbridge/DiaryBridge.kt` | **新增**：列表/详情/标记已读/未读计数 + 自检夹具/自检（真实 `ChatRepository` + `SettingsRepository`） |
| `shared/src/iosMain/.../iosbridge/IosAppGraph.kt` | 最小 patch：`diaryBridge` 字段 + `diary()` getter |

> `commonMain`（`DiaryRepository`/`Diaries.sq`）零改动；复用现成 `ChatRepository.getAllDiariesForBackup/getAllDiaryEntries/getDiary/getDiaryCount/insertDiary`。

## 2. 桥接契约（`DiaryBridge`，`@ObjCName(swiftName="DiaryBridge")`）

Swift 入口 `graph.diary()`；回调一律 Main 队列；错误经第二参数回传（null = 成功）。

| 方法 | 回调 | 语义 |
|---|---|---|
| `list(unreadOnly, onResult)` | `(DiaryListDto, String?)` | 跨干员列表（createdAt 倒序）；DTO 含 items/totalCount/unreadCount/unreadOnly |
| `detail(id, onResult)` | `(DiaryDetailDto?, String?)` | 全文；不存在返回错误「日记不存在（可能已被删除）」 |
| `markRead(operatorId, onDone)` | `(Int, String?)` | 写 `diary_read_at_<operatorId>` = 该干员最新 createdAt；回调剩余未读干员数；无日记 no-op |
| `unreadCount(onResult)` | `(Int, String?)` | 未读干员数（= Android getUnreadDiaryCount；功能页角标同源） |
| `debugEnsureFixture(onDone)` | `(String)` | 自检/截图夹具（幂等）：DB 空 → 写 1 条「T51自检」日记；否则复用最新一篇；把该干员已读位归零 |
| `debugRunSelfTest(onDone)` | `(String)` | 端到端自检，写 `Application Support/t51-diary-selftest.log` |

DTO（普通 class，简单类型）：`DiaryEntryDto(id, operatorId, operatorName, avatarId, date, title, summary, createdAt, version, isUnread)`、
`DiaryListDto(items, totalCount, unreadCount, unreadOnly)`、`DiaryDetailDto(id, operatorId, operatorName, avatarId, date, title, content, createdAt, version, isUnread)`。

## 3. UI 契约（`iosApp/.../Features/Diary/` + `Bridge/DiaryClient.swift`）

- `DiaryView`：头部卡（共 N 篇 / 未读干员 M 位 + 「全部/未读」分段筛选）、下拉刷新、工具栏刷新；
  行 = 角色头像（`RoleAvatar`，未读红点）+ 标题 + 未读徽标 + 时间 + 摘要（2 行）+「第 N 篇 · 日期」；
  空态（全部：还没有干员日记；未读：没有未读日记）、加载态、错误态（原因 + 重试）。
- 点击行 → `DiaryDetailView`：`shared.detail(id)` 取全文，打开后自动 `markRead`（干员维度未读清零），
  返回列表触发刷新（未读点消失）；详情含头部（头像/角色/标题/第 N 篇/已读徽标）+ 全文（可选中复制）。
- 未读筛选由桥 `list(unreadOnly=true)` 服务端过滤（与全量列表同一排序）。
- 路由：`RouteView` 的 `.diary` 分支 = `DiaryView()`（替换原 PlaceholderView；不改其它分支）；
  入口不变（功能 Tab → `router.openFeature(.diary)`，深链 `rhodes://features/diary`）。
- 功能 Tab 的日记角标本轮仍为 0（`FeaturesView.swift` 属 T48/T50 写域；桥 `unreadCount` 已可供后续接线）。

## 4. 自检与验证（`--diary-selftest`）

启动参数：`--diary-selftest`（配合 `--open-url rhodes://features/diary` 自动进入页面）。对用户路径零影响。

1. `DiaryBridge.debugEnsureFixture`：DB 无日记时写 1 条「T51自检」日记（北京时间今天，干员 amiya/首位干员）；
   已读位归零 → 列表出现未读点。
2. `DiaryBridge.debugRunSelfTest`：列表非空/倒序/字段完整 → 未读标记（unreadCount≥1、unreadOnly 全为未读）→
   详情全文一致 → `markRead` 后 `diary_read_at_*` ≥ 最新 createdAt 且该干员未读清零 → DB 行数核对 →
   重新置未读（供 UI 截图）。日志 `Application Support/t51-diary-selftest.log`。
3. Swift `DiaryAutomation`：桥自检后重载列表（记录 UI 未读点）→ 6s 后自动打开未读首篇 →
   详情加载并 markRead → 4s 后重载列表/未读计数，断言该干员未读清除、详情全文非空 →
   写 `Documents/diary-selftest.log`（含桥报告 + UI 断言；页面顶部「T51 自检」卡展示结论）。

主机侧（`xcrun simctl` 显式 udid）：install → launch（上述参数）→ 定时截图（列表未读态 / 详情阅读态）→
取容器 `Documents/diary-selftest.log` + `sqlite3 rhodes.db` 核对 `diaries` 行 → `defaults read` 核对
`diary_read_at_<operatorId>`。证据见 `ios-port/handoffs/evidence/t51-*`。

## 5. 限制 / 已知取舍

1. **删除不可用**：schema 无按 id 删除查询，本卡写域不含 `Diaries.sq`，故详情页无删除入口（任务卡「删除可选」）。
2. 标题为桥派生（「干员名 · 日期」），Android oracle 无标题概念；摘要是正文截断，评论文本等不参与。
3. 未读是**干员维度**（对齐 oracle）：列表把未读干员的全部历史日记都标未读；打开任一篇即整位干员已读。
4. 自检夹具日记会留在 DB（仅当 DB 为空时写入 1 条，内容含「T51自检」），可用「设置→数据管理→清理日记」
   或完整备份恢复清除；无单条删除能力。
5. AI 重新生成（`generateDiary`）不在本卡范围（依赖模型供应商/提示词管线，属 A7 生成规则波次）。
6. 功能 Tab 日记角标未接线（`FeaturesView.swift` 写域冲突，避免与 T48/T50 并行修改）；桥已提供 `unreadCount`。
