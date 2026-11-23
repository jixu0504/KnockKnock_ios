# Phase 1 规格卡评审：prompt-template / private-chat-engine / message-id-media

- 评审代理：reviewer ｜ 日期：2026-11-23 ｜ 方式：全部只读（read/grep/git show），未构建、未改动仓库与任何被审文件
- 评审对象：
  1. `ios-port/specs/prompt-template-spec.md`（T14）+ `handoffs/spec-prompt.md`
  2. `ios-port/specs/private-chat-engine-spec.md`（T15）+ `handoffs/spec-chat-engine.md`
  3. `ios-port/specs/message-id-media-spec.md`（T16）+ `handoffs/spec-id-media.md`
- 基线注意：三卡声明锚定 tag `v1.14.27-android-baseline` @ 3642d2c；但 GroupChatViewModel.kt 已被 T11（7cad292）改过（2877→2945 行，executeGroupTurn 抽取），ChatViewModel.kt 未动（仍 3829 行）。**ChatViewModel 行号与基线一致；GroupChatViewModel 行号按当前树（7cad292 之后）核对**（见修正 #2/#7）。
- **总结论：三张卡均 PASS-WITH-FIXES**（T14：1×P2+5×P3；T15：3×P2+1×P3；T16：2×P2+2×P3；另各有 lead 裁决回填项）。核心 oracle 与接口互洽性成立，修正后即可按推荐波次开工。

---

## 1. T14 prompt-template-spec —— PASS-WITH-FIXES

### a) oracle file:line 抽查（亲自验证 12 组，全部属实）

| 引用 | 验证结果 |
|---|---|
| `PromptTemplates.kt:4` VERSION=39；`:6-22` get() 分发 | ✅ 14 surface（private×4/group×4/moment/moment_comment/diary/dispatch×3 + else ""）逐行吻合 |
| `PromptModuleDefaults.kt:5/185/201/335` 四模块 | ✅ behavior/narrationProtocol/outputProtocol/runtime 精确 |
| `SharedUtils.kt:16/112/229/585/599-603/637/765` | ✅ CachePromptLayering、buildNaturalRuntimeContext、正则 `\{\{([A-Z0-9_]+)\}\}`、applyTemplate、**空实现** requireNoUnresolvedTemplateTokens（注释「intentionally preserved」）、withLegacyPromptPlaceholders、beijingPromptTime 全部精确 |
| `MemoryV2PromptTemplates.kt:4/136` | ✅ DATA_BOUNDARY / SCHEMA |
| `SettingsRepository.kt:1280-1285/1296-1308/1316-1321/1329-1341/1346-1357` | ✅ 保存三键、resolvePromptTemplate 四分支、删除三键、getCustomPromptModuleOrNull（空串≠未自定义）、模块 version「写但不解析」全部精确 |
| `MainViewModel.kt:436/460/462/480/482-488/497-506/518-526/561-566` | ✅ 校验/警告/保存/模块 API/知识库召回超时 800-3000ms 全部精确 |
| `ChatViewModel.kt:497-499/3113-3116/3237/3295-3299/3334-3336/3387-3394/3430-3437` | ✅ 组装顺序 foundation→protocol→tagProtocol→narrationProtocol 与源码 `listOf(...)` 一致；非 custom 先 stripLegacy；「本轮参考资料」包裹精确 |
| `GroupChatViewModel.kt:93/1309/1408/1482-1491` | ✅ 当前树精确（见修正 #2 的 :101 问题） |
| `PromptEditorScreen.kt:104/114/127-132/134/157/230/496` | ✅ tab 结构、dispatch tab、role→模板/三模块、criticalPlaceholders、保存/警告/custom 读取 |
| `ProblemChecker.kt:815-833` / `PromptPlaceholderRegistry.kt:8-27` | ✅ tokenPattern 与 allowed/unsupportedWarning（legacy vs 未识别两种文案） |
| 金样算术：47 件 = 22+8+2+9+6 | ✅ 与 inventory.md §3 逐一核对（PromptTemplates 22、MemoryV2 8、SleepPrompts 2、PromptModuleDefaults 9、AgentProfiles 6）；114−47=67 内联片段数一致 |
| prompt_* 不在 PortableSettings 白名单 | ✅ 独立复核（白名单与动态键均无 prompt_*，仅有 operator_prompt_slot_* 槽位） |

### b) 验收可自动判定

验收 7 项均为断言型且可跑，**但两处命令的任务名错误**（见修正 #1）；金样门禁设计（Tier-1 字节比对 + allowlist.json + 重抽唯一修复路径）可执行且纪律正确。

### c)/d) 接口与资产一致性

- 与 golden/prompts（114 件/inventory 映射表）、audit/fields（prompt_* 不进备份）、checklist（A6 编辑器条目）一致 ✅。
- 与 T15 的边界切割有一处冲突：T15 依赖表把 buildApiMessages/组装编排划给 T14，而本卡「抽取落点」明确不迁组装编排（见 T15 修正 #8）。
- 范围映射诚实：dispatch 模板归档金样但实现随二期、galgame/麻将不迁 ✅。

### e) open questions

5 条全部列出且可裁决；**lead 已全部裁决**（prompt_* 不进备份照搬 Android；allowlist.json 形态通过；Clock 构造器注入；模块 version 写但不解析；dispatch tab 保留）——规格卡需回填为「已裁决」记录（修正 #6）。

### 修正清单（T14）

| # | 问题 | 怎么改 | 优先级 |
|---|---|---|---|
| 1 | 验收 #1/#7 与门禁设计命令使用 `:shared:jvmTest`——shared 无 jvm() target，该任务不存在（T10/T15 实证任务名为 `:shared:testDebugUnitTest`）；`./gradlew test` 亦宜写明 | 改为 `:shared:testDebugUnitTest`；回归命令写 `./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest` | **P2** |
| 2 | oracle 清单 `GroupChatViewModel.kt:101` 过期：当前树（post-T11 7cad292）:101 = unavailableHistoryReplyIds，groupApplicationSafetyBoundary 在 :103；而 :1309/:1408/:1482-1491 又是当前树行号，与卡头「基于 3642d2c」矛盾 | oracle 清单注明「GroupChatViewModel 行号以 post-T11 提交 7cad292 为准」，并把 :101 → :103 | P3 |
| 3 | 契约 19 引 `SettingsRepository.kt:1396-1399`「kotlinx-datetime 先例」——基线 tag 该处是 java.text/java.util（P0 泄漏原处，我已用 `git show 3642d2c:` 实证），该先例只存在于 post-T1 树（95e3166） | 注明「该先例引用 post-T1 提交 95e3166（commonMain 平台化修复）」或改引该提交 | P3 |
| 4 | oracle 清单写「24 个模板字面量」——实测 22 块 `"""`（与验收 #1 的 22 一致） | 改为 22 | P3 |
| 5 | 验收 #1 依赖 `sync_golden.sh`（尚不存在） | 标注「实现期新增脚本，与本卡同批提交」 | P3 |
| 6 | lead 裁决未回填（5 条 open questions 全部已裁决，其中契约 15/20 已按裁决写对） | 评审结论/开放问题节记录裁决结论，把「待裁决」改「已裁决」 | P2 |

