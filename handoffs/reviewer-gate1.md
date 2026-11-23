# Phase 1 Gate 1 终审报告（reviewer）

- 评审代理：reviewer ｜ 日期：2026-11-23 ｜ 方式：全部只读核查（git show/log、grep、Python 源码扫描、test-results XML 解析），**未跑 gradle**（lead 全量门禁进行中，结果落 `ios-port/baseline/gate1-verify.txt`）。
- 对象：Phase 1 全部提交（06f7fde/368e29b/5ed811f/7847bc5/a9a3afd/66dbeb9/d8a38b2/0fd2fb9，另含已评 df71a3b）与六张规格卡验收落实。
- **总结论：Gate 1 四条款全部 PASS；接线批 B 的延后判定成立（独立复核）；Phase 2 可以启动，批 C 接线列为 Phase 2 首个专项。**

---

## 1. Gate 1 四条款逐条

### 1a. Android 原功能不回归 —— PASS

- `gate1-verify.txt`（lead 门禁运行中）已有 `assembleDebug EXIT=0`、`appTests EXIT=0`。
- test-results XML 实测：`app/build/test-results/testDebugUnitTest` = **39 文件 / 324 tests / 0 failures**（Python 解析 XML 交叉核对，与 324 口径一致）✅。
- 支持性事实：T26/T27 的 app 侧改动仅限 lead 授权的接线文件（提示词 3 文件+MemoryV2Pipeline 委托、ChatRepository/3 个死文件删除），且 MessageRepository 单公式改造带「24 步同序列等价性测试」（见 4.）。

### 1b. 核心提示词/记忆测试全绿 —— PASS

- **shared 双路径口径交叉核对**（源码 @Test 计数）：commonTest **474**（61 文件）+ androidUnitTest 0 + iosTest 28 → **JVM=474、iOS=502**，与 0fd2fb9 提交信息「shared 474/502」完全一致；历史口径 414/442（T24）、470/498（T25）与各波次新增例数（46、56）吻合 ✅。
- XML 现状：iOS `iosSimulatorArm64Test` = **55 文件 / 502 tests / 0 failures**（最新一轮全绿）；JVM 当前仅 1 个部分 XML（8 tests，lead 门禁进行中）——474 全量结果随 gate1-verify.txt 完成落地，静态口径已闭合。
- **金样门禁**：prompt 侧（评审批1 已实测）——raw 114 件三档比对（27 strict + 7 canonical + 13 rawAnchor = 47 件 Tier-1，件数硬断言），allowlist 7 条全部为「插值先于 trimIndent」的行首空白差异、内容字节一致；**memory 侧——反自证纪律成立**：`gen_golden.sh` 从 `git show v1.14.27-android-baseline` 取 Android oracle 源码 → 一次性 harness → 固定 fixtures → 生成 `recall/*.md` 11 件 + `embedding_local.json`（20×384 逐位）→ 删除 harness（harness 留档 `golden/memory/harness/`），`MemoryRecallGoldenTest` 8 例双路径逐字节比对 ✅。

### 1c. 共享层不依赖 Android Context/WorkManager/UI —— PASS

- 自写 Python 扫描 commonMain 全部 .kt：排除注释/块注释后，`import android./java./javax.` 与 FQ `android.*/java.*` 引用 **0 命中**（含 T1 平台化后的 SettingsRepository/AnchorRepository）✅。
- automation 包专项：impl-groups/impl-a7 均实测 `grep -r "BGTask|UNUserNotification|WorkManager" commonMain/.../automation/ → 0 命中`（T22 验收 #10）✅。

### 1d. A7 决策点完成 —— PASS

- 拆分裁决落实：DelayedTaskLayer（T23，§A-12 冻结契约、零平台依赖）+ MomentReply/ContextMaintenance 胶水（T25，56 例）双路径全绿 ✅。
- **32min oracle 修正已落**（d8a38b2）：oracle `ChatViewModel.kt:637` 为 `(failures-1).coerceAtMost(5)` → 封顶 **32min**（60s<<5）；实现 `SUMMARY_RETRY_MAX_SHIFT=5`、BackoffTest golden `1/2/4/8/16/32/32/32`，我复核 diff 与代码一致 ✅；另「第 4 次执行失败即终态」（oracle claim 时 attemptCount++，规格卡「第 5 次」为笔误）同批修正 ✅。

---

## 2. 六卡验收对账（每卡抽查 ≥3 项，均与实现 handoff 证据 + 源码交叉）

