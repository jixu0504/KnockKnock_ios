# 规格卡：提示词管线（模板 / 变量 / 版本 / 编辑器模型）

> 基线：Android v1.14.27-android-baseline @ 3642d2c（只读 oracle）。所有 file:line 均基于该提交。
> 金样：`ios-port/golden/prompts/inventory.md` + `raw/`（114 件，~588 KB）。
> 范围纪律：本卡只定义「模板字面量 + 变量替换 + 版本/存取语义 + 编辑器校验模型」；记忆/知识库内容的**构造**归后续记忆管线卡，本卡只定义其注入接口（replacements 键）。

## 模块信息

| 项 | 内容 |
|---|---|
| 模块名 | 提示词管线（模板/变量/版本/编辑器模型） |
| 所属阶段 | Phase 1（P0-1，与 T14 对应） |
| 范围映射 | **A6**（完整提示词编辑器，功能对照清单-v1.md:100：查看/编辑/保存/恢复默认/启停模块/变量替换/实际生效预览）；**A1**（私聊/群聊主链路 system prompt 组装中的模板与替换部分）；**A3**（记忆管线 L1/L2/L3 提示词字面量——本卡只迁字面量与分发，注入编排归记忆卡）；二期候选（报纸/派遣提示词仅归档金样，不实现）；排除（galgame/麻将提示词不迁） |
| 对应 Android 源码 | 见下「oracle 文件清单」 |
| 依赖模块 | shared/settings（`resolvePromptTemplate` 等存取接口已在该域，SettingsRepository.kt:1270-1357）；shared/prompt 只依赖 kotlin 标准库 + kotlinx-datetime + 由调用方注入的 `Clock` |

### oracle 文件清单（只读）

| 文件（相对 RhodesLink/） | 作用 |
|---|---|
| `app/src/main/java/com/rhodes/privatechat/viewmodel/shared/PromptTemplates.kt`（1291 行） | 主模板库：`VERSION=39`@:4，`get(type, mode)` 分发@:6-22，24 个模板字面量（TAG_* / DISPATCH_* / PRIVATE_* / GROUP_* / MOMENT / MOMENT_COMMENT / DIARY） |
| `app/src/main/java/com/rhodes/privatechat/viewmodel/PromptModuleDefaults.kt`（387 行） | 可编辑模块默认值：`behavior`@:5、`narrationProtocol`@:185、`outputProtocol`@:201、`runtime`@:335 |
| `app/src/main/java/com/rhodes/privatechat/viewmodel/shared/MemoryV2PromptTemplates.kt`（165 行） | 记忆提取 L1×4/L2/L3 + `SCHEMA`@:136 + `DATA_BOUNDARY`@:4 |
| `app/src/main/java/com/rhodes/privatechat/viewmodel/shared/SleepPrompts.kt` | 陪睡来电/唤醒（二期候选，仅归档） |
| `shared/src/commonMain/.../shared/settings/AgentProfiles.kt` | 内置 6 个客服人设（`defaultId="nuan"`@:22，人设字面量 :30/:42/:54/:66/:78/:90） |
| `app/src/main/java/com/rhodes/privatechat/data/PromptPlaceholderRegistry.kt` | 占位符白名单：`allowed`/`recommended`/`runtimeKeys`/`unsupportedWarning`/`legacyCompatibilityKeys` |
| `app/src/main/java/com/rhodes/privatechat/viewmodel/shared/SharedUtils.kt` | 渲染引擎：`applyTemplate`@:585、`withLegacyPromptPlaceholders`@:637、`CachePromptLayering`@:16、`buildNaturalRuntimeContext`@:112、`buildCachePromptLayers`@:631、`stripLegacyChatJsonInstructions`@:606、`beijingPromptTime`@:765 |
| `shared/src/commonMain/.../shared/settings/SettingsRepository.kt:1270-1357` | `prompt_<type>_<mode>` / `prompt_<module>_<type>_<mode>` 存取、版本键、custom 标记、`resolvePromptTemplate`@:1296 |
| `app/src/main/java/com/rhodes/privatechat/viewmodel/MainViewModel.kt:422-578` | 编辑器管理 API：校验/警告/保存/重置/模块覆盖 |
| `app/src/main/java/com/rhodes/privatechat/viewmodel/ChatViewModel.kt:497-499, 3113-3167, 3237, 3295-3437` | 私聊组装（记忆上下文先构建→进 replacements→模板渲染→foundation/protocol/tagProtocol/narrationProtocol 拼接） |
| `app/src/main/java/com/rhodes/privatechat/viewmodel/GroupChatViewModel.kt:93, 101, 1309, 1408, 1482-1491` | 群聊组装 |
| `app/src/main/java/com/rhodes/privatechat/ui/prompt/PromptEditorScreen.kt:73-954` | 编辑器 UI（tabs :104-132、criticalPlaceholders :134、保存/重置 :157-230、custom 标记读取 :496） |
| `app/src/main/java/com/rhodes/privatechat/util/ProblemChecker.kt:815-833` | 模板完整性诊断探针 |
| `app/src/main/java/com/rhodes/privatechat/data/backup/PortableSettings.kt:6-75` | 备份白名单（证明 prompt_* 不进备份） |
| `app/src/main/java/com/rhodes/privatechat/data/ExportPayload.kt:49-59` | OperatorExport 含 privatePrompt/groupPrompt（角色包字段，与 settings 键无关） |
| 陷阱 | `app/.../data/PromptTemplates.kt:12` 是**已废弃空壳** object（历史遗留，勿用；真库在 viewmodel/shared） |

