//
//  SettingsExtraAutomation.swift
//  T53 设置余项端到端自动化验证（仅启动参数启用，对用户路径零影响）：
//
//   `--settings-extra-selftest`          写入阶段：调试日志打点 → 读取非空/行解析 → 导出文件生成
//                                       → 清空后重新打点；通知 4 键写入→读回；外观 3 键
//                                       （主题/聊天背景/字号）写入→读回；flushDefaults 强制落盘。
//   `--settings-extra-verify`            冷启动校验阶段：不写值，只读并断言上一轮写入已持久化
//                                       （Debug 日志文件仍在 + 可再导出；通知键；外观键 + SettingsStore 读回）。
//   `--settings-extra-restore`           恢复阶段：外观回 system/classic/standard、通知开关全开、flush。
//
//  证据：SelfTestLog（`--selftest` 时落盘 Documents/ios-shell-selftest.log）；
//  外部证据：NSUserDefaults plist（simctl get_app_container + plutil -p）。
//

import Foundation

@MainActor
enum SettingsExtraAutomation {
    private static let arguments = ProcessInfo.processInfo.arguments
    private static let writeEnabled = arguments.contains("--settings-extra-selftest")
    private static let verifyEnabled = arguments.contains("--settings-extra-verify")
    private static let restoreEnabled = arguments.contains("--settings-extra-restore")
    private static var didRun = false

    /// 写入阶段的期望值（验证阶段复用）
    static let expectedAppearance = "dark"
    static let expectedBackground = "dusk"
    static let expectedFontSize = "large"
    static let expectedLogMarker = "T53-SELFTEST-LOG-MARKER"
    static let expectedExportedMarker = "T53-SELFTEST-EXPORTED-MARKER"

    /// 通知键期望值（默认值之外的混合状态：证明每个键独立可写）
    static let expectedPrivateReply = false
    static let expectedGroupReply = true
    static let expectedMoments = false
    static let expectedDiary = true

    static func runIfNeeded(environment: AppEnvironment) async {
        guard writeEnabled || verifyEnabled || restoreEnabled else { return }
        guard !didRun else { return }
        didRun = true
        let bridge = environment.bridge
        let client = SettingsExtraClient.shared

        if restoreEnabled {
            restore(environment: environment, bridge: bridge, client: client)
            return
        }
        if verifyEnabled {
            await verify(environment: environment, bridge: bridge, client: client)
            return
        }
        await write(environment: environment, bridge: bridge, client: client)
    }

    // MARK: - 写入阶段

