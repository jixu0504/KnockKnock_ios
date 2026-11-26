# RhodesLink iOS ×「敲敲」UI 全面改版 计划书 v1.1

> v1.1 变更（2026-11-25）：① 纳入用户补充的 3 张 knock knock App 截图，新增「条形栏组件 + 配色逻辑」规格；② 冻结用户 6 条决策；③ 构建路线定为 B（本机自主修复）；④ 验证改为真机（设备已连接）；⑤ 外观保留深/浅/跟随系统 + 字号档位。
>
> 目标：把整个 App 的视觉语言统一为《绝区零》「敲敲」风格。
> 参考素材：图 A 聊天详情页（704×1678）+ 图 B/C/D knock knock App（1850×290 / 2436×1446 / 2420×1438）。
> 本计划只改**视觉层**，不改 `shared` 业务逻辑与数据契约。

---

## 〇、需求冻结（v1.1）

| # | 需求 | 决策 |
|---|---|---|
| 1 | 范围 | **整个 App 统一**（4 Tab + 全部二级页面） |
| 2 | 字体 | **系统 PingFang 超粗近似**（描边加粗补偿，不引入字体文件） |
| 3 | 事件卡 | **不还原**（不做「天网危机」类卡片消息） |
| 4 | 头像 | **不复用原 147 个内置头像**；先用「黑底蓝圈 + 首字」占位，后续替换绝区零人物 |
| 5 | 液态玻璃 | 若阻碍还原则考虑弃 SwiftUI → 见第五章（当前结论：不弃用，给触发标准） |
| 6 | **外观**（v1.1 新增） | **保留 深色 / 浅色 / 跟随系统 + 字号三档**；删除 6 档聊天背景（改固定风格） |
| 7 | **iPad**（v1.1 新增） | **不适配**，仅 iPhone 竖屏 |
| 8 | **构建**（v1.1 新增） | 用户不提供 framework → **路线 B：本机自主修复 KMP 构建链** |
| 9 | **验收方式**（v1.1 修订） | **本机 iOS 27 模拟器截图**（用户已清理磁盘，`xcodebuild -downloadPlatform iOS` 已启动）；真机 `Chen 的 iphone 17` 已连接，仅作最终抽查 |

---

## 一、设计规格（实测）

### 1.1 图 A：聊天页

| Token | 实测 | iOS 值 |
|---|---|---|
| 背景 | `#000000` | `#000000` |
| 发送气泡 | `#3364E7`（采样 #3364E7/#3463E7/#2F65E7） | `#3364E7` |
| 接收气泡 | `#FFFFFF`，文字 `#111` | 白 / `#0B0B0C` |
| 气泡尺寸 | 高≈61px，圆角≈半高 | 胶囊，字号 **18pt** heavy，内边距 H10/V8，行距 4pt |
| 尾巴 | 角色左上 / 玩家右上，小三角 | Path 绘制 8×6pt |
| 头像 | 直径≈66px | **36pt** 圆形，无描边 |
| 顶栏 | 深灰对话气泡图标 + 左对齐 20pt heavy 标题 | 44pt 自绘顶栏 |
| 时间分隔 | 灰 `- 一分钟前 -` | 13pt medium `#8A8A8E` |
| 间距 | 同人≈18px / 换人≈33px / 页边距≈21px | 10 / 18 / 12pt |

### 1.2 图 B/C/D：knock knock App（条形栏与配色逻辑，v1.1 新增）

**实测色板**

