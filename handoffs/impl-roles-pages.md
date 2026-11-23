# T36 实现 handoff：角色/关系页 + 会话列表（Phase 4 UI 波次一）

> 代理：impl-roles-pages（task-36）｜规格：`ios-port/specs/ios-roles-pages-spec.md`（本卡产出）
> 状态：**实现完成，Shared.framework 重链 + xcodebuild 通过，iPhone 17 / iOS 27.2 模拟器实跑通过**
> 验收：角色列表真实数据（147 位，真实头像 Bundle 解析）✓ / 角色详情 + 关系网 + 印象 + 等级 ✓ /
> 新建会话 → 进入聊天 ✓ / 未读角标 + 最后消息 + 时间 ✓ / 深色 + Dynamic Type ✓ / 空态/错误态 ✓
> git：**不提交**（lead 统一提交）；与 T34 写域交叉文件按「只追加 + edit」协作，无互相覆盖。

## 1. 写域确认 / 交付物清单

| 路径 | 类型 | 说明（file:line） |
|---|---|---|
| `RhodesLink/shared/src/iosMain/.../iosbridge/RoleBridge.kt` | 新增 | 角色/关系网桥：`list` :41、`find` :59（兼容旧 sessionId 深链）、`relations` :75（out+in 合并去重）、`startChat` :117（`getOrCreateSession`）、`loadIndex`/`pickSessions` :151、DTO 映射 :169 |
| `RhodesLink/shared/src/iosMain/.../iosbridge/IosDtos.kt` | 修改（追加） | `RoleDto` :60（16 字段；**`intro` 而非 `description`** :67，见 §6.1）、`RelationDto` :88（含 direction out/in） |
| `RhodesLink/shared/src/iosMain/.../iosbridge/IosAppGraph.kt` | 修改（追加 2 处） | `roleBridge` 字段 :58、`roles(): RoleBridge` :83（与 T34 的 historyBridge 并存） |
| `RhodesLink/iosApp/RhodesLink/DesignSystem/RoleAvatar.swift` | 新增 | Bundle 内置头像 1..147 数字解析 + 占位回退 + `RoleAvatarProbe`（`builtin:<N>`/`placeholder:<值>`/`bundledAvatarCount()`） |
| `.../Bridge/AppGraphClient.swift` | 修改（类内末尾追加） | `RoleItem` / `RelationItem` Swift 模型 + `loadRoles` / `loadRole` / `loadRelations` / `startChat`（Kotlin 类型不外泄） |
| `.../Features/Roles/RolesView.swift` | 重写 | 真实角色列表：头部玻璃卡（147 位/已有会话数）、搜索、等级/关系/会话状态、下拉刷新、空态/错误态/搜索无结果态 |
| `.../Features/Roles/RoleDetailView.swift` | 重写 | 资料卡（玻璃 1）+ 等级与关系 + 长期印象（L3）+ 角色资料 + 关系网 + 进入聊天；无会话说明创建 `session_<id>`；错误/不存在态 |
| `.../Features/Roles/NewChatView.swift` | 新增 | 新建聊天选角页：已有会话直接进 / 否则 `startChat` → `openChat`；搜索/空态/错误/行内 loading |
| `.../Features/Roles/RolesAutomation.swift` | 新增 | 启动参数验证钩子 `--roles-selftest` / `--autochat <operatorId>`（与 UI 同一代码路径） |
| `.../Features/Chat/ChatListView.swift` | 修改 | 右上「+」→ `openNewChat()`；会话头像改用 `RoleAvatar`（session.avatarUri 数字串）；空态 action 改「新建聊天」 |
| `.../App/AppRouter.swift` | 修改 | `Route.newChat` :61、`roleDetail(roleId:)` :63、`openNewChat()` :131、深链 `rhodes://chat/new` :172 |
| `.../Features/Common/RouteView.swift` | 修改 | `.newChat` → NewChatView、`.roleDetail(roleId:)` |
| `.../RhodesLinkApp.swift` | 修改（1 行） | `.task` 中 `RolesAutomation.runIfNeeded`（在 SelfTestDriver 之前，保证打点进 selftest 落盘日志） |
| `RhodesLink/iosApp/RhodesLink/Resources/Avatars/avatar_1..147.png` | 新增资源 | 147 张（6.9 MB）从 Android `app/src/main/res/drawable/` 复制；同步组自动进 Bundle 根目录 |
| `ios-port/specs/ios-roles-pages-spec.md` | 新增 | 本卡规格（数据语义/桥接契约/头像裁决落地/页面路由/验证命令/限制） |
| `ios-port/handoffs/impl-roles-pages.md` | 新增 | 本文件 |
| `ios-port/handoffs/evidence/t36-*` | 新增 | 8 张截图 + 5 份日志（含最终复跑；见 §3/§4） |

