//
//  SelfTest.swift
//  启动自检（仅 `--selftest` 启动参数启用；对用户路径零影响）：
//  串联验证 Phase 3 验收项 —— shared 桥接、4 Tab、深链、聊天发送/打字机/落库。
//
//  证据：
//  - NSLog（`xcrun simctl spawn booted log show` 可查）；
//  - 沙盒 Documents/ios-shell-selftest.log（simctl get_app_container 可取）。
//
//  另外 `--stress-2000`：聊天页注入 2,000 条本地消息（T12 LazyVStack 基线冒烟）。
//

import Foundation

// MARK: - 自检日志

enum SelfTestLog {
    static let isEnabled: Bool = ProcessInfo.processInfo.arguments.contains("--selftest")

    private static let lock = NSLock()
    private static var lines: [String] = []

    static func add(_ message: String) {
        NSLog("[RhodesLink][SelfTest] %@", message)
        guard isEnabled else { return }
        lock.lock()
        lines.append("\(timestamp()) \(message)")
        lock.unlock()
    }

    static func snapshot() -> [String] {
        lock.lock()
        defer { lock.unlock() }
        return lines
    }

    /// 把自检日志写入 App Documents（模拟器上可 `simctl get_app_container` 取出作为证据）
    static func flushToDisk(summary: String) {
        guard isEnabled else { return }
        let header = [
            "# RhodesLink iOS 真壳自检日志",
            "# args: \(ProcessInfo.processInfo.arguments.joined(separator: " "))",
            "# device: \(deviceDescription())",
            "# ————————————————————",
        ]
        let body = snapshot().joined(separator: "\n")
        let text = (header + [body, "", "## summary", summary]).joined(separator: "\n")
        guard let directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return }
        let url = directory.appendingPathComponent("ios-shell-selftest.log")
        do {
            try text.write(to: url, atomically: true, encoding: .utf8)
            NSLog("[RhodesLink][SelfTest] log written to %@", url.path)
        } catch {
            NSLog("[RhodesLink][SelfTest] log write failed: %@", error.localizedDescription)
        }
    }

    private static func timestamp() -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss.SSS"
        return formatter.string(from: Date())
    }

    private static func deviceDescription() -> String {
        #if targetEnvironment(simulator)
        return "iOS Simulator \(ProcessInfo.processInfo.operatingSystemVersionString)"
        #else
        return "iOS Device \(ProcessInfo.processInfo.operatingSystemVersionString)"
        #endif
    }
}

// MARK: - 自检驱动

@MainActor
enum SelfTestDriver {
    static let isEnabled = ProcessInfo.processInfo.arguments.contains("--selftest")
    private static var didRun = false

    /// 启动参数深链钩子：`--open-url rhodes://chat/session_zzz008`
    /// 与 `.onOpenURL` 走同一 `AppRouter.handle(url:)` 路径；仅用于无 GUI 自动化验证
    /// （iOS 26 对 simctl 打开自定义 scheme 会弹系统确认框，模拟器命令行无法点击）。
    @discardableResult
    static func handleLaunchURLIfNeeded(router: AppRouter) -> Bool {
        let arguments = ProcessInfo.processInfo.arguments
        guard let index = arguments.firstIndex(of: "--open-url"),
              index + 1 < arguments.count,
              let url = URL(string: arguments[index + 1]) else {
            return false
        }
        let handled = router.handle(url: url)
        SelfTestLog.add("launch-arg deeplink url=\(url.absoluteString) handled=\(handled)")
        return handled
    }

