# T50 实现 handoff：动态广场（加急版第 2 批）

> 代理：impl-moments（task-50）｜规格：`ios-port/specs/moments-spec.md`（本卡产出）
> 状态：**实现完成**。Shared.framework 重链 + xcodebuild 通过；`--moments-selftest` 28/28 PASS（RESULT PASS）；
> iPhone 17 Pro 模拟器 `T50-sim`（iOS 27.2）实跑 + axe 真实触控 UI 取证 + sqlite3 DB 核对完成。
> 纪律：git **不提交**（lead 统一提交）；task-50 claim→complete；不改 Settings/Roles/Chat 页；
> RouteView 只改 `.moments` 分支（未动 `.diary`，T51 无冲突）。

## 1. 交付清单（写域内 + 2 处最小交叉 patch）

| 路径 | 类型 | 说明（关键 file:line） |
|---|---|---|
| `shared/src/iosMain/.../iosbridge/MomentsBridge.kt` | **新增** | `@ObjCName("MomentsBridge")`；DTO：`MomentDto/MomentPageDto/MomentCommentDto/MomentLikeResultDto/MomentBadgeDto`；方法：`list`/`publish`/`toggleLike`/`comments`/`addComment`/`delete`/`badges`/`markMomentsSeen`/`markCommentsRead`/`ingestImage`/`imagePath`/`userName`/`userAvatarId`；自检 `debugRunSelfTest`/`debugCleanFixtures`；内容内嵌图片标记 `encodeContent/splitContent`（companion） |
| `shared/src/iosMain/.../iosbridge/IosAppGraph.kt` | 最小 patch | `momentsBridge` 属性 + `maybeRunMomentsSelfTest()`（`--moments-selftest` / `--moments-fixture-clean` 启动钩子）+ `fun moments()` 访问器 |
| `iosApp/RhodesLink/Bridge/MomentsClient.swift` | **新增** | Swift 模型（`MomentItem/MomentCommentItem/MomentLikeResultItem/MomentBadgeItem`）+ `AppGraphClient` 扩展（load/publish/toggleLike/comments/addComment/delete/badges/markSeen/ingestImage/imagePath） |
| `iosApp/RhodesLink/Features/Moments/MomentsView.swift` | **新增** | `MomentsView`（时间线/下拉刷新/分页/空态/错误态/加载态/图片全屏/点赞乐观更新/删除二次确认）+ `MomentComposerView`（TextEditor + PhotosPicker → 媒体入库 → 发布）+ `MomentCommentsSheet`（列表 + 回复 + 输入）+ `MomentImageThumb/MomentFullscreenImage` |
| `iosApp/RhodesLink/Features/Common/RouteView.swift` | 仅 `.moments` 分支 | 占位 `PlaceholderView` → `MomentsView()`（其余分支未动） |
| `iosApp/RhodesLink/Bridge/AppGraphClient.swift` | 追加 1 个访问器 | `var momentsBridge: MomentsBridge { graph.moments() }`（与 T47/T48 同模式，只暴露桥对象给独立扩展文件） |
| `shared/src/commonMain/sqldelight/.../Moments.sq` | **最小交叉 patch** | 追加 `deleteMoment: DELETE FROM moments WHERE id = ?;`（只增查询，**不改表结构/迁移**，金样 schema 不受影响） |
| `shared/src/commonMain/.../data/MomentRepository.kt` | **最小交叉 patch** | `deleteMoment(id)`：事务级联删 `moment_likes`/`moment_comments`/`moments` |
| `shared/src/commonMain/.../data/ChatRepository.kt` | **最小交叉 patch** | 1 行委托 `deleteMoment(id)` |
| `ios-port/specs/moments-spec.md` | 新增 | 桥接契约 / 数据语义 / UI 契约 / 验证 / 限制 |
| `ios-port/handoffs/impl-moments.md` | 新增 | 本文件 |
| `ios-port/handoffs/evidence/t50-*` | 新增 | 构建日志 2、自检日志、DB 核对、11 张截图 |

**未改**：`ChatEngineBridge.kt`/`SettingsBridge.kt`/`RouteView .diary`/`FeaturesView`/Settings/Roles/Chat 页；未改数据库 schema/迁移。

## 2. 功能对照（Android oracle ↔ iOS）

