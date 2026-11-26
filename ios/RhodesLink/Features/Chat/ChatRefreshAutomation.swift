//
//  ChatRefreshAutomation.swift
//  `--chat-refresh-selftest`：真机/模拟器自检钩子（对用户路径零影响）。
//
//  目的：用**真实** ChatViewModel + 真实 ChatEngineBridge / DB 证明两件事：
//   1. 撤回 / 本地删除消息后，聊天页时间线**即时**更新（不需要退出重进）：
//      - 最新一屏消息：撤回/删除后气泡立即消失；
//      - 已通过上滑分页加载的**更早消息**（olderKept 区域）：撤回/删除后同样立即消失
//        （真机回归点：`refreshAfterSend` 只刷新首屏，olderKept 会把已删行原样留下）；
//   2. 导入 Markdown 聊天记录后，聊天页**无需退出**即可看到导入记录：
//      - 导入发生在聊天页未打开：消费设置项「待定位导入块」标记（下次进入立即载入导入块）；
//      - 导入发生在聊天页已打开：监听 `.chatDataDidChangeExternally` 通知立即载入导入块；
//      - 导入记录时间戳通常早于首屏窗口（历史时间），必须按 id 区间取回，否则首屏永远看不到。
//
//  证据：
//   - NSLog：`[RhodesLink][ChatRefreshSelfTest] …`（`xcrun simctl launch --console-pty` 可直接抓）；
//   - Documents/chat-refresh-selftest.log（`simctl get_app_container … data` 取出完整日志）。
//
//  启动示例：
//    xcrun simctl launch --console-pty <UDID> com.xsbcjx.knockknock \
//      --open-url knock://chat/session_zzz001 --chat-refresh-selftest
//

import Foundation
import UIKit

@MainActor
enum ChatRefreshAutomation {
    static let isEnabled: Bool = ProcessInfo.processInfo.arguments.contains("--chat-refresh-selftest")
    static let logFileName = "chat-refresh-selftest.log"
    private static var didRun = false

    // MARK: - 日志

