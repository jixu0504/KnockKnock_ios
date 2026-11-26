# 规格卡：聊天排行榜 / 消费统计 / 大家的印象（T54）

> 模块：Phase 6 内容生态余项 ｜ 任务卡：task-54 ｜ 实现代理：impl-ranking-impressions（2026-11-23）
> Android oracle：`RankingScreen.kt:53`、`TokenStatsScreen.kt:65`、`ImpressionsScreen.kt:59`、`DataViewModel.kt:91-104`、`MainViewModel.kt:1527-1575`、`ChatMessages.sq:49-53`、`MemoryItems.sq`、`Memories.sq:23-26`、`Operators.sq`

## 1. 范围

| 项 | 内容 |
|---|---|
| 聊天排行榜 | 维度：消息数 / 互动天数 / 消费（估算）；消息数支持「昨日排行 / 历史总榜」两个 oracle tab；行 = 排名徽章 + 角色头像 + 名称 + 维度数值 + 好感 |
| 消费统计 | Token 输入/输出总览 + 6 分类明细；今日 / 本周 / 全部（oracle `TokenStatsScreen` 三分段） |
| 大家的印象 | 三段分组（长期印象 / 持续了解 / 互动印象）+ 角色/正文/级别·日期/类型标签/来源标签；旧版 `memories` 长期印象只读兜底 |
| 入口 | 功能 Tab「大家的印象」「聊天排行榜」两行（T52 已完成入口与路由）；`RouteView` 两分支替换占位页；深链 `rhodes://features/{ranking,impressions}` 不变 |
| 不在范围 | 排行榜/印象的编辑、删除、清空、重置 Token 统计；Token 折线图（oracle 图表为 Compose Canvas，iOS 首版只做数值+明细表）；点角色进私聊（iOS 用角色详情页承接） |

## 2. 数据契约（对照 golden/db/schema）

| 数据 | 表/键 | iOS 桥读取 | oracle |
|---|---|---|---|
| 排行榜消息数 | `chat_messages`：`isMe = 0 AND senderName != '' AND senderName != '系统' AND sessionId NOT LIKE 'group_%'` | `getAllMessagesForBackup()` 同口径过滤（自检与 `getMessageCountPerSender()` SQL 逐对核对） | `ChatMessages.sq:49,52`、`DataViewModel.kt:91-104` |
| 昨日区间 | `timestamp >= 昨日 00:00`（**无上界，含今天**，oracle 语义原样保留） | 北京时区 `todayStart - 24h` | `DataViewModel.kt:93-103` |
| 好感列 | settings `intimacy_daily_<operatorId>` | 同键 | `RankingScreen.kt:104` |
| 互动天数（新增维度） | 派生：该 sender 消息的北京时间自然日去重 | 内存派生，不落库 | 无 oracle 字段（任务卡新增，页面无「估算」标注） |
| 消费（逐角色） | 派生：该 sender 消息正文字符数合计 | 内存派生；`estimated = true`，UI 标注「估算」并说明 DB 无逐角色 token 字段 | 无 oracle 字段（任务卡新增） |
| Token 消费 | settings `token_in/out_<category>`、`daily_in/out_<category>_<yyyy-MM-dd>`（北京时间日期） | 6 分类合并键同 oracle（私聊/群聊/记忆/动态/日记/其他） | `TokenStatsScreen.kt:65,86-90`、`SettingsRepository.kt:1370-1393` |
| 印象 | `memory_items`：`ownerType=operator`、`sourceKind=PRIVATE_CHAT`、`status=active`、content 非空；L1 类型 ∈ {preference_expression, emotion_state, physiological_state}；L2 `expiresAt>now && importance>=60` 且类型 ∈ 6 种；L3 `expiresAt>now` | 逐条同条件过滤 | `MainViewModel.kt:1527-1551` |
| 印象排序 | L3 优先 → `updatedAt` 降序 → `importance` 降序 | 同序 | 同上 |
| 旧版印象兜底 | `memories` 表 `type='LONG_TERM'`、`MAX(id) per operatorId` | 只读；当该角色已有 memory_items L3 时跳过（对齐 `migrateLegacyImpressions` 去重语义） | `Memories.sq:23-26`、`MainViewModel.kt:1554-1567` |
| 级别/类型标签 | L3 长期印象 / L2 持续了解 / L1 互动印象；类型标签 8 种映射 | 同文案 | `ImpressionsScreen.kt:137-145,215-225` |
| 空态 | 排行榜「暂无数据」；印象「暂无印象数据 / 多聊几次后…」 | 同文案 + 补充引导 | `RankingScreen.kt:92`、`ImpressionsScreen.kt:155-163` |

**Schema 改动：无**（未新增/修改任何 `.sq` 文件、不改 `commonMain`，与并行批次写域隔离）。

## 3. 行为契约

