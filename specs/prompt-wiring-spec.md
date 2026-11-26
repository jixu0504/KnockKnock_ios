# 私聊提示词/记忆/知识库真实接线规格卡（T45）

> 状态：已实现（`PrivatePromptWiring.kt` + `ChatEngineBridge` 接线 + `--prompt-wiring-selftest`）
> 范围：iOS 私聊桥从 `DemoPromptAssembler`（只注入角色名+最近 10 条历史）切换为 T15
> `PrivatePromptAssembler` 的完整四层提示词（人设/记忆/知识库/参考资料边界块/历史/用户消息）。
> 不改 Swift、不改群聊（群聊继续走 `GroupChatBridge` + T23 `GroupChatEngine`）。

## 1. 模块边界

| 组件 | 文件 | 责任 |
| --- | --- | --- |
| `PrivatePromptWiring` | `shared/src/iosMain/.../iosbridge/PrivatePromptWiring.kt`（新增） | 按真实依赖构造 `MemoryV2Pipeline` / `KnowledgeBaseService` / `PrivatePromptAssembler`；接线 7 个上下文提供者 + 用户档案；回复后记忆提取入口；自检辅助 |
| `ChatEngineBridge` | `.../iosbridge/ChatEngineBridge.kt`（改） | `assembler = wiring.promptAssembler`（构造失败回退 `DemoPromptAssembler` 并 NSLog）；`EngineEvent.ReplyStored` → fire-and-forget `ingestPrivateChatAfterReply`；`--prompt-wiring-selftest` 驱动；`promptWiringStatus()` |
| `IosAppGraph` | `.../iosbridge/IosAppGraph.kt`（改） | 启动参数 `--prompt-wiring-selftest` 的纯 Kotlin 侧钩子（不改 Swift）：触发自检并落盘证据 |
| 复用（不改） | `domain/memory/MemoryV2Pipeline.kt`（T24）、`domain/knowledge/KnowledgeBaseService.kt`（T24）、`domain/chat/PrivatePromptAssembler.kt`（T19）、`prompt/*`（T18） | 域层行为零改动，本卡只做宿主接线 |

构造（真实依赖，全部已在 iOS 可用）：

```kotlin
PromptAssembler = PrivatePromptAssembler(
  store = RepositoryChatStore(chatRepo), settings = settingsRepo, codec = DefaultReplyCodec(),
  providers = PrivateContextProviders(...7 个...),
  userProfileProvider = { UserProfileView(settings.userName, settings.userGender, settings.userSignature) },
)
MemoryV2Pipeline(repo, settings, AIService(deepseekThinking = { settings.deepseekThinkingEnabled }),
                 MemoryVectorService(settings, LocalVectorStoreGateway(dbWrapper)),
                 userNicknameProvider = { settings.userName.ifBlank { "博士" } })
KnowledgeBaseService(KnowledgeBaseImportService, KnowledgeBaseIndexService, KnowledgeBaseContextBuilder)
```

## 2. Provider 映射表（oracle API ↔ 实现）

