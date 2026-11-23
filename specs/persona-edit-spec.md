# 角色人设编辑规格（T49 加急：新建 / 编辑 / 头像 / 人设 / 提示词槽位）

> 代理：impl-persona-edit｜任务：task-49｜状态：实现完成（构建/模拟器验证见
> `ios-port/handoffs/impl-persona-edit.md`）
>
> 范围锚点：《功能对照清单-v1》§1.2 A2：
> `NewOperatorScreen`（`AppNavigation.kt:199` → `OperatorEditScreen.kt:743` 新建表单）与
> `EditOperator`（`AppScreens.kt:142` → `OperatorEditScreen.kt:93` 编辑表单）。

## 1. 数据真源（golden 核对）

`operators` 表（`shared/src/commonMain/sqldelight/com/rhodes/privatechat/shared/db/Operators.sq:1-27`）：
`id/name/title/description/gender/avatarUri/location/activity/emotion/intimacy/privatePrompt/
groupPrompt/memoryInjection/userRelation/lmb/attack/defense/meldPref/activityLevel/voiceName/
voiceSpeed/voicePitch`。**没有 tags / persona 独立列**，因此字段映射如下（与 Android 完全一致）：

| 编辑 UI 字段 | DB 列 / settings 键 | 说明 |
|---|---|---|
| 姓名（必填） | `operators.name` | trim 后必填；同名（忽略大小写）拒绝，`OperatorViewModel.kt:58-70` |
| 标签（逗号分隔） | `operators.title` | Android 原版「身份」字段（`OperatorEditScreen.kt:254-267`）；UI 改称标签，值原样写入 |
| 简介 | `operators.description` | 留空回退 `"<name>，罗德岛干员"`（`OperatorEditScreen.kt:188`） |
| 人设描述（TextEditor） | `operators.privatePrompt` | 当前私聊槽位内容；槽位 1 回退 DB 旧值 |
| 群聊人设 | `operators.groupPrompt` | 当前群聊槽位内容 |
| 初始关系（24 选） | `operators.userRelation` | 值 = Android `RelationshipType` 中文标签（`RelationshipUiMapper.kt:8-32`）；也可自定义旧值 |
| 头像 | `operators.avatarUri` | 内置数字串 `"1".."147"`（T16 裁决，Bundle `avatar_<N>.png`）或媒体 ID `media/<uuid>.<ext>` |
| 性别（选填） | `operators.gender` | Android 同名字段 |
| 提示词槽位（高级） | `operator_prompt_slot_<id>_{private,group}` = 当前槽位 1..3；`operator_prompt_slot_<id>_{private,group}_<n>` = 槽位内容 | 与 Android `OperatorEditScreen.kt:118-119,141-146` 同键同语义；DB 的 private/groupPrompt 存当前槽位内容（`:181-192`） |

其余列（location/activity/emotion/intimacy/lmb/attack/defense/meldPref/activityLevel/voice*）编辑时保旧值
（Android `OperatorViewModel.kt:72-97` 同语义），新建时取 DB/SQL 默认值。

## 2. 目标（DoD）

1. **OperatorBridge.kt（新，shared/iosMain）**：`create / update / load / delete / setAvatarBuiltin /
   setAvatarFromFile / mediaPath / relationTypes`，回调式（Main 队列），无 Flow/suspend 外溢。
2. **RoleEditView（iOS，新建/编辑共用）**：姓名（必填校验）、标签、简介、性别、人设描述（TextEditor）、
   初始关系下拉 + 快捷标签、头像（内置 147 网格 Sheet + 相册 PhotosPicker → 方形裁剪 ≤1024 → 媒体入库）、
   高级折叠区（私聊/群聊提示词槽位 1..3 覆盖）、保存/取消、编辑态删除（二次确认）。
3. **入口**：RolesView 工具栏「新建角色」（与既有「新建聊天」「新建群聊」并存不冲突）；
   RoleDetailView 工具栏「编辑」→ 同一 RoleEditView；保存后刷新列表/详情；新建可选保存后直接进聊天。
4. **验证**：`--persona-selftest` 全链路（新建→列表→编辑姓名/人设/头像/槽位→读回→建会话→删除），
   `sqlite3` 核对 operators 行；模拟器截图证据 `ios-port/handoffs/evidence/t49-*`。

## 3. OperatorBridge 契约

`shared/src/iosMain/.../iosbridge/OperatorBridge.kt`（`@ObjCName("OperatorBridge")`）：