| Android（oracle） | iOS 实现 | 语义 |
|---|---|---|
| `MomentsScreen` 时间线（头像/作者/正文/时间/点赞/评论 + 前 2 条评论预览） | `MomentsView.momentCard` | ✓（头像走 `RoleAvatar`：内置 1..147 Bundle / 用户头像 / 首字占位） |
| `MomentsViewModel.likeMoment`（operatorId="user" 切换 + 回写 likeCount） | `MomentsBridge.toggleLike` | ✓ 记录级幂等切换；UI 乐观更新 + 失败回滚 |
| `MainViewModel.commentOnMoment`（用户评论 + 回写 commentCount） | `MomentsBridge.addComment` | ✓ 用户评论最小面；回复归一到根 parent + replyToName；**AI 自动回帖（reply_turns/A7）不在本卡** |
| `MainViewModel.postUserMoment`（用户动态落库） | `MomentsBridge.publish` | ✓ 文本 + iOS 增强图片；**AI 自动点赞/评论（A7）不触发** |
| 未读：`getMomentBadgeSuspend` / `getUnreadCommentCountSuspend` / `markMomentsSeen` | `badges` / `markCommentsRead` / `markMomentsSeen` | ✓；打开广场首屏后推进 `last_seen_moment_id` |
| 动态详情页（MomentDetailScreen 评论/点赞） | 评论 Sheet（列表 + 输入 + 回复）+ 卡片内联预览 | 简化为 Sheet（iOS 交互习惯）；详情页路由不在本卡范围 |
| 无「删除自己动态」入口 | 「⋯」菜单 → 删除动态 → 二次确认 | iOS 增强（deliverable 要求；shared 无单条删除 API，故加最小 SQL） |
| 无动态图片（moments 表无图片列） | PhotosPicker → `MediaRepository.ingest(PICKER)` → 内容标记 `\n[图片] media/<uuid>.<ext>` | iOS 增强；出 DTO 时剥离标记为 `text`+`imageId` |

## 3. 验证证据（`ios-port/handoffs/evidence/`）

### 3.1 `--moments-selftest`（真实 SQLite，28/28 PASS（RESULT PASS））

启动：`xcrun simctl launch <UDID> com.rhodeslink.app --moments-selftest`
日志：App 容器 `Library/Application Support/t50-moments-selftest.log`（归档 `t50-selftest.log`）+ NSLog `[RhodesLink][T50SELFTEST]`。

```
=== T50 moments selftest ===
owner=博士 avatar=<empty>
fixture.cleanup removed=1
baselineMaxMomentId=0
PASS image.ingest | id=media/d59ff87f-84a4-4e42-b2e7-522cf63d5272.png
PASS image.resolve | path=file:///…/Application Support/media/images/media/d59ff87f-….png
PASS publish.ok | id=4
PASS publish.newId | baselineMax=0 id=4
PASS list.contains | pageSize=1
PASS list.text | text=T50自检1795408445732：今天和干员们一起完成了训练，记录一下。
PASS list.image | imageId=media/d59ff87f-…
PASS list.author | author=博士 mine=true
PASS list.countsInit | like=0 comment=0
PASS like.on | liked=true count=1
PASS like.off | liked=false count=0
PASS like.onAgain | liked=true count=1
PASS like.db | dbCount=1
PASS comment.ok | id=4
PASS comment.list | count=1
PASS comment.db | dbCommentCount=1
PASS comment.mine
PASS list.countsAfter | like=1 comment=1
PASS badge.newMoment | unreadMoments=1
PASS badge.markSeen | after=0
PASS delete.rejectOther | momentId=5 operator=M3
PASS delete.rejectOtherKept | 被拒后动态仍在
PASS publish.rejectEmpty
PASS delete.tempCreated | id=6
PASS delete.ok | err=-
PASS delete.gone | id=6
PASS delete.dbRow
PASS delete.dbCascade | likes=0 comments=0
fixture.kept momentId=4 image=media/d59ff87f-…
RESULT PASS
```

覆盖：图片入库/解析 → 发布（id 自增、列表出现、图片/作者/本人/初始计数）→ 点赞 ON(+1)/OFF(0)/再 ON + DB likeCount →
评论（列表/DB commentCount/isMine）+ 列表计数联动 → 新动态角标 ≥1 + markSeen 清零 → 删除**非本人**被拒且动态保留 →
空内容拒绝 → 删除本人（列表消失 + `getMoment`=null + 点赞/评论级联 0）。

### 3.2 DB 复核（`t50-db-check.txt`，sqlite3 直查容器 `Library/Application Support/rhodes.db`）

自检后（UI 删除前）：
```
## moments
id  operatorId  operatorName  isUserPost  likeCount  commentCount  createdAt
 4  user        博士                   1          2             2  1795408445732  T50自检…\n[图片] media/d59ff87f-…
## moment_likes
4|user|博士      4|mon3tr|M3
## moment_comments
4|user|博士|1|T50自检…评论：图片很清晰。
4|mon3tr|M3|0|博士拍得真好，训练场今天的风也很舒服。
moments=1 likes=2 comments=2 operators=147
```
UI 流程（真实触控）后：UI 发布动态（id=7）经「⋯→删除动态→确认」被删 → 列表 2 条→1 条；DB `moments` 仅剩夹具。
最后 `--moments-fixture-clean` → `moments=0 likes=0 comments=0`（夹具级联清理，容器无残留业务行）。

### 3.3 UI 实跑（`T50-sim` = iPhone 17 Pro / iOS 27.2；`xcrun simctl` + `/tmp/axedl/axe` 真实触控）