---

## 2. T15 private-chat-engine-spec —— PASS-WITH-FIXES

### a) oracle file:line 抽查（亲自验证 16 组，全部属实）

| 引用 | 验证结果 |
|---|---|
| 预算族 `:105-108/:115-117/:124/:132-133/:139-140/:1344/:1447/:1546/:1160-1164/:1184-1202/:1251-1256/:1369-1373/:665/:689/:694/:1506/:1703-1716/:1493-1503` | ✅ 180s/240s/50s(min)/12s/120s/15s/2条600字/内容重试一次+剩余<5s跳过/历史阶梯/重启清理 10s/会话选择 8s/400 上下文减半 max3 次/合并批次——逐行精确 |
| 入口 guard `:1311-1322` | ✅ 无 session Toast+return、空文本 return、配置错误 Toast 精确 |
| 写入时机 `:1408-1422/:1465/:1429-1435/:1616-1624` | ✅ 乐观插入+同步持久化+读回校验、重试只重置 type、AI 整包事务落库+lease 校验 |
| 并发 `:185/:195-203/:1470-1476/:1482-1492/:2151` | ✅ aiMutex、requestId 载荷守卫、sessionGenerations、250ms 防抖、retryingMessageIds |
| 租约 `:1442-1453` | ✅ claim 之前先调度恢复 worker；claim 被拒 Toast「消息已保存，回复稍后自动补上」精确 |
| 取消/回滚 `:1697-1699/:1735-1739/:1743-1748` | ✅ CancellationException 透传、草稿恢复、已持久化置 send_failed |
| ChatStageTimeout.kt `:7-13/:15-33/:35-36/:38-62` | ✅ 异常类、withChatStageTimeout、用户文案、22 项阶段中文映射 |
| 结算/三模式 `:1122-1127/:1656-1667/:1216-1220/:1135-1143/:1600-1607` | ✅ 唯一硬契约（非旁白非空台词）、不结算分支、online 仅 dialogue、offline/director 首旁白末台词、turnStatus 收束/转题 |
| 恢复/变更 `:1796-1817/:2742-2747/:2083/:2175/:2393/:2457-2477/:800/:857/:448/:652/:1035-1047/:302-309` | ✅ resumePersistedReply/resumeImageReply/撤回/分段撤回/重新生成乐观锁/续写/重启/擦除/读档/会话切换/6 向过渡提示/存档容量 3/5/8/12/16/20 |
| AIService `:424/:666-668` | ✅ chat /「保留用于兼容」注释 + streamChat——「整包落库」结论成立 |
| ReplyTurnRepository `:67-76/:111-114` | ✅ cleanupOrphanedSucceededTurns（仅 succeeded）/retryableTurnIds |
| ReplyTurns.sq `:1-19/:21-22/:40-62` | ✅ 建表+唯一索引+claim/reserve/complete/isOwned/release/fail 全部在区间 |
| ManualReplyWorker `:21-46` + Scheduler `:22/:35` | ✅ 5 分钟延迟、claim 新租约、triggerKind 路由 |
| MessageParser `:137-157` | ✅ imageMsg 解析、`status=="failed"` → isSendFailed |
| GroupChatViewModel `:863/:1722`（当前树） | ✅ 两处 withTimeout(getNextMessageId) 精确 |
| `:104` CHAT_PAGE_SIZE=50 / buildApiMessages `:3034` / 导演块 `:3662` | ✅ 精确 |

### b) 验收可自动判定

8 项验收命令任务名正确（`:shared:testDebugUnitTest` + `:shared:iosSimulatorArm64Test`）✅；唯一实质问题在 #6 金样输入源（见修正 #9）。测试迁移清单 8 套 40 个 @Test 与实测文件计数一致（8+2+7+5+1+6+2+9=40 ✅）。

### c)/d) 接口与资产一致性

- 与 T16 互洽：本卡只定「ID 分配器接口语义」（oracle 引 MessageRepository.kt:296-305），T16 卡定义 allocate()/observeUsed() 且声明「T15 引擎是唯一合法消费者」——双向一致 ✅。
- 与 T10 互洽：reply_turns 不进备份 + 恢复依赖 cleanupOrphanedSucceededTurns 与 T10/fields.md a2 一致 ✅。
- 与 T14 有一处边界冲突（buildApiMessages 归属，见修正 #8）。
- 与 T13 衔接正确：R1 后台恢复方案与 lead 裁决（启动 reconcile+前台补算）一致。

### e) open questions

6 条全部列出；**lead 已裁决 5 条**（后台恢复、打字机 UI 层、376 口径、确定性 fake AI、群聊单独立卡），④ reply_turns 不进备份保持开放（reviewer 建议确认不进备份，理由：租约态随恢复重建、备份跨端无意义）。

### 修正清单（T15）

| # | 问题 | 怎么改 | 优先级 |
|---|---|---|---|
| 7 | 状态机图引「ReplyTurnStatus（ReplyTurn.kt:17-22）」双错：对象在 **:23-28**，且仅 4 常量（无 CANCELLED）；cancelled 终止态来自 SQL（ReplyTurns.sq:94-95、:99-100） | 更正行号，并注明「cancelled 仅存在于 SQL 转移路径，无 Kotlin 常量」 | **P2** |
| 8 | 依赖模块表把 buildApiMessages(:3034)/模式过渡提示/导演模式块(:3662) 划给 T14，与 T14「组装编排不迁」的边界冲突（T14 明确留给管线卡=本卡） | 改为：组装编排（buildApiMessages 等）归 **T15 自持**；T14 仅提供模板字面量/渲染原语/占位符注册表；契约 19「提示词内容归 T14」统一为「过渡提示**文案**归 T14，setMode 机制归本卡」 | **P2** |
| 9 | 验收 #6 用 `golden/sessions/chat-*.json`（3 份）作输入序列**不可行**：它们是验证清单 JSON（checks 字符串数组），不含消息数据 | 改用 T10 金样 `golden/backup/minimal-1.14.27.zip` 的 7 条消息作种子输入序列 + lead 已裁决的确定性 fake AI 回放；或先产出「输入序列+期望消息序列」对（R5 建议落地，实现代理产出、reviewer 复核） | **P2** |
| 10 | 「reply_turns 19 字段」实为 **17**（.sq 17 列；ReplyTurn data class 17 字段） | 改为 17 | P3 |
| 11 | lead 裁决未回填（5/6 条已裁决） | 写入裁决记录：R1 启动 reconcile+前台补算、领域层不依赖 BGTask；R2 打字机属 UI 层、领域层不承诺；R3 测试口径以实测 376 为准（324 作废）；金样回放用确定性 fake AI；群聊状态机单独立卡（下一批）。④ 保持开放 | **P2** |

