# 实现报告：记忆与印象系统 M0（T62–T67，v0.2.0 基线）

| 项 | 内容 |
|---|---|
| 依据 | `docs/审计-记忆与印象系统-0.2.0.md`（v1.1）＋ `docs/KnockKnock-记忆与印象系统升级项目计划书-v1.1.md` §6.2 / §7（M0） |
| 基线 | tag `v0.2.0`（HEAD `0e08a81`） |
| 本次范围 | **M0：T62 维护接线、T63 生命周期清理、T64 索引重建、T65 恢复语义、T66 诊断、T67 正确性修复（含死键盘点）** |
| 未包含 | T68 回放集与基线脚本；M1+ 的 schema v24+、混合检索、事实整合、印象实体（按计划书排在后续里程碑） |
| 执行方式 | 单代理顺序实现（写域不重叠，便于逐项复核）；每步用 `:shared:iosSimulatorArm64Test` 编译/回归 |

---

## 1. 逐任务改动

### T62 维护链路接线（M-02）

**问题**：`ContextMaintenanceTask` 的 5 个触发点在 iOS 无调用点；且即使入队，非重建分支只在
`state == "failed"` 时才生成摘要 → 滚动摘要永远为空。

- `ChatEngineBridge`：新增 `setReplyMaintenanceHook`（`ChatEngineBridge.kt:130`），在 `EngineEvent.ReplyStored`
  后 fire-and-forget 触发（`:978`）。
- `GroupChatBridge`：同样接入群聊回复（`GroupChatBridge.kt:129,412`）。
- `A7Glue`：新增 `onPrivateReplyStored` / `onGroupReplyStored`（`A7Glue.kt:219,222`），按会话 45s 节流后
  调 `onForegroundReplyCompleted` / `onGroupContextChanged`。
- `ContextMaintenanceTask`：非重建分支改为「failed 且关闭自动重试 → 跳过；退避未到 → 延后；其余（含 idle）→
  尝试生成一次」（重写 `runLocked` 的 else 分支，`ContextMaintenanceTask.kt:384-410`）。真实生成器在没有
  到期轮次时不会调用模型，只把状态写回 idle，因此不会产生额外费用。
- `IosAppGraph`：注入两个 hook（`IosAppGraph.kt:131-132`），lambda 内才触达 `a7Glue`，保持懒构建。

**效果**：回复后 → 维护入队 → 摘要生成/失败退避/失败窗口重试真正开始运转。

### T63 生命周期清理（M-08）

- `A7Glue.runHousekeeping()`（`A7Glue.kt:368`）：启动/回前台/BGTask catch-up 时机会执行，
  `cleanupIntervalMs`（6h）节流，独立协程执行（不拖慢 catch-up 与 BGTask 结算）。
- `CleanupRepository`：在原有软删之后增加「宽限期后物理删除」：`purgeExpiredMemoryItems(now - 30d)` +
  `deleteOrphanedMemoryLinks()`（`CleanupRepository.kt`）。
- SQLDelight：`purgeExpiredMemoryItems`（`MemoryItems.sq`）、`getAllMemoryLinks`（`MemoryLinks.sq`）。

### T64 索引重建（M-03 / M-04）

- `MemoryV2Pipeline`：
  - `indexHealth()`（`MemoryV2Pipeline.kt:893`）：按 `memoryIndexStates` 统计 ready/missing/stale/expired/inactive；
  - `repairMemoryIndex(batchSize, maxBatches, shouldCancel, onProgress)`（`:914`）：缺失 + 签名漂移两类分批重建，
    可取消、有限批次；按 `(createdAt, id)` 游标推进，**单条持续失败不会阻塞后续条目**，剩余量留到下次活跃继续；
  - 逐条写向量改为返回成功/失败（`saveMemoryItemToVector(...): Boolean`），以便统计失败数。
- SQLDelight：`getActiveMemoryItemsWithStaleVector`（`MemoryItems.sq`）——换嵌入模型后签名漂移的条目；
  重建循环使用 `...After` 游标查询（`MemoryItems.sq`）。
- 触发点：
  - 启动/回前台自检（`A7Glue.repairIndexIfNeeded`，6h 节流，前台批量 100×3）；
  - 恢复备份后置 `memory_index_rebuild_pending`（见 T65），下次活跃继续；
  - 用户入口：记忆管理页「重建记忆索引」按钮（`MemoryListView.swift` 的记忆体检面板）→
    `AppGraphClient.a7RebuildMemoryIndex()` → `A7Glue.rebuildMemoryIndex`（`A7Glue.kt:254`）。
- 体检文案：`A7Glue.memoryHealthLine`（`:273`）→ 记忆页显示 ready/missing/stale/expired 计数（无正文）。

### T65 恢复语义（M-03）

- `BackupFullRestoreExecutor`：不再把 L2/L3 强制改成 `archived`，保留备份中的原状态
  （`BackupFullRestoreExecutor.kt` 的 memoryItems 恢复分支）。
- `BackupFullRestoreCoordinator`：恢复完成阶段置 `settings.memoryIndexRebuildPending = true`
  （`REBUILDING_RUNTIME` 阶段），由 T64 的分批重建兜底；不在恢复事务里做嵌入。
