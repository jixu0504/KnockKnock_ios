# 敲敲 UI 改版 · P5 收口与最终验收报告

- **阶段**：P5 收口（WBS T7 / 共享任务板 `task-8`）；上游 P1–P4（聊天 / 设计系统 / 角色关系 / 内容 / 设置）已全部完成并各自编译、自检通过。
- **唯一设计依据**：[`docs/敲敲UI改版计划书-v1.1.md`](../docs/敲敲UI改版计划书-v1.1.md)（第 1.2 色板与面积纪律、第 5 章 Liquid Glass 决策树、7.1/7.2 验收条目）。
- **最终源码冻结点**：2026-11-25 23:39（含 Lead 收尾的用户反馈修订：品牌名「敲敲」、红色 X 结束键胶囊化、分段栏黄胶囊等分槽位）。
- **构建**：`xcodebuild -project ios/RhodesLink.xcodeproj -scheme RhodesLink -configuration Debug -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' -derivedDataPath /tmp/rhodes-dd-polish build` → **BUILD SUCCEEDED**（0 error）。
- **截图环境**：iOS 27.2 模拟器 `iPhone 17 Content`（`51C310B6-…`，数据最全：会话 5 / 动态 4 / 日记 5 / 知识库 1 / 关系 2 / 记忆 1；截图前已清掉回归自检写入的临时会话与消息，聊天页为种子对话 + 1 条「我」的发送气泡）；深/浅两套均由 `simctl` 切换 + App 外观「跟随系统」拍摄。

## 0. 结论摘要

| 验收项 | 结果 |
|---|---|
| 全仓编译 | ✅ BUILD SUCCEEDED（0 error） |
| TabBar 遮挡二级页 | ✅ 已修（App 层一处解决，isPresented / destination 型 push 全覆盖），4 类二级页截图验证 |
| 双主题终版截图 | ✅ 23 页 × 深/浅 = **46 张** `screenshots/knock-final-*.png`，无缺失 |
| 黄色面积占比 | ✅ **46/46 全部 ≤5%**（用户页最高 `ranking` 3.00%；最高为组件画廊 3.96%）；1 页超标已整改（backup 6.24% → 1.97%） |
| 可访问性 | ✅ Dynamic Type「大」3 页不裁切；减弱动态 / 降低透明度证据见 §6（含模拟器限制说明） |
| Liquid Glass 复核 | ✅ 决策树结论 **A（SwiftUI 走到底）**，全仓玻璃 API 残留 **0** |
| 回归自检矩阵 | ✅ **10 次运行全绿**（selftest / bubble-split / mode / roles / persona / settings×3 / memory-mgmt / group） |
| 文档 | ✅ README「界面风格（敲敲）」章节 + 本报告 + 截图归档整理 |

---

## 1. 本阶段（P5）改动清单

### 1.1 修复：KnockTabBar 遮挡二级页（content 域报告）

**现象**：`navigationDestination(isPresented:)` 推入的二级页（设置 → 我的身份、角色详情 → 关系网、日记 → 自动管理、提示词 → 示例详情）以及 `NavigationLink { destination }` 推入的页面（知识库）底部被自绘 TabBar 盖住。

**根因**：TabBar 挂在 `RootTabView` 的 `TabView.safeAreaInset`，可见性由 `router.<tab>Path.isEmpty` 判断；但上述两类 push **不写 AppRouter.path**，判断恒为「根页」→ TabBar 常驻。

**修法**（`ios/RhodesLink/RhodesLinkApp.swift:155` 起，纯 App 层，未改 Features 导航代码）：新增 `TabRootStack`，把 `KnockTabBar` 下移到**各 Tab 的 NavigationStack 根视图**的 `safeAreaInset(edge: .bottom)`。`safeAreaInset` 属于根视图自身布局，push 后根页离屏 → **任何形式的二级页 push 都自动收起 TabBar**，不再依赖「path 是否为空」这种脆弱自证；同时天然避免「TabBar 盖 sheet」（sheet 覆盖整棵 TabView）。
删除 `showsKnockTabBar` 与 4 个重复的 `*TabRoot` 视图，合并为一个泛型 `TabRootStack`。

**验证截图**（均为 isPresented / destination 路径）：`knock-final-profile-{dark,light}.png`（设置→我的身份）、`knock-final-relations-{dark,light}.png`（角色详情→关系网）、`knock-final-knowledge-{dark,light}.png`（NavigationLink→知识库）；根页 TabBar 正常：`knock-final-chat-list/roles/features/settings-*.png`。