---

## 3. T16 message-id-media-spec —— PASS-WITH-FIXES

### a) oracle file:line 抽查（亲自验证 12 组，全部属实）

| 引用 | 验证结果 |
|---|---|
| MessageRepository `:46-47/:167-168/:190/:194/:219/:225/:242-243/:285-289/:296-305/:307-312/:318-324` | ✅ idMutex+高水位、三入口+restore 抬水位、persisted 防回退 doc 注释、删除路径不动 persisted——全部精确 |
| ChatMessages.sq `:40-41/:75-77/:99-101` | ✅ getMaxId/insertMessage/insertMessageIfAbsent（INSERT OR IGNORE） |
| BackupMediaCollector `:17-23/:43-57/:59-69/:74-80`（另 :24-30/:71-72/:86 前评已验） | ✅ collect/skipped、referencedUris 六扫描面、ownedImageFile（scheme≠file 拒绝+ownedRoots 前缀）、mimeType |
| ImageUtils `:13-17/:24-33/:39-53` | ✅ img_<ts>_<hash>.jpg、空文件删除+「Never leave an empty owned image」、copyToCache |
| MainActivity `:170-176/:178-193/:197-203` | ✅ camera→copyChatImageToFiles（image_<ts>.jpg、**1920 缩放 JPEG 80** 实测在区间内）、http(s)/data 透传 |
| LocalAudioController `:44/:140-142`（另 :129/:135 前评已验） | ✅ voice_<UUID>.pcm、deleteAudio removePrefix |
| AvatarImage `:24-64`（前评已验证 :31-34/:46-52/:53-64） | ✅ 数字解析/AsyncImage/首字符回退 |
| AiSupportViewModel `:85/:215/:311/:328/:419-421` | ✅ 独立内存计数器 + 恢复修复——「独立 id 空间」结论成立 |
| GroupChatViewModel `:863/:1722`（当前树） | ✅ 两处 withTimeout(getNextMessageId) |
| GiftRecords.sq:4 / MainViewModel `:1478/:1505` | ✅ imageUri 列 + GiftRecord 走 getNextMessageId |
| ChatScreen `:383/:2749`、GroupDetailScreen `:122` | ✅ deleteAudio 调用群 |
| 「max_used_message_id 不在 PortableSettings 白名单」 | ✅ 独立 grep 复核 0 命中 |

### b) 验收可自动判定

10 项验收全部断言型、命令结构正确，**但任务名 `:shared:androidUnitTest` 不存在**（源集名 ≠ 任务名，实证任务名为 `:shared:testDebugUnitTest`，T10 工作记录与 T15 卡均如此）——见修正 #12。金样路径转换矩阵 9 行与 audit/paths.md 典型形态、T10 mediaId 公式、lead 裁决逐一相符 ✅。

### c)/d) 接口与资产一致性（重点核查项）

- **T16 落点口径 vs T9 契约 11**：T16 全卡统一采用 **Application Support/media**（风险 3 明示 task-16 描述「Documents/media」为笔误，取 T9 已批准口径）——与 lead 裁决「T9 口径胜出」一致 ✅。
- **avatar:N 冲突处理**：T16 契约 20 明示「内置头像不落 media 目录、值域保持数字串原样、不引入 avatar: 前缀」，并指出 T9 契约 11 的 `avatar:x → images/avatar_x` 仅为内部解析映射示例——与 lead 裁决「内置头像为 Bundle 资源」一致 ✅；**T9 文档的示例文案修正需另派单**（修正 #15）。
- **bg_***：保持 settings 动态键、值域改 media ID、`s:` 前缀不变——与 lead 裁决及 fields.md §a2 编码一致 ✅。
- **与 T10**：mediaId 路径派生公式原样保留、恢复执行器不感知分配器（下游自愈）、验收 #7 复用 BackupRestoreGoldenTestIos——与已批准 T10 卡风险 3/4 互洽 ✅。
- **与 T15**：allocate()/observeUsed() 接口与 T15「唯一合法消费者」表述互洽 ✅。

### e) open questions

7 项风险全部列出；**lead 已裁决 4 项**（落点、内置头像、bg_* 动态键、Bundle 资源打包），仍开放 2 项（头像无损压缩与否；孤儿媒体 GC 默认开关，建议随 A5 默认关）。

### 修正清单（T16）

| # | 问题 | 怎么改 | 优先级 |
|---|---|---|---|
| 12 | 验收载体段与 handoff §5 的 `:shared:androidUnitTest` 任务名不存在 | 改为 `:shared:testDebugUnitTest`（KMP androidTarget 单测任务名，T10/T15 实证） | **P2** |
| 13 | lead 裁决未回填（4/6 项已裁决） | 写入裁决记录：落点=Application Support/media（T9 口径胜出，task-16 描述笔误）；内置头像 Bundle 资源按数字解析、不落 media 目录；bg_* 保持 settings 动态键；打包=Bundle Resources（asset catalog 否）。仍开放：无损压缩、孤儿 GC | **P2** |
| 14 | handoff「21 条行为契约」= 11+9 = **20**（spec 编号 1-20 亦为 20） | 改为 20 | P3 |
| 15 | T9 `ios-data-base-spec.md` 契约 11 示例「avatar:x → images/avatar_x」需改为「内置头像为 Bundle 资源」（lead 裁决）——本代理不改他卡文件 | 请 lead 派单给 T9 卡维护者（或随本批评审结论一并记录） | P3 |

---

## 4. 三卡接口一致性总评（标准 c）

- T16 ↔ T15：分配器接口（allocate/observeUsed ↔ 唯一合法消费者）互洽 ✅。
- T16 ↔ T10：恢复自愈依赖关系互洽 ✅（T10 验收 #10 为回归载体，T16 验收 #7 反向覆盖）。
- T14 ↔ T15：**唯一冲突点 = buildApiMessages/组装编排归属**（T15 依赖表划给 T14，T14 明示不迁）——修正 #8 后即互洽；其余（replacements 键、渲染原语、占位符注册表）口径一致 ✅。
- 三卡与既有资产（golden/prompts 114 件、audit/paths §0/§4/§7、T9/T10 已批准卡、fields.md）一致 ✅。

