# 提示词盘点与原文抽取（基线 v1.14.27-android-baseline @ 3642d2c）

范围：`app/src/main` + `shared/src`（含 commonMain / androidMain）。口径：系统提示词模板、
提示词模块（behavior/protocol/runtime）、记忆流水线提示词、agent 人设（persona）、
各玩法（galgame/报纸/麻将/朋友圈/日记）提示词，以及提示词存储/校验/编辑器代码。

> **2026-11-26 重生成注记（绝区零世界观替换，task-11）**：shared `PromptTemplates.VERSION` 39→40
> （罗德岛/龙门币/干员 → 绳网/新艾利都/丁尼/代理人），7 件 strict 金样已按
> `shared/src/commonMain/kotlin/com/rhodes/privatechat/shared/prompt/PromptTemplates.kt`
> 重新抽取，字节级与 VERSION 40 模板一致：DISPATCH_START（L136）/ DISPATCH_PROGRESS（L159）/
> DISPATCH_ENDING（L185）/ PRIVATE_PROACTIVE（L561）/ MOMENT（L1120）/ MOMENT_COMMENT（L1166）/
> DIARY（L1228）。这 7 件文件头的 `Source:`/`Git:` 行已改指 shared 源文件与重抽取日期，文件名
> （含基线行号锚）不变；其余 107 件仍为基线抽取原文。下文「VERSION = 39」均指 Android 基线
> oracle（`android-oracle/` 未随本次替换修改），shared 侧现状为 VERSION 40。allowlist 7 条
> （TAG_* 行首空白豁免）与三档件数（27 strict + 7 canonical + 13 rawAnchor = 47）不变。

## 0. 总体结构

- 主模板库：`app/src/main/java/com/rhodes/privatechat/viewmodel/shared/PromptTemplates.kt`
  （1291 行，`VERSION = 39`，`get(type, mode)` 分发；24 个大型模板字面量）。
- 模块化片段：`viewmodel/PromptModuleDefaults.kt`（behavior / narrationProtocol /
  outputProtocol / runtime 四大模块，运行时拼进主模板）。
- 记忆三层：`viewmodel/shared/MemoryV2PromptTemplates.kt`（L1/L2/L3 + SCHEMA + DATA_BOUNDARY），
  由 `MemoryV2Pipeline.kt` 调用。
- 人设：`shared/.../shared/settings/AgentProfiles.kt`（内置 6 个 AgentProfile，含完整 prompt 人设字面量）。
- 自定义覆盖与持久化：`MainViewModel.kt:422-511` + `shared/settings/SettingsRepository.kt:1275-1327`
  （键 `prompt_<type>_<mode>`，版本键 `prompt_<type>_<mode>_version`），UI 为
  `ui/prompt/PromptEditorScreen.kt:73`。
- 占位符白名单：`data/PromptPlaceholderRegistry.kt:4`（allowed/recommended/runtimeKeys/unsupportedWarning）。

## 1. 提示词组装代码位置清单（file:line）

### 主模板与模块

