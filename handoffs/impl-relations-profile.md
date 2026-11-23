# T57 交付：关系网编辑 + 用户身份页

> 代理：impl-relations-profile ｜ 任务：task-57（Phase 4 剩余页面）｜ 状态：**实现完成，shared framework + xcodebuild 编译通过；`--relations-selftest` 26/26 PASS、`--identity-selftest` 17/17 PASS（均 RESULT PASS）；iPhone 17 专用模拟器 T57-sim（380EB649-61C3-434F-8950-39E63BFD6F09）UI 实跑 + 截图取证通过（2026-11-23）**
> 规格卡：`ios-port/specs/relations-profile-spec.md`（T57 产出，含 oracle 口径/契约/验收）。
> git：**未提交**（lead 统一提交）。T57 主体已随 lead 的 `cf22383`（2026-11-23 17:05）进入 `ios-port` 分支；本卡最后一处「添加 Sheet 截图钩子轮询等待」补丁为工作树未提交改动（`iosApp/RhodesLink/Features/Relations/RelationsView.swift`）。

## 1. 交付摘要

| 能力 | 实现 | 数据源 |
|---|---|---|
| 关系网读取 | 出向 + 入向合并列表（同 related 出向优先去重），对方名称/头像/类型标签/反向标签/方向/亲密度/备注/描述 | 真实 `relationships` + `operators` |
| 添加关系 | 24 种类型 Picker + 亲密度 + 备注 + 「同时创建双向互补关系」开关（oracle `complementType` 映射逐条复刻） | `RelationshipRepository.insertRelationship`（已存在覆盖 type/intimacy/note） |
| 更新关系 | Bridge `update`：覆盖 type/intimacy/note（关系不存在报错）；UI 当前以「删除后重加」为主路径（见 §6 限制） | 同上 |
| 删除关系 | 列表滑动删除 + 二次确认；按 (source→target) 精确删单向行，不连带反向（oracle 单向语义） | `relationshipsQueries.deleteRelationship` |
| 关系网入口 | 角色详情「关系网」卡片标题行「管理 ›」→ `RelationsView`（`navigationDestination(isPresented:)`，不改 AppRouter） | — |
| 用户身份读取 | 昵称/性别/签名 + 首字头像；真实统计：消息/记忆/会话/角色/关系 | settings `user_name/user_gender/user_signature/user_avatar_uri` + SQLite |
| 用户身份保存 | 昵称 trim 必填、写三键 + `NSUserDefaults.synchronize()` 落 plist；下一轮聊天生效（T45 提示词 USER_NAME/USER_GENDER/USER_BIO） | `SettingsRepository.userName/userGender/userSignature` |
| 我的身份入口 | 设置主页二级路由首行「我的身份」→ `UserProfileView` | — |
| 自检 | `--relations-selftest`（夹具角色→两条关系（含双向）→list 读回→DB/sqlite 双通道→update→remove（反向保留）→错误路径→清理还原）；`--identity-selftest`（改名/性别/签名→DTO+NSUserDefaults 双通道→T45 真实提示词断言→恢复原值） | 真实 DB/settings，无 mock |

## 2. 文件清单

