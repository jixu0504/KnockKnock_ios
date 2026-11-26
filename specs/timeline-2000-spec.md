# 时间线 2,000 条性能原型规格（timeline-2000-spec.md）

> 状态：v1，Gate 0 第 5 条验收（已通过）。
> 验证物：`ios-port/iosApp-demo/GlassShellDemo` 第 5 个 Tab「时间线」；实测数据原件 `ios-port/handoffs/perf-result-lazyvstack.json`。
> 依据：《玻璃设计规范 v1》设计原则 3（气泡不用玻璃）与可行性报告 §4.3 界面映射。

---

## 1. 目标与验收对应

Gate 0 第 5 条：证明 Phase 3 正式时间线可在 iOS 上以 2,000 条消息规模流畅滚动，并给出「SwiftUI LazyVStack vs UICollectionView」的选型依据。

验收口径：
- 2,000 条异构消息渲染，id 稳定，气泡无玻璃；
- 程序化滚动遍历 2,000 条 × 3 轮，无崩溃；
- CADisplayLink 帧间隔统计（均值 / P95 / 掉帧率）+ rusage 峰值内存，落盘 JSON；
- 若均值 < 50fps 才触发 UICollectionView 对比版（本次未触发，见 §5）。

## 2. 原型设计

### 2.1 入口
- Demo 第 5 个 Tab「时间线」（`RootTabView` 增加 `AppTab.timeline`）。
- 启动参数 `--perf-test`：默认选中时间线 Tab，进入后 1.2s 自动开始自测；无参数时右上角「自测」按钮可手动跑。

### 2.2 数据模型（`TimelineMessage.swift`）
- **id 稳定**：`Int` 序号（0…1999），直接作为 ForEach 身份，供 `scrollTo` 精确定位。
- **确定性生成**：固定种子 LCG 伪随机，每次运行数据集完全一致（可复现对比）。
- **异构构成**（2,000 条）：

| 种类 | 占比 | 说明 |
|---|---|---|
| 短文本 shortText | 20% | ≤30 字 |
| 长文本 longText | 20% | 4–12 句拼接，约 90–450 字，多行换行 |
| 图片占位 image | 20% | 不透明渐变色块，16:9 / 4:3 / 1:1 三档宽高比 + 尺寸标注 |
| 引用回复 quote | 10% | 引用块（品牌橙竖线 + 半透明底）+ 回复气泡 |
| 撤回提示 recall | 10% | 居中灰胶囊「xx 撤回了一条消息」 |
| 系统提示 system | 20% | 居中灰胶囊（加入会话 / 加密开启 / 主题变更等） |

- 收发比：约 20% outgoing / 80% incoming，气泡左右分布。

### 2.3 渲染（`TimelinePrototypeView.swift`）
- `ScrollView + LazyVStack(spacing: 8)`，惰性实例化，只保留视口附近行。
- **气泡不用玻璃**（设计原则 3）：文本气泡复用 `bubbleIncoming/bubbleOutgoing` token（高对比半透明/不透明色，圆角 18）；图片占位、引用块、提示行均为不透明/半透明纯色，无 blur、无 material。
- 正文 `.body`（≥17pt）；行内容最大宽 300pt。
- 环境背景用 `ambientTop/Bottom` 纯渐变——自测期间刻意不做视差/模糊/立绘层，避免非时间线因素干扰帧数据。

### 2.4 性能自测装置（`PerfHarness.swift`）
- **滚动驱动**：CADisplayLink（`.main/.common`）每帧按 `45 条/s` 推进目标下标，`ScrollViewProxy.scrollTo(id, anchor: .top)` 定位。45 条/s ≈ 4,000pt/s 级快速滑动，每轮 44.4s 遍历全部 2,000 条；轮间跳回顶部并稳定 0.8s（稳定期不滚动、不采样）。
- **帧采样**：同一 CADisplayLink 记录实际帧间隔（CACurrentMediaTime 差值）。
- **掉帧口径**（相对刷新率，兼容 60/120Hz）：`dropped15` = 帧间隔 > 1.5 × 中位帧间隔（错过 ≥1 次刷新）；`dropped20` = > 2.0 × 中位（明显卡顿帧）。
- **内存**：`getrusage(RUSAGE_SELF).ru_maxrss`（峰值常驻，Darwin 单位字节）+ `task_vm_info.phys_footprint`（Jetsam 记账口径），测试期间每 0.25s 采样取峰。
- **落盘**：`Documents/perf-result.json`（App 沙盒内），含 device / config / rounds / overall / memory 全量数据。

## 3. 实测数据（iPhone 17 模拟器，iOS 27.2，60Hz 主机）

跑 3 轮无崩溃；全量原件见 `ios-port/handoffs/perf-result-lazyvstack.json`。

| 轮次 | 帧数 | 时长 | 均值帧间隔 | 均值 FPS | P95 帧间隔 | P95 FPS | P99 | 最大帧 | dropped15 | dropped20 |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 2,649 | 44.42s | 16.77ms | **59.62** | 16.72ms | 59.81 | 18.26ms | 65.07ms | 14 (0.53%) | 3 (0.11%) |
| 2 | 2,649 | 44.42s | 16.77ms | **59.62** | 16.73ms | 59.76 | 18.27ms | 33.39ms | 17 (0.64%) | 3 (0.11%) |
| 3 | 2,642 | 44.42s | 16.82ms | **59.46** | 16.73ms | 59.77 | 18.27ms | 33.35ms | 24 (0.91%) | 1 (0.04%) |
| **总体** | **7,940** | **133.25s** | **16.79ms** | **59.56** | **16.73ms** | **59.77** | **18.27ms** | **65.07ms** | **55 (0.69%)** | **7 (0.09%)** |

