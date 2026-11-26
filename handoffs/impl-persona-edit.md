# T49 实现 handoff：角色人设编辑（新建 / 编辑 / 头像 / 人设 / 提示词槽位）

> 代理：impl-persona-edit（task-49）｜规格：`ios-port/specs/persona-edit-spec.md`（本卡产出）
> 状态：**实现完成；Shared.framework（含 OperatorBridge）重链 + `xcodebuild` BUILD SUCCEEDED；
> iPhone 17 / iOS 27.2 专用模拟器实跑 `--persona-selftest` PASS=16 / FAIL=0；DB（sqlite3）与截图证据齐全**
> 验收：新建（姓名必填/标签/简介/性别/人设/关系/内置 147 头像/槽位）✓ 编辑（含 prompt 槽位覆盖）✓
> 相册头像方形裁剪 ≤1024 + 媒体入库 ✓（自动裁剪器已实现；相册选择需人工点按，见 §6）
> RolesView「新建角色」+ RoleDetailView「编辑」入口 ✓ 保存后刷新 ✓ 新建后可选直接进聊天 ✓
> git：**本代理不提交**（lead 已在 a2fbb62 统一提交，工作树干净）

## 1. 写域 / 交付物清单

| 路径 | 类型 | 说明（file:line / 关键点） |
|---|---|---|
| `RhodesLink/shared/src/iosMain/.../iosbridge/OperatorBridge.kt` | 新增（384 行） | `load/create/update/delete/setAvatarBuiltin/setAvatarFromFile/mediaPath/relationTypes`；`OperatorEditDto` / `OperatorDraftDto` / `RelationTypeDto`；槽位键 `operator_prompt_slot_<id>_{private,group}_{1..3}`；对齐 Android `OperatorViewModel.kt:27-160,173-213` 与 `OperatorEditScreen.kt:99-192` |
| `.../iosbridge/IosAppGraph.kt` | 最小 patch（2 处） | `operatorBridge` 字段 + `fun operators(): OperatorBridge`（与 `roles()` 并列） |
| `RhodesLink/iosApp/RhodesLink/Bridge/OperatorClient.swift` | 新增（234 行） | `OperatorEditItem` / `OperatorDraft` / `OperatorRelationType`；`AppGraphClient` 扩展：`loadOperatorEdit/createOperator/updateOperator/deleteOperator/setOperatorAvatarBuiltin/setOperatorAvatarFromFile/operatorRelationTypes/operatorMediaPath`（Kotlin 回调 → async/await） |
| `.../Bridge/AppGraphClient.swift` | 追加 4 行 | `var operatorBridge: OperatorBridge { graph.operators() }`（沿用 T37/T43/T47/T48 的「桥对象只在 AppGraphClient 暴露」约定） |
| `.../Features/Roles/RoleEditView.swift` | 新增（803 行） | 新建/编辑共用表单：姓名*、标签、简介、性别、人设 TextEditor、初始关系（24 种 Menu + 快捷胶囊）、头像卡（内置 147 网格 Sheet + PhotosPicker 方形裁剪 ≤1024）、折叠「高级：提示词槽位覆盖」、删除（二次确认）、新建「保存后直接进入聊天」开关；`RoleEditTarget`（sheet item 目标） |
| `.../Features/Roles/RolesView.swift` | 修改（+38/-3） | 工具栏改为 `ToolbarItemGroup`：`person.crop.circle.badge.plus`「新建角色」+ 原「+」新建聊天（与新建群聊入口并存不冲突）；`.sheet(item:)` 弹 RoleEditView；保存/删除后 `reload()` |
| `.../Features/Roles/RoleDetailView.swift` | 修改（+27） | 工具栏「编辑」（`square.and.pencil`）→ RoleEditView(roleId: role.id)；保存后 `load()` 刷新详情；删除后返回上一页 |
| `.../Features/Roles/PersonaAutomation.swift` | 新增（280 行） | `--persona-selftest` 全链路自检 + 独立日志 `Documents/ios-persona-selftest.log`；截图辅助参数 `--persona-edit-new/--persona-edit-id/--persona-avatar-sheet/--persona-advanced`；keep/cleanup 证据模式 |
| `.../Features/Roles/RolesAutomation.swift` | 修改（+7） | App 启动自检链挂载 `PersonaAutomation.runIfNeeded` / `runCleanupIfNeeded`（无需进入联系人 Tab） |
| `ios-port/specs/persona-edit-spec.md` | 新增 | 数据映射/桥接契约/UI 结构/验证矩阵/取舍 |
| `ios-port/handoffs/evidence/t49-*` | 新增 15 项 | 截图 8 张 + 日志/DB/头文件证据（见 §5） |

**未改**（纪律）：`Settings/**`、`Chat/**`、`RouteView.swift`、`ChatEngineBridge.kt`、Android `app/`、`shared/commonMain`。
与 T50/T51 的共享文件（AppGraphClient/RolesView/RolesAutomation）只做追加/局部替换。