| 侧 | 类型 | 文件 | 说明 |
|---|---|---|---|
| shared iosMain | 新增 | `iosbridge/RelationBridge.kt`（≈610 行） | `@ObjCName("RelationBridge")`：`list`(:52)/`operators`(:65)/`typeOptions`(:87)/`add`(:101)/`update`(:121)/`remove`(:143)/`debugRunSelfTest`(:158)/演示夹具(:316,:342)；互补映射 `complementType`(:530)；4 个 DTO（`RelationEntryDto`:582 等） |
| shared iosMain | 新增 | `iosbridge/IdentityBridge.kt`（≈260 行） | `@ObjCName("IdentityBridge")`：`getProfile`(:44)/`save`(:59)/`debugRunSelfTest`(:79)/`IdentityProfileDto`(:241) |
| shared iosMain | 修改（最小） | `iosbridge/IosAppGraph.kt` | +2 桥字段(:67-70)、+`relations()`/`identity()`(:279-283)、+`maybeRunT57Hooks()`(:285-330) 启动参数钩子 |
| shared iosMain | 修改（1 行） | `iosbridge/OperatorBridge.kt` | `RELATION_TYPES` private→internal（T49 关系类型目录复用，唯一真源） |
| iosApp Swift | 新增 | `Bridge/RelationsClient.swift` | Swift 模型（`RelationEntryItem`/`RelationOperatorItem`/`RelationTypeOptionItem`（含 oracle 配色族 tint）/`RelationMutationItem`）+ `loadRelationEntries`(:147)/`addRelation`(:180)/`updateRelation`/`removeRelation`(:239)/`cleanRelationDemoFixtures` |
| iosApp Swift | 新增 | `Bridge/ProfileClient.swift` | `UserProfileItem`/`UserProfileStat` + `loadUserProfile`(:61)/`saveUserProfile`(:77) |
| iosApp Swift | 新增 | `Features/Relations/RelationsView.swift`（≈420 行） | 关系列表（List + swipeActions 删除 + confirmationDialog）、空/错误/加载态、下拉刷新、添加 Sheet（`RelationAddSheet`:308：选角色 Picker + 类型 Picker + 关系描述 + 亲密度 Slider + 备注 + 双向互补 Toggle） |
| iosApp Swift | 新增 | `Features/Profile/UserProfileView.swift`（≈330 行） | 身份卡（首字头像/昵称/签名）、编辑卡（昵称必填/性别/签名）、互动统计卡（5 格真实数据）、关于身份说明；保存轻提示 |
| iosApp Swift | 修改（最小） | `Bridge/AppGraphClient.swift` | +`relationBridge`/`identityBridge` 2 个 getter |
| iosApp Swift | 修改（最小） | `Features/Roles/RoleDetailView.swift` | 关系卡标题行 +「管理 ›」入口、`.navigationDestination(isPresented:)`、`--relations-demo`/`--relations-demo-add` 自动打开、`--t57-role-detail-scroll` 截图滚动钩子 |
| iosApp Swift | 修改（最小） | `Features/Settings/SettingsView.swift` | routeRowsCard 首行「我的身份」、`.navigationDestination(isPresented:)`、`--identity-demo` 自动推入 |
| 文档 | 新增 | `ios-port/specs/relations-profile-spec.md` | 规格卡（口径/契约/验收/限制） |
| 证据 | 新增 | `ios-port/handoffs/evidence/t57-*` | 见 §4 |

## 3. 关键口径（与 Android 1.14.27 oracle 对齐）

1. **关系单向**：`relationships(operatorId→relatedOperatorId, type)`；列表 = `getRelationships`（`ORDER BY intimacy DESC`，`Relationships.sq:17-18`）+ `getReverseRelationships`，入向行 `direction="in"` 且源/目标互换展示。与 oracle `RelationshipScreen.kt:57-180` 同语义；oracle 说明文案也明确「关系可以是单向的」。
2. **添加/覆盖** = `RelationshipRepository.insertRelationship`（`:53-63`）：同向已存在仅更新 type/intimacy/note，不重复插入；`intimacy` 压 0..100；`relatedOperatorName` 取 operators 当前名称。
3. **双向互补** = oracle `RelationshipScreen.kt:372-398` 映射逐条复刻（`RelationBridge.kt:530`）：姐姐↔妹妹、哥哥↔弟弟、母亲/父亲→女儿、女儿→母亲、儿子→父亲、上司↔下属、队长↔队员、导师↔学生、朋友/挚友/战友/队友/对手/情敌/恋人/家人同类型、监护人→家人、**暗恋对象（CRUSH）无互补**；默认不创建（oracle 开关默认关）。
4. **删除单向** = `deleteRelationship`（`Relationships.sq:44-46`）：只删 (source,target) 一行，反向行保留（自检 `remove.ab.reverse.kept` 断言）。
5. **列表文案** = oracle `RelationshipUiMapper.description`：「A是B的【类型】」。
6. **用户身份键** = `user_name`（默认「博士」）/`user_gender`（默认「男」）/`user_signature`（默认空）；保存 = `MainViewModel.updateUserProfile :2129-2131` 三键赋值；空昵称拒绝 = `ProfileSettingsScreen.kt:110`。
7. **等级/经验**：Android 1.14.27 **无**用户 level/exp 字段（全仓 grep `user_level|user_exp` 无命中）→ 任务口径「无则显示消息数/记忆数统计」；身份页展示真实统计：消息（`chat_messages` 总数）/记忆（`memories` 长期印象，与设置页「N 条记忆」同源）/会话/角色/关系。
8. **提示词接线**：身份值经 T45 `PrivatePromptWiring.userProfile()` → `PrivatePromptAssembler.kt:164` 的 `USER_NAME/USER_GENDER/USER_BIO` 占位符进入私聊提示词；自检用真实装配器 + 临时确定性模板断言三值出现后原样恢复用户模板。
9. **NSObject.description 踩坑**：Kotlin DTO 字段名 `description` 会被 Swift 解析为 `NSObject.description`（截图里显示成 `com.rhodes...Relation...`），已改名 `relationDescription`（同 T36 `RoleDto.intro` 处置）——本卡修复后截图/自检均正常。