### 1.2 补齐：知识库路由与深链（收口/回归可用性）

**现象**：`KnowledgeListView` 仅由 `FeaturesView` 的 `NavigationLink { }` 直达，不在 `Route` 表内、无深链 → 截图/回归无法稳定复现该页（此前 P3 只能借 `--knowledge-selftest` 的全屏自检浮层拍摄）。

**修法**：`Route.knowledgeList`（`App/AppRouter.swift:77`）+ `RouteView` 分支（`Features/Common/RouteView.swift:33`）+ 深链 `rhodes://features/knowledge`（`AppRouter.handle`）。
（这是 P5「路由/自检适配」范围内的必要小改动，非视觉改动。）

### 1.3 统一：最后一处「玻璃时代」页面（占位页）

`PlaceholderView`（设置/可访问性深链与所有未知路由的兜底页）此前仍使用 `GlassTokens.brandOrange` + `cardSurface` + 系统 `navigationTitle`。P5 改为 `KnockHeader`（返回胶囊 + 标题）+ `AmbientBackground`（纯色 + 斜切迷彩）+ `KnockEmptyState`（NoData 行 + `EMPTY` pill）+ `knockHideSystemChrome()`。
验证：全仓 `cardSurface(` / `EmptyStateView(` / `GlassTokens.brandOrange` 调用点 = 0（仅剩兼容定义与注释）。

### 1.4 整改：备份页黄色面积超 5%

首轮审计 `knock-final-backup-dark/light` = **6.24% / 6.01%**（超出计划书 1.2「黄 ≤5%」）：该页同时存在 3 个全宽黄底主按钮（选择 Markdown 文件 / 导出完整备份 / 导入备份中两个黄）。
整改：本页主 CTA 只保留「导出完整备份」，把「选择 Markdown 文件」由 `KnockPrimaryButtonStyle`（黄底黑字）降为 `KnockSecondaryButtonStyle`（描边）——`Features/Settings/BackupSettingsView.swift:822` 处已注释原因。
复测：**1.97% / 1.92%** ✅。

### 1.5 设计与文档收口（Lead 用户反馈修订的落地记录）

| 项 | 终版规范 |
|---|---|
| 品牌名 | **「敲敲」**（去掉旧「敲敲门」；`KnockChrome` 品牌默认文案 / 画廊标题 / 设置页副标题已同步） |
| 红色 X 结束键 | **红色胶囊** `#C02207` + 内圈暗红 `#7A1404` 1.2pt 收边 + 黑色粗圆头 X；无旋转、无贴纸尖角；浅色模式补 1pt 中性描边，命中区 ≥44pt（`KnockChrome.KnockExitKey`） |
| 分段栏 | 一级 TabBar 与二级筛选栏**完全一致**：选中黄胶囊**撑满等宽槽位**（`KnockSegmentedBar.segmentContent` 去 fixedSize），1.5pt 浅蓝外圈 + 黑色 heavy 图标/文字 |
| 截图/文档 | 品牌名与红 X 规范写入 README「界面风格（敲敲）」；截图 `knock-brand-home.png`（品牌）、`knock-final-red-x-*`、`knock-final-bars-*`（专项） |
| 截图归档 | 84 张分阶段/被替换截图移入 `screenshots/archive/`（含 P0–P4 过程件与旧 T 编号截图），根目录只保留终版；`_env-check.png` 保留为环境基线 |
| 审计脚本 | 新增 `scripts/knock-yellow-audit.py`（HSV 阈值抽样、Markdown 输出、退出码可作 CI 门禁） |

---

## 2. 全量改动清单（P1–P5，按域）

> 说明：P1–P4 的视觉改造由各域完成；P5 仅做上表收口修复 + 截图/文档。**`shared/` 与 `Bridge/` 零改动。**