| 方法 | 语义 / 失败回传 |
|---|---|
| `load(operatorId, onResult(dto, error))` | operators 行 + 槽位回填为 `OperatorEditDto` |
| `create(draft, onResult(dto, error))` | 生成 `custom_<uuid>`（对齐 `OperatorEditScreen.kt:101`）；校验姓名/重名；写 DB + 槽位 |
| `update(draft, onResult(dto, error))` | id 必填；同名去重（排除自身）；写 DB + 槽位；`syncOperatorAvatar/Name` 同步会话 |
| `delete(operatorId, onResult(deletedId, error))` | `deleteOperatorWithPrivateData`（清私聊会话/记忆/关系/知识库关联）+ settings 清槽位；id 记入 `deleted_preset_operator_ids` |
| `setAvatarBuiltin(operatorId, number, onResult(avatarUri, error))` | 仅 1..147；写 DB + 同步会话头像 |
| `setAvatarFromFile(operatorId, sourcePath, onResult(mediaId, error))` | 原图已由 Swift 方形裁剪 ≤1024 写临时文件；`MediaRepository.ingest(PICKER)` → `media/<uuid>.<ext>` → 写 DB；入库失败不落 DB |
| `mediaPath(mediaId): String` | 媒体 ID → `file://` 绝对路径（预览）；非媒体/不存在 = `""` |
| `relationTypes(): List<RelationTypeDto>` | 24 种，code = Android `RelationshipType` 枚举名，label = 中文标签 |

DTO 纪律（同 `IosDtos.kt`）：普通 class、只读字段、无集合嵌套导出（槽位用 `privateSlot1..3` 展开，
避免 Swift/Kotlin 泛型桥接歧义）；简介字段名 `intro`（`description` 与 `NSObject.description` 冲突，T36 踩坑）。

## 4. iOS UI 结构（RoleEditView）

```
NavigationStack
├─ 取消 / 保存（保存时转圈；保存成功 dismiss）
├─ 头像卡：预览（内置 Bundle / 媒体入库图 / 首字占位）+「内置头像 1-147」Sheet + PhotosPicker
├─ 基础信息：姓名*、标签、简介、性别
├─ 人设描述：TextEditor（当前私聊槽位）+ 槽位角标
├─ 初始关系：Menu 下拉（24 种）+ 横向快捷胶囊 + 未设定
├─ 高级（DisclosureGroup，默认折叠）：私聊槽位 1/2/3、群聊槽位 1/2/3 编辑
├─ 新建：Toggle「保存后直接进入聊天」（默认开）
└─ 编辑：删除角色（Alert 二次确认，文案对齐 Android「删除干员」）
```

- 头像上传：`PhotosPicker` → `loadTransferable(Data)` → `AvatarImageProcessor.squareJPEG`（居中方形裁剪 +
  最长边 ≤1024px + JPEG 0.9）→ 临时文件 → `setOperatorAvatarFromFile` 入库；
- 内置头像渲染：`UIImage(named: "avatar_<N>")`（Bundle 147 张，T16 裁决不变）；
- 关系值：下拉选中写中文标签（与既有 `operators.userRelation` 历史值兼容，未命中选项则原样显示）。

## 5. 验证矩阵

| # | 步骤 | 断言 | 证据 |
|---|---|---|---|
| 1 | `relationTypes` | count ≥ 20（实际 24），code 与 Android 枚举一致 | `t49-selftest.log` |
| 2 | 新建角色（姓名/标签/人设/简介/关系/头像 7/槽位 2-3） | id 前缀 `custom_`；字段逐项相等 | 同上 |
| 3 | 角色列表 | 新 id 出现在 `RoleBridge.list` | 同上 |
| 4 | 编辑（姓名+人设+标签+关系+头像 42+槽位改） | 读回字段相等；槽位 2/3 覆盖 | 同上 |
| 5 | 读回（模拟重启） | `load` 返回编辑后值；`RoleBridge.find` 名字/头像已变 | 同上 |
| 6 | 内置头像切换 99 | 返回 `"99"` | 同上 |
| 7 | 新角色开聊 | `startChat` 生成会话，`RoleBridge.find` 带会话且名字为新 | 同上 |
| 8 | 删除测试角色 | 列表消失；`load` 失败；清测试数据 | 同上 |
| 9 | DB 直查 | `sqlite3 rhodes.db "select ... from operators where id='<id>'"` | `t49-db-query.txt` |
| 10 | UI 截图 | 新建表单 / 内置头像网格 / 高级槽位 / 编辑表单 / 列表入口 / 详情入口 | `t49-*.png` |

## 6. 取舍与限制

- **标签列**：operators 无 tags 列，标签按 Android「身份(title)」语义存储（逗号分隔原样）；
- **上传头像在列表/详情**：`RoleAvatar` 仍只解析内置数字（T16 裁决 + DesignSystem 非本卡写域），
  上传头像在编辑页内可预览，列表/详情显示首字占位；媒体 ID 渲染接线见后续波次
  （`RoleAvatar.swift:6` 注释已预留 `MediaRepository.resolve`）；
- **关系网编辑**（多角色关系 BFS）/ 知识库关联 / AI 人设生成助手不在本卡（T49 加急范围只到单角色
  userRelation + 提示词槽位）；Android 原版这些区保留给后续 A2 波次；
- **语音音色 / 活跃度 / 动态权限** 等 Android 编辑页其余分区本卡不铺（字段保旧值），避免与 T50/T51
  写域交叉；`operators` 写路径已为后续区留好 `OperatorDraft` 扩展位。