## 5. 对实现波次顺序的建议（三卡落地次序）

1. **Wave 1 = T16（分配器+媒体仓库）先行**：它是 T15 引擎与 T10 恢复的共同下游依赖，且纯数据层、无 UI/网络依赖，10 项验收全部 commonTest 可跑，风险面最小。落点口径/头像/背景三处裁决已定，无阻塞。
2. **Wave 2 = T14（提示词管线）**：纯逻辑、金样 diff 门禁（Tier-1 47 件字节比对）是 Phase 1 最先可执行的 Gate 1 证据；T15 依赖其渲染原语，必须在前。
3. **Wave 3 = T15（私聊引擎）**：依赖 T14 接口 + T16 分配器；测试迁移（8 套 40 @Test）+ 新增 5 套状态机/超时/恢复/变更矩阵 + 金样回放（fake AI + minimal-1.14.27.zip 种子）；后台恢复本期只做「启动 reconcile+前台补算」（lead 裁决），BGTask 增强留待与 T13 蓝图合流。
4. **合流回归点**（三卡落地后一次跑齐）：Android `:app:testDebugUnitTest`（376 口径中的 324 基线）+ `:shared:testDebugUnitTest` + `:shared:iosSimulatorArm64Test`（T9 28 + T10 24+24 + 三卡新增）+ 金样 diff 门禁 + T10 恢复双路径回归。
5. **下一批规格卡**（lead 已裁决群聊单独立卡）：群聊状态机（GroupChatViewModel，注意 executeGroupTurn 结构、行号以 7cad292 为准）→ 记忆管线（依赖 T14 的 MemoryV2 模板层）→ A7 延迟任务层（T13 spec §4 蓝图）。

---

# 实现评审批1：T17/T18 + allowlist（追加于 2026-11-23）

- 对象：commit `df71a3b`（T17 MessageIdAllocator+MediaRepository）、`06f7fde`（T18 提示词管线+金样门禁）、`ios-port/golden/prompts/allowlist.json`（7 条）、`ios-port/golden/prompts/sync_golden.sh`。
- 方式：全部只读（git show / read / grep / python3 静态模拟），未运行 gradle（T19 构建中），未改动任何文件。
- **结论：四项全部 PASS（0 个 P1/P2，2 个 P3 观察项）。**

## 1. allowlist.json 逐件核查 —— PASS（7/7 成立，机制为插值而非漂移）

方法：用 Python 静态模拟测试两侧（金样侧 = raw 正文去 HTML 头 + `resolveDollarRefs`；实现侧 = shared PromptTemplates.kt 源字面量做 `$HUMAN_REALISM` 替换 + Kotlin trimIndent 语义模拟），逐件断言 strict/canonical/差异行内容。

| 条目 | strict | canonical | 差异行 | 差异是否仅行首空白 |
|---|---|---|---|---|
| HUMAN_REALISM 引用件（对照基准） | ✅ 逐字节 | — | 0 | —（自身严格通过） |
| L38 TAG_PRIVATE_ONLINE | ❌（预期） | ✅ | 13 | ✅ 全部 whitespace-only |
| L54 TAG_PRIVATE_OFFLINE | ❌ | ✅ | 11 | ✅ |
| L68 TAG_PRIVATE_DIRECTOR | ❌ | ✅ | 10 | ✅ |
| L81 TAG_GROUP_ONLINE | ❌ | ✅ | 11 | ✅ |
| L95 TAG_GROUP_OFFLINE | ❌ | ✅ | 10 | ✅ |
| L109 TAG_GROUP_DIRECTOR | ❌ | ✅ | 9 | ✅ |
| L121 TAG_GROUP_AUTO | ❌ | ✅ | 11 | ✅ |

- **机制复核（理由成立）**：TAG_* 模板用单 `$HUMAN_REALISM` 插值（实现 :52/:66/:79/:93/:107/:119/:134，共 7 处，与白名单 7 条一一对应、无遗漏）；Kotlin 插值先于 trimIndent，插入的 HUMAN_REALISM 行 0 缩进使外层 trimIndent margin=0 → 运行时保留源 8 空格缩进；抽取脚本按源缩进 dedent 掉了 8 空格 → 两侧仅行首空白差异、内容字节一致。「插值动态值差异」而非「实现漂移」✅。
- **完整性**：实现中 `$HUMAN_REALISM` 仅这 7 处（grep 复核）；MemoryV2 的 `$SCHEMA`/`$DATA_BOUNDARY` 12 处按测试设计走 strict（抽取 dedent 对它们为 no-op），与白名单注释声明一致，无需入白名单 ✅。
- **防宽放设计**：GoldenPromptTest 硬断言 anchors=13 + strict=27 + canonical=7 = 47（算术成立），白名单被静默放宽会使测试红，设计合格 ✅。
- **资源一致性**：commonTest/androidUnitTest 两份 raw 拷贝（各 114 件）与金样源 3-way sha256 逐字节一致（抽查 3 件 + 计数）✅；`HUMAN_REALISM` 金样与实现严格逐字节一致 ✅。

## 2. T17 实现 spot check（3 处）—— PASS

| 契约 | 实现位置 | 与 oracle 核对 |
|---|---|---|
| 分配公式三源 max 自愈 | `MessageIdAllocator.allocate()`（:48-60） | ✅ 逐行对齐 MessageRepository.kt:298-302：`fromDatabase=(MAX?:0)+1`、`fromPersisted+1`、`maxOf(内存,DB,persisted)`、`next+1`、仅更大时写回（:292-294）；settings=null 分量 0（:290）；DB 失败无 catch 向上抛；键名 `max_used_message_id` 一致 |
| observeUsed 抬高语义 | `:68-72` + 头注释 | ✅ oracle :168/:194/:225（仅抬内存）与 :243-244（抬内存+持久化，restoreMessage 实测确有 rememberMaxUsedMessageId）——实现为保守超集（发送路径早持久化），头注释如实声明且不影响任何 allocate 结果（persisted 只增不减），接受 |
| MediaRepository 语义 | `ingest/resolve/deleteAudio` + `MediaId.kt` | ✅ 远程 http(s)/data: 透传（MainActivity:197-198）；内置头像 1..147 原样（AvatarImage:31-34）；备份导入 local-image/<sha24> 直映射 restored_media（BackupMediaCollector:24-30）；失败回退原引用+空文件拒绝（ImageUtils:24-32）；deleteAudio removePrefix("file://")（LocalAudioController:140-142）；ID 校验 = T9 契约 11 断言集（空/`..`/`/` 开头/`://`）——全部对齐 audit/paths.md 口径 |

## 3. T18 实现 spot check（3 处）—— PASS