| 位置 | 符号 | 用途 |
|---|---|---|
| `app/src/main/java/com/rhodes/privatechat/viewmodel/shared/PromptTemplates.kt:3` | `object PromptTemplates` | 主模板库（VERSION=39 @ :4，`get()` 分发 @ :6） |
| 同 :25/:38/:54/:68 | HUMAN_REALISM / TAG_PRIVATE_ONLINE / TAG_PRIVATE_OFFLINE / TAG_PRIVATE_DIRECTOR | 真人感 + 私聊 tag 模板 |
| 同 :81/:95/:109/:121 | TAG_GROUP_ONLINE / TAG_GROUP_OFFLINE / TAG_GROUP_DIRECTOR / TAG_GROUP_AUTO | 群聊 tag 模板 |
| 同 :136/:159/:185 | DISPATCH_START / DISPATCH_PROGRESS / DISPATCH_ENDING | 派送（主动消息）三阶段模板 |
| 同 :221/:339/:452/:561 | PRIVATE_OFFLINE / PRIVATE_DIRECTOR / PRIVATE_ONLINE / PRIVATE_PROACTIVE | 私聊四模式主模板 |
| 同 :615 | LEGACY_GROUP_TAG_TEMPLATES | 旧群 tag 模板（兼容） |
| 同 :744/:881/:1005 | GROUP_DIRECTOR / GROUP_AUTO / GROUP_ONLINE | 群聊三模式主模板 |
| 同 :1120/:1166/:1228 | MOMENT / MOMENT_COMMENT / DIARY | 朋友圈 / 评论 / 日记模板 |
| `app/src/main/java/com/rhodes/privatechat/viewmodel/PromptModuleDefaults.kt:4` | `object PromptModuleDefaults` | 提示词模块默认值 |
| 同 :5 | `behavior(type, mode)` | 行为模块 |
| 同 :185 | `narrationProtocol(mode)` | 叙述协议模块 |
| 同 :201 | `outputProtocol(type, mode)` | 输出协议模块 |
| 同 :335 | `runtime(type, mode)` | 运行时模块 |
| `app/src/main/java/com/rhodes/privatechat/data/PromptTemplates.kt:12` | `object PromptTemplates { }` | **已废弃空壳**（历史遗留，勿用） |

### 记忆流水线（MemoryV2）

| 位置 | 符号 | 用途 |
|---|---|---|
| `.../viewmodel/shared/MemoryV2PromptTemplates.kt:3` | `object MemoryV2PromptTemplates` | 记忆提取提示词 |
| 同 :4/:9/:17/:35 | DATA_BOUNDARY / `getL1(sourceKind)` / L2 / L3 | 数据边界声明 + 三层提取模板 |
| 同 :53/:76/:97/:116 | PRIVATE_L1 / GROUP_L1 / EVENT_L1 / DIARY_L1 | L1 按来源分型 |
| 同 :136 | SCHEMA | 记忆 JSON schema 模板 |
| `.../viewmodel/shared/MemoryV2Pipeline.kt:396` | L2 组装 `buildLevelMessages(L2, selected)` | L2 主题聚合 |
| 同 :422 | L3 组装 | L3 长期记忆 |
| 同 :444 | `buildPrivateMemoryContext` | 私聊记忆上下文 |
| 同 :490 | `buildPrivateChatMemoryContext` | 私聊记忆注入 |
| 同 :639 | `buildRelationshipPrivateMemoryContext` | 关系记忆上下文 |
| 同 :683 | `buildPrivateStableImpression` | 稳定印象 |
| 同 :695 | `buildPublicMemoryContext` | 公共记忆上下文 |
| 同 :709 | `buildOwnerMemoryContext` | 拥有者记忆上下文 |
| 同 :790/:799 | `getL1(sourceKind.name)` / `getL1("GROUP_CHAT")` | L1 调用点 |
| `.../viewmodel/ChatViewModel.kt:535` | 滚动摘要 prompt（内联字面量） | unifiedMemory 私聊滚动摘要 |
| 同 :406 | 存档整理 prompt（内联字面量） | ChatArchive 摘要 |
| `.../viewmodel/shared/UnifiedMemoryContext.kt` | `normalize` | 记忆上下文归一化 |
| `.../viewmodel/shared/MemoryRanker.kt:132` | `normalize` | 记忆排序归一化 |

### 私聊 / 群聊主链路