    private static func write(environment: AppEnvironment, bridge: AppGraphClient, client: SettingsExtraClient) async {
        SelfTestLog.add("=== settings-extra selftest write start ===")
        var passCount = 0
        var failCount = 0
        func check(_ name: String, _ condition: Bool, _ detail: String) {
            if condition { passCount += 1 } else { failCount += 1 }
            SelfTestLog.add("settings-extra-check \(condition ? "PASS" : "FAIL") \(name) \(detail)")
        }

        // 0. 权限状态只读（不触发授权弹窗）
        let permission = await client.notificationAuthorizationStatus()
        SelfTestLog.add(
            "settings-extra notifications permission status=\(permission.status)/\(permission.statusName) " +
            "granted=\(permission.granted) provisional=\(permission.provisional) error='\(permission.error)'"
        )
        check(
            "notifications.permission-readable",
            permission.status >= 0 && permission.status <= 4,
            "status=\(permission.status)/\(permission.statusName) granted=\(permission.granted)"
        )

        // 1. 调试日志：打点 → 读取非空 + 行解析 → 导出文件
        client.debugLogWrite(level: "INFO", tag: "T53SELFTEST", message: expectedLogMarker)
        client.debugLogWrite(
            level: "ERROR",
            tag: "T53SELFTEST",
            message: "\(expectedExportedMarker) 错误级别行（导出内容应包含本行）"
        )
        let snapshot = client.debugLogSnapshot(limit: 200)
        let markerEntry = snapshot.entries.first { $0.message.contains(expectedLogMarker) }
        check(
            "debug-logs.read-non-empty",
            !snapshot.entries.isEmpty && markerEntry != nil && !snapshot.path.isEmpty,
            "lines=\(snapshot.totalLines) shown=\(snapshot.entries.count) bytes=\(snapshot.bytes) path=\(snapshot.path)"
        )
        check(
            "debug-logs.parse-fields",
            markerEntry?.level == "INFO" && markerEntry?.tag == "T53SELFTEST" && (markerEntry?.time.isEmpty == false),
            "level=\(markerEntry?.level ?? "-") tag=\(markerEntry?.tag ?? "-") time=\(markerEntry?.time ?? "-")"
        )
        check(
            "debug-logs.files-listed",
            !snapshot.files.isEmpty,
            "files=\(snapshot.files.joined(separator: ","))"
        )

        let exportedURL = client.debugLogExport()
        var exportedSize = 0
        if let exportedURL,
           let attributes = try? FileManager.default.attributesOfItem(atPath: exportedURL.path),
           let size = attributes[.size] as? NSNumber {
            exportedSize = size.intValue
        }
        let exportedContent = exportedURL.flatMap { try? String(contentsOf: $0, encoding: .utf8) } ?? ""
        check(
            "debug-logs.export-file",
            exportedURL != nil && exportedSize > 0 && exportedContent.contains(expectedExportedMarker),
            "path=\(exportedURL?.path ?? "-") size=\(exportedSize) containsMarker=\(exportedContent.contains(expectedExportedMarker))"
        )

        // 2. 清空 → 空文件 → 再打点（证明清空真实生效且日志仍可用）
        let cleared = client.debugLogClear()
        let afterClear = client.debugLogSnapshot(limit: 50)
        client.debugLogWrite(level: "INFO", tag: "T53SELFTEST", message: "\(expectedLogMarker) cleared-then-rewritten")
        let afterRewrite = client.debugLogSnapshot(limit: 50)
        check(
            "debug-logs.clear-and-rewrite",
            cleared && afterClear.totalLines == 0 && afterRewrite.entries.contains(where: { $0.message.contains("cleared-then-rewritten") }),
            "cleared=\(cleared) linesAfterClear=\(afterClear.totalLines) linesAfterRewrite=\(afterRewrite.totalLines)"
        )

        // 3. 通知偏好 4 键：写入 → 读回
        let saves: [(String, Bool)] = [
            ("private_reply", expectedPrivateReply),
            ("group_reply", expectedGroupReply),
            ("moments", expectedMoments),
            ("diary", expectedDiary),
        ]
        var saveErrors: [String] = []
        for (scope, enabled) in saves {
            let result = client.saveNotificationPref(scope: scope, enabled: enabled)
            if !result.isEmpty { saveErrors.append("\(scope):\(result)") }
        }
        let prefs = client.notificationPrefs()
        check(
            "notifications.prefs-save-readback",
            saveErrors.isEmpty
                && prefs.privateReply == expectedPrivateReply
                && prefs.groupReply == expectedGroupReply
                && prefs.moments == expectedMoments
                && prefs.diary == expectedDiary,
            "errors=\(saveErrors.joined(separator: ",")) snapshot=\(client.notificationPrefsSnapshot()) " +
            "keys=\(prefs.settingsKeys.joined(separator: ","))"
        )
        check(
            "notifications.keys-non-empty",
            prefs.settingsKeys.count == 4,
            "keys=\(prefs.settingsKeys.joined(separator: ","))"
        )

        // 4. 外观 3 键：写入 → 读回（经 SettingsStore，与用户路径一致）
        environment.settings.setAppearance(.dark)
        environment.settings.setChatBackground(.dusk)
        environment.settings.setFontSize(.large)
        let readBack = (
            appearance: bridge.settingsString(key: SettingsStore.appearanceKey),
            background: bridge.settingsString(key: ChatBackgroundStyle.settingsKey),
            fontSize: bridge.settingsString(key: SettingsStore.fontSizeKey)
        )
        check(
            "appearance.keys-save-readback",
            readBack.appearance == expectedAppearance
                && readBack.background == expectedBackground
                && readBack.fontSize == expectedFontSize,
            "appearance=\(readBack.appearance) background=\(readBack.background) font=\(readBack.fontSize) " +
            "store: appearance=\(environment.settings.appearance.rawValue) " +
            "background=\(environment.settings.chatBackground.rawValue) font=\(environment.settings.fontSize.rawValue)"
        )

        // 5. 强制落盘（冷启动 verify 阶段依赖）
        let flushed = bridge.flushSettingsDefaults()
        SelfTestLog.add("settings-extra flushDefaults=\(flushed) prefsSnapshot=\(client.notificationPrefsSnapshot())")

        let summary = "settings-extra selftest write summary pass=\(passCount) fail=\(failCount) " +
            "appearance=\(expectedAppearance) background=\(expectedBackground) font=\(expectedFontSize) " +
            "notify(private=\(expectedPrivateReply),group=\(expectedGroupReply),moments=\(expectedMoments),diary=\(expectedDiary)) " +
            "exported=\(exportedURL?.lastPathComponent ?? "-")"
        SelfTestLog.add("=== settings-extra selftest write done pass=\(passCount) fail=\(failCount) ===")
        SelfTestLog.flushToDisk(summary: summary)
    }

