//
//  DiaryAutomation.swift
//  T51 干员日记端到端自检（仅 `--diary-selftest` 启动参数启用，对用户路径零影响）。
//
//  链路：桥层自检（DiaryBridge.debugRunSelfTest：夹具预置 → 列表 → 未读 → 详情 → 标记已读 →
//  DB/settings 核对）→ UI 自动化（列表出现未读点 → 打开详情阅读 → 未读清零 → 回写列表）。
//  证据：Documents/diary-selftest.log（`simctl get_app_container` 可取）+ NSLog。
//

import Foundation

@MainActor
enum DiaryAutomation {
    static let isEnabled: Bool = ProcessInfo.processInfo.arguments.contains("--diary-selftest")

    private static var didRun = false
    private static var lines: [String] = []

    static var hasRun: Bool { didRun }

    /// 最近一次自检结论（供页面顶部展示）。
    private(set) static var resultText: String = ""
    /// 最近一次自检是否全部 PASS（nil = 未跑/进行中）。
    private(set) static var passed: Bool?

    static func beginIfNeeded() -> Bool {
        guard isEnabled, !didRun else { return false }
        didRun = true
        lines = []
        resultText = "自检运行中…"
        passed = nil
        add("=== T51 diary UI selftest start ===")
        add("args: \(ProcessInfo.processInfo.arguments.joined(separator: " "))")
        return true
    }

    static func add(_ line: String) {
        NSLog("[RhodesLink][T51SELFTEST] %@", line)
        lines.append("\(timestamp()) \(line)")
    }

    /// 写结论：综合桥层 RESULT 与 UI 断言（FAIL 行数）。
    static func finish(summary: String) {
        let failCount = lines.filter { $0.contains(" FAIL ") }.count
        let ok = failCount == 0
        passed = ok
        resultText = ok ? "自检 PASS" : "自检 FAIL（\(failCount)）"
        add("UI RESULT \(ok ? "PASS" : "FAIL(\(failCount))")")
        add(summary)

        guard let directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return }
        let url = directory.appendingPathComponent("diary-selftest.log")
        let header = [
            "# RhodesLink iOS T51 干员日记端到端自检日志",
            "# args: \(ProcessInfo.processInfo.arguments.joined(separator: " "))",
            "# ————————————————————",
        ]
        let text = (header + lines + ["", "## summary", summary]).joined(separator: "\n")
        do {
            try text.write(to: url, atomically: true, encoding: .utf8)
            NSLog("[RhodesLink][T51SELFTEST] log written to %@", url.path)
        } catch {
            NSLog("[RhodesLink][T51SELFTEST] log write failed: %@", error.localizedDescription)
        }
    }

    private static func timestamp() -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss.SSS"
        return formatter.string(from: Date())
    }
}
