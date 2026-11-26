# 关系网编辑 + 用户身份页规格（T57）

> 规格版本：v1（2026-11-23）｜实现：T57 / task-57｜分支：`RhodesLink@ios-port`（Kotlin 2.3.21 / SwiftUI iOS 18+）
> 目标阶段：Phase 4 剩余页面 —— 关系网编辑（添加/删除/双向互补）+ 用户身份页（昵称/性别/签名 + 真实统计）。
> Oracle：Android 1.14.27（commit 基线见 `ios-port/baseline/BASELINE.md`）。

---

## 1. 范围与 oracle 映射

| iOS 交付 | Android oracle（file:line） | 说明 |
|---|---|---|
| `RelationBridge.kt`（新） | `RelationshipRepository.kt:29-70`、`RelationshipScreen.kt:57-398`、`MainViewModel.kt:2151-2155` | 关系表读写 + 24 类型 + 互补映射 |
| `RelationsView.swift`（新） | `ui/relation/RelationshipScreen.kt:57-260`（列表/编辑对话框/删除）、`OperatorDetailScreen.kt:331-362`（关系网卡片） | 角色详情「关系网 · 管理」进入 |
| `IdentityBridge.kt`（新） | `SettingsRepository.kt:836-850`、`MainViewModel.kt:2129-2131`、`ui/profile/ProfileSettingsScreen.kt:77-190` | 昵称/性别/签名 + 统计 |
| `UserProfileView.swift`（新） | `ProfileSettingsScreen.kt`（身份设置页） | 设置 → 我的身份 |
| 入口 patch | `RoleDetailView.swift`（关系卡「管理」）、`SettingsView.swift`（「我的身份」行） | 最小 patch |

**不在本卡范围**：关系亲密度对记忆传递的运行时逻辑（T24/T45 已有；本卡只写表）、用户头像上传/裁剪（Android `ProfileSettingsScreen` 有上传头像，本卡身份页只显示首字头像 + `user_avatar_uri` 读取）、群聊成员关系图可视化（Android `OperatorDetailScreen` 的图节点）。

---

## 2. 数据真源（golden 核对）

`relationships` 表（`shared/src/commonMain/sqldelight/.../Relationships.sq:1-11`，golden 归档 `ios-port/golden/db/schema/Relationships.sq`）：

| 列 | 语义 |
|---|---|
| `operatorId` | 关系发起方（A） |
| `relatedOperatorId` | 关系对象（B） |
| `relatedOperatorName` | B 的名称快照 |
| `type` | `RelationshipType` 枚举名（24 种） |
| `intimacy` | 亲密度 0-100（oracle 显示 0-100 进度） |
| `isPreset` | 旧版预设标记（关系系统改为玩家自建，`RelationshipRepository.migrateOldRelationships` 会清预设） |
| `note` | 备注 |

`user_name` / `user_gender` / `user_signature` / `user_avatar_uri` 为 settings 键（`SettingsRepository.kt:836-850`），进完整备份（`BackupPortableSettings.kt:11`）。**Android 1.14.27 无用户 level/exp 字段**（全仓 grep `user_level|user_exp` 无命中）→ 身份页按任务口径展示真实统计。

---

## 3. 行为契约

### 3.1 关系网（单向 + 可选双向）

1. **列表** = `getRelationships(operatorId)`（`ORDER BY intimacy DESC`，`Relationships.sq:17-18`）+ `getReverseRelationships(operatorId)`（对方→当前）。
   同 related 角色出向优先去重（T36 RoleBridge 同口径）；入向行 `direction="in"`，出向 `direction="out"`。
2. **添加** = `insertRelationship` 语义（`RelationshipRepository.kt:53-63`）：同向已存在则覆盖 `type/intimacy/note`，否则插入；
   `relatedOperatorName` 取 operators 表当前名称（换名后新关系用新名）；`intimacy` 压到 0..100。
3. **双向互补**（`RelationshipScreen.kt:372-398` 逐条复刻）：
   `BIG_SISTER↔LITTLE_SISTER`、`BIG_BROTHER↔LITTLE_BROTHER`、`MOTHER|FATHER→DAUGHTER`、`DAUGHTER→MOTHER`、
   `SON→FATHER`、`BOSS↔SUBORDINATE`、`CAPTAIN↔MEMBER`、`MENTOR↔STUDENT`、
   `FRIEND|CLOSE_FRIEND|COMRADE|TEAMMATE|RIVAL|LOVE_RIVAL|LOVER|FAMILY` 同类型、
   `GUARDIAN→FAMILY`、`CRUSH` 无互补。**默认不自动创建**（oracle 开关默认关）。
4. **更新** = 已存在关系覆盖 type/intimacy/note；不存在报「关系不存在，请先添加」。
5. **删除** = 按 (source,target) 精确删单行（`Relationships.sq:44-46`），**不连带反向行**（oracle 单向语义）。
6. **错误**：自己设为自己、未知类型、角色不存在、删除不存在关系 → 第二回调参数回传中文错误，不崩溃。
7. **描述文案** = `RelationshipUiMapper.description`：`A是B的【类型】`；入向行源/目标互换。

### 3.2 用户身份

