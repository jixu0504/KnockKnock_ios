# 敲敲 · KnockKnock iOS

> **敲敲**：以《绝区零》为世界观的 AI 角色聊天 App（iOS 原生实现，同人二创）。
> Kotlin Multiplatform 共享领域层 + SwiftUI 原生壳，全站采用「敲敲」视觉语言——**电光黄黑胶囊 + 纯色底斜切迷彩纹理 + 等宽分段栏**，深/浅双主题。
> 内置 **62 位绝区零代理人**联系人、**21 个阵营群聊**与新艾利都世界观前置设定，开箱即是绳网另一端的日常。

<p align="center">
  <img src="screenshots/knock-final-chat-list-dark.png" width="240" alt="聊天列表（深色）" />
  <img src="screenshots/knock-final-chat-dark.png" width="240" alt="聊天详情（深色）" />
  <img src="screenshots/knock-final-features-dark.png" width="240" alt="功能页（深色）" />
  <img src="screenshots/knock-final-chat-list-light.png" width="240" alt="聊天列表（浅色）" />
</p>

---

## 📇 内置内容（绝区零）

> 交接文档：[`handoffs/zzz-content-swap.md`](handoffs/zzz-content-swap.md)。数据源：仓库内 [`zzz_characters/`](zzz_characters/README.md)（63 份角色资料：62 位代理人 + 主角铃，含头像、人设档案与世界观文档）。

- **联系人**：62 位绝区零代理人（`zzz001..zzz062`），人设提示词由人设档案「人设解读 + 联系人适配」两段生成；头像内置于 `ios/RhodesLink/Resources/AvatarsKnock/knock_NNN.png`（`knock:` 前缀解析）。
- **初始群聊**：21 个阵营群（`group_f01..group_f21`，成员 = 阵营全员），群规则取自世界观文档「势力版图」。
- **世界观**：对话前置设定为新艾利都 / 空洞 / 以太 / 绳网 / 丁尼 / 代理人（`PromptTemplates` VERSION 40）；旧题材种子与前置设定已清零。
- **再生成**：`tools/zzz/gen_presets.py` —— 修改 `zzz_characters/` 数据源后重跑，即可再生 `shared/src/commonMain/.../data/ZzzPresets.kt`。
- 旧安装不自动迁移：卸载重装即得纯绝区零数据。

## ✨ 功能概览

### 已实现（真机可用）

| 模块 | 内容 |
|---|---|
| **私聊** | 真实供应商流式对话（OpenAI 兼容 / DeepSeek / 自定义）、图片消息、停止生成、失败重试、长按操作（复制/引用/撤回/重试/重说/继续说/删除）、历史分页、会话置顶/已读/移除 |
| **聊天表现** | 线上 / 线下 / 导演三模式切换；台词与旁白**分别限定段数与单段字数**（私聊 + 群聊）；温度、深度思考、历史轮数、回复长度 |
| **提示词编辑器** | 38 项模板目录（14 主模板 + 24 行为模块）：查看 / 编辑 / 保存 / 恢复默认 / 禁用，变量占位符说明 |
| **人设系统** | 角色新建/编辑（姓名、头像内置 147 + 相册上传裁剪、人设、标签、初始关系、提示词槽位）、关系网编辑（24 类型、双向互补）、用户身份（昵称/性别/签名，注入提示词） |
| **群聊** | 联系人页群聊分区、群聊页（多发言人气泡）、成员/禁言/自动互动开关、分段撤回、深链 `rhodes://group/{id}` |
| **记忆与知识库** | 记忆管理（筛选/搜索/编辑/删除/隐私/锚点）、知识库（导入 TXT/MD、切块、索引、召回测试）、聊天前后自动注入与提取 |
| **内容生态** | 动态广场（发布/点赞/评论/删除，**发布后代理人自动评论+点赞**）、代理人日记（列表/未读/阅读，**按角色开关自动生成**）、聊天排行榜、消费统计、大家的印象 |
| **自动化** | A7 延迟任务（动态回复/主动消息/记忆维护）、本地通知（开关过滤/去重/静音/深链）、前台 tick + 启动补算 |
| **数据** | 完整备份恢复（zip）、聊天记录 Markdown 导入/导出、角色卡导入导出、调试日志 |

### 进行中 / 未实现

- 语音输入/ASR/TTS、麻将/文字游戏、聊天导入的完整 wire 格式 UI（已有 Markdown 导入）
- 部分设置项（省钱/标准/完整预设、自动上下文窗口）
- 关系网可视化图、用户等级/经验
- UI 术语清扫（个别界面仍残留旧题材措辞，如「干员」→「代理人」，已排期）
- 工程内部标识改名（详见「构建与运行」）

