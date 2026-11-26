# 规格卡：聊天模式切换（线上 / 线下 / 导演）+ 操作流去开发小字

> 基线：Android v1.14.27-android-baseline @ 3642d2c（只读 oracle）。file:line 均基于该提交。
> 任务：T55（用户插入优先任务）。实现代理：impl-mode-switch。
> iOS 侧落点：`shared/src/iosMain/.../iosbridge/{SessionListBridge,ChatEngineBridge,IosAppGraph}.kt`、`iosApp/RhodesLink/Features/Chat/**`。

## 模块信息

| 项 | 内容 |
|---|---|
| 模块名 | 私聊三模式切换（UI + DB + 提示词联动） |
| 所属阶段 | Phase 6 收尾（用户插入） |
| 范围映射 | A1（私聊主链路）；A6（提示词按 mode 分发，T18 管线只读） |
| 对应 Android 源码 | `app/.../viewmodel/ChatViewModel.kt:1046`（setMode）、`app/.../ui/chat/component/ChatHeader.kt:196-197`（模式徽标文案/图标）、`app/.../ui/chat/component/ChatInputBar.kt:202-224`（「切换模式」对话框：线上/线下/导演 + 当前高亮 + 取消）、`app/.../ui/chat/ChatScreen.kt:452,604`（入口）、`shared/prompt/PromptTemplates.kt:39/55/69`（三套 TAG_PRIVATE_*）、`shared/prompt/PromptModuleDefaults.kt:185`（narrationProtocol：仅 offline/director 返回【旁白硬规则】） |
| 依赖模块 | T18 提示词管线（`PrivatePromptAssembler`，mode 经 `PromptBuildRequest.mode` 分发）、T45 真实装配接线（`PrivatePromptWiring`）、T19 ChatEngine（`sendMessage(mode=…)` 贯通到 assembler/可见性契约）、SQLDelight `ChatSessions.sq:48 updateMode`（commonMain 既有，未改 schema） |

## 行为契约（逐条）

| # | 契约 | 实现落点 |
|---|---|---|
| M1 | 会话模式取值仅 `online` / `offline` / `director`；非法值拒绝（错误回传 Swift，不改 DB） | `SessionListBridge.kt:99`（`updateMode`，`require(normalized in CHAT_MODES)`，`CHAT_MODES` 定义于文件末） |
| M2 | 切换写 `chat_sessions.mode`（`SessionRepository.updateSessionMode` → `updateMode` SQL），幂等：同值不重复写 | `SessionListBridge.kt:99-110`、`shared/data/SessionRepository.kt:70-72`、`ChatSessions.sq:48` |
| M3 | 同步写 `settings.last_mode_<operatorId>`（oracle `ChatViewModel.setMode` → `settings.putLastMode`） | `SessionListBridge.kt:106`、`shared/settings/SettingsRepository.kt:546-549` |
| M4 | 发送时以 **会话当前 mode（DB 重读）** 作为 `ChatEngine.sendMessage(mode=…)`，无需重启引擎；切换后下一轮即生效 | `ChatEngineBridge.kt:165`（send）、`:305/:387/:456`（重试/续写/图片轮）、`resolveSession()` 每次读取 DB |
| M5 | 提示词按 mode 分发 T18 默认模板：offline → `TAG_PRIVATE_OFFLINE`（含【场景连续性】/面对面互动）、director → `TAG_PRIVATE_DIRECTOR`（含【导演互动】）、online → `TAG_PRIVATE_ONLINE`（含【线上互动】） | `shared/prompt/PromptTemplates.kt:6-10`（get 分发）、`:39/:55/:69`；`PrivatePromptWiring.kt:228-231`（`PromptBuildRequest(..., mode = session.mode)`） |
| M6 | 旁白协议随 mode 变化：offline/director 的 system 层包含 `【旁白硬规则】`；online 不包含 | `PromptModuleDefaults.kt:185`、`PrivatePromptAssembler.kt:237-238` |
| M7 | UI：聊天页导航栏右上常驻当前模式徽标「🟢 线上 / 🏠 线下 / 🎬 导演」+ `▾`；点击弹「切换模式」三选一，当前项标注（当前），可选取消；切换中防连点 | `ChatView.swift:38-88`（toolbar + confirmationDialog）、`ChatViewModel.swift:191-259`（chatModes / modeText / modeIcon / modeBadgeText / setMode） |
| M8 | 切换成功后 UI 立即刷新：徽标、导航副标题（`线上/线下/导演 · 私聊`）、会话行模式胶囊、轻提示「已切换到X模式」；失败显示错误并保持原模式 | `ChatViewModel.setMode`（`:215`，重读 `findSession` 回写）、`ChatView.swift:66-78`、`ChatListView.swift:203`（modeText 胶囊）、`AppGraphClient.swift:266`（`updateSessionMode`） |
| M9 | 文案口径与 Android 一致：线上/线下/导演（不写「在线/离线」） | `AppGraphClient.swift`（`SessionItem.modeText` / `RoleItem.modeText`）、`ChatViewModel.chatModes` |
| M10 | 模式自检可在无 GUI 触控下完成：DB 字段核对 + 三模式提示词关键段/旁白协议断言 + 恢复原状态 | `ChatEngineBridge.kt:656-780`（`debugModeSwitchSelftest`）、`IosAppGraph.kt:187-197`（`--mode-switch-selftest` 启动钩子） |
| M11 | 自检隔离：先快照三模式自定义模板覆盖（`prompt_private_<mode>` + custom 标记 + version），断言默认模板后**原样恢复**；自检结束恢复原 mode，不改变用户数据 | `ChatEngineBridge.kt:690-706`（快照/移除）、`:766-780`（finally 恢复） |
| M12 | UI 取证不依赖触控：`--mode-switch-ui-demo` 自动开弹窗→offline→director→online（各留 ~10s 截图窗口）；`--mode-switch-menu` 仅保持菜单 30s | `ModeSwitchAutomation.swift:16-79`、`ChatView.swift:136-144` |