## 4. 验证证据

### 4.1 构建

| 步骤 | 结果 | 证据 |
|---|---|---|
| `./gradlew --no-daemon :shared:linkDebugFrameworkIosSimulatorArm64` | BUILD SUCCESSFUL；`Shared.h` 含 `RelationBridge`/`IdentityBridge`/DTO（`relationDescription`） | `evidence/t57-framework-link.log` |
| `xcodebuild build`（generic iOS Simulator，DerivedData-t57，CODE_SIGNING_ALLOWED=NO） | **BUILD SUCCEEDED**，0 error | `evidence/t57-xcodebuild.log` |

### 4.2 桥层自检（模拟器 T57-sim）

| 自检 | 结果 | 证据 |
|---|---|---|
| `--relations-selftest` | **RESULT PASS（26 PASS / 0 FAIL）**：DB 文件存在；夹具 3 角色；A→B 挚友（含双向互补）+ A→C 上司；`db.row.*`（repository）与 `db.sql.ab`（原始 SQL）双通道核对；`list.a.count=2`、类型标签「挚友」、反向标签、描述文案、对方名称/头像字段；B 侧 direction=out；update 为导师/75/备注；remove 后反向保留；`db.count.delta base=0 after=2`；错误路径（自己/未知类型/删不存在）；`catalog.24`（暗恋对象无反向）；CLEANUP 删夹具角色后 `relations.count` 回基线 | `evidence/t57-relations-selftest.log` |
| `--identity-selftest` | **RESULT PASS（17 PASS / 0 FAIL）**：保存 DTO 读回；5 项统计；`NSUserDefaults.stringForKey` 三键原文核对；空昵称拒绝；**真实提示词 `prompt.built chars=9836` 且含临时昵称/性别/签名**（`PROMPT_USER_LINE=姓名：T57身份自检…`）；finally 恢复原值（博士/男/空）且 `restore.*` PASS | `evidence/t57-identity-selftest.log` |

### 4.3 DB / NSUserDefaults 直接核对（`simctl get_app_container` + sqlite3 + plutil）

`evidence/t57-db-defaults-check.txt`：
- `select count(*) from relationships` → **0**（自检夹具 + `--relations-fixture-clean` 演示夹具均已清理）；
- operators = 147（预设角色）；
- `user_name=博士 / user_gender=男 / user_signature=""`（identity selftest 已恢复原值）；
- `relationships` 建表/唯一索引与 `golden/db/schema/Relationships.sq` 一致（含 `relationships_operator_related_unique`）。

### 4.4 UI 实跑截图（final build，iPhone 17 T57-sim）

| 截图 | 内容 |
|---|---|
| `evidence/t57-role-detail-entry.png` | 角色详情「关系网」卡：`2 条 管理 ›`，两条关系行（M3 挚友 88 / Misery 对手 35）→ 入口可见 |
| `evidence/t57-relations-list.png` | 「阿米娅的关系网」：说明卡、分组「我对对方 2」、对方头像/名称/方向标签「我对 TA」/类型胶囊（挚友·青 / 对手·红）/反向标签/亲密度进度/备注；导航栏「＋」 |
| `evidence/t57-relations-add-sheet.png` | 添加关系 Sheet：关系对象 Picker（Scout · 罗德岛精英狙击手）、关系类型「朋友」、关系描述「阿米娅是Scout的【朋友】」、亲密度 50、备注、双向互补开关（`Scout → 阿米娅：【朋友】`） |
| `evidence/t57-settings-row.png` | 设置主页二级路由首行「我的身份」 |
| `evidence/t57-profile.png` | 我的身份页：身份卡（首字「博」+ 博士）、编辑卡（昵称/性别/签名）、互动统计（消息 0 / 记忆 0 / 会话 2 / 角色 147 / 关系 2）、说明；保存按钮 |

## 5. 复现命令