    private static var lines: [String] = []
    private static let timeFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss.SSS"
        return formatter
    }()

    private static func log(_ line: String) {
        NSLog("[RhodesLink][ChatRefreshSelfTest] %@", line)
        lines.append("\(timeFormatter.string(from: Date())) \(line)")
    }

    private static func writeEvidenceFile() {
        guard let directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return }
        let url = directory.appendingPathComponent(logFileName)
        let header = [
            "# ChatRefreshSelfTest 证据日志",
            "# args: \(ProcessInfo.processInfo.arguments.joined(separator: " "))",
            "# ————————————————————",
        ]
        let text = (header + lines).joined(separator: "\n") + "\n"
        do {
            try text.write(to: url, atomically: true, encoding: .utf8)
            NSLog("[RhodesLink][ChatRefreshSelfTest] evidence written to %@", url.path)
        } catch {
            NSLog("[RhodesLink][ChatRefreshSelfTest] evidence write FAILED %@", error.localizedDescription)
        }
    }

    // MARK: - 主流程

    static func runIfNeeded(model: ChatViewModel) async {
        guard isEnabled, !didRun else { return }
        didRun = true
        lines.removeAll()
        defer { writeEvidenceFile() }

        log("=== chat-refresh selftest start ===")
        log("legacyMergeSimulation=\(ChatViewModel.isLegacyMergeSimulationEnabled)（true = 复现修复前合并逻辑，用于 FAIL 基线对比）")
        log("session=\(model.sessionIdForAutomation) title=\(model.title) operatorId=\(model.operatorId)")

        // 0) 等首屏加载 + 引擎空闲（避免与仍在生成的回复抢时间线）
        let loadDeadline = Date().addingTimeInterval(30)
        while !model.hasLoaded, Date() < loadDeadline {
            try? await Task.sleep(nanoseconds: 100_000_000)
        }
        let idleDeadline = Date().addingTimeInterval(60)
        while model.isStreaming, Date() < idleDeadline {
            try? await Task.sleep(nanoseconds: 200_000_000)
        }
        // 0.5) 确保目标会话真实存在：深链 `knock://chat/session_<operatorId>` 在全新安装上
        // 该会话可能尚未创建（bootstrap 只给「第一个角色」建会话），此时 load 找不到会话、
        // 发送会回退到别的会话。用角色页同一条 startChat 路径补建 → 再 refresh，保证自检可重复。
        if model.session == nil {
            let prefix = "session_"
            let operatorId = model.sessionIdForAutomation.hasPrefix(prefix)
                ? String(model.sessionIdForAutomation.dropFirst(prefix.count))
                : model.sessionIdForAutomation
            switch await AppGraphClient.shared.startChat(operatorId: operatorId) {
            case .success(let sessionId):
                log("ensure-session operatorId=\(operatorId) -> session=\(sessionId)")
            case .failure(let error):
                log("ensure-session FAILED operatorId=\(operatorId) error=\(error.message)")
            }
            await model.refresh()
        }
        log(
            "baseline messages=\(model.messages.count) loaded=\(model.loadedCount) total=\(model.totalCount) " +
            "hasMore=\(model.hasMoreHistory) demo=\(model.isDemoMode) sessionFound=\(model.session != nil) " +
            "operatorId=\(model.operatorId) title=\(model.title)"
        )

        // 1) 最新一屏：发送 → 撤回 → 气泡立即消失
        await verifyNewestRecall(model: model)

        // 2) 最新一屏：发送 → 本地删除 → 气泡立即消失
        await verifyNewestDelete(model: model)

        // 2.5) 清空时间线（保留「无历史消息」的干净会话）：
        // 让后续导入块全部落在时间戳更早的历史区，构造「已加载但不在最新一页」的 olderKept 场景。
        await clearTimelineForImport(model: model)

        // 3) 导入：标记路径（聊天页未打开 → 下次进入立即载入导入块）
        let markerImport = await importFixture(
            model: model,
            tag: "MARKER",
            messageCount: 8,
            day: "2025-06-14"
        )
        if let markerImport {
            await verifyMarkerPath(model: model, import: markerImport)
        }

        // 4) 导入：通知路径（聊天页已打开 → 立即载入导入块，不退出重进）
        // 70 条：足以让首屏（最新 50 条）之外仍留有已并入时间线的导入记录，
        // 用于下面的「删除更早已加载消息」回归点（id 落在首屏最早 id 之前 = 真机残留场景）。
        let notifyImport = await importFixture(
            model: model,
            tag: "NOTIFY",
            messageCount: 70,
            day: "2025-06-15"
        )
        if let notifyImport {
            await verifyNotificationPath(model: model, import: notifyImport)

            // 5) 回归点：删除「更早已加载」的消息（不在最新一页，正是 olderKept 残留场景）
            await verifyOlderLoadedDelete(model: model, import: notifyImport)

            // 5.5) 回归点：后续权威刷新不能把导入块丢掉
            await verifyImportPersistence(model: model, fixture: notifyImport)
        }

        // 6) 会话列表预览/时间（同源 DB）刷新证据
        await logSessionListState(model: model)

        log("=== chat-refresh selftest done ===")
        SelfTestLog.flushToDisk(summary: "chat refresh selftest（撤回/删除即时刷新 + 导入后无需重进）")
    }

    // MARK: - 用例 1：撤回最新消息

    private static func verifyNewestRecall(model: ChatViewModel) async {
        let token = "CF-REFRESH-RECALL-\(Int(Date().timeIntervalSince1970))"
        model.send(token)
        let done = await waitUntil(timeout: 60) { !model.isStreaming }
        guard done,
              let message = model.messages.last(where: {
                  $0.side == .outgoing && $0.id > 0 && $0.text.contains(token)
              }) else {
            log("CASE recall-newest FAIL setup (send/DB row missing) messages=\(model.messages.count)")
            return
        }
        let countBefore = model.messages.count
        log("CASE recall-newest before messageId=\(message.id) messages=\(countBefore) contains=true")
        model.recall(message)
        let gone = await waitForRemoval(model: model, messageId: message.id, timeout: 10, label: "recall-newest")
        log(
            "CASE recall-newest \(gone ? "PASS" : "FAIL") messageId=\(message.id) " +
            "messages=\(countBefore)->\(model.messages.count) stillContains=\(contains(model, message.id))"
        )
    }

    // MARK: - 用例 2：本地删除最新消息

    private static func verifyNewestDelete(model: ChatViewModel) async {
        let token = "CF-REFRESH-DELETE-\(Int(Date().timeIntervalSince1970))"
        model.send(token)
        let done = await waitUntil(timeout: 60) { !model.isStreaming }
        guard done,
              let message = model.messages.last(where: {
                  $0.side == .outgoing && $0.id > 0 && $0.text.contains(token)
              }) else {
            log("CASE delete-newest FAIL setup (send/DB row missing) messages=\(model.messages.count)")
            return
        }
        let countBefore = model.messages.count
        log("CASE delete-newest before messageId=\(message.id) messages=\(countBefore) contains=true")
        model.deleteLocal(message)
        let gone = await waitForRemoval(model: model, messageId: message.id, timeout: 10, label: "delete-newest")
        log(
            "CASE delete-newest \(gone ? "PASS" : "FAIL") messageId=\(message.id) " +
            "messages=\(countBefore)->\(model.messages.count) stillContains=\(contains(model, message.id))"
        )
    }

    // MARK: - 用例 3：导入（标记路径，聊天页未打开）

    private struct ImportFixture {
        let sessionId: String
        let operatorId: String
        let insertedIds: [Int64]
        let markerToken: String
        let sampleText: String
    }

    private static func importFixture(
        model: ChatViewModel,
        tag: String,
        messageCount: Int,
        day: String
    ) async -> ImportFixture? {
        let bridge = AppGraphClient.shared
        let operatorId = model.session?.operatorId ?? model.operatorId
        let operatorName = model.session?.title ?? model.title
        let userName = bridge.markdownUserName
        let token = "CF\(tag)-\(Int(Date().timeIntervalSince1970))"
        var markdown = "# 与\(operatorName)的聊天记录\n\n导出时间：2025-06-15 22:10\n\n## \(day)\n\n"
        for index in 0..<messageCount {
            let speaker = index % 2 == 0 ? operatorName : userName
            markdown += "\(speaker)：\(token)-\(index) 导入自检消息\n\n"
        }
        guard case .success(let document) = await bridge.parseMarkdownText(text: markdown) else {
            log("IMPORT \(tag) FAIL parse")
            return nil
        }
        switch await bridge.importMarkdown(document: document, targetOperatorId: operatorId) {
        case .failure(let error):
            log("IMPORT \(tag) FAIL error=\(error.message)")
            return nil
        case .success(let info):
            log(
                "IMPORT \(tag) ok session=\(info.sessionId) operator=\(info.operatorId) inserted=\(info.insertedMessages) " +
                "skipped=\(info.skippedDuplicates) ids=\(info.insertedIds.first ?? 0)..\(info.insertedIds.last ?? 0) " +
                "targetSession=\(model.sessionIdForAutomation) same=\(info.sessionId == model.sessionIdForAutomation)"
            )
            return ImportFixture(
                sessionId: info.sessionId,
                operatorId: info.operatorId,
                insertedIds: info.insertedIds,
                markerToken: token,
                sampleText: "\(token)-0"
            )
        }
    }

    /// 清空时间线：删除会话内所有已加载消息（自检会话专用），并刷新到空态。
    private static func clearTimelineForImport(model: ChatViewModel) async {
        let bridge = AppGraphClient.shared
        let ids = model.messages.map(\.id).filter { $0 > 0 }
        for id in ids {
            _ = await bridge.deleteMessageLocal(sessionId: model.sessionIdForAutomation, messageId: id)
        }
        await model.refresh()
        log("CASE prepare clear-timeline deleted=\(ids.count) remaining=\(model.messages.count) total=\(model.totalCount)")
    }

    /// 标记路径（真实复现「导入时聊天页未打开 → 之后进入该会话」）：
    /// 导入成功写设置项「待定位导入块」→ 新建一个 ChatViewModel 走完整 load() →
    /// 导入块（时间戳更早、不在首屏 50 条内）应立即并入时间线并置好滚动锚点。
    private static func verifyMarkerPath(model: ChatViewModel, import fixture: ImportFixture) async {
        guard fixture.sessionId == model.sessionIdForAutomation,
              let firstId = fixture.insertedIds.first, !fixture.insertedIds.isEmpty else {
            log("CASE import-marker SKIP (session mismatch or zero insert) fixtureSession=\(fixture.sessionId)")
            return
        }
        let info = ChatExternalChangeInfo(
            source: "marker",
            sessionId: fixture.sessionId,
            operatorId: fixture.operatorId,
            importedCount: fixture.insertedIds.count,
            firstImportedId: firstId
        )
        AppGraphClient.shared.setSettingsString(
            key: ChatViewModel.pendingImportFocusKey(sessionId: fixture.sessionId),
            value: info.markerValue
        )
        log("CASE import-marker marker=\(info.markerValue)（模拟导入时聊天页未打开，随后新建聊天页进入）")
        // 新建 ChatViewModel = 用户从会话列表重新进入私聊页（hasLoaded 守卫不阻止首屏加载，
        // 但普通首屏只有最新 50 条；必须靠标记把导入块取回，否则看不到导入记录）。
        let reopened = ChatViewModel(sessionId: model.sessionIdForAutomation)
        await reopened.load()
        let appeared = await waitUntil(timeout: 10) {
            reopened.messages.contains { $0.text.contains(fixture.markerToken) }
        }
        await waitForStableTimeline(model: reopened, timeout: 5)
        let markerAfter = AppGraphClient.shared.settingsString(
            key: ChatViewModel.pendingImportFocusKey(sessionId: fixture.sessionId)
        )
        log(
            "CASE import-marker \(appeared ? "PASS" : "FAIL") reopenedMessages=\(reopened.messages.count) " +
            "loaded=\(reopened.loadedCount)/\(reopened.totalCount) generation=\(reopened.prependGeneration) " +
            "anchor=\(reopened.lastPrependAnchor ?? 0) containsImported=\(appeared) " +
            "markerAfter=\(markerAfter.isEmpty ? "<empty>" : markerAfter)"
        )
    }

    /// 通知路径：导入时聊天页已打开 → `.chatDataDidChangeExternally` → 立即刷新并载入导入块（不退出重进）。
    private static func verifyNotificationPath(model: ChatViewModel, import fixture: ImportFixture) async {
        guard fixture.sessionId == model.sessionIdForAutomation,
              let firstId = fixture.insertedIds.first, !fixture.insertedIds.isEmpty else {
            log("CASE import-notify SKIP (session mismatch or zero insert) fixtureSession=\(fixture.sessionId)")
            return
        }
        let countBefore = model.messages.count
        let generationBefore = model.prependGeneration
        log(
            "CASE import-notify before messages=\(countBefore) generation=\(generationBefore) " +
            "containsImported=\(containsText(model, fixture.markerToken))"
        )
        NotificationCenter.default.post(
            name: .chatDataDidChangeExternally,
            object: nil,
            userInfo: [
                ChatExternalChangeKey.source: "selftest",
                ChatExternalChangeKey.sessionId: fixture.sessionId,
                ChatExternalChangeKey.operatorId: fixture.operatorId,
                ChatExternalChangeKey.importedCount: fixture.insertedIds.count,
                ChatExternalChangeKey.firstImportedId: firstId,
            ]
        )
        let appeared = await waitUntil(timeout: 10) { containsText(model, fixture.markerToken) }
        // 通知处理是「先刷新首屏、再并入导入块」两段（都是异步），等时间线稳定再取证。
        await waitForStableTimeline(model: model, timeout: 10)
        log(
            "CASE import-notify \(appeared ? "PASS" : "FAIL") messages=\(countBefore)->\(model.messages.count) " +
            "generation=\(generationBefore)->\(model.prependGeneration) anchor=\(model.lastPrependAnchor ?? 0) " +
            "containsImported=\(containsText(model, fixture.markerToken)) total=\(model.totalCount)"
        )
        // 标记已被消费/清除（避免下次进入重复插入）
        let marker = AppGraphClient.shared.settingsString(
            key: ChatViewModel.pendingImportFocusKey(sessionId: fixture.sessionId)
        )
        log("CASE import-notify markerAfter=\(marker.isEmpty ? "<empty>" : marker)")
    }

    // MARK: - 用例 5：删除「更早已加载」消息（olderKept 残留回归点）

    private static func verifyOlderLoadedDelete(model: ChatViewModel, import fixture: ImportFixture) async {
        let bridge = AppGraphClient.shared
        // 首屏（最新 50 条，按 timestamp）之外、但已经并入时间线的「更早消息」——
        // 真机 bug 就出在这里：refreshAfterSend 只按 id 保留 olderKept，已删行会残留。
        var freshIds = Set<Int64>()
        if case .success(let page) = await bridge.loadMessagePage(
            sessionId: model.sessionIdForAutomation,
            beforeId: 0,
            limit: 50
        ) {
            freshIds = Set(page.items.map(\.id))
        }
        let olderLoaded = model.messages.filter { $0.id > 0 && !freshIds.contains($0.id) }
        guard let target = olderLoaded.min(by: { $0.id < $1.id }) else {
            log("CASE delete-older SKIP (no loaded message outside first page) messages=\(model.messages.count)")
            return
        }
        let oldestFreshId = freshIds.min() ?? -1
        let idResidueCase = target.id < oldestFreshId
        log(
            "CASE delete-older before messageId=\(target.id) idResidueCase=\(idResidueCase) " +
            "oldestFreshId=\(oldestFreshId) olderLoaded=\(olderLoaded.count) messages=\(model.messages.count)"
        )
        model.deleteLocal(target)
        let gone = await waitForRemoval(model: model, messageId: target.id, timeout: 10, label: "delete-older")
        let stillInDB = await bridge.findMessage(id: target.id) != nil
        log(
            "CASE delete-older \(gone ? "PASS" : "FAIL") messageId=\(target.id) idResidueCase=\(idResidueCase) " +
            "messages=\(model.messages.count) stillInTimeline=\(contains(model, target.id)) stillInDB=\(stillInDB)"
        )
    }

    /// 导入块并入时间线后，再触发一次权威刷新（发送消息 → refreshAfterSend），
    /// 验证导入记录不会被「只保留最新一页 + olderKept」的合并逻辑丢掉。
    private static func verifyImportPersistence(model: ChatViewModel, fixture: ImportFixture) async {
        let token = "CF-PERSIST-\(Int(Date().timeIntervalSince1970))"
        let before = containsText(model, fixture.markerToken)
        model.send(token)
        _ = await waitUntil(timeout: 60) { !model.isStreaming }
        let after = containsText(model, fixture.markerToken)
        log("CASE import-persist \(before && after ? "PASS" : "FAIL") before=\(before) after=\(after) messages=\(model.messages.count)")
    }

    // MARK: - 会话列表（同源 DB）

    private static func logSessionListState(model: ChatViewModel) async {
        switch await AppGraphClient.shared.loadSessions() {
        case .failure(let error):
            log("SESSIONS FAILED error=\(error.message)")
        case .success(let items):
            let row = items.first(where: { $0.id == model.sessionIdForAutomation })
            log(
                "SESSIONS count=\(items.count) current=\(row?.id ?? "-") preview=\(row?.lastMessage.prefix(30) ?? "-") " +
                "lastTime=\(row?.lastTime ?? 0) unread=\(row?.unreadCount ?? -1)"
            )
        }
    }

    // MARK: - 轮询/断言辅助

    private static func contains(_ model: ChatViewModel, _ messageId: Int64) -> Bool {
        model.messages.contains { $0.id == messageId }
    }

    private static func containsText(_ model: ChatViewModel, _ token: String) -> Bool {
        model.messages.contains { $0.text.contains(token) }
    }

    /// 轮询直到消息从时间线消失；记录 messages.count / 状态的每一步变化，作为「即时刷新」证据。
    private static func waitForRemoval(
        model: ChatViewModel,
        messageId: Int64,
        timeout: TimeInterval,
        label: String
    ) async -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        var lastCount = model.messages.count
        var lastPresent = contains(model, messageId)
        var transitions: [String] = []
        while Date() < deadline {
            let present = contains(model, messageId)
            let count = model.messages.count
            if present != lastPresent || count != lastCount {
                let line = "count=\(count) present=\(present) busy=\(model.isActionBusy)"
                transitions.append(line)
                log("CASE \(label) transition \(line)")
                lastPresent = present
                lastCount = count
            }
            if !present { return true }
            try? await Task.sleep(nanoseconds: 100_000_000)
        }
        log("CASE \(label) timeout transitions=\(transitions.joined(separator: " | "))")
        return !contains(model, messageId)
    }

    /// 等时间线稳定（连续 4 次 100ms 采样 messages.count 不变），避免记录到异步刷新中间态。
    private static func waitForStableTimeline(model: ChatViewModel, timeout: TimeInterval) async {
        let deadline = Date().addingTimeInterval(timeout)
        var lastCount = -1
        var stableTicks = 0
        while Date() < deadline {
            let count = model.messages.count
            if count == lastCount {
                stableTicks += 1
                if stableTicks >= 4 { return }
            } else {
                lastCount = count
                stableTicks = 0
            }
            try? await Task.sleep(nanoseconds: 100_000_000)
        }
    }

    private static func waitUntil(timeout: TimeInterval, _ condition: () -> Bool) async -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if condition() { return true }
            try? await Task.sleep(nanoseconds: 100_000_000)
        }
        return condition()
    }
}