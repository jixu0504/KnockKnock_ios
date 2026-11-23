//
//  DiaryAutoAutomation.swift
//  T61「日记生成管理」端到端自检（仅 `--diary-auto-selftest` 启动参数启用，对用户路径零影响）。
//
//  链路：Kotlin `DiaryAutoGlue.debugRunSelfTest`（IosAppGraph 启动钩子：mock 内容走真实
//  提示词 → 规范化 → diaries 表 → 同日幂等 → 开关/tick 断言，写 Application Support/
//  t61-diary-auto-selftest.log）→ 本文件驱动真实 UI（管理页列表/总开关/全部开启/立即生成），
//  断言列表 147 位、开关持久化、生成后最近日记时间更新，写 Documents/diary-auto-selftest.log。
//

import Foundation

@MainActor
enum DiaryAutoAutomation {
    static let isEnabled: Bool = ProcessInfo.processInfo.arguments.contains("--diary-auto-selftest")

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
        add("=== T61 diary-auto UI selftest start ===")
        add("args: \(ProcessInfo.processInfo.arguments.joined(separator: " "))")
        return true
    }

    static func add(_ line: String) {
        NSLog("[RhodesLink][T61SELFTEST] %@", line)
        lines.append("\(timestamp()) \(line)")
    }

    /// 写结论：FAIL 行数 = 0 视为通过。
    static func finish(summary: String) {
        let failCount = lines.filter { $0.contains(" FAIL ") }.count
        let ok = failCount == 0
        passed = ok
        resultText = ok ? "自检 PASS" : "自检 FAIL（\(failCount)）"
        add("UI RESULT \(ok ? "PASS" : "FAIL(\(failCount))")")
        add(summary)

        guard let directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return }
        let url = directory.appendingPathComponent("diary-auto-selftest.log")
        let header = [
            "# RhodesLink iOS T61 干员日记自动生成端到端自检日志",
            "# args: \(ProcessInfo.processInfo.arguments.joined(separator: " "))",
            "# ————————————————————",
        ]
        let text = (header + lines + ["", "## summary", summary]).joined(separator: "\n")
        do {
            try text.write(to: url, atomically: true, encoding: .utf8)
            NSLog("[RhodesLink][T61SELFTEST] log written to %@", url.path)
        } catch {
            NSLog("[RhodesLink][T61SELFTEST] log write failed: %@", error.localizedDescription)
        }
    }

    private static func timestamp() -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss.SSS"
        return formatter.string(from: Date())
    }
}