| `PrivateContextProviders` | Android oracle 调用点 | iOS 实现（PrivatePromptWiring） | 失败/降级 |
| --- | --- | --- | --- |
| `shortTermMemory(sessionId)` | `ChatViewModel.kt:3081` `repository.getShortTermMemory` | `ChatRepository.getShortTermMemory(sessionId)?.content` | 空 → 模板占位「无」；assembler 10s 预算 |
| `groupContext(operatorId, query)` | `:3140-3149` + `buildPrivateGroupContext :3708-3728` | 同款：该干员所在 `group*` 会话按 `lastTime` 降序，取 `privateGroupContextCount` 个群，读各自 `getShortTermMemory` 摘要（`private_*` 状态过滤） | 无群/无摘要 → 「无」 |
| `stableImpression(operatorId)` | `:3164` `memoryV2Pipeline.buildPrivateStableImpression` | `memoryV2.buildPrivateStableImpression(operatorId)`（L3 长期印象） | `metadata` 开关关闭 → 空；assembler 兜底「无」 |
| `personalMemory(operatorId, query)` | `:3168-3173` `buildPrivateChatMemoryContext` | `memoryV2.buildPrivateChatMemoryContext(operatorId, query, allowedSources = privateChatAllowedSources(), allowPrivateVisualRecall = PRIVATE_CHAT in sources)` | 源开关全关 → 空；无向量 → 空 |
| `relationshipMemory(operatorId, query)` | `:3174` `buildRelationshipPrivateMemoryContext` | `memoryV2.buildRelationshipPrivateMemoryContext(operatorId, query)`（关系网反向记忆，亲密度分档） | 无关系/低亲密度/无向量 → 空 |
| `publicMemory(query)` | `:3187` `buildPublicMemoryContext` | `memoryV2.buildPublicMemoryContext(query, limit=2, allowedSources = privateChatAllowedSources ∩ {MOMENT, MOMENT_COMMENT})`，受 `globalPublicMemoryEnabled` 门控 | 关闭 → 「无」 |
| `knowledgeContext(operatorId, query)` | `:3195-3220` `forOperatorRecall` | 先 `knowledgeBases.getAssignments(operatorId)` 过滤 `enabled && isKnowledgeBaseEnabledForBook(bookId,"private_chat")`，再 `KnowledgeBaseService.recallForPrivateChat(operatorId, query, assignedBookIds, candidateLimit=settings.knowledgeBasePrivateCandidateLimit)` | 无分配 → 「无」（不触发向量请求）；索引不可用/无嵌入 → 「无」 |
| `userProfileProvider()` | `appState.userProfile`（`MainViewModel.saveUserProfile :2132-2136`） | `settings.userName`（`user_name`，默认「博士」）/ `settings.userGender`（`user_gender`，默认「男」）/ `settings.userSignature`（`user_signature`，= bio） | 空值走 assembler 默认「来访者/未知/无」 |

记忆注入源开关语义与 Android 一致：`settings.memoryV2Enabled` + `isMemoryInjectionAllowed("private_chat", <SOURCE>)`
（默认 PRIVATE_CHAT/GROUP_CHAT/MOMENT/MOMENT_COMMENT/DIARY/MANUAL_MEMORY/RELATIONSHIP 全开）。

## 3. 回复后记忆提取（fire-and-forget）

接在 `EngineEvent.ReplyStored`（引擎回复已落库并完成租约，`ChatEngineProtocols.kt:175`）之后，
不在 `onDone`/UI 回调链上，**不阻塞回复**：

1. `workScope.launch` → `PrivatePromptWiring.ingestPrivateChatAfterReply(sessionId)`；
2. 按 oracle 维护语义（`ChatViewModel :2250-2266 / :2748-2760`）读取「游标之后」的待提取窗口：
   `getMessagesAfterCursor(sessionId, memory_extraction_cursor_session_*, restartAt, threshold)`，
   过滤 `system/send_failed/gift_reply_failed`；
3. `batch.size < privateMemoryExtractionThreshold`（默认 12，可设 3..30）→ 不提取（无任何副作用）；
4. 达阈值 → `memoryV2.ingestPrivateChat(sessionId, operatorId, operatorName, batch, currentRound)`；
5. 成功 → 推进 `memory_extraction_cursor_session_*` 并置 `archive_private_recall_ready_<sessionId>=true`；
6. 任何异常被捕获 → 只写 System log（`[RhodesLink][PromptWiring]`），不弹错、不写失败消息。

> 说明：`MemoryV2` 的记忆实体落在 `memory_items` / `memory_source_queue`（MemoryV2 表），
> Android 1.14.27 同样如此；`memories` 表只存放旧版短期/长期记忆。DB 证据同时核对两张表（§6）。

## 4. 回退与开关

- `PrivatePromptWiring` 构造失败（依赖缺失等极端情况）→ `DemoPromptAssembler` 回退 +
  `NSLog("[RhodesLink][PromptWiring] 构造失败…")`，聊天不中断；
- 无供应商配置 → AI 仍走 `DemoStreamingClient`（`demo=true`），但**提示词仍是真实完整组装器**
  （`PrivatePromptAssembler`），演示模式下可验证完整 prompt；
- `promptWiringStatus()` 返回 `assembler=PrivatePromptAssembler|DemoPromptAssembler(fallback) …`。

## 5. 已知差异 / 未支持（白名单）