不动 Android `app/`、不改 T33 DesignSystem 既有语义；T34 的 `MessageHistoryBridge.kt` / `MessageHistoryClient.swift` /
`ChatView*` / `SelfTest.swift` 未触碰。

## 2. 构建

```bash
cd RhodesLink && source ../ios-port/toolchain/env.sh
./gradlew --no-daemon :shared:linkDebugFrameworkIosSimulatorArm64      # BUILD SUCCESSFUL in 40s
# → evidence/t36-framework-link.log；产物含 SharedRoleBridge/RoleDto/RelationDto + IosAppGraph.roles()

cd iosApp && TMPDIR="$PWD/tmp" xcodebuild build -project RhodesLink.xcodeproj -scheme RhodesLink \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath "$PWD/DerivedData" CODE_SIGNING_ALLOWED=NO
# → ** BUILD SUCCEEDED **（evidence/t36-build.log，与 T34 的 MessageHistory 改动同一构建）
```

首次包含 T36 全量 Swift 文件的构建由 T34 代跑（`evidence/t34-build.log`，BUILD SUCCEEDED）；
本卡随后独立构建（t36-build.log）验证 `intro` 修名等最终状态。构建纪律：gradle/xcodebuild 前 pgrep，
发现 T34/T35 构建在跑即排队等待（T35 的 `:shared:testDebugUnitTest` 期间等 40s 后再 link，未并发）。

**收尾复跑（当前工作区最终状态，07:55 批次）**：`:shared:linkDebugFrameworkIosSimulatorArm64` BUILD SUCCESSFUL
（`t36-framework-link-final.log`）+ xcodebuild ** BUILD SUCCEEDED **（`t36-build-final.log`）——证明与 T34/T35
未提交改动共存的当前树仍可编译；T36 文件与 `intro` 字段在最终 framework 头文件中确认存在。

## 3. 模拟器实跑（xcrun simctl，未用 dsh-ios 工具）

```bash
xcrun simctl install booted DerivedData/Build/Products/Debug-iphonesimulator/RhodesLink.app
xcrun simctl launch booted com.rhodeslink.app --tab roles
xcrun simctl io booted screenshot evidence/t36-roles-list.png
```

**证据截图（均模拟器实际画面，已逐张人工核对）**

| 文件 | 内容 |
|---|---|
| `t36-roles-list.png` | 角色 Tab：头部「角色 · 真实 operators 数据 / RoleBridge · 147 位角色 · 已有会话数」+ 搜索框 + 真实 Bundle 头像角色卡（M3/Misery/Scout/Whitesmith…），每行显示 `Lv.0` / 关系 / 心情 / 「尚未开始对话」 |
| `t36-role-detail-amiya.png` | 角色详情：阿米娅（真实 Bundle 头像）/ 身份 / 性别 / Lv / 位置·状态·心情 / 等级与关系 / 长期印象（真实 L3 记忆「演示记忆（真实 DB 写入）…」）/ 角色资料（真实 operators.description，修复后正常渲染） |
| `t36-role-detail-relations.png` | 同上 + 关系网「3 条」：凯尔希 监护人 92、M3 队友 55、博士（AI）战友 80（relationships 表真实行） |
| `t36-newchat-picker.png` | 聊天 Tab「+」→「新建聊天」选角页：真实 147 角色 + 真实头像 + 搜索；副标题说明已有会话直接打开、否则创建 `session_<id>` |
| `t36-autochat-chat.png` | 新建会话成功：进入「凯尔希」聊天页（会话 `session_kaltsit` 真实落库，空历史态文案来自 T34 MessageHistoryBridge 空态） |
| `t36-chatlist-unread.png` | 聊天列表：3 个真实会话（M3/凯尔希/阿米娅）+ 真实头像 + 最后消息 + 时间 + 未读角标「2」（凯尔希） |
| `t36-roles-dark.png` | 深色外观（`simctl ui booted appearance dark`）角色列表：玻璃卡/头像/文字均正常 |
| `t36-roles-dynamictype-xl.png` | Dynamic Type `accessibility-extra-large`：卡片自然增高、文案换行不截断 |
| `t36-build.log` / `t36-framework-link.log` | 构建/链接成功日志 |
| `t36-selftest.log` | `--selftest --roles-selftest` 完整落盘自检日志（Documents 取出） |

**自检日志节选（`t36-selftest.log`，真实运行）**

