# T54 实现 handoff：聊天排行榜 / 消费统计 / 大家的印象

> 代理：impl-ranking-impressions ｜ 任务：task-54（Phase 6 余项）｜ 状态：**实现完成，shared framework + xcodebuild 编译通过，iPhone 17 模拟器（专用 T54-sim）端到端实跑 PASS（2026-11-23）**
> 规格卡：`ios-port/specs/ranking-impressions-spec.md`（本卡产出）｜ Android oracle：`RankingScreen.kt:53`、`TokenStatsScreen.kt:65`、`ImpressionsScreen.kt:59`、`DataViewModel.kt:91-104`、`MainViewModel.kt:1527-1575`、`ChatMessages.sq:49-53`、`MemoryItems.sq`、`Memories.sq:23-26`
> git：**未提交**（lead 统一提交）。写域：`shared/src/iosMain/.../iosbridge/{RankingBridge.kt, ImpressionsBridge.kt 新增, IosAppGraph.kt 最小 patch}`、`iosApp/RhodesLink/{Bridge/{RankingClient,ImpressionsClient}.swift 新增, Bridge/AppGraphClient.swift 追加 2 getter, Features/{Ranking,Impressions}/** 新增, Features/Common/RouteView.swift 仅 .ranking/.impressions 两分支}`、`ios-port/{specs,handoffs}`。
> 未改：`FeaturesView.swift`（两入口 T52 已存在且可用，无需 patch，零冲突）、Settings / Chat / Moments / Diary / Roles 功能页、`commonMain`（无 `.sq`/schema/迁移改动）。

## 1. 交付摘要

| 能力 | 实现 | 数据源 |
|---|---|---|
| 排行榜·消息数 | 维度 = 消息数；昨日排行 / 历史总榜两个 oracle tab；oracle SQL 同口径（私聊、`isMe=0`、排除「系统」/群聊会话） | 真实 `chat_messages` |
| 排行榜·互动天数（新增维度） | 该 sender 消息的北京时间自然日去重计数 | 真实 `chat_messages`（内存派生，不落库） |
| 排行榜·消费（估算） | 该 sender 消息正文字符数合计；页面「估算」胶囊 + 口径说明（DB 无逐角色 token 字段） | 真实 `chat_messages`（内存派生） |
| 排行榜 UI | 维度分段（消息数/互动天数/消费）、昨日/历史胶囊、排名徽章（1-3 金/银/铜）、角色头像+名称、数值列、好感列、空/加载/错误/下拉刷新、点行→角色详情 | `RankingBridge` |
| 消费统计 | Token 输入+输出总览 + 6 分类明细（私聊/群聊/记忆/动态/日记/其他），今日/本周/全部 | settings `token_in/out_*`、`daily_in/out_*_<yyyy-MM-dd>`（北京时区） |
| 大家的印象 | 三段分组（长期印象/持续了解/互动印象）+ 角色头像/名称/级别·日期/正文/类型标签/来源标签/旧版标记；空/加载/错误/下拉刷新；点行→角色详情 | 真实 `memory_items` L1/L2/L3 + 旧版 `memories` LONG_TERM 只读兜底 |
| 路由/入口 | 功能 Tab「大家的印象」「聊天排行榜」（T52 已完成入口；`rhodes://features/{impressions,ranking}` 深链不变）；`RouteView` 两分支替换占位页 | — |
| 自检 | `--ranking-selftest`：桥层（夹具→三维度→昨日/历史→与 oracle SQL 逐对核对→Token 三分段）+ Swift UI 链路（维度切换/排序/估算/区间/Token）→ 双日志 + 截图；`--impressions-selftest`：桥层（夹具→过滤→分组→排序→DB 核对）+ UI 链路 → 双日志 + 截图 | 真实 DB/settings，无 mock |

## 2. 文件清单