| 角色 | 实测值 | 用途 |
|---|---|---|
| **电光黄（品牌主色）** | `#F3FF00` / `#FCFE01` / `#E2FE00` → 取 **`#F2FF00`** | 仅用于：选中胶囊、主按钮、品牌 logo、Tab 选中 |
| 黄·按压/暗部 | — | `#D8E600` |
| 画面底 | `#111111` + `#050505` 斜切迷彩纹理 | 窗口/页面背景 |
| 面板底面 | `#0A0A0A` | 内容面板、分组容器 |
| 列表行底 | `#171717`（带极淡棋盘格纹理） | 列表行、输入框 |
| 行/面板描边 | `#2A2A2E` ~ `#3A3A3E`（1.5–2pt） | 面板、行、胶囊容器 |
| 搜索框 | 底 `#1C1C1C` + 描边 `#303030` | 搜索条 |
| 主文字 | `#FFFFFF` | 标题、选中外文字 |
| 次文字 | `#9A9A9A` | 副标题、占位符、说明 |
| 选中黄胶囊外圈 | 浅蓝 `#8CC4FF`（2–3px） | 黄底选中项的高光描边（关键细节） |
| 危险/关闭 | `#BF2207` 底 + 亮红图标 `#FF3B30` | 关闭胶囊、删除、错误 |

**条形栏规格（按 iPhone 适配，原图为 2x 桌面尺寸）**

| 组件 | 规格 |
|---|---|
| 搜索条 | 胶囊（radius=半高，高 44pt），底 `#1C1C1C`，描边 1.5pt `#303030`；左侧文字灰色加粗，右侧 1pt 竖分隔线 + 放大镜图标 |
| 一级分段栏（Tab 替代） | 胶囊容器（高 44pt，底 `#0A0A0A`，描边 1.5pt `#2A2A2E`）；内部 3–4 等分项；**选中项 = 黄色胶囊（内缩 4pt）+ 浅蓝 1.5pt 外圈 + 黑色 heavy 图标/文字**；未选中 = 白色 heavy 图标/文字 |
| 二级筛选栏 | 同款式但更小（高 36pt）；末尾可接「全部 ⌄」下拉胶囊（同款描边） |
| 面板 | 圆角 20pt，底 `#0A0A0A`，描边 1.5pt `#2A2A2E`，内边距 16pt |
| 列表行 | **胶囊行**（radius=半高，高 64–72pt），底 `#171717`，描边 1.5pt `#3A3A3E`；左侧 44pt 圆形图标/头像；主标题 15pt heavy 白、副标题 13pt `#9A9A9A` |
| 分组标题 | 14pt heavy 白（如「绳网」），右侧操作链接「+ 新建」14pt heavy 白/黄 |
| 虚线按钮 | 胶囊，虚线 1.5pt `#3A3A3E`，居中 14pt heavy 白/黄文字 + 图标 |
| 空态 | 宽字距灰字 pill「E M P T Y」（底 `#060606`，字 `#5A5A5A`，字距 8pt）；面板顶部「NoData」行 |
| 顶栏（App 内） | 左：黄色手绘 logo（敲敲门）+ 白色 heavy 品牌字；右：红色胶囊关闭/返回键 |
| 背景纹理 | 低对比斜切迷彩块（`#0D0D0D` on `#050505`）+ 面板内极淡 2pt 棋盘格；**禁止**使用毛玻璃/模糊 |

**配色逻辑（v1.1 核心裁决）**

```
黑（≈85% 面积）：底 + 面板 + 行 + 描边层次
白（≈10%）：主文字、未选中项、图标
电光黄（≤5%）：仅"当前选中 / 主 CTA / 品牌"——绝不整片铺开
蓝（≤3%）：仅聊天语义——发送气泡、链接、进度（图 A 的聊天蓝 #3364E7）
灰阶（≈5%）：副文字 #9A9A9A、黄胶囊外圈高光 #8CC4FF、行底 #171717
红（<1%）：关闭、删除、错误
```

> 两套强调色的分工是本项目最关键的视觉纪律：**黄 = App 交互语言；蓝 = 聊天消息语言**。禁止跨用（例如用黄色画发送气泡、用蓝色画 Tab 选中）。

### 1.3 浅色模式推导（因保留深/浅/跟随系统）

