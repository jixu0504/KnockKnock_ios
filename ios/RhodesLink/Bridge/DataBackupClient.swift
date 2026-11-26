//
//  DataBackupClient.swift
//  T38 数据与备份：shared 桥的 Swift 薄包装（不向上暴露 Kotlin 类型）。
//
//  分工：
//  - 完整备份导出/两阶段恢复：复用 T29 `BackupBridge`（rhodes.db 文件导出 + `.restore-pending` 冷启动换入）；
//  - 角色卡导出/检视/导入：T38 新增 `DataBackupBridge`（rhodes-operator v1，`OperatorCard*` 域层）；
//  - 聊天记录导出/检视/导入：T38 新增 `DataBackupBridge`（rhodes-chat v1，`ChatExportWriter` / `ChatMergeRestorer`）；
//  - 文件校验/QSH-256/SQLite 自检：`BackupSelfCheck.swift`（Speak 侧 CryptoKit + SQLite3 C API）。
//
//  所有 Kotlin 回调都在 Main 队列触发（桥约定）；这里统一包成 async 函数 + `Result<T, BridgeError>`。
//

import Foundation
import Shared

// MARK: - Swift 侧模型

struct FullBackupFileInfo {
    let path: String
    let sizeBytes: Int64
    let sha256: String
    let inspection: BackupSQLiteInspection

    var sizeText: String { ByteCountFormatter.string(fromByteCount: sizeBytes, countStyle: .file) }
}

struct PendingRestoreState {
    /// 已暂存待恢复文件路径（冷启动换入）
    let pendingPath: String
    /// 暂存后对 pending 文件的校验结果（SQLite 头 / integrity_check / 表计数）
    let inspection: BackupSQLiteInspection
    /// 当前运行库（换入前的现网数据）计数，供恢复后自检对比
    let currentCounts: [String: Int]
}

struct OperatorCardExportInfo {
    let path: String
    let sizeBytes: Int64
    let sha256: String
    let operatorId: String
    let operatorName: String
    let relationshipCount: Int
    let hasAvatar: Bool
    let format: String
    let formatVersion: Int

    var sizeText: String { ByteCountFormatter.string(fromByteCount: sizeBytes, countStyle: .file) }
}

struct OperatorCardPreview {
    let operatorId: String
    let operatorName: String
    let title: String
    let relationshipCount: Int
    let hasAvatar: Bool
    let promptSlotCount: Int
    let summary: String
}

struct OperatorCardImportInfo {
    let operatorId: String
    let operatorName: String
    let createdNew: Bool
    let importedRelationships: Int
    let skippedRelationships: Int
    let mode: String
}

struct ChatExportInfo {
    let path: String
    let sizeBytes: Int64
    let sha256: String
    let sessionId: String
    let sessionTitle: String
    let messageCount: Int
    let mediaCount: Int
    let archiveCount: Int
    let historySegmentCount: Int
    let format: String
    let formatVersion: Int

    var sizeText: String { ByteCountFormatter.string(fromByteCount: sizeBytes, countStyle: .file) }
}

struct ChatExportPreview {
    let sessionId: String
    let sessionTitle: String
    let operatorName: String
    let messageCount: Int
    let mediaCount: Int
    let archiveCount: Int
    let historySegmentCount: Int
    let alerts: [String]
}

struct ChatImportOutcome {
    let success: Bool
    let stage: String
    let sessionId: String
    let importedMessages: Int
    let dedupedMessages: Int
    let importedMedia: Int
    let importedArchives: Int
    let importedHistorySegments: Int
    let reason: String
    let alerts: [String]

    var summaryText: String {
        if !success { return reason.isEmpty ? "导入失败" : reason }
        return "新增 \(importedMessages) 条 · 去重 \(dedupedMessages) 条 · 媒体 \(importedMedia) 个 · 存档 \(importedArchives) 段 · 历史片段 \(importedHistorySegments) 段"
    }
}

// MARK: - 备份文件工具（目录/临时文件/SHA-256）