| 契约 | 实现位置 | 与 oracle 核对 |
|---|---|---|
| 模板分发 14 surface | shared `PromptTemplates.get()` | ✅ 与 oracle PromptTemplates.kt:6-22 逐行一致（else ""），VERSION=39 保留，22 个字面量齐 |
| 存取四分支 | `PromptResolver` 接口 + `PromptEditor` | ✅ 四分支语义留在 SettingsRepository.resolvePromptTemplate（oracle :1296-1304，此前已逐行验证）；PromptEditor 保存=校验+三键、恢复默认=删三键、模块空串=有意禁用不回落（oracle :1280-1334 语义）——本批未接线 settings 实现（与 MediaStoreProvider 同模式延后，注释声明清楚），可接受 |
| 渲染/校验/时钟 | `PromptRender.applyTemplate`、`PromptSyntaxValidator`、`PromptClock` | ✅ 单次 Regex 扫描、未定义 token 原样保留（oracle :585-592/:599-603 空实现语义）；校验错误文案与 oracle MainViewModel:436-460 逐字一致；时区 Asia/Shanghai + 时段映射（5-7 清晨…else 凌晨）与 oracle :765-771/:232-238 逐档一致；Clock 构造器注入符合 lead 裁决 |

## 4. 写域核查（标准 3）—— PASS

- `df71a3b`：18 文件全部在 `shared/src/{commonMain,iosMain,commonTest,androidUnitTest,iosTest}/…/domain/{data,media}/` + `shared/files/MediaStoreIos.kt`。**无 app/ 文件、无 shared/backup 包文件** ✅。
- `06f7fde`：9 个 commonMain prompt 文件 + 12 个测试 + 测试资源（raw×228 为两份 114、allowlist/inventory×2）。**无 app/ 文件、无 backup 包文件** ✅。
- 两 commit 均未改 SettingsRepository/MessageRepository（接线延后模式，见修正 #1）。

## 5. 金样门禁可复跑性（sync_golden.sh 静态核查）—— PASS

- `bash -n` 语法通过；`rsync`/`shasum` 本机可用；`ios-port/toolchain/env.sh` 存在；路径算术 `SCRIPT_DIR/../../../RhodesLink` 解析正确；两个目标目录已存在且各含 114 件（实测）✅。
- 任务名 `:shared:testDebugUnitTest`（已按上一批评审修正，与 T10/T15 实证任务名一致）✅；`--no-daemon` + pgrep 构建排队纪律 ✅；`.raw-manifest.sha256` 已生成且相对路径（./file）与脚本生成方式一致 ✅。
- 件数校验 114、`--sync-only` 干跑路径、哈希清单 tripwire（变化时提示 reviewer 确认）逻辑自洽 ✅。

## 6. 修正清单（均为 P3 观察项，不阻塞）

| # | 文件/事项 | 问题 | 怎么改 | 优先级 |
|---|---|---|---|---|
| 1 | T16 规格卡「MessageRepository.getNextMessageId() 改为委托 allocator」未在本批落地 | 同公式双实现短期并存（MessageRepository 内联逻辑 vs MessageIdAllocator），存在漂移风险 | 随 T15 引擎接线批一并收口：MessageRepository.getNextMessageId 改调 MessageIdAllocator（或至少新增一条「allocator 公式 == MessageRepository 公式」等价性 commonTest 防漂移） | P3（T15 批必做） |
| 2 | `sync_golden.sh` | JVM 资源目录（TEST_RES_JVM）件数未做 114 校验；`rsync` 无 cp 回退 | 两处均可加防御（非阻塞） | P3 |

**批内结论：T17/T18 实现与 oracle/规格卡对齐，allowlist 7 条全部成立且无遗漏，写域干净，金样门禁可复跑。可以进入 T19/T15 接线批。**

---

# 规格卡评审批2：T20/T21/T22（追加于 2026-11-23）

- 对象：`group-chat-engine-spec.md`（T20）、`memory-v2-spec.md`（T21）、`a7-delay-task-spec.md`（T22）+ 各自 handoff。
- 方式：全部只读（read/grep/sed），未构建、未改动任何文件。GroupChatViewModel 行号按当前树（post-T11 7cad292，2945 行）核对。
- **结论：三卡全部 PASS-WITH-FIXES**（0 个 P1；T20 1×P2+3×P3、T21 1×P2+1×P3、T22 3×P3）。oracle 精度极高（合计抽查 ~105 处 file:line，仅 2 处行号漂移、1 处统计数、1 处口径表述），接口互洽性成立，修正后可按建议波次开工。

## 1. T20 group-chat-engine-spec —— PASS-WITH-FIXES

### a) oracle 抽查（~40 处，全部属实；GVM 行号为 T11 后当前树）

- 函数入口：sendGroupMessage :817、executeGroupTurn :937、prepareTurn :1040、runModelTurn :1514、cancelGroupRequests :261、recallMessageSegment :393、restartGroupSession :598、deleteGroup :631、setAutoGroupChatEnabled :662、startAutoGroupChat :687、sendHiddenGiftMessage :1849、retryFailedMessage :1886、runScheduledAutoTurn :1933（wait :1935）、resumePersistedReply :2124、sendGroupImageMessage :2247、resumeImageReply :2862 —— grep 精确核对 ✅。
- 预算常数：210s(:123)/270s getter(:207-208)/prompt 60s(:121)/model 150s(:122)/write 15s(:119)/重启清理 2s(:120)/思考加成 60s(:131) —— 逐行精确 ✅；内容重试无条件一次(:1677-1698)、上下文超限 ≤6 次保最新 10(:1648-1671)、可选上下文 skip 阈值（剩余 prompt-2s、<1s 跳过 :997-1002）✅。
- 守卫/租约/成员：:822-832 四守卫、:1044-1055（createReplyTurn-if-missing + claim 拒绝 Toast「该消息正在恢复发送，请稍候」+ 重新调度）、:1062-1064 双复核+delay(250)、:1121-1129 名册 AppState→DB 回退、:1133-1134 muted 过滤、:1142-1154 release(+5s, group_members_unresolved)、:1155-1167 全员禁言系统消息 —— 全部精确 ✅。
- 独有面：GroupEmotionRules :19-28（6h 窗/10 字/60 字/12 人）、:183/:233/:288-305；GroupTaggedReplyParser :23-42/:44-47（resolveSpeaker null → 丢弃块含玩家「我」）/:48-102；GroupOutputBudget :17-27（5000=4096×1.22，zhipu/minimax/siliconflow/custom→null）；ChatStageTimeout :55-60 群聊 6 阶段中文名；GroupRuntimeContext :8-14 五别名家族、:15 runtimeKeys("group")；isDisplayableGroupReply :2149-2150；classifyGroupError :2492-2502；markGroupMessagesUndelivered :1919-1930；龙门币仅手动 :1783-1786；输出检查清单 :1567-1572 —— 全部精确 ✅。
- 支撑件：GroupAutoChatScheduler :24(5min)/:51/:80/:107；SettingsRepository :1195-1223(GroupAutoChatPlan)/:1230；NetworkModels :210/:281；SessionViewModel :83（group_<uuid>）；MainViewModel :1813（setGroupMode）；五个死助手 :2191/:2420/:2528/:2682/:2690-2691 —— 全部精确 ✅（R6「无调用点」的 grep 结论我复核确认）。