| 域 | 主要文件 | 内容 |
|---|---|---|
| 设计系统（P0/P5） | `DesignSystem/KnockTokens` `KnockTypography` `KnockTexture` `KnockChrome` `KnockBars` `KnockSurfaces` `KnockControls` `KnockEmpty` `KnockDialog` `KnockGalleryView`（新增 11 件）；`GlassTokens` `GlassPanel` `CardSurface` `AmbientBackground` `MessageBubble` `GlassInputBar` `RoleAvatar`（重写） | 颜色/尺寸 token、字体层级 + 描边补偿、程序化迷彩/棋盘格纹理、顶栏/TabBar/分段栏/胶囊行/按钮/空态/弹窗/画廊；旧玻璃组件退化为实色映射 |
| 根容器 / 路由（P5） | `RhodesLinkApp.swift`、`App/AppRouter.swift`、`Features/Common/RouteView.swift` | TabBar 下移到 Tab 根（§1.1）、知识库路由 + 深链（§1.2）、占位页统一（§1.3） |
| 聊天域（P1） | `Features/Chat/ChatListView` `ChatView/ChatViewModel`、`GroupChat/GroupChatView` `NewGroupView`、`Roles/NewChatView` | 会话列表/私聊/群聊/新建聊天：黑底 + 自绘顶栏 + 白/蓝胶囊气泡 + 旁白/时间分隔 + 输入栏；三模式菜单自绘 |
| 角色关系（P2） | `Features/Roles/RolesView` `RoleDetailView` `RoleEditView`、`Features/Relations/RelationsView`、`Roles/RolesKnockSupport`（新增） | 角色列表/详情/人设编辑（含 147 内置头像默认停用、黑底蓝圈首字占位）、关系网/添加关系 |
| 内容域（P3） | `Features/Memory` `Knowledge`×3 `Diary`×3 `Moments` `Ranking` `Impressions` `Profile` `Features/FeaturesView` `Debug/DebugLogsView`、`Features/Features/KnockContentUI`（新增） | 记忆/知识库/日记/动态/榜单/印象/我的/功能/调试全量敲敲化；空态统一为 `EMPTY` pill |
| 设置域（P4） | `Features/Settings/`（设置根、外观、提示词编辑器、供应商、备份、通知、聊天表现、调试日志）+ `SettingsKnockStyle`（新增） | 表单/滑块/开关/诊断行统一胶囊语言；删除 6 档聊天背景；字号三档 + 主题三选 |
| 截图/文档/脚本（P5） | `README.md`、`handoffs/knock-ui-final.md`、`screenshots/**`、`scripts/knock-yellow-audit.py` | 见 §1.4/§1.5 |

---

## 3. 逐域终版截图索引

根目录 `screenshots/` 只保留终版与可访问性证据；分阶段过程件在 `screenshots/archive/`。

### 3.1 双主题终版（`knock-final-*`，23 页 × 深/浅）

| 域 | 页面 | 深色 | 浅色 |
|---|---|---|---|
| 聊天 | 聊天列表 | `knock-final-chat-list-dark.png` | `knock-final-chat-list-light.png` |
| 聊天 | 聊天详情 | `knock-final-chat-dark.png` | `knock-final-chat-light.png` |
| 聊天 | 群聊 | `knock-final-group-chat-dark.png` | `knock-final-group-chat-light.png` |
| 角色关系 | 角色列表 | `knock-final-roles-dark.png` | `knock-final-roles-light.png` |
| 角色关系 | 角色详情 | `knock-final-role-detail-dark.png` | `knock-final-role-detail-light.png` |
| 角色关系 | 关系网 | `knock-final-relations-dark.png` | `knock-final-relations-light.png` |
| 内容 | 功能页 | `knock-final-features-dark.png` | `knock-final-features-light.png` |
| 内容 | 动态广场 | `knock-final-moments-dark.png` | `knock-final-moments-light.png` |
| 内容 | 干员日记 | `knock-final-diary-dark.png` | `knock-final-diary-light.png` |
| 内容 | 记忆管理 | `knock-final-memory-dark.png` | `knock-final-memory-light.png` |
| 内容 | 知识库 | `knock-final-knowledge-dark.png` | `knock-final-knowledge-light.png` |
| 内容 | 聊天排行榜 | `knock-final-ranking-dark.png` | `knock-final-ranking-light.png` |
| 内容 | 大家的印象 | `knock-final-impressions-dark.png` | `knock-final-impressions-light.png` |
| 内容 | 我的身份 | `knock-final-profile-dark.png` | `knock-final-profile-light.png` |
| 设置 | 设置根 | `knock-final-settings-dark.png` | `knock-final-settings-light.png` |
| 设置 | 外观设置 | `knock-final-appearance-dark.png` | `knock-final-appearance-light.png` |
| 设置 | 提示词编辑器 | `knock-final-prompt-dark.png` | `knock-final-prompt-light.png` |
| 设置 | 模型供应商 | `knock-final-providers-dark.png` | `knock-final-providers-light.png` |
| 设置 | 数据与备份 | `knock-final-backup-dark.png` | `knock-final-backup-light.png` |
| 设置 | 调试日志 | `knock-final-debug-logs-dark.png` | `knock-final-debug-logs-light.png` |
| DS 专项 | 组件画廊 | `knock-final-gallery-dark.png` | `knock-final-gallery-light.png` |
| DS 专项 | 红色 X 结束键 | `knock-final-red-x-dark.png` | `knock-final-red-x-light.png` |
| DS 专项 | 条形栏验收 | `knock-final-bars-dark.png` | `knock-final-bars-light.png` |