1. 排行榜查询失败 → 页面错误卡（原因 + 重试），不清空已加载数据；桥回调第二参数非空。
2. 空数据 → 空态卡；「昨日」为空但历史非空时仍显示空态（或空行）不合并不实数据。
3. 维度/区间切换触发重新查询；同一维度重复点击不重复写库（纯读）。
4. 排序：数值降序，同值按 senderName 升序（oracle SQL 未定义同值次序，iOS 固定 tie-breaker 保证稳定）。
5. 排名从 1 连续编号；1-3 名徽章金/银/铜颜色，4+ 为灰色 `#n`。
6. 头像：`operators.avatarUri` 原样传入 `RoleAvatar`（内置 "1".."147" / 上传 media ID / 空=首字占位）。
7. 消费维度 `estimated = true`，页面显示「估算」胶囊 + 口径说明；Token 卡片为真实 settings 值。
8. 印象过滤失败条目（低重要度 L2 / 过期 L3 / 群聊来源 / 非 operator / 非私聊）必须被排除（自检夹具覆盖）。
9. 印象旧版兜底条目带「旧版」标签与来源「历史印象（旧版 memories）」；不写库、不迁移。
10. 所有桥回调在 Main 队列；错误经第二参数回传（null=成功）。

## 4. 并发/边界

- 消息读取上限：`getAllMessagesForBackup()` 全量（与备份同源）；规模 = DB 全部私聊消息，无分页（首版限制，见 §6）。
- 时区：固定 Asia/Shanghai；「今日/本周」日期键 = `yyyy-MM-dd`（与设置写入端一致）。
- 本周 = 周一 00:00（北京时间）至今天，按自然日累加；周日不特殊回退（isoDayNumber 已给周一）。
- 排行榜不写库；印象页不写库（自检夹具除外）。自检夹具仅在「DB 无任何真实数据」时幂等写入。

## 5. 验收

| # | 验收项 | 判定方式 | 命令/证据 |
|---|---|---|---|
| 1 | `RankingBridge` 编译并导出到 Swift | 编译产检 | `:shared:linkDebugFrameworkIosSimulatorArm64` + `grep RankingBridge Shared.h` |
| 2 | 排行榜三维度 + 昨日/历史读取，排序/排名/字段断言 | 桥层自检 `--ranking-selftest` | 日志 `t54-ranking-selftest.log` RESULT PASS |
| 3 | 消息数与 oracle SQL 逐对一致 | 自检 `rank.db.countParity` | 桥日志 + 主机 `sqlite3` 核对 |
| 4 | Token 三分段结构/合计断言 | 自检 `token.{today,week,all}.*` | 桥日志 |
| 5 | 印象过滤/分组/排序/字段/去重断言 | 桥层自检 `--impressions-selftest` | `t54-impressions-selftest.log` RESULT PASS |
| 6 | 印象 DB 过滤数一致 + 夹具排除 | 自检 `db.countParity` / `fixture.filterExcludesInvalid` | 桥日志 + 主机 sqlite3 |
| 7 | 页面空态/错误态/分段/徽章色 | 截图基线 | `evidence/t54-ranking.png`、`t54-impressions.png` |
| 8 | UI 链路（列表非空、排名连续、估算标注、三段顺序） | Swift UI 自动化断言 | Documents `t54-{ranking,impressions}-selftest.log` UI RESULT PASS |

## 6. 风险与限制

1. **消费为估算**：DB 无逐角色 token 字段（token 仅按全局分类记在 settings），逐角色消费 = 消息字符数，页面明确标注「估算」；真实 Token 消耗由「消费统计」卡片提供。
2. **昨日口径沿用 oracle**：SQL `timestamp >= 昨日 00:00` 无上界 → 「昨日排行」实际含今天；页面 note 明示，不擅自修正以保持金样一致。
3. **互动天数为派生维度**：oracle 无此概念（任务卡新增）；口径 = 北京时间自然日去重，按 sender 消息统计。
4. **全量消息读取**：排行榜/互动天数/消费使用 `getAllMessagesForBackup()`（无分页）；大数据量（数万条）时首屏可能偏慢。后续可加 SQLDelight 聚合查询（需改 `commonMain`，本卡写域外）。
5. **旧版印象兜底只读**：不执行 `migrateLegacyImpressions` 写库迁移（避免桥内写库与并行批次冲突）；某角色已有 memory_items L3 时旧版条目不展示。
6. **编辑/清空/重置未移植**：oracle 印象页有编辑/删除/清空、Token 页有重置统计；本卡只读展示（任务卡未要求），后续需要时补桥方法。
7. **自检夹具保留**：仅在 DB 无真实数据时写入（排行榜：1 干员+1 会话+3 干员消息+1 本人消息；印象：3 有效+3 应过滤+1 旧版），可用数据管理/完整恢复清除。
8. **点角色跳转**：oracle 进私聊；iOS 进角色详情（`Route.roleDetail`），不新建会话，避免副作用。

## 7. 评审结论

- reviewer：待评审（实现 + 自检证据已附 handoff）
- lead：待批准