| 深色 | 浅色 |
|---|---|
| 底 `#000000` | 底 `#F4F4F2`（暖白）+ 极淡灰纹理 `#ECECE9` |
| 面板 `#0A0A0A` | 面板 `#FFFFFF` + 描边 `#E2E2DD` |
| 行 `#171717` | 行 `#F7F7F5` + 描边 `#DDDDD8` |
| 文字 `#FFFFFF` | 文字 `#0B0B0C` |
| 次文字 `#9A9A9A` | 次文字 `#6E6E73` |
| 黄 `#F2FF00` + 黑字 | **保持 `#F2FF00` + 黑字**（浅底上依然醒目，不改色） |
| 蓝气泡 `#3364E7` | 保持 `#3364E7`（可压至 `#2F5FE0` 提高白底对比） |
| 红 `#BF2207` | 保持 |

### 1.4 字体现实（不变）

- PingFang SC 最粗只有 **Semibold(600)**，无 Heavy/Black。
- 组合方案：字重 `.heavy` + 字号 +0.5~1pt + **描边加粗**（≥16pt 文字用 `strokeWidth` 负值叠加）；实测可达参考图约 85% 厚度。
- 所有文字经 `KnockTypography` 出口，未来换 MiSans Heavy 只改一处。

---

## 二、设计系统重构

### 2.1 新增文件（DesignSystem）

| 文件 | 内容 |
|---|---|
| `KnockTokens.swift` | 全部颜色（含深浅双主题映射）/间距/圆角/描边/尺寸 |
| `KnockTypography.swift` | 字体层级 + 描边加粗实现 + 字号档位接入 |
| `KnockChrome.swift` | `KnockHeader`（logo/返回 + 标题 + 右侧胶囊按钮）、`KnockTabBar`（一级分段栏形态）、`KnockCloseButton` |
| `KnockBars.swift` | `KnockSearchBar`、`KnockSegmentedBar`（黄胶囊+蓝圈）、`KnockFilterBar`、`KnockDropdown` |
| `KnockSurfaces.swift` | `KnockPanel`、`KnockListRow`（胶囊行）、`KnockDashedButton`、`KnockGroupTitle` |
| `KnockControls.swift` | `KnockPrimaryButtonStyle`（黄胶囊黑字）/`KnockSecondaryButtonStyle`（描边）/`KnockTextFieldStyle`/`KnockBadge`/`KnockChip`/`KnockDivider` |
| `KnockTimeline.swift` | `TimestampDivider`（- 一分钟前 -）、`TypingIndicator` |
| `KnockTexture.swift` | 背景斜切迷彩 + 面板棋盘格（`Canvas` 程序化绘制，缓存为 CGImage，关闭开关可关） |
| `KnockEmpty.swift` | `KnockEmptyPill`（E M P T Y）、`KnockNoDataRow`、空态组合 |
| `KnockDialog.swift` | 自绘确认弹窗（黑/白面板 + 黄主按钮） |
| `KnockGalleryView.swift` | 组件画廊（Debug 隐藏入口 `--knock-gallery`），截图验收用 |

### 2.2 改造文件（文件名保留，内部重写）

| 文件 | 改造 |
|---|---|
| `GlassTokens.swift` | 保留旧名做映射：`brandOrange`→黄、`accent`→黄、`bubbleIncoming`→白、`bubbleOutgoing`→蓝、`ambient*`→黑/暖白；新增黄/蓝/纹理 token |
| `GlassPanel.swift` | 移除 iOS 26 `.glassEffect` 分支 → 退化为 `KnockPanel` |
| `AmbientBackground.swift` | 渐变/光斑/暗角/立绘模糊 → 纯色底 + `KnockTexture`（深浅自适应） |
| `CardSurface.swift` | 半透明白卡 → 面板/胶囊行两种形态 |
| `SectionTitle` / `EmptyStateView` | 分组标题 / E M P T Y 空态 |
| `MessageBubble.swift` | 白/蓝胶囊 + 小尾巴 + 18pt heavy + 描边加粗；旁白/撤回/图片/流式光标/群成员名全保留 |
| `GlassInputBar.swift` | 黑底 + 描边胶囊输入框 + 黄/蓝发送键（发送键用黄：主 CTA）；键盘避让逻辑原样保留 |
| `NarrationBubble` | 漫画旁白：左 2pt 黄竖条 + 斜体灰白字 |
| `RoleAvatar.swift` | 增加 `KnockAvatarStyle`（黑底 + 蓝圈 + 首字）；新增 `knock:` 前缀解析；原 147 头像默认停用（资源保留） |