### b) 验收：11 项全部断言型、命令任务名正确（testDebugUnitTest/iosSimulatorArm64Test）✅；#9 群聊回放 fixture 的合成方案（ROLES 页种子 + 确定性 fake AI + reviewer 复核）与 T15 R5 裁决一致 ✅。

### c) 接口一致性（重点）

- **T20↔T15**：共性表逐项锚定「reply_turns 同一张表同一套 SQL + surface 参数化 + TurnStateMachine/ReplyTurnLease 复用 + 同 ManualReplyWorker + 同 2×600/250ms 合并机制」——与 T15 已批准卡的抽取落点（domain/chat 下 TurnStateMachine/ReplyTurnLease）互洽 ✅；群聊子包 domain/chat/group 理由充分（T11 已拆 executeGroupTurn 结构，沿用）✅。
- **T20↔T22（AutoChatPlanPort 分工）**：T20 定义 AutoChatPlanPort（纯状态机）+ AutoChatTriggerPort（触发回调），iOS 触发实现归 T22 DelayedTaskLayer —— 与 T22 §A-12 冻结接口一致 ✅；唯一待办是「实现顺序」需 lead 拍板（见修正 #2）。

## 2. T21 memory-v2-spec —— PASS-WITH-FIXES

### a) oracle 抽查（~35 处，全部属实）

- MemoryV2Pipeline：:95 L1 开关守卫、:104 sourceRefId 格式（`<session>:epoch<…>:<first>-<last>`）、:110-118 幂等/claim 失败、:923 合法空数组注释、:932-950 三条抛错规则、:1332-1334 MAX=5/2/1、:397 退避 [10s,60s,300s]、:397/:423/:793/:802 四处 50s、:533-537 三档预算、:607-633 注入选择、:714-731 候选池、:1239-1245 关系网亲密度、:871-879 **隐私强制覆盖**、:953-967 维护函数 —— 全部精确 ✅。
- 存储/向量/知识库：MemoryV2Repository :57/:21/:205（10min 租约）/:309/:380；PrivacyPolicy :12/:55-61；LocalHashEmbeddingGateway :5-37；EmbeddingConfigurationSignature :8-18；LocalVectorStoreGateway :108/:202；KB TextProcessor :18(5MB)/:21(80 重叠)/:70；KB IndexService :176/:246/:247-288/:359(15s)/:362(信号量 1)；KB RecallPolicy :5-11（3/3/60/1200/4500/1500/750 全数精确）/ :35-36 包裹文案；ContextBuilder :89/:211-215(0.16)；ChatViewModel :3101-3110（3 轮 query）/3113/3169/3176/3200/3236-3264；Enums :12；MemoryV2PromptTemplates :157（重要性四档）—— 全部精确 ✅。

### b) 验收：10 项断言型；#1 命令（sync_golden.sh + testDebugUnitTest）任务名正确 ✅；#2/#3 金样（recall/*.md、embedding_local.json）由实现期生成——生成来源须锁定（见修正 #5）。

### c) 接口一致性（重点）

- **T21↔T18（MEMORY_V2_CONTEXT/__KNOWLEDGE_BASE_CONTEXT）**：契约 16 的注入顺序（群聊近况→稳定印象→个人向量→关系网→公开→知识库）与 replacements 键值同 T18 金样（inventory/MemoryV2 8 件严格比对）及 T15 组装器（ChatViewModel:3113-3264 实测）互洽；「模板消费 T18 资产、零复制」✅。
- T21↔T22：维护函数本体归 T21、调度触发归 T22 —— 两卡双向一致 ✅。

## 3. T22 a7-delay-task-spec —— PASS-WITH-FIXES

### a) oracle 抽查（~30 处，全部属实）

- 双 Worker 四行惯用法：MomentReplyWorker :15-21（at 默认 0L、initialDelay 钳 0 :18、EXP 1min :19、CONNECTED :20、KEEP :21）、:27-28/:33；ContextMaintenanceWorker :15-21、:27-31、:39-41 —— 逐行精确 ✅。
- MainViewModel：:140 24h 窗口、:620-639 启动重挂三件套、:2833（10s+random×20s）、:2836-2838（SHA-256 id）、:2847-2859 arming 双通道、:2866-2874 UI 重试、:2877-2897（恢复护栏/claim 10min/4min 超时/成功判定）、:2907-2912 退避（≥4→fail；60s<<(n-1)∈[0,3] = 1/2/4/8min）✅、:2927-2936 落库、:2950/:2987/:3022、:3060-3066、:2781-2787 —— 全部精确 ✅。
- ChatViewModel :636-638（60s<<(n-1) 上限 5 → 1..64min）、:2211/:2216-2219（tryLock）/2222-2267/2776/2803-2808/2721-2732/2622/2052-2059（私聊回复通知既有路径）；GVM :479/:485/:2802/:2845（handoff 自称「预研 :478/:2734 与实测不符，已按实测记录」——我复核实测确为 :479/:2802 ✅）；ReplyTurnRepository :31-34/:41-44/:50-52/:54-57/:82-84/:86/:97-101；ChatRepository :346（limit 50）；DailyContentScheduler :205-211/:229-241（迟建铺开）；ManualReplyScheduler :20-25（5min）；GroupAutoChatScheduler :124-129 —— 全部精确 ✅。

### b) 验收：10 项断言型（fake clock 单测 + golden 文案 + Debug-only 深触发链 + 编译期隔离 grep），命令可执行 ✅。

### c) 接口一致性（重点）

- **T22↔T20**：AutoChatPlanPort/触发回调分工与 T20 R1 一致；§A-12 DelayedTaskChannel 四方法冻结并声明「不允许单方面改协议」✅；「DelayedTaskLayer 由 A1 先行落地」的实现顺序需 lead 确认（同 T20 修正 #2）。
- T22↔T13：直接引用 T13 实测结论（code=1、深触发 5/5、provisional 全链路）与产品文案 ✅；↔T7：拆分裁决与回退预案留痕完整 ✅。

