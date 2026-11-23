# T52 实现 handoff：加急版收尾三项用户可见缺口

> 代理：impl-polish-fixes ｜ 任务：task-52（加急第 3 批收尾）｜ 状态：**实现完成，xcodebuild BUILD SUCCEEDED，iPhone 17 模拟器三项端到端实跑 PASS（2026-11-23）**
> 规格卡：`ios-port/specs/polish-fixes-spec.md` ｜ 基线提交：`a2fbb62`
> **git 未提交**（lead 统一提交）。Kotlin/shared **零改动**；`./gradlew :shared:linkDebugFrameworkIosSimulatorArm64` 校验 `UP-TO-DATE`。
> 写域：`iosApp/DesignSystem/RoleAvatar.swift`、`iosApp/Features/{Roles,Features,GroupChat}/**`、`ios-port/{specs,handoffs}`。
> 未改：Settings / Chat / Memory / Knowledge / Moments / Diary 功能页、`Bridge/*`、`AppRouter`、`RhodesLinkApp`、`commonMain/.sq`。

## 1. 交付摘要

| # | 缺口 | 修复 | 验证结论 |
|---|---|---|---|
| 1 | T49 上传头像（`media/<uuid>.<ext>`）在列表/详情显示首字占位 | `RoleAvatar` 新增 `RoleAvatarResolver`（媒体 ID → shared `OperatorBridge.mediaPath` → `UIImage`），文件缺失回退首字；probe 扩展 `media:`/`media-missing:` | `--avatar-render-selftest` **PASS=13 FAIL=0**；列表/详情截图显示真实上传图；DB `operators.avatarUri=media/...jpg`（测后恢复 `6`） |
| 2 | 功能 Tab 动态/日记角标恒 0 | `FeaturesView` 接 `MomentsBridge.badges()`（新动态+未读评论）与 `DiaryBridge.unreadCount()`；onAppear + 每 10s 刷新；未读红 #FA5151 | `--features-badge-selftest` **PASS=7 FAIL=0**；界面显示 动态=2、日记=1；日志第 2 次 refresh 间隔 10.06s |
| 3 | 群聊未读角标不出现/不刷新 | shared 已消费 `UnreadHint`（T43/T44），本卡补 Swift 侧可见性：`RolesView` 停留期间每 5s `reloadGroups()`；打开群聊沿用 `setActiveGroup`+`markSessionRead` 清零 | `--group-unread-selftest` **PASS=6 FAIL=0**；DB 运行中 `unreadCount=2`（=可见段数），打开后 0，当前群不累加；截图群聊行角标 `2` |

## 2. 文件清单