### 2.3 根容器

- `RootTabView`：隐藏系统 TabView tab bar → 自绘 `KnockTabBar`（**改用图 B 的胶囊分段栏形态**：整体一条胶囊 + 选中黄胶囊）；`.tint(#F2FF00)`。
- 全部 `NavigationStack`：隐藏系统导航栏 → `KnockHeader`。
- 外观：`preferredColorScheme(settings.appearance.colorScheme)` 保留；Info.plist 不锁深色。

---

## 三、页面改造清单（30 个视图，无 iPad）

| 批次 | 页面 | 主文件 | 量级 |
|---|---|---|---|
| P1 | 会话列表 | `ChatListView` (510) | M |
| P1 | 私聊 | `ChatView` (610) + `ChatViewModel` | **L** |
| P1 | 群聊 / 新建群 / 新建聊天 | `GroupChatView` (404)、`NewGroupView`、`NewChatView` | M |
| P2 | 角色列表/详情/编辑 | `RolesView` (422)、`RoleDetailView` (439)、`RoleEditView` (803) | **L** |
| P2 | 关系网 | `RelationsView` (456) | M |
| P3 | 记忆 / 知识库×3 / 日记×3 | `MemoryListView` (892)、`Knowledge*`、`Diary*` (479) | M/L |
| P3 | 动态 / 排行榜 / 印象 / 我的 / 功能 / 调试 | `MomentsView` (957)、`RankingView` (528)、`ImpressionsView`、`UserProfileView`、`FeaturesView`、`DebugLogsView` (387) | M/L |
| P4 | 设置根 + 外观 | `SettingsView`、`AppearanceSettingsView` | M |
| P4 | 聊天表现/提示词/供应商/通知/备份 | 455 / 528 / 669 / — / 988 行 | **L** |
| P5 | 路由/自检适配 | `RouteView`、`SelfTest` | S |

改版后设置页变化：**删「聊天背景」6 档**，保留主题三选 + 字号三档（与决策 6 一致）。

---

## 四、实施阶段

| 阶段 | 内容 | 验收 |
|---|---|---|
| **P0 环境 + 底座** | ① KMP 构建链修复（第八章 8.2 路线 B）② tokens/typography/texture/bar/surface 组件 ③ KnockGallery 画廊 ④ 4 张关键页 mockup（Pillow 渲染，先对齐再写代码）⑤ 字重样张 | 编译通过；mockup 确认；画廊真机截图 |
| **P1 聊天域** | 会话列表 + 私聊 + 群聊 + 顶栏/TabBar + 气泡/旁白/时间分隔/输入栏 | 聊天页 12 条 + 条形栏 8 条验收（7.1/7.2）通过；真机截图 |
| **P2 角色关系** | 角色×3、关系网、新聊天、头像占位生效 | 视觉统一 + 147 头像停用 + 自检通过 |
| **P3 内容域** | 记忆、知识、日记、动态、榜单、印象、我的、功能、调试 | 全暗黑/浅色双主题一致；空态/错误态统一 |
| **P4 设置域** | 设置根、外观（删背景档）、表现、提示词、供应商、通知、备份 | 表单/编辑器/选择器双主题；保存恢复自检通过 |
| **P5 收口** | 弹窗/菜单/Toast/加载/可访问性/双主题回归/截图归档/文档 | 全量截图评审 + 自检矩阵全绿 |

预计 **18–26 回合**。

### 7.1 聊天页验收（12 条，图 A）

1 纯黑底；2 顶栏 44pt 左对齐 heavy 标题；3 接收白胶囊+左上尾巴；4 发送蓝胶囊+右上尾巴；5 胶囊圆角 H10/V8；6 头像 36pt 无描边每消息独立；7 时间戳灰 `- … -`；8 间距 10/18/12；9 系统 TabBar 隐藏且无玻璃边缘；10 输入栏描边胶囊+键盘吸附；11 长按菜单黑底；12 Dynamic Type 不裁切。