---

## 🎨 界面风格（敲敲）

> 设计依据：[`docs/敲敲UI改版计划书-v1.1.md`](docs/敲敲UI改版计划书-v1.1.md)（实测色板 / 条形栏规格 / Liquid Glass 决策树 / 验收标准）。
> 全站视觉统一为《绝区零》「敲敲」语言：**电光黄黑胶囊 + 纯色底斜切迷彩纹理 + 等宽分段栏**，深/浅双主题。

### 视觉纪律（面积配比）

| 色 | 目标占比 | 允许位置 |
|---|---|---|
| 黑 / 暖白（底 + 面板 + 行 + 描边） | ≈85% | 页面底、内容面板、胶囊行、描边层次 |
| 白 / 主文字 | ≈10% | 标题、正文、未选中图标与文字 |
| **电光黄 `#F2FF00`** | **≤5%** | 仅：选中胶囊、主按钮、角标、品牌 logo |
| **聊天蓝 `#3364E7`** | **≤3%** | 仅：发送气泡、链接、进度（聊天语义） |
| 灰阶 | ≈5% | 次文字 `#9A9A9A`、黄胶囊外圈高光 `#8CC4FF`、行底 |
| 危险红 `#BF2207` / `#FF3B30` | <1% | 关闭、删除、错误 |

> 两条铁律：**黄 = App 交互语言，蓝 = 聊天消息语言，禁止跨用**（不用黄画发送气泡、不用蓝画 Tab 选中）；**禁止毛玻璃 / 半透明白 / 渐变光斑**——页面底一律实色 + 程序化斜切迷彩纹理（浅色纹理对比度 ≤3%）。
> 黄色面积纪律由 `scripts/knock-yellow-audit.py` 在验收截图上做像素直方图抽查（阈值 5%，见下文「复现与审计」）。

### 设计 token（`ios/RhodesLink/DesignSystem/KnockTokens.swift`）

| Token | 深色 | 浅色 | 用途 |
|---|---|---|---|
| `canvas` 页面底 | `#000000` | `#F4F4F2` | 页面底 + 迷彩纹理 |
| `panel` 面板 | `#0A0A0A` | `#FFFFFF` | 内容面板 / 分组容器 |
| `row` 行底 | `#171717` | `#F7F7F5` | 胶囊行 / 输入框 |
| `stroke` 主描边 | `#2A2A2E` | `#E2E2DD` | 面板 / 行 / 胶囊容器（1.5pt） |
| `textPrimary` 主文字 | `#FFFFFF` | `#0B0B0C` | 标题 / 正文 |
| `textSecondary` 次文字 | `#9A9A9A` | `#6E6E73` | 副标题 / 说明 |
| `electric` 电光黄 | `#F2FF00` | `#F2FF00` | 选中胶囊 / 主 CTA / 品牌（≤5%） |
| `electricRing` 黄外圈 | `#8CC4FF` | `#8CC4FF` | 黄胶囊 1.5pt 高光外圈 |
| `chatBlue` 聊天蓝 | `#3364E7` | `#3364E7` | 发送气泡 / 链接（≤3%） |
| `danger` 危险 | `#BF2207` | `#BF2207` | 关闭 / 删除 / 错误底 |

- **字体**：PingFang SC 无 Heavy → `KnockTypography` 用 `.heavy` + 字号补偿 + 负 `strokeWidth` 描边加粗近似（换 MiSans Heavy 只改一处）。
- **尺寸**：顶栏 44pt · 一级分段栏/TabBar 44pt · 二级筛选栏 36pt · 搜索条 44pt · 胶囊行 64–72pt · 面板圆角 20pt · 描边 1.5pt · 按钮高 46pt · 页面边距 16pt。

### 组件库（`ios/RhodesLink/DesignSystem/`）

