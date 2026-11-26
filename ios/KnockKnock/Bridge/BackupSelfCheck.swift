//
//  BackupSelfCheck.swift
//  T38 数据与备份：文件校验 + 恢复后自检（Swift 侧，复用系统 SQLite3 模块）
//
//  为什么在 Swift 做：
//  - 完整备份是 SQLite 数据库文件（`rhodes.db`），SQLite C API 可直接只读打开做
//    `PRAGMA integrity_check` / `sqlite_master` 表计数，无需再经 SQLDelight 打开第二连接；
//  - 换入发生在冷启动 `IosAppGraph` 打开 DB 之前（两阶段恢复），自检必须在启动后由 Swift 侧
//    比对「暂存时期望计数 vs 换入后现网计数」，这是“恢复后自检结果展示”的数据来源；
//  - 导出前 `wal_checkpoint(FULL)` 把 -wal 帧并回主库，降低运行中文件复制漏最近写入的风险
//    （T29 handoff 已知限制 4 的补强，两阶段恢复语义不变）。
//

import CryptoKit
import Foundation
import SQLite3

// MARK: - SHA-256（CryptoKit）

enum BackupSHA256 {
    static func hex(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }
}

// MARK: - SQLite 文件校验

struct BackupSQLiteInspection {
    var path: String
    var exists: Bool
    var fileSize: Int64
    /// 文件头 = `SQLite format 3\0`
    var headerValid: Bool
    /// `PRAGMA integrity_check` 首行结果（"ok" = 通过）
    var integrityResult: String
    var tableCount: Int
    /// 关键表行数（表不存在则跳过）
    var counts: [String: Int]
    var error: String?

    var isHealthy: Bool { exists && headerValid && integrityResult.lowercased() == "ok" && error == nil }

    var countsText: String {
        BackupSQLite.trackedTables.compactMap { key in
            counts[key].map { "\(key)=\($0)" }
        }.joined(separator: " · ")
    }
}

enum BackupSQLite {
    /// 自检关键表（与备份恢复覆盖范围一致；表不存在时跳过计数）
    static let trackedTables = [
        "operators", "chat_sessions", "chat_messages", "memories", "relationships",
        "chat_archives", "chat_history_segments", "knowledge_bases",
    ]

    /// 读取文件头 16 字节判断 SQLite 魔数。
    static func hasSQLiteHeader(path: String) -> Bool {
        guard let handle = FileHandle(forReadingAtPath: path) else { return false }
        defer { try? handle.close() }
        let head = handle.readData(ofLength: 16)
        guard head.count == 16 else { return false }
        return head == "SQLite format 3\u{0}".data(using: .utf8)
    }

    /// 导出前 WAL checkpoint（FULL：把所有已提交帧写回主库）。失败静默（部分只读场景属正常）。
    static func checkpoint(databasePath: String) {
        var db: OpaquePointer?
        guard sqlite3_open_v2(databasePath, &db, SQLITE_OPEN_READWRITE, nil) == SQLITE_OK, let db else {
            if let db { sqlite3_close(db) }
            return
        }
        sqlite3_busy_timeout(db, 1_500)
        var statement: OpaquePointer?
        if sqlite3_prepare_v2(db, "PRAGMA wal_checkpoint(FULL);", -1, &statement, nil) == SQLITE_OK {
            _ = sqlite3_step(statement)
        }
        sqlite3_finalize(statement)
        sqlite3_close(db)
    }

    /// 打开数据库连接。
    ///
    /// - `immutable: true`：用于**静态备份文件**（导出副本 / `.restore-pending`）。这类文件是从运行中的
    ///   WAL 库复制出来的单文件快照，没有 `-shm/-wal`；只读 + 普通打开会因 WAL 模式拿不到 shm 而返回
    ///   `SQLITE_CANTOPEN`（实测 “unable to open database file”）。`?immutable=1` URI 让 SQLite 把它当
    ///   不可变文件读取，跳过 WAL/锁，是 SQLite 官方推荐的备份校验打开方式。
    /// - `immutable: false`：用于**现网运行库**，普通读写打开（可见 WAL 中的最新提交，不执行写语句）。
    private static func open(path: String, immutable: Bool) -> OpaquePointer? {
        var db: OpaquePointer?
        if immutable {
            let encoded = path.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? path
            let uri = "file:\(encoded)?immutable=1"
            if sqlite3_open_v2(uri, &db, SQLITE_OPEN_READONLY | SQLITE_OPEN_URI, nil) == SQLITE_OK {
                return db
            }
            if let db { sqlite3_close(db) }
            db = nil
        }
        if sqlite3_open_v2(path, &db, SQLITE_OPEN_READWRITE, nil) == SQLITE_OK {
            return db
        }
        if let db { sqlite3_close(db) }
        return nil
    }

