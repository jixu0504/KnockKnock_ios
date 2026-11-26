//
//  ImpressionsAutomation.swift
//  T54 「大家的印象」端到端自检日志（仅 `--impressions-selftest` 启动参数启用，对用户路径零影响）。
//
//  链路：桥层自检（ImpressionsBridge.debugRunSelfTest：夹具 → 过滤 → 三段分组 → 排序 → DB 核对）
//  → UI 自动化（页面加载后断言分组/字段非空）→ 证据 Documents/t54-impressions-selftest.log。
//

import Foundation

@MainActor
enum ImpressionsAutomation {
    static let isEnabled: Bool = ProcessInfo.processInfo.arguments.contains("--impressions-selftest")

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
        add("=== T54 impressions UI selftest start ===")
        add("args: \(ProcessInfo.processInfo.arguments.joined(separator: " "))")
        return true
    }

    static func add(_ line: String) {
        NSLog("[RhodesLink][T54SELFTEST] %@", line)
        lines.append("\(timestamp()) \(line)")
    }

    static func finish(summary: String) {
        let failCount = lines.filter { $0.contains(" FAIL ") || $0.contains("FAIL ") }.count
        let ok = failCount == 0 && !summary.contains("FAIL")
        passed = ok
        resultText = ok ? "自检 PASS" : "自检 FAIL（\(failCount)）"
        add("UI RESULT \(ok ? "PASS" : "FAIL(\(failCount))")")
        add(summary)

        guard let directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return }
        let url = directory.appendingPathComponent("t54-impressions-selftest.log")
        let header = [
            "# RhodesLink iOS T54 大家的印象端到端自检日志",
            "# args: \(ProcessInfo.processInfo.arguments.joined(separator: " "))",
            "# ————————————————————",
        ]
        let text = (header + lines + ["", "## summary", summary]).joined(separator: "\n")
        do {
            try text.write(to: url, atomically: true, encoding: .utf8)
            NSLog("[RhodesLink][T54SELFTEST] log written to %@", url.path)
        } catch {
            NSLog("[RhodesLink][T54SELFTEST] log write failed: %@", error.localizedDescription)
        }
    }

    private static func timestamp() -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss.SSS"
        return formatter.string(from: Date())
    }
}