| 文件 | 组件 |
|---|---|
| `KnockTokens` / `KnockTypography` / `KnockTexture` | 颜色/尺寸 token、字体层级与描边补偿、程序化斜切迷彩 + 棋盘格纹理 |
| `KnockChrome` | `KnockHeader`（品牌/返回 + 居中标题 + 右侧胶囊）、`KnockTabBar`、`KnockCloseButton`、`KnockExitKey`（红色 X 结束键） |
| `KnockBars` | `KnockSearchBar`、`KnockSegmentedBar`（一级/二级分段栏）、`KnockFilterBar`、`KnockDropdown` |
| `KnockSurfaces` | `KnockPanel`、`KnockListRow`（胶囊行）、`KnockDashedButton`、`KnockGroupTitle` |
| `KnockControls` | 主/次/危险按钮、输入框、`KnockBadge`、`KnockChip`、`KnockDivider` |
| `KnockEmpty` / `KnockDialog` | `E M P T Y` 空态组合 / 自绘确认弹窗（无系统玻璃） |
| `KnockGalleryView` | 组件画廊（`--knock-gallery` 启动参数，截图验收用） |

### 形状与图标规则

- **品牌**：「敲敲」+ 黄色手绘 logo（`KnockBrandLogo`），一级页顶栏左上角。
- **底部 TabBar 与二级筛选栏同一语言**：胶囊容器（44pt / 36pt）+ **选中项 = 撑满等宽槽位的电光黄胶囊** + 1.5pt 浅蓝外圈 + 黑色 heavy 图标/文字；未选中 = 白色 heavy。
- **红色 X 结束键**：红色胶囊 `#C02207` + 内圈暗红 `#7A1404` 1.2pt 收边 + 黑色粗圆头 X（**无旋转、无贴纸尖角**）；浅色模式补 1pt 中性描边防糊底，命中区 ≥44pt。
- **空态**：面板 + `NoData` 行 + `E M P T Y` 宽字距 pill + 说明 + 可选主操作。
- **危险操作**：红色描边/底 + 自绘二次确认弹窗（`knockConfirmDialog`），不用系统 alert。

### 主题与字号

- 外观：**深色 / 浅色 / 跟随系统**（`ios_shell_appearance`，默认跟随系统；Info.plist 未锁深色）；
- 字号：**小 / 标准 / 大**（`ios_shell_font_size` → 全局 Dynamic Type `.small` / 跟随系统 / `.xxLarge`）；
- 纹理开关 `knock_texture_enabled`、字体描边补偿 `knock_stroke_compensation`（默认均开，设置页可关）。

### Liquid Glass 结论（计划书第五章决策树 → **A 级**）

- 全仓 **0 处** `.glassEffect` / `.ultraThinMaterial` / `.regularMaterial` / `Material` 玻璃 API（grep 验证）；
- 系统 TabBar 隐藏（`.toolbar(.hidden, for: .tabBar)`）、导航栏隐藏（自绘 `KnockHeader`）、iOS 26 `scrollEdgeEffectHidden(true)`、sheet 实色 `presentationBackground`、无 `.searchable`（搜索条自绘）；
- App 自有页面**不可控玻璃点 = 0** → 决策 **A：SwiftUI 走到底**，不引入 UIKit 混合容器。
- 不可控但无碍的系统表面：通知授权弹窗 / `ShareLink` / PhotosPicker / 键盘（系统标准模态，不在 App 自绘页面内）。

### 截图索引（`screenshots/knock-final-*.png`，深/浅双主题齐全）

