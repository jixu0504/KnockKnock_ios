# T52 规格卡：加急版收尾三项用户可见缺口（头像渲染 / 功能角标 / 群未读）

> 代理：impl-polish-fixes ｜ 任务：task-52 ｜ 分支：`ios-port`（基线提交 `a2fbb62`）｜ 日期：2026-11-23
> 写域：`iosApp/DesignSystem/RoleAvatar.swift`、`iosApp/Features/{Roles,Features,GroupChat}/**`、`ios-port/{specs,handoffs}`。
> **Kotlin/shared 零改动**（群聊 UnreadHint 消费在 T43/T44 已实现，本卡通过端到端自检证实并在 Swift 侧补可见角标刷新）。
> Android oracle：`ui/roles/OperatorAvatar.kt`（头像值域）、`ui/features/FeaturesScreen.kt`（动态/日记角标）、
> `MainViewModel.getUnreadDiaryCount/getMomentBadge`、`GroupChatViewModel`（未读自增/清零语义）。

## 0. 问题与修复一览

| # | 用户可见缺口 | 根因 | 修复 |
|---|---|---|---|
| 1 | T49 上传的头像在角色列表/详情显示「首字占位」 | `RoleAvatar` 只解析内置数字头像（`builtin:N`），`media/<uuid>.<ext>` 直接落入占位分支 | `RoleAvatar` 内新增 `RoleAvatarResolver`：媒体 ID → shared 桥 `OperatorBridge.mediaPath` → 本机绝对路径 → `UIImage` 渲染；文件缺失/解析失败回退首字占位（不 crash、不空白） |
| 2 | 功能 Tab 动态/日记角标恒为 0 | `FeaturesView` 的 `momentBadge`/`diaryBadge` 写死 0 | 接 `MomentsBridge.badges()`（新动态 + 未读评论）与 `DiaryBridge.unreadCount()`（未读干员数）；onAppear 首刷 + 每 10 秒轮询；角标用未读红 `GlassTokens.unreadBadge`（#FA5151），99+ 截断 |
| 3 | 群聊未读角标不出现 / 不刷新 | shared 侧 `GroupChatBridge` 已消费 `GroupEngineEvent.UnreadHint`（T43/T44），但联系人页群列表只在进入页面时 `reloadGroups()`，驻留期间新未读不刷新 | 联系人页停留期间每 5 秒 `reloadGroups()`（轻量单查询）→ 群聊角标近实时出现；打开群聊沿用 `GroupChatViewModel.load()` 的 `setActiveGroup` + `markSessionRead` 清零 |

## 1. 头像渲染（`RoleAvatar`）

### 1.1 值域判定
| avatarUri | 解析 | 渲染 |
|---|---|---|
| `"1".."147"` | `RoleAvatarSource.builtin(n)` | Bundle `avatar_<n>.png`（T16 裁决不变） |
| `"media/<uuid>.<ext>"` | `RoleAvatarSource.media(id)` | `AppGraphClient.operatorMediaPath(mediaId:)` → `mediaRepository.resolve` → `UIImage(contentsOfFile:)` |
| 空 / 远程 URL / 其它 | `.placeholder` | `AvatarBadge` 首字色块（回退） |

- 缓存两级：媒体 ID → 本机路径（只缓存成功且文件存在的结果，避免刚入库竞态被永久缓存）；位图用 `NSCache`（countLimit 64）。媒体 ID 由 uuid 唯一，不存在失效问题。
- `RoleAvatarProbe.description` 扩展为 `builtin:N` / `builtin-missing:N` / `media:<id>` / `media-missing:<id>` / `placeholder:*`；
  新增 `mediaRenderProbe` 输出「媒体 ID → 路径 → 字节数 → 位图尺寸」一行证据。
- 统一生效面：`RolesView`（列表）、`RoleDetailView`（详情 92pt）、`NewChatView`、`NewGroupView`、`GroupChatView` 成员 Sheet、`MomentsView`、`DiaryView`/`DiaryDetailView` 全部已走 `RoleAvatar`，一处修复即全生效。
  （**注**：本 iOS 壳的私聊页顶部为「角色名 + 状态副标题」导航标题，没有头像组件——不存在需要接线的聊天头部头像。）

