# impl-settings-extra.md — T53 设置余项（调试日志 / 通知 / 外观）handoff

> 代理：`impl-settings-extra`（task-53）｜分支：`RhodesLink@ios-port`（Kotlin 2.3.21 / SwiftUI iOS 18+）
> 状态：**实现完成 + 模拟器端到端全绿**（调试日志读取/解析/导出/清空往返、通知 4 键落盘冷启动保持、
> 外观 3 键（主题/聊天背景/字号）落盘 + 冷启动 SettingsStore 读回一致；4 张截图证据）。
> git 不提交（lead 统一提交）；`ios-port/` 非 git 仓库。
> 构建纪律：T54 并行 xcodebuild 排队；`IosAppGraph.kt` / `SettingsBridge.kt` / `AppGraphClient.swift` **本卡未改**（T54 写域）。

---

## 1. 交付清单（写域内）

### Kotlin（shared/iosMain，仅新增文件）

| 文件 | 内容 |
|---|---|
| `.../iosbridge/DebugLogBridge.kt`（新） | `write(level,tag,message)` / `snapshot(limit)` / `exportToFile()` / `clearAll()` / `pathsSummary()`；DTO：`DebugLogEntryDto` / `DebugLogSnapshotDto`；行解析 `ISO8601 [LEVEL] [tag] message`（缺字段降级 RAW） |
| `.../iosbridge/NotificationBridge.kt`（新） | 权限 `authorizationStatus` / `requestFullAuthorization` / `requestProvisionalAuthorization` / `openSystemSettings`（包 T32 `IosNotificationScheduler`）；4 个提醒偏好键 `notify_private_reply` / `notify_group_reply` / `notify_moments` / `notify_diary`（`readPrefs()` / `savePref(scope,enabled)` / `prefsSnapshot()`）；DTO：`NotificationPrefsDto` |
| `.../iosbridge/SettingsExtraBridges.kt`（新） | 工厂 `makeSettingsExtraBridges()`（Swift `SettingsExtraBridgesKt.makeSettingsExtraBridges()`），自带一份 `SettingsRepository(createPlatformSettings())`（NSUserDefaults 标准域） |

说明：**未改** `IosAppGraph.kt` / `SettingsBridge.kt`（T54/T45 并行写域），本卡能力全部经新增工厂对象暴露。

### Swift（iosApp/RhodesLink）

| 类型 | 文件 | 内容 |
|---|---|---|
| 新增 | `Bridge/SettingsExtraClient.swift` | Kotlin 类型只在此文件出现；`DebugLogEntryItem` / `DebugLogSnapshotItem` / `NotificationPrefsItem` / `NotificationPermissionItem`；async 权限包装（`withCheckedContinuation`） |
| 新增 | `Features/Debug/DebugLogsView.swift` | 调试日志页：状态卡（路径/大小/归档/行数）+ 级别 segmented + 关键词搜索 + 生成导出文件 → `ShareLink` + 清空二次确认 + 条目列表（时间/级别/tag/正文 2 行截断点按展开）+ 下拉刷新 + `--settings-extra-demo-logs` 演示钩子 |
| 新增 | `Features/Settings/NotificationSettingsView.swift` | 通知设置页：授权状态卡（真实 UNUserNotificationCenter 状态 + 请求完整/静默授权 + 打开系统设置）、4 提醒开关（每行显示 settings 键名，切换即写即读回）、前台 vs 后台说明、诊断卡 |
| 新增 | `Features/Settings/AppearanceSettingsView.swift` | 外观设置页：主题分段（写 `ios_shell_appearance` + 同步 `dark_mode`）、聊天背景 6 宫格（选中大预览含示例气泡 + 色板）、字体大小分段（写 `ios_shell_font_size`）+ 三键落盘读回卡 |
| 新增 | `Features/Settings/SettingsExtraAutomation.swift` | `--settings-extra-selftest`（写+断言 9 项）/ `-verify`（冷启动只读 6 项）/ `-restore`（恢复默认） |
| 新增 | `DesignSystem/ChatAppearance.swift` | `ChatBackgroundStyle`（4 纯色 + 2 渐变，深浅色自适应）+ `ChatBackgroundView`；`stored()` 读 UserDefaults |
| 修改 | `DesignSystem/GlassTokens.swift` | `chatBackground` 由 `static let Color` 改为 `static var some View`（渲染时读 `ChatBackgroundStyle.stored()`）→ **ChatView.swift 一行未改**，重进聊天页即生效 |
| 修改 | `App/AppEnvironment.swift` | `SettingsStore` 新增 `FontSize`（小/标准/大 → DynamicTypeSize）、`chatBackground`、`fontSize` 状态 + `setChatBackground` / `setFontSize`（写键 + 快照 + SelfTestLog） |
| 修改 | `RhodesLinkApp.swift` | `RootTabView` 增加 `AppFontScaleModifier`（标准档跟随系统、小/大档强制 Dynamic Type）；启动接 `SettingsExtraAutomation.runIfNeeded` |
| 修改 | `App/AppRouter.swift` | 新路由 `.settingsNotifications` / `.settingsDebugLogs`；深链 `rhodes://settings/{notifications\|notify\|debug-logs\|logs}` |
| 修改 | `Features/Common/RouteView.swift` | `.settingsAppearance` 接 `AppearanceSettingsView`（替换占位）；新增两路由 → 新页面 |
| 修改 | `Features/Settings/SettingsView.swift` | 路由行卡：外观设置 / **通知设置** / **调试日志** / 可访问性 / 模型供应商 / 聊天表现 / 提示词 / 数据与备份（共 8 行） |