## 输入 / 输出 / 边界

- **输入**
  - 模板选择：`type` ∈ {private, group, moment, moment_comment, diary, dispatch}，`mode` ∈ {""（默认）, offline, director, online, proactive(仅private), auto(仅group), start/progress/ending(仅dispatch)}。未知 type/mode → 返回空字符串，不抛异常（PromptTemplates.kt:6-22）。
  - 模板文本：编辑器 TextField 原始文本（可含 `{{PLACEHOLDER}}`，白名单外占位符合法）。
  - 替换表：`Map<String,String>`（运行时资料：时间/用户资料/记忆/知识库/段数配置等）。
  - 持久化：settings 键族（见「数据与迁移」）。
- **输出**
  - 渲染结果：`system` 前缀（稳定层）+ `runtimeContext`（易变层）两段（CachePromptLayers，SharedUtils.kt:96）。
  - 编辑器 API 返回值：语法错误列表（`List<String>`，MainViewModel.kt:482-488 保存时返回 warnings）；校验/警告列表。
  - 副作用：settings 写入（save/reset）、诊断日志（DebugLogger）、无网络调用、无 DB 表。
- **边界与错误行为**
  - 未定义变量：替换后**保留原样**送达模型（用户自定义占位符按字面指令合法），不报错、不空替（SharedUtils.kt:585-592, 599-603）。
  - 空值/`"null"` 文本：`buildNaturalRuntimeContext` 将字面 `"null"`（不区分大小写）与空白视为空（SharedUtils.kt:115）；空块文案各 surface 不同（默认「暂无相关内容。」，SharedUtils.kt:116-117）；legacy 合成键空值回填 `"无"`（SharedUtils.kt:656-657）。
  - 语法错误：孤立 `}}`、未闭合 `{{`、空 body、body 含 `{ } \r \n` → 阻断保存（MainViewModel.kt:436-460）。
  - 性能边界：模板渲染为纯字符串操作（单次 Regex 替换，SharedUtils.kt:585-592）；知识库召回超时 800-3000ms 按 surface（MainViewModel.kt:561-566，归知识库管线卡实现）。
  - 并发：编辑器按 key 维护 dirtyKeys/textMap 会话状态（PromptEditorScreen.kt:99,146-155）；保存幂等（重复保存同值无副作用）。

## 行为契约（逐条，每条带 Android oracle 位置）