### 1.2 自检 `--avatar-render-selftest`（真实链路，不 mock）
1. 取联系人列表首行角色（与 `RolesView` 渲染顺序一致，截图必可见），记录原 avatarUri；
2. 生成 256×256 测试图（青→蓝渐变 + T52）→ 临时文件 → `OperatorBridge.setAvatarFromFile`（PICKER 入库，与 RoleEditView 保存按钮同一 API）→ `operators.avatarUri = media/<uuid>.jpg`；
3. 断言：DB 回读 avatarUri、媒体路径存在且 bytes>0、`UIImage` 非空（768×768 = 256pt@3x）、probe=`media:`；
4. 回退断言：不存在的媒体 ID → nil/`media-missing`；空 avatarId → nil/`placeholder`；内置 1..147 不受影响（147/147）；
5. 截图窗口：联系人列表 6s → 角色详情 7s；随后恢复原内置头像（不留测试头像）。

## 2. 功能 Tab 角标（`FeaturesView`）

- 动态角标 = `unreadMoments`（`moments.id > last_seen_moment_id AND isUserPost=0`）+ `unreadComments`（30 天内、`isRead=0`、本人动态或回复我）——与 `MomentsViewModel.getMomentBadgeSuspend` 同源；
- 日记角标 = 未读干员数（`MAX(diaries.createdAt) > diary_read_at_<op>`）——与 `getUnreadDiaryCount` 同源；
- 刷新：`.task` 首刷 + 独立 `.task` 循环每 10s 重读（离开页面随 Task 取消）；读取失败保持上一次值（首帧 0），不阻塞 UI；
- 样式：`GlassTokens.unreadBadge`（#FA5151）胶囊，`>99` 显示 `99+`。
- 自检 `--features-badge-selftest`：日记夹具 `DiaryBridge.debugEnsureFixture`（DB 空则写 1 条并把 read_at 归零）；动态/评论已读位由外部 sqlite 预置（见 handoff §4）；断言桥值（≥1/≥1/≥1）与界面实际显示值（`momentBadge=2, diaryBadge=1`）+ 11 秒后日志出现第 2 次 `features.badge refresh`（周期刷新证据）。

## 3. 群聊未读角标

- shared 链路（T43/T44 已有，本卡验证不回归）：
  `GroupChatEngine.send` → `GroupEngineEvent.UnreadHint(groupId, visibleCount)` → `GroupChatBridge.onEngineEvent`（`activeViewingGroup != groupId` 时才累加）→ `SessionRepository.incrementUnread` → `chat_sessions.unreadCount +N`；
  打开群聊：`GroupChatViewModel.load()` → `setActiveGroup(groupId)` + `markSessionRead` 清零；离开 → `setActiveGroup("")`。
- 本卡新增（Swift 侧可见性）：`RolesView` 停留期间每 5s `reloadGroups()`，群聊行角标（橙底 `unreadCount` 胶囊）近实时出现；否则新未读要等重新进入联系人页才可见。
- 自检 `--group-unread-selftest`（真实引擎 + 真实 DB + 真实 AI 通道）：
  1) 基线 `markAllSessionsRead` + `setActiveGroup("")` → 目标群 unread=0；
  2) 非当前群发一轮群消息（真实 provider 通道；本机 mock，见 handoff §4）→ `UnreadHint` → unread 0→2（= 可见回复段数 2，`db.unreadDeltaVisibleCount` 精确相等）；
  3) 真实路由打开群聊 → `markSessionRead` → unread=0；
  4) 打开状态下再发一轮 → UnreadHint 被排除（当前群不累加）→ unread 仍 0。
- DB 证据：运行中 sqlite3 快照 `chat_sessions.unreadCount=2`；打开后 0。

## 4. 兼容与边界

- 上传头像文件缺失（清缓存/媒体被清理）→ 回退首字占位，不 crash；跨设备恢复备份后媒体未落盘同样回退。
- 媒体路径为 http(s) 远程 URL 时不在头像渲染（仅本机上传头像；远程头像仍占位，后续波次接 AsyncImage 缓存）。
- 本次未改 `.sq` / `commonMain` / 其它功能页；`RoleAvatarResolver` 标 `@MainActor`（依赖 `@MainActor AppGraphClient`），调用方均为视图 body / @MainActor 自检。
- 角标轮询为前台轻量查询（动态 1 次 + 日记 1 次 / 10s；群列表 1 次 / 5s，仅联系人页可见时）；后续可换事件驱动/推送。

## 5. 验证入口（复用命令见 handoff）

```bash
DEV=A63CD17A-E1F9-4C5C-9B8C-92DEB43862FA   # iPhone 17
xcrun simctl launch $DEV com.rhodeslink.app --avatar-render-selftest --tab settings
xcrun simctl launch $DEV com.rhodeslink.app --features-badge-selftest --tab features
xcrun simctl launch $DEV com.rhodeslink.app --group-unread-selftest --tab contacts
```