## 4. 修正清单

| # | 文件 | 问题 | 怎么改 | 优先级 |
|---|---|---|---|---|
| 1 | T20 R4 | 「重跑 sync_golden.sh 校准行号」表述有误导风险：golden 文件的 L-行号是**基线快照**（3642d2c），sync 只复制不校准；实现者若从 post-T11 树重新抽取会破坏 oracle | 改写为：golden 行号/文件名保持基线快照不动；新增群聊金样必须从 baseline tag 重新抽取（extract_prompts.py 语义）；测试映射以声明名为准 | **P2** |
| 2 | T20 R1 + T22 §风险 5 | 实现顺序未定：DelayedTaskLayer 契约在 T22 §A，落地却声明由 A1 先行 | lead 裁决：A1 波次先落地 DelayedTaskLayer（契约以 T22 §A-12 冻结接口为准）+ AutoChatPlanPort，A7 波次接 B/C 胶水；T20 依赖表补一句引用 T22 §A-12 | P2（裁决项） |
| 3 | T21 验收 #2/#3 | 金样生成来源未锁定，存在「用迁移后代码生成金样」的自证风险 | 注明：recall/*.md 与 embedding_local.json 必须由 **baseline tag 3642d2c 的 Android oracle 代码**运行生成；lead 裁决 3 选「Android 运行时实测输出」并写进卡 | **P2** |
| 4 | T21 契约 17 | 「敏感词 15 个」实为 **18 个**（我数了 :55-61 列表） | 改为 18 | P3 |
| 5 | T22 数据节/模块信息 | `summaryAutoRetryEnabled` 引 SettingsRepository.kt:169，实测在 **:173**（:169 是 groupSummaryRawTailRounds） | 两处改 :173 | P3 |
| 6 | T22 §A 前言 | 「5 个 Scheduler 共用同一惯用法（…KEEP…退避…）」——GroupAutoChatScheduler:124-129 实测为 **REPLACE 且无退避** | 修正为「4 个（Moment/Context/Daily/Manual）KEEP+EXP 1min；GroupAuto 为 REPLACE 无退避（A1 自动互动语义不同）」 | P3 |
| 7 | T22 验收 #1 | 「宿主侧 `:shared:test` 等价」任务名不存在 | 改为 `:shared:testDebugUnitTest` | P3 |
| 8 | T20 验收 #1 | app 版与 shared 版 GroupRuntimeContextTest（各 18 @Test）并存，下沉后去重口径未写明 | 写明：合并为一份 shared commonTest 版（app 版删除），避免双份漂移 | P3 |
| 9 | T20 R6 | 五个死助手（:2191/:2420/:2528/:2682/:2690） | reviewer 确认：**YAGNI 不迁移**（oracle 无调用点，金样 diff 不受影响），请 lead 一并记录 | P3（建议已附） |

## 5. 实现波次建议（群聊/记忆/A7 的顺序与并行度）

1. **T20 群聊引擎先行**（与 T21 并行）：抽取沿 T11 已拆结构（executeGroupTurn/prepareTurn/runModelTurn），复用 T15 已批准卡的 TurnStateMachine/ReplyTurnLease/withChatStageTimeout（surface 参数化），风险最低、无新增平台化点（4 个 shared 群文件已纯 Kotlin，仅 GroupRuntimeContext 需 GroupPromptKeysPort 接口化）。T20 落地时**同时落地 DelayedTaskLayer**（契约以 T22 §A-12 为准，见修正 #2）。
2. **T21 记忆管线与 T20 并行**：写域不重叠（domain/memory + domain/knowledge 门面 vs domain/chat/group）；仅依赖 T18 已闭环的 prompt 资产与 shared 数据层，不依赖任何引擎；平台化点明确（java.text×5 处按 T18 PromptClock 先例、ConcurrentHashMap→platformSynchronized）；金样生成（baseline oracle 运行）前置完成。
3. **T22 A7 随后**：等待 T20 的 DelayedTaskLayer 落地后接 B/C 胶水（moment_comment 管线随动态广场移植、维护函数由 T21 交付）；本卡接口已冻结，可提前准备 fake-clock 单测骨架。
4. **合流回归**：三卡落地后一次性跑 Android 376 口径 + T10 双路径 24+24 + 金样门禁（prompt 47 件 + memory recall/embedding 新增金样）；构建串行纪律沿用（pgrep 排队）。
5. **下一批候选**：A2 群资料卡（T20 R7 边界）与 A5 备份核对（reply_turns/群自动计划进不进备份，T20 R9/T15 open④）在 Phase 1 收尾时交叉核对。

---

# 规格卡复核：chat-import-impl-spec（追加于 2026-11-23）

- 对象：`ios-port/specs/chat-import-impl-spec.md`（T30，31 条契约）+ `ios-port/golden/chat-import/`（4 金样 zip + make_chat_exports.py + README + sync_golden.sh）。
- 方式：全部只读核查（read/grep/sed + Python 解包验证 + 生成器实跑复现哈希；未改任何实现文件）。
- **结论：PASS-WITH-FIXES（0 个 P1；1×P2 + 3×P3，全部为文档滞后/行号漂移，不阻塞已在进行的实现）。**

## 1. 标准 a：oracle file:line 抽查（~35 处，仅 2 处漂移）

| 抽查组 | 结果 |
|---|---|
| OperatorPackage.kt :37-66/:68-100/:103-119/:135-139 | ✅ writer/reader/readCompatible/上限 16 条目+20MB+25MB 逐行精确；excludedCategories 五项与 :57 一致 |
| BackupRestoreScreen.kt :124/:185-188/:235-260/:305 | ✅ schemaVersion=1、selectedChatExport、CreateDocument("text/plain") 镜像入口、失败删 URI :252-254、选择器按钮 :305 |
| 9 个 .sq 引用（ChatSessions :19-26/:29/:38-46、ChatMessages :24(ORDER BY timestamp ASC,id ASC)/:74-77、ChatDisplayEvents :9-10/:18/:23/:29、ChatArchives :29/:41/:60/:66、Operators :32/:38/:58、Relationships :21/:42） | ✅ 全部精确 |
| MessageIdAllocator 引用 | ⚠️ 卡写 allocate :65-76 / observeUsed :80-87，实测 **:48-60 / :68-72**（语义正确，行号漂移，见修正 #2） |
| ChatRepository.kt:55（runRestoreTransaction）、RestoreDatabaseExecutor :69-74/:78/:163-164、ChatViewModel:349、BackupWireModels:183-199、BackupModels:16-34、BackupZip:26-72、OperatorPackageService :38-53/:112-130/:146-176、ExportModels:6-13、BackupFileWriter :13/:27/:46-79/:81-97/:133-141 | ✅ 全部精确 |
| BackupRestoreCoordinator.kt:121-124,137-138 | ⚠️ :121-124（CancellationException 清理）精确；**:137-138 实为 filterIssues 区域**（sessionIds/momentIds），与「取消清理」语义不符，见修正 #3 |