| 位置 | 符号 | 用途 |
|---|---|---|
| `.../viewmodel/ChatViewModel.kt:497` | `getPromptTemplate(type, mode)` | 私聊取模板（resolvePromptTemplate 覆盖链） |
| 同 :3297 | 私聊主模板组装（`getPromptTemplate("private", mode)`） | 私聊最终 system prompt |
| 同 :3335/:3384 | `PromptModuleDefaults.behavior/outputProtocol("private", mode)` | 模块拼接 |
| `.../viewmodel/GroupChatViewModel.kt:101` | `groupApplicationSafetyBoundary()` | 群聊安全边界字面量 |
| 同 :1248 | `getPromptTemplate("group", templateMode)` | 群聊主模板组装 |
| 同 :1379 | `getPromptModule("behavior", "group", mode)` | 行为模块拼接 |
| 同 :1421/:1427 | outputProtocol / narrationProtocol 拼接 | 群聊协议拼接 |
| `.../viewmodel/MainViewModel.kt:333-335` | 三个 lambda 注入 GroupChatViewModel | 模板/模块获取器 |
| 同 :422/:423 | `getPromptTemplate` | 管理 API（自定义覆盖） |
| 同 :497-511 | `getPromptModule` | 模块管理 API |
| 同 :578 | `defaultTemplate(type, mode)` | 默认模板入口 |
| 同 :980 | proactive 模板调用 | 私聊主动消息 |
| 同 :2351/:2557 | moment 模板调用 | 朋友圈生成 |
| 同 :2683/:2987 | moment_comment 模板调用 | 评论生成 |
| 同 :3418 | diary 模板调用 | 日记生成 |

### 人设（persona / AgentProfile）

| 位置 | 符号 | 用途 |
|---|---|---|
| `shared/src/commonMain/kotlin/com/rhodes/privatechat/shared/settings/AgentProfiles.kt:7` | `data class AgentProfile` | 客服 agent 数据结构（含 prompt 字段） |
| 同 :21 | `object AgentProfiles` | 内置 6 个 profile（`defaultId = "nuan"` @ :22；`all` @ :24-96，人设字面量 :30/:42/:54/:66/:78/:90） |
| 同 :99/:103 | `byId` / `routineAt` | 按 id 取 / 日程状态 |
| `.../viewmodel/AiSupportViewModel.kt:265` | 支持会话 prompt 组装（`agent.prompt`） | 客服会话 system prompt |
| `.../ui/support/AiSupportContract.kt:12` | 支持会话契约字面量 | 支持聊天契约 |

### 玩法 / 场景提示词

| 位置 | 符号 | 用途 |
|---|---|---|
| `.../galgame_full/DeepSeekClient.kt:11/:75` | SYSTEM_PROMPT（:75-82 字面量） | galgame 剧情生成 system prompt |
| `.../galgame_full/ProgressJudgeClient.kt:13/:17` | 进度判定 system/user 字面量 | galgame 章节进度判定 |
| `.../galgame_full/RdgPackage.kt:139` | H5_TEMPLATE | galgame H5 打包模板 |
| `.../newspaper/NewspaperModels.kt:167` | NEWSPAPER_SYSTEM_PROMPT | 报纸生成 system prompt |
| `.../viewmodel/shared/SleepPrompts.kt:4/:18` | CALL / WAKE | 睡眠陪伴来电/唤醒提示词 |
| `.../game/mahjong/AiChat.kt` / `AiSpeech.kt` | （无独立字面量，走主链路） | 麻将 AI 对白/语音 |
| `.../viewmodel/shared/GroupEmotionRules.kt:288` | `promptEmotions(...)` | 群聊情绪状态渲染（含字面量片段） |
| `.../viewmodel/shared/SharedUtils.kt:217/:710` | `addIfRelevant` / `compactTemplate` | 提示词压缩/相关片段拼接 |
| `.../viewmodel/shared/GroupTaggedReplyParser.kt` | （tag 解析，非提示词） | 群聊 tag 回复解析 |
| `.../viewmodel/shared/PlainGeneratedContentNormalizer.kt` | （归一化规则，非提示词） | 输出内容清洗 |

### 存储 / 校验 / 编辑器

| 位置 | 符号 | 用途 |
|---|---|---|
| `shared/.../shared/settings/SettingsRepository.kt:1275-1327` | `prompt_<type>_<mode>` 键族 | 自定义模板与版本持久化 |
| `app/.../data/backup/PortableSettings.kt:58-62` | `operator_prompt_slot_<id>_<type>_<slot>` | 备份包中的提示词槽位配置 |
| `app/.../ui/prompt/PromptEditorScreen.kt:73` | `PromptEditorScreen` | 提示词编辑器 UI |
| `app/.../viewmodel/MainViewModel.kt:428-490` | validate/promptWarnings/save/reset | 校验（占位符白名单）+ 保存/重置 |
| `app/.../util/ProblemChecker.kt:815` | `promptTemplateIntegrityProbe` | 模板完整性诊断 |
| 测试契约 | `app/src/test/.../MemoryPromptShapeTest.kt`、`PromptCacheLayerTest.kt`、`PromptHistorySafetyTest.kt` | 提示词形状/缓存/历史的 324 单测之一部 |