enum BackupFiles {
    /// App 沙盒 Documents/BackupExports（导出的完整备份/角色卡/聊天记录都落这里，再由 fileExporter 另存到 Files）
    static var exportDirectory: URL {
        let base = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let dir = base.appendingPathComponent("BackupExports", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    static func timestamp(_ date: Date = Date()) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyyMMdd-HHmmss"
        return formatter.string(from: date)
    }

    /// 文件大小 + SHA-256（小写 hex）；读不到返回 nil。
    static func digest(path: String) -> (size: Int64, sha256: String)? {
        guard let data = FileManager.default.contents(atPath: path) else { return nil }
        return (Int64(data.count), BackupSHA256.hex(data))
    }

    /// 把 fileImporter 选中的外部文件复制进 App 沙盒临时目录（读完立即释放 security scope）。
    static func stageImportedFile(from url: URL) throws -> URL {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let name = url.lastPathComponent.isEmpty ? "import.bin" : url.lastPathComponent
        let target = FileManager.default.temporaryDirectory
            .appendingPathComponent("BackupImport-\(UUID().uuidString)-\(name)")
        if FileManager.default.fileExists(atPath: target.path) {
            try? FileManager.default.removeItem(at: target)
        }
        try FileManager.default.copyItem(at: url, to: target)
        return target
    }
}

// MARK: - 桥接客户端扩展（T38）

extension AppGraphClient {

    // ---------------------------------------------------------------- 完整备份