### 3.2 补充证据（根目录）

| 文件 | 用途 |
|---|---|
| `knock-a11y-dynamic-type-chat-list-{dark,light}.png` / `…-settings-dark.png` / `…-role-detail-dark.png` | 字号「大」（Dynamic Type .xxLarge）不裁切 |
| `knock-a11y-reduce-motion-chat-list-dark.png` / `knock-a11y-reduce-motion-settings-dark.png` | 系统「减弱动态效果」开启（settings 页状态 chip 实测「减弱动态 开」） |
| `knock-a11y-reduce-transparency-equivalent-chat-list-dark.png` | 「降低透明度」同一渲染分支（纹理关闭 → 纯色底）；模拟器限制见 §6.3 |
| `knock-brand-home.png` | 品牌名「敲敲」实证（Lead 修订） |
| `_env-check.png` | 环境基线（首帧探针），保留 |

### 3.3 分阶段过程件（`screenshots/archive/`，84 张）

- P0：`knock-p0-gallery / bars-fix / exit-key`（旧版规格，终版由 `knock-final-gallery/bars/red-x` 取代）。
- P1–P4 单域证据：`knock-p1-*`…`knock-p4-*`（含二级状态：模式菜单、群成员、新建聊天、头像选择器、人设编辑、关系添加、知识库新建、日记详情/自动管理、记忆详情、动态发布/评论、提示词详情、备份 Markdown、通知、聊天表现等）。
- 修复后重拍的 `knock-p3-profile-{dark,light}.png`（TabBar 遮挡修复证据，现无底部遮挡）。
- 更早期的 T 编号截图（`t42`…`t61`）已被敲敲终版取代，一并归档。

---

## 4. 黄色面积占比审计

**脚本**：`scripts/knock-yellow-audit.py`（新增）
**口径**：截图转 HSV，命中「电光黄附近」的像素（hue 50°–75°、sat ≥0.55、val ≥0.20）计为黄色面积，占比 = 黄像素 / 全图像素；抗锯齿混色落在阈值内即计数（口径偏保守，宁高勿低）。阈值 5%（计划书 1.2 / 7.2-8）。

**复现**：

```bash
python3 scripts/knock-yellow-audit.py "screenshots/knock-final-*.png" --md-out /tmp/yellow.md
# 依赖 Pillow（python3 -m pip install pillow）；退出码 1 = 存在超标页，可直接做 CI 门禁
```

**整改记录**：首轮 `backup-dark 6.24% / backup-light 6.01%` 超标 → 按 §1.4 降级次按钮 → 复测 `1.97% / 1.92%` ✅；其余首轮即达标。

**终版全量结果（46 张，全部达标）**：

