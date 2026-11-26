//
//  PolishSelfTestLog.swift
//  T52 加急收尾三项修复的统一自检日志（仅对应启动参数启用；NSLog + 沙盒 Documents 落盘）：
//  - `--avatar-render-selftest`：上传头像在角色列表/详情的真实图片渲染；
//  - `--features-badge-selftest`：功能 Tab 动态/日记未读角标；
//  - `--group-unread-selftest`：群聊非当前群未读 +N / 打开清零。
//
//  证据：Documents/t52-{avatar,features-badge,group-unread}-selftest.log（simctl get_app_container 可取），
//  不依赖 `--selftest` 的 SelfTestLog 落盘开关。
//

import Foundation

enum PolishSelfTestLog {
    /// 任一 T52 自检参数启用即落盘（各测试文件独立，避免互相覆盖）。
    static let isEnabled: Bool = {
        let args = ProcessInfo.processInfo.arguments
        return args.contains("--avatar-render-selftest")
            || args.contains("--features-badge-selftest")
            || args.contains("--group-unread-selftest")
    }()

    private static let lock = NSLock()
    private static var lines: [String] = []

    static func add(_ message: String) {
        NSLog("[RhodesLink][T52] %@", message)
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

    /// 统计已记录日志中包含指定子串的行数（自检断言用，如周期刷新次数）。
    static func count(containing needle: String) -> Int {
        snapshot().filter { $0.contains(needle) }.count
    }

    /// 落盘到 Documents/<fileName>；header 带启动参数与设备版本。
    static func flushToDisk(fileName: String, summary: String) {
        guard isEnabled else { return }
        let header = [
            "# RhodesLink iOS T52 收尾自检日志",
            "# file: \(fileName)",
            "# args: \(ProcessInfo.processInfo.arguments.joined(separator: " "))",
            "# device: \(ProcessInfo.processInfo.operatingSystemVersionString)",
            "# ————————————————————",
        ]
        let body = snapshot().joined(separator: "\n")
        let text = (header + [body, "", "## summary", summary]).joined(separator: "\n")
        guard let directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return }
        let url = directory.appendingPathComponent(fileName)
        do {
            try text.write(to: url, atomically: true, encoding: .utf8)
            NSLog("[RhodesLink][T52] log written to %@", url.path)
        } catch {
            NSLog("[RhodesLink][T52] log write failed: %@", error.localizedDescription)
        }
    }

    private static func timestamp() -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss.SSS"
        return formatter.string(from: Date())
    }
}