| 侧 | 类型 | 文件 | 说明 |
|---|---|---|---|
| shared iosMain | 新增 | `iosbridge/RankingBridge.kt`（~430 行） | `@ObjCName(swiftName="RankingBridge")`：`ranking(dimension,range)` / `tokenStats(range)` / `debugRunSelfTest` + 5 DTO；oracle SQL 同口径过滤、北京时区日期派生、Token 6 分类三分段、幂等夹具 |
| shared iosMain | 新增 | `iosbridge/ImpressionsBridge.kt`（~380 行） | `@ObjCName(swiftName="ImpressionsBridge")`：`list()` / `debugRunSelfTest` + 2 DTO；oracle 过滤/排序逐条复刻 + 旧版 memories 只读兜底（有 memory_items L3 时去重） |
| shared iosMain | 修改（追加） | `iosbridge/IosAppGraph.kt` | +2 桥字段、+`ranking()` / `impressions()` 2 getter（最小 patch，未动其它桥） |
| iosApp Swift | 新增 | `Bridge/RankingClient.swift` | Swift 模型（维度/区间/Token 区间枚举 + 行/快照 struct）+ `loadRanking/loadTokenStats/runRankingSelfTest`（async/await，回调转 continuation） |
| iosApp Swift | 新增 | `Bridge/ImpressionsClient.swift` | 印象行/快照/三段分组 struct + `loadImpressions/runImpressionsSelfTest` |
| iosApp Swift | 修改（追加） | `Bridge/AppGraphClient.swift` | +`rankingBridge` / `impressionsBridge` 2 getter |
| iosApp Swift | 新增 | `Features/Ranking/RankingView.swift` | 排行榜+消费统计页面（分段/徽章/状态卡/自检卡/UI 自动化断言） |
| iosApp Swift | 新增 | `Features/Ranking/RankingAutomation.swift` | `--ranking-selftest` 日志/结论（Documents/t54-ranking-selftest.log + NSLog） |
| iosApp Swift | 新增 | `Features/Impressions/ImpressionsView.swift` | 印象三段分组页面（来源标签/旧版标记/状态卡/自检卡/UI 断言） |
| iosApp Swift | 新增 | `Features/Impressions/ImpressionsAutomation.swift` | `--impressions-selftest` 日志/结论（Documents/t54-impressions-selftest.log） |
| iosApp Swift | 修改（替换 2 分支） | `Features/Common/RouteView.swift` | `.impressions` / `.ranking` → 真实页面（T53 同文件外观/通知/调试日志分支未动） |
| 文档 | 新增 | `ios-port/specs/ranking-impressions-spec.md` | 规格卡（口径/契约/验收/限制） |
| 证据 | 新增 | `ios-port/handoffs/evidence/t54-*` | 见 §4 |

## 3. 关键口径与设计

1. **消息数与 oracle 逐对一致**：桥内用 `getAllMessagesForBackup()` 过滤（`!isMe && senderName != "" && != "系统" && !sessionId.startsWith("group_") && timestamp >= since`），与 `ChatMessages.sq:49-53` 的 `getMessageCountPerSender()` 结果逐对断言（`rank.db.countParity`）。
2. **「昨日」沿用 oracle 语义**：`timestamp >= 昨日 00:00` **无上界（含今天）**；页面 note 明示，不擅自修正以保持金样一致。
3. **互动天数/消费为任务卡新增维度**：oracle 无逐角色字段 → 内存派生（北京时间自然日去重 / 消息字符数合计）；消费 `estimated=true`，UI 显示「估算」胶囊 + 说明「Token 真实消耗见消费统计」。
4. **好感列同源**：`settings.getInt("intimacy_daily_<operatorId>", 0)`（oracle `RankingScreen.kt:104`），带正号。
5. **消费统计按 oracle TokenStatsScreen**：6 分类（多 key 合并为一行）+ 今日/本周/全部；日期键 `yyyy-MM-dd` 北京时区，本周 = 周一~今天逐日累加（不依赖 LocalDate.plus 的版本差异，用 epoch-millis 步进）；`formatTokens` 与 oracle 同（`1.2M`/`1.2K`/原值）。
6. **印象过滤逐条复刻 oracle**（`MainViewModel.kt:1527-1551`）：ownerType/sourceKind/status/content 非空 + L1 三类型、L2（未过期 && importance≥60 && 六类型）、L3 未过期；排序 L3 优先 → updatedAt 降序 → importance 降序。**旧版 memories 兜底**：`getAllLongTermImpressions()` 只读，若该角色已有 memory_items L3 则跳过（对齐 `migrateLegacyImpressions` 去重），携带「旧版」标签/来源「历史印象（旧版 memories）」。桥内**不写库迁移**。
7. **排序合并**：当前条目与旧版兜底条目合并后统一按 oracle 排序（保证 DTO 契约「L3 first」）。
8. **无 schema 改动**、**零 commonMain 改动**，与并行批次（T52/T53）写域隔离；`FeaturesView` 未动（入口 T52 已存在，实测截图 `t54-features-entries.png` 可点入两页）。
9. **回调式桥**：所有回调 Main 队列，错误第二参数回传；DTO 仅简单类型（无 Flow/sealed/data class 外溢）。