    /// 导出完整备份（rhodes.db 文件快照）到指定路径；返回大小/SHA-256/SQLite 自检信息。
    /// 导出前先尝试 WAL checkpoint（把 -wal 内容并回主库），降低“运行中复制”漏最近写入的风险。
    func exportFullBackup(destinationURL: URL) async -> Result<FullBackupFileInfo, BridgeError> {
        BackupSQLite.checkpoint(databasePath: databasePath)
        try? FileManager.default.createDirectory(
            at: destinationURL.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        if FileManager.default.fileExists(atPath: destinationURL.path) {
            try? FileManager.default.removeItem(at: destinationURL)
        }

        let (stage, message): (String, String) = await withCheckedContinuation { continuation in
            backupBridge.exportToFile(destinationPath: destinationURL.path) { progress in
                if progress.stage == "done" || progress.stage == "failed" {
                    continuation.resume(returning: (progress.stage, progress.message))
                }
            }
        }
        guard stage == "done" else {
            return .failure(BridgeError(message: "备份导出失败：\(message)"))
        }
        guard let digest = BackupFiles.digest(path: destinationURL.path) else {
            return .failure(BridgeError(message: "备份文件写入后不可读：\(destinationURL.path)"))
        }
        let inspection = BackupSQLite.inspect(path: destinationURL.path, immutable: true)
        BackupLog.add("full backup exported path=\(destinationURL.lastPathComponent) size=\(digest.size) sha=\(digest.sha256.prefix(16))… integrity=\(inspection.integrityResult) tables=\(inspection.tableCount) counts=\(inspection.counts)")
        return .success(FullBackupFileInfo(
            path: destinationURL.path,
            sizeBytes: digest.size,
            sha256: digest.sha256,
            inspection: inspection
        ))
    }

    var hasPendingRestore: Bool { backupBridge.hasPendingRestore() }
    var pendingRestorePath: String { backupBridge.pendingRestorePath() }

    /// 两阶段恢复第一步：校验源文件（SQLite 头）→ 复制为 `rhodes.db.restore-pending`，返回待恢复状态。
    /// 真正换入发生在**下次冷启动**（`IosAppGraph` 打开 DB 之前）。
    func stageFullRestore(sourcePath: String) async -> Result<PendingRestoreState, BridgeError> {
        let (stage, message): (String, String) = await withCheckedContinuation { continuation in
            backupBridge.restoreFromFile(sourcePath: sourcePath) { progress in
                if progress.stage == "staged" || progress.stage == "failed" {
                    continuation.resume(returning: (progress.stage, progress.message))
                }
            }
        }
        guard stage == "staged" else {
            BackupLog.add("full restore staging failed source=\(sourcePath) reason=\(message)")
            return .failure(BridgeError(message: message))
        }
        let pending = backupBridge.pendingRestorePath()
        let inspection = BackupSQLite.inspect(path: pending, immutable: true)
        let current = BackupSQLite.inspect(path: databasePath).counts
        BackupSelfCheck.recordStagedRestore(
            pendingPath: pending,
            dbPath: databasePath,
            inspection: inspection,
            currentCounts: current
        )
        BackupLog.add("full restore staged pending=\(pending) size=\(inspection.fileSize) integrity=\(inspection.integrityResult) counts=\(inspection.counts)")
        guard inspection.headerValid, inspection.integrityResult.lowercased() == "ok" else {
            // 暂存文件自检不通过：删除 pending，避免用户看到失败提示却在下次冷启动被换入。
            _ = dataBackupBridge.cancelPendingRestore()
            BackupSelfCheck.clearStagedMarker()
            return .failure(BridgeError(message: "备份自检未通过：\(inspection.integrityResult)"))
        }
        return .success(PendingRestoreState(
            pendingPath: pending,
            inspection: inspection,
            currentCounts: current
        ))
    }

    /// 撤销已暂存、尚未换入的恢复（删除 pending 文件）。
    @discardableResult
    func cancelPendingRestore() -> Bool {
        BackupSelfCheck.clearStagedMarker()
        return dataBackupBridge.cancelPendingRestore()
    }

    /// 恢复后自检：若上次暂存的恢复已在本次冷启动换入，比对「换入前期望计数 vs 现网计数」。
    /// 结果持久化（设置页展示），返回最新一次报告；无待处理恢复时返回 nil。
    func evaluateRestoreSelfCheck() -> RestoreSelfCheckReport? {
        guard let report = BackupSelfCheck.evaluateAfterLaunch(dbPath: databasePath) else { return nil }
        BackupLog.add("restore self-check passed=\(report.passed) mismatches=\(report.mismatches) actual=\(report.actualCounts)")
        return report
    }

    // ---------------------------------------------------------------- 角色卡

    func exportOperatorCard(operatorId: String, to destinationURL: URL) async -> Result<OperatorCardExportInfo, BridgeError> {
        await withCheckedContinuation { continuation in
            dataBackupBridge.exportOperatorCard(
                operatorId: operatorId,
                destinationPath: destinationURL.path
            ) { info, error in
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else if let info {
                    continuation.resume(returning: .success(OperatorCardExportInfo(
                        path: info.destinationPath,
                        sizeBytes: info.sizeBytes,
                        sha256: info.sha256,
                        operatorId: info.operatorId,
                        operatorName: info.operatorName,
                        relationshipCount: Int(info.relationshipCount),
                        hasAvatar: info.hasAvatar,
                        format: info.format,
                        formatVersion: Int(info.formatVersion)
                    )))
                } else {
                    continuation.resume(returning: .failure(BridgeError(message: "角色卡导出失败")))
                }
            }
        }
    }

    func inspectOperatorCard(sourcePath: String) async -> Result<OperatorCardPreview, BridgeError> {
        await withCheckedContinuation { continuation in
            dataBackupBridge.inspectOperatorCard(sourcePath: sourcePath) { preview, error in
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else if let preview {
                    continuation.resume(returning: .success(OperatorCardPreview(
                        operatorId: preview.operatorId,
                        operatorName: preview.operatorName,
                        title: preview.title,
                        relationshipCount: Int(preview.relationshipCount),
                        hasAvatar: preview.hasAvatar,
                        promptSlotCount: Int(preview.promptSlotCount),
                        summary: preview.summary
                    )))
                } else {
                    continuation.resume(returning: .failure(BridgeError(message: "角色包校验失败")))
                }
            }
        }
    }

    func importOperatorCard(
        sourcePath: String,
        mode: String = "NEW",
        targetOperatorId: String = ""
    ) async -> Result<OperatorCardImportInfo, BridgeError> {
        await withCheckedContinuation { continuation in
            dataBackupBridge.importOperatorCard(
                sourcePath: sourcePath,
                mode: mode,
                targetOperatorId: targetOperatorId
            ) { info, error in
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else if let info {
                    continuation.resume(returning: .success(OperatorCardImportInfo(
                        operatorId: info.operatorId,
                        operatorName: info.operatorName,
                        createdNew: info.createdNew,
                        importedRelationships: Int(info.importedRelationships),
                        skippedRelationships: Int(info.skippedRelationships),
                        mode: info.mode
                    )))
                } else {
                    continuation.resume(returning: .failure(BridgeError(message: "角色卡导入失败")))
                }
            }
        }
    }

    // ---------------------------------------------------------------- 聊天记录

    func exportChat(sessionId: String, to destinationURL: URL) async -> Result<ChatExportInfo, BridgeError> {
        await withCheckedContinuation { continuation in
            dataBackupBridge.exportChat(
                sessionId: sessionId,
                destinationPath: destinationURL.path
            ) { info, error in
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else if let info {
                    continuation.resume(returning: .success(ChatExportInfo(
                        path: info.destinationPath,
                        sizeBytes: info.sizeBytes,
                        sha256: info.sha256,
                        sessionId: info.sessionId,
                        sessionTitle: info.sessionTitle,
                        messageCount: Int(info.messageCount),
                        mediaCount: Int(info.mediaCount),
                        archiveCount: Int(info.archiveCount),
                        historySegmentCount: Int(info.historySegmentCount),
                        format: info.format,
                        formatVersion: Int(info.formatVersion)
                    )))
                } else {
                    continuation.resume(returning: .failure(BridgeError(message: "聊天记录导出失败")))
                }
            }
        }
    }

    func inspectChatExport(sourcePath: String) async -> Result<ChatExportPreview, BridgeError> {
        await withCheckedContinuation { continuation in
            dataBackupBridge.inspectChatExport(sourcePath: sourcePath) { preview, error in
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else if let preview {
                    continuation.resume(returning: .success(ChatExportPreview(
                        sessionId: preview.sessionId,
                        sessionTitle: preview.sessionTitle,
                        operatorName: preview.operatorName,
                        messageCount: Int(preview.messageCount),
                        mediaCount: Int(preview.mediaCount),
                        archiveCount: Int(preview.archiveCount),
                        historySegmentCount: Int(preview.historySegmentCount),
                        alerts: preview.alerts
                    )))
                } else {
                    continuation.resume(returning: .failure(BridgeError(message: "聊天包校验失败")))
                }
            }
        }
    }

    /// 聊天记录合并导入；`onProgress(stage, detail)` 用于 UI 进度展示。
    func importChat(
        sourcePath: String,
        onProgress: @escaping (String, String) -> Void
    ) async -> Result<ChatImportOutcome, BridgeError> {
        await withCheckedContinuation { continuation in
            dataBackupBridge.importChat(
                sourcePath: sourcePath,
                onProgress: { progress in
                    onProgress(progress.stage, progress.detail)
                }
            ) { result, error in
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else if let result {
                    continuation.resume(returning: .success(ChatImportOutcome(
                        success: result.success,
                        stage: result.stage,
                        sessionId: result.sessionId,
                        importedMessages: Int(result.importedMessages),
                        dedupedMessages: Int(result.dedupedMessages),
                        importedMedia: Int(result.importedMedia),
                        importedArchives: Int(result.importedArchives),
                        importedHistorySegments: Int(result.importedHistorySegments),
                        reason: result.reason,
                        alerts: result.alerts
                    )))
                } else {
                    continuation.resume(returning: .failure(BridgeError(message: "聊天记录导入失败")))
                }
            }
        }
    }
}

// MARK: - T58 Markdown 聊天记录导入

/// 预览样例（Markdown 解析结果的首尾消息）。
struct MarkdownChatSample: Identifiable {
    let id = UUID()
    let senderName: String
    let isMe: Bool
    let text: String
    let timestamp: Int64

