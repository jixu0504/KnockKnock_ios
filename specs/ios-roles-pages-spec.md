# iOS 角色/关系页 + 会话列表 规格（Phase 4 UI 波次一，T36）

> 任务：task-36（impl-roles-pages）｜状态：**实现完成，xcodebuild 通过，iPhone 17 / iOS 27.2 模拟器实跑通过**
> 上游规格：`ios-bridge-spec.md`（窄门面纪律）、`ios-shell-spec.md`（T33 壳与路由）、
> `message-id-media-spec.md`（T16 头像 Bundle 裁决）、`功能对照清单-v1.md`（A1 会话列表 / A2 角色、关系、资料）

## 1. 范围

| 交付 | 内容 |
|---|---|
| shared 桥 | `RoleBridge`（角色列表 / 详情 / 关系网 / 开始聊天）+ `RoleDto` / `RelationDto`；接真实 `operators` / `chat_sessions` / `relationships` / `memories` 表 |
| 角色 Tab | 真实角色列表（147 位预设角色、真实头像、等级/关系/会话状态、搜索、刷新、空态/错误态） |
| 角色详情 | 资料卡（头像/身份/性别/位置/状态/心情）、等级与关系、长期印象（L3 记忆）、角色资料、关系网（双向）、进入聊天 |
| 聊天 Tab | 会话列表补「+ 新建聊天」入口（选角色 → 创建/复用 stable 会话 → 进入聊天）；真实头像/最后消息/时间/未读角标 |
| 资源 | 内置头像 1..147 打包为 Bundle 资源（`RhodesLink/Resources/Avatars/avatar_<N>.png`），按数字动态解析 |
| 体验 | 深色/浅色（既有 GlassToken 体系）、Dynamic Type、空态/错误态 |

不改 Android app/，不改 T33 既有 DesignSystem 语义；与 T34（消息历史桥）写域交叉点用「只追加」协作（见 handoff §7）。

## 2. 数据源与语义（真实 DB，非 mock）

| 字段 | 来源 | 语义 / oracle 对齐 |
|---|---|---|
| id / name / title / gender / location / activity / emotion | `operators` 表 | 与 Android `OperatorRepository.getAllOperatorsSync()`（ORDER BY name ASC）一致 |
| avatarId | `operators.avatarUri` 原样 | 内置为数字串 `"1".."147"`；T16 裁决：内置头像不落 media 目录、值域保持数字串 |
| relationType | `operators.userRelation` | 用户与该角色的关系（Android OperatorDetailScreen RelationsSection 同字段） |
| level | `operators.intimacy` | 亲密度；UI 显示 `Lv.<intimacy>` + 进度条（0..100 clamp 仅用于进度） |
| impression | `memories`（L3 长期印象） | 该角色最新一条 `createdAt` 最大的长期印象原文；无则空串 → UI 空态文案 |
| mode / sessionId / lastMessage / lastTime | `chat_sessions` | 优先稳定 id `session_<operatorId>`，否则 lastTime 最新的一条；无会话 → `mode="offline"`、`sessionId=""`、`lastTime=0` |
| 关系网 | `relationships` 表 | forward（out）+ reverse（in）合并；同 relatedOperatorId 时 out 优先去重；`type` 为 `RelationshipType` 枚举名（20+ 种） |

预设角色：`RoleBridge.list` 首次调用幂等执行 `ensurePresetOperators()`（147 位，与 T33 `bootstrap` 同语义），
保证空库也有真实角色可展示；用户自建角色不受影响。

## 3. 桥接契约（Swift ↔ Kotlin）

`@ObjCName(swiftName = "RoleBridge")`，回调式（无 Flow），回调一律 Main 队列，错误经第二参数回传（null = 成功）：

| Kotlin 方法 | Swift 名 | 返回 |
|---|---|---|
| `list(onResult)` | `list(onResult:)` | `[RoleDto]` |
| `find(roleId, onResult)` | `find(roleId:onResult:)` | `RoleDto?`；**兼容 T33 旧深链**：roleId 未命中 operator 时按 sessionId 反查会话再取 operatorId |
| `relations(operatorId, onResult)` | `relations(operatorId:onResult:)` | `[RelationDto]`（out+in） |
| `startChat(operatorId, onResult)` | `startChat(operatorId:onResult:)` | `sessionId: String?`（`getOrCreateSession` 语义） |

DTO 纪律：普通 class、仅简单类型；**字段名避开 `description`**（Kotlin/Native 导出 ObjC 会与
`NSObject.description` 冲突——本项目实测 Swift 侧读到的是对象描述串，故字段名为 `intro`，见 §8 坑位）。

`IosAppGraph` 新增 `roles(): RoleBridge`（与 chatEngine/sessions/settings/backup/memory/history 并列）。

## 4. 头像打包与解析（T16 裁决落地）

- 资源：Android `app/src/main/res/drawable/avatar_1..147.png`（147 张连续，共 6.9 MB）原样复制到
  `RhodesLink/iosApp/RhodesLink/Resources/Avatars/`；由 `PBXFileSystemSynchronizedRootGroup` 自动进入
  Copy Bundle Resources，构建后位于 `RhodesLink.app/avatar_<N>.png`（**Bundle 根目录、按文件名**）。
- 解析：`RoleAvatar` 读取 `avatarId`，`Int(trimmed)` ∈ 1...147 时 `UIImage(named: "avatar_<N>")`；
  非数字 / 越界 / 资源缺失 → 回退 `AvatarBadge`（品牌橙渐变 + 首字色块）。