1. **模板分发**：`get(type, mode)` 按 (type, mode) 精确匹配到 14 个 surface，unknown → `""`；private 缺省 mode → `TAG_PRIVATE_ONLINE`，group 缺省 → `TAG_GROUP_ONLINE`（PromptTemplates.kt:6-22）。iOS 差异：无（纯逻辑）。
2. **库版本号**：全库单版本 `VERSION = 39`（PromptTemplates.kt:4），随发布升一；语义见契约 4。
3. **自定义覆盖解析（VERSION 迁移策略）**：`resolvePromptTemplate` 四分支——(a) `_custom=true` → 永远用已存自定义（升级不覆盖）；(b) 无 custom 且已存为空 → 用随包默认；(c) 无 custom 且 `_version < VERSION` → 视为旧随包默认，**升级为当前默认**；(d) 否则用已存（SettingsRepository.kt:1296-1304，version 键默认 0 @:1306-1308）。iOS 差异：无；这是「随包模板热修」通道，必须保留。
4. **编辑器保存语义**：保存 = 写 `prompt_<type>_<mode>` 值 + `_custom=true` + `_version=VERSION` 三键（SettingsRepository.kt:1280-1285；入口 MainViewModel.kt:482-488，先过语法校验 :436-460）。返回 warnings（未知占位符 + 系统保留标签）而非阻断（MainViewModel.kt:462-480）。
5. **恢复默认语义**：删除 `prompt_<type>_<mode>` 值 + `_version` + `_custom` 三键（SettingsRepository.kt:1316-1321；入口 MainViewModel.kt:490-492）。
6. **未知占位符策略**：白名单外的占位符**允许保存**（注释明确：留作未来运行时值/字面指令，MainViewModel.kt:429-431）；运行时未定义 token 保留原样（SharedUtils.kt:585-592、`requireNoUnresolvedTemplateTokens` 为空实现 @:599-603）。区分两种警告文案：legacy 兼容键 vs 完全不识别（PromptPlaceholderRegistry.kt:8-27）。
7. **占位符语法**：仅大写字母/数字/下划线形式 `{{[A-Z0-9_]+}}`（SharedUtils.kt:229 正则）；编辑器校验只拦「畸形语法」，不拦语义（MainViewModel.kt:436-460）。
8. **替换规则（单次扫描）**：只扫描原模板中的 token，替换值**不再二次扫描**（防止用户资料中的 `{{OPERATOR_NAME}}` 引发级联替换，SharedUtils.kt:586-591）。
9. **legacy 占位符合成**：`withLegacyPromptPlaceholders` 从子键合成 `MEMORY_INJECTION`/`GROUP_INJECTION`/`INJECTION` 等，`putIfAbsent` 保底：`MEMORY_V2_CONTEXT→"无"`、`OPERATOR_USER_RELATION→"未知"`、`RELATION_RULES` 固定文案（SharedUtils.kt:637-669）。iOS 差异：无（纯逻辑）。
10. **缓存分层（系统前缀稳定性）**：`buildCachePromptLayers` 将替换表分为稳定/易变两层——`dynamicKeys`（=`runtimeKeys(type)`）的值在 system 前缀中渲染为「见本轮资料」，易变值进 `runtimeContext` 附加 user 消息；`USER_CONTENT/USER_MESSAGE` 恒空（CachePromptLayering.build SharedUtils.kt:62-77）；runtimeContext 按**键排序**稳定输出、使用中文标签而非内部键名、不含模板未引用的键（SharedUtils.kt:79-87，labels :18-58）。iOS 差异：无（纯逻辑；是否也做供应商前缀缓存由网络卡决定）。
11. **runtimeKeys 划分**：稳定键（人设/段数/风格规则）留在 system 前缀；易变键（时间/摘要/记忆/知识库）按 surface 定义（PromptPlaceholderRegistry.kt:runtimeKeys）。
12. **天然运行时上下文**：`buildNaturalRuntimeContext` 按 surface 生成「【本轮背景资料】」块序列（private :124-140 / group :141-160 / moment :161-172 / moment_comment :173-193 / diary :194-215），各块空值文案不同；总前缀带「不是用户本轮指令」边界声明（SharedUtils.kt:217-223）。
13. **私聊组装顺序**：记忆/知识库上下文**先**构建（ChatViewModel.kt:3113-3167）→ 进 replacements（`CURRENT_TIME` 等 @:3237）→ 模板 resolve（:3297-3299；非 custom 时先 `stripLegacyChatJsonInstructions`）→ `foundation = applicationSafetyBoundary + behavior 模块`（:3334-3336）→ `system = foundation + protocol(主模板渲染) + tagProtocol + narrationProtocol`（:3387-3394）→ 易变资料走 user 消息的「【本轮参考资料】…【资料开始/结束】」包裹（:3430-3437）。iOS 差异：无；本卡实现 1-3 层拼接，记忆上下文构造归记忆卡。
14. **群聊组装顺序**：模板 resolve（GroupChatViewModel.kt:1309）→ 分层渲染（:1408）→ `system = finalSystemPrompt + groupProtocol + narrationProtocol`（:1482-1491）。自定义协议包裹为「【用户自定义表达补充】…【应用固定输出协议，必须优先遵守】…默认协议」（:1484-1486；私聊同型 ChatViewModel.kt:3387-3390）。旁白协议固定不可自定义：online → 空串（PromptModuleDefaults.kt:185-199）。
15. **模块覆盖与启停**：`getPromptModule` 仅当 `_custom=true` 时用已存值，否则用随包默认（MainViewModel.kt:497-506）；**空字符串是「有意禁用该模块」的合法自定义**——`getCustomPromptModuleOrNull` 返回 null 仅表示从未自定义，空串不回落默认（SettingsRepository.kt:1329-1334）。模块 version 键存在（:1346-1350）但解析**不使用**版本比较（custom 永不被升级替换）。
16. **模块保存/重置**：`savePromptModule` 写值+`_custom`+`_version`（SettingsRepository.kt:1336-1341；入口 MainViewModel.kt:518-526，校验同模板语法）；`resetPromptModule` 删三键（SettingsRepository.kt:1352-1357）。
17. **记忆模板分发**：L1 按来源 `PRIVATE_CHAT/GROUP_CHAT/MOMENT|MOMENT_COMMENT|COMMENT/DIARY` 分型，else → PRIVATE_L1（MemoryV2PromptTemplates.kt:9-15）；L2 :17、L3 :35 均为 `正文 + $SCHEMA + $DATA_BOUNDARY` 拼接；SCHEMA 字段结构与字段规则 @:136-164。这些模板**不可用户编辑、无 VERSION**。
18. **完整性探针**：启动诊断检查全部 session surface 的模板——blank custom 模板、空 token、花括号不平衡 → `check()` 抛错（ProblemChecker.kt:815-833）。iOS 应保留同等诊断（可与 iOS 诊断卡合并实现）。
19. **时间注入格式**：`CURRENT_TIME` = `yyyy-MM-dd HH:mm（时段）`，Asia/Shanghai，现为 `java.util.Calendar`（SharedUtils.kt:765-771，调用点 ChatViewModel.kt:3237）。**平台差异白名单**：iOS 用 kotlinx-datetime `TimeZone.of("Asia/Shanghai")`（SettingsRepository.kt:1396-1399 已有同模式迁移先例），并注入可替换 `Clock` 供测试固定时间。
20. **备份关系（现状即规格）**：`prompt_*`（模板+模块+version+custom）**不在** PortableSettings 白名单内 → 不进备份/恢复（audit/fields.md a3 已证；PortableSettings.kt:6-66 白名单与 :69-75 动态键均无 prompt_*）。备份中与提示词相关的仅有 `operator_prompt_slot_<id>_<type>_<slot>`（PortableSettings.kt:58-62，int 槽位配置，非模板文本）与角色包字段 `privatePrompt/groupPrompt/memoryInjection`（ExportPayload.kt:49-59）。
21. **编辑器 UI 状态边界**：tab 结构 = private/group（各含 role/behavior/protocol/runtime 四模块）+ moment/moment_comment/diary/dispatch/help（PromptEditorScreen.kt:104-132）；「role」tab 读写主模板，「behavior/protocol/runtime」读写模块（:127-132）；`criticalPlaceholders` 提示（:134-144）；dirtyKeys 追踪 + 全量保存 `saveAllEdited`（:157-230）。**UI 状态全部留在 app 层**（见「抽取落点」）。

