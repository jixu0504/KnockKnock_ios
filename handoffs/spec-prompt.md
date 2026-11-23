# Handoff：T14 规格卡 P0-1 —— 提示词模板与变量替换

- 交付：`ios-port/specs/prompt-template-spec.md`（按规格卡模板 v1 全节填写）
- 状态：**待 reviewer 评审 / lead 批准**（评审结论节留空）

## 方法

1. 认领 task-14（rev 3 → claim → rev 4），写域仅 ios-port/{specs,handoffs}。
2. 只读遍历 oracle：主模板库 `PromptTemplates.kt`（VERSION=39 @:4、get() 分发 @:6-22）、`PromptModuleDefaults.kt`（behavior/narrationProtocol/outputProtocol/runtime）、`MemoryV2PromptTemplates.kt`（L1×4/L2/L3/SCHEMA/DATA_BOUNDARY）、`PromptPlaceholderRegistry.kt`（allowed/recommended/runtimeKeys/legacy）、渲染引擎 `SharedUtils.kt`（applyTemplate @:585、CachePromptLayering @:16、buildNaturalRuntimeContext @:112、withLegacyPromptPlaceholders @:637、beijingPromptTime @:765）、存取 `SettingsRepository.kt:1270-1357`、编辑器 `MainViewModel.kt:422-578` + `PromptEditorScreen.kt:73`、组装 `ChatViewModel.kt:3113-3437` / `GroupChatViewModel.kt:1309-1491`、探针 `ProblemChecker.kt:815`。
3. 交叉验证既有资产：`inventory.md`（114 件金样映射表）、`audit/fields.md`（备份白名单——确认 prompt_* 不在其中）、`功能对照清单-v1.md:100`（A6 PromptEditorRoute 条目）。
4. 与下一管线卡的边界切割：记忆/知识库**内容构造**归记忆卡；本卡只定义注入接口（replacements 键 MEMORY_V2_CONTEXT / __KNOWLEDGE_BASE_CONTEXT 等 + 组装顺序契约 13/14）。

## 关键发现（对实现卡最重要）

1. **占位符语法是 `{{NAME}}` 双花括号**（正则 `\{\{[A-Z0-9_]+\}\}`，SharedUtils.kt:229），金样文件头注释里写的 `${...}` 是抽取脚本的陈旧措辞，勿被误导。
2. **VERSION 迁移四分支**（SettingsRepository.kt:1296-1304）：custom 标记 → 永不覆盖；blank → 随包默认；无 custom 且 version<39 → 升级为默认；否则用已存。版本键默认 0。
3. **模块「启停」= 保存空字符串**：空 custom 是「有意禁用」，`getCustomPromptModuleOrNull` 只在「从未自定义」时返回 null（SettingsRepository.kt:1329-1334）——这是 checklist 里「启停模块」的真相。
4. **模块 version 键写入但解析不使用**（MainViewModel.kt:497-506 只看 custom 标记）：Android 上模块随包升级**不覆盖**任何自定义——iOS 不得擅自加版本比较（已列入裁决项 4）。
5. **自定义模板不进备份是 Android 现状**：PortableSettings 白名单（PortableSettings.kt:6-75）无 prompt_*；备份中仅 `operator_prompt_slot_*` 槽位（:58-62，int 非文本）与角色包 OperatorExport.privatePrompt/groupPrompt（ExportPayload.kt:49-59）。
6. **两层渲染**：稳定 system 前缀（dynamicKeys 渲染为「见本轮资料」，USER_CONTENT/USER_MESSAGE 恒空，CachePromptLayering SharedUtils.kt:62-77）+ runtimeContext 易变 user 消息（键排序、中文标签 :79-87）。`requireNoUnresolvedTemplateTokens` 是**故意空实现**（未解析 token 按字面量送模型，SharedUtils.kt:599-603）——iOS 勿当 bug 补校验。
7. **陷阱**：`app/.../data/PromptTemplates.kt:12` 是废弃空壳 object；真库在 `viewmodel/shared/PromptTemplates.kt`。
8. `stripLegacyChatJsonInstructions` 只在**非 custom** 模板渲染前执行（ChatViewModel.kt:3296-3299）；群聊路径未见同款剥离（GroupChatViewModel.kt:1309 直接渲染）——保持现状。

## 验收设计（实现卡照抄）

- 金样门禁两级：Tier-1 字面量逐字节（47 件：PromptTemplates 22 + MemoryV2 8 + SleepPrompts 2 + PromptModuleDefaults 9 + AgentProfiles 6）；Tier-2 渲染 fixture（~67 件 app 内联片段，随各自所有者卡迁移时比对）。
- allowlist.json 白名单载体 + 金样失败唯一修复路径 = 从 baseline tag 重抽（extract_prompts.py），禁止手改金样。
- 单测迁移第一批：MemoryPromptShapeTest(1) + PromptCacheLayerTest(31) + PromptHistorySafetyTest(7) + AgentProfilesTest + AiSupportContractTest + GroupRuntimeContextTest（324 @Test 全景：39 文件）。

## Open questions（已全部裁决，reviewer PASS-WITH-FIXES 2026-11-23）

1. prompt_* 不进备份：**已裁决——照搬 Android 现状**（保持不进备份）。
2. allowlist.json 机制：**已裁决——通过**。
3. Clock 注入落点：**已裁决——构造器注入**。
4. 模块 version 键：**已裁决——保持「写但不解析」oracle 行为**。
5. dispatch 模板编辑器 tab：**已裁决——保留**。

## 遗留风险

- kotlinx-datetime 0.7.1-0.6.x-compat：beijingPromptTime 现为 JVM 专属（java.util.Calendar，SharedUtils.kt:765-771），迁 shared 必须 Clock 注入 + `TimeZone.of("Asia/Shanghai")`（SettingsRepository.kt:1396-1399 已有迁移先例可参照）。
- 金样抽取脚本对 trimIndent 的近似还原可能产生行首空白差异——进 allowlist 前需 reviewer 逐件确认。
