//
//  SettingsExtraClient.swift
//  T53 Swift 侧包装：调试日志桥（DebugLogBridge）+ 通知设置桥（NotificationBridge）。
//
//  - Kotlin 类型只在本文件出现，SwiftUI 只接触本文件的 struct / async 方法；
//  - 桥对象经 `SettingsExtraBridgesKt.makeSettingsExtraBridges()` 创建一次（不扩展 IosAppGraph，
//    避免与并行批次写同一文件）；
//  - 通知偏好 4 键：notify_private_reply / notify_group_reply / notify_moments / notify_diary
//    （NSUserDefaults，默认 true，冷启动保持）；
//  - 调试日志：Application Support/logs/rhodes.log（IosDebugLogger），行格式
//    `2026-11-23T05:44:00.123Z [INFO] [tag] message`。
//

import Foundation
import Shared

// MARK: - 调试日志

struct DebugLogEntryItem: Identifiable, Hashable {
    let id: Int
    /// shared 侧 ISO8601 时间戳（`2026-11-23T05:44:00.123Z`）。
    let time: String
    let level: String
    let tag: String
    let message: String

    /// 本地时间显示 HH:mm:ss.SSS（解析失败回退原始时间戳）。
    var timeText: String {
        guard !time.isEmpty else { return "—" }
        let iso = ISO8601DateFormatter()
        iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let date = iso.date(from: time) ?? ISO8601DateFormatter().date(from: time)
        guard let date else { return time }
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.dateFormat = "HH:mm:ss.SSS"
        return formatter.string(from: date)
    }

    var levelIsError: Bool { level == "ERROR" || level == "FATAL" }
    var levelIsWarn: Bool { level == "WARN" || level == "WARNING" }
}

struct DebugLogSnapshotItem {
    let entries: [DebugLogEntryItem]
    let path: String
    let files: [String]
    let bytes: Int64
    let totalLines: Int
    let truncated: Bool

    var bytesText: String {
        if bytes >= 1024 * 1024 { return String(format: "%.2f MB", Double(bytes) / 1024 / 1024) }
        if bytes >= 1024 { return String(format: "%.1f KB", Double(bytes) / 1024) }
        return "\(bytes) B"
    }
}

// MARK: - 通知

struct NotificationPrefsItem {
    let privateReply: Bool
    let groupReply: Bool
    let moments: Bool
    let diary: Bool
    let settingsKeys: [String]
}

struct NotificationPermissionItem {
    let status: Int
    let statusName: String
    let granted: Bool
    let provisional: Bool
    let error: String

    var label: String {
        if !error.isEmpty { return "请求失败：\(error)" }
        switch status {
        case 0: return "未决定（尚未请求授权）"
        case 1: return "已拒绝（受限）"
        case 2: return "已授权（横幅 / 声音 / 角标）"
        case 3: return "静默授权（provisional，进通知中心不弹横幅）"
        case 4: return "临时授权（ephemeral）"
        default: return statusName.isEmpty ? "未知（\(status)）" : statusName
        }
    }
}

// MARK: - 客户端

@MainActor
final class SettingsExtraClient {
    static let shared = SettingsExtraClient()

    private let bridges: SettingsExtraBridges

    private init() {
        bridges = SettingsExtraBridgesKt.makeSettingsExtraBridges()
        NSLog("[RhodesLink][T53] SettingsExtraBridges created")
    }

    // MARK: 调试日志

    func debugLogSnapshot(limit: Int = 500) -> DebugLogSnapshotItem {
        let dto = bridges.debugLogs().snapshot(limit: Int32(limit))
        let entries = dto.entries.enumerated().map { index, entry in
            DebugLogEntryItem(
                id: index,
                time: entry.time,
                level: entry.level,
                tag: entry.tag,
                message: entry.message
            )
        }
        let files = dto.filesCsv.split(separator: "\n").map(String.init)
        return DebugLogSnapshotItem(
            entries: entries,
            path: dto.path,
            files: files,
            bytes: dto.bytes,
            totalLines: Int(dto.totalLines),
            truncated: dto.truncated
        )
    }

    /// 导出日志副本，返回可分享的文件 URL（无日志/失败返回 nil）。
    func debugLogExport() -> URL? {
        let path = bridges.debugLogs().exportToFile()
        guard !path.isEmpty else { return nil }
        return URL(fileURLWithPath: path)
    }

    func debugLogClear() -> Bool {
        bridges.debugLogs().clearAll()
    }

    func debugLogWrite(level: String, tag: String, message: String) {
        bridges.debugLogs().write(level: level, tag: tag, message: message)
    }

    func debugLogPathsSummary() -> String {
        bridges.debugLogs().pathsSummary()
    }

    // MARK: 通知偏好

    func notificationPrefs() -> NotificationPrefsItem {
        let dto = bridges.notifications().readPrefs()
        return NotificationPrefsItem(
            privateReply: dto.privateReply,
            groupReply: dto.groupReply,
            moments: dto.moments,
            diary: dto.diary,
            settingsKeys: dto.settingsKeysCsv.split(separator: "\n").map(String.init)
        )
    }

    /// 空串 = 成功。
    func saveNotificationPref(scope: String, enabled: Bool) -> String {
        bridges.notifications().savePref(scope: scope, enabled: enabled)
    }

    func notificationPrefsSnapshot() -> String {
        bridges.notifications().prefsSnapshot()
    }

    // MARK: 通知权限

    func notificationAuthorizationStatus() async -> NotificationPermissionItem {
        await withCheckedContinuation { continuation in
            bridges.notifications().authorizationStatus { status in
                continuation.resume(returning: Self.mapPermission(status))
            }
        }
    }

    func requestFullNotificationAuthorization() async -> NotificationPermissionItem {
        await withCheckedContinuation { continuation in
            bridges.notifications().requestFullAuthorization { status in
                continuation.resume(returning: Self.mapPermission(status))
            }
        }
    }

    func requestProvisionalNotificationAuthorization() async -> NotificationPermissionItem {
        await withCheckedContinuation { continuation in
            bridges.notifications().requestProvisionalAuthorization { status in
                continuation.resume(returning: Self.mapPermission(status))
            }
        }
    }

    func openNotificationSystemSettings() {
        bridges.notifications().openSystemSettings()
    }

    private static func mapPermission(_ status: NotificationPermissionStatus) -> NotificationPermissionItem {
        NotificationPermissionItem(
            status: Int(status.status),
            statusName: status.statusName,
            granted: status.granted,
            provisional: status.provisional,
            error: status.error
        )
    }
}