### 7.2 条形栏验收（8 条，图 B/C/D）

1 一级分段栏为胶囊容器 + 黄选中胶囊 + 浅蓝外圈；2 选中文字黑色 heavy、未选中白色 heavy；3 搜索条胶囊 + 1.5pt 描边 + 右侧放大镜与分隔线；4 二级筛选栏同款小尺寸 + 下拉胶囊；5 列表行为胶囊行（描边 + 左图标）；6 空态为 E M P T Y 宽字距 pill；7 背景出现低对比斜切纹理，且面板内无毛玻璃；8 黄色全屏占比 ≤5%（截图直方图抽查）。

---

## 五、Liquid Glass 风险评估（不变）

- 系统玻璃来源与对策：隐藏 TabBar/导航栏、`scrollEdgeEffectHidden()`、`toolbarBackgroundVisibility(.hidden)`、`presentationBackground()`、自绘弹窗/开关/选择器。
- 决策树：P1 真机验收 → 不可控点 ≤2 → SwiftUI 走到底；3–4 → 混合 UIKit 容器；≥5 → 全 UIKit（不推荐）。
- 当前结论：**不弃用 SwiftUI**。

---

## 六、头像与资产（不变）

- `KnockAvatarStyle`：黑底 + 1.5pt 蓝圈 + 白色 heavy 首字（后续可换剪影）。
- `knock:<id>` 前缀 → `Resources/AvatarsKnock/knock_<id>.png`，用户丢素材即生效。
- 147 内置头像资源保留但默认不展示；上传头像保留并套蓝圈。

---

## 七、环境与验证（v1.1 重大更新）

### 8.1 已确认的环境事实

| 项 | 实测 |
|---|---|
| Xcode / SDK | Xcode 27.2，iOS 27.2 SDK |
| 真机（备用） | `Chen 的 iphone 17` 已连接（paired, physical, iOS18,3）；签名 identity 有效；`DEVELOPMENT_TEAM = 7PPJKDAPJZ` |
| 模拟器 | **iOS 27.2 runtime 下载中**（`xcodebuild -downloadPlatform iOS` 后台任务；用户已清理磁盘） |
| 网络 | Maven Central 直连 200；阿里云镜像可用；download.jetbrains.com 可达；GitHub 需代理，当前代理已关 |
| 磁盘 | 50GB 可用 |
| Gradle | `~/.gradle/wrapper/dists/gradle-8.10.2-all` 已缓存（离线可用） |
| JDK | 系统仅 JDK25；**JDK21 已从清华镜像下载中** → 装到 `~/jdks` |
| KMP 工程 | `shared/` 仅有 `build.gradle.kts` + 338 个 .kt；缺 settings/wrapper/version catalog；本机无 `~/.konan`、无 Android SDK |

### 8.2 构建路线 B 执行步骤（已启动）

1. **JDK 21**：清华 Adoptium 镜像 `OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.13.1_2.tar.gz` → `~/jdks`（后台下载中）。
2. **Gradle**：直接用缓存 `gradle-8.10.2` 的 `bin/gradle`（不下载）。
3. **重建构建脚手架**（放进 `shared/`，原文件另存 `build.gradle.kts.upstream` 备查）：
   - `settings.gradle.kts`：仓库指向阿里云 public/google + Maven Central；`include(":shared")`
   - `gradle/libs.versions.toml`：Kotlin **2.3.21**（已确认 Maven 存在）、SQLDelight **2.2.1**、Ktor **3.0.3**、kotlinx-datetime 0.8.0、russhwolf multiplatform-settings、koin、kotlinx-serialization（按 shared 源码实际 import 校准）
   - `build.gradle.kts`（iOS-only 版）：去掉 `android.library`/`androidTarget()`/Compose 插件与依赖（源码实测 0 处 `androidx.compose` import），保留 `iosArm64` + `iosSimulatorArm64` 静态 framework 与 SQLDelight 配置