| 截图 | 黄色面积占比 | 判定（阈值 5%） |
|---|---:|---|
| `knock-final-gallery-dark.png` | 3.96% | ✅ 达标 |
| `knock-final-gallery-light.png` | 3.87% | ✅ 达标 |
| `knock-final-ranking-dark.png` | 3.00% | ✅ 达标 |
| `knock-final-ranking-light.png` | 3.00% | ✅ 达标 |
| `knock-final-bars-dark.png` | 1.98% | ✅ 达标 |
| `knock-final-bars-light.png` | 1.98% | ✅ 达标 |
| `knock-final-backup-dark.png` | 1.97% | ✅ 达标 |
| `knock-final-backup-light.png` | 1.92% | ✅ 达标 |
| `knock-final-roles-dark.png` | 1.59% | ✅ 达标 |
| `knock-final-roles-light.png` | 1.59% | ✅ 达标 |
| `knock-final-appearance-light.png` | 1.53% | ✅ 达标 |
| `knock-final-appearance-dark.png` | 1.53% | ✅ 达标 |
| `knock-final-settings-dark.png` | 1.37% | ✅ 达标 |
| `knock-final-settings-light.png` | 1.36% | ✅ 达标 |
| `knock-final-moments-dark.png` | 1.29% | ✅ 达标 |
| `knock-final-moments-light.png` | 1.27% | ✅ 达标 |
| `knock-final-diary-dark.png` | 1.08% | ✅ 达标 |
| `knock-final-diary-light.png` | 1.08% | ✅ 达标 |
| `knock-final-features-dark.png` | 0.85% | ✅ 达标 |
| `knock-final-chat-list-dark.png` | 0.84% | ✅ 达标 |
| `knock-final-features-light.png` | 0.84% | ✅ 达标 |
| `knock-final-chat-list-light.png` | 0.83% | ✅ 达标 |
| `knock-final-profile-dark.png` | 0.59% | ✅ 达标 |
| `knock-final-memory-dark.png` | 0.48% | ✅ 达标 |
| `knock-final-memory-light.png` | 0.48% | ✅ 达标 |
| `knock-final-debug-logs-dark.png` | 0.36% | ✅ 达标 |
| `knock-final-debug-logs-light.png` | 0.36% | ✅ 达标 |
| `knock-final-providers-dark.png` | 0.24% | ✅ 达标 |
| `knock-final-providers-light.png` | 0.21% | ✅ 达标 |
| `knock-final-relations-dark.png` | 0.18% | ✅ 达标 |
| `knock-final-relations-light.png` | 0.18% | ✅ 达标 |
| `knock-final-knowledge-dark.png` | 0.18% | ✅ 达标 |
| `knock-final-knowledge-light.png` | 0.18% | ✅ 达标 |
| `knock-final-red-x-dark.png` | 0.16% | ✅ 达标 |
| `knock-final-red-x-light.png` | 0.15% | ✅ 达标 |
| `knock-final-prompt-dark.png` | 0.05% | ✅ 达标 |
| `knock-final-prompt-light.png` | 0.03% | ✅ 达标 |
| `knock-final-chat-dark.png` | 0.00% | ✅ 达标 |
| `knock-final-chat-light.png` | 0.00% | ✅ 达标 |
| `knock-final-group-chat-dark.png` | 0.00% | ✅ 达标 |
| `knock-final-group-chat-light.png` | 0.00% | ✅ 达标 |
| `knock-final-impressions-dark.png` | 0.00% | ✅ 达标 |
| `knock-final-impressions-light.png` | 0.00% | ✅ 达标 |
| `knock-final-profile-light.png` | 0.00% | ✅ 达标 |
| `knock-final-role-detail-dark.png` | 0.00% | ✅ 达标 |
| `knock-final-role-detail-light.png` | 0.00% | ✅ 达标 |

> 黄色出现位置复核：全部落在「选中胶囊（一级 TabBar / 二级筛选栏 / 外观三选）/ 主 CTA（导出备份、添加关系、发布等）/ 未读角标与品牌 logo」之内，未发现整片铺黄或黄色用于聊天语义。组件画廊 3.96% 为色板/控件展示（非用户路径页），属设计系统专项目标而非产品页。

---

## 5. Liquid Glass 复核（计划书第五章决策树）

### 5.1 代码取证（grep 全仓 `ios/RhodesLink`）

| 检查项 | 结果 |
|---|---|
| `.glassEffect` / `.ultraThinMaterial` / `.regularMaterial` / `.thinMaterial` / `.thickMaterial` / `glassBackgroundEffect` / `Material(` | **0 处实际使用**（`GlassPanel.swift:3` 仅一处注释说明「原 iOS 26 玻璃分支已删除」） |
| `.searchable` | 0（搜索条为自绘 `KnockSearchBar`，无系统搜索玻璃） |
| 系统 TabBar | `.toolbar(.hidden, for: .tabBar)` 全站 4 处（TabView / TabRootStack / ChatView / GroupChatView），自绘 `KnockTabBar` |
| 系统导航栏 | 全量隐藏：直接 `.toolbar(.hidden, for: .navigationBar)` 8 处 + 三套辅助修饰器（`knockHideSystemChrome()` 10、`knockHiddenNavigationBar()` 18、`settingsKnockNavigationChrome()` 10） |
| iOS 26 滚动边缘玻璃 | `scrollEdgeEffectHidden` 7 处（helper + 调用点） |
| sheet | 7 个 sheet 内容均为不透明（`AmbientBackground` 实色 + 迷彩）；其中 2 处另设实色 `presentationBackground(KnockTokens.canvas)`（备份 Markdown 角色选择、群成员） |
| 旧玻璃组件 | `GlassPanel` / `CardSurface` / `AmbientBackground` / `MessageBubble` / `GlassInputBar` 均已重写为实色实现；`cardSurface(` / `EmptyStateView(` / `GlassTokens.brandOrange` 调用点 = 0 |

### 5.2 截图取证