## 4. 验证证据（iPhone 17 专用模拟器 T54-sim `2C4AA085-3456-48F5-B54F-357EC2CA11ED`，显式 udid；为排除并行 launch 参数互覆盖，新建此专用模拟器）

### 4.1 构建

| 步骤 | 结果 | 证据 |
|---|---|---|
| `:shared:linkDebugFrameworkIosSimulatorArm64`（`--no-daemon`，排队纪律） | **BUILD SUCCESSFUL**；`RankingBridge`/`ImpressionsBridge`/DTO 已进 `Shared.h`（`grep -n "RankingBridge\|ImpressionsBridge" Shared.h`） | `evidence/t54-framework-link.log` |
| `xcodebuild build`（generic iOS Simulator，DerivedData-t54，CODE_SIGNING_ALLOWED=NO） | **BUILD SUCCEEDED**，0 error | `evidence/t54-xcodebuild.log` |

### 4.2 端到端自检（干净安装：`simctl uninstall` → `install` → 专用模拟器）

```bash
DEV=2C4AA085-3456-48F5-B54F-357EC2CA11ED   # T54-sim
xcrun simctl install $DEV DerivedData-t54/Build/Products/Debug-iphonesimulator/RhodesLink.app
xcrun simctl launch $DEV com.rhodeslink.app --open-url rhodes://features/ranking --ranking-selftest
# 截图 → 间隔 0
xcrun simctl terminate $DEV com.rhodeslink.app
xcrun simctl launch $DEV com.rhodeslink.app --open-url rhodes://features/impressions --impressions-selftest
# 截图 → 
xcrun simctl launch $DEV com.rhodeslink.app --open-url rhodes://features   # 功能 Tab 入口截图
```

- **排行榜桥层日志**（`Library/Application Support/t54-ranking-selftest.log`）：**RESULT PASS**，24 项 PASS：
  - 夹具 `fixture created op=t54_selftest_op session=session_t54_selftest_op messages=3`（3 天各 1 条干员消息 + 1 条本人消息）；
  - `rank.messages.nonEmpty entries=1`、`rank.sortedDesc`、`rank.ranksSequential`、`rank.fields`（名字/头像/单位）、`rank.notEstimated`；
  - **DB 核对** `rank.db.countParity | db=1 bridge=1 dbPairs=T54自检干员:3`、`db.rank.totalMessages=3 bridge.total=3`；
  - `rank.days.nonEmpty sample=T54自检干员:3`（3 个自然日）、`rank.consumption.estimated`、`rank.consumption.chars=87`、`days.totalLteConsumption`；
  - `rank.yesterday.lteHistory | yesterdayTotal=2 historyTotal=3`（oracle「昨日」含今天 → 今天 + 昨天 2 条）；
  - `token.{today,week,all}.structure`（6 分类）与 `.totals`（输入+输出=总计）。
- **排行榜 UI 自动化日志**（`Documents/t54-ranking-selftest.log`）：**UI RESULT PASS**（bridge PASS + 列表非空/排名连续/降序/字段 + 互动天数非空且 ≤ 消息数 + 消费 estimated + 昨日 ≤ 历史 + Token 三分段）。
- **印象桥层日志**（`Library/Application Support/t54-impressions-selftest.log`）：**RESULT PASS**，18 项 PASS：
  - 夹具 `fixture created op=mon3tr second=misery valid=3 invalid=3 legacy=1`；
  - `list.nonEmpty total=5`、`list.fields sample=M3/长期印象`、`list.sortedL3First levels=L3,L3,L3,L1,L2`、`list.counts total=5 L3=3 L2=1 L1=1 legacy=2`、`list.levelLabels`；
  - 过滤断言：`fixture.filterExcludesInvalid`（低重要度 L2/已过期 L3/群聊来源 L3 全被排除，contents 仅 3 有效 + 2 旧版）、`fixture.legacySource`（旧版 memories 兜底）；
  - **DB 核对** `db.countParity | dbMemoryItems=3 bridge=3 dbLegacyRaw=2 bridgeLegacy=2`、`db.fixtureRowsPresent rows=6 expected>=6（3 有效 + 3 应被过滤）`。
