# T18 实现 handoff：提示词管线（模板/变量/版本/编辑器模型）+ 金样 diff 门禁

> 代理：impl-prompt（T18）。规格卡：`ios-port/specs/prompt-template-spec.md`（21 条行为契约，评审 PASS-WITH-FIXES，lead 批准 2026-11-23）。
> 状态：**实现完成，JVM 路径待最后一轮全绿（与 T17 并行排队中），iOS 路径待跑**。git 不提交（按任务纪律）。

## 1. 实现清单（file:line 指向 RhodesLink/）

### 1.1 commonMain 新包 `shared/src/commonMain/kotlin/com/rhodes/privatechat/shared/prompt/`（9 文件）

| 文件 | 内容 | 与 oracle 的关系 |
|---|---|---|
| `PromptTemplates.kt` | 主模板库：`VERSION=39`、`get(type,mode)` 14 surface 分发（:6-22）；22 个模板字面量**逐字节**自 baseline 源文件复制（仅 package 与可见性改动）；`ArchivedTemplates`（:1303，见下） | oracle `app/.../viewmodel/shared/PromptTemplates.kt`（git diff 零变更，逐字复制） |
| `PromptModuleDefaults.kt` | behavior/narrationProtocol/outputProtocol/runtime 四大模块（:4-387 逐字复制）+ `SourceFragments`（金样锚点，见下） | oracle `app/.../viewmodel/PromptModuleDefaults.kt` 逐字复制 |
| `MemoryV2PromptTemplates.kt` | DATA_BOUNDARY/SCHEMA/L1×4/L2/L3（逐字复制；DATA_BOUNDARY/SCHEMA 改 internal 供金样比对） | oracle `app/.../viewmodel/shared/MemoryV2PromptTemplates.kt` 逐字复制 |
| `SleepPrompts.kt` | CALL/WAKE（二期候选，仅归档迁移） | oracle 逐字复制 |
| `PromptPlaceholderRegistry.kt` | allowed/recommended/runtimeKeys/legacyCompatibilityKeys/unsupportedWarning（契约 6/11） | oracle `app/.../data/PromptPlaceholderRegistry.kt` 逐字复制 |
| `PromptRender.kt` | 渲染引擎纯函数层：`applyTemplate`（单次扫描，契约 8）、`withLegacyPromptPlaceholders`（legacy 合成，契约 9）、`CachePromptLayering`（缓存分层，契约 10）、`buildNaturalRuntimeContext`（契约 12）、`compactTemplate`、`stripLegacyChatJsonInstructions`、`getTimeOfDay`、`GroupRuntimeContext`（group 别名去重，供 T15） | 移植自 oracle SharedUtils.kt:585-717、CachePromptLayering:16-88、GroupRuntimeContext.kt 全文；**两处平台化改动**：`putIfAbsent`→`getOrPut`/等语义改写（JVM 专有 API，Native 不可用；注释标明） |
| `PromptClock.kt` | `beijingPromptTime(clock: Clock = Clock.System, timestamp: Instant? = null)`：`yyyy-MM-dd HH:mm（时段）` Asia/Shanghai，Clock 构造器注入（契约 19，lead 裁决）；用 `dt.monthNumber`（0.7.1-0.6.x-compat 实证可用，T17 验证） | 替代 oracle SharedUtils.kt:765-769 的 java.util.Calendar 实现；先例 SettingsRepository.kt:1396-1399 |
| `PromptSyntaxValidator.kt` | `promptSyntaxErrors`（畸形语法阻断，契约 7）+ `promptWarnings`（未知/legacy 占位符 + 系统保留标签，契约 6） | 纯函数化移植 MainViewModel.kt:436-480 |
| `PromptResolver.kt` | `PromptResolver` 接口（settings 域实现，prompt 域不持有存储）+ `PromptEditor`（保存=三键/恢复=删三键/模块启停空串语义，契约 3/4/5/15/16） | MainViewModel.kt:422-578 管理 API 纯逻辑形态；`resolvePromptTemplate` 四分支本体在 settings 域（oracle SettingsRepository.kt:1296-1304，已存在于 shared，未改） |