- 所有页面无系统 TabBar / 导航栏残影、无滚动边缘玻璃条、无半透明面板：见 §3.1 全量 46 张。
- 空态、弹窗、菜单均为自绘：`knock-final-gallery-*`（画廊 07 空态 / 08 弹窗段）。

### 5.3 决策结论

> **A 级：SwiftUI 走到底**，不引入 UIKit 混合容器。

- App 自有页面的不可控玻璃点 = **0**（远低于决策树「混合 UIKit」的 3–4 门槛）；
- 自绘组件（TabBar / 分段栏 / 搜索条 / 弹窗 / 空态 / 红色 X 键）已覆盖全部交互面；
- 不可控系统表面仅 4 类，且均为系统标准模态、不在 App 自绘页面内：通知授权弹窗、`ShareLink`、PhotosPicker、系统键盘。它们不影响敲敲视觉一致性，无需 UIKit 改造。

---

## 6. 可访问性验证

### 6.1 Dynamic Type「大」（设置 → 外观 → 字号「大」→ 全局 `.dynamicTypeSize(.xxLarge)`）

| 页面 | 证据 | 结果 |
|---|---|---|
| 聊天列表 | `knock-a11y-dynamic-type-chat-list-{dark,light}.png` | 行标题/副标题/时间/角标整体放大，两行副标题自动换行，**无裁切**、TabBar 文字不溢出（`minimumScaleFactor 0.85`） |
| 设置根 | `knock-a11y-dynamic-type-settings-dark.png` | 分组标题/胶囊行/状态 chip/大字号预览均不裁切 |
| 角色详情 | `knock-a11y-dynamic-type-role-detail-dark.png` | 顶栏标题/卡片/按钮不裁切 |

### 6.2 减弱动态效果（系统「减弱动态效果」）

- 系统开关生效实证：`knock-a11y-reduce-motion-settings-dark.png` 中状态 chip 显示 **「减弱动态 开」**（App 读取 `accessibilityReduceMotion` 实时值）。
- 代码分支：`RootTabView` 切 Tab 动画在 reduceMotion 下置 `nil`；`KnockSegmentedBar` 选中胶囊不做弹簧动画；`KnockExitKey` 按压无缩放动画；`KnockTexture` 本就静态。
- 说明：动画禁用无法用静态截图证明，已由「系统标志生效 + 代码分支」双重取证；`knock-a11y-reduce-motion-chat-list-dark.png` 为同条件下的页面渲染证据。

### 6.3 降低透明度（系统「降低透明度」）——含模拟器限制

- 设计行为：`AmbientBackground.textureIntensity = (reduceTransparency || !textureEnabled) ? 0 : 1` → 关闭迷彩纹理，只留纯色底（可读性优先），面板本身已是实色、无需降级。
- **限制**：模拟器无法注入该系统开关——写入 `com.apple.Accessibility ReduceTransparencyEnabled = 1` 并**重启模拟器**、以及 `killall SpringBoard` respring 后，`UIAccessibility.isReduceTransparencyEnabled` 仍为 false（App 设置页状态 chip 实测仍显示「降低透明度 关」）。因此改用 **App 内『纹理』开关**验证**同一渲染分支**：`knock-a11y-reduce-transparency-equivalent-chat-list-dark.png`（纹理关闭 → 纯黑底，无迷彩块）。
- 建议：真机抽查该项（真机系统开关可正常生效）。

### 6.4 对比度（计划书 1.3）

| 组合 | 实测 |
|---|---|
| 白字 / 黑底（`#FFFFFF` on `#000000`） | 21:1 ✅ |
| 黑字 / 黄底（`#0B0B0C` on `#F2FF00`） | ≈17.6:1 ✅（深浅主题一致，黄不随主题变色） |
| 白字 / 蓝气泡（`#FFFFFF` on `#3364E7`） | ≈5.2:1 ✅（AA） |
| 次文字 / 黑底（`#9A9A9A` on `#000000`） | ≈7.6:1 ✅（AA） |
| 危险底 / 白字（`#FFFFFF` on `#BF2207`） | ≈5.9:1 ✅（AA） |

> 增强对比度（`colorSchemeContrast == .increased`）时描边切换 `strokeContrast`；降低透明度时纹理关闭（见 §6.3）。

---

## 7. 自检回归矩阵（最终构建）

运行方式：同一模拟器串行执行；每次 `--selftest <hook>` 启动后取回 `Documents/ios-shell-selftest.log`、`Application Support/t55-mode-switch-selftest.log`、`Documents/ios-persona-selftest.log`。日志留档 `/tmp/regression/<case>/`。