## 数据

- 读/写：`chat_sessions.mode`（TEXT，既有列；未改 schema/迁移）。
- 读/写：`settings.last_mode_<operatorId>`（既有键；备份可携带，见 `BackupPortableSettings.kt:76`）。
- 不新增表 / 不新增迁移 / 不触碰金样 schema。
- 自检临时改动：三模式 `prompt_private_<mode>` 自定义覆盖（快照→移除→恢复），以及会话 mode（finally 恢复原值）。

## 验收（可自动判定）

1. `--mode-switch-selftest`（`xcrun simctl launch <UDID> com.rhodeslink.app --mode-switch-selftest`）：
   - 报告 `Application Support/t55-mode-switch-selftest.log`，末尾 `RESULT PASS`；
   - 断言组：`db.mode.{offline,director,online}`、`settings.last_mode.*`、`prompt.{mode}.contains/notContains`（模板关键段 + `【旁白硬规则】` + `【用户本轮消息】`）共 30 条。
2. `--mode-switch-ui-demo --open-url rhodes://chat/<sessionId>`：NSLog 依次出现 `switched target=offline/director/online` 且 `dbMode` 与之相等、`status=线下/导演/线上 · 私聊`；三张截图徽标随模式变化。
3. `xcrun simctl io <UDID> screenshot` 截图：模式菜单、切换后聊天页；去小字后的聊天列表/记忆/排行榜/设置/占位页。
4. 复跑基线：`:shared:linkDebugFrameworkIosSimulatorArm64` BUILD SUCCESSFUL + `xcodebuild build` BUILD SUCCEEDED（0 error）。

## 抽取落点

- Kotlin：`shared/src/iosMain/.../iosbridge/`（mode 读写归 `SessionListBridge`，提示词断言归 `ChatEngineBridge` 自检，启动钩子归 `IosAppGraph`）。commonMain 未改（`updateSessionMode`/`getLastMode`/`PromptTemplates` 均已存在）。
- Swift：`Features/Chat/`（ChatView/ChatViewModel/ModeSwitchAutomation）、`Bridge/AppGraphClient.swift`（`updateSessionMode` + modeText 文案）。
- 去开发小字：见 `handoffs/impl-mode-switch.md` §2 清单（file:line）。

## 已知差异 / 不承诺

1. **模式切换过渡提示**：oracle `ChatViewModel.setMode` 还会写 `settings.putPendingPrivateModeTransition`（一次性「从本轮开始改为…」系统提示）并 `clearPrivateTurnState`；该逻辑在 Android app 层（依赖 app 侧私有 turn 状态），shared 无对应键，本次未移植（模板/旁白协议已即时切换，下一轮提示词正确）。列为后续可选增强。
2. 群聊模式切换未接 iOS 群聊页（本次入口只在私聊聊天页；shared 桥 `updateMode` 对群会话同样可用）。
3. 演示模式 fake AI 固定输出 dialogue 段（不含 narration），端到端「导演模式回复含旁白」需真实模型；本次验收按规格卡要求以**提示词断言**为准。