| 页面 | 深色 | 浅色 |
|---|---|---|
| 聊天列表 | [dark](screenshots/knock-final-chat-list-dark.png) | [light](screenshots/knock-final-chat-list-light.png) |
| 聊天详情 | [dark](screenshots/knock-final-chat-dark.png) | [light](screenshots/knock-final-chat-light.png) |
| 群聊 | [dark](screenshots/knock-final-group-chat-dark.png) | [light](screenshots/knock-final-group-chat-light.png) |
| 角色列表 | [dark](screenshots/knock-final-roles-dark.png) | [light](screenshots/knock-final-roles-light.png) |
| 角色详情 | [dark](screenshots/knock-final-role-detail-dark.png) | [light](screenshots/knock-final-role-detail-light.png) |
| 关系网 | [dark](screenshots/knock-final-relations-dark.png) | [light](screenshots/knock-final-relations-light.png) |
| 功能页 | [dark](screenshots/knock-final-features-dark.png) | [light](screenshots/knock-final-features-light.png) |
| 动态广场 | [dark](screenshots/knock-final-moments-dark.png) | [light](screenshots/knock-final-moments-light.png) |
| 代理人日记 | [dark](screenshots/knock-final-diary-dark.png) | [light](screenshots/knock-final-diary-light.png) |
| 记忆管理 | [dark](screenshots/knock-final-memory-dark.png) | [light](screenshots/knock-final-memory-light.png) |
| 知识库 | [dark](screenshots/knock-final-knowledge-dark.png) | [light](screenshots/knock-final-knowledge-light.png) |
| 聊天排行榜 | [dark](screenshots/knock-final-ranking-dark.png) | [light](screenshots/knock-final-ranking-light.png) |
| 大家的印象 | [dark](screenshots/knock-final-impressions-dark.png) | [light](screenshots/knock-final-impressions-light.png) |
| 我的身份 | [dark](screenshots/knock-final-profile-dark.png) | [light](screenshots/knock-final-profile-light.png) |
| 设置根 | [dark](screenshots/knock-final-settings-dark.png) | [light](screenshots/knock-final-settings-light.png) |
| 外观设置 | [dark](screenshots/knock-final-appearance-dark.png) | [light](screenshots/knock-final-appearance-light.png) |
| 提示词编辑器 | [dark](screenshots/knock-final-prompt-dark.png) | [light](screenshots/knock-final-prompt-light.png) |
| 模型供应商 | [dark](screenshots/knock-final-providers-dark.png) | [light](screenshots/knock-final-providers-light.png) |
| 数据与备份 | [dark](screenshots/knock-final-backup-dark.png) | [light](screenshots/knock-final-backup-light.png) |
| 调试日志 | [dark](screenshots/knock-final-debug-logs-dark.png) | [light](screenshots/knock-final-debug-logs-light.png) |
| 组件画廊 | [dark](screenshots/knock-final-gallery-dark.png) | [light](screenshots/knock-final-gallery-light.png) |
| 红色 X 专项 | [dark](screenshots/knock-final-red-x-dark.png) | [light](screenshots/knock-final-red-x-light.png) |
| 条形栏专项 | [dark](screenshots/knock-final-bars-dark.png) | [light](screenshots/knock-final-bars-light.png) |

> 可访问性证据：`screenshots/knock-a11y-dynamic-type-*`（字号「大」不裁切）、`knock-a11y-reduce-motion-chat-list-dark.png` / `knock-a11y-reduce-motion-settings-dark.png`（系统「减弱动态效果」状态 chip 实测「开」）、`knock-a11y-reduce-transparency-equivalent-chat-list-dark.png`（「降低透明度」同一渲染分支：纹理关闭 → 纯色底）。
> 历史分阶段截图（P0–P4 过程件、旧 T 编号截图、被替换的旧版）归档在 `screenshots/archive/`；`screenshots/_env-check.png` 为环境基线；`knock-brand-home.png` 为品牌名「敲敲」证据。

### 复现截图与黄色审计

```bash
# 单页截图（内置串行锁；自动构建/安装/切换深色或浅色；--wait 5 等首帧稳定）
scripts/ui-screenshot.sh /tmp/shot.png --dark --wait 5 --launch-arg --tab --launch-arg chat

# 黄色面积占比审计（阈值 5%；依赖 Pillow：python3 -m pip install pillow）
python3 scripts/knock-yellow-audit.py "screenshots/knock-final-*.png" --md-out /tmp/yellow.md
```

> 终版实测：46 张双主题截图**全部 ≤5%**（最高为组件画廊 `gallery` 3.96%，用户页最高为 `ranking` 3.00%）；逐张表格见 [`handoffs/knock-ui-final.md`](handoffs/knock-ui-final.md)。

---

## 📁 仓库结构

```
ios/               iOS 工程源码（SwiftUI 壳：App / Bridge / DesignSystem / Features）
shared/            Kotlin Multiplatform 共享领域层（域模型、聊天引擎、提示词、记忆、备份…）
zzz_characters/    绝区零角色资料库（63 角色头像/资料/人设档案/世界观文档，内置内容的数据源）
android-oracle/    移动端行为对照基线（Android 原版源码，供金样与 parity 对照）
specs/             规格卡（21+ 张：契约、边界、验收标准）
handoffs/          实现报告（每个任务的实现清单、验证证据、限制）
golden/            金样数据（提示词/记忆/备份/聊天导入的基线快照）
docs/              开发计划、UI 改版计划书、实机签名续签指南、决策记录等
screenshots/       功能与验收截图（历史过程件在 archive/）
scripts/           构建 / 截图 / 审计脚本（build-device.sh、ui-screenshot.sh、knock-yellow-audit.py）
tools/             内容生成工具（tools/zzz/gen_presets.py）
artifacts/         发行产物（最新未签名 IPA）
```

技术栈：Kotlin 2.3.21 · Kotlin Multiplatform · SQLDelight 2.2.1 · Ktor 3.0.3 · kotlinx.serialization / datetime · SwiftUI · 最低 iOS 18.0