| # | 钩子 | 覆盖 | 结果 | 关键证据 |
|---|---|---|---|---|
| 1 | `--selftest` | 壳层冒烟：环境 bootstrap / 会话 / 历史分页 / 记忆 / 设置写入读回 / 深链 / 4 Tab 切换 / 真实发送落库 | ✅ PASS | sessions=6、history total=33、memories=1、settings readBack 非空、`deeplink rhodes://chat/session_amiya handled=true`、tabs 依次切换、发送后 lastMessage 更新 |
| 2 | `--bubble-split-selftest` | 旁白/台词分段：段数、类型顺序、时间分隔仅首段、id 稳定、流式增量、线上模式过滤旁白 | ✅ PASS | `bubble-split selftest done PASS=true failures=0`（19 条 PASS 断言） |
| 3 | `--mode-switch-selftest`（shared 侧）+ `--mode-switch-ui-demo`（UI 链路） | 三模式切换：DB mode 字段、last_mode 落盘、三套提示词分段断言（offline/director/online） | ✅ PASS | `t55-mode-switch-selftest.log`：`RESULT PASS`（方向键 24 条 PASS：prompt.* / db.mode.* / settings.last_mode.*）；UI demo `mode=online` 完成 |
| 4 | `--roles-selftest` | 角色列表(147)/头像计数/详情/关系网/新建会话全链路 | ✅ PASS | `list count=147 withSession=5 builtinAvatar=147`、`bundledAvatars=147/147`、`detail found=true`、`relations count=1`、`startChat → session linked` |
| 5 | `--persona-selftest`（+`--persona-cleanup`） | 人设编辑：新建→列表→编辑→选择器→会话复用→删除→DB 读回失败 | ✅ PASS | `persona selftest done PASS=16 FAIL=0`（含 `PASS read after delete fails`） |
| 6 | `--settings-selftest`（写） | 聊天表现保存读回、提示词保存/语法校验/恢复默认/模块禁用、识图配置 configured | ✅ PASS | `settings selftest write done pass=7 fail=0` |
| 7 | `--settings-selftest-verify`（冷启动校验） | 上轮写入是否持久化（NSUserDefaults 冷启动读回） | ✅ PASS | `settings selftest verify done pass=3 fail=0`（temp=1.23 / thinking=true / history=42 / prompt len=890 / vision configured） |
| 8 | `--settings-selftest-restore`（恢复） | 恢复 Android 默认（0.70/false/15/300/300）+ 提示词默认 + 识图清空 | ✅ PASS | `restore result='' temp=0.7 thinking=false history=15 diaMax=300 narMax=300 visionCleared=true` |
| 9 | `--memory-mgmt-selftest` | 记忆管理：预置/列表/筛选/编辑/删除/隐私/锚点 | ✅ PASS | `memory mgmt selftest done PASS=true passed=27 failed=0` |
| 10 | `--group-selftest`（补跑） | 群聊全链路：群列表/详情/成员/禁言/分页/自动互动开关/发群消息/分段撤回/深链 | ✅ PASS | `group selftest done`；`recallSegment … PASS=true`（segmentsBefore=2 → visible 1 / recalled 1）；群消息 2 条落库（我 1 条 + 多发言人 ai_json 1 条） |

**已知差异**：`--selftest` 的计数断言是「与真实 DB 一致」而非固定条数（历史数据随回归累积：会话 6、消息 33），日志中记录了实际值；`roles selftest` 的 `withSession` 随会话新增而增长（5→7），属预期。**无 FAIL 项。**

> 首轮回归（23:35–23:39，DS 冻结前构建）与终版回归（23:52–23:55，冻结后构建）结论一致；DS 冻结差异仅为视觉组件（分段栏/红 X），不影响上述逻辑断言。

---

## 8. 已知限制

