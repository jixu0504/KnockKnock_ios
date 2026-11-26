# T45 实现交接：真实提示词/记忆/知识库注入接线（P0）

> 状态：**实现完成；`:shared:testDebugUnitTest` 524/0 零回归；`:shared:iosSimulatorArm64Test` T45 新增 2 例全绿；
> 模拟器端 `--prompt-wiring-selftest` 全链路证据（真实提示词 6139 字符 + L3 记忆入 prompt + 演示模式可聊 +
> mock 供应商记忆提取 memory_items 新增 L1 行）。** 详见 §4。
> 分支 `ios-port`，git 未提交；未改 Swift/iosApp。

## 1. 交付内容

| 类型 | 文件 | 说明 |
| --- | --- | --- |
| 新增 | `RhodesLink/shared/src/iosMain/.../iosbridge/PrivatePromptWiring.kt` | 真实依赖装配：`MemoryV2Pipeline` + `KnowledgeBaseService` + `PrivatePromptAssembler`；7 个 `PrivateContextProviders` + `userProfileProvider`；回复后记忆提取入口；自检辅助 |
| 改 | `.../iosbridge/ChatEngineBridge.kt` | `assembler = promptWiring.promptAssembler`（构造失败回退 `DemoPromptAssembler` + NSLog）；`EngineEvent.ReplyStored` → fire-and-forget 记忆提取；`--prompt-wiring-selftest` 实现；`promptWiringStatus()` |
| 改 | `.../iosbridge/IosAppGraph.kt` | 纯 Kotlin 启动参数钩子：`--prompt-wiring-selftest` → 触发自检（不改 Swift）；未带参数零开销 |
| 改 | `.../db/DatabaseWrapper.ios.kt` | 新增 `internal fun injectForTest(database)`（iosTest 注入临时 SQLDelight 库；默认路径行为不变） |
| 新增 | `RhodesLink/shared/src/iosTest/.../iosbridge/T45PromptWiringIosTest.kt` | 模拟器原生测试：预置 L3 → 自定义模板引用 `{{LONG_TERM_IMPRESSION}}` → 断言真实 build 含记忆/人设/参考资料块 |
| 修 | `RhodesLink/shared/src/iosTest/.../network/ProviderDarwinBehaviorTest.kt:34` | 1 行修复：`mockAvailable()` 加 `suspend`（原文件调用 suspend `client.get` 导致 iosTest 编译失败，阻塞整包测试） |
| 新增 | `ios-port/specs/prompt-wiring-spec.md` | 规格：provider 映射表（oracle ↔ 实现）、提取接线、回退策略、已知差异、验收 |
| 新增 | `ios-port/handoffs/t45-mock-memory-server.py` | 自检 mock 模型（OpenAI 兼容）：`stream=true` 返回结构化聊天 JSON；`stream=false` 返回 L1 记忆 JSON 数组；`/health`、`/stats` |
| 证据 | `ios-port/handoffs/evidence/t45-*` | 测试摘要、mock 请求日志、自检报告、DB 核对、截图 |

## 2. 接线映射（摘要；完整表见 specs/prompt-wiring-spec.md §2）

| oracle (Android) | iOS 实现 |
| --- | --- |
| `repository.getShortTermMemory(sessionId)` :3081 | `ChatRepository.getShortTermMemory` |
| `buildPrivateGroupContext` :3708-3728 | `PrivatePromptWiring.buildPrivateGroupContext`（群会话最近滚动摘要，`privateGroupContextCount`） |
| `memoryV2Pipeline.buildPrivateStableImpression` :3164 | `MemoryV2Pipeline.buildPrivateStableImpression`（L3） |
| `memoryV2Pipeline.buildPrivateChatMemoryContext` :3168 | 同方法 + `privateChatAllowedSources()` + `allowPrivateVisualRecall` |
| `memoryV2Pipeline.buildRelationshipPrivateMemoryContext` :3174 | 同方法（关系网反向记忆） |
| `memoryV2Pipeline.buildPublicMemoryContext` :3187 | 同方法（MOMENT/MOMENT_COMMENT 源 ∩ 允许源） |
| `knowledgeBaseContextBuilder.forOperatorRecall` :3207-3220 | `KnowledgeBaseService.recallForPrivateChat`（先过滤 enabled 分配 + `isKnowledgeBaseEnabledForBook(...,"private_chat")`） |
| `appState.userProfile`（MainViewModel :2132-2136） | `settings.userName`/`userGender`/`userSignature`（`user_name`/`user_gender`/`user_signature`） |