| 侧 | 类型 | 文件 | 说明 |
|---|---|---|---|
| Swift | 修改 | `iosApp/RhodesLink/DesignSystem/RoleAvatar.swift` | +`RoleAvatarSource`/`RoleAvatarResolver`（值域判定/路径缓存/NSCache 位图/媒体渲染）；`RoleAvatarProbe` 扩展 `media:` 与 `mediaRenderProbe`；`@MainActor`（依赖 `@MainActor AppGraphClient`） |
| Swift | 修改 | `iosApp/RhodesLink/Features/Features/FeaturesView.swift` | 角标接真实桥 + onAppear/10s 轮询；未读红 `GlassTokens.unreadBadge`；99+ 截断；`--features-badge-selftest` 钩子 |
| Swift | 新增 | `iosApp/RhodesLink/Features/Features/FeaturesBadgeAutomation.swift` | 日记夹具（`debugEnsureFixture`）+ 桥/界面双层断言 + 周期刷新断言 + `Documents/t52-features-badge-selftest.log` |
| Swift | 新增 | `iosApp/RhodesLink/Features/Features/PolishSelfTestLog.swift` | T52 三个自检统一日志（NSLog + 按文件落盘 Documents） |
| Swift | 新增 | `iosApp/RhodesLink/Features/Roles/AvatarRenderAutomation.swift` | `--avatar-render-selftest`：生成测试图 → T49 真实上传 → 渲染/回退/恢复断言 + 列表/详情截图窗口 |
| Swift | 修改 | `iosApp/RhodesLink/Features/Roles/RolesAutomation.swift` | +4 行：`--avatar-render-selftest` 分支 |
| Swift | 修改 | `iosApp/RhodesLink/Features/Roles/RolesView.swift` | `.task` 分支条件加 `unreadSelfTestEnabled`；新增 5s 群列表轮询（群聊未读角标近实时刷新） |
| Swift | 修改 | `iosApp/RhodesLink/Features/GroupChat/GroupChatAutomation.swift` | +`--group-unread-selftest` 全链路自检（非当前群 +N / 打开清零 / 当前群不累加）+ 可见段数断言 |
| 脚本 | 新增 | `ios-port/handoffs/t52-group-mock-server.py` | 群聊协议 mock（`[{speaker,message,type}]` SSE），使真实 provider 通道能产出可见群回复（模拟器残留 custom provider 配置时必需） |
| 文档 | 新增 | `ios-port/specs/polish-fixes-spec.md`、`ios-port/handoffs/impl-polish-fixes.md` | 本卡产出 |
| 证据 | 新增 | `ios-port/handoffs/evidence/t52-*` | 见 §4 |

## 3. 构建与验证命令（可复跑）

```bash
cd /Users/jackchen/Documents/deepseek-harness/default-workspace
source ios-port/toolchain/env.sh
cd RhodesLink
pgrep -fl "gradle|xcodebuild"                    # 串行纪律（本卡全程无并发）
./gradlew --no-daemon :shared:linkDebugFrameworkIosSimulatorArm64   # 本卡未改 Kotlin → UP-TO-DATE（证据 t52-framework-link.log）
cd iosApp
TMPDIR="$PWD/tmp" xcodebuild build -project RhodesLink.xcodeproj -scheme RhodesLink \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath /tmp/int-build CODE_SIGNING_ALLOWED=NO
# → BUILD SUCCEEDED（证据 t52-xcodebuild.log；增量复用 /tmp/int-build）

DEV=A63CD17A-E1F9-4C5C-9B8C-92DEB43862FA    # iPhone 17（勿用 booted：本机可能多台 booted）
xcrun simctl install $DEV /tmp/int-build/Build/Products/Debug-iphonesimulator/RhodesLink.app
```

### 3.1 头像渲染（`--avatar-render-selftest`）
```bash
xcrun simctl launch $DEV com.rhodeslink.app --avatar-render-selftest --tab settings
sleep 5  && xcrun simctl io $DEV screenshot evidence/t52-avatar-list.png
sleep 6  && xcrun simctl io $DEV screenshot evidence/t52-avatar-detail.png
sleep 9  && cp "$(xcrun simctl get_app_container $DEV com.rhodeslink.app data)/Documents/t52-avatar-selftest.log" evidence/
```
结果：**allPASS=true PASS=13 FAIL=0**。关键行：
- `target role=mon3tr name=M3 originalAvatar=6 probe=builtin:6`
- `PASS upload.mediaId avatarUri=media/b7eafa4e-…jpg`
- `PASS db.operators.avatarUri dbAvatar=media/b7eafa4e-…jpg`
- `PASS media.pathResolved path=…/Library/Application Support/media/images/media/b7eafa4e-….jpg bytes=43051`
- `avatar.renderProbe … image=768x768 suffix=jpg`（256pt@3x）
- `PASS fallback.missingMedia media-missing:…` / `PASS fallback.placeholder placeholder:empty` / `PASS builtin.unaffected builtin:1`
- `PASS restore.builtin avatarUri=6`（测试后自动恢复）
截图：`t52-avatar-list.png`（首行 M3 = 上传的 T52 渐变图，不再是内置立绘）、`t52-avatar-detail.png`（详情 92pt 同一上传图）。
上传媒体文件另存 `evidence/t52-avatar-uploaded.jpg`（43051B，证明媒体真实落盘）。