8. **读取**：`user_name`（默认「博士」）、`user_gender`（默认「男」）、`user_signature`（默认空）、`user_avatar_uri`；统计 = 消息总行数 / 长期印象行数 / 会话数 / 角色数 / 关系数。
9. **保存**：昵称 trim 后必填（空 → 「昵称不能为空」，oracle `ProfileSettingsScreen.kt:110`）；写三键并 `NSUserDefaults.synchronize()` 落 plist。
10. **提示词生效**：`USER_NAME/USER_GENDER/USER_BIO` 进入 T45 私聊提示词（`PrivatePromptAssembler.kt:164`、`PrivatePromptWiring.kt:101-105`），保存后下一轮请求生效。

---

## 4. Bridge 契约（回调式，Main 队列回调，不导出 Flow/suspend）

### RelationBridge（`@ObjCName("RelationBridge")`）

| 方法 | 说明 |
|---|---|
| `list(operatorId, onResult(List<RelationEntryDto>, String?))` | 出向 + 入向，空 = 无关系 |
| `operators(onResult(List<RelationOperatorDto>, String?))` | 候选角色（id/name/title/avatarId） |
| `typeOptions(): List<RelationTypeOptionDto>` | 24 类型 + reverseCode/reverseLabel + isFamily |
| `add(operatorId, targetOperatorId, relationType, intimacy, note, createReciprocal, onResult(RelationMutationDto?, String?))` | 添加/覆盖 + 可选双向 |
| `update(operatorId, targetOperatorId, relationType, intimacy, note, onResult)` | 覆盖已有关系 |
| `remove(operatorId, targetOperatorId, onResult(Int?, String?))` | 删单向，返回删除行数 |
| `debugRunSelfTest(onDone)` | `--relations-selftest` 自检 |
| `debugSeedDemonstrationFixtures` / `debugCleanDemonstrationFixtures` | `--relations-demo` 截图夹具（note 前缀 `[T57演示]`） |

DTO：`RelationEntryDto`（包含 source/target、related 名称/头像、type/label、reverseType/label、`relationDescription`（oracle 文案；**字段名避开 `NSObject.description`**，Kotlin/Native 导出踩坑同 T36 `RoleDto.intro`）、intimacy、note、direction）、`RelationOperatorDto`、`RelationTypeOptionDto`、`RelationMutationDto`。

### IdentityBridge（`@ObjCName("IdentityBridge")`）

| 方法 | 说明 |
|---|---|
| `getProfile(onResult(IdentityProfileDto?, String?))` | 身份 + 统计 |
| `save(nickname, gender, signature, onResult(IdentityProfileDto?, String?))` | 校验 + 写三键 + 落盘 |
| `debugRunSelfTest(onDone)` | `--identity-selftest`：改名/签名 → NSUserDefaults 核对 → T45 提示词断言 → finally 恢复原值 |

---

## 5. UI

- **RelationsView**（`Features/Relations/RelationsView.swift`）：List + 自定义行（对方 `RoleAvatar`/名称/类型胶囊/方向标签/反向标签/亲密度进度/备注）、`swipeActions` 删除 + `confirmationDialog` 二次确认、空态、错误态、下拉刷新、工具栏「＋」→ 添加 Sheet。
- **添加 Sheet**：选角色 Picker、24 类型 Picker、亲密度 Slider、备注、双向互补 Toggle（显示 `B→A：【互补类型】`；无互补类型时隐藏并说明）。
- **UserProfileView**（`Features/Profile/UserProfileView.swift`）：身份卡（首字头像 + 昵称 + 签名）、编辑卡（昵称必填 / 性别 / 签名）、互动统计卡（5 格）、关于身份说明；保存后轻提示「下一轮聊天生效」；空昵称禁用保存。
- **入口**：`RoleDetailView` 关系卡标题行「管理 ›」（`.navigationDestination(isPresented:)`）；`SettingsView` 二级路由首行「我的身份」。
- **不改** AppRouter/RouteView（避开 T56 写域 `iosApp/App`，改用 SwiftUI 原生 `navigationDestination(isPresented:)`）。

---

## 6. 验收（可自动判定）

| # | 验收项 | 判定方式 | 命令/证据 |
|---|---|---|---|
| 1 | shared framework 链接 + Swift 编译 | 构建日志 0 error | `:shared:linkDebugFrameworkIosSimulatorArm64`；`xcodebuild build` |
| 2 | `--relations-selftest` | 夹具角色添加 2 条关系（1 条双向）→ list 读回 → DB/sqlite 双通道核对 → update → remove（反向保留）→ 错误路径 → 清理还原；日志 RESULT PASS | `Application Support/t57-relations-selftest.log`（`ios-port/handoffs/evidence/t57-relations-selftest.log`） |
| 3 | `--identity-selftest` | 临时改名/性别/签名 → 桥 DTO + `NSUserDefaults.stringForKey` 双通道核对 → T45 真实提示词含三值 → 恢复原值；RESULT PASS | `Application Support/t57-identity-selftest.log` |
| 4 | UI 实跑截图 | 角色详情入口 / 关系列表 / 添加 Sheet / 设置「我的身份」行 / 身份页 | `evidence/t57-*.png` |
| 5 | 数据隔离 | 自检夹具角色/关系全删、身份原值恢复；重启后 DB 行数=基线 | 自检 CLEANUP/RESTORE 行 + sqlite3 核对 |
