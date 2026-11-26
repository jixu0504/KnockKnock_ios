//
//  BackupAutomation.swift
//  T38 数据备份自动化验证（仅 `--backup-selftest` 启动参数启用；对用户路径零影响）
//
//  两轮运行设计（模拟器端到端证据）：
//  第一轮：角色卡导出→检视→导入；聊天记录导出→检视→导入（去重）；坏包/错误路径矩阵；
//          导出完整备份 → 暂存恢复（两阶段）→ 撤销一次 → 再暂存（留待下次冷启动换入）。
//  第二轮（冷启动）：pending 已被 IosAppGraph 换入 → 比对暂存时期望计数 vs 现网计数 → 自检报告。
//
//  证据：
//  - Documents/backup-selftest.log（本文件写入，可 simctl get_app_container 取出）；
//  - Documents/BackupExports/ 下的真实产物（sqlite 备份 / rhodes-operator zip / rhodes-chat zip）；
//  - NSLog（simctl spawn log show 可查）。
//

import Foundation

// MARK: - 自动化状态（设置页展示自检进度/结果）

@MainActor
final class BackupAutomationState: ObservableObject {
    static let shared = BackupAutomationState()

    @Published private(set) var lines: [String] = []
    @Published private(set) var running = false
    @Published private(set) var finished = false

    func reset() {
        lines = []
        finished = false
    }

    func append(_ line: String) {
        lines.append(line)
        if lines.count > 60 { lines.removeFirst(lines.count - 60) }
        BackupLog.add(line)
    }

    func begin() { running = true }

    func end() {
        running = false
        finished = true
    }
}

@MainActor
enum BackupAutomation {
    static let isEnabled = ProcessInfo.processInfo.arguments.contains("--backup-selftest")
    private static var didRun = false

