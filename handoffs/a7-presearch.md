# handoffs/a7-presearch.md —— A7 预研方法与证据索引

预研人：a7-presearch（T7）。结论见 `../decisions/a7-预研.md`（拆分建议，比值 43%–65% < 80% 触发线）。基线：RhodesLink @ ios-port（3642d2c，tag v1.14.27-android-baseline），全程只读，未构建。

## 方法

1. 枚举 automation 包 9 文件（`ls` + `wc -l`，共 790 行），逐个通读（read）。
2. 调度注册点：`grep -rn "WorkManager|PeriodicWorkRequest|OneTimeWorkRequest" app/src` → 命中 10 文件（8 个 automation + MainViewModel/GroupChatViewModel + BackupRestoreCoordinator）。
3. 调用点枚举：`grep -rn "<Scheduler>\\." --include=*.kt app/src | grep -v automation/`，逐个核对上下文。
4. 提示词管线：grep `PromptTemplates|memoryV2Pipeline` 于 3 个 ViewModel；读 `PromptTemplates.kt:6-20` 分发表与 `triggerSingleAiReply`/`sendProactiveMessage`/`runContextMaintenance` 全文。
5. 通知面：grep `RhodesNotificationCenter.show` 全仓（4 处调用，仅 2 处属 A7 弃项）。
6. 设置面：grep A7 相关键于 `shared/src/commonMain/.../SettingsRepository.kt`（注意：该文件不在 app/src 下，审计口径「190 属性」即此文件）。
7. 平台确认：grep BootReceiver/BOOT_COMPLETED（无命中）——无额外系统级调度钩子；WorkManager 默认初始化。
8. 与 plan/audit 交叉核对：计划书 A7 节（第 78–89 行）+ 补充裁决 3（第 18 行）；`ios-port/audit/platform-coupling.md:15,30`（automation 9 文件 P1 替换结论一致）。

## 证据索引（关键 file:line）

| 断言 | 证据 |
|---|---|
| MomentReply 调度参数（一次性/唯一键/退避/网络约束） | `automation/MomentReplyWorker.kt:14-23` |
| MomentReply 双通道（WorkManager + 进程内协程） | `viewmodel/MainViewModel.kt:2847-2859` |
| 评论回复随机延迟 10–30s | `MainViewModel.kt:2833` |
| MomentReply 启动重挂 / 手动重试 | `MainViewModel.kt:628-631`、`:2866-2874` |
| executeMomentReplyTask 租约与超时（4min） | `MainViewModel.kt:2876-2905` |
| moment_comment 管线（模板/记忆/知识库/社交上下文） | `MainViewModel.kt:2939-3022`，模板 `PromptTemplates.kt:16` |
| ContextMaintenance 调度参数 | `automation/ContextMaintenanceWorker.kt:14-23` |
| ContextMaintenance 启动重挂 | `MainViewModel.kt:632-639` |
| runContextMaintenance 幂等锁 + 抽取/摘要分步 | `ChatViewModel.kt:2216-2267` |
| 摘要提示词内联（非 PromptTemplates） | `ChatViewModel.kt:541`、`:573` |
| 摘要生成与聊天主路径共用 | `ChatViewModel.kt:2776`、`:2804` |
| 每日计划器 24h 周期注册 | `automation/DailyContentScheduler.kt:38-47`；`RhodesApplication.kt:81`；`BackupRestoreCoordinator.kt:116` |
| 前台补建计划（Android 已有补算原语） | `MainViewModel.kt:706`；幂等标记 `DailyContentScheduler.kt:61/:129` |
| 迟建铺开恢复逻辑（iOS 直接平移的语义） | `DailyContentScheduler.kt:229-241` |
| 每日动态投递（AUTO 生成与手动共用） | `MainViewModel.kt:1183-1213`；`generateOneForOpSync :2543`；手动入口 `:2450/:3182` |
| 主动私聊计划筛选（权限/概率/派遣/上下文/配额） | `DailyContentScheduler.kt:76-108` |
| 主动私聊生成管线 + 30 变量 + 记忆召回 | `MainViewModel.kt:876-950`；`buildProactiveContext :1120` |
| PRIVATE_PROACTIVE 模板分发 | `PromptTemplates.kt:9` |
| 主动私聊/补句通知（唯一弃项专属通知点） | `MainViewModel.kt:1265-1267`、`:1379-1381` |
| 免打扰 = 计划期过滤函数（非 worker） | `DailyContentScheduler.kt:243-255`；设置 `SettingsRepository.kt:827-835`；UI `DailyContentSettingsScreen.kt:114-119` |
| 手动回复 = 5min 兜底恢复计时器（非用户定时发送；全仓无「定时发送」功能） | `ManualReplyScheduler.kt:19-48`；`ManualReplyWorker.kt:20-96`；启动重挂 `MainViewModel.kt:620-627` |
| 手动回复管线 = 常规私聊/群聊回复路径 | `ManualReplyWorker.kt:42-46`（resumePrivate/GroupReply） |
| ReplyTurn 耐用协议在 shared 层（随共享层自然移植） | `shared/src/commonMain/.../data/ChatRepository.kt:51,330-333` |
| A7 相关设置键全集 | `SettingsRepository.kt:169,506,510,514,534,612-620,778-812,827-835,1146,1166,1178` |
| 通知中心实现（iOS 需替换的 1 个类） | `notification/RhodesNotificationCenter.kt:21-58` |
| 无 BootReceiver / 无 WorkManager 定制 | grep 全仓 0 命中 |
| 审计口径一致（automation 9 文件 P1 替换） | `ios-port/audit/platform-coupling.md:15,30` |