1. **「降低透明度」系统开关无法在模拟器注入**（plist + 重启 + respring 后 `UIAccessibility` 仍为 false），已用 App 纹理开关验证同一渲染分支（§6.3）；建议真机抽查。
2. **「减弱动态效果」只能证明系统标志生效 + 代码分支**，静态截图无法证明动画被禁用；建议真机观察 Tab 切换/按钮按压。
3. **TabBar 随根页离屏**：自绘 TabBar 挂在 Tab 根视图上，推送二级页时随根页过渡离屏（而非固定悬浮层）。视觉过渡自然无闪烁，但与 iOS 系统 TabBar「固定悬浮」语义略有差异；如需固定悬浮，应改为 AppRouter 统一二级页深度状态（会侵入各域导航代码，P5 未做）。
4. **组件画廊 3.96%** 为色板/控件展示页（仅 `--knock-gallery` 启动参数可达，非用户路径），黄色为其展示目标；若后续纳入用户路径需重新审计。
5. **`ChatAppearance.swift` 6 档聊天背景为死代码**：UI 已按决策 6 删除入口、`GlassTokens.chatBackground` 固定返回敲敲底；枚举与 `SettingsStore.chatBackground` 字段仅为兼容旧存储键/自检，待后续清理。
6. **7 个 sheet 未显式设置 `presentationBackground`**：内容均为不透明 `AmbientBackground`，实测无系统玻璃边缘；建议后续统一补实色背景（2 处已设）。
7. **截图数据为模拟器夹具**：演示模式回复、演示动态/日记/关系（`--relations-demo` 写入 2 条演示关系）；非生产内容，页面结构/视觉不受影响。
8. **机型覆盖**：深/浅双主题均在 iPhone 17（iOS 27.2，1206×2622@3x）验证；未逐一验证其他尺寸机型（计划书范围仅 iPhone 竖屏）。

---

## 9. 复现命令

```bash
# 构建（模拟器）
cd ios && xcodebuild -project RhodesLink.xcodeproj -scheme RhodesLink \
  -configuration Debug -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath /tmp/rhodes-dd-polish build

# 单页截图（内置串行锁；--wait 5 等首帧稳定）
scripts/ui-screenshot.sh /tmp/shot.png --dark --wait 5 --launch-arg --tab --launch-arg chat

# 终版关键页（示例：我的身份 = 设置二级页 isPresented 路径）
scripts/ui-screenshot.sh screenshots/knock-final-profile-dark.png --dark --wait 9 \
  --launch-arg --tab --launch-arg settings --launch-arg --identity-demo

# 设计系统专项
scripts/ui-screenshot.sh screenshots/knock-final-gallery-dark.png --dark --wait 7 --launch-arg --knock-gallery --launch-arg --knock-appearance --launch-arg dark
scripts/ui-screenshot.sh screenshots/knock-final-bars-dark.png    --dark --wait 6 --launch-arg --knock-gallery --launch-arg --knock-chrome-proof --launch-arg --knock-appearance --launch-arg dark
scripts/ui-screenshot.sh screenshots/knock-final-red-x-dark.png   --dark --wait 6 --launch-arg --knock-gallery --launch-arg --knock-exit-key --launch-arg --knock-appearance --launch-arg dark

# 黄色面积审计（阈值 5%，超标退出码 1）
python3 scripts/knock-yellow-audit.py "screenshots/knock-final-*.png" --md-out /tmp/yellow.md

# 回归（示例）
xcrun simctl launch <UDID> com.rhodeslink.app --selftest --bubble-split-selftest --open-url rhodes://chat/session_amiya
```

**模拟器注意事项**：App 外观设置键 `ios_shell_appearance` 必须为 `system`，否则 `simctl ui appearance dark|light` 不生效：
`xcrun simctl spawn <UDID> defaults write com.rhodeslink.app ios_shell_appearance system`。

---

## 10. 文件清单（P5 新增/修改）

| 类型 | 路径 |
|---|---|
| 修改 | `ios/RhodesLink/RhodesLinkApp.swift`（TabBar 下移根视图 + `TabRootStack`） |
| 修改 | `ios/RhodesLink/App/AppRouter.swift`（`Route.knowledgeList` + `rhodes://features/knowledge` 深链） |
| 修改 | `ios/RhodesLink/Features/Common/RouteView.swift`（知识库路由分支 + 占位页敲敲化） |
| 修改 | `ios/RhodesLink/Features/Settings/BackupSettingsView.swift`（黄色面积整改：Markdown 导入改次按钮） |
| 新增 | `scripts/knock-yellow-audit.py`（黄色面积审计脚本） |
| 新增 | `handoffs/knock-ui-final.md`（本报告） |
| 新增 | `screenshots/knock-final-*.png` ×46、`screenshots/knock-a11y-*.png` ×7、`knock-brand-home.png` |
| 归档 | `screenshots/archive/` ×84（P0–P4 过程件与旧 T 编号截图） |
| 修改 | `README.md`（新增「界面风格（敲敲）」章节、更新 hero 截图/仓库结构/架构要点/更新记录） |

---

*报告生成：P5 收口（polish）· 源码冻结点 2026-11-25 23:39 · 截图与审计均在冻结后的构建上重跑。*
