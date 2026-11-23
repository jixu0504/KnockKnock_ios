# handoff：ios-shell-design（Phase 0 玻璃壳 Demo，task-8）

> 代理：ios-shell-design ｜ 任务：T8 SwiftUI 液态玻璃壳 Demo ｜ 状态：**完成（编译通过）**
> 交付目录：`ios-port/iosApp-demo/`、`ios-port/specs/玻璃设计规范-v1.md`、本文件

## 1. 交付物清单

| 路径 | 说明 |
|---|---|
| `ios-port/iosApp-demo/GlassShellDemo.xcodeproj/` | 手写工程（objectVersion 77）+ 共享 scheme |
| `ios-port/iosApp-demo/GlassShellDemo/` | 源码（8 个 Swift 文件 + Assets.xcassets） |
| `ios-port/iosApp-demo/build-ok.log` | 通过构建的完整日志（BUILD SUCCEEDED） |
| `ios-port/specs/玻璃设计规范-v1.md` | token、一屏玻璃 ≤5 原则、双版本对照表、附录 C 逐条标注 |

源码结构（与计划书 §三.1 的 iosApp 骨架一致）：
`GlassShellDemoApp.swift`（App+TabView）→ `DesignSystem/`（GlassTokens、GlassPanel、AmbientBackground）→ `Features/`（Chat/Roles/Memory/Settings 四个占位页）。

## 2. pbxproj 方案（同步组）

- 采用 **`PBXFileSystemSynchronizedRootGroup`**（objectVersion 77，`preferredProjectObjectVersion = 77`）：整目录 `GlassShellDemo/` 同步进 target（`fileSystemSynchronizedGroups`），**增删源文件无需维护 pbxproj 条目**；pbxproj 里 Sources/Frameworks/Resources 三个 phase 均为空列表。
- `GENERATE_INFOPLIST_FILE = YES`（无独立 Info.plist，用 `INFOPLIST_KEY_*`）；部署目标 `IPHONEOS_DEPLOYMENT_TARGET = 18.0`；`SWIFT_VERSION = 5.0`；bundle id `com.rhodeslink.demo.GlassShellDemo`。
- 共享 scheme 位于 `xcshareddata/xcschemes/GlassShellDemo.xcscheme`，`xcodebuild -scheme` 可直接使用。

## 3. 编译结果（round 1 一次通过，0 error）

```bash
cd ios-port/iosApp-demo
TMPDIR="$PWD/tmp" xcodebuild build -project GlassShellDemo.xcodeproj \
  -scheme GlassShellDemo -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath "$PWD/DerivedData" CODE_SIGNING_ALLOWED=NO
# → ** BUILD SUCCEEDED **（arm64 + x86_64 双 slice，SDK 27.2，部署目标 18.0）
```

警告仅 1 条（无害）：`appintentsmetadataprocessor: Metadata extraction skipped, no AppIntents.framework dependency found`（Demo 未用 AppIntents，属预期）。

### ⚠️ 环境适配说明（重要，Phase 3 必须知道）

本会话（DSH 沙箱，workspace-write 模式）下 xcodebuild 需要三项适配，已全部内建/写进构建命令：

1. **`OTHER_SWIFT_FLAGS = "$(inherited) -disable-sandbox"`（写死在 target 配置）**：沙箱内 `sandbox-exec` 被内核拒绝（`sandbox_apply: Operation not permitted`），导致 Swift 宏插件服务器（`swift-plugin-server`，承载 `@State/@Binding/@Environment/@main` 等宏）直接失败。`-disable-sandbox` 让 swiftc 以非沙箱方式拉起插件服务器，验证有效（日志中出现 6 次）。**风险与建议**：这略微降低宏展开的进程隔离；正式 Phase 3 工程在非沙箱环境构建时**可移除该 flag 恢复默认沙箱**。若不移除也能构建，但不建议带入发布配置。
2. **`-derivedDataPath` 必须指向工作区内**（沙箱禁写 `~/Library/Developer/Xcode/DerivedData` 与 `/var/folders/...`）。
3. **`TMPDIR` 需指向工作区内**（同上原因）。上两条不需要写进 pbxproj，是构建命令层面的适配。

## 4. API 事实（SDK 27.2 swiftinterface 实测，供 Phase 3 直接引用）

