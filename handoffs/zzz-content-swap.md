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