| 卡 | 抽查项 | 证据 | 判定 |
|---|---|---|---|
| T14 prompt | #1 金样 47 件三档+件数断言 | GoldenPromptTest（评审批1 已实跑 7 canonical+strict 基线） | ✅ |
| | #4 VERSION 迁移矩阵 | PromptVersionMigrationTest | ✅ |
| | #5 校验矩阵 | PromptSyntaxValidatorTest（与 oracle :436-460 文案逐字一致，已核） | ✅ |
| T15 chat-engine | #2 状态机转移矩阵 | ReplyTurnStateMachineTest 10×2（真实 SQL 直驱） | ✅ |
| | #3 超时矩阵 | ChatEngineTimeoutTest 12（含 240s 口径/内容重试/降级地板） | ✅ |
| | #6 金样回放 | ChatEngineGoldenReplayTest 3（zip sha256 绑定+7 条+确定性 fake AI） | ✅ |
| T16 id-media | #1-#10 全部 | handoff 逐条列测试名（并发 1000、恢复自愈 1000/5001、恢复后 allocate==8…） | ✅ |
| | #2 备份公式 | backupFormulas_matchAndroidBackupMediaCollector | ✅ |
| | #5 恢复自愈 | restoreSelfHeal 两场景 | ✅ |
| T20 groups | #2 tagged 矩阵 | GroupTaggedReplyParserTest 19 | ✅ |
| | #6 自动计划 | GroupAutoChatPlanTest 13（5min 租约/上限/release 保一轮） | ✅ |
| | #8 组装金样 | GroupPromptAssemblyTest 10（required+可选顺序/固定协议优先） | ✅ |
| T21 memory | #3 本地哈希金样 | embedding_local.json 20×384 逐位（oracle 生成） | ✅ |
| | #4 限额矩阵 | MemoryRecallMatrixTest 11（六桶数值与 oracle :533-537 一致，已核） | ✅ |
| | #6 隐私/删除 | MemoryPrivacyTest 3 + 强制覆盖（oracle :871-879 一致，已核） | ✅ |
| T22 a7 | #5 退避 golden | BackoffTest（32min 裁决后 1/2/4/8/16/32/32/32） | ✅ |
| | #4 双通道幂等 | MomentReplyIdempotencyTest（claim CAS 恰一评论） | ✅ |
| | #10 编译期隔离 | grep 0 命中（已实测） | ✅ |

## 3. 实现质量抽查（T17/T19/T23/T24/T25/T27 各 2 处，全部 PASS）

| 任务 | 抽查点 | 与 oracle 核对 |
|---|---|---|
| T17 | allocator 三源 max / MediaId 校验 | ✅（评审批1 已逐行核对 MessageRepository:296-305 / T9 契约 11） |
| T19 | claim 租约+Toast「消息已保存，回复稍后自动补上」（ChatEngine.kt:338-344，注释引 oracle :1446-1455） | ✅ |
| | 金样回放复用 T10 GoldenBackupFixture + BackupFileReader（无重复内嵌） | ✅ |
| T23 | GroupStageBudget 210s/60s/150s/15s/2s/5min/60s（GroupChatProtocols.kt:29-66） | ✅ 与 GVM:119-123/:24 逐值一致 |
| | GroupEmotionRules FALLBACK_WINDOW_MS=6h（:18） | ✅ |
| T24 | privateRecallBudget fast=(80,20,30,20,20)/deep=(260,100,100,40,40)/balanced=(150,50,60,30,30) | ✅ 与 oracle :533-537 逐值一致 |
| | 隐私强制覆盖（PRIVATE_CHAT→private、GROUP/MOMENT/COMMENT→public） | ✅ 与 oracle :871-879 一致 |
| T25 | MomentReplyTask 退避 60s<<(n-1).coerceIn(0,3)=1/2/4/8min + MAX_ATTEMPTS=4 | ✅ 与 oracle MainViewModel:2907-2912 一致 |
| | DelayedTaskLayer KEEP 语义（:113-114 文档 + :233 实现） | ✅ |
| T27 | MessageRepository → MessageIdAllocator 单公式（lazy 构造 :59、observeUsed×4 :180/:205/:253/:260） | ✅ 双公式已消除 |
| | MemoryDiagnostics MemoryOperationSink/installOperations 恢复（:27/:41） | ✅ |

## 4. 接线批 B 延后判定复核（独立判断）