### 1.2 金样锚点（关键设计决策，需 lead/reviewer 知晓）

金样 47 件 Tier-1 与运行时值有三类形态，比对方式相应分三档（GoldenPromptTest 内断言件数，防白名单静默放宽）：

1. **严格逐字节 27 件**：运行时值（模板常量/函数输出）与金样正文在「两侧对称去掉抽取脚本保留的首/尾空行」后逐字节相等。含 MemoryV2 的 `$SCHEMA`/`$DATA_BOUNDARY` 插值模板（L1×4/L2/L3）——实测运行时文本与金样逐字节一致（抽取脚本对这些文件未做 dedent，与 Kotlin「插值先于 trimIndent → min-indent=0 → 不 dedent」的运行值恰好一致）。
2. **白名单 canonical 7 件**（allowlist.json `leading-whitespace`）：TAG_PRIVATE_ONLINE/OFFLINE/DIRECTOR、TAG_GROUP_ONLINE/OFFLINE/DIRECTOR/AUTO——`$HUMAN_REALISM` 插值先于 trimIndent，运行时保留源 8 空格缩进，与抽取脚本 dedent 仅行首空白差异（规格卡白名单第③类）。比对前金样侧把 `$HUMAN_REALISM` 解析为迁移后的引用件文本；HUMAN_REALISM 自身走第 1 档严格比对（链式锁定）。
3. **源分片/归档锚点 13 件**：oracle 中以下字面量**在注释块里**（`/* Removed default ... */`，oracle :220-559 / :614-1118）或含 `${if...}` 源码插值片段——运行时不可达/不可直接比对：PRIVATE_OFFLINE/DIRECTOR/ONLINE、LEGACY_GROUP_TAG_TEMPLATES、GROUP_DIRECTOR/AUTO/ONLINE（7 件归档模板）+ outputProtocol 的 6 个抽取分片。迁移代码以 `internal object ArchivedTemplates`（PromptTemplates.kt:1303）/`SourceFragments`（PromptModuleDefaults.kt 尾部）承载与金样**逐字节相同**的锚点文本（`$` 用 `${'$'}` 转义保留源码形态），GoldenPromptTest 以 rawAnchor 严格比对。**运行时函数体仍是逐字移植的 oracle**（get() 分发 TAG_* 系列与基线行为一致，契约 1 有专项测试覆盖）。

### 1.3 金样门禁资产（ios-port/golden/prompts/）

- `allowlist.json`：初始 7 条 `leading-whitespace` 豁免（TAG_* 7 件，见上第 2 档），每条含 reason。**评审确认请求**：条目内容与原因已按规格卡第③类填写，请 reviewer 逐件确认后本门禁即可视为闭环；不接受测试代码内硬编码豁免（已实现为测试读取该文件）。MemoryV2 插值模板经实测走严格比对（不入白名单）。
- `sync_golden.sh`：`raw/ + allowlist.json + inventory.md` → 同步到 `commonTest/resources`（iOS bundle 用）**与** `androidUnitTest/resources`（KGP 不把 commonTest resources 合入 Android 单测 classpath，JVM 用）；114 件数校验 + sha256 清单（`.raw-manifest.sha256`）防手改；默认跑 `:shared:testDebugUnitTest --tests "*GoldenPrompt*"`，`--sync-only` 只同步。构建纪律：source env.sh + pgrep 排队 + `--no-daemon`。
- `ios-port/handoffs/predict_golden.py`：金样 vs 运行时字节差异**预测/复核工具**（模拟 Kotlin trimIndent + `$X` 插值求值顺序），reviewer 可用它复核 allowlist 分类；非构建路径依赖。

### 1.4 commonTest 测试（`shared/src/commonTest/kotlin/com/rhodes/privatechat/shared/prompt/`，全部 kotlin-test，双路径跑）