## 与既有审计的差异/更正

- 无。audit-agent 的「automation 9 文件 → BGTaskScheduler/本地通知（P1）」结论与本预研一致；本预研补充了粒度结论：**BGTask 只是外壳，真正承载语义的是「前台补算队列 + 启动重挂」，且该层与 A1 群聊自动互动共享**。

## 开放问题（Phase 1 中段正式裁决需补充的实测数据）

1. **A1 群聊自动互动的 iOS 语义实测**：A1 先行落地延迟任务层后，真机验证 BGAppRefreshTask 实际获得率与前台补算延迟分布。若 A1 实现发现 iOS 延迟任务层成本远超 §4 估算（>2.5 代理日），需按触发条件公式重算（比值上限仍 ~65%，结论稳健）。
2. **启动重挂性能实测**：iOS 冷启动时扫描 pending MomentReply/ContextMaintenance 项的耗时（Android 对应 `MainViewModel.kt:620-639` 在 Dispatchers.IO 内）。若 iOS 启动预算（计划书要求冷启动耗时记录）被重挂扫描挤占，需将重挂改为懒触发（首次进入动态广场/会话时再补算）。
3. **moment_comment 管线在 iOS 的内存占用**：`buildCommenterMemoryContext` + 知识库 + 社交上下文三块拼接在 iPhone 上的峰值（Android 有 50s prompt_build 超时先例，`DailyContentScheduler.kt:52-57` 注释记载的线程饥饿事故提醒 iOS 前台补算需限并发）。
4. **记忆维护前移验证**：iOS 聊天主路径内联触发 `generateShortTermSummary`（对应 `ChatViewModel.kt:2776`）后，是否还需要独立 worker 时机；若内联已覆盖，ContextMaintenance 可简化为「仅失败重试 + 重建 pending 标记」。
5. **通知权限时机**：计划书「通知权限时机」要求与保留项无关（保留项无通知），无需新增实验；若 Phase 1 中段产品改口要主动私聊，需先重估 BGTask+LLM 超窗问题（LLM 生成 10–60s > BGTask ~30s 窗口，本预研 §3 已判不可行）。
6. **二期候选冻结确认**：Phase 0 评审确认「每日动态/主动私聊/免打扰/手动回复定时器」冻结后，`DailyContentSettingsScreen.kt` 整页与 `SettingsScreen.kt:122` 入口在 iOS 版隐藏（而非做空壳）。
