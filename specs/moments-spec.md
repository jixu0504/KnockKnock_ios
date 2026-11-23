# T50 规格：动态广场（iOS 移植，Phase 6 内容生态加急）

> 代理：impl-moments（task-50）｜分支 ios-port｜Kotlin 2.3.21
> 状态：实现完成（桥 + 页面 + 路由 + 自检 + 证据）；Android oracle：`app/.../ui/moments/MomentsScreen.kt`、
> `app/.../viewmodel/MomentsViewModel.kt`、`MainViewModel.postUserMoment/commentOnMoment`。
> 纪律：只改 `.moments` 分支，不碰 `.diary`（T51 写域）。

## 1. 范围与数据

| 项 | 结论 |
|---|---|
| 数据表 | `moments` / `moment_likes` / `moment_comments`（`golden/db/schema/` 与 `shared/.../db/*.sq` 一致；**无图片列**） |
| 列表 | `moments ORDER BY createdAt DESC, id DESC`，offset/limit 分页（`getMomentsPaged`） |
| 作者 | 用户动态 = `settings.user_name`（默认「博士」）+ `user_avatar_uri`；干员动态 = `moments.operatorName` + `operators.avatarUri`（1..147 内置头像） |
| 点赞 | `moment_likes` 行（operatorId="user"）+ 回写 `moments.likeCount`；展示数取实际行数（= Android `likes.size`） |
| 评论 | `moment_comments` 行（operatorId="user"、isRead=true）+ 回写 `commentCount`；展示数取实际行数 |
| 删除 | 仅本人（`isUserPost` 或 operatorId="user"）：同事务删 `moments` + 级联 `moment_likes` / `moment_comments` |
| 未读 | 新动态 = `countUnreadMomentsAfterId(last_seen_moment_id)`；未读评论 = `getUnreadCommentCount(30 天, 用户昵称)`；打开广场 `markMomentsSeen`（推进 `last_seen_moment_id`） |
| 图片 | **iOS 增强**（Android 动态为纯文本）：内容内嵌媒体标记 `\n[图片] media/<uuid>.<ext>`（纯图 `[图片] media/...`），出 DTO 时剥离为 `text` + `imageId`；媒体文件落 `Application Support/media/images/`（`MediaRepository.ingest(PICKER)`） |

### 1.1 共享层最小 patch（写域交叉，reviewer 复核）

| 文件 | 改动 | 原因 |
|---|---|---|
| `shared/src/commonMain/sqldelight/.../Moments.sq` | 追加 `deleteMoment: DELETE FROM moments WHERE id = ?;` | shared 原无单条删除语句（Android 只有按角色/过期清理）；**只增查询、不改表结构/迁移**，金样 schema 不受影响 |
| `shared/src/commonMain/.../data/MomentRepository.kt` | 追加 `deleteMoment(id)`（事务级联点赞/评论） | 同上 |
| `shared/src/commonMain/.../data/ChatRepository.kt` | 追加 1 行委托 `deleteMoment(id)` | 同上 |

## 2. 桥接契约（`shared/src/iosMain/.../iosbridge/MomentsBridge.kt`）

Kotlin/Native `@ObjCName(swiftName = "MomentsBridge")`；Swift 入口 `graph.moments()`；回调一律 Main 队列。

| 方法 | 回调 | 语义 |
|---|---|---|
| `list(offset, limit, onResult)` | `(MomentPageDto, String?)` | 分页 DTO（items/offset/limit/hasMore）；hasMore = 本页取满 limit；limit 裁剪 1..50 |
| `publish(text, imageId, onDone)` | `(Long, String?)` | 发布用户动态；空文本+空图拒绝；imageId 必须已入库且文件存在；返回新 id |
| `toggleLike(momentId, onDone)` | `(MomentLikeResultDto, String?)` | 点赞↔取消（幂等切换），回写 likeCount；动态不存在返回错误 |
| `comments(momentId, onResult)` | `([MomentCommentDto], String?)` | createdAt ASC（对齐 Android `getComments`） |
| `addComment(momentId, text, parentCommentId, replyToName, onDone)` | `(Long, String?)` | 写用户评论（isRead=true）；parent>0 校验父评论属于该动态并归一到根 parent；回写 commentCount |
| `delete(momentId, onDone)` | `(String?)` | 仅本人；级联删除；非本人返回「只能删除自己发布的动态」 |
| `badges(onResult)` | `(MomentBadgeDto, String?)` | `(新动态数, 未读评论数)` |
| `markMomentsSeen(onDone)` / `markCommentsRead(onDone)` | `(String?)` | 角标落盘（`last_seen_moment_id` / `last_seen_comment_id`） |
| `ingestImage(sourcePath, onDone)` | `(String, String?)` | PhotosPicker 本地文件 → 媒体 ID（`Application Support/media/images/media/<uuid>.<ext>`） |
| `imagePath(imageId)` | 同步 String | 媒体 ID → 本机绝对路径（缺失 ""） |
| `userName()` / `userAvatarId()` | 同步 | 发布器/头部展示 |