| 文件 | 用例数 | 覆盖 |
|---|---|---|
| `GoldenPromptTest.kt` | 2 | 验收 #1：Tier-1 47 件逐字节（22 strict + 12 canonical + 13 rawAnchor，件数有断言）；缺件 fail-fast 报告全清单；(b) 固定 Clock+fixture 渲染无残留 token |
| `PromptRenderTest.kt` | 18 | 验收 #3：单次扫描/未定义保原样/空值/"null"/legacy 合成与保底值/分层排序与中文标签/USER_CONTENT 恒空/别名族 |
| `PromptVersionMigrationTest.kt` | 14 | 验收 #4 + 契约 4/5/15/16：四分支迁移矩阵（真实 SettingsRepository + 内存 ObservableSettings）、三键保存/删除、模块空串=有意禁用、模块 version 写但不解析（lead 裁决） |
| `PromptSyntaxValidatorTest.kt` | 9 | 验收 #5：孤立 `}}`/未闭合 `{{`/空 body/body 含 `{}` `\r` `\n` 阻断；白名单外放行+warning；legacy 键区分文案；保留标签 |
| `PromptClockTest.kt` | 4 | 契约 19：上海时区+时段、零填充、Clock 注入可复现、timestamp 优先 |
| `MemoryPromptShapeTest.kt` | 1 | 第一批 39 例迁移之一 |
| `PromptCacheLayerTest.kt` | 31 | 第一批 39 例迁移（原 31 例，仅 imports/断言 API 适配） |
| `PromptHistorySafetyTest.kt` | 7 | 第一批 39 例迁移（shared.model.trimPromptHistory/readPrivateHistoryWindow，已存在于 shared） |
| `AgentProfilesTest.kt` | 2 | 迁移（AgentProfiles 已在 shared/settings 原地） |
| `GroupRuntimeContextTest.kt` | 20 | 迁移（GroupRuntimeContext 随 PromptRender.kt 移植） |

- 平台桥：`GoldenTestResources.kt`（commonTest expect + 门禁辅助）+ `androidUnitTest/.../GoldenTestResourcesJvm.kt`（classloader）+ `iosTest/.../GoldenTestResourcesIos.kt`（bundle 多候选路径 + pathForResource 兜底）。
- `PromptTestSettings.kt`（commonTest）：内存 ObservableSettings + `SettingsPromptResolver`（SettingsRepository→PromptResolver 适配，settings 域接口由调用方组装）。

## 2. 39 例迁移对照（第一批）

- **已迁移 39 例**：MemoryPromptShapeTest(1) + PromptCacheLayerTest(31) + PromptHistorySafetyTest(7)。**app 侧原用例未删除**（双跑对照，删除由 lead 统一裁决）。
- 追加迁移 24 例：AgentProfilesTest(2) + GroupRuntimeContextTest(20)（GroupRuntimeContext 为 prompt 分层逻辑，随本卡移植）+ AiSupportContractTest **未迁**（9 例，见遗留）。
- 对照结果（JVM）：迁移件在 commonTest 与 app 侧原用例同源（同一套断言、同一批被测逻辑的 shared 副本）；app 侧原用例依赖 app 模块未被本卡改动，仍在 app 侧全量跑。→ **最终对照以 lead 裁决的双跑汇总为准**。

## 3. 验证状态（全部完成）

- [x] `:shared:testDebugUnitTest` —— **全绿**：156 tests, 0 failures（含本卡 115 例 prompt 测试；GoldenPromptTest 2 例 47 件逐字节全过：27 strict + 7 canonical + 13 rawAnchor）。
- [x] `:shared:iosSimulatorArm64Test` —— **prompt 测试全绿**：115/115，0 failures（GoldenPromptTest 同样通过，iosTest 资源 reader 经 bundle 多候选路径取到金样）。全量 184 tests 中 8 例失败均为 T17 的 KtorDarwinStreamingTest（本地 mock SSE 服务器未启动，环境前置，非本卡范围）。
- [x] `sync_golden.sh` 复跑 —— 同步 114 件 + sha256 清单 + `:shared:testDebugUnitTest --tests "*GoldenPrompt*"` 通过（「金样 diff 门禁通过」）。
- 构建修复轮次：主修复 ≤3 轮（详见 §5 平台化差异与协作记录），另与 T17 交叉编译中断两轮（其 MediaId.kt value class 编译错误，已由其修复并验证）不计入本卡修复轮。