4. **构建 framework**：`gradle :shared:linkDebugFrameworkIosArm64`（真机 debug）+ release 版（安装用）。首次会下载 Kotlin/Native 工具链（约 1–2GB，来自 download.jetbrains.com）。
5. **重写 `scripts/build-device.sh`**：现脚本路径按旧工作区（`RhodesLink/iosApp`、`ios-port/toolchain/env.sh`）写死 → 改为本仓库布局（`ios/KnockKnock.xcodeproj`、`shared/build/bin/...`），加入 `xcodebuild -allowProvisioningUpdates` + `xcrun devicectl device install/launch`。
6. **编译校验**：`xcodebuild -sdk iphoneos ... build`（真机 SDK）。
7. **模拟器截图（本轮验收主路径）**：
   ```bash
   xcrun simctl list runtimes                      # 确认 iOS 27.2 已装
   xcrun simctl boot "iPhone 17" && open -a Simulator
   xcrun simctl install booted /tmp/rhodes-dd/Build/Products/Debug-iphonesimulator/KnockKnock.app
   xcrun simctl launch booted com.rhodeslink.app
   xcrun simctl io booted screenshot screenshots/knock-chat.png
   ```
   备用：真机 `scripts/build-device.sh`（需重写路径后）→ `devicectl install`。

### 8.3 回归风险

15+ 个 `*Automation.swift` 自检钩子与 `SelfTest.swift` 不得破坏；仅 5 处 `accessibilityIdentifier`、少量 `contains("…")` 文本断言，改文案时同步核对；`shared/`、`Bridge/` 零改动。

---

## 九、风险登记（v1.1）

| # | 风险 | 概率 | 影响 | 对策 |
|---|---|---|---|---|
| 1 | KMP 依赖版本推导失败 | 中 | 构建受阻 | 以 shared 源码 import 为准反推版本；逐个失败逐个升/降级；保留 upstream 构建文件 |
| 2 | K/N 工具链下载慢/中断 | 中 | 首次构建慢 | download.jetbrains.com 实测可达；断点重试 |
| 3 | 模拟器 runtime 下载失败/过慢 | 低 | 无法截图 | 已启动 `xcodebuild -downloadPlatform iOS`；失败则回退真机截图 |
| 4 | Liquid Glass 残留 | 中 | 破坏观感 | 第五章对策 + P1 评审 |
| 5 | PingFang 无黑体字重 | 高 | 还原度打折 | 描边加粗补偿 + 预留字体替换 |
| 6 | 黄/蓝双强调色失衡 | 中 | 风格走样 | 面积配比纪律 + 截图直方图抽查（7.2-8） |
| 7 | 浅色模式纹理过重 | 中 | 观感脏 | 浅色纹理对比度 ≤3%，可全局关纹理 |
| 8 | 大面积改动回归 | 中 | 功能受损 | 分阶段编译 + 自检 + 只改视觉层 |

---

## 十、交付物

1. 本计划书 v1.1
2. P0：mockup 4 张 + 字重样张 + 组件画廊 + 可编译工程
3. P1–P5：分阶段代码（每阶段可编译、可真机截图）
4. `screenshots/knock-*.png` 全套双主题截图
5. `handoffs/knock-ui-*.md` 实现报告
6. README 界面风格章节更新

---

## 十一、状态与待确认

**已定（不再询问）**：范围、字体、事件卡、头像、液态玻璃策略、外观三项、iPad、构建路线、真机验收。

**新增待确认（2 条，可先按我的默认执行）**：
1. 聊天发送气泡：保持**蓝色** `#3364E7`（图 A），还是并入黄色体系（黄底黑字）？→ 默认**保持蓝色**（与图 A 一致，黄只做交互）。
2. 浅色模式下是否保留背景纹理？→ 默认**保留但对比度压到极低**（≤3%），并提供设置开关（默认开）。

---

*本文档规格均基于实测：图 A/B/C/D 像素采样 + 本机 Xcode 27.2 / iOS 27.2 SDK / 仓库 HEAD 30050c1 / 真机连接状态。*