- **B6（无 per-entry 验证信号）已实测成立**：`grep -rln "ChatViewModel\|GroupChatViewModel" app/src/test` = **0**——逐入口替换的唯一信号确为「编译通过」，对 3829/2945 行行为迁移是假绿，论断成立。
- **B2（双编排器租约争用）已实测成立**：ChatEngine.kt:73-86 与 VM:186-226 持有同构内存状态（sessionGenerations/pendingUserMessageIds/chatAiJobs/activeRequestBySession…），部分替换会造成双写者；「必须整组替换」的结论正确。
- **B1/B3/B4/B5**：EngineEvent 无诊断载荷、PromptAssembler 前置未就绪、allocator 必须单例（两实例=独立 Mutex 撞 id 窗口，论证正确；T27 已收口）、结算副作用映射未冻结——均与代码现状一致，无夸大。
- **判定：延后理由成立**。任务卡兜底条款（Step 1 完整 + 双端全绿 + 如实记录）已按约执行；批 C 计划 8 步有明确前置（步骤 0 单例 accessor 已由 T27 完成；步骤 3 先建 VM 测试替身再逐入口替换）。**不阻塞 Gate 1**（Gate 1 条款不含 VM 接线；Android 行为不变有 324/0 证据；提示词/记忆已单源化——app 侧为薄委托，净删 2993 行双实现）。
- 风险提示：批 C 必须作为 Phase 2 首个专项排期（2-3 人日+独立评审），并先满足 B6 测试替身前提，防止「批 C 无限延后 → 双实现回潮」。

## 5. 写域越界检查 —— PASS

- 8 个提交文件域汇总：T18/T19/T23/T24/T25 全部 `shared/src/{commonMain,commonTest,iosTest,androidUnitTest}`；T26（66dbeb9）app 4 文件 = 提示词 3 文件 + MemoryV2Pipeline（lead 授权接线目标）；T27（0fd2fb9）app 4 文件 = data/repository/ChatRepository + 3 个待删文件（授权收口）——与任务板写域声明一致 ✅。
- 唯一披露例外：T24 修改 `shared/knowledge/KnowledgeBaseTextProcessor`（单函数 `splitSentences` O(n) 修复 K/N look-behind 死循环）——handoff §3 已主动披露并给出语义等价论证，属最小必要修复，可接受 ✅。
- 当前工作树干净（git status 空）✅。
- 注意：`shared/memory/{AnchorSourcePolicy,MaintenancePolicy}.kt` 仍以 @Deprecated 薄委托形式保留（等 app 未迁移调用点改造后删除），非双实现，风险低——列入批 C 清理清单（P3）。

## 6. test-results 交叉核对（324/474/502）—— PASS

| 口径 | 来源 | 实测 |
|---|---|---|
| app 324 | test-results XML | 39 文件 / **324 tests / 0 failures** ✅ |
| shared JVM 474 | commonTest(474)+androidUnitTest(0) 源码计数 | ✅ 与提交信息一致；XML 全量待 lead 门禁完成（当前仅部分 8 例） |
| shared iOS 502 | commonTest(474)+iosTest(28) | ✅ 源码计数一致；**XML 已实测 55 文件 / 502 tests / 0 failures** |

## 7. 修正清单（全部 P3 观察项，不阻塞 Gate 1）

| # | 事项 | 建议 |
|---|---|---|
| 1 | `gate1-verify.txt` 完成后应含 shared 双路径计数 | 补 `sharedJvmTests EXIT=0 (474)` 与 `sharedIosTests EXIT=0 (502)` 行（或引用 XML 计数），供审计追溯 |
| 2 | `shared/memory/` 两个 @Deprecated 薄委托 | 随批 C app 调用点改造一并删除（当前为委托非双实现） |
| 3 | T23 历史注释含「BGTask」字样（commonTest） | T22 验收 #10 门禁限定 commonMain 即可；顺手清理注释避免误报 |

## 8. 对 Phase 2 启动的建议

1. **批 C 接线列 Phase 2 首个专项**（2-3 人日 + 独立评审）：步骤 0 已由 T27 就位（单例 allocator）；按 wiring-b §4 顺序执行，先建 VM 测试替身（B6 前提），恢复类入口 → 撤回 → 重新生成/续写 → sendMessage/sendImageMessage → PromptAssembler shim 切换，每步独立回退；群聊同构（自动互动最后）。
2. **真机验证尽早排**：T9 遗留（Keychain 宿主 app/真机、0→18 段旧库金样）、T13 遗留（BGTask 真实调度/provisional 升级弹窗）、T12 遗留（真机帧率/真实图片负载）——建议 Phase 2 前段合并为一次真机冒烟包。
3. **金样门禁体系化**：sync_golden.sh（prompt）+ gen_golden.sh（memory）并列纳入 CI 门禁清单，禁止手改金样（当前纪律已在实现中执行，建议固化为脚本断言）。
4. **A5 卡交叉核对**（Phase 2 数据底座卡）：reply_turns/群自动计划进不进备份（T15 open④/T20 R9）+ 恢复后启动 reconcile 闭环，与 backup-restore-minimal-spec 合流。
5. **iOS 壳波次衔接**：T22 验收 #9 双路径命令（前台命令通道 + Debug 深触发链）与 T13 产品文案随 iOS 壳波次落地；DelayedTaskChannel 的 Timer/BGTask 两个宿主实现按 T22 §A-12 接口接。