## 数据与迁移

- **settings 键清单**（SettingsRepository.kt:1270-1357；存储介质 iOS = NSUserDefaults，对应 Android EncryptedSharedPreferences 的普通键区，无密钥内容）：
  | 键族 | 值类型 | 语义 |
  |---|---|---|
  | `prompt_<type>_<mode>`（mode 空则 `prompt_<type>`） | String | 主模板自定义文本 |
  | `prompt_<type>_<mode>_custom` | Bool | 编辑器显式保存标记（决定升级不覆盖） |
  | `prompt_<type>_<mode>_version` | Int（默认 0） | 保存时的 VERSION |
  | `prompt_<module>_<type>_<mode>`（module ∈ behavior/protocol/runtime；mode 空则无 `_<mode>` 后缀，@:1323-1324） | String | 模块自定义文本（空串=禁用） |
  | `prompt_<module>_<type>_<mode>_custom` / `_version` | Bool / Int | 同上 |
- **无 DB 表**；**迁移版本**：主模板 `VERSION=39`（PromptTemplates.kt:4）——升级策略见契约 3；模块无版本迁移（custom 永不被替换，契约 15）。
- **备份/恢复**：全部 `prompt_*` 键不进备份（契约 20）；恢复后自定义模板**不**跨设备恢复（用户自定义随本地丢失，与 Android 现状一致，差异白名单：无）。恢复侧唯一提示词相关处理是 `operator_prompt_slot_*` 槽位（PortableSettings.apply 按前缀解码回填，audit/fields.md a3）。