```bash
cd /Users/jackchen/Documents/deepseek-harness/default-workspace && source ios-port/toolchain/env.sh
cd RhodesLink && ./gradlew --no-daemon :shared:linkDebugFrameworkIosSimulatorArm64
cd iosApp && TMPDIR="$PWD/tmp" xcodebuild build -project RhodesLink.xcodeproj -scheme RhodesLink \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath "$PWD/DerivedData-t57" CODE_SIGNING_ALLOWED=NO

DEV=380EB649-61C3-434F-8950-39E63BFD6F09   # T57-sim
APP=DerivedData-t57/Build/Products/Debug-iphonesimulator/RhodesLink.app
xcrun simctl install $DEV "$APP"
# 自检（读 App 容器 Library/Application Support/t57-*.log）
xcrun simctl launch $DEV com.rhodeslink.app --relations-selftest
xcrun simctl launch $DEV com.rhodeslink.app --identity-selftest
# 截图（演示夹具由 shared 启动钩子写入，note 前缀 [T57演示]）
xcrun simctl launch $DEV com.rhodeslink.app --open-url rhodes://role/amiya --t57-role-detail-scroll
xcrun simctl launch $DEV com.rhodeslink.app --relations-demo --open-url rhodes://role/amiya
xcrun simctl launch $DEV com.rhodeslink.app --relations-demo --relations-demo-add --open-url rhodes://role/amiya
xcrun simctl launch $DEV com.rhodeslink.app --open-url rhodes://settings --settings-route-rows
xcrun simctl launch $DEV com.rhodeslink.app --identity-demo --open-url rhodes://settings
xcrun simctl io $DEV screenshot t57-xxx.png
# 清理演示夹具
xcrun simctl launch $DEV com.rhodeslink.app --relations-fixture-clean
```

## 6. 写域 / 交叉与限制

### 6.1 写域

| 文件 | 说明 |
|---|---|
| `shared/.../iosbridge/{RelationBridge,IdentityBridge}.kt` | 新增（T57 私有） |
| `shared/.../iosbridge/{IosAppGraph,OperatorBridge}.kt` | 共享文件最小 patch（只追加 T57 段 / 1 行可见性） |
| `iosApp/.../{RelationsClient,ProfileClient}.swift`、`Features/{Relations,Profile}/**` | 新增 |
| `iosApp/.../{AppGraphClient,RoleDetailView,SettingsView}.swift` | 最小 patch（新增 getter/入口/截图钩子；未改既有逻辑） |
| `ios-port/{specs,handoffs}` | 文档与证据 |

未改：AppRouter/RouteView（避开 T56 写域，改用 SwiftUI `navigationDestination(isPresented:)`）、Chat/Moments/Diary/Knowledge 页、commonMain/`.sq`/schema。

### 6.2 限制 / 后续

1. **关系编辑 UI 未做「修改弹窗」**：Bridge 已提供 `update`（含自检断言），UI 当前以「滑动删除 + 添加」为主路径；oracle 编辑对话框的「亲密度/备注修改」可与添加 Sheet 复用（小改动）。
2. **入向关系删除**：列表允许滑动删除入向行（按 DTO 的 source→target 精确删），但 UI 未单独提示「这是对方对你的关系」的删除语义差异；当前确认弹窗已写明方向与「反向关系不会自动删除」。
3. **用户头像编辑未移植**：Android `ProfileSettingsScreen` 有上传头像/预设色块；本卡身份页只显示首字头像并读取 `user_avatar_uri`（不写）。T49 已具备媒体入库能力，可作后续增强。
4. **无用户等级/经验**：按任务口径显示真实统计；若后续版本加 level/exp 字段，`IdentityBridge` 只需扩展 DTO。
5. **角色详情关系卡头像**：T36 既有卡片用 `RoleAvatar(avatarId: "", ...)` 首字占位（本卡未改该卡片渲染，只加「管理」入口）；RelationsView 列表使用真实 `operators.avatarUri`。
6. **演示/截图夹具**：`--relations-demo` 给 amiya 写 2 条 note=`[T57演示]…` 的关系；`--relations-fixture-clean` 精确清理（已在最终取证后执行，DB relationships=0）。自检夹具使用独立 `t57_rel_*` 临时角色且 finally 删除，不触碰真实角色。
7. **环境备注（iCloud dataless 抖动）**：本次收尾期间工作区 iCloud 同步故障导致 `Stale NFS file handle`（gradle wrapper/JDK `conf/security/java.security`/gradle dist jar/`shared/build` 增量缓存）。处置：`brctl download <file>` 逐个物化 + 删除 `shared/build/classes/kotlin/iosSimulatorArm64` 增量缓存后重链；均未 kill 构建。最终构建与本自检在该环境下通过。
8. **task/自检范围**：`--relations-selftest` / `--identity-selftest` 是 shared 启动钩子（纯 Kotlin，未带参数零开销）；Swift UI 侧以真实模拟器截图与 SelfTestLog 打点取证（未做 XCUITest 点击自动化）。