---

## 🛠 构建与运行

> 命名说明：App 显示名为「**敲敲**」；工程内部标识（`RhodesLink.xcodeproj`、scheme `RhodesLink`、bundle id `com.rhodeslink.app`、深链 `rhodes://`）沿袭自项目前身，工程级改名已列入后续版本。当前请照以下命令原名执行。

### 前置条件

- macOS + Xcode 26+（当前在 Xcode 27.2 验证）
- JDK 21（Gradle 8.10.2 需要）
- Android SDK（编译 `shared` 的 android target 用；仅跑 iOS 可跳过部分）
- 首次构建会由 Gradle 自动下载 Kotlin/Native 工具链（约 1–2GB）

### 1. 编译 KMP framework

```bash
cd shared
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64    # 模拟器（约 1–2 分钟）
./gradlew :shared:linkReleaseFrameworkIosArm64           # 真机（约 4–6 分钟）
```

> 仓库不包含 `toolchain/`（自包含 JDK/SDK/K-N 缓存，体积 5GB+），请使用系统 JDK 21 + Android SDK，或参考 `docs/实机测试准备手册.md`。

### 2. 运行 App（模拟器）

```bash
cd ios
xcodebuild -project RhodesLink.xcodeproj -scheme RhodesLink \
  -configuration Debug \
  -destination 'platform=iOS Simulator,id=<YOUR_SIMULATOR_UDID>' \
  -derivedDataPath /tmp/build build
xcrun simctl install <UDID> /tmp/build/Build/Products/Debug-iphonesimulator/RhodesLink.app
xcrun simctl launch <UDID> com.rhodeslink.app
```

### 3. 真机安装

```bash
DEVELOPMENT_TEAM=<你的 Team ID> ./scripts/build-device.sh
# 或
xcodebuild -project ios/RhodesLink.xcodeproj -scheme RhodesLink -configuration Debug \
  -destination 'generic/platform=iOS' -allowProvisioningUpdates \
  DEVELOPMENT_TEAM=<TEAM> CODE_SIGN_STYLE=Automatic build
xcrun devicectl device install app --device <UDID> <RhodesLink.app 路径>
```

首次启动需在 iPhone「设置 → 通用 → VPN与设备管理」信任开发者证书。

### 4. 配置模型供应商

App 内 **设置 → 模型供应商**：填写 OpenAI 兼容接口的 base URL / 模型名 / API Key（Keychain 保存）；未配置时进入**演示模式**（本地确定性回复）。
识图模型为可选项（图片消息识图用），支持独立配置。

---

## 📦 安装包（IPA）

| 文件 | 说明 |
|---|---|
| [`artifacts/knock-unsigned-20261126.ipa`](artifacts/knock-unsigned-20261126.ipa) | 最新**未签名**安装包（2026-11-26 构建，与当前源码一致） |

- 未签名包需用自己的开发者证书**重签**后安装到真机，步骤见 [`docs/实机签名续签指南.md`](docs/实机签名续签指南.md)。
- 也可以按上文「构建与运行」从源码直接构建（推荐，签名一步到位）。

---

## 🧪 测试与自检

```bash
cd shared && ./gradlew :shared:testDebugUnitTest        # JVM 单测 500+ 例
cd shared && ./gradlew :shared:iosSimulatorArm64Test    # iOS target 单测 560+ 例
```

App 内置可自动化自检（启动参数触发，日志写入 App 沙盒 `Documents/` 或 `Library/Application Support/`）：

| 参数 | 覆盖 |
|---|---|
| `--selftest` | 壳层冒烟（会话/角色/Tab/深链） |
| `--prompt-wiring-selftest` | 真实提示词装配 + 记忆/知识库注入 + 聊天后记忆提取 |
| `--chat-ops-selftest` | 撤回/重试/重说/继续说/删除/图片消息 |
| `--bubble-split-selftest` | 旁白/台词分气泡渲染 |
| `--mode-switch-selftest` | 三模式切换与提示词分段 |
| `--settings-selftest` | 聊天表现/提示词编辑器/识图配置读写与持久化 |
| `--memory-mgmt-selftest` / `--knowledge-selftest` | 记忆管理 / 知识库索引召回 |
| `--moment-reply-selftest` / `--moments-selftest` | 动态 AI 自动回复 / 动态 CRUD |
| `--diary-selftest` / `--diary-auto-selftest` | 日记阅读 / 按角色自动生成 |
| `--markdown-import-selftest` | Markdown 聊天记录导入 |
| `--a7-glue-selftest` | 延迟任务/通知事件源/群自动互动 |
| `--group-selftest` / `--unread-selftest` / `--persona-selftest` / `--relations-selftest` 等 | 群聊 / 未读 / 人设 / 关系网 |