    /// 设置页 `.task` 调用；`--backup-selftest` 时执行两轮中的对应轮次。
    static func runIfNeeded(environment: AppEnvironment) async {
        guard isEnabled, !didRun else { return }
        didRun = true
        let state = BackupAutomationState.shared
        state.begin()

        // ---- 第二轮：上次暂存的恢复已在本次冷启动换入 ----
        if let marker = BackupSelfCheck.stagedMarker(),
           !FileManager.default.fileExists(atPath: marker.pendingPath) {
            BackupLog.add("=== backup selftest round 2 (post cold-start restore) ===")
            state.append("第二轮：检测到上次暂存的恢复已在冷启动换入")
            if let report = environment.bridge.evaluateRestoreSelfCheck() {
                state.append("恢复后自检：\(report.passed ? "通过" : "未通过") · \(report.summary)")
                state.append("期望计数：\(report.expectedCounts.sorted { $0.key < $1.key }.map { "\($0.key)=\($0.value)" }.joined(separator: " · "))")
                state.append("实际计数：\(report.actualCounts.sorted { $0.key < $1.key }.map { "\($0.key)=\($0.value)" }.joined(separator: " · "))")
                state.append("integrity_check = \(report.integrityResult)")
            } else {
                state.append("恢复后自检：未取得报告")
            }
            state.end()
            return
        }

        // ---- 第一轮：完整自检矩阵 ----
        BackupLog.reset()
        state.reset()
        BackupLog.add("=== backup selftest round 1 (full matrix) ===")
        state.append("第一轮：数据与备份自检矩阵开始")

        let bridge = environment.bridge
        let exportDir = BackupFiles.exportDirectory
        let stamp = BackupFiles.timestamp()

        // 0) 准备：确保有可导出的会话（无消息时通过真实引擎发一条）
        let sessions = await loadSessions(bridge: bridge)
        let roles = await loadRoles(bridge: bridge)
        state.append("基线：会话 \(sessions.count) 个 / 角色 \(roles.count) 个")
        BackupLog.add("baseline sessions=\(sessions.count) roles=\(roles.count)")

        var chattableSessionId: String?
        for session in sessions {
            if case let .success(page) = await bridge.loadMessagePage(sessionId: session.id, beforeId: 0, limit: 1), page.totalCount > 0 {
                chattableSessionId = session.id
                BackupLog.add("chat export candidate session=\(session.id) messages=\(page.totalCount)")
                break
            }
        }
        if chattableSessionId == nil, let role = roles.first {
            switch await bridge.startChat(operatorId: role.id) {
            case .success(let sessionId):
                chattableSessionId = sessionId
                let sent = await sendChat(bridge: bridge, text: "备份自检：为聊天导出准备的一条真实消息。")
                BackupLog.add("chat send prepared session=\(sessionId) ok=\(sent.ok) text=\(sent.text.prefix(30))")
            case .failure(let error):
                state.append("准备会话失败：\(error.message)")
            }
        }
        if chattableSessionId == nil {
            state.append("没有可导出消息的会话，跳过聊天记录用例")
        }

        // 1) 角色卡导出 → 检视 → 导入（NEW）
        var roleCardPath: String?
        if roles.isEmpty {
            state.append("角色卡用例跳过：没有角色")
        } else {
            let target = roles.first!
            let roleCardURL = exportDir.appendingPathComponent("operator-\(target.id)-\(stamp).rhodes-operator")
            switch await bridge.exportOperatorCard(operatorId: target.id, to: roleCardURL) {
            case .success(let info):
                roleCardPath = info.path
                state.append("角色卡导出：\(info.operatorName) · \(info.sizeText) · 关系 \(info.relationshipCount) · 头像 \(info.hasAvatar ? "含" : "无") · sha=\(info.sha256.prefix(12))…")
                BackupLog.add("operator export PASS name=\(info.operatorName) size=\(info.sizeBytes) sha=\(info.sha256) rel=\(info.relationshipCount) avatar=\(info.hasAvatar) format=\(info.format)/v\(info.formatVersion)")
            case .failure(let error):
                state.append("角色卡导出失败：\(error.message)")
                BackupLog.add("operator export FAIL reason=\(error.message)")
            }
            if let path = roleCardPath {
                switch await bridge.inspectOperatorCard(sourcePath: path) {
                case .success(let preview):
                    state.append("角色卡检视通过：\(preview.summary)")
                    BackupLog.add("operator inspect PASS summary=\(preview.summary)")
                case .failure(let error):
                    state.append("角色卡检视失败：\(error.message)")
                    BackupLog.add("operator inspect FAIL reason=\(error.message)")
                }
                switch await bridge.importOperatorCard(sourcePath: path, mode: "NEW") {
                case .success(let info):
                    state.append("角色卡导入完成：新角色 id=\(info.operatorId) · 关系 \(info.importedRelationships) 条（跳过 \(info.skippedRelationships)）")
                    BackupLog.add("operator import PASS newId=\(info.operatorId) createdNew=\(info.createdNew) importedRel=\(info.importedRelationships) skippedRel=\(info.skippedRelationships)")
                case .failure(let error):
                    state.append("角色卡导入失败：\(error.message)")
                    BackupLog.add("operator import FAIL reason=\(error.message)")
                }
            }
        }

        // 2) 聊天记录导出 → 检视 → 导入（合并去重）
        var chatExportPath: String?
        if let sessionId = chattableSessionId {
            let chatURL = exportDir.appendingPathComponent("RhodesLinkChatExport-\(stamp).zip")
            switch await bridge.exportChat(sessionId: sessionId, to: chatURL) {
            case .success(let info):
                chatExportPath = info.path
                state.append("聊天导出：\(info.sessionTitle) · 消息 \(info.messageCount) · 媒体 \(info.mediaCount) · 存档 \(info.archiveCount) · \(info.sizeText) · sha=\(info.sha256.prefix(12))…")
                BackupLog.add("chat export PASS session=\(info.sessionId) messages=\(info.messageCount) media=\(info.mediaCount) archives=\(info.archiveCount) history=\(info.historySegmentCount) size=\(info.sizeBytes) sha=\(info.sha256)")
            case .failure(let error):
                state.append("聊天导出失败：\(error.message)")
                BackupLog.add("chat export FAIL reason=\(error.message)")
            }
            if let path = chatExportPath {
                switch await bridge.inspectChatExport(sourcePath: path) {
                case .success(let preview):
                    state.append("聊天包检视通过：消息 \(preview.messageCount) · 媒体 \(preview.mediaCount) · 告警 \(preview.alerts.count)")
                    BackupLog.add("chat inspect PASS session=\(preview.sessionId) messages=\(preview.messageCount) media=\(preview.mediaCount) alerts=\(preview.alerts)")
                case .failure(let error):
                    state.append("聊天包检视失败：\(error.message)")
                    BackupLog.add("chat inspect FAIL reason=\(error.message)")
                }
                let outcome = await bridge.importChat(sourcePath: path) { stage, detail in
                    BackupLog.add("chat import progress stage=\(stage) detail=\(detail)")
                }
                switch outcome {
                case .success(let result):
                    state.append("聊天导入（重复导入=幂等验证）：\(result.summaryText)")
                    BackupLog.add("chat import PASS success=\(result.success) imported=\(result.importedMessages) deduped=\(result.dedupedMessages) media=\(result.importedMedia) archives=\(result.importedArchives) alerts=\(result.alerts)")
                case .failure(let error):
                    state.append("聊天导入失败：\(error.message)")
                    BackupLog.add("chat import FAIL reason=\(error.message)")
                }
            }
        }

        // 3) 完整备份导出（含上面导入后的最新数据）
        var fullBackupURL: URL?
        let fullURL = exportDir.appendingPathComponent("rhodes-backup-\(stamp).db")
        switch await bridge.exportFullBackup(destinationURL: fullURL) {
        case .success(let info):
            fullBackupURL = URL(fileURLWithPath: info.path)
            state.append("完整备份导出：\(info.sizeText) · sqlite 头 \(info.inspection.headerValid ? "有效" : "无效") · integrity=\(info.inspection.integrityResult) · 表 \(info.inspection.tableCount) · \(info.inspection.countsText)")
            BackupLog.add("full backup export PASS size=\(info.sizeBytes) sha=\(info.sha256) header=\(info.inspection.headerValid) integrity=\(info.inspection.integrityResult) tables=\(info.inspection.tableCount) counts=\(info.inspection.counts)")
        case .failure(let error):
            state.append("完整备份导出失败：\(error.message)")
            BackupLog.add("full backup export FAIL reason=\(error.message)")
        }

        // 4) 完整备份两阶段恢复：暂存 → 撤销 → 再暂存（留给下次冷启动）
        if let url = fullBackupURL {
            switch await bridge.stageFullRestore(sourcePath: url.path) {
            case .success(let pending):
                state.append("恢复暂存成功：\(pending.inspection.countsText)")
                BackupLog.add("full restore stage PASS pending=\(pending.pendingPath) header=\(pending.inspection.headerValid) integrity=\(pending.inspection.integrityResult) counts=\(pending.inspection.counts)")
                let cancelled = bridge.cancelPendingRestore()
                let stillPending = bridge.hasPendingRestore
                state.append("撤销待恢复：\(cancelled && !stillPending ? "成功（pending 已删除，现网数据未动）" : "失败")")
                BackupLog.add("pending cancel result=\(cancelled) stillPending=\(stillPending)")
                switch await bridge.stageFullRestore(sourcePath: url.path) {
                case .success(let second):
                    state.append("再次暂存成功：重启 App 后换入（\(second.inspection.countsText)）")
                    BackupLog.add("full restore re-stage PASS pending=\(second.pendingPath)")
                case .failure(let error):
                    state.append("再次暂存失败：\(error.message)")
                    BackupLog.add("full restore re-stage FAIL reason=\(error.message)")
                }
            case .failure(let error):
                state.append("恢复暂存失败：\(error.message)")
                BackupLog.add("full restore stage FAIL reason=\(error.message)")
            }
        }

        // 5) 错误路径矩阵（坏包/格式不符/不存在/权限类 → 必须明确报错且不崩）
        await runErrorMatrix(bridge: bridge, exportDir: exportDir, fullBackupPath: fullBackupURL?.path, roleCardPath: roleCardPath, chatExportPath: chatExportPath, state: state)

        state.append("第一轮自检矩阵完成；待重启后验证换入与恢复后自检")
        BackupLog.add("=== backup selftest round 1 done (pending staged for next cold start) ===")
        state.end()
    }