## 2. 原版对照（详见 spec §1/§3）

| Android 原版 | iOS 实现 | 一致性 |
|---|---|---|
| 新建角色（`AppNavigation.kt:199` → `OperatorEditScreen.kt:743`） | RolesView「新建角色」→ RoleEditView(roleId: nil) | ✓ |
| 编辑角色（`AppScreens.kt:142` → `OperatorEditScreen.kt:93`） | RoleDetailView「编辑」→ RoleEditView(roleId:) | ✓ |
| `custom_${UUID.randomUUID()}` 新 id（:101） | `OperatorBridge.create` → `custom_<NSUUID 去横线>` | ✓ 语义一致（值域不同） |
| 姓名必填 + 同名去重（`OperatorViewModel.kt:58-70`） | 桥内同名校验（忽略大小写，排除自身） | ✓ |
| `title`（身份） | UI 改名「标签（逗号分隔）」→ 同列 `title` | ✓（表无 tags 列，见 spec §1） |
| `description` 留空回退 `"<name>，罗德岛干员"` | 桥内同回退 | ✓ |
| 私聊/群聊人设槽位 1..3（`OperatorEditScreen.kt:118-146,181-192`） | 高级区槽位编辑 + 桥写 `operator_prompt_slot_*` + DB 当前槽位内容 | ✓ 键名/语义逐字一致 |
| 内置头像 1..147 Bundle（T16 裁决） | 147 网格 Sheet（`avatar_<N>`）+ `setAvatarBuiltin` | ✓ |
| 相册头像裁剪（1:1）+ 内部存储（`copyToInternalStorageAsync`） | PhotosPicker → `AvatarImageProcessor.squareJPEG`（1:1，≤1024px，JPEG0.9）→ `MediaRepository.ingest(PICKER)` → `media/<uuid>.jpg` | ✓（iOS media ID 语义，见 message-id-media-spec） |
| 关系（`userRelation` 自由文本，20+ 类型） | 24 种 `RelationshipType` 中文标签下拉 + 快捷胶囊 + 自定义旧值兼容 | ✓ |
| 删除干员（确认弹窗 + 清私聊会话/记忆/关系/知识库关联 + `deleted_preset_operator_ids`） | `OperatorBridge.delete` 同语义 + RoleEditView 删除按钮 | ✓ |
| 保存后同步已有会话头像/标题（`OperatorViewModel.kt:124-129`） | `chatRepo.syncOperatorAvatar/Name` | ✓ |

## 3. 构建

```bash
cd /Users/jackchen/Documents/deepseek-harness/default-workspace
source ios-port/toolchain/env.sh
cd RhodesLink
./gradlew --no-daemon :shared:linkDebugFrameworkIosSimulatorArm64
# 本卡未单独产 framework 日志：OperatorBridge 由共享工具链 link（框架 12:17 首含桥，12:54 重链仍在）：
#   evidence/t49-bridge-header.txt（Shared.h 中 8 个导出方法 + DTO 构造函数）
cd iosApp
TMPDIR="$PWD/tmp" xcodebuild build -project RhodesLink.xcodeproj -scheme RhodesLink \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath "$PWD/DerivedData-t49" CODE_SIGNING_ALLOWED=NO
# → ** BUILD SUCCEEDED **（evidence/t49-build.log，独立 DerivedData-t49）
```

构建纪律：`pgrep -x xcodebuild` / `pgrep -f GradleDaemon` 排队；全程未与其它卡同时 xcodebuild（T45/T46/T48 的 gradle
在共享工具链上并行重链，中途 T48 的 Knowledge 文件半成品导致过一次 BUILD FAILED，其修复后重跑通过）。
**经验**：本次 T45/T48/T50/T51 同时在**同一台模拟器**（A63CD17A…）装/删/启动同一 bundle，导致截图为他人运行态；
改用 `xcrun simctl create "iPhone 17 T49" "iPhone 17" <iOS 27.2 runtime>` 专用设备完成全部证据采集（已删除）。

## 4. 验证

### 4.1 桥接层全链路自检（`--persona-selftest`，专用 iPhone 17 / iOS 27.2）

`evidence/t49-selftest.log`（最终一次，PASS=16 FAIL=0）：

```
PASS relationTypes count=24
PASS create id=custom_fd0687ae... name=T49测试角色…
PASS create fields tags=测试,毒舌 avatar=7 relation=朋友
PASS create slots privateSlots=[…, "私聊槽位2-新建", "私聊槽位3-新建"]
PASS create group slot groupSlots=["群聊槽位1-新建", "", ""]
PASS list contains created count=148
PASS update name=T49测试角色…改 avatar=42
PASS update slot override privateSlots=[...] groupActive=2
PASS readback name=…改 persona=更新后的人设… tags=测试,已编辑 avatar=42
PASS role dto after edit name=…改 avatar=42 relation=对手
PASS setAvatarBuiltin avatarUri=99
PASS startChat session=session_custom_…
PASS session uses edited role session=session_custom_… name=…改
PASS delete id=custom_fd0687ae…
PASS list after delete count=147
PASS read after delete fails
=== persona selftest done PASS=16 FAIL=0 ===
```