### ios-port 资产

- `ios-port/specs/settings-extra-spec.md`（键表 / 桥 API / 页面行为 / 验收 / 已知限制）
- `ios-port/handoffs/evidence/t53-*`（见 §5）

---

## 2. 关键设计决策

1. **不改共享写域文件**：T54 并行改 `IosAppGraph.kt` / `AppGraphClient.swift`；本卡用独立工厂 `SettingsExtraBridges.kt`
   + Swift `SettingsExtraClient`，零新增依赖到 graph。通知偏好桥自带 `SettingsRepository(createPlatformSettings())`，
   与 graph 内实例读写同一 NSUserDefaults 标准域（已验证冷启动读回一致）。
2. **调试日志复用 T32 `IosDebugLogger`**：不新建日志设施；只补「解析成行 DTO + 导出路径 + 清空」能力。
   行格式 `2026-11-23T05:55:20.930166Z [INFO] [T53SELFTEST] …`（Kotlin Clock ISO8601），Swift 显示本地 `HH:mm:ss.SSS`。
   导出 = 复制主文件到 `Application Support/exports/rhodes-log-<epoch>.log`（`ShareLink` 分享该 URL）。
3. **通知偏好 4 键为本卡定义**：Android oracle 只有通知中心 + 系统权限，无提醒开关键；iOS 先定义并默认全开
   （不改变现状）。权限状态不落键（每次实时查系统，系统为权威）。UI 明示「事件源接线属后续批次」。
4. **聊天背景用 token 间接生效**：纪律要求不改 Chat/GroupChat 功能页，因此把 `GlassTokens.chatBackground`
   从固定 `Color` 改为渲染时读 `ChatBackgroundStyle.stored()` 的 `some View`；`ChatView.swift` 零改动，
   设置页改完重进聊天页生效。群聊页仍用系统分组背景（已知限制）。
5. **字体大小用 `.dynamicTypeSize` 全局缩放**：标准档不加 modifier（跟随系统、保留可访问性字号），
   小 = `.small`、大 = `.xxLarge`；`RootTabView` 一处 modifier 覆盖聊天/列表/设置全部页面，同样无需改功能页。
6. **自动化三阶段**（`--settings-extra-selftest` / `-verify` / `-restore`）：写入阶段断言 + `flushDefaults()`
   强制落盘；**另起进程**冷启动只读断言（真正证明持久化：日志文件跨进程 + 可再导出；通知/外观键 + SettingsStore 读回）；
   最后恢复阶段清理测试态（外观回 system/classic/standard、通知全开；日志保留为证据）。

---

## 3. 验证结果（模拟器 iPhone 17，UDID A63CD17A-E1F9-4C5C-9B8C-92DEB43862FA）

| 项 | 结果 | 证据 |
|---|---|---|
| `:shared:linkDebugFrameworkIosSimulatorArm64` | **BUILD SUCCESSFUL** | `evidence/t53-framework-link.log` |
| iosApp `xcodebuild`（iOS 18+ 模拟器） | **BUILD SUCCEEDED** | `evidence/t53-xcodebuild.log` |
| 写入阶段 9 项断言 | **9 PASS / 0 FAIL** | `evidence/t53-selftest-write.log` |
| 冷启动持久化 6 项断言 | **6 PASS / 0 FAIL** | `evidence/t53-selftest-verify.log` |
| NSUserDefaults 落盘核对 | `ios_shell_appearance=dark`、`ios_chat_background=dusk`、`ios_shell_font_size=large`、`dark_mode=true`、`notify_private_reply=false`、`notify_group_reply=true`、`notify_moments=false`、`notify_diary=true` | `evidence/t53-nsuserdefaults-write.txt` |
| 页面截图 | 设置主页 8 行（外观设置/通知设置/调试日志…）、外观页（深色 + 暮色渐变选中 + 字号大）、通知页（真实权限状态「未决定」+ 3 按钮 + 4 开关混合状态）、调试日志页（11 行多级别日志 + 筛选/搜索/导出/清空） | `evidence/t53-settings-home-rows.png`、`t53-settings-appearance.png`、`t53-settings-notifications.png`、`t53-settings-debug-logs.png` |
| 恢复默认（清理测试态） | 外观 system/classic/standard、通知 4 键全 true、flush=true | `evidence/t53-selftest-restore.log` |