| 项 | Android oracle | iOS 本卡 | 影响 |
| --- | --- | --- | --- |
| 存档时间线隔离 | `archive_context_active_*` / `archive_private_recall_ready_*` 门控记忆注入 | provider 内未判定 `archive_context_active`，仅在提取成功时写 `archive_private_recall_ready` | 仅在「读档后立即聊天」的短暂窗口可能注入旧时间线记忆；不影响正常会话 |
| 上下文溯源卡片 | `ChatTrace` / `ContextProvenance`（操作详情 UI） | 未移植 | 仅调试 UI 缺字段，提示词内容不变 |
| 短期摘要生成 | `generateShortTermSummary`（维护任务） | 本波只读取已有摘要；生成归 A7 维护层（T25/T22 已留接口） | 摘要类记忆需 A7 接线后才会出现；不影响 L1/L2/L3 注入 |
| 知识库导入/索引 UI | 设置页 | 本卡只接召回；导入/索引入口属后续设置波次 | 已有分配时才注入；无分配为「无」 |
| `MEMORY_ANCHORS` / `USER_PREFS` 等占位符 | oracle 另有锚点/偏好拼装 | 保持空串 | 这些块在 oracle 中同样常为空；待锚点召回迁移后再接 |

## 6. 验收（可自动判定）

| # | 验收项 | 判定方式 | 证据 |
| --- | --- | --- | --- |
| 1 | 真实组装器接线 | `promptWiringStatus()` 含 `assembler=PrivatePromptAssembler` | 自检日志 `WIRING …` |
| 2 | L3 记忆入 prompt | 预置 L3 `memory_items` → 构建 prompt，断言包含唯一 token `T45-SELFTEST-L3-*` | `PASS prompt.contains.l3_memory` + `PROMPT_REFERENCES` 片段 |
| 3 | 完整提示词结构 | 断言含 `【本轮参考资料】`/`【用户本轮消息】`/`【输出要求】`/角色名/用户名，长度 > 3000 | 自检 PASS 行 + prompt 头片段 |
| 4 | 演示模式可聊 | 无供应商配置下发一轮，`onResponseComplete=true` 且 `demo=true` | `PASS demo.round.visible_reply` |
| 5 | 回复后 L1 记忆落库 | 本地 mock 供应商（`127.0.0.1:18767`）连发两轮 → `memory_items` 新增 L1 行 | `PASS memory.ingest.new_rows/l1_row` + `sqlite3 memory_items` 查询 |
| 6 | 不回归 | `:shared:testDebugUnitTest`（524/0） | `evidence/t45-shared-tests-summary.txt` |
| 7 | iOS 原生路径 | `:shared:iosSimulatorArm64Test --tests *T45PromptWiringIosTest*`（2/2） | `evidence/t45-ios-test-summary.txt` |

iOS 原生测试（不依赖模型/网络，确定性验证装配）：

```bash
cd RhodesLink && source ../ios-port/toolchain/env.sh
./gradlew --no-daemon :shared:iosSimulatorArm64Test --tests com.rhodes.privatechat.shared.iosbridge.T45PromptWiringIosTest
# 2 例：预置 L3 + 自定义模板 → 真实 build 断言记忆/人设/边界块；服务构造状态断言
```

自检启动（模拟器实跑，无需改 Swift）：

```bash
xcrun simctl launch <DEV> com.rhodeslink.app --prompt-wiring-selftest
# 结果：<App容器>/Library/Application Support/t45-prompt-wiring-selftest.log
#       + `simctl spawn booted log show` 中 [RhodesLink][T45SELFTEST] 行
```

## 7. 文件清单

- 新增：`RhodesLink/shared/src/iosMain/kotlin/com/rhodes/privatechat/shared/iosbridge/PrivatePromptWiring.kt`
- 新增：`RhodesLink/shared/src/iosTest/kotlin/com/rhodes/privatechat/shared/iosbridge/T45PromptWiringIosTest.kt`
- 修改：`.../iosbridge/ChatEngineBridge.kt`、`.../iosbridge/IosAppGraph.kt`、`.../db/DatabaseWrapper.ios.kt`（测试注入 hook，默认行为不变）
- 修复：`shared/src/iosTest/.../network/ProviderDarwinBehaviorTest.kt:34`（`mockAvailable()` 加 suspend，解除 iosTest 整包编译阻塞）
- 证据：`ios-port/handoffs/evidence/t45-*`
- 自检 mock：`ios-port/handoffs/t45-mock-memory-server.py`