- 文件级恢复（iOS 冷启动换入 `rhodes.db`，`IosAppGraph.kt:51`）：检测到换入成功同样置重建标记（`:59`）。
- 金样测试断言语义同步更新：`FullBackupGoldenTest` 现在断言「状态原样保留」且「恢复后已安排重建」。

### T66 诊断与降级可见（M-09）

- `IosAppGraph.installMemoryDiagnostics()`（`IosAppGraph.kt:67`）：安装日志 sink（`IosDebugLogger`，写入
  `Application Support/logs/rhodes.log`）与 operation sink（beginOperation / conversationStep）。
- `MemoryRecallEngine`：召回结束写「候选/过阈值/注入数/查询长度/耗时」；通道失败写降级原因；
  未配置向量服务时也记录（`MemoryRecallEngine.kt:59-96,130`）。**不写记忆正文或用户原文。**
- `MemoryVectorService`：签名漂移、查询向量不可用时记录降级原因（`MemoryVectorService.kt`），
  不再把「静默失忆」表现为「没有相关记忆」。
- token 计量：`MemoryV2Pipeline.recordTokenUsage`（`:128`）把 L1/L2/L3/摘要请求的 usage 累加到
  settings 的 `memory` 分类（总/日两个维度），消费统计页「记忆」一栏开始有数。

### T67 正确性与死键（M-10 / X-01 / X-06 / X-07）

- **证据下标对齐**：`MemoryParseRules` 新增 `parseMemoryItemsIndexed`（保留模型原始数组下标），
  `parsePromotionItems` 改用它（`MemoryParseRules.kt`）。解析丢弃非法项或去重后不再错位。
  新增两条矩阵测试（丢弃项前置、重复项去重）。
- **失败退避阶梯**：`ingestPrivateChat` / `ingestGroupChat` / `ingestDiary` 的失败路径改传真实
  `retryCount`（`retryCountOf`，`MemoryV2Pipeline.kt:82`），3 次封顶与 dead-letter 才真正生效。
- **token 计量**：见 T66。
- **死键盘点**：新增 `docs/记忆与印象-死键清单-0.2.0.md`，逐条给出证据与处置（本次接线 4 项，
  其余标注保留待 T86/T88 或 deprecated）。

---

## 2. 验证

| 项 | 命令 | 结果 |
|---|---|---|
| 共享层编译 | `gradle :shared:compileKotlinIosSimulatorArm64` | ✅ |
| 全量单测 | `gradle :shared:iosSimulatorArm64Test` | **608 tests / 8 failed**，8 条全部是基线已存在的 `KtorDarwinStreamingTest`（本地流式桩端口类，与本次改动无关；改动前同样 8 条失败） |
| 新增/更新测试 | 同上（过滤 4 个类） | ✅ `ContextMaintenanceLockTest`（新增 2 条）、`MemoryParsePromotionTest`（新增 2 条）、`MemoryPurgeIosTest`（新增）、`FullBackupGoldenTestIos`（更新 + 新断言） |
| iOS App 构建 | `xcodebuild -scheme RhodesLink -destination 'generic/platform=iOS Simulator'` | ✅ BUILD SUCCEEDED |

> 说明：本次未做真机长跑与「清空向量后召回率 ≥95%」实测（计划书 T64 验收项）；本机无真机连接，
> 且需要真实模型/嵌入配置。逻辑与构建已验证，性能与召回率留给 G0 基线（T68）实测。

## 3. 已知限制与交接

1. **滚动摘要只在有「超出保留尾窗的轮次」时生成**：默认 `privateSummaryRawTailRounds = 25`，
   即约 26 轮后才会产生第一条摘要（T62 验收「30 轮后非空」满足）。若要更短周期，属于产品参数（T86）。
2. **没有消息编辑入口**：iOS 目前无「编辑历史消息」UI，`onPrivateContextChanged`（重建路径）尚未接线；
   接线点已就绪，待消息编辑功能落地（T62 验收中的「编辑历史触发 rebuild」未覆盖）。
3. **记忆设置页 / 保留期 UI 未做**（T86/M4）；当前沿用现有 `clean_days_memory_items`（默认永久），
   清理逻辑只对「已过期」行生效——真正止住无界增长还需要 T86 的保留期默认值与 UI。
4. **印象系统本体未改**：本次仅让印象的上游（摘要/晋升前提）开始运转；`character_impressions` 实体、
   证据链、纠错/冻结属 M3（T81–T85），未开始。
5. **T68 未做**：没有回放集与指标脚本，因此 G0 基线报告仍缺；这需要在有模型路由的环境执行。
6. 8 条 `KtorDarwinStreamingTest` 基线失败未修（不在本里程碑范围），建议在 M1 前单独处置，避免掩盖新回归。

## 4. 建议的下一步（按计划书）

1. **T68**：回放集 v0（调参/保留拆分）+ 指标脚本，先拿到 G0 基线；
2. **M1（T69–T74）**：schema v24–v29、BM25/实体索引、两阶段检索、向量 BLOB；
3. **M2（T75–T80）**：L1 v3 事实结构 + 三级去重 + 整合 Worker —— 这是「长期印象真正可达」的地基；
4. 与本次改动直接衔接的遗留：T86 设置页（保留期/召回模式/索引重建入口归一）、T73 反馈三态。