### 3.1 写入阶段断言逐条（最终二进制，2026-11-23 14:00:05）

```
settings-extra-check PASS notifications.permission-readable status=0/notDetermined granted=false
settings-extra-check PASS debug-logs.read-non-empty lines=13 shown=13 bytes=1336 path=…/Library/Application Support/logs/rhodes.log
settings-extra-check PASS debug-logs.parse-fields level=INFO tag=T53SELFTEST time=2026-11-23T06:00:05.367400Z
settings-extra-check PASS debug-logs.files-listed files=…/Library/Application Support/logs/rhodes.log
settings-extra-check PASS debug-logs.export-file path=…/Library/Application Support/exports/rhodes-log-1795413605370.log size=1336 containsMarker=true
settings-extra-check PASS debug-logs.clear-and-rewrite cleared=true linesAfterClear=0 linesAfterRewrite=1
settings-extra-check PASS notifications.prefs-save-readback errors= snapshot=private=false;group=true;moments=false;diary=true keys=notify_private_reply,notify_group_reply,notify_moments,notify_diary
settings-extra-check PASS notifications.keys-non-empty keys=notify_private_reply,notify_group_reply,notify_moments,notify_diary
settings-extra-check PASS appearance.keys-save-readback appearance=dark background=dusk font=large store: appearance=dark background=dusk font=large
=== settings-extra selftest write done pass=9 fail=0 ===
```

> 说明：`lines=13` 是模拟器上历史累计日志行数（含截图演示钩子写入的条目）；清空后 `linesAfterClear=0` 证明清空真实生效。

### 3.2 冷启动持久化断言（另起进程，只读；2026-11-23 14:00:17）

```
settings-extra-check PASS persisted.debug-logs lines=1 bytes=96 path=…/Library/Application Support/logs/rhodes.log
settings-extra-check PASS persisted.debug-logs-reexport path=…/Library/Application Support/exports/rhodes-log-1795413617259.log size=96
settings-extra-check PASS persisted.notification-prefs snapshot=private=false;group=true;moments=false;diary=true
settings-extra-check PASS persisted.appearance-keys appearance=dark background=dusk font=large
settings-extra-check PASS persisted.appearance-store-readback store appearance=dark background=dusk font=large
settings-extra-check PASS persisted.dark-mode-synced snapshot=dark=true;skin=rhodes_night;demo_note=
=== settings-extra selftest verify done pass=6 fail=0 ===
```

---

## 4. 复现命令

```bash
cd /Users/jackchen/Documents/deepseek-harness/default-workspace
source ios-port/toolchain/env.sh
cd RhodesLink

# 1) shared framework（先 pgrep 排队；T54 并行时注意串行）
./gradlew --no-daemon :shared:linkDebugFrameworkIosSimulatorArm64

# 2) iosApp
cd iosApp
TMPDIR="$PWD/tmp" xcodebuild build -project RhodesLink.xcodeproj -scheme RhodesLink \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath "$PWD/DerivedData-t53" CODE_SIGNING_ALLOWED=NO

# 3) 安装 + 写入阶段自检（日志读取/导出/清空 + 通知/外观键写入）
UDID=A63CD17A-E1F9-4C5C-9B8C-92DEB43862FA
EVID=/Users/jackchen/Documents/deepseek-harness/default-workspace/ios-port/handoffs/evidence
xcrun simctl install $UDID DerivedData-t53/Build/Products/Debug-iphonesimulator/RhodesLink.app
xcrun simctl launch $UDID com.rhodeslink.app --selftest --settings-extra-selftest
sleep 7
C=$(xcrun simctl get_app_container $UDID com.rhodeslink.app data)
cp "$C/Documents/ios-shell-selftest.log" $EVID/t53-selftest-write.log
plutil -p "$C/Library/Preferences/com.rhodeslink.app.plist" > $EVID/t53-nsuserdefaults-write.txt

# 4) 冷启动持久化校验（另起进程，只读）
xcrun simctl terminate $UDID com.rhodeslink.app
xcrun simctl launch $UDID com.rhodeslink.app --selftest --settings-extra-verify
sleep 6
cp "$C/Documents/ios-shell-selftest.log" $EVID/t53-selftest-verify.log

# 5) 截图
xcrun simctl terminate $UDID com.rhodeslink.app; xcrun simctl launch $UDID com.rhodeslink.app --tab settings --settings-route-rows
sleep 4; xcrun simctl io $UDID screenshot $EVID/t53-settings-home-rows.png
xcrun simctl terminate $UDID com.rhodeslink.app; xcrun simctl launch $UDID com.rhodeslink.app --tab settings --open-url "rhodes://settings/appearance"
sleep 4; xcrun simctl io $UDID screenshot $EVID/t53-settings-appearance.png
xcrun simctl terminate $UDID com.rhodeslink.app; xcrun simctl launch $UDID com.rhodeslink.app --tab settings --open-url "rhodes://settings/notifications"
sleep 4; xcrun simctl io $UDID screenshot $EVID/t53-settings-notifications.png
xcrun simctl terminate $UDID com.rhodeslink.app; xcrun simctl launch $UDID com.rhodeslink.app --tab settings --open-url "rhodes://settings/debug-logs" --settings-extra-demo-logs
sleep 4; xcrun simctl io $UDID screenshot $EVID/t53-settings-debug-logs.png

# 6) 恢复默认（清测试态）
xcrun simctl terminate $UDID com.rhodeslink.app
xcrun simctl launch $UDID com.rhodeslink.app --selftest --settings-extra-restore
sleep 5
cp "$C/Documents/ios-shell-selftest.log" $EVID/t53-selftest-restore.log
```