## 验收（必须可自动判定）

| # | 验收项 | 判定方式 | 判定脚本或命令 |
|---|---|---|---|
| 1 | **金样 diff 门禁（字面量层）**：迁移进 shared 的 47 件模板字面量（PromptTemplates 22 + MemoryV2 8 + SleepPrompts 2 + PromptModuleDefaults 9 + AgentProfiles 6）与 `ios-port/golden/prompts/raw/` 对应金样逐字节一致 | commonTest 读取 resources 下复制进的金样目录，按清单映射逐件断言 | `bash ios-port/golden/prompts/sync_golden.sh && ./gradlew :shared:testDebugUnitTest --tests "*GoldenPromptRenderTest*"`；iOS 侧同用例跑 `iosSimulatorArm64Test` |
| 2 | **金样 diff 门禁（渲染层）**：app 内联片段（~67 件 ChatViewModel/GroupChatViewModel/MainViewModel 等）在其所有者管线卡迁移时，以固定 fixture 替换表渲染后与金样比对 | 渲染级 diff（这些片段含 `${settings.xxx}` 内插，源码比对无意义） | 同一 `GoldenPromptRenderTest` 的 tier-2 段，fixtures 硬编码在测试内 |
| 3 | **变量替换矩阵**：已定义/未定义/空值/`"null"` 文本/legacy 键合成/大写正则边界/替换值不二次扫描 | 单元测试 | `./gradlew :shared:testDebugUnitTest --tests "*PromptRenderTest*"`（从 324 单测中 PromptCacheLayerTest 31 例 + 新矩阵移植） |
| 4 | **VERSION 迁移矩阵**：custom=true 不覆盖 / blank→default / 无 custom 且 version<default→default / version≥default→saved | 单元测试（fake settings store） | 同上（新增 `PromptVersionMigrationTest`） |
| 5 | **编辑器校验矩阵**：孤立 `}}`、未闭合 `{{`、空 body、body 含 `{}`/`\r`/`\n` → 阻断；白名单外占位符 → 放行+warning | 单元测试 | `PromptSyntaxValidatorTest`（移植 MainViewModel.kt:436-480 逻辑） |
| 6 | **组装顺序**：私聊/群聊 system = foundation+protocol+tagProtocol+narrationProtocol 顺序与边界块包裹 | 单元测试（固定 fixtures 断言片段次序） | `PromptAssemblyOrderTest`（移植 PromptHistorySafetyTest 7 例 + 新增） |
| 7 | **回归**：Android 侧原 324 单测全绿（本卡迁移后 Android 调用点改为 shared 包引用，原测试重定向） | JVM 测试 | `./gradlew testDebugUnitTest` |

### 金样 diff 门禁设计（详细）