### 4.2 keep 模式 + DB 直查（`--persona-selftest --persona-selftest-keep`）

`evidence/t49-selftest-keep.log`（PASS=13 FAIL=0，`KEEP role=custom_877405e5…`）→ `evidence/t49-db-query.txt`：

```
operators 行：custom_877405e5…|T49测试角色1795411163改|测试,已编辑|T49 自动化创建的角色|99|
              更新后的人设：毒舌但关键时刻可靠。|群聊槽位2-已编辑|对手|女
（列序：id|name|tags(title)|description|avatarUri|privatePrompt|groupPrompt|userRelation|gender）
NSUserDefaults 槽位键：…_private=1 / …_private_1..3 / …_group=2 / …_group_1..3（内容与编辑值一致）
清理后（--persona-cleanup custom_877405e5…）：rows_left=0，operators_total=147
```

`KEEP` 角色详情截图 `t49-created-role-detail.png`：名字=…改、标签=测试,已编辑、关系=对手、头像=内置 99、
已有会话在线 —— 证明编辑结果经 `RoleBridge`（真实 DB）投影到角色详情。

### 4.3 UI 实跑截图（专用模拟器，全部为本次构建）

| 截图 | 内容 |
|---|---|
| `t49-roles-toolbar.png` | 联系人 Tab：右上「新建角色」+「+」（新建聊天）两个入口，147 角色 / 1 群聊 |
| `t49-detail-entry.png` | 角色详情（阿米娅）右上「编辑」铅笔入口 |
| `t49-new-role-form.png` | 新建角色表单：头像卡、姓名/标签/简介/性别、人设描述（私聊槽位 1） |
| `t49-avatar-grid.png` | 内置头像 1-147 网格 Sheet（Bundle 资源全量渲染） |
| `t49-edit-existing.png` | 编辑角色（阿米娅）：name/tags/intro/gender/人设 全部从真实 DB 回填，标题「编辑角色」 |
| `t49-advanced-slots.png` | 高级区（自动展开滚动）：私聊槽位 1/2/3 + 群聊槽位 1/2/3 + 槽位键说明 + 删除角色 |
| `t49-created-role-detail.png` | keep 模式测试角色详情：编辑后字段落库并投影 |
| `t49-selftest-end.png` | 完整自检结束画面（会话/角色状态正常） |

## 5. 证据清单（`ios-port/handoffs/evidence/`）

| 文件 | 内容 |
|---|---|
| `t49-build.log` | xcodebuild ** BUILD SUCCEEDED ** |
| `t49-bridge-header.txt` | Shared.framework 中 OperatorBridge 8 个 ObjC/Swift 导出方法 + DTO 构造函数 |
| `t49-selftest.log` | 完整自检（含删除）PASS=16 FAIL=0 |
| `t49-selftest-keep.log` | keep 模式 PASS=13 FAIL=0 + KEEP role id |
| `t49-db-query.txt` | sqlite3 operators 行 + NSUserDefaults 槽位键 + 清理后行数 |
| `t49-selftest-end.png` | 完整自检结束画面 |
| 6 张 UI 截图（§4.3） | 入口/表单/头像网格/编辑/高级区/详情 |

## 6. 限制 / 取舍（与 spec §6 一致）

1. **标签列**：`operators` 无 tags 列（golden schema 核对），标签按 Android「身份(title)」语义写入 `title`；
2. **上传头像在列表/详情仍显示占位**：`RoleAvatar`（DesignSystem，非本卡写域）只解析内置数字；编辑页内已可预览
   上传图（`mediaPath` → `UIImage`），列表/详情渲染接线留给后续波次（`RoleAvatar.swift:6` 已注明）；
3. **相册选择未做自动化点按**：PHPicker 需人工点按，自动化只覆盖「裁剪器 + 入库桥」代码路径与临时文件写入日志；
   `PhotosPicker`/`AvatarImageProcessor` 编译与链接通过，未在模拟器内自动触发系统相册；
4. **关系网编辑 / 知识库关联 / AI 人设生成助手 / 语音音色 / 活跃度 / 动态权限**（Android 编辑页其余分区）不在
   本卡加急范围——桥保存时保留旧值，UI 未铺；`OperatorDraft` 已留扩展位；
5. **模拟器共享竞争**：见 §3 经验；证据采集使用专用克隆设备，避免与 T45/T48/T50/T51 互相干扰；
6. 测试辅助参数仅在启动参数存在时生效，对用户路径零影响；keep/cleanup 模式仅用于产出 DB 证据，已清理测试角色
   （`operators_total=147`，无 `T49测试角色%` 残留）。