### 3.2 功能 Tab 角标（`--features-badge-selftest`）
预置未读（app 未运行，sqlite + NSUserDefaults 现状）：
- 动态/评论：T50 历史夹具已有 1 条本人动态（`moments.id=1`）+ 1 条未读干员评论（`moment_comments.id=3,isRead=0`）；再 `sqlite3` 插入 1 条干员动态（`id=4,isUserPost=0`），`last_seen_moment_id=1` → 新动态 1、未读评论 1；
- 日记：自检内 `DiaryBridge.debugEnsureFixture`（DB 空则写 1 条 amiya 日记并把 `diary_read_at_amiya` 归零）→ 未读干员 1。
```bash
xcrun simctl launch $DEV com.rhodeslink.app --features-badge-selftest --tab features
sleep 5  && xcrun simctl io $DEV screenshot evidence/t52-features-badges.png
sleep 12 && xcrun simctl io $DEV screenshot evidence/t52-features-badges-after10s.png
sleep 4  && cp "$(… data)/Documents/t52-features-badge-selftest.log" evidence/
```
结果：**allPASS=true PASS=7 FAIL=0**：
- `features.badge refresh#1 unreadMoments=1 unreadComments=1 momentBadge=2 diaryUnread=1 diaryBadge=1`
- `PASS view.momentBadge displayed=2 expected=2`、`PASS view.diaryBadge displayed=1 expected=1`
- `refresh#2` 在 **10.06s** 后（`PASS view.periodicRefresh10s`）→ 周期刷新生效
截图：`t52-features-badges.png` 显示「动态广场 2（红）、干员日记 1（红）」，10s 后截图数值稳定。

### 3.3 群聊未读角标（`--group-unread-selftest`）
前置：模拟器残留 `provider=custom`（`custom_url=http://127.0.0.1:18767/v1`，T45/T46 测试遗留）。T45 mock 返回私聊 ai_json，群聊解析器会过滤成空（`本轮未生成可见回复`）→ 用本卡 `ios-port/handoffs/t52-group-mock-server.py`（返回群聊协议 2 段 dialogue，speaker=电弧/阿斯卡纶）：
```bash
python3 ios-port/handoffs/t52-group-mock-server.py      # 127.0.0.1:18767 前台/后台均可
xcrun simctl launch $DEV com.rhodeslink.app --group-unread-selftest --tab contacts
sleep 8  → 截图 t52-group-unread-badge.png（联系人页群聊行角标）
sleep 8.5 → sqlite3 快照 chat_sessions.unreadCount（运行中）
sleep 14 → 截图 t52-group-unread-cleared.png（群聊页）
sleep 16 → 取 Documents/t52-group-unread-selftest.log
```
结果：**allPASS=true PASS=6 FAIL=0**：
- `PASS baseline.groupUnread unread=0`
- `PASS unreadHint.increment unread=0→2 delta=2 expectedVisible=2 demo=false error=`
- `PASS db.unreadDeltaVisibleCount delta=2 visibleSegments=2`
- `PASS openGroup.clearUnread unreadAfterOpen=0`
- `PASS viewing.noIncrement unreadWhileViewing=0`（当前群不累加，与私聊 T44 一致）
- DB 运行中快照：`group_elite|unreadCount=2`；打开后 `0`（`evidence/t52-group-unread-db.txt`）
截图：`t52-group-unread-badge.png` 群聊行红色/橙色角标 `2`；`t52-group-unread-cleared.png` 群聊页真实 AI 回复（电弧/阿斯卡纶），未读已清零。

> 说明：shared 侧 `UnreadHint → incrementUnread` 与 `setActiveGroup` 语义在 T43/T44 已实现，本卡**未改 Kotlin**；缺口在「联系人页驻留期间群列表不刷新」，故本卡在 `RolesView` 加 5s 轮询（首次刷新 ~5s 内出现角标，截图即证）。