    /// 只读打开数据库文件做完整性 + 行数自检（不修改数据）。
    /// - `immutable`：静态备份文件传 true；现网库传 false（见 [open]）。
    static func inspect(path: String, immutable: Bool = false) -> BackupSQLiteInspection {
        let exists = FileManager.default.fileExists(atPath: path)
        let attributes = try? FileManager.default.attributesOfItem(atPath: path)
        let size = (attributes?[.size] as? NSNumber)?.int64Value ?? 0
        guard exists else {
            return BackupSQLiteInspection(
                path: path, exists: false, fileSize: 0, headerValid: false,
                integrityResult: "文件不存在", tableCount: 0, counts: [:], error: "文件不存在"
            )
        }
        let headerValid = hasSQLiteHeader(path: path)
        guard let db = open(path: path, immutable: immutable) else {
            return BackupSQLiteInspection(
                path: path, exists: true, fileSize: size, headerValid: headerValid,
                integrityResult: "无法打开数据库", tableCount: 0, counts: [:], error: "无法打开数据库"
            )
        }
        defer { sqlite3_close(db) }
        sqlite3_busy_timeout(db, 1_500)

        var error: String?
        let integrity: String
        if let value = queryString(db: db, sql: "PRAGMA integrity_check;") {
            integrity = value
        } else {
            error = String(cString: sqlite3_errmsg(db))
            integrity = "读取失败：\(error ?? "unknown")"
        }
        let tableCount = queryInt(db: db, sql: "SELECT count(*) FROM sqlite_master WHERE type='table';") ?? 0
        var counts: [String: Int] = [:]
        for table in trackedTables {
            if let value = queryInt(db: db, sql: "SELECT count(*) FROM \(table);") {
                counts[table] = value
            }
        }
        return BackupSQLiteInspection(
            path: path, exists: true, fileSize: size, headerValid: headerValid,
            integrityResult: integrity, tableCount: tableCount, counts: counts, error: error
        )
    }

    private static func queryInt(db: OpaquePointer, sql: String) -> Int? {
        var statement: OpaquePointer?
        defer { sqlite3_finalize(statement) }
        guard sqlite3_prepare_v2(db, sql, -1, &statement, nil) == SQLITE_OK else { return nil }
        guard sqlite3_step(statement) == SQLITE_ROW else { return nil }
        return Int(sqlite3_column_int64(statement, 0))
    }

    private static func queryString(db: OpaquePointer, sql: String) -> String? {
        var statement: OpaquePointer?
        defer { sqlite3_finalize(statement) }
        guard sqlite3_prepare_v2(db, sql, -1, &statement, nil) == SQLITE_OK else { return nil }
        guard sqlite3_step(statement) == SQLITE_ROW else { return nil }
        guard let text = sqlite3_column_text(statement, 0) else { return nil }
        return String(cString: text)
    }
}

// MARK: - 恢复后自检（两阶段恢复的“换入后”比对）

struct RestoreSelfCheckReport: Codable, Identifiable, Equatable {
    var stagedAt: Date
    var checkedAt: Date
    var dbPath: String
    var expectedCounts: [String: Int]
    var actualCounts: [String: Int]
    var mismatches: [String]
    var integrityResult: String
    var passed: Bool

    var id: String { "\(checkedAt.timeIntervalSince1970)" }

    var mismatchesText: String {
        mismatches.isEmpty ? "无差异" : mismatches.joined(separator: " · ")
    }

    var summary: String {
        passed
            ? "恢复后自检通过：表计数与恢复前期望一致，integrity_check = ok"
            : "恢复后自检未通过：\(mismatchesText)"
    }
}

/// 暂存恢复标记（staging 时写入；下次冷启动换入后消费）
struct StagedRestoreMarker: Codable, Equatable {
    var stagedAt: Date
    var pendingPath: String
    var dbPath: String
    var expectedCounts: [String: Int]
    var integrityResult: String
    var pendingSize: Int64
}