依赖构造（真实）：`AIService(deepseekThinking={settings.deepseekThinkingEnabled})`、
`MemoryVectorService(settings, LocalVectorStoreGateway(dbWrapper))`（默认本地哈希嵌入，离线可用）、
`KnowledgeBaseImportService/IndexService/ContextBuilder`、`RepositoryChatStore(chatRepo)`、`DefaultReplyCodec()`。

## 3. 回复后记忆提取（fire-and-forget）

`EngineEvent.ReplyStored` → `workScope.launch { wiring.ingestPrivateChatAfterReply(sessionId) }`：
按 oracle 维护语义读 `memory_extraction_cursor_session_*` 之后的窗口（过滤 system/send_failed/gift_reply_failed），
未达 `privateMemoryExtractionThreshold`（默认 12）→ 无副作用；达阈值 → `ingestPrivateChat(...)`，
成功推进游标并置 `archive_private_recall_ready_*`。异常全捕获，只 NSLog，不影响回复/UI。

## 4. 验证证据

### 4.1 回归

- `:shared:testDebugUnitTest`：**524 tests / 0 failures / 0 errors**（56 个 XML）→ `evidence/t45-shared-tests-summary.txt`
- `:shared:iosSimulatorArm64Test --tests *T45PromptWiringIosTest*`：**BUILD SUCCESSFUL，2/2 通过**
  - `promptContainsSeededL3MemoryAndProfile`（prompt chars=9793，hasL3=true）
  - `wiringConstructsRealMemoryAndKnowledgeServices`
  → `evidence/t45-ios-test-summary.txt`、`t45-ios-test.xml`、`t45-ios-test.log`

### 4.2 模拟器 `--prompt-wiring-selftest`（真实 App，最终 **RESULT PASS**）

构建：`xcodebuild build -project RhodesLink.xcodeproj -scheme RhodesLink -destination 'generic/platform=iOS Simulator'
-derivedDataPath DerivedData-t45 CODE_SIGNING_ALLOWED=NO` → **BUILD SUCCEEDED**
（`evidence/t45-build.log`、`t45-build-summary.txt`）
mock 模型：`python3 ios-port/handoffs/t45-mock-memory-server.py`（127.0.0.1:18767，`/health` OK，`/stats` 见 §4.3）

两台设备各跑一轮，均 **RESULT PASS**（设备：T45-sim=新建 iPhone 17e/iOS 27.2；iPhone 17=A63CD17A）：

```
=== T45 prompt wiring selftest ===
PASS wiring.constructed | assembler=PrivatePromptAssembler memoryV2=MemoryV2Pipeline knowledge=KnowledgeBaseService providers=7 embedding=local
PASS session.available | session=session_mon3tr operator=M3 mode=online
PASS l3.seeded | memoryItemId=1 token=T45-SELFTEST-L3-1795409039507
PASS prompt.assembled | chars=6139
PASS prompt.contains.reference_block
PASS prompt.contains.user_message_block
PASS prompt.contains.output_requirement
PASS prompt.contains.operator_name
PASS prompt.contains.user_profile_name
PASS prompt.memory_provider_ran
PASS prompt.custom_template.l3_memory | chars=9793
PASS prompt.custom_template.operator_name
PASS demo.round.visible_reply            ← 演示模式（fake AI）+ 真实完整提示词
PASS demo.provider.status | lastWasDemo=true
PASS mock.round1.visible_reply
PASS mock.round2.visible_reply
PASS mock.provider.real
PASS memory.ingest.new_rows | before=1 after=2 waited=0ms
PASS memory.ingest.l1_row | L1:用户与角色约定：两人的暗号是 T45-L1-MEMORY
PASS chat.rounds.stored | messages=6
PROMPT_AFTER_INGEST_HAS_L1=true         ← 记忆落库后再次 assemble，MEMORY_V2_CONTEXT 已召回新 L1
settings.restored provider=deepseek threshold=12
RESULT PASS
```

- T45-sim 完整报告：`evidence/t45-selftest-pass.log`；iPhone 17 完整报告：`evidence/t45-selftest-pass-iphone17.log`
- 修正说明：首轮（11:33）只有 `prompt.contains.l3_memory` FAIL，原因是 online 运行模板（`TAG_PRIVATE_ONLINE`）
  不内联 `{{LONG_TERM_IMPRESSION}}`（oracle 同款，稳定印象只在自定义模板/其它模板中出现）。
  已改为「保存自定义模板（引用 `{{LONG_TERM_IMPRESSION}}`）→ 真实 build 断言 L3 记忆 token」，并恢复用户模板（finally），
  修复后两台设备均 PASS。
- `PROMPT_MEMORY_SECTION=：见本轮资料`：`LONG_TERM_IMPRESSION` 在自定义模板下被替换为运行时上下文引用
  （token 在 `【资料开始】…【资料结束】` 内，见 self-assert `prompt.custom_template.l3_memory` PASS）。