## 2. 标准 b：与已批准卡/实现一致性

- **T10 模式复用** ✅：复用 BackupZip（含 CRC/上限 1000/100MB/500MB）、BackupFileReader 校验结构（路径安全/去重/manifest 三元组/files 集合/size+sha256）、BackupFileWriter 写入序（conversation → media 排序 → manifest 最后）；**互斥断言已设计**（验收 #6：`BackupFileReader.validate(chat-text-v1.zip)` → Invalid「不支持的备份类型」——该错误文案我实测存在于 BackupFileReader.kt:61）✅；分类恢复用**新文件** BackupSelectiveRestoreExecutor/CoordinatorEx，不改 T10 现有 executor/coordinator（写域尊重）✅。
- **T17 复用** ✅：契约 14 用 MessageIdAllocator.allocate/observeUsed 重分配（三源 max 自愈，禁止沿用导出 id——T6 §1.3 跨设备碰撞论据正确）；运行时媒体 ID `media/import-<sha24>.<ext>` 为合法 MediaId 可被 MediaRepository.resolve 命中 ✅；明确**不**复用 T10 restored_media 暂存语义 ✅。
- **T6 决策对齐** ✅：4.0-6.5 代理日区间与决策文档成本分解（Android 1.0-1.5 + iOS 2.5-4.0 + 金样 0.25-0.5）吻合；v1 格式约定（manifest rhodes-chat/formatVersion=1/scope=conversation、conversation.json、mediaId=image/<sha256(content)>）与 `decisions/chat-import-可行性.md:161` 逐字一致 ✅。

## 3. 标准 c：安全/健壮性 —— 覆盖充分 ✅

- 坏 zip 4 变体（缺 manifest/formatVersion=2/`../` 不安全路径/篡改媒体字节）→ Invalid + DB/媒体零残留（验收 #4）✅；
- 路径穿越：复用 isSafeArchivePath + 重复路径拒绝（契约 8）+ 角色卡上限 16/20MB/25MB ✅；
- formatVersion 三层门禁（rhodes-chat/formatVersion/scope + conversation.formatVersion）各带明确中文 reason（验收 #5）✅；
- **与 BackupFileReader 格式互斥**（验收 #6）防两类容器混淆 ✅；
- 单事务 + 三层回滚（事务/新增媒体/无设置改动）+ 取消清理 + 幂等去重键（sha256 六字段精确归一，误合并风险已如实记录为已知限制）+ 媒体内容寻址写入 ✅。

## 4. 标准 d：金样夹具 —— 实测通过 ✅

- **确定性**：实跑 `make_chat_exports.py` 重新生成，4 个 zip 的 SHA-256 与 README 冻结值**逐字节一致**（178e7abb…/0872d3df…/629c61b5…/19c5f63c…，字节数 2207/2472/2183/25663 全对）✅。
- **自校验**：内置 verify 镜像 iOS ChatExportReader 契约 #1-#8，实跑 `[verify] 全部金样自校验 PASS` ✅。
- **格式一致性（实测解包 chat-image-v1.zip）**：条目序 conversation.json → media 排序 → manifest.json（最后）✅；manifest 13 字段与契约 2 一致、files 不含 manifest 自身且 == ZIP 条目集 ✅；conversation.json 字段序与契约 3 一致 ✅；`mediaId == "image/"+sha256(字节全 64hex)`、`archivePath == media/images/<sha24>.<ext>` 逐项断言通过（内容派生公式正确）✅；recordCounts 与消息/媒体/displayEvents 数一致 ✅。

## 5. 标准 e：lead 三点裁决 —— 已写入但未标注为「已裁决」（P2）

- 范围界定（风险 2：§D 仅分类门禁+v1 分页覆盖+自检，全类目字段级重放单列 T31）——卡内以「请 lead 裁决」表述，**lead 已裁决接受 §D 界定**，需回填；
- 跨端验证口径（风险 6/开放问题 2：生成器金样+writer 静态等价+assembleDebug 编译，不做真机端到端）——已按该口径执行（T30 已开工），需标注已裁决；
- 群聊合并（风险 1：v1 私聊确定性合并+群聊按导出 session.id 精确命中，成员模糊匹配二期）——内容与 lead 裁决一致，需标注已裁决；
- 另：评审结论节仍写「待评审/待批准」，与「已获 lead 批准并开始实现」的现状不符（文档滞后）。

## 6. 修正清单（按常规派回，不阻塞实现）

| # | 文件 | 问题 | 怎么改 | 优先级 |
|---|---|---|---|---|
| 1 | `chat-import-impl-spec.md` 评审结论 + 风险 1/2/6 | lead 三点裁决未回填（范围界定 §D→T31、跨端验证口径、群聊 v1 精确命中）且结论节仍「待评审/待批准」 | 结论节改「reviewer：PASS-WITH-FIXES（2026-11-23）；lead：批准（2026-11-23）」，三条裁决改「已裁决」并注明裁决内容 | **P2** |
| 2 | 契约 14/数据节 | MessageIdAllocator 行号漂移（allocate 实为 :48-60、observeUsed :68-72） | 改行号 | P3 |
| 3 | 边界「取消」行 | BackupRestoreCoordinator.kt:137-138 实为 filterIssues 区域，非清理语义 | 仅保留 :121-124（CancellationException 清理）作证据 | P3 |
| 4 | 验收 #16 | 「当前实测 376，以既有报告为口径」表述可再收窄 | 直接写「376（324 口径已作废，lead 裁决）」，引用 gate1 报告 | P3 |

## 7. 对实现期的提醒（不阻塞，供 T30 与后续 A5 卡留意）

1. 契约 17 去重键的「同毫秒同内容误合并」已列为已知限制（风险 5）——实现 handoff 应保留该记录，二期如加 segmentGroup/narration 入键成本极低（0.1 天）。
2. 契约 16/风险 7 的 `stateJson` 递归替换可能残留未知形态 id（仅影响存档回放上下文）——实现期用金样 chat-text-v1.zip（含 1 存档）覆盖验证。
3. 契约 28 的分类型恢复门禁修复的是 T10「只删不插」不对称——A5 完整实现时应把该语义回写 T10 规格卡备注，避免两卡口径分叉。
4. 验收 #15 的 Android 导出器 writer 与金样生成器「静态等价」审查应在 assembleDebug 通过后补一次 diff 级核对（字段序/清单/哈希/写入序四件套）。