## 2. raw/ 原文抽取说明

- 抽取脚本：`handoffs/extract_prompts.py`（从 `git show v1.14.27-android-baseline:<path>` 读取，
  逐字抽取 Kotlin 三引号字面量；`${...}` 占位符原样保留；缩进按 trimIndent 近似还原）。
- 共抽取 **114 个** 字面量文件（含主模板、模块片段、人设、各玩法提示词及少量解析/归一化规则字面量），
  合计约 588 KB。
- 命名：`<源文件名>_L<起始行>[_<声明名>].md`；文件头注释含 源路径:行号 与 tag 信息。
- 多段拼接的模板（如 PromptTemplates 主模板 + PromptModuleDefaults 模块 + 记忆上下文）
  均已按段独立归档并保留 `L<行号>` 定位，可在 inventory 上表按行号还原拼接顺序。
- 完整清单见下（`raw/*.md <- 源文件:行` 全表）。



## 3. raw/ 全量清单（114 件）

| 归档文件 | 源文件:行 |
|---|---|
| `raw/PromptTemplates_L25_HUMAN_REALISM.md` | PromptTemplates.kt:25-34 |
| `raw/PromptTemplates_L38_TAG_PRIVATE_ONLINE.md` | PromptTemplates.kt:38-52 |
| `raw/PromptTemplates_L54_TAG_PRIVATE_OFFLINE.md` | PromptTemplates.kt:54-66 |
| `raw/PromptTemplates_L68_TAG_PRIVATE_DIRECTOR.md` | PromptTemplates.kt:68-79 |
| `raw/PromptTemplates_L81_TAG_GROUP_ONLINE.md` | PromptTemplates.kt:81-93 |
| `raw/PromptTemplates_L95_TAG_GROUP_OFFLINE.md` | PromptTemplates.kt:95-107 |
| `raw/PromptTemplates_L109_TAG_GROUP_DIRECTOR.md` | PromptTemplates.kt:109-119 |
| `raw/PromptTemplates_L121_TAG_GROUP_AUTO.md` | PromptTemplates.kt:121-134 |
| `raw/PromptTemplates_L136_DISPATCH_START.md` | PromptTemplates.kt:136-157 |
| `raw/PromptTemplates_L159_DISPATCH_PROGRESS.md` | PromptTemplates.kt:159-183 |
| `raw/PromptTemplates_L185_DISPATCH_ENDING.md` | PromptTemplates.kt:185-218 |
| `raw/PromptTemplates_L221_PRIVATE_OFFLINE.md` | PromptTemplates.kt:221-337 |
| `raw/PromptTemplates_L339_PRIVATE_DIRECTOR.md` | PromptTemplates.kt:339-450 |
| `raw/PromptTemplates_L452_PRIVATE_ONLINE.md` | PromptTemplates.kt:452-557 |
| `raw/PromptTemplates_L561_PRIVATE_PROACTIVE.md` | PromptTemplates.kt:561-611 |
| `raw/PromptTemplates_L615_LEGACY_GROUP_TAG_TEMPLATES.md` | PromptTemplates.kt:615-742 |
| `raw/PromptTemplates_L744_GROUP_DIRECTOR.md` | PromptTemplates.kt:744-879 |
| `raw/PromptTemplates_L881_GROUP_AUTO.md` | PromptTemplates.kt:881-1003 |
| `raw/PromptTemplates_L1005_GROUP_ONLINE.md` | PromptTemplates.kt:1005-1115 |
| `raw/PromptTemplates_L1120_MOMENT.md` | PromptTemplates.kt:1120-1164 |
| `raw/PromptTemplates_L1166_MOMENT_COMMENT.md` | PromptTemplates.kt:1166-1226 |
| `raw/PromptTemplates_L1228_DIARY.md` | PromptTemplates.kt:1228-1290 |
| `raw/MemoryV2PromptTemplates_L4_DATA_BOUNDARY.md` | MemoryV2PromptTemplates.kt:4-7 |
| `raw/MemoryV2PromptTemplates_L17_L2.md` | MemoryV2PromptTemplates.kt:17-33 |
| `raw/MemoryV2PromptTemplates_L35_L3.md` | MemoryV2PromptTemplates.kt:35-51 |
| `raw/MemoryV2PromptTemplates_L53_PRIVATE_L1.md` | MemoryV2PromptTemplates.kt:53-74 |
| `raw/MemoryV2PromptTemplates_L76_GROUP_L1.md` | MemoryV2PromptTemplates.kt:76-95 |
| `raw/MemoryV2PromptTemplates_L97_EVENT_L1.md` | MemoryV2PromptTemplates.kt:97-114 |
| `raw/MemoryV2PromptTemplates_L116_DIARY_L1.md` | MemoryV2PromptTemplates.kt:116-134 |
| `raw/MemoryV2PromptTemplates_L136_SCHEMA.md` | MemoryV2PromptTemplates.kt:136-164 |
| `raw/SleepPrompts_L4_CALL.md` | SleepPrompts.kt:4-16 |
| `raw/SleepPrompts_L18_WAKE.md` | SleepPrompts.kt:18-31 |
| `raw/PromptModuleDefaults_L187_in_narrationProtocol.md` | PromptModuleDefaults.kt:187-198 |
| `raw/PromptModuleDefaults_L207_in_privateOutputProtocol.md` | PromptModuleDefaults.kt:207-227 |
| `raw/PromptModuleDefaults_L237_in_privateOutputProtocol.md` | PromptModuleDefaults.kt:237-237 |
| `raw/PromptModuleDefaults_L247_in_privateOutputProtocol.md` | PromptModuleDefaults.kt:247-281 |
| `raw/PromptModuleDefaults_L283_in_groupOutputProtocol.md` | PromptModuleDefaults.kt:283-307 |
| `raw/PromptModuleDefaults_L309_in_groupOutputProtocol.md` | PromptModuleDefaults.kt:309-309 |
| `raw/PromptModuleDefaults_L316_in_groupOutputProtocol.md` | PromptModuleDefaults.kt:316-333 |
| `raw/PromptModuleDefaults_L336_in_runtime.md` | PromptModuleDefaults.kt:336-359 |
| `raw/PromptModuleDefaults_L360_in_runtime.md` | PromptModuleDefaults.kt:360-384 |
| `raw/AgentProfiles_L30.md` | AgentProfiles.kt:30-35 |
| `raw/AgentProfiles_L42.md` | AgentProfiles.kt:42-47 |
| `raw/AgentProfiles_L54.md` | AgentProfiles.kt:54-59 |
| `raw/AgentProfiles_L66.md` | AgentProfiles.kt:66-71 |
| `raw/AgentProfiles_L78.md` | AgentProfiles.kt:78-83 |
| `raw/AgentProfiles_L90.md` | AgentProfiles.kt:90-95 |
| `raw/ChatViewModel_L402_compactPrompt.md` | ChatViewModel.kt:402-402 |
| `raw/ChatViewModel_L406_prompt.md` | ChatViewModel.kt:406-406 |
| `raw/ChatViewModel_L535_in_generateShortTermSummaryLocked.md` | ChatViewModel.kt:535-567 |
| `raw/ChatViewModel_L567_in_generateShortTermSummaryLocked.md` | ChatViewModel.kt:567-597 |
| `raw/ChatViewModel_L1235_in_containsFirstPersonNarration.md` | ChatViewModel.kt:1235-1235 |
| `raw/ChatViewModel_L1236_in_containsFirstPersonNarration.md` | ChatViewModel.kt:1236-1236 |
| `raw/ChatViewModel_L1904_in_sendImageMessage.md` | ChatViewModel.kt:1904-1912 |
| `raw/ChatViewModel_L2542_prompt.md` | ChatViewModel.kt:2542-2573 |
| `raw/ChatViewModel_L3341_defaultTagProtocol.md` | ChatViewModel.kt:3341-3352 |
| `raw/ChatViewModel_L3360_in_remainingPromptBudgetMs.md` | ChatViewModel.kt:3360-3360 |
| `raw/ChatViewModel_L3372_in_remainingPromptBudgetMs.md` | ChatViewModel.kt:3372-3381 |
| `raw/ChatViewModel_L3431_in_reportHistory.md` | ChatViewModel.kt:3431-3437 |
| `raw/ChatViewModel_L3446_in_reportHistory.md` | ChatViewModel.kt:3446-3450 |
| `raw/ChatViewModel_L3607_in_hypnosisPromptBlock.md` | ChatViewModel.kt:3607-3625 |
| `raw/ChatViewModel_L3628_in_applicationSafetyBoundary.md` | ChatViewModel.kt:3628-3633 |
| `raw/ChatViewModel_L3725_in_buildRegenerateApiMessages.md` | ChatViewModel.kt:3725-3739 |
| `raw/ChatViewModel_L3807_prompt.md` | ChatViewModel.kt:3807-3812 |
| `raw/GroupChatViewModel_L101_in_groupApplicationSafetyBoundary.md` | GroupChatViewModel.kt:101-107 |
| `raw/GroupChatViewModel_L1330_in_remainingTurnBudget.md` | GroupChatViewModel.kt:1330-1336 |
| `raw/GroupChatViewModel_L1337_in_remainingTurnBudget.md` | GroupChatViewModel.kt:1337-1344 |
| `raw/GroupChatViewModel_L1418_in_remainingTurnBudget.md` | GroupChatViewModel.kt:1418-1505 |
| `raw/GroupChatViewModel_L1510_in_historyStats.md` | GroupChatViewModel.kt:1510-1624 |
| `raw/GroupChatViewModel_L1629_in_normalizeReply.md` | GroupChatViewModel.kt:1629-2091 |
| `raw/GroupChatViewModel_L2091_in_groupReplyFailureReason.md` | GroupChatViewModel.kt:2091-2158 |
| `raw/GroupChatViewModel_L2158_in_containsFirstPersonNarration.md` | GroupChatViewModel.kt:2158-2159 |
| `raw/GroupChatViewModel_L2159_in_containsFirstPersonNarration.md` | GroupChatViewModel.kt:2159-2257 |
| `raw/GroupChatViewModel_L2265_in_sendGroupImageMessage.md` | GroupChatViewModel.kt:2265-2428 |
| `raw/GroupChatViewModel_L2428_in_classifyGroupError.md` | GroupChatViewModel.kt:2428-2430 |
| `raw/GroupChatViewModel_L2430_in_classifyGroupError.md` | GroupChatViewModel.kt:2430-2432 |
| `raw/GroupChatViewModel_L2432_in_classifyGroupError.md` | GroupChatViewModel.kt:2432-2460 |
| `raw/GroupChatViewModel_L2466_in_untrustedGroupRepairInput.md` | GroupChatViewModel.kt:2466-2522 |
| `raw/GroupChatViewModel_L2522_in_extractGroupPlotSummary.md` | GroupChatViewModel.kt:2522-2530 |
| `raw/GroupChatViewModel_L2530_in_field.md` | GroupChatViewModel.kt:2530-2585 |
| `raw/GroupChatViewModel_L2585_in_extractSpeakerLines.md` | GroupChatViewModel.kt:2585-2589 |
| `raw/GroupChatViewModel_L2589_in_extractSpeakerLines.md` | GroupChatViewModel.kt:2589-2594 |
| `raw/GroupChatViewModel_L2594_in_extractSpeakerLines.md` | GroupChatViewModel.kt:2594-2690 |
| `raw/MainViewModel_L545_in_buildContentGenerationMessages.md` | MainViewModel.kt:545-551 |
| `raw/MainViewModel_L851_prompt.md` | MainViewModel.kt:851-864 |
| `raw/MainViewModel_L997_in_sendProactiveMessage.md` | MainViewModel.kt:997-1004 |
| `raw/MainViewModel_L1157_summary.md` | MainViewModel.kt:1157-1163 |
| `raw/MainViewModel_L1322_system.md` | MainViewModel.kt:1322-1338 |
| `raw/MainViewModel_L1339_user.md` | MainViewModel.kt:1339-1348 |
| `raw/MainViewModel_L1884_prompt.md` | MainViewModel.kt:1884-1909 |
| `raw/MainViewModel_L3092_in_buildMentionedCommentPrompt.md` | MainViewModel.kt:3092-3104 |
| `raw/MainViewModel_L3106_in_buildBystanderCommentPrompt.md` | MainViewModel.kt:3106-3119 |
| `raw/MainViewModel_L3582_prompt.md` | MainViewModel.kt:3582-3625 |
| `raw/AiSupportViewModel_L265_prompt.md` | AiSupportViewModel.kt:265-288 |
| `raw/AiSupportViewModel_L443_in_analyzeImage.md` | AiSupportViewModel.kt:443-443 |
| `raw/AiSupportViewModel_L453_in_currentSupportStatus.md` | AiSupportViewModel.kt:453-459 |
| `raw/UnifiedMemoryContext_L38_in_normalize.md` | UnifiedMemoryContext.kt:38-38 |
| `raw/UnifiedMemoryContext_L39_in_normalize.md` | UnifiedMemoryContext.kt:39-39 |
| `raw/UnifiedMemoryContext_L40_in_normalize.md` | UnifiedMemoryContext.kt:40-40 |
| `raw/UnifiedMemoryContext_L41_in_normalize.md` | UnifiedMemoryContext.kt:41-41 |
| `raw/MemoryRanker_L132_in_normalize.md` | MemoryRanker.kt:132-132 |
| `raw/SharedUtils_L217_in_addIfRelevant.md` | SharedUtils.kt:217-223 |
| `raw/SharedUtils_L710_in_compactTemplate.md` | SharedUtils.kt:710-710 |
| `raw/SharedUtils_L712_in_compactTemplate.md` | SharedUtils.kt:712-712 |
| `raw/SharedUtils_L714_in_compactTemplate.md` | SharedUtils.kt:714-714 |
| `raw/SharedUtils_L716_in_compactTemplate.md` | SharedUtils.kt:716-716 |
| `raw/DeepSeekClient_L30_in_context.md` | DeepSeekClient.kt:30-72 |
| `raw/DeepSeekClient_L75_SYSTEM_PROMPT.md` | DeepSeekClient.kt:75-82 |
| `raw/ProgressJudgeClient_L13_system.md` | ProgressJudgeClient.kt:13-16 |
| `raw/ProgressJudgeClient_L17_user.md` | ProgressJudgeClient.kt:17-33 |
| `raw/RdgPackage_L139_H5_TEMPLATE.md` | RdgPackage.kt:139-148 |
| `raw/NewspaperModels_L167_NEWSPAPER_SYSTEM_PROMPT.md` | NewspaperModels.kt:167-191 |
| `raw/AiSupportContract_L12.md` | AiSupportContract.kt:12-12 |
| `raw/ProblemChecker_L815_in_promptTemplateIntegrityProbe.md` | ProblemChecker.kt:815-815 |

## 2026-11-28 导演群聊重抽取（VERSION 40 → 41）

- 变更件（1 件 strict）：`PromptTemplates_L109_TAG_GROUP_DIRECTOR.md`
  改动主题：导演群聊中「用户不在场」。用户每条消息视为导演指令，成员按指令演出，不得把用户当作在场者（不对其说话、不在台词/旁白中描写其动作位置外貌情绪）。
- `PromptModuleDefaults_L187_in_narrationProtocol.md` **未改动**：narrationProtocol 为私聊/群聊共用，
  导演专属旁白约束改由 `GroupPromptAssembler` 追加【导演群聊旁白约束】块实现，故该金样字节保持不变。
- strict/canonical/rawAnchor 三档计数保持 27/7/13；`android-oracle` 基线章节的 VERSION 指未改动的 Android 原件。