- 金样目录：`ios-port/golden/prompts/raw/`（114 件，命名 `<源文件>_L<行>[_<声明>].md`，文件头含 Source/Decl/Git 注释）；映射表 = inventory.md §3 全量清单（114 行映射）。
- 同步步骤：`sync_golden.sh` 将 `raw/` 复制到 `shared/src/commonTest/resources/golden/prompts/raw/`（一次性复制，CI 校验哈希不变）。
- **Tier-1（47 件）**：测试内建 `templateKey → (raw 文件名, 渲染 lambda, fixture 替换表)` 映射；断言两件事：(a) 模板常量经 `trimIndent()` 规范化后与金样正文逐字节相等（占位符 `{{X}}` 原样比对，不替换）；(b) 用固定 Clock + fixture 替换表渲染后无残留 token。
- **差异白名单（仅允许这三类，必须在测试内显式声明）**：① 平台字段——`CURRENT_TIME/CURRENT_DATE/TIME_OF_DAY` 动态值（经注入固定 Clock 消除）；② 日期动态值（`YESTERDAY_DATE` 由 fixture 固定）；③ 抽取脚本 trimIndent 近似误差（仅允许行首空白差异，且必须先经 reviewer 确认后写入 `.allowlist` 文件，见下）。
- 白名单载体：`shared/src/commonTest/resources/golden/prompts/allowlist.json`，条目 `{raw: <文件名>, field: "leading-whitespace", reason: ...}`；测试读取该文件执行豁免，**不允许在测试代码里硬编码豁免**。
- **失败处理纪律**：任何字节差异的合法修复路径只有一条——从 baseline tag 重新抽取（`handoffs/extract_prompts.py`，基线 `v1.14.27-android-baseline @ 3642d2c`），禁止手改金样。
- 判定命令（KMP）：`./gradlew :shared:testDebugUnitTest --tests "*GoldenPrompt*"`；iOS 实机/模拟器同源 commonTest 由 `xcodebuild test` 覆盖。
- 边界用例：空 replacements、全部键缺失、金样文件缺失（测试应 fail-fast 报告缺件清单，而非跳过）。

### 性能

- 模板渲染（applyTemplate + 分层）：单次 Regex 扫描，目标 **< 5 ms/次**（纯字符串，无 IO）；测量：JVM 微基准（循环 1000 次均值）。达标线：< 5ms 均值。
- 完整私聊组装（不含记忆召回/知识库召回）：目标 **< 50 ms**（召回超时另计，见 MainViewModel.kt:561-566）。此指标归组装器卡实测，本卡仅声明渲染部分预算。

## 抽取落点建议

- **新包**：`shared/src/commonMain/kotlin/com/rhodes/privatechat/shared/prompt/`
  - `PromptTemplates.kt`（主模板库 + VERSION + get 分发）
  - `PromptModuleDefaults.kt`（四模块默认）
  - `MemoryV2PromptTemplates.kt`（L1/L2/L3/SCHEMA/DATA_BOUNDARY）
  - `SleepPrompts.kt`（二期候选，迁入仅归档）
  - `PromptPlaceholderRegistry.kt`（allowed/recommended/runtimeKeys/legacy/unsupportedWarning）
  - `PromptRender.kt`（applyTemplate / withLegacyPromptPlaceholders / stripLegacyChatJsonInstructions / CachePromptLayering / buildNaturalRuntimeContext / requireNoUnresolvedTemplateTokens）
  - `PromptClock.kt`（`beijingPromptTime` 的 kotlinx-datetime 重写，接受注入 `Clock`；参照 SettingsRepository.kt:1396-1399 的 TimeZone 用法）
  - `PromptSyntaxValidator.kt`（MainViewModel.kt:436-480 的校验/警告纯函数化）
  - 接口：`PromptResolver`（消费 settings 域的 resolvePromptTemplate / save / reset / isCustom——接口由 settings 域实现，prompt 域不持有存储）