```
04:45:19.444 roles selftest started
04:45:19.598 roles selftest list count=147 withSession=2 builtinAvatar=147 first=M3
04:45:19.599 roles selftest bundledAvatars=147/147
04:45:19.614 roles selftest detail id=mon3tr found=true name=M3 level=0 relation= impression=no session=
04:45:19.614 roles selftest relations count=1 role=mon3tr          ← 反向关系（amiya→mon3tr）被 relations 合并读到
04:45:19.617 roles selftest startChat role=mon3tr session=session_mon3tr
04:45:19.630 roles selftest session linked role=mon3tr mode=online sessionId=session_mon3tr
04:45:19.637 roles selftest done sessions=3
04:45:19.718 roles loaded count=147 sessions=3 first=mon3tr avatar=builtin:6
```

**DB 直查（模拟器数据容器 `Library/Application Support/rhodes.db`，证明非 mock）**

```
operators|147   sessions|3   relationships|3   memories|1
session_mon3tr   M3       unread=0  last=（iOS 桥接演示）我听见了：…
session_kaltsit  凯尔希   unread=2  last=（新建会话演示）今天也要好好工作。
session_amiya    阿米娅   unread=0  last=（iOS 桥接演示）我听见了：…
```

说明：`t36-newchat-picker.png` / `t36-autochat-chat.png` 里的凯尔希会话由 `--autochat kaltsit`
（与「+」按钮、角色详情「开始聊天」**同一** `AppGraphClient.startChat` + `AppRouter.openChat` 代码路径）创建；
为验证未读角标与关系网渲染，在 DB 中置了 `session_kaltsit.unreadCount=2` 与 3 条 amiya 关系（数据落库、
Bridge 原样读取，非 UI 假数据），已在 §6 记录。

## 4. 验收逐项

| # | 验收项 | 结果 | 证据 |
|---|---|---|---|
| 1 | RoleBridge（列表/详情 DTO 指定字段）+ 接真实 operators 表 | ✅ | RoleBridge.kt:41/59/117/169；selfTest list count=147；DB operators=147 |
| 2 | RelationBridge 最小面（关系网列表） | ✅ | RoleBridge.relations:75（out+in）；detail 截图「3 条」；日志 relations count=1（反向） |
| 3 | 角色列表真实头像 1..147 Bundle 数字解析 | ✅ | 147 PNG 入 `RhodesLink.app/` 根（`ls avatar_*.png|wc -l`=147）；日志 `bundledAvatars=147/147`、`builtinAvatar=147` |
| 4 | 无资源占位色块 + 首字 | ✅ | RoleAvatar 回退 AvatarBadge；`RoleAvatarProbe` 可判定（非数字头像 → `placeholder:`） |
| 5 | 角色详情（资料/关系/印象/等级 + 进入聊天） | ✅ | t36-role-detail-amiya.png / t36-role-detail-relations.png；impression 来自 memories L3 |
| 6 | 会话列表（已有桥）+ 最后消息/时间/未读 | ✅ | t36-chatlist-unread.png（3 会话、未读 2、时间、最后消息）；SessionListBridge 未改 |
| 7 | 新建聊天入口（选角色→建会话→进聊天） | ✅ | t36-newchat-picker.png + t36-autochat-chat.png + DB session_kaltsit / session_mon3tr |
| 8 | 空态/错误态 | ✅ | 代码路径：RolesView/RoleDetailView/NewChatView/ChatListView 均有 empty/error/retry；关系网 0 条空态文案（初始 0 条时已截图前验证） |
| 9 | 深色/浅色 + Dynamic Type | ✅ | t36-roles-dark.png（深色）；t36-roles-dynamictype-xl.png（AX3 不截断）；浅色为默认截图 |
| 10 | xcodebuild 编译通过 + 模拟器实跑 + 截图证据 | ✅ | t36-build.log ** BUILD SUCCEEDED **；8 张截图；t36-selftest.log |

## 5. 关键设计决策

1. **角色 DTO 语义以 operators 表为单一来源**：列表顺序沿用 SQLite `ORDER BY name ASC`（Android oracle 同 SQL），
   不额外排序；会话选取「稳定 id `session_<operatorId>` 优先，否则 lastTime 最新」——避免历史 dup 会话错配。
2. **`startChat` 复用 `getOrCreateSession`**：与 Android 会话列表点角色进私聊语义一致（幂等），
   所以「新建聊天」对已有会话角色等价于「打开会话」；UI 用 `hasSession` 区分文案/图标。
3. **长连接数据一次载入**：`loadIndex()` 一次取 operators/sessions/impressions 再映射，避免 N+1（147 行 × 3 表）。
4. **印象 = L3 记忆最新一条**（非 UI 假文案），无记忆时回退空态说明「持续对话后写入 memories」；
   资料简介与人设分离两卡（印象 ≠ 角色简介）。