    var timeText: String {
        guard timestamp > 0 else { return "—" }
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.dateFormat = "yyyy/M/d HH:mm"
        return formatter.string(from: Date(timeIntervalSince1970: Double(timestamp) / 1000))
    }
}

/// Markdown 解析预览（标题/角色/条数/首尾样例）。
struct MarkdownChatPreview {
    let title: String
    let operatorName: String
    let userName: String
    let sourceFormat: String
    let messageCount: Int
    let dayCount: Int
    let samples: [MarkdownChatSample]
    let firstMessageText: String
    let lastMessageText: String

    var formatText: String {
        switch sourceFormat {
        case "rhodes-markdown": return "RhodesLink Markdown 导出"
        case "bold-name": return "变体：加粗称呼"
        case "plain-colon": return "变体：称呼+冒号"
        case "text-timestamp": return "变体：带日期时间"
        default: return sourceFormat
        }
    }
}

/// 解析后的 Markdown 文档句柄（对 SwiftUI 隐藏 Kotlin 类型）。
final class MarkdownParsedDocument {
    fileprivate let handle: ParsedMarkdownHandle
    let preview: MarkdownChatPreview

    fileprivate init(handle: ParsedMarkdownHandle) {
        self.handle = handle
        self.preview = MarkdownChatPreview(
            title: handle.title,
            operatorName: handle.operatorName,
            userName: handle.userName,
            sourceFormat: handle.sourceFormat,
            messageCount: Int(handle.messageCount),
            dayCount: Int(handle.dayCount),
            samples: handle.samples.map { sample in
                MarkdownChatSample(
                    senderName: sample.senderName,
                    isMe: sample.isMe,
                    text: sample.text,
                    timestamp: sample.timestamp
                )
            },
            firstMessageText: handle.firstMessageText,
            lastMessageText: handle.lastMessageText
        )
    }
}

/// Markdown 导入结果。
struct MarkdownChatImportInfo {
    let sessionId: String
    let operatorId: String
    let operatorName: String
    let insertedMessages: Int
    let skippedDuplicates: Int
    let totalMessages: Int
    let createdSession: Bool
    let warning: String