- 用户上传头像：值域为 `media/<uuid>.<ext>`（MediaRepository 相对 ID，T17 语义），本波**不**解析文件，
  直接走占位色块；后续波在 `RoleAvatar` 内接 `MediaRepository.resolve`（Kotlin 侧提供字节/路径回调）。
- 诊断：`RoleAvatarProbe.description(avatarId:)` → `builtin:<N>` / `builtin-missing:<N>` / `placeholder:<值>`；
  `bundledAvatarCount()` 统计可解析数量（自检日志证据）。

## 5. 页面与导航

| 页面 | 文件 | 说明 |
|---|---|---|
| 角色列表（Tab 根） | `Features/Roles/RolesView.swift` | 头部玻璃卡（147 位 / 已有会话数）+ 搜索 + 角色卡列表；下拉刷新；空态（可重新播种）/错误态（重试）/搜索无结果态 |
| 角色详情 | `Features/Roles/RoleDetailView.swift` | 资料卡（玻璃 1）+ 等级与关系 + 长期印象 + 角色资料 + 关系网 + 进入聊天；无会话时说明将创建 `session_<id>`；加载/不存在/失败态 |
| 新建聊天 | `Features/Roles/NewChatView.swift` | 从聊天 Tab 右上角「+」push；选角色 → 已有会话直接进、否则 `startChat` 创建 → 进入聊天；搜索/空态/错误态/行内 loading |
| 会话列表 | `Features/Chat/ChatListView.swift` | 保留 SessionListBridge 真实数据；新增「+」工具栏入口；头像改用 `RoleAvatar`（session.avatarUri 数字串）；未读角标/最后消息/时间沿用 |
| 头像组件 | `DesignSystem/RoleAvatar.swift` | Bundle 数字解析 + 占位回退 + `RoleAvatarProbe` |

路由（`AppRouter`）：新增 `Route.newChat`；`Route.roleDetail(sessionId:)` 改为 `roleDetail(roleId:)`（operatorId）。
深链：`rhodes://role/{operatorId}`（旧 `sessionId` 兼容）、`rhodes://chat/new`（新建聊天选角页）、
`rhodes://chat/{sessionId}` 行为不变。`openNewChat()` / `openChat(sessionId:)` 跨 Tab 导航。

## 6. 空态/错误态/自适应

- 空态：角色表空（重新播种 action）、搜索无结果、关系网 0 条（文案说明 relationships 语义）、
  无会话（「尚无会话」卡片 + 开始聊天）、印象无记忆（说明 L3 写入时机）。
- 错误态：`RoleBridge` 第二参数 error → 页面错误卡 + 重试；新建会话失败在按钮区显示错误条。
- 深色/浅色：全部颜色来自 `GlassTokens`（rhodesHex 双值）/系统语义色；模拟器实测深色截图。
- Dynamic Type：全部使用 `.font(...)` 语义样式，不写死字号；AX3 大字号实测不截断（卡片自然增高、文本换行）。
- 玻璃计数：角色列表 1 个（头部）、角色详情 1 个（资料卡），满足一屏 ≤5。

## 7. 构建 / 验证命令

```bash
# 1) framework（含 RoleBridge）
cd RhodesLink && source ../ios-port/toolchain/env.sh
./gradlew --no-daemon :shared:linkDebugFrameworkIosSimulatorArm64

# 2) app
cd iosApp && TMPDIR="$PWD/tmp" xcodebuild build -project RhodesLink.xcodeproj -scheme RhodesLink \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath "$PWD/DerivedData" CODE_SIGNING_ALLOWED=NO

# 3) 模拟器（不用 dsh-ios 工具）
xcrun simctl boot "iPhone 17"   # 已启动则跳过
xcrun simctl install booted DerivedData/Build/Products/Debug-iphonesimulator/RhodesLink.app
xcrun simctl launch booted com.rhodeslink.app --tab roles
xcrun simctl io booted screenshot shot.png

# 启动参数（T36）
--tab roles                     # 角色 Tab
--open-url rhodes://role/amiya  # 角色详情（operatorId）
--open-url rhodes://chat/new    # 新建聊天选角页
--autochat <operatorId>         # 创建/复用会话并进入聊天（与 UI 同一代码路径）
--selftest --roles-selftest     # 角色/关系/新建会话全链路打点（落 Documents/ios-shell-selftest.log）
```

验收证据见 `ios-port/handoffs/impl-roles-pages.md` §4（截图 + DB 直查 + 自检日志）。

## 8. 已知限制 / 坑位

1. **`description` 字段名冲突（已修）**：Kotlin DTO 字段名 `description` 经 K/N 导出后与 `NSObject.description`
   冲突，Swift 读到对象描述串（`...RoleDto@2232a588`）；已改名 `intro`。后续所有 DTO 避免使用
   `description` / `hash` / `debugDescription`。
2. 用户上传头像不渲染（`media/<uuid>.<ext>` 走占位）；待接 MediaRepository.resolve。
3. 免打扰/置顶/删除会话、会话搜索未做（A1 后续波）；未读角标已渲染但无「进入会话清零」动作（T34 范畴）。
4. 角色编辑/新建角色/群聊人设、礼物墙、公开经历、印象列表（ImpressionsRoute）未做（A2 后续波）。
5. 角色列表顺序 = SQLite `ORDER BY name ASC`（BINARY），中文名按 UTF-8 字节序而非拼音——与 Android oracle 一致，
   未加拼音分组（A2 可选增强）。
6. AX5 超大字号下头部卡文案换行较多（不截断），人类评审项（Gate 3）。
7. 147 张头像全量入 Bundle（6.9 MB），未做按需下载/压缩；运行时 `UIImage(named:)` 全尺寸解码，
   列表懒加载下内存可接受，后续可加缩略图。