> 设计原则：**金样门禁**——所有与移动端基线对齐的行为，均由基线生成的 golden 样本 + 单测锁死，禁止“自证”。

---

## 📐 架构要点

- **共享领域层**：聊天状态机（租约/超时/恢复/变更）、提示词四层装配（含记忆/知识库/关系/模式模板）、记忆 V2（三层 + 锚点 + 向量召回 + 隐私）、群聊引擎、A7 延迟任务层、备份恢复（zip/SHA-256/角色卡/wire 全类目）、聊天导入（wire + Markdown）
- **iOS 桥接**：`iosMain` 中 `IosAppGraph` 统一装配（ChatEngine/记忆/知识库/A7/备份/关系/日记桥），Swift 通过回调式 DTO 调用，主线程回投
- **SwiftUI 壳**：4 Tab（聊天/联系人/功能/设置）+ NavigationStack 路由 + 深链 + **「敲敲」自绘设计系统**（纯黑底 + 斜切迷彩纹理 + 胶囊层级 + 电光黄交互语言，深/浅双主题；见「界面风格（敲敲）」）+ 聊天气泡分段渲染
- **数据**：SQLDelight（SQLite）+ NSUserDefaults/Keychain（设置/密钥）+ Application Support/media（媒体 ID 化落盘）

---

## ⚠️ 已知限制

- 加急/测试版本：**未做性能 Gate 全量验证**（2000 条消息 / 1 万条记忆压测待补）
- 部分 AI 生成内容（日记/动态）在未配置供应商时使用本地演示内容
- 群聊图片/礼物消息、语音消息未实现
- 个别界面文案仍残留旧题材术语（「干员」等），UI 术语清扫已排期
- 工程内部标识仍沿用前身旧名（`RhodesLink.xcodeproj` / `com.rhodeslink.app` / `rhodes://`），改名列入后续版本
- 极早期项目，数据库结构可能随版本调整（升级前请备份）

---

## 📜 许可与致谢

- **绝区零素材**：本项目为《绝区零》**同人二创**，角色头像、档案文案与世界观设定等素材版权归米哈游（miHoYo / HoYoverse）所有，仅供个人学习与自用，不得用于商业用途。
- **项目演化**：敲敲 iOS 由 RhodesLink iOS 移植版演化而来（KMP + SwiftUI 架构与共享领域层承自该项目）。本仓库沿用 [`LICENSE`](LICENSE) 中原始项目的自定义许可（非商业自由使用、商业需书面授权），并按其要求保留原作者版权与来源署名。
- **行为基线**：`android-oracle/` 与 `golden/` 为移动端原版对照基线，仅用于功能对齐验证。
- **第三方组件**：Kotlin/Kotlin Multiplatform、SQLDelight、Ktor、kotlinx.serialization、kotlinx-datetime 均遵循各自开源许可。

---

## 🗓 更新记录

| 日期 | 内容 |
|---|---|
| 2026-11-26 | **仓库独立焕新**：项目定名「敲敲（KnockKnock）」，全面转向绝区零题材——内置 62 位代理人 + 21 个阵营群 + 新艾利都世界观前置设定；`zzz_characters/` 资料库入仓；随附最新未签名 IPA 与重签指南；以全新精简历史开仓 |
| 2026-11-25 | **「敲敲」UI 全面改版（P1–P5）**：全站统一电光黄/黑胶囊视觉 + 深浅双主题 + 自绘 TabBar/分段栏/空态/弹窗/红色 X 结束键；Liquid Glass 复核结论 A（SwiftUI 走到底）；46 张双主题终版截图 + 黄色面积审计 + 可访问性证据（详见 [`handoffs/knock-ui-final.md`](handoffs/knock-ui-final.md)） |
| 2026-11-23 | **加急测试版**：私聊（真实提示词/记忆注入、气泡分段、模式切换、输出数量设置）、人设编辑、提示词编辑器、动态（含自动回复）、日记（含自动生成开关）、排行榜/印象、备份与 Markdown 导入、A7 自动化 |
| 2026-11-22 | 基础骨架：KMP 域层抽取、iOS 数据底座、SwiftUI 真壳、聊天/角色/设置基础页 |