- `tabBarMinimizeBehavior(_:)`（注意是 **tabBar** 不是 tabView；报告 §4.3 正确）——`TabBarMinimizeBehavior.automatic / .onScrollDown / .never`。
- `glassEffect(_ glass: Glass = .regular, in shape: some Shape = DefaultGlassEffectShape())`；`Glass` 有 `.regular/.clear/.identity` + `.tint(Color?)` + `.interactive(_:)`；shape 接受 `.rect(cornerRadius:)`/`.capsule`/`.circle`。
- `GlassEffectContainer(spacing: CGFloat? = nil, content:)`。
- `.buttonStyle(.glass)` / `.glass(_ glass: Glass)`（`GlassButtonStyle`），另有 `.glassProminent`。
- `glassEffectID(_:in:)`、`glassEffectTransition`、`UIGlassEffect`/`UIGlassContainerEffect`（UIKit）——本 Demo 未用，Phase 3 做融合动画时可用。
- `onScrollGeometryChange(for:of:action:)`（iOS 18+，视差用）。
- 以上全部先经 `swiftc -typecheck -target arm64-apple-ios18.0-simulator` 探针验证后再写入源码，Demo 编译即为二次验证。

## 5. 已实现（对照任务要求）

✅ TabView 4 个占位 Tab（聊天/角色/记忆/设置），iOS 26 `.tabBarMinimizeBehavior(.automatic)` 条件启用
✅ 统一 token（glassRegular/glassClear/glassTint/glassStroke/glassRadius/glassShadow + 品牌橙、深色为主）
✅ AmbientBackground 三层结构 + 滚动视差（`onScrollGeometryChange` 注入）+ 立绘占位（SwiftUI 形状，真实资产 Phase 2）+ 降低透明度/增强对比度/减弱动态效果响应
✅ Chat：NavigationStack 玻璃导航栏（系统自动）+ 非玻璃消息气泡 + GlassInputBar（`GlassEffectContainer` + 背景层 `.glassEffect`、文字层 `.foregroundStyle(.primary)`、3 个玻璃按钮上限）
✅ Settings：玻璃卡片列表（4 玻璃卡 + 1 非玻璃行，12pt 间距，一屏 ≤5）
✅ 可访问性逐项：`accessibilityReduceTransparency` / `colorSchemeContrast` / `accessibilityReduceMotion` / `legibilityWeight` / `dynamicTypeSize`（设置页有实时状态区可自查）
✅ 无第三方依赖

## 6. 遗留问题 / 需复查清单（无模拟器 runtime，全部视觉项待 Phase 3）

**本机无 iOS 模拟器 runtime，验收为「编译通过」；以下项全部未做视觉验证：**

1. iOS 26 真玻璃观感（折射/透镜/环境取色）：需真机（iOS 26+）或安装 runtime 后复查——**人类签字点**（计划书 Gate 3「玻璃观感评审」）。
2. `GlassEffectContainer` 融合效果与 `spacing: 10` 的观感是否合适；输入胶囊与两个玻璃按钮的形态融合。
3. 键盘弹起/收起无跳变（结构已按 `safeAreaInset` + `ignoresSafeArea(.keyboard)` 处理，未实测）。
4. 滚动 60/120fps：需 Instruments（iPhone 15 / ProMotion 机）。
5. 浅色模式：Demo 强制深色（`preferredColorScheme(.dark)`）；Phase 3 改为跟随系统，浅色 token（glassStroke 加深、环境渐变提亮）需重新调校并过 4.5:1 对比。
6. Tab Bar 最小化/浮动、导航栏系统玻璃：需 iOS 26 runtime 实际观感确认。
7. 光斑/立绘占位的构图美感（Demo 用形状剪影，真实立绘由 Phase 2 `AmbientBackground` 交付物替换）。
8. 大字号（accessibility5）下输入栏/卡片是否溢出：语义字体 + 无固定高度已尽量规避，未实测。

## 7. 对 Phase 3 正式壳的建议

1. **直接复用本 Demo 的 `GlassPanel` / `GlassInputBar` / `GlassTokens`** 作为 DesignSystem 起点；页面层继续「只引 token、不碰 iOS 26 API」的纪律。
2. 正式工程若在非沙箱环境构建，移除 `OTHER_SWIFT_FLAGS -disable-sandbox`；保留 `-derivedDataPath`/`TMPDIR` 适配仅当构建仍跑在 DSH 沙箱内。
3. `preferredColorScheme(.dark)` 移除，改为跟随系统；补浅色 token 与两套描边。
4. 路由加入二级页面后验证长按返回历史栈（NavigationStack 原生支持）；弹窗一律系统 Alert/Sheet（自动玻璃），禁止自绘假弹窗。
5. 若做输入栏展开/收起、Tab 切换融合动画：用 `@Namespace` + `.glassEffectID` + `.glassEffectTransition`，时长 ≤300ms 且 `accessibilityReduceMotion` 下退化为淡入。
6. 一屏玻璃计数若超 5（如设置页分组多），用系统 `List` 的 iOS 26 玻璃分组样式或折叠分组，不要自增卡片。
7. 编译验证命令已固化在 `ios-port/iosApp-demo/build-ok.log` 同款（见 §3）；建议 Phase 3 建立 CI 时以该命令为基线。