---

## 5. 证据清单（`ios-port/handoffs/evidence/`）

| 文件 | 内容 |
|---|---|
| `t53-framework-link.log` | shared framework 链接日志（BUILD SUCCESSFUL） |
| `t53-xcodebuild.log` | iosApp 编译日志（BUILD SUCCEEDED） |
| `t53-selftest-write.log` | 写入阶段 9 项断言 + 导出路径 + flush |
| `t53-selftest-verify.log` | 冷启动 6 项持久化断言 |
| `t53-nsuserdefaults-write.txt` | 写入后 NSUserDefaults plist（8 个相关键） |
| `t53-nsuserdefaults-restored.txt` | 恢复阶段后 NSUserDefaults plist（外观回默认、通知全开） |
| `t53-settings-home-rows.png` | 设置主页 8 行路由（含通知设置 / 调试日志） |
| `t53-settings-appearance.png` | 外观页：深色主题 + 暮色渐变选中（含示例气泡）+ 字号大 |
| `t53-settings-notifications.png` | 通知页：权限「未决定」+ 请求按钮 + 4 开关混合状态 |
| `t53-settings-debug-logs.png` | 调试日志页：11 行多级别日志 + 级别筛选 + 搜索 + 导出/清空 |
| `t53-selftest-restore.log` | 恢复默认阶段日志（外观 system/classic/standard、通知全开） |

---

## 6. 构建/环境说明

1. **xcodebuild 排队**：T54 同批在跑 `DerivedData-t54`，其首轮因自身文件编译错误失败；本卡构建脚本先 `pgrep` 等待
   其构建结束（连续 20s 无 xcodebuild）再执行，最终 `BUILD SUCCEEDED`。后续代理若遇同类错误，先确认不是并行批次文件导致，
   不要修改他卡写域文件。
2. **Kotlin 框架复用**：本卡 framework 链接时 T54 的 `RankingBridge` / `ImpressionsBridge` 已并入同一份 `Shared.framework`；
   最终 iosApp 二进制与证据在**同一次构建产物**上采集（见时间戳）。
3. **NSUserDefaults 取证时机**：`plutil -p` 需在 App 退出后 1–2s 再读（cfprefsd 异步落盘）；首采曾读到旧文件（只有 `dark_mode`），
   二次采集完整（见证据）。
4. **模拟器权限**：本卡只读权限状态（`status=0 notDetermined`），未触发系统授权弹窗（模拟器完整授权升级弹窗不出现，T13 结论）。
5. **未跑**：shared 全量单测本卡未新增 shared 测试（T53 为桥接线 + UI；解析/持久化语义由自检覆盖）。如需回归可跑
   `:shared:testDebugUnitTest`（T52/T54 并行时注意排队）。

---

## 7. 未支持 / 下一批

- **通知事件源接线**：`IosNotificationScheduler` 已有，但聊天回复/动态/日记落库后尚未按 4 个偏好键发本地通知（需改聊天/日记管线，
  属其它卡写域）；本卡先保证偏好键与权限 UI 真实。
- **群聊背景**：`GroupChatView.swift` 仍用系统分组背景（纪律禁止改功能页）；后续统一到 `ChatBackgroundStyle`。
- **字体档位叠加系统字号**：小/大档强制 Dynamic Type，覆盖系统可访问性字号；标准档跟随系统（见 spec §6）。
- **日志级别开关 / 归档列表详情**：只列归档文件路径（当前 0 个），未做归档浏览；Android `debug_log_enabled` 语义未移植。
- **分享面板 UI 驱动**：`ShareLink` 已接导出文件 URL，但截图证据以文件生成（`exports/rhodes-log-*.log`）为准，未驱动系统分享面板。