    // MARK: - 冷启动校验阶段（只读 + 断言持久化）

    private static func verify(environment: AppEnvironment, bridge: AppGraphClient, client: SettingsExtraClient) async {
        SelfTestLog.add("=== settings-extra selftest verify start (cold start, read-only) ===")
        var passCount = 0
        var failCount = 0
        func check(_ name: String, _ condition: Bool, _ detail: String) {
            if condition { passCount += 1 } else { failCount += 1 }
            SelfTestLog.add("settings-extra-check \(condition ? "PASS" : "FAIL") \(name) \(detail)")
        }

        // 1. 调试日志文件跨进程仍在 + 可重新导出
        let snapshot = client.debugLogSnapshot(limit: 200)
        check(
            "persisted.debug-logs",
            snapshot.totalLines > 0 && snapshot.entries.contains(where: { $0.message.contains(expectedLogMarker) }),
            "lines=\(snapshot.totalLines) bytes=\(snapshot.bytes) path=\(snapshot.path)"
        )
        let reExport = client.debugLogExport()
        var reExportSize = 0
        if let reExport,
           let attributes = try? FileManager.default.attributesOfItem(atPath: reExport.path),
           let size = attributes[.size] as? NSNumber {
            reExportSize = size.intValue
        }
        check(
            "persisted.debug-logs-reexport",
            reExport != nil && reExportSize > 0,
            "path=\(reExport?.path ?? "-") size=\(reExportSize)"
        )

        // 2. 通知偏好 4 键跨进程保持
        let prefs = client.notificationPrefs()
        check(
            "persisted.notification-prefs",
            prefs.privateReply == expectedPrivateReply
                && prefs.groupReply == expectedGroupReply
                && prefs.moments == expectedMoments
                && prefs.diary == expectedDiary,
            "snapshot=\(client.notificationPrefsSnapshot())"
        )

        // 3. 外观 3 键跨进程保持（SettingsStore 启动时读取 → 与 bridge 原始键一致）
        let rawAppearance = bridge.settingsString(key: SettingsStore.appearanceKey)
        let rawBackground = bridge.settingsString(key: ChatBackgroundStyle.settingsKey)
        let rawFontSize = bridge.settingsString(key: SettingsStore.fontSizeKey)
        check(
            "persisted.appearance-keys",
            rawAppearance == expectedAppearance && rawBackground == expectedBackground && rawFontSize == expectedFontSize,
            "appearance=\(rawAppearance) background=\(rawBackground) font=\(rawFontSize)"
        )
        check(
            "persisted.appearance-store-readback",
            environment.settings.appearance.rawValue == expectedAppearance
                && environment.settings.chatBackground.rawValue == expectedBackground
                && environment.settings.fontSize.rawValue == expectedFontSize,
            "store appearance=\(environment.settings.appearance.rawValue) " +
            "background=\(environment.settings.chatBackground.rawValue) font=\(environment.settings.fontSize.rawValue)"
        )
        check(
            "persisted.dark-mode-synced",
            bridge.settingsSnapshot().contains("dark=true"),
            "snapshot=\(bridge.settingsSnapshot())"
        )

        let summary = "settings-extra selftest verify summary pass=\(passCount) fail=\(failCount) (cold-start persisted values)"
        SelfTestLog.add("=== settings-extra selftest verify done pass=\(passCount) fail=\(failCount) ===")
        SelfTestLog.flushToDisk(summary: summary)
    }

    // MARK: - 恢复阶段

    private static func restore(environment: AppEnvironment, bridge: AppGraphClient, client: SettingsExtraClient) {
        SelfTestLog.add("=== settings-extra selftest restore start ===")
        environment.settings.setAppearance(.system)
        environment.settings.setChatBackground(.classic)
        environment.settings.setFontSize(.standard)
        for scope in ["private_reply", "group_reply", "moments", "diary"] {
            _ = client.saveNotificationPref(scope: scope, enabled: true)
        }
        let flushed = bridge.flushSettingsDefaults()
        SelfTestLog.add(
            "settings-extra restore appearance=\(environment.settings.appearance.rawValue) " +
            "background=\(environment.settings.chatBackground.rawValue) font=\(environment.settings.fontSize.rawValue) " +
            "prefsSnapshot=\(client.notificationPrefsSnapshot()) flush=\(flushed)"
        )
        SelfTestLog.add("=== settings-extra selftest restore done ===")
        SelfTestLog.flushToDisk(summary: "settings-extra restore appearance=system background=classic font=standard notify=all-on")
    }
}
