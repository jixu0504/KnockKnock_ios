# Handoff：T12 时间线 2,000 条性能原型（timeline-prototype）

> 交付日期：2026-11-23（Gate 0 第 5 条）
> 规格文档：`ios-port/specs/timeline-2000-spec.md`

## 1. 做了什么

在 GlassShellDemo（`ios-port/iosApp-demo`）新增第 5 个 Tab「时间线」，渲染 2,000 条异构消息（短/长文本、图片占位色块、引用块、撤回提示、系统提示行），气泡无玻璃（复用玻璃 token 配色）。内置自动滚动性能自测：

- **驱动**：`--perf-test` 启动参数自动选中时间线 Tab 并开跑（无参数可点右上「自测」手动跑）。
- **滚动**：CADisplayLink 每帧推进 `ScrollViewProxy.scrollTo`，45 条/s 遍历 2,000 条 × 3 轮，轮间回顶稳定 0.8s。
- **统计**：同 CADisplayLink 记录实际帧间隔 → 均值 / P95 / P99 / max / 掉帧率（dropped15 = >1.5×中位，dropped20 = >2.0×中位，兼容 60/120Hz）；`getrusage.ru_maxrss` + `phys_footprint` 每 0.25s 采样取峰。
- **落盘**：App 沙盒 `Documents/perf-result.json`，验收脚本轮询该文件确认完成。

新增/修改文件（均在写域内）：

| 文件 | 说明 |
|---|---|
| `iosApp-demo/GlassShellDemo/Features/TimelinePrototype/TimelineMessage.swift` | 数据模型 + 固定种子 LCG 确定性生成 2,000 条 |
| `iosApp-demo/GlassShellDemo/Features/TimelinePrototype/PerfHarness.swift` | FrameProfiler / MemorySampler / PerfRunController / JSON 落盘 |
| `iosApp-demo/GlassShellDemo/Features/TimelinePrototype/TimelinePrototypeView.swift` | LazyVStack 时间线 + 六种行视图 + 状态横幅 |
| `iosApp-demo/GlassShellDemo/GlassShellDemoApp.swift` | 第 5 Tab + `--perf-test` 默认选中 |

## 2. 构建 / 运行方法（可复现）

```bash
# 构建（沙箱适配：-derivedDataPath 指工作区内；OTHER_SWIFT_FLAGS=-disable-sandbox 已在工程内）
xcodebuild build -project ios-port/iosApp-demo/GlassShellDemo.xcodeproj \
  -scheme GlassShellDemo -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath ios-port/iosApp-demo/DerivedData

# 运行（iPhone 17，iOS 27.2 runtime）
xcrun simctl boot "iPhone 17"                      # 已 booted 则跳过
xcrun simctl uninstall <udid> com.rhodeslink.demo.GlassShellDemo
xcrun simctl install <udid> ios-port/iosApp-demo/DerivedData/Build/Products/Debug-iphonesimulator/GlassShellDemo.app
xcrun simctl launch <udid> com.rhodeslink.demo.GlassShellDemo --perf-test

# 轮询结果（~140s 后出现）
CONTAINER=$(xcrun simctl get_app_container <udid> com.rhodeslink.demo.GlassShellDemo data)
cat "$CONTAINER/Documents/perf-result.json"
```

本次实测：iPhone 17 模拟器（UDID A63CD17A-E1F9-4C5C-9B8C-92DEB43862FA）、iOS 27.2、60Hz 主机。构建一次通过；安装后 launch 返回 pid 95914，文件在 ~140s 后出现（28 次 ×5s 轮询）。

## 3. 结果（LazyVStack 通过，未触发 UICollectionView 对比版）

3 轮遍历无崩溃；总体 7,940 帧：

- **均值 FPS 59.56**（验收线 50）｜P95 FPS 59.77｜P99 帧间隔 18.27ms｜最大帧 65.07ms（仅第 1 轮初始物化一次）
- 掉帧：dropped15 = 55 帧（0.69%），dropped20 = 7 帧（0.09%）；无阻断性卡死
- 内存：phys_footprint 峰值 42.5MB（惰性实例化生效，未线性膨胀）；ru_maxrss 峰值 273.1MB（模拟器进程口径，含运行时）

原件：`ios-port/handoffs/perf-result-lazyvstack.json`；运行截图：`ios-port/handoffs/timeline-prototype-screenshot.png`。

## 4. 结论与建议（详见 spec §5）

- **Phase 3 首选 SwiftUI LazyVStack**，不为 2,000 条规模预引入 UICollectionView。
- UICollectionView 触发条件（满足任一再实施）：真实图片负载后均值 <50fps 或 dropped20 >2%；规模 5,000+ 且出现长帧 >100ms；真机 ProMotion 周期抖动。
- 若触发：`UIViewRepresentable` + `UICollectionViewCompositionalLayout` + `UIHostingConfiguration` 复用 `TimelineRow`，复用 PerfHarness 跑同数据集对比。

## 5. 遗留

1. **模拟器数据不代表真机**：Phase 3 需真机复测（ProMotion 120Hz 掉帧口径、Jetsam 内存限额、GPU 压力）。
2. **无真实图片负载**：占位为纯色块；AsyncImage + 缓存 + 固定宽高比占位接入后复测（宽高比抖动比帧率更伤体验）。
3. **静态数据集**：未覆盖实时收发、差分插入、未读数等动态更新路径。
4. **无玻璃干扰测量**：自测背景刻意用纯渐变；正式页面视差光斑/立绘模糊层对帧率影响未测。
5. 帧率回归可并入 CI 冒烟（launch --perf-test → 轮询 JSON → 阈值断言），见 spec §5。
6. `print` 进度日志走 stdout，不进统一日志（`log show` 看不到）；验收以文件轮询为准（已验证可靠）。

## 6. 附件清单

| 附件 | 路径 |
|---|---|
| 规格文档（含数据表与决策） | `ios-port/specs/timeline-2000-spec.md` |
| 实测 JSON 原件 | `ios-port/handoffs/perf-result-lazyvstack.json` |
| 运行截图 | `ios-port/handoffs/timeline-prototype-screenshot.png` |
