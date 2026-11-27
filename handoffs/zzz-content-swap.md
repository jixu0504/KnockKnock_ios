# 敲敲 · 绝区零内容替换 交接（T8/T9）

- 时间：2026-11-26
- 范围：把 App 内置的明日方舟联系人/群聊种子与对话前置世界观，整体替换为绝区零（ZZZ）；UI 术语清扫（干员→代理人 等）按用户指示留到下一版本（任务板 task-11）。
- 数据源：`/Users/jackchen/Downloads/敲敲开发 workplace/zzz_characters/`（62 角色 JSON/CSV/人设档案/世界观文档，用户自备）。

## 1. 种子数据（shared 数据层）

- 新增生成器 `tools/zzz/gen_presets.py`：从 characters.json + 联系人配置总表.csv + 人设档案/*.md + 世界观文档 确定性生成种子；可重跑。
- 新增 `shared/.../data/ZzzPresets.kt`（137KB）：
  - **62 位代理人** `zzz001..zzz062`：title=`<阵营>·<特性>代理人`；description=备注名｜个性签名；privatePrompt=人设解读三段精简(≤450字)+称呼/口头禅/雷区/典型回复；groupPrompt≤120字；`avatarUri="knock:NNN"`。
  - **21 个阵营群** `group_f01..group_f21`：members=阵营全部成员（合计 62 人），rules 取自世界观文档「势力版图」定位列。
- `OperatorRepository.kt`：presetOperators=ZzzPresets.operators；删除 avatarRes 的 147 项方舟映射；`count==0 才种子` 守卫保留（**老安装不会自动迁移**，需卸载重装或另开迁移任务）。
- `SessionRepository.kt`：initPresetGroups 改用 ZzzPresets.groups（群在首次进入群聊/联系人页时懒播种，与旧版同语义）。
- 头像：62 张 250×250 PNG 已入 `ios/RhodesLink/Resources/AvatarsKnock/knock_001..062.png`（RoleAvatar `knock:` 前缀直接生效）。

## 2. 世界观（shared 提示词层）

- `PromptTemplates.kt`（VERSION 39→40，触发存量用户默认模板刷新）+ `PromptModuleDefaults.kt`：
  罗德岛/泰拉/龙门币/干员 等前置设定 → 新艾利都/绳网/丁尼/代理人；派遣叙述者=「绳网的委托记录员」；示例角色改安比/妮可（狡兔屋）。
  占位符、ai_json 分段协议、段数字数规模全部不变。
- lead 补改 3 处运行时注入串：`PromptRender.kt`（附近干员→附近代理人）、`GroupPromptAssembler.kt`/`GroupChatEngine.kt`（群空闲闲聊「干员们」→「代理人」）；2 处 VERSION 断言 39→40（commonTest）。

## 3. 旧种子引用清理（9 文件）

GroupChatBridge/A7Glue（演示桩 妮可 zz058、userName 默认 绳匠）· SelfTest.swift · AppGraphClient.swift · BubbleSplitAutomation.swift（夹具台词 绳匠）· DebugLogsView.swift · NewGroupView.swift（占位群名 狡兔屋委托闲聊群）。全仓 grep 旧 id/旧名零残留（in-scope）。

## 4. 验证

- gradle `:shared:linkDebugFrameworkIosSimulatorArm64` / `:shared:linkReleaseFrameworkIosArm64` BUILD SUCCESSFUL；xcodebuild 模拟器 + 真机 Debug BUILD SUCCEEDED。
- 二进制校验（UTF-16LE 检索静态库成员）：zzz001/zzz062/group_f08/group_f21/赛维里安/狡兔屋/绳网的委托记录员/附近代理人 **存在**；阿米娅/group_elite/罗德岛的战术记录员/精英干员茶水间 **零残留**。
- 真机（iPhone 17 / iOS 27.0.1，卸载重装清旧库）：
  - `--selftest`：bootstrap 23 会话（21 群+2 私聊）、深链 session_zzz023、真实供应商流式回复、`=== selftest done ===`、**0 FAIL**；
  - `--roles-selftest`：roles loaded **count=62** first=zzz043 avatar=knock:043；groups **count=21** first=都市秩序部；
  - `--avatar-render-selftest`：**allPASS=true PASS=13 FAIL=0**（knock: 头像真实渲染）；
  - `--group-selftest`（**需 `--tab contacts` 触发**，自动化挂在联系人页 task）：真机 PASS——建群/群消息落库/分段撤回 `recallSegment PASS=true`/`=== group selftest done ===`，FAIL=0。

## 5. 已知差异 / 遗留（下一版本 task-11 + 用户侧）

1. **铃（法厄同）缺失**：zzz 数据集 62 人不含 铃/哲（法厄同仅 佩洛伊斯）；用户在另一会话补数据，回来后按 `gen_presets.py` 流程增补 zzz063+ 与法厄同群成员。
2. UI 术语仍为方舟口径（干员日记/干员动态/「我的身份」默认昵称=博士 等）→ task-11。
3. golden 金样 7 件 strict 预期红（未重生成）→ task-11。
4. Knowledge/Memory 自动化演示文案仍罗德岛味（有动态 fallback，不崩）→ task-11。
5. 老安装（含 147 方舟角色）不自动迁移：卸载重装即得纯 ZZZ 数据。

## 6. 第二轮收口（2026-11-26 午）

- **术语清扫（sweep）**：Features 用户可见串 干员→代理人 / 罗德岛→新艾利都 / 博士→绳匠（含 Knowledge 演示书整体改写、Ranking/Moments/Impressions/RoleEdit/UserProfile/Settings 等）；shared 侧 `SettingsRepository.user_name` 默认与 15 处 `.ifBlank{"博士"}`、OperatorBridge/KnowledgeBaseBridge 报错、bootstrap 演示记忆、AnchorSourcePolicy 同步。注释/日志标签/golden/prompt 模板/导入兼容常量按约定未碰。
- **私聊分区 bar（sweep）**：RolesView 私聊分区改与群聊同款 KnockPanel 卡片（图标+私聊+计数+折叠 chevron）；群聊/私聊均可折叠；搜索框移出 LazyVStack 修复失焦。
- **147 张旧头像移除（sweep）**：删 `Resources/Avatars/`（6.9MB）；RoleAvatar 保留 builtin 值域解析、渲染走首字占位；断言同步（bundledAvatars=0/0、knock.render、builtin.fallbackPlaceholder）。
- **golden 重生成（golden）**：7 件 strict 金样 + inventory 按 VERSION 40 重抽取（commonTest/androidUnitTest 双树 + 顶层真源同步、manifest 刷新）；`SourceFragments.groupOutputProtocolTail` 锚点镜像同步 anby/nicole 并重抽 L316 金样；`:shared:iosSimulatorArm64Test` 603 项仅 8 件 KtorDarwinStreamingTest 环境前置失败（需 mock SSE，既有）；build.gradle.kts 补 `copyIosTestResources`、sync_golden.sh 路径修复。
- **图标/标识重绘（lead）**：按用户参考图矢量重绘「黄色手机+心形+WiFi 弧」：上部左倾 14°、圆滑信号弧、8× 超采样；AppIcon 1024 与 KnockPhone imageset 更新；App 内标识放大（mark 34pt / 聊天顶栏 28pt）。
- **聊天页参考图重设计（lead）**：浮动圆角顶栏（返回+手机标识+左对齐名称+模式胶囊）、消息+输入同处 24pt 圆角面板、接收气泡 #E6E7EB、发送/发送钮 #1C55E2、白色胶囊输入框、#292929 加号圆钮、图片消息白相框；群聊同构。
- 验证：gradle + xcodebuild 全绿；模拟器整合构建截图 knock-final2-*.png / knock-icon-home.png / knock-chat-ref-*.png；sweep 自检 roles/persona/avatar/identity/knowledge/memory 全 PASS。

## 7. 第三轮微调（2026-11-26 下午）

- **私聊输入框占位**：改为 `对{当前联系人名}说些什么…`（ChatView `inputPlaceholder`，标题「11号」类书名号自动剥离 → 「对11号说些什么…」；群聊仍为「在群里说点什么…」）。
- **图标居中**：AppIcon 改按「手机+WiFi 弧」整体 bbox 精确居中 + 画幅 78% 等比缩放（实测水平/垂直偏移 0.0px，高度占幅 77.8%、原 55%）；`KnockPhone` imageset 同源。
- **App 内标识尺寸补偿**：因画幅填充率变化，品牌 mark 34→40pt、聊天/群聊顶栏 24(pre-28)→34pt。
- **私聊首条会话选择**：bootstrap 与 SelfTest 的首会话改为优先取非 `group_` 会话（原先可能落到群聊）。

## 8. 真机试玩反馈修复（2026-11-26 下午，第 4 轮）

真机发现 4 个问题 + 1 个新需求，分组并行修（task-12/13/14/15）：
1. **撤回/删除不即时刷新**（chatfix）：`refreshAfterSend` 合并逻辑 + 撤回态映射。
2. **长按菜单是系统液态玻璃**（menu）：新建 `KnockContextMenu.swift`，替换 4 处 `.contextMenu` + 4 处系统 `Menu`（KnockBars/RolesKnockSupport/Moments/KnowledgeDetail）。`.alert` 替换留待后续（`KnockConfirmDialog` 在 onConfirm 前先置 isPresented=false，需要捕获选中项的变体）。
3. **导入聊天记录后私聊不显示**（chatfix）：补通知机制 + `hasLoaded` 守卫 + 列表同步。
4. **字重过粗导致边缘模糊**（type）：`KnockTypography.weight` 原除 body/caption 外全 `.heavy`（PingFang 实际最粗 Semibold → 合成加粗发糊）；重排梯度并削弱同色 shadow 假加粗。
5. **新需求：私聊点对方头像 → 角色简介页**（lead，已落地并验证）：
   - `SessionDto` 新增 `operatorId`（IosDtos.kt）→ `ChatSession.toDto()` 映射 → Swift `SessionItem.operatorId` → `ChatViewModel.operatorId`；
   - `ChatView.messageAvatar` 角色侧改 Button → `openRoleProfile()` → `router.chatPath.append(Route.roleDetail(roleId:))`（推入聊天自己的栈，返回回到私聊）；
   - 钩子 `--chat-avatar-profile`；验证日志 `chat avatar profile push roleId=zzz043 depth=2` + 截图 screenshots/knock-chat-avatar-profile.png。
6. **额外修复：深链 scheme 不匹配**（lead）：`AppRouter.handle(url:)` 只认 `rhodes://`，而 Info.plist 已改 `knock://` → 所有 knock:// 深链 `handled=false`。现两者都接受；`knock://chat/session_zzz043` 验证 `handled=true`。

## 9. 第 4 轮修复交付（2026-11-26 14:00，真机反馈四问题 + 新功能）

**问题1 撤回/删除不即时刷新（chatfix）**
- 真根因：`refreshAfterSend` 的 `olderKept` 只按 id 回填「更早已加载」消息 → 被删/撤回行 id 小于最新页最早 id 时被原样回填；且召回语义是**删行**（非 isRecalled 标记）。
- 修法：`runOperation(_:mutation:)` 增加 `LocalMutation(.remove/.reload)`，成功后 O(1) 精确更新时间线再 `refreshAfterSend(dropping:)`；`olderKept` 改按 timestamp 保留 + 按 removedIds 剔除。

**问题2 长按菜单液态玻璃（menu）**
- 新建 `DesignSystem/KnockContextMenu.swift`（492 行）：`KnockMenuAction` / `KnockMenuPanel` / `KnockMenuOverlay` / `KnockContextMenuModifier`（长按 0.35s、拖动 22pt 让位滚动、light 触觉、Esc/遮罩关闭、scale 0.96→1 弹簧、面板打开锁滚动、accessibilityAction 暴露同等动作）+ `knockMenuHost()` 根宿主。
- 替换 4 处 `.contextMenu`（ChatView/ChatListView/GroupChatView/MemoryListView）+ 4 处系统 `Menu`（KnockBars.KnockDropdown 8 调用点 / RolesKnockSupport.KnockMenuRow / MomentsView / KnowledgeDetailView）；动作集合与顺序逐一对照无丢失。仓内 `.contextMenu` 真实调用已清零。
- `.alert` 未替换（需「捕获选中项的 dialog 变体」，`KnockConfirmDialog` 在 onConfirm 前先置 isPresented=false 会把 pendingDeleteMessage 清空）→ 后续收口项。

**问题3 导入聊天记录后不显示（chatfix）**
- 真根因：导入消息 timestamp 远早于现有聊天，首屏只取最新 50 条 → 导入块不在首屏；且 `hasLoaded` 守卫 + 视图不销毁导致不刷新。
- 修法：`MarkdownChatImportBridge` 回传连续 `insertedIds`；`MessageHistoryBridge.pageByIdRange` 新查询；`DataBackupClient`/`MessageHistoryClient` 透传；导入成功 post `.chatDataDidChangeExternally` → ChatViewModel `mergeImportedMessages`（≤500 条、按 (createdAt,id) 排序去重、复用 `prependGeneration` 前插锚点自动定位）+ ChatListView 预览同步；`load()` 消费「待定位导入块」标记。

**问题4 字重过粗发糊（type）**
- 真根因：`KnockTypography.weight` 除 body/caption 外全 `.heavy`（PingFang 实际最粗 Semibold → 系统合成加粗）+ KnockText 四向 offset 副本 + 同色 shadow 假加粗。
- 修法：全量重排梯度（display/brand/pageTitle/sectionTitle/rowTitle/captionStrong/tabLabel → `.semibold`；bubble/badge/emptyPill → `.medium`；body → `.regular`），**全项目不再请求文本 `.heavy`**；删四向 offset 副本与多行 shadow，仅 ≥20pt 标题保留 radius 0.30/opacity 0.30 微补偿；气泡正文改 `bubble` Medium 真字重。
- lead 追加清扫：19 处直写 `Text(... weight: .heavy)` → `.semibold`（Ranking/Moments/KnowledgeDetail/DebugLogs/DiaryAutoManage/RoleAvatar）。

**新功能：私聊点对方头像 → 角色简介页（lead）**
- `SessionDto.operatorId`（含群聊空串）→ `ChatSession.toDto()` → Swift `SessionItem.operatorId` → `ChatViewModel.operatorId`；`ChatView.messageAvatar` 角色侧改 Button → `openRoleProfile()` → `router.chatPath.append(Route.roleDetail(roleId:))`（返回回私聊）；钩子 `--chat-avatar-profile`。

**额外修复（lead）**
- 深链 scheme：`AppRouter.handle(url:)` 只认 `rhodes://` 而 Info.plist 已改 `knock://` → 所有 knock:// 深链 `handled=false`；现两者都接受。
- 发送钮禁用态：灰底在暗色下几乎不可见 → 改暗蓝 `chatBlue.opacity(0.26)` + 白字 0.55。

**验证**：模拟器（51C310B6）`--chat-refresh-selftest` 6/6 PASS（含 recall-newest/delete-newest/delete-older id 残留用例/import-marker/import-notify/import-persist）；`--chat-ops-selftest` 7 PASS（regenerate 为演示模式既有确定性差异）；`--selftest === done ===`；深/浅截图 knock-final3-*.png。真机包已安装（启动时设备锁定，解锁即可）。

## 10. 群聊模式切换 + 群消息撤回/删除（2026-11-27，v0.3.4）

真机反馈两问题，本轮修复：

### A. 群聊缺「线上/线下/导演」模式切换（新增功能）
- **shared 早已支持**三种模式的回复规则（`GroupPromptAssembler.foundation/modeFormat/modeHistoryBoundary/observingText`），发送时取 `session.mode` —— 缺的只是 iOS UI 入口与写库通道。
- 新增 `GroupChatBridge.setGroupMode(sessionId, mode, onDone)`：校验 online/offline/director → 写 `chat_sessions.mode` → `engine.invalidateGroup()` 让下一轮按新模式组装提示词；**不写** `settings.last_mode_*`（该键私聊专用，群 operatorId 是 group_xx，写入即脏数据）。
- `GroupChatClient.setGroupMode` Swift 包装；`GroupChatViewModel.mode/modeText/isModeBusy/setMode`（复用 `ChatViewModel.chatModes` 的 线上/线下/导演 口径）。
- `GroupChatView`：顶栏加**模式胶囊**（位于群名右侧、成员按钮左侧）+ `KnockDialogPanel` 三模式弹窗（当前项电动黄高亮 + ✔），与私聊同款视觉。
- 实机验证：`--group-mode-selftest` 三模式往返 `online→offline→director→online` 全 ok，`RESULT PASS=true`；DB `chat_sessions.mode` 正确落库；**线下模式回复里出现【旁白】块**（截图 knock-034-group-online.png），证明规则真实生效。

### B. 群聊里自己发的消息无法撤回/删除（bug）
- 原菜单只对 AI 消息提供「撤回这一段」，自己发的消息**只有复制**。
- 新增 `GroupChatBridge.recallGroupMessage / deleteGroupMessage`（共用 `removeGroupMessage`：删行 → 清 replyTurns/displayEvents → 重算会话预览 → `invalidateGroup` → 校验是否真删）。
- `GroupChatView` 菜单：outgoing 且有真实 id 时新增「撤回」「删除（本地）」两项 destructive，各带二次确认弹窗。
- **同时修掉 id 残留 bug**：`GroupChatViewModel.refresh()` 的 `olderKept` 原按 id 原样回填 → 删除较早消息时会「复活」。新增 `refresh(dropping:)` 剔除被删 id，`runOperation` 传递之（与私聊 0.2.0 的修法同源）。
- 实机验证：`--group-recall-selftest` → `recall PASS=true messageId=981 stillInTimeline=false`、`delete PASS=true messageId=983 stillInTimeline=false`；**DB 复核**（devicectl 拷出 rhodes.db）：被删 id 在 `chat_messages` 中确实不存在。

### 取证钩子（仅启动参数触发，对用户路径零影响）
`--group-mode-picker`（自动展开模式弹窗）、`--group-mode-selftest`（三模式往返自检）、`--group-mode-offline-demo`（切线下便于截图）、`--group-recall-selftest`（发→撤回→删→DB 复核）。