    var summaryText: String {
        let base = "新增 \(insertedMessages) 条 · 去重 \(skippedDuplicates) 条 · 共解析 \(totalMessages) 条"
        return warning.isEmpty ? base : "\(base)\n\(warning)"
    }
}

extension AppGraphClient {

    /// 当前用户昵称（设置 `user_name`，默认「博士」）——解析时判定哪些消息属于「我」。
    var markdownUserName: String { markdownImportBridge.currentUserName() }

    func parseMarkdownFile(path: String, userName: String = "") async -> Result<MarkdownParsedDocument, BridgeError> {
        await withCheckedContinuation { continuation in
            markdownImportBridge.parseMarkdownFile(path: path, userName: userName) { handle, error in
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else if let handle {
                    continuation.resume(returning: .success(MarkdownParsedDocument(handle: handle)))
                } else {
                    continuation.resume(returning: .failure(BridgeError(message: "Markdown 解析失败")))
                }
            }
        }
    }

    func parseMarkdownText(text: String, userName: String = "") async -> Result<MarkdownParsedDocument, BridgeError> {
        await withCheckedContinuation { continuation in
            markdownImportBridge.parseMarkdownText(text: text, userName: userName) { handle, error in
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else if let handle {
                    continuation.resume(returning: .success(MarkdownParsedDocument(handle: handle)))
                } else {
                    continuation.resume(returning: .failure(BridgeError(message: "Markdown 解析失败")))
                }
            }
        }
    }

    /// 导入解析结果到目标角色（`targetOperatorId` 为空 = 按解析出的角色名自动匹配 / 回退第一个角色）。
    func importMarkdown(
        document: MarkdownParsedDocument,
        targetOperatorId: String
    ) async -> Result<MarkdownChatImportInfo, BridgeError> {
        await withCheckedContinuation { continuation in
            markdownImportBridge.importMarkdown(
                handle: document.handle,
                targetOperatorId: targetOperatorId
            ) { info, error in
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else if let info {
                    continuation.resume(returning: .success(MarkdownChatImportInfo(
                        sessionId: info.sessionId,
                        operatorId: info.operatorId,
                        operatorName: info.operatorName,
                        insertedMessages: Int(info.insertedMessages),
                        skippedDuplicates: Int(info.skippedDuplicates),
                        totalMessages: Int(info.totalMessages),
                        createdSession: info.createdSession,
                        warning: info.warning
                    )))
                } else {
                    continuation.resume(returning: .failure(BridgeError(message: "Markdown 导入失败")))
                }
            }
        }
    }
}