## 4. 证据文件（`ios-port/handoffs/evidence/`）

| 文件 | 内容 |
|---|---|
| `t52-framework-link.log` | gradle framework 校验 `BUILD SUCCESSFUL`（`linkDebugFrameworkIosSimulatorArm64 UP-TO-DATE`，证明 Kotlin 零改动） |
| `t52-xcodebuild.log` | `BUILD SUCCEEDED`（0 error） |
| `t52-avatar-selftest.log` / `t52-avatar-list.png` / `t52-avatar-detail.png` / `t52-avatar-uploaded.jpg` | 头像：13 项断言、列表/详情截图、上传媒体原文件 |
| `t52-features-badge-selftest.log` / `t52-features-badges.png` / `t52-features-badges-after10s.png` / `t52-features-preseed.txt` | 角标：7 项断言、界面截图、sqlite 预置未读证据 |
| `t52-group-unread-selftest.log` / `t52-group-unread-badge.png` / `t52-group-unread-cleared.png` / `t52-group-unread-db.txt` | 群未读：6 项断言、行角标/群聊页截图、运行中/清零 DB 快照 |
| `t52-db-checks.txt` | 三项的 DB 终态核对（operators.avatarUri / moments / moment_comments / diaries / chat_sessions） |
| `ios-port/handoffs/t52-group-mock-server.py` | 群聊协议 mock（复跑群未读自检必需；端口 18767 与模拟器 provider 配置一致） |

## 5. 已知限制与遗留

1. **私聊页无头像组件**：本 iOS 壳聊天页顶部是「角色名 + 状态副标题」导航标题，不含头像；任务卡中「聊天头部头像」在本壳无对应视图，故无额外接线（已确认全仓 `RoleAvatar` 调用点覆盖列表/详情/群成员/新建聊天/动态/日记）。
2. **远程/URL 头像不渲染**：`http(s)` 头像仍是首字占位（本卡只修本机上传头像；后续可接 AsyncImage 远程缓存）。
3. **模拟器 environment 遗留**：模拟器 NSUserDefaults 仍保存 T45/T46 的 `provider=custom`（`127.0.0.1:18767`）→ 复跑群未读自检前必须先启动 `t52-group-mock-server.py`，否则真实通道失败（T45 mock 返回私聊 JSON，群聊解析为空）。未清理该配置以避免影响其它并行/后续验证；正式包里用户未配置时走群聊 Demo 通道（`isDemoMode`），未读链路同样成立。
4. **测试夹具遗留**：`moments.id=4`（T52 sqlite 预置干员动态）、`moment_comments.id=3`（T50 未读评论）、`diaries.id=1`（amiya 日记，read_at=0）、群聊测试消息（`group_elite`）+ 媒体目录 1 个孤儿上传文件（`media/...jpg`，已另存证据）。均为截图/复核夹具；如需恢复干净环境可在 app 未运行时删除对应行（见 `t52-db-checks.txt` 的 DB 路径）。头像角色（M3/amiya）已由自检恢复原内置头像。
5. **轮询成本**：功能 Tab 10s / 联系人页群列表 5s 为前台轻量查询；后续建议改事件驱动（`UnreadHint` 通知 + 列表 reload）或后台推送。
6. **并行代理文件**：收尾时 `git status` 出现 `shared/src/iosMain/.../iosbridge/{DebugLogBridge,NotificationBridge,SettingsExtraBridges}.kt` 三个未跟踪文件（13:46 由其它代理产出），**非本卡创建/改动**，本卡不纳入提交；本卡 Kotlin/shared 零改动（`t52-framework-link.log` 的 `UP-TO-DATE` 为证）。
7. **未做真机/深色模式专项截图**：三项均为数据链路修复，浅色模拟器已覆盖用户可见结果；深色/无障碍沿用 T36 既有组件。