    // MARK: - 错误路径矩阵

    private static func runErrorMatrix(
        bridge: AppGraphClient,
        exportDir: URL,
        fullBackupPath: String?,
        roleCardPath: String?,
        chatExportPath: String?,
        state: BackupAutomationState
    ) async {
        func expectFailure(_ name: String, _ error: String?) {
            if let error, !error.isEmpty {
                state.append("错误路径 \(name)：已明确提示「\(error)」")
                BackupLog.add("error-path \(name) PASS reason=\(error)")
            } else {
                unexpectedSuccess(name)
            }
        }

        func unexpectedSuccess(_ name: String) {
            state.append("错误路径 \(name)：**未按预期报错**")
            BackupLog.add("error-path \(name) FAIL reason=<no error>")
        }

        // a) 非备份文件（文本）——完整恢复暂存：应报「不是合法 SQLite」
        let textURL = exportDir.appendingPathComponent("bad-not-a-backup.txt")
        try? "这不是备份文件".data(using: .utf8)?.write(to: textURL)
        switch await bridge.stageFullRestore(sourcePath: textURL.path) {
        case .success:
            unexpectedSuccess("非 SQLite 备份恢复")
        case .failure(let error):
            expectFailure("非 SQLite 备份恢复", error.message)
        }

        // b) 聊天包检视遇到完整备份（sqlite 文件）：应报「不是 ZIP / 无法读取」
        if let fullBackupPath {
            switch await bridge.inspectChatExport(sourcePath: fullBackupPath) {
            case .success:
                unexpectedSuccess("聊天包检视-传入备份文件")
            case .failure(let error):
                expectFailure("聊天包检视-传入备份文件", error.message)
            }
        }

        // c) 角色包检视遇到聊天包 zip：跨格式拒绝
        if let chatExportPath {
            switch await bridge.inspectOperatorCard(sourcePath: chatExportPath) {
            case .success:
                unexpectedSuccess("角色包检视-传入聊天包")
            case .failure(let error):
                expectFailure("角色包检视-传入聊天包", error.message)
            }
        }

        // d) 聊天包检视遇到角色包 zip：跨格式拒绝
        if let roleCardPath {
            switch await bridge.inspectChatExport(sourcePath: roleCardPath) {
            case .success:
                unexpectedSuccess("聊天包检视-传入角色包")
            case .failure(let error):
                expectFailure("聊天包检视-传入角色包", error.message)
            }
        }

        // e) 截断 zip（只保留前 64 字节）：应报 ZIP 结构错误
        if let chatExportPath, let data = FileManager.default.contents(atPath: chatExportPath) {
            let truncatedURL = exportDir.appendingPathComponent("bad-truncated.zip")
            try? data.prefix(64).write(to: truncatedURL)
            switch await bridge.inspectChatExport(sourcePath: truncatedURL.path) {
            case .success:
                unexpectedSuccess("截断聊天包")
            case .failure(let error):
                expectFailure("截断聊天包", error.message)
            }
            switch await bridge.importChat(sourcePath: truncatedURL.path, onProgress: { _, _ in }) {
            case .success(let result):
                if result.success {
                    unexpectedSuccess("截断聊天包导入")
                } else {
                    expectFailure("截断聊天包导入", result.reason.isEmpty ? "导入失败" : result.reason)
                }
            case .failure(let error):
                expectFailure("截断聊天包导入", error.message)
            }
        }

        // f) 文件不存在
        switch await bridge.inspectOperatorCard(sourcePath: exportDir.appendingPathComponent("missing.rhodes-operator").path) {
        case .success:
            unexpectedSuccess("角色包不存在")
        case .failure(let error):
            expectFailure("角色包不存在", error.message)
        }

        // g) 导出不存在的会话 / 角色
        switch await bridge.exportChat(sessionId: "__missing_session__", to: exportDir.appendingPathComponent("missing.zip")) {
        case .success:
            unexpectedSuccess("导出不存在会话")
        case .failure(let error):
            expectFailure("导出不存在会话", error.message)
        }
        switch await bridge.exportOperatorCard(operatorId: "__missing_operator__", to: exportDir.appendingPathComponent("missing-card.rhodes-operator")) {
        case .success:
            unexpectedSuccess("导出不存在角色")
        case .failure(let error):
            expectFailure("导出不存在角色", error.message)
        }
    }

    // MARK: - 辅助

    private static func loadSessions(bridge: AppGraphClient) async -> [SessionItem] {
        if case .success(let items) = await bridge.loadSessions() { return items }
        return []
    }

    private static func loadRoles(bridge: AppGraphClient) async -> [RoleItem] {
        if case .success(let items) = await bridge.loadRoles() { return items }
        return []
    }

    /// 通过真实 ChatEngineBridge 发一条消息（仅用于制造聊天导出素材）。
    private static func sendChat(bridge: AppGraphClient, text: String) async -> ChatRoundResult {
        let sessionId = (try? await bridge.loadSessions().get())?.first?.id ?? ""
        return await withCheckedContinuation { continuation in
            var resumed = false
            _ = bridge.sendChat(sessionId: sessionId, text: text, onDelta: { _ in }) { result in
                if !resumed {
                    resumed = true
                    continuation.resume(returning: result)
                }
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 30) {
                if !resumed {
                    resumed = true
                    continuation.resume(returning: ChatRoundResult(ok: false, text: "", error: "发送超时", aiMessageId: 0, isDemo: true))
                }
            }
        }
    }
}