内存（模拟器进程，含 SwiftUI 运行时）：

| 指标 | 值 |
|---|---|
| ru_maxrss 起始 / 峰值 / 结束 | 244.9 / **273.1** / 273.1 MB |
| phys_footprint 峰值 / 结束 | **42.5** / 38.6 MB |

关键观察：
- 中位帧间隔 16.67ms = 60Hz 主机满帧线；均值 59.56fps，**远高于 50fps 验收线**。
- 唯一 65ms 长帧出现在第 1 轮（初始物化 + 文本排版缓存预热），P99 稳定在 18.3ms。
- dropped15 三轮 0.53% → 0.64% → 0.91%，轻微上升但幅度小；dropped20 全程 ≤0.11%，无阻断性卡死。
- phys_footprint 峰值仅 42.5MB——LazyVStack 惰性实例化生效，2,000 条数据未造成内存线性膨胀（数据文本池总量 < 100KB，行视图随滚随建随弃）。

## 4. 结论

1. **SwiftUI LazyVStack 在 2,000 条异构消息下满足 Gate 0 全部验收**：3 轮遍历无崩溃、均值 59.6fps、P95 59.8fps、明显卡顿帧 0.09%，滚动无阻断性卡死。
2. 图片占位为纯色块（无解码、无 IO），这是「模拟器最优情形」；真实图片解码/缓存引入后需复测（见 §6）。
3. 模拟器帧数据只反映「SwiftUI 布局/物化成本」下限，真机（ProMotion 120Hz、A 系列 GPU、内存限额更紧）需在 Phase 3 复测。

## 5. Phase 3 正式时间线建议（含 UICollectionView 决策依据）

**结论：Phase 3 首选 SwiftUI `LazyVStack`（或 `List` 会话视图），不需要为 2,000 条规模预先引入 UICollectionView。**

决策依据：
- 实测均值 59.6fps，距 50fps 触发线有 ~20% 余量；本次未触发 UICollectionView 对比版（按 Gate 0 约定「只记录，不阻塞」）。
- 2,000 条是本项目已知最大会话规模（聊天导入评估以 2,000 条为边界）；超过该规模的极端会话可再评估。
- LazyVStack 与 SwiftUI 玻璃 chrome（导航、Tab Bar 最小化、输入栏 GlassEffectContainer）集成成本最低，避免 UIViewRepresentable 与玻璃/手势系统的互操作成本。

**触发 UICollectionView 的条件**（满足任一即实施对比版，建议 Phase 3 每周性能冒烟覆盖）：
1. 真实图片负载（解码 + 磁盘缓存）接入后，快速滑动均值 < 50fps 或 dropped20 > 2%；
2. 会话规模提升到 5,000+ 条且出现滚动白屏/长帧 > 100ms；
3. 真机 ProMotion 上出现周期性长帧（如 cell 复用导致的 30Hz 抖动）。

若触发，实施方案：`UIViewRepresentable` + `UICollectionView` + `UICollectionViewCompositionalLayout`（estimatedItemSize 自动高度）+ 预取（`UICollectionViewDataSourcePrefetching`），行视图可复用 SwiftUI 的 `TimelineRow` 经 `UIHostingConfiguration` 嵌入（cell 复用 + SwiftUI 排版双赢），并复用 `PerfHarness` 跑同一数据集对比。

Phase 3 其他建议：
- 时间线行视图保持「无玻璃」原则与 300pt 最大宽度，正文 `.body` 起。
- 图片消息用 `AsyncImage` + 内存/磁盘缓存 + 固定宽高比占位，避免布局抖动（宽高比未知会引发滚动跳动，是比帧率更影响体验的项）。
- 消息 id 用 Int 序号（或稳定 snowflake 的 Hashable 包装），不要用 `UUID()` 每次重建——本次原型已证明稳定 id 是 scrollTo/增量更新前提。
- 帧率回归自测并入 CI 冒烟：`simctl launch --perf-test` → 轮询 `Documents/perf-result.json` → 阈值断言（均值 ≥ 50fps、dropped20 ≤ 2%）。

## 6. 遗留与局限

| 项 | 说明 |
|---|---|
| 模拟器 vs 真机 | 数据为 Intel/Apple Silicon 主机上的模拟器结果；真机内存限额（Jetsam）与 GPU 压力不同，Phase 3 复测 |
| 无图片解码负载 | 占位为纯色块；真实 JPEG/HEIC 解码、缓存、IO 未覆盖 |
| 无网络/增量更新 | 静态数据集；实时收发、差分插入、未读数、输入态未覆盖 |
| 刷新率 | 本次主机 60Hz；120Hz ProMotion 下的掉帧口径需真机验证 |
| 玻璃干扰 | 自测背景为纯渐变；正式页面的视差光斑/立绘模糊层对帧率的影响未测（Phase 3 用真实页面复测） |
| 撤回/系统行 | 交互（撤回动效、折叠系统提示）不在本原型范围 |

## 7. 附件

- 实测数据原件：`ios-port/handoffs/perf-result-lazyvstack.json`
- 运行截图：`ios-port/handoffs/timeline-prototype-screenshot.png`
- 源码：`ios-port/iosApp-demo/GlassShellDemo/Features/TimelinePrototype/{TimelineMessage,PerfHarness,TimelinePrototypeView}.swift` + `GlassShellDemoApp.swift`（第 5 Tab）