| 文件 | 内容 |
|---|---|
| `t50-empty.png` | 空态：头部「真实 SQLite · 0 条」+「还没有动态」+「发布第一条动态」 |
| `t50-feed.png` | 时间线：用户头像「博」+「我」徽标、正文、**图片正常渲染**、2 条评论预览（M3）、红心 2 / 评论 2、相对时间「刚刚」、无更多提示 |
| `t50-like-toggled.png` / `t50-liked.png` | axe 点「取消点赞」→ 状态/计数即时变化；再点「点赞」恢复（DB likes 2 一致） |
| `t50-comments.png` | 评论 Sheet：作者/时间/回复按钮/输入栏（2 条真实评论） |
| `t50-comment-sent.png` | axe 输入并点「发送评论」→ 新评论出现在列表，DB `moment_comments` 新增 id=7（UI 输入文本经模拟器输入法重排为 `T50UI co m men t…`，属 IME 行为，落库已证明） |
| `t50-composer.png` / `t50-composer-typed.png` | 发布器 Sheet：正文 TextEditor + 「选择图片」（PhotosPicker）+ 取消/发布 + 字数（已输入正文） |
| `t50-published.png` | axe 点「发布」→ 返回列表，新动态置顶（头部「2 条」，DB 出现 id=7） |
| `t50-delete-menu.png` / `t50-delete-confirm.png` | 「⋯」菜单 → 删除动态 → 二次确认弹窗（「删除后无法恢复，该动态的点赞与评论会一并删除」） |
| `t50-after-delete.png` | 确认删除后：列表回到 1 条（UI 发布行消失；DB `moments` 仅剩夹具） |

### 3.4 构建

```bash
cd /Users/jackchen/Documents/deepseek-harness/default-workspace && source ios-port/toolchain/env.sh
cd RhodesLink && ./gradlew --no-daemon :shared:linkDebugFrameworkIosSimulatorArm64   # BUILD SUCCESSFUL（evidence/t50-framework-link.log）
cd iosApp && TMPDIR="$PWD/tmp" xcodebuild build -project RhodesLink.xcodeproj -scheme RhodesLink \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath "$PWD/DerivedData-t50" CODE_SIGNING_ALLOWED=NO
# ** BUILD SUCCEEDED **（evidence/t50-xcodebuild.log）
```
构建纪律：gradle/xcodebuild 前检测真实进程（`ps -A -o comm=` 判 `xcodebuild`；java 进程 args 判 `GradleDaemon/GradleWrapperMain`）排队，`--no-daemon`。
本轮实际遇到并行代理半成品编译失败 2 次（T48 `KnowledgeDetailView.reload`、T51 `DiaryBridge` 冲突声明），均等待其修复后重试通过——**共享 checkout 并行构建的已知现象，非本卡代码问题**。

## 4. 写域交叉（reviewer 请重点复核）

1. **shared commonMain 3 处最小 patch**（§1 表）：仅新增 `deleteMoment` 查询 + 两处委托；不改任何既有方法语义、不改合并/迁移文件（`.sqm` 不动），金样 schema 无差异。
2. **`IosAppGraph.kt`**：与 T45/T48/T49 共享文件，本卡只追加属性/init 钩子/访问器（T49 的 `operatorBridge` 行未动）。
3. **`AppGraphClient.swift`**：追加 `momentsBridge` 访问器 1 处（T46/T47/T48 的扩展段未动）。
4. **`RouteView.swift`**：仅 `.moments` 分支 4 行；`.diary` 保持 T51 占位原文。
5. 未修改 `FeaturesView`（T48 写域）→ 功能 Tab 的动态角标仍为 0（桥已提供 `badges`，接线留给后续/由 lead 安排）。

## 5. 已知限制 / 后续建议

1. **图片跨端呈现**：`moments` 表无图片列，图片以内容标记 `\n[图片] media/<uuid>.<ext>` 实现。iOS 端渲染/剥离正常；Android 端打开该动态会显示字面标记；备份会原样带走内容（恢复后 iOS 仍可解析）。若后续允许 schema 变更（建议 `ALTER TABLE moments ADD COLUMN imageMediaId TEXT NOT NULL DEFAULT ''` + migration），可把标记迁移为列并保持兼容解析。
2. **A7 自动互动未接**：iOS 侧发布/评论不创建 `reply_turns`、不触发 AI 自动点赞/评论；等价 Android `postUserMoment`/`commentOnMoment` 的 AI 分支属 T22/T23 延迟任务层写域。
3. **功能 Tab 角标**：`badges`（新动态/未读评论）已就绪，但 `FeaturesView` 的 `momentBadge` 仍写死 0（T48 写域，本卡未改）。
4. **点赞者名单**：卡片只显示点赞数（对齐 deliverable）；Android 的「谁点了赞」与「回复他人评论」完整交互仅评论 Sheet 内提供回复输入。
5. **图片裁剪**：仅 ≤1920px JPEG 0.8 压缩，无方图裁剪/多图（原版无多图）。
6. **自检夹具**：`--moments-selftest` 结束会保留 1 条内容含 `T50` 的截图夹具（供 UI 取证）；`--moments-fixture-clean` 幂等清理（已在交付验证末尾执行，DB 归零）。