DTO（简单类型，普通 class）：`MomentDto(id, operatorId, authorName, authorAvatarId, text, imageId, isUserPost, isMine, likeCount, commentCount, isLiked, createdAt)`、
`MomentPageDto(items, offset, limit, hasMore)`、`MomentCommentDto(id, momentId, operatorId, authorName, text, parentCommentId, replyToName, createdAt, isMine)`、
`MomentLikeResultDto(liked, likeCount)`、`MomentBadgeDto(unreadMoments, unreadComments)`。

## 3. UI 契约（`iosApp/.../Features/Moments/MomentsView.swift` + `Bridge/MomentsClient.swift`）

- 时间线：头像（`RoleAvatar`：内置头像 Bundle / 用户头像 / 首字占位）、作者名（本人带「我」徽标）、正文、图片（点开全屏，缺失显示占位）、相对时间（刚刚/N 分钟前/N 小时前/N 天前）、点赞（红心+数）、评论（气泡+数）、前 2 条评论预览（回复缩进 + `@`）。
- 下拉刷新（`.refreshable`）、上滑分页（末行 onAppear 触发，`hasMore` 到底提示）、加载态、错误态（重试）、空态（「发布第一条动态」）。
- 发布器 Sheet：TextEditor + PhotosPicker（≤1920px JPEG 0.8 → shared 媒体入库 → 发布带媒体 ID）；字数、发布/取消、错误提示；发布成功返回刷新列表。
- 点赞：本地乐观更新（立即变色/计数），桥返回后以真实值校准，失败回滚 + Toast。
- 评论 Sheet：列表（作者/时间/回复关系）、输入框（回复态 chip）、发送；发送成功刷新预览与计数。
- 删除：本人动态「⋯」菜单 → 二次确认 Alert（文案含不可恢复）→ 删除后行移除 + Toast。
- 未读：进入广场加载首屏后 `markMomentsSeen`（对齐 Android 打开即清角标）；桥 `badges` 供功能页角标接线（功能页角标 UI 属 FeaturesView 写域，本卡未改，避免与 T48 冲突）。
- 路由：`RouteView` 的 `.moments` 分支 = `MomentsView()`；入口不变（功能 Tab → `router.openFeature(.moments)`，深链 `rhodes://features/moments`）。

## 4. 验证

- 自检：`xcrun simctl launch <UDID> com.rhodeslink.app --moments-selftest`
  → `Application Support/t50-moments-selftest.log`（逐行 PASS/FAIL + RESULT）与 NSLog `[RhodesLink][T50SELFTEST]`。
  用例：图片入库/resolve → 发布（新 id > baseline）→ 列表出现（文本/图片/作者/本人/计数 0）→ 点赞 ON(+1)/OFF(0)/再 ON → DB likeCount →
  评论（列表 + DB commentCount + isMine）→ 列表计数联动 → 新动态角标 ≥1 + markSeen 清零 → 删除非本人被拒 → 空内容被拒 →
  删除本人（列表消失 + DB 行消失 + 级联 0）→ 留 1 条截图夹具。
- 夹具清理：`--moments-fixture-clean`（删除内容含 `T50` 标记的本人动态）。
- DB 复核：`sqlite3 <容器>/Library/Application Support/rhodes.db` 查 `moments/moment_likes/moment_comments/operators`。
- UI 证据：`xcrun simctl io <UDID> screenshot` + `axe describe-ui/tap`（真实页面：feed / liked / comments / composer / delete 确认 / 删除后）。
- 构建：`:shared:linkDebugFrameworkIosSimulatorArm64` + `xcodebuild build`（见 handoff 日志）。

## 5. 限制 / 后续

1. **图片跨端**：图片用内容内嵌标记实现（events 表无图片列）。在 Android 端打开该动态会看到字面标记；后续若升级 schema（新增 `imageMediaId` 列 + migration），可把标记迁移为列。
2. **AI 自动互动未接**：Android `postUserMoment` 会创建 AI 点赞/评论 reply_turns（A7 延迟任务层 T22/T23）并在打开评论时回帖；本卡只做用户侧读写，iOS 不产生 AI 自动点赞/评论，也不创建 reply_turns。
3. **未读角标 UI**：桥已提供 `badges`（新动态/未读评论）；功能 Tab 的角标展示在 `FeaturesView`（当前写死 0，属 T48 写域），本卡未改。
4. **评论删除/点赞者名单**：Android 卡片展示「谁点了赞」名单与评论回复输入；本卡列表只显示计数（评论 Sheet 内可见全部评论与回复），点赞者名单不展示（数据可由 `moment_likes` 扩展）。
5. **图片裁剪**：发布图片仅缩放到 ≤1920px JPEG 0.8，未做方图裁剪/多图（原版动态也无多图）。
