//
//  ModeSwitchAutomation.swift
//  T55 聊天模式切换 UI 演示/取证（仅启动参数启用，对用户路径零影响）：
//    --mode-switch-ui-demo  → 等聊天页加载 → 打开模式弹窗 → 依次切 offline / director / online，
//                             每步保留 ~9s 供 `xcrun simctl io booted screenshot` 定时截图；
//    --mode-switch-menu     → 只打开模式弹窗并保持 30s（菜单截图用）。
//
//  说明：真实 DB/settings 写入与提示词断言在 shared 侧 `--mode-switch-selftest`
//  （ChatEngineBridge.debugModeSwitchSelftest → t55-mode-switch-selftest.log）；
//  本文件只负责「用户可见 UI 链路」的截图取证与日志。
//

import Foundation

@MainActor
enum ModeSwitchAutomation {
    /// 完整演示：打开菜单 + 三模式依次切换。
    static let isEnabled = ProcessInfo.processInfo.arguments.contains("--mode-switch-ui-demo")
    /// 仅菜单取证：打开三选一弹窗后保持。
    static let isMenuShotEnabled = ProcessInfo.processInfo.arguments.contains("--mode-switch-menu")
    private static var didRun = false

    private static func sleep(_ seconds: Double) async {
        try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
    }

    static func run(
        model: ChatViewModel,
        presentPicker: @escaping () -> Void,
        dismissPicker: @escaping () -> Void
    ) async {
        guard isEnabled || isMenuShotEnabled, !didRun else { return }
        didRun = true
        let sessionId = model.sessionIdForAutomation
        SelfTestLog.add("=== T55 mode switch UI demo start session=\(sessionId) menuOnly=\(isMenuShotEnabled) ===")

        // 等首屏（会话元数据）加载
        let deadline = Date().addingTimeInterval(20)
        while model.session == nil && Date() < deadline {
            await sleep(0.2)
        }
        guard model.session != nil else {
            SelfTestLog.add("T55 mode switch UI demo ABORT: session not loaded")
            return
        }
        SelfTestLog.add("T55 mode switch UI demo loaded mode=\(model.mode) title=\(model.title)")

        await sleep(2.0)
        presentPicker()
        SelfTestLog.add("T55 mode switch UI demo menu presented currentMode=\(model.mode)")

        if isMenuShotEnabled {
            await sleep(30.0)
            dismissPicker()
            SelfTestLog.add("T55 mode switch UI demo menu held 30s, done")
            SelfTestLog.flushToDisk(summary: "T55 聊天模式菜单取证（线上/线下/导演三选一）")
            return
        }

        await sleep(12.0)
        dismissPicker()
        for target in ["offline", "director", "online"] {
            await model.setMode(target)
            SelfTestLog.add(
                "T55 mode switch UI demo switched target=\(target) mode=\(model.mode) " +
                "dbMode=\(model.session?.mode ?? "-") status=\(model.statusLine)"
            )
            await sleep(10.0)
        }
        SelfTestLog.add("=== T55 mode switch UI demo done mode=\(model.mode) ===")
        SelfTestLog.flushToDisk(summary: "T55 模式切换 UI 演示（线上/线下/导演 + 模式菜单）")
    }
}

/// T55：聊天模式选项（导航栏弹窗三选一 + UI 文案）。
struct ChatModeOption: Identifiable, Hashable {
    let code: String
    let label: String
    let icon: String
    var id: String { code }
}