enum BackupSelfCheck {
    /// 落盘持久化（不用 UserDefaults：`simctl terminate` 是 SIGKILL，UserDefaults 异步写入会丢；
    /// 标记丢失会导致“pending 已换入但未识别为第二轮”）。文件写 `.atomic` 立即落盘。
    private static var documents: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }

    private static var markerURL: URL { documents.appendingPathComponent("backup-restore-marker.json") }
    private static var reportURL: URL { documents.appendingPathComponent("backup-restore-report.json") }

    private static func write<T: Encodable>(_ value: T, to url: URL) {
        guard let data = try? JSONEncoder().encode(value) else { return }
        try? data.write(to: url, options: .atomic)
    }

    private static func read<T: Decodable>(_ type: T.Type, from url: URL) -> T? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(type, from: data)
    }

    // MARK: staging 记录

    static func recordStagedRestore(
        pendingPath: String,
        dbPath: String,
        inspection: BackupSQLiteInspection,
        currentCounts: [String: Int]
    ) {
        let marker = StagedRestoreMarker(
            stagedAt: Date(),
            pendingPath: pendingPath,
            dbPath: dbPath,
            expectedCounts: inspection.counts,
            integrityResult: inspection.integrityResult,
            pendingSize: inspection.fileSize
        )
        write(marker, to: markerURL)
    }

    static func clearStagedMarker() {
        try? FileManager.default.removeItem(at: markerURL)
    }

    static func stagedMarker() -> StagedRestoreMarker? {
        read(StagedRestoreMarker.self, from: markerURL)
    }

    // MARK: 换入后自检

    /// 启动后调用：若上次 staging 的 pending 文件已消失（= 本次冷启动已换入），比对计数并落盘报告。
    static func evaluateAfterLaunch(dbPath: String) -> RestoreSelfCheckReport? {
        guard let marker = stagedMarker() else { return nil }
        // pending 仍在 → 尚未换入（等待重启），本轮不评估
        if FileManager.default.fileExists(atPath: marker.pendingPath) { return nil }
        let actualInspection = BackupSQLite.inspect(path: dbPath)
        let expected = marker.expectedCounts
        let actual = actualInspection.counts
        let keys = Set(expected.keys).union(actual.keys)
        var mismatches: [String] = []
        for key in keys.sorted() {
            // pending 文件里不存在的表（例如老库没有 knowledge_bases）不计为差异
            guard let expectedValue = expected[key] else { continue }
            let actualValue = actual[key] ?? 0
            if expectedValue != actualValue {
                mismatches.append("\(key) 期望 \(expectedValue) 实际 \(actualValue)")
            }
        }
        if actualInspection.integrityResult.lowercased() != "ok" {
            mismatches.append("integrity_check=\(actualInspection.integrityResult)")
        }
        if !actualInspection.headerValid {
            mismatches.append("SQLite 文件头无效")
        }
        let report = RestoreSelfCheckReport(
            stagedAt: marker.stagedAt,
            checkedAt: Date(),
            dbPath: dbPath,
            expectedCounts: expected,
            actualCounts: actual,
            mismatches: mismatches,
            integrityResult: actualInspection.integrityResult,
            passed: mismatches.isEmpty
        )
        saveLastReport(report)
        clearStagedMarker()
        return report
    }

    static func saveLastReport(_ report: RestoreSelfCheckReport) {
        write(report, to: reportURL)
    }

    static func loadLastReport() -> RestoreSelfCheckReport? {
        read(RestoreSelfCheckReport.self, from: reportURL)
    }

    static func clearLastReport() {
        try? FileManager.default.removeItem(at: reportURL)
    }
}

// MARK: - 自检日志（T38 证据：Documents/backup-selftest.log）

enum BackupLog {
    private static let lock = NSLock()
    private static var lines: [String] = []
    private static let isSelfTest = ProcessInfo.processInfo.arguments.contains("--backup-selftest")

    static func reset() {
        lock.lock()
        lines.removeAll()
        lock.unlock()
        guard isSelfTest else { return }
        try? "".write(to: logURL, atomically: true, encoding: .utf8)
    }

    static func add(_ message: String) {
        NSLog("[KnockKnock][Backup] %@", message)
        lock.lock()
        lines.append("\(timestamp()) \(message)")
        let snapshot = lines
        lock.unlock()
        guard isSelfTest else { return }
        let text = ([
            "# KnockKnock iOS 数据与备份自检日志",
            "# args: \(ProcessInfo.processInfo.arguments.joined(separator: " "))",
            "# ————————————————————",
        ] + snapshot).joined(separator: "\n")
        try? text.write(to: logURL, atomically: true, encoding: .utf8)
    }

    static var logURL: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("backup-selftest.log")
    }

    private static func timestamp() -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "HH:mm:ss.SSS"
        return formatter.string(from: Date())
    }
}