- **留在 app（Android 侧不迁）**：`PromptEditorScreen` UI 状态（dirtyKeys/textMap/tab 状态/criticalPlaceholders）；ChatViewModel/GroupChatViewModel 的组装编排与内联片段（待各自管线卡迁移）；MainViewModel 管理 API（改为薄封装 shared 函数）；ProblemChecker 探针（或迁 shared 诊断模块，由诊断卡决定）；AgentProfiles 已在 shared/settings（原地不动，金样比对即可）。
- **Android 侧调用点改动面**（迁移后 diff 范围）：`ChatViewModel.kt:497-499,3297`、`GroupChatViewModel.kt:93,1309`、`MainViewModel.kt:422-578`、`PromptEditorScreen.kt:102,127-132`、`ProblemChecker.kt:815`——统一改为 `shared.prompt.*` 包引用；`data/PromptTemplates.kt` 空壳删除。

## 风险与开放问题

- **已知风险**
  1. `kotlinx-datetime 0.7.1-0.6.x-compat`（libs.versions.toml:17,58）的 Clock 迁移债：`beijingPromptTime` 现为 JVM 专属（`java.util.Calendar`），迁 shared 需 Clock 注入；0.7.x 中 `Clock` 是接口而非对象，测试固定时间需自定义 `Clock` 实现——纳入 Tier-1 测试设计（契约 19）。
  2. 324 单测迁移分批（39 个测试文件共 324 @Test）：**第一批随本卡** = MemoryPromptShapeTest(1) + PromptCacheLayerTest(31) + PromptHistorySafetyTest(7) + AgentProfilesTest + AiSupportContractTest + GroupRuntimeContextTest；**第二批随组装器卡** = DiaryGenerationOutcomeTest、MomentPublicationTest、ChatInteractionPolicyTest、TurnStateParsingTest、PlainGeneratedContentNormalizerTest。
  3. 金样 114 件中约 67 件是 app ViewModel 内联片段（含 Kotlin `${...}` 内插），Tier-1 字节比对不适用；需 Tier-2 fixture 渲染比对，且金样抽取脚本（`handoffs/extract_prompts.py`）对 trimIndent 的近似还原可能引入行首空白差异 → 进 allowlist 前必须 reviewer 逐件确认。
  4. 模块 version 键（`prompt_<module>_..._version`）在 Android 上**写入但不参与解析**（契约 15）——**已裁决：保持「写但不解析」oracle 行为**，iOS 不启用模块级版本比较。
- **待 reviewer/lead 裁决**（已全部裁决，见「评审结论」）
  1. `prompt_*` 自定义模板不进备份是 Android 现状（audit 已证）；iOS 是否保持？→ **已裁决：照搬 Android 现状**。
  2. allowlist.json 机制形态（金样文件侧声明 vs 测试资源声明）是否批准。→ **已裁决：通过**。
  3. `Clock` 注入位置：prompt 域构造函数参数（建议）vs 全局 service locator。→ **已裁决：构造器注入**。
  4. dispatch 模板（start/progress/ending）编辑器 tab 已在 Android UI 存在（PromptEditorScreen.kt:114），dispatch 玩法本身是二期候选——一期是否保留 dispatch 模板编辑器入口。→ **已裁决：保留**。
  5. `runtimeKeys` 分层在 iOS 端是否仍服务于「供应商系统前缀缓存」——若 iOS 网络层不做前缀缓存，分层逻辑仍需保留以维持双端输出一致（渲染结果必须一致，缓存只是 Android 优化）。→ **已裁决：照搬 oracle 分层逻辑，保证双端渲染输出一致**。

## 评审结论

- reviewer：**PASS-WITH-FIXES（2026-11-23）** —— 修正项：gradle 任务名统一为 `:shared:testDebugUnitTest`（已落实）。
- lead：批准（2026-11-23）。与本卡相关的裁决：
  1. `prompt_*` 自定义模板不进备份：**照搬 Android 现状**（保持不进备份，iOS 不新增 PortableSettings 等价白名单条目）；
  2. allowlist.json 白名单机制形态：**通过**（金样侧声明 + 测试资源加载）；
  3. `Clock` 注入落点：**构造器注入**（prompt 域渲染器/时钟函数以 `Clock` 为构造参数，不做全局服务定位器）；
  4. 模块 version 键：**保持「写但不解析」oracle 行为**（iOS 不启用模块级版本比较，自定义永不被升级覆盖）；
  5. dispatch 模板编辑器 tab：**保留**（二期候选不影响一期 UI 完整性）。