## 4. 遗留与冻结说明

1. **T15 接口冻结**：`buildApiMessages`（私聊/群聊 system prompt 最终组装）归 T15；本卡交付的组装原语：`PromptTemplates.get`、`PromptModuleDefaults.*`、`PromptRender.applyTemplate/buildCachePromptLayers/buildNaturalRuntimeContext`、`GroupRuntimeContext.build`（group runtime 组装）、`PromptClock.beijingPromptTime`、`PromptEditor`（编辑器语义）。契约 13/14 的拼接顺序测试（foundation+protocol+tagProtocol+narrationProtocol、边界块包裹）建议随 T15 的 PromptAssemblyOrderTest 落地（本卡未实现，因组装编排在 T15 写域）。
2. **Android 侧调用点改造清单**（迁移后由 lead 统一裁决执行，本卡未动 app/）：ChatViewModel.kt:497-499,3297；GroupChatViewModel.kt:93,101,1248,1309,1379,1408,1421-1491；MainViewModel.kt:333-335,422-578；PromptEditorScreen.kt:102,127-132；ProblemChecker.kt:815；AiSupportViewModel.kt:265（agent.prompt 用 shared AgentProfiles，已就位）；`app/data/PromptTemplates.kt` 空壳删除。
3. **AiSupportContractTest(9 例) 未迁移**：被测对象 AiSupportContract/AiSupportMessage/sanitizeSupportConversation 属客服支持链路（非提示词模板逻辑，金样 AiSupportContract_L12 只是红包正则），建议归支持/A8 卡迁移。
4. **金样覆盖缺口（建议 reviewer 复核）**：PromptModuleDefaults outputProtocol 的 `${if...}` 分支体（私聊 online/offline 分支 228-236/238-246、群聊 308/310-315）未被抽取脚本归档（无对应 raw 文件）；本卡以「函数体逐字复制 + 分片锚点 + 形状测试（PromptCacheLayerTest 31 例）」覆盖，如需逐字节锁定请从 baseline 重抽（`handoffs/extract_prompts.py`）。
5. **allowlist.json 7 条目**：第③类「行首空白」（TAG_* 7 件，理由为 `$HUMAN_REALISM` 插值先于 trimIndent 的求值顺序）；**请 reviewer 确认**（规格卡要求逐件确认后方可视为闭环）。
6. 诊断探针（契约 18，ProblemChecker.kt:815-833）与 Tier-2 渲染层金样（~67 件 app 内联片段）未在本卡范围——分别归诊断卡与各管线卡。
7. 性能：渲染为单次 Regex 扫描纯字符串（契约达标线 <5ms/次 未单独做微基准，可由 T15 组装器卡统一实测）。

## 5. 平台化差异总表（相对 Android oracle）

| 位置 | Android | iOS/共享实现 | 依据 |
|---|---|---|---|
| beijingPromptTime | java.util.Calendar + SimpleDateFormat | kotlinx-datetime + TimeZone.of("Asia/Shanghai") + Clock 注入 | 契约 19（白名单①） |
| MutableMap.putIfAbsent（2 处语义依赖 + 47 处保底） | JVM 专有 | getOrPut / 先判存在再 getOrPut（等语义） | Native 编译约束 |
| 模块 version 键 | 写但不解析 | 同（不启用模块级版本比较） | lead 裁决 |
| prompt_* 备份 | 不进备份 | 同（iOS 不新增白名单） | lead 裁决 |
| dispatch tab | 保留 | 编辑器 UI 状态留 app 层（本卡无 UI） | lead 裁决 |