- 截图：`evidence/t45-app-running.png`（App 运行态，T45-sim 聊天 Tab；深链确认弹窗为 `t45-deeplink-dialog.png`）。

### 4.3 mock 供应商请求证据（`evidence/t45-mock-server.log` / `t45-mock-stats-final.json`）

最终统计：`{"stream":8,"non_stream":8,"memory":8,"chat":8}`（含两轮自检）。典型请求：

```
REQ path=/v1/chat/completions model=mock-model stream=True  messages=6/8/36 kind=chat   system0='【应用保护规则】…'
END  chat SSE
REQ path=/v1                model=mock-model stream=False messages=2        kind=memory system0='你是一个私聊记忆提取器…'
END  JSON kind=memory
```

说明：`messages=6/8/36` 证明模型收到的是真实四层提示词（含历史/参考资料/用户消息），不是 Demo 的一行 system；
`stream=False` 的 `kind=memory` 请求证明 `MEMORY_V2` L1 提取走真实 `AIService`；`chat=8` 含演示轮（fake AI，不联网）。

### 4.4 DB 核对（sqlite3 直查运行中容器；`evidence/t45-db-check.txt`、`t45-db-check-iphone17.txt`）

```
memory_items:
1|operator|mon3tr|L3|evaluation_opinion|PRIVATE_CHAT|active|用户与M3 的约定：我们的暗号是 T45-SELFTEST-L3-…
2|operator|mon3tr|L1|agreement_commitment|PRIVATE_CHAT|active|用户与角色约定：两人的暗号是 T45-L1-MEMORY
3|operator|mon3tr|L1|agreement_commitment|PRIVATE_CHAT|active|用户与角色约定：两人的暗号是 T45-L1-MEMORY   ← 两个提取窗口各一条
memory_source_queue:
1|PRIVATE_CHAT|mon3tr|succeeded|session_mon3tr:epoch0:1-3
2|PRIVATE_CHAT|mon3tr|succeeded|session_mon3tr:epoch0:4-6
vector_memories: 2（本地哈希向量，memory_v2_operator_mon3tr_l1_*）
chat_messages: 6（demo 1 轮 + mock 2 轮，ai_json 为真实结构化回复）
```

> `memory_items`/`memory_source_queue` 是 MemoryV2 表（Android 1.14.27 同款）；`memories` 表只存旧版
> 短期/长期记忆，本次 L1 落库不写 `memories`（与 Android oracle 一致）。

## 5. 已知差异 / 未支持（同步写进 spec §5）

1. **在线/线下运行模板不内联 `{{LONG_TERM_IMPRESSION}}`**（`TAG_PRIVATE_*` 无该占位符，oracle 同款）：
   稳定印象只在自定义模板或其它带该占位符的模板中生效；本卡自检改用自定义模板验证装配管线。
   若产品希望在线模式也注入 L3，需要改 T18 模板（属提示词产品决策，非接线问题）。
2. **存档时间线隔离**（`archive_context_active_*`）未在 provider 内判定：仅在提取成功时写
   `archive_private_recall_ready_*`；读档后立即聊天的短暂窗口可能注入旧时间线记忆。
3. **短期摘要生成**未接线（只读已有摘要）：生成属 A7 维护层（T25/T22 已留接口），接线后 L2 才有数据。
4. **`ChatTrace`/`ContextProvenance` 溯源卡片**未移植（仅调试 UI 缺字段，不影响提示词内容）。
5. **知识库导入/索引 UI** 属知识库 UI 波次（T48）；本卡只接召回，无分配时输出「无」且不发向量请求。

## 6. 其他记录

- 构建期共享缓存损坏：12:10-12:20 多代理并行 gradle 导致 `kotlin-dsl/accessors` 与项目
  `.gradle/8.10.2/dependencies-accessors` 损坏（`LibrariesForLibs` EOF），已清理两处缓存后恢复；
  已在协作广播中告知其它代理。根因建议：同 workspace 多代理构建串行化（flock 或 lead 统一排队）。
- `ProviderDarwinBehaviorTest.kt` 的 1 行修复见 §1，属阻塞 iosTest 编译的既有 bug（非本卡引入）。
- 自检 hook 的启动钩子刻意放在 Kotlin `IosAppGraph`（读 `NSProcessInfo.arguments`），避免改 Swift
  （T46/T47/T48 并行改 `iosApp/`），也避免与 `--selftest` 的 Swift `SelfTestDriver` 冲突。
- 首轮 selftest 的 `prompt.contains.l3_memory` FAIL 已定位为「在线模板不内联 LONG_TERM_IMPRESSION」，
  改为自定义模板断言并在两台设备复跑 **RESULT PASS**（§4.2）。