- **印象 UI 自动化日志**（`Documents/t54-impressions-selftest.log`）：**UI RESULT PASS**（列表非空/计数一致/三段顺序 L3→L2→L1/字段完整/旧版兜底 2 条）。
- **主机侧 sqlite3 核对**（`evidence/t54-db-check.txt`）：
  - oracle SQL `getMessageCountPerSender` → `T54自检干员|3`（= 桥 total 3）；
  - `chat_messages` 4 条（session_t54_selftest_op：3 干员 + 1 本人，isMe 被正确排除）；
  - `memory_items` 6 行：id1/2/3 有效（L3/L2/L1），id4 低重要度 30、id5 expiresAt 已过期、id6 GROUP_CHAT（均被 oracle 过滤）；
  - `memories` LONG_TERM 2 行（amiya 演示记忆 + misery T54 夹具）；
  - oracle impressions 过滤 SQL 计数 = 3（与桥 dbMemoryItems 一致）。
- **截图**（`xcrun simctl io ... screenshot`）：
  - `evidence/t54-ranking.png`：历史·消息数维度（排名徽章 1 金色、头像/名称/3/好感 0、维度分段、昨日/历史胶囊、消费统计 Token 卡片、T54 自检 PASS 卡）；
  - `evidence/t54-impressions.png`：三段分组（长期印象 3 / 持续了解 1 / 互动印象 1）、M3/旧版 Misery 行、类型/来源标签、「旧版」标记、自检 PASS 卡；
  - `evidence/t54-features-entries.png`：功能 Tab 中「大家的印象」「聊天排行榜」入口（T52 已有入口实测可达）。

> 并行环境注意：本卡专用 T54-sim（`2C4AA085-…`）；iOS 27.2 模拟器 `simctl launch` 在 App 已运行时不会替换启动参数，复跑前先 `terminate`；`simctl install` 覆盖升级可能更换 data container UUID，复跑后重新 `get_app_container` 取日志路径。

## 5. 已知限制（与 spec §6 一致）

1. **消费为估算**：DB 无逐角色 token 字段 → 逐角色消费 = 消息字符数，页面标注「估算」；真实 Token 见「消费统计」卡片（settings 累计/每日键）。
2. **昨日口径沿用 oracle**：`>= 昨日 00:00` 无上界 → 「昨日排行」含今天，页面 note 明示。
3. **互动天数为任务卡新增派生维度**，oracle 无对应字段。
4. **全量消息读取无分页**（`getAllMessagesForBackup()`）：数万条消息时首屏可能偏慢；后续若要优化需新增 `.sq` 聚合查询（本卡写域外）。
5. **旧版印象只读兜底、不迁移**：不执行 `migrateLegacyImpressions` 写库；某角色已有 memory_items L3 时旧版条目不重复展示。
6. **oracle 的印象编辑/删除/清空、Token 重置统计未移植**（任务卡未要求；只读展示）。
7. **自检夹具保留**：仅在 DB 无真实数据时幂等写入（排行榜 1 干员+1 会话+3 干员消息+1 本人消息；印象 3 有效+3 应过滤+1 旧版），可用数据管理/完整恢复清除；夹具首次写入后再次运行自检为「skip 夹具、基于真实数据断言」模式（仍 PASS）。
8. **点角色跳转**：oracle 进私聊；iOS 进角色详情（`Route.roleDetail`），不建会话避免副作用。
9. **Token 数据为空时页面显示 0**（T54-sim 未配置模型供应商、无真实调用；结构与合计断言通过，本周/全部为 0 是真实值）。