    /// 自检流程（全部走真实 shared 桥接，不 mock）
    static func runIfNeeded(environment: AppEnvironment, router: AppRouter) async {
        guard isEnabled, !didRun else { return }
        didRun = true

        SelfTestLog.add("=== selftest start ===")
        SelfTestLog.add("args=\(ProcessInfo.processInfo.arguments)")
        SelfTestLog.add("tabs=\(AppTab.allCases.map(\.rawValue))")
        SelfTestLog.add("initialTab=\(router.selectedTab.rawValue)")

        // 1. 启动环境（含必要播种）
        await environment.start()
        SelfTestLog.add("bootstrap result: \(environment.seedMessage)")

        // 2. 会话（真实 DB）
        var firstSessionId: String?
        switch await environment.bridge.loadSessions() {
        case .success(let items):
            firstSessionId = items.first?.id
            SelfTestLog.add("sessions count=\(items.count) first=\(items.first?.id ?? "-") title=\(items.first?.title ?? "-") lastMessage=\(items.first?.lastMessage ?? "-")")
        case .failure(let error):
            SelfTestLog.add("sessions FAILED error=\(error.message)")
        }

        // 2.5 消息历史（T34 MessageHistoryBridge：真实 chat_messages 只读分页）
        if let sessionId = firstSessionId {
            switch await environment.bridge.loadMessagePage(sessionId: sessionId, beforeId: 0, limit: 50) {
            case .success(let page):
                SelfTestLog.add("history page1 session=\(sessionId) count=\(page.items.count) hasMore=\(page.hasMore) nextBeforeId=\(page.nextBeforeId) total=\(page.totalCount)")
                if let newest = page.items.first {
                    SelfTestLog.add("history newest id=\(newest.id) isMe=\(newest.isMe) type=\(newest.type) time=\(newest.timePayload) recalled=\(newest.isRecalled) text=\(newest.text.prefix(40))")
                }
                if let oldest = page.items.last {
                    SelfTestLog.add("history oldest id=\(oldest.id) type=\(oldest.type) text=\(oldest.text.prefix(30))")
                }
                if page.hasMore {
                    switch await environment.bridge.loadMessagePage(sessionId: sessionId, beforeId: page.nextBeforeId, limit: 50) {
                    case .success(let page2):
                        SelfTestLog.add("history page2 count=\(page2.items.count) hasMore=\(page2.hasMore) total=\(page2.totalCount) ids=\(page2.items.map(\.id))")
                    case .failure(let error):
                        SelfTestLog.add("history page2 FAILED error=\(error.message)")
                    }
                }
            case .failure(let error):
                SelfTestLog.add("history FAILED error=\(error.message)")
            }
        } else {
            SelfTestLog.add("history SKIPPED (no session)")
        }

        // 3. 长期印象（真实 DB）
        switch await environment.bridge.loadMemories() {
        case .success(let items):
            SelfTestLog.add("memories count=\(items.count) first=\(items.first?.content ?? "-")")
        case .failure(let error):
            SelfTestLog.add("memories FAILED error=\(error.message)")
        }

        // 4. 设置真实写入 + 读回（SettingsBridge）
        let readBack = environment.settings.writeTestNote()
        SelfTestLog.add("settings readBack=\(readBack) snapshot=\(environment.settings.lastSnapshot)")

        // 5. 深链：rhodes://chat/{sessionId} → 路由到聊天页
        if let sessionId = firstSessionId,
           let url = URL(string: "rhodes://chat/\(sessionId)") {
            let handled = router.handle(url: url)
            SelfTestLog.add("deeplink rhodes://chat/\(sessionId) handled=\(handled) lastHandledLink=\(router.lastHandledLink ?? "-")")
        } else {
            SelfTestLog.add("deeplink SKIPPED (no session)")
        }

        // 6. 等聊天页出现后触发一次真实发送（ChatEngineBridge → 打字机 → 落库）
        try? await Task.sleep(nanoseconds: 1_500_000_000)
        NotificationCenter.default.post(name: .shellSelfTestSendMessage, object: "自检：你好，新艾利都。这是来自 iOS 真壳的消息。")
        SelfTestLog.add("chat send posted")

        // 7. 等引擎回包（fake AI 约 120ms + 打字机 2 字/20ms）
        try? await Task.sleep(nanoseconds: 8_000_000_000)

        // 8. 会话 lastMessage 应被引擎更新（证明落库）
        switch await environment.bridge.loadSessions() {
        case .success(let items):
            SelfTestLog.add("sessions after send count=\(items.count) lastMessage=\(items.first?.lastMessage ?? "-")")
        case .failure(let error):
            SelfTestLog.add("sessions after send FAILED error=\(error.message)")
        }

        // 8.5 发送后历史刷新（T34：新消息应出现在 chat_messages 最新一页）
        if let sessionId = firstSessionId {
            switch await environment.bridge.loadMessagePage(sessionId: sessionId, beforeId: 0, limit: 50) {
            case .success(let page):
                SelfTestLog.add("history after send total=\(page.totalCount) count=\(page.items.count) hasMore=\(page.hasMore) newestId=\(page.items.first?.id ?? 0) newestIsMe=\(page.items.first?.isMe ?? false) newestText=\(page.items.first?.text.prefix(40) ?? "-")")
            case .failure(let error):
                SelfTestLog.add("history after send FAILED error=\(error.message)")
            }
        }

        // 9. 其余 Tab 切换冒烟（真实 TabView selection）
        let tabOrder: [AppTab] = [.contacts, .features, .settings, .chat]
        for tab in tabOrder {
            router.selectedTab = tab
            SelfTestLog.add("tab switched to \(tab.rawValue)")
            try? await Task.sleep(nanoseconds: 200_000_000)
        }

        SelfTestLog.add("deeplink history=\(router.linkHistory)")
        SelfTestLog.add("=== selftest done ===")
        SelfTestLog.flushToDisk(summary: "sessions=\(environment.sessionCount) memories=\(environment.memoryCount) lastLink=\(router.lastHandledLink ?? "-")")
    }
}

extension Notification.Name {
    /// 自检向当前聊天页投递一条待发送文本
    static let shellSelfTestSendMessage = Notification.Name("rhodeslink.selftest.send")
}