5. **头像 Bundle 化按 T16 裁决**：147 张 PNG 原样复制、Bundle 根目录按 `avatar_<N>` 文件名解析，无 asset catalog、
   无编译期映射表；占位回退保证非数字/缺失资源不崩。用户上传头像（media 相对 ID）留占位，后续接 MediaRepository。
6. **把 automation 放在 SelfTestDriver 之前**：让 `--roles-selftest` 打点进入 `--selftest` 的 Documents 落盘日志，
   证据可离线复取（不依赖 log show）。
7. **深链兼容**：`Route.roleDetail` 的 roleId 在 Kotlin 侧先按 operatorId 命中、未命中再按 sessionId 反查
   operatorId → T33 的 `rhodes://role/session_amiya` 仍然可用。
8. **未读角标**数据来自 `chat_sessions.unreadCount`（SessionListBridge 既有字段），UI 渲染；清零动作归 T34/后续波。

## 6. 已知限制 / 坑位

1. **Kotlin DTO 字段名 `description` 会与 `NSObject.description` 冲突**：首版 RoleDto.description 在 Swift 侧
   读到的不是字段值而是对象描述串（`...RoleDto@2232a588`，实测截图），已改名 `intro` 并重链 framework 修复。
   后续所有 iosbridge DTO 避免 `description` / `hash` / `debugDescription`。
2. 用户上传头像不渲染（`media/<uuid>.<ext>` 占位色块），待接 MediaRepository.resolve（后续波）。
3. 会话级动作未做：置顶/删除/免打扰/标为已读/会话搜索；未读角标只读不清（T34 聊天页范畴）。
4. A2 后续波未做：新建/编辑角色、群聊人设编辑、礼物墙、公开经历、印象列表（ImpressionsRoute）、资料设置。
5. 147 张头像全量入 Bundle（6.9 MB）且 `UIImage(named:)` 全尺寸解码；列表懒加载下可接受，
   后续可加缩略图/按需压缩（人类评审/性能波）。
6. AX5 超大字号下头部卡文案换行较多（不截断）——G3 人类评审项。
7. `--roles-selftest` 会创建 `session_mon3tr`（新建会话验收需要），属自检副作用，仅该启动参数触发。
8. 演示数据说明：`unreadCount=2` 与 3 条 amiya 关系为验证渲染手工置于模拟器 DB（见 §3），非产品播种逻辑。

## 7. 与并行代理（T34）的写域协作

- 重叠文件只有 `IosDtos.kt`（T34 不动）、`IosAppGraph.kt`（T34 先插 historyBridge，本卡后插 roleBridge）、
  `AppGraphClient.swift`（T34 只插 1 行 `historyBridge` 计算属性，本卡在类内末尾追加角色方法）——均用 `edit`
  追加式修改，未整文件 `write`；T34 的 `MessageHistoryBridge.kt`/`MessageHistoryClient.swift`/`ChatView*`/`SelfTest.swift` 未触碰。
- 构建排队：T34 先跑 gradle link + xcodebuild（含本卡文件，BUILD SUCCEEDED），本卡复用产物做截图；
  本卡最终独立构建验证（t36-build.log）。模拟器占用按消息约定轮换。

## 8. 复现命令

见 `ios-port/specs/ios-roles-pages-spec.md` §7（framework link / xcodebuild / simctl install-launch / 截图 /
`--tab roles`、`--open-url rhodes://role/amiya`、`--open-url rhodes://chat/new`、`--autochat <operatorId>`、
`--selftest --roles-selftest`）。取自检日志：
`xcrun simctl get_app_container booted com.rhodeslink.app data` → `Documents/ios-shell-selftest.log`。

## 9. 对后续波的建议

| 优先级 | 项 | 落点 |
|---|---|---|
| P0 | 会话列表动作（标已读/置顶/删除/搜索）+ 未读清零与聊天页联动 | ChatListView / SessionListBridge（与 T34 协调） |
| P0 | 用户上传头像渲染（MediaRepository.resolve → Bundle/文件对话框） | RoleAvatar + 新 bridge 方法 |
| P1 | 角色编辑 / 新建角色 / 头像裁剪（A2 OperatorEditScreen 等价物） | RoleBridge 增加写面 + 新表单页 |
| P1 | 关系网增删改 + 20+ 关系类型选择器（A2 关系编辑） | RoleBridge.relations 写面 |
| P2 | 角色列表拼音分组/排序切换、角色卡导出（复用 T30 OperatorCard） | RolesView / RoleBridge |
| P2 | 147 头像缩略图（降采样/缓存） | RoleAvatar |
