//
//  GroupChatAutomation.swift
//  T43 群聊全链路自检钩子（仅 `--group-selftest` 启动参数启用，对用户路径零影响）：
//  群列表（含预设群播种）→ 群详情/成员/禁言 → 群消息分页 → 自动互动开关切换 →
//  发一条群消息（真实 GroupChatEngine + 真实 DB 落库 surface=group）→ 分页复核 →
//  分段撤回 → 深链 rhodes://group/{id} 打开群聊页。
//
//  证据：NSLog；独立日志文件 Documents/ios-group-selftest.log（simctl get_app_container 可取，
//  不依赖 `--selftest` 的 SelfTestLog 落盘开关）。
//

import Foundation

// MARK: - 群聊自检日志（独立文件，避免依赖 --selftest）

enum GroupSelfTestLog {
    static let isEnabled: Bool = ProcessInfo.processInfo.arguments.contains("--group-selftest")

    private static let lock = NSLock()
    private static var lines: [String] = []

    static func add(_ message: String) {
        NSLog("[RhodesLink][GroupSelfTest] %@", message)
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

    static func flushToDisk(summary: String) {
        guard isEnabled else { return }
        let header = [
            "# RhodesLink iOS 群聊自检日志（T43）",
            "# args: \(ProcessInfo.processInfo.arguments.joined(separator: " "))",
            "# device: \(ProcessInfo.processInfo.operatingSystemVersionString)",
            "# ————————————————————",
        ]
        let body = snapshot().joined(separator: "\n")
        let text = (header + [body, "", "## summary", summary]).joined(separator: "\n")
        guard let directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return }
        let url = directory.appendingPathComponent("ios-group-selftest.log")
        do {
            try text.write(to: url, atomically: true, encoding: .utf8)
            NSLog("[RhodesLink][GroupSelfTest] log written to %@", url.path)
        } catch {
            NSLog("[RhodesLink][GroupSelfTest] log write failed: %@", error.localizedDescription)
        }
    }

    private static func timestamp() -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss.SSS"
        return formatter.string(from: Date())
    }
}

// MARK: - 自检驱动

@MainActor
enum GroupChatAutomation {
    static let isEnabled = ProcessInfo.processInfo.arguments.contains("--group-selftest")
    /// T52：群聊未读角标自检（非当前群 AI 回复 → unread +N → 打开群聊清零）。
    static let unreadSelfTestEnabled = ProcessInfo.processInfo.arguments.contains("--group-unread-selftest")
    private static var didRun = false
    private static var didRunUnread = false

    /// 由联系人页（RolesView.task）在出现后调用；`--tab contacts --group-selftest` 即可无触控跑通。
    static func runIfNeeded(environment: AppEnvironment, router: AppRouter) async {
        // T52：群聊未读自检走独立参数/独立日志（与 T43 全链路自检互不干扰）
        if unreadSelfTestEnabled {
            guard !didRunUnread else { return }
            didRunUnread = true
            await runUnreadSelfTest(environment: environment, router: router)
            return
        }
        guard isEnabled, !didRun else { return }
        didRun = true

        GroupSelfTestLog.add("=== group selftest start ===")
        let bridge = environment.bridge

        // 1. 群列表（首次调用会幂等播种预设角色 + 预设群）
        var groupId = ""
        switch await bridge.loadGroups() {
        case .success(let groups):
            GroupSelfTestLog.add("group list count=\(groups.count) ids=\(groups.map(\.sessionId)) names=\(groups.map(\.name))")
            if let first = groups.first {
                groupId = first.sessionId
                GroupSelfTestLog.add(
                    "group list first id=\(first.sessionId) name=\(first.name) members=\(first.memberCount) " +
                    "muted=\(first.mutedCount) lastMessage=\(first.lastMessage.prefix(30)) unread=\(first.unreadCount)"
                )
            }
        case .failure(let error):
            GroupSelfTestLog.add("group list FAILED error=\(error.message)")
        }

        if groupId.isEmpty {
            // 极端情况（DB 无预设群）：用真实角色列表创建群聊（同一 createGroup 桥/DB 路径）
            switch await bridge.loadRoles() {
            case .success(let roles):
                let ids = roles.prefix(4).map(\.id)
                switch await bridge.createGroup(name: "T43 自检群", memberIds: ids) {
                case .success(let created):
                    groupId = created
                    GroupSelfTestLog.add("group create fallback ok id=\(created) members=\(ids)")
                case .failure(let error):
                    GroupSelfTestLog.add("group create fallback FAILED error=\(error.message)")
                }
            case .failure(let error):
                GroupSelfTestLog.add("group create fallback roles FAILED error=\(error.message)")
            }
        }
        guard !groupId.isEmpty else {
            GroupSelfTestLog.add("group selftest ABORT (no group)")
            GroupSelfTestLog.flushToDisk(summary: "abort: no group")
            return
        }

        // 2. 群详情 / 成员 / 禁言 / 权限
        switch await bridge.loadGroupDetail(sessionId: groupId) {
        case .success(let detail):
            GroupSelfTestLog.add(
                "group detail id=\(detail.sessionId) name=\(detail.name) mode=\(detail.mode) " +
                "members=\(detail.memberCount) muted=\(detail.mutedCount) auto=\(detail.autoEnabled) " +
                "master=\(detail.autoMasterEnabled) maxRounds=\(detail.autoMaxRounds) rules=\(detail.rules.prefix(30))"
            )
            for member in detail.members {
                GroupSelfTestLog.add(
                    "group member id=\(member.id) name=\(member.name) muted=\(member.isMuted) " +
                    "permission=\(member.permissionText) resolved=\(member.isResolved)"
                )
            }
        case .failure(let error):
            GroupSelfTestLog.add("group detail FAILED error=\(error.message)")
        }

        // 3. 群消息分页（发送前基线）
        var baselineTotal = 0
        switch await bridge.loadGroupMessagePage(sessionId: groupId, beforeId: 0, limit: 50) {
        case .success(let page):
            baselineTotal = page.totalCount
            GroupSelfTestLog.add(
                "group history baseline total=\(page.totalCount) count=\(page.items.count) hasMore=\(page.hasMore) " +
                "newestId=\(page.items.first?.id ?? 0)"
            )
        case .failure(let error):
            GroupSelfTestLog.add("group history baseline FAILED error=\(error.message)")
        }

        // 4. 自动互动开关切换（on → status → off → status）
        switch await bridge.setGroupAutoChat(sessionId: groupId, enabled: true) {
        case .success(let status):
            GroupSelfTestLog.add("group auto toggle ON ok status=\(status)")
        case .failure(let error):
            GroupSelfTestLog.add("group auto toggle ON FAILED error=\(error.message)")
        }
        switch await bridge.setGroupAutoChat(sessionId: groupId, enabled: false) {
        case .success(let status):
            GroupSelfTestLog.add("group auto toggle OFF ok status=\(status)")
        case .failure(let error):
            GroupSelfTestLog.add("group auto toggle OFF FAILED error=\(error.message)")
        }

        // 5. 发送一条群消息（真实 GroupChatEngine：用户消息落库 → 模型/演示通道 → ai_json 落库）
        let text = "T43 自检：大家好，今天群里有什么新鲜事？"
        let sendResult = await sendMessage(bridge: bridge, sessionId: groupId, text: text)
        if let sendResult {
            GroupSelfTestLog.add(
                "group send ok=\(sendResult.ok) user=\(sendResult.userMessageId) ai=\(sendResult.aiMessageId) " +
                "speakers=\(sendResult.speakersCsv) narration=\(sendResult.narrationCount) " +
                "demo=\(sendResult.isDemo) chars=\(sendResult.text.count) error=\(sendResult.error)"
            )
            GroupSelfTestLog.add("group send text=\(sendResult.text.replacingOccurrences(of: "\n", with: " | ").prefix(200))")
        } else {
            GroupSelfTestLog.add("group send TIMEOUT (30s, engine no callback)")
        }

        // 6. 分页复核（最新一页应包含刚落的用户消息 + ai_json 回复）
        var newestAiMessageId: Int64 = 0
        switch await bridge.loadGroupMessagePage(sessionId: groupId, beforeId: 0, limit: 50) {
        case .success(let page):
            let aiRows = page.items.filter { $0.type == "ai_json" }
            newestAiMessageId = aiRows.first?.id ?? 0
            GroupSelfTestLog.add(
                "group history after send total=\(page.totalCount) (baseline=\(baselineTotal)) " +
                "newestId=\(page.items.first?.id ?? 0) newestType=\(page.items.first?.type ?? "-") " +
                "aiRows=\(aiRows.count) newestAi=\(newestAiMessageId)"
            )
            if let newest = page.items.first {
                GroupSelfTestLog.add(
                    "group history newest id=\(newest.id) isMe=\(newest.isMe) type=\(newest.type) " +
                    "sender=\(newest.senderName) time=\(newest.timePayload) segments=\(newest.segments.count)"
                )
            }
            if let ai = aiRows.first {
                for segment in ai.segments {
                    GroupSelfTestLog.add(
                        "group ai segment idx=\(segment.index) speaker=\(segment.speakerName) " +
                        "emotion=\(segment.emotion) narration=\(segment.isNarration) text=\(segment.text.prefix(60))"
                    )
                }
            }
        case .failure(let error):
            GroupSelfTestLog.add("group history after send FAILED error=\(error.message)")
        }

        // 7. 分段撤回（群聊独有：只撤回某一位成员的发言段）
        if newestAiMessageId > 0 {
            let before = await segmentStats(bridge: bridge, sessionId: groupId, messageId: newestAiMessageId)
            let error = await bridge.recallGroupSegment(sessionId: groupId, messageId: newestAiMessageId, segmentIndex: 0)
            let after = await segmentStats(bridge: bridge, sessionId: groupId, messageId: newestAiMessageId)
            GroupSelfTestLog.add(
                "group recallSegment messageId=\(newestAiMessageId) index=0 error=\(error ?? "-") " +
                "segmentsBefore=\(before.total)/visible=\(before.visible)/recalled=\(before.recalled) " +
                "segmentsAfter=\(after.total)/visible=\(after.visible)/recalled=\(after.recalled) " +
                "removedWhole=\(after.total == 0 && before.total > 0) " +
                "PASS=\(error == nil && (after.recalled > before.recalled || (after.total == 0 && before.total > 0)))"
            )
        } else {
            GroupSelfTestLog.add("group recallSegment SKIPPED (no ai message)")
        }

        // 8. 深链 rhodes://group/{id} → 打开群聊页（截图/页面证据）
        if let url = URL(string: "rhodes://group/\(groupId)") {
            let handled = router.handle(url: url)
            GroupSelfTestLog.add("group deeplink url=\(url.absoluteString) handled=\(handled) route=\(router.linkHistory.last ?? "-")")
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            NotificationCenter.default.post(name: .groupSelfTestRefresh, object: nil)
            try? await Task.sleep(nanoseconds: 1_000_000_000)
        }

        GroupSelfTestLog.add("=== group selftest done ===")
        GroupSelfTestLog.flushToDisk(
            summary: "group=\(groupId) baseline=\(baselineTotal) send=\(sendResult?.ok ?? false) " +
                "speakers=\(sendResult?.speakersCsv ?? "-") deeplink=\(router.lastHandledLink ?? "-")"
        )
    }

    // MARK: - T52 群聊未读角标自检（--group-unread-selftest）

    /// 校验链路（真实 Shared.framework / SQLite / GroupChatEngine，不 mock）：
    /// 1) 基线：全部已读、无「当前查看群」（`setActiveGroup("")`）→ 目标群 unread=0；
    /// 2) 在**非当前群**产生一轮真实 AI 回复 → 引擎 `GroupEngineEvent.UnreadHint` →
    ///    `GroupChatBridge.onEngineEvent`（非当前群）→ `SessionRepository.incrementUnread` →
    ///    `chat_sessions.unreadCount` +N（N = 可见回复段数，≥1）；
    /// 3) 打开群聊（真实路由 → GroupChatViewModel.load：`setActiveGroup(groupId)` + `markSessionRead`）→ 未读清零；
    /// 4) 打开状态下再产生一轮回复 → UnreadHint 被忽略（当前群不累加，与私聊 T44 语义一致）；
    /// 结束时停在群聊页（截图证据）。DB 侧另用 sqlite3 复核 chat_sessions.unreadCount。
    private static func runUnreadSelfTest(environment: AppEnvironment, router: AppRouter) async {
        var passCount = 0
        var failCount = 0
        func check(_ name: String, _ ok: Bool, _ detail: String = "") {
            if ok { passCount += 1 } else { failCount += 1 }
            PolishSelfTestLog.add("\(ok ? "PASS" : "FAIL") \(name)\(detail.isEmpty ? "" : " \(detail)")")
        }

        let bridge = environment.bridge
        PolishSelfTestLog.add("=== group unread selftest start ===")

        // 1. 基线：全部已读 + 未查看任何群
        bridge.setActiveGroup(sessionId: "")
        let clearError = await bridge.markAllSessionsRead()
        check("baseline.markAllRead", clearError == nil, "error=\(clearError ?? "-")")

        var groups: [GroupItem] = []
        if case .success(let items) = await bridge.loadGroups() { groups = items }
        guard let target = groups.first else {
            PolishSelfTestLog.add("group unread selftest ABORT (no groups)")
            PolishSelfTestLog.flushToDisk(fileName: "t52-group-unread-selftest.log", summary: "abort: no groups")
            return
        }
        let groupId = target.sessionId
        let baseline = await groupUnread(bridge: bridge, groupId: groupId)
        check("baseline.groupUnread", baseline == 0, "group=\(groupId) name=\(target.name) unread=\(baseline)")
        PolishSelfTestLog.add(
            "target group=\(groupId) name=\(target.name) members=\(target.memberCount) " +
            "unread=\(baseline) activeViewing=<empty>(非当前群)"
        )

        // 2. 非当前群产生一轮真实 AI 回复 → UnreadHint → incrementUnread
        let send = await sendMessage(bridge: bridge, sessionId: groupId, text: "T52 自检：非当前群收到 AI 回复应累计未读。")
        let expectedVisible = visibleCount(send)
        let unreadAfter = await pollGroupUnread(bridge: bridge, groupId: groupId, deadlineSeconds: 25) { $0 > 0 }
        let delta = unreadAfter - baseline
        check(
            "unreadHint.increment",
            send?.ok == true && unreadAfter > baseline && delta >= 1,
            "sendOk=\(send?.ok ?? false) ai=\(send?.aiMessageId ?? 0) unread=\(baseline)→\(unreadAfter) " +
                "delta=\(delta) expectedVisible=\(expectedVisible) demo=\(send?.isDemo ?? false) " +
                "error=\(send?.error ?? "-")"
        )
        check(
            "db.unreadDeltaVisibleCount",
            delta >= 1 && (expectedVisible <= 0 || delta == expectedVisible),
            "delta=\(delta) visibleSegments=\(expectedVisible)"
        )
        PolishSelfTestLog.add("ui.groupBadge group=\(groupId) unread=\(unreadAfter)（截图窗口：联系人页群聊角标，RolesView 每 5s 轮询刷新）")
        try? await Task.sleep(nanoseconds: 8_000_000_000)

        // 3. 打开群聊（真实路由 → GroupChatViewModel.load → setActiveGroup + markSessionRead 清零）
        router.openGroupChat(sessionId: groupId)
        var unreadAfterOpen = -1
        var waitCount = 0
        while waitCount < 100 {
            try? await Task.sleep(nanoseconds: 200_000_000)
            unreadAfterOpen = await groupUnread(bridge: bridge, groupId: groupId)
            if unreadAfterOpen == 0 { break }
            waitCount += 1
        }
        check(
            "openGroup.clearUnread",
            unreadAfterOpen == 0,
            "group=\(groupId) unreadAfterOpen=\(unreadAfterOpen) waitedMs=\(waitCount * 200)"
        )
        try? await Task.sleep(nanoseconds: 1_500_000_000)

        // 4. 打开状态下再产生回复 → 当前群不累加（UnreadHint 被 activeViewingGroup 排除）
        let viewingSend = await sendMessage(bridge: bridge, sessionId: groupId, text: "T52 自检：当前群收到回复不应累计未读。")
        try? await Task.sleep(nanoseconds: 2_000_000_000)
        let unreadWhileViewing = await pollGroupUnread(bridge: bridge, groupId: groupId, deadlineSeconds: 8) { $0 != 0 }
        check(
            "viewing.noIncrement",
            viewingSend?.ok == true && unreadWhileViewing == 0,
            "sendOk=\(viewingSend?.ok ?? false) unreadWhileViewing=\(unreadWhileViewing) " +
                "expectedVisible=\(visibleCount(viewingSend)) error=\(viewingSend?.error ?? "-")"
        )
        PolishSelfTestLog.add("ui.groupChat shown group=\(groupId)（截图窗口：群聊页，未读已清零）")
        try? await Task.sleep(nanoseconds: 3_000_000_000)

        let allPass = failCount == 0
        PolishSelfTestLog.add("=== group unread selftest done allPASS=\(allPass) PASS=\(passCount) FAIL=\(failCount) ===")
        PolishSelfTestLog.flushToDisk(
            fileName: "t52-group-unread-selftest.log",
            summary: "T52 群聊未读：group=\(groupId) baseline=\(baseline) afterReply=\(unreadAfter) delta=\(delta) " +
                "afterOpen=\(unreadAfterOpen) whileViewing=\(unreadWhileViewing) PASS=\(passCount) FAIL=\(failCount)"
        )
    }

    /// 读目标群当前未读（真实 `chat_sessions.unread_count`）。
    private static func groupUnread(bridge: AppGraphClient, groupId: String) async -> Int {
        guard case .success(let groups) = await bridge.loadGroups() else { return -1 }
        return groups.first(where: { $0.sessionId == groupId })?.unreadCount ?? -1
    }

    /// 轮询目标群未读直到条件满足或超时，返回最后一次读数。
    private static func pollGroupUnread(
        bridge: AppGraphClient,
        groupId: String,
        deadlineSeconds: Double,
        until: (Int) -> Bool
    ) async -> Int {
        let deadline = Date().addingTimeInterval(deadlineSeconds)
        var last = -1
        while Date() < deadline {
            last = await groupUnread(bridge: bridge, groupId: groupId)
            if until(last) { return last }
            try? await Task.sleep(nanoseconds: 250_000_000)
        }
        return last
    }

    /// 可见回复段数（对话 speaker 数 + 旁白段数），与引擎 UnreadHint.count 同口径。
    private static func visibleCount(_ result: GroupSendResultItem?) -> Int {
        guard let result else { return 0 }
        let speakers = result.speakersCsv
            .split(whereSeparator: { $0 == "," || $0 == "，" || $0 == "、" })
            .filter { !$0.isEmpty }
            .count
        return speakers + result.narrationCount
    }

    // MARK: - 辅助

    private static func sendMessage(bridge: AppGraphClient, sessionId: String, text: String) async -> GroupSendResultItem? {
        await withCheckedContinuation { continuation in
            var resumed = false
            let finish: (GroupSendResultItem?) -> Void = { result in
                guard !resumed else { return }
                resumed = true
                continuation.resume(returning: result)
            }
            _ = bridge.sendGroupChat(
                sessionId: sessionId,
                text: text,
                onDelta: { _ in },
                onDone: { result in finish(result) }
            )
            Task {
                try? await Task.sleep(nanoseconds: 30_000_000_000)
                if !resumed {
                    bridge.cancelGroupChat()
                    finish(nil)
                }
            }
        }
    }

    private static func segmentStats(
        bridge: AppGraphClient,
        sessionId: String,
        messageId: Int64
    ) async -> (total: Int, visible: Int, recalled: Int) {
        switch await bridge.loadGroupMessagePage(sessionId: sessionId, beforeId: 0, limit: 50) {
        case .success(let page):
            guard let message = page.items.first(where: { $0.id == messageId }) else { return (0, 0, 0) }
            let recalled = message.segments.filter(\.isRecalled).count
            return (message.segments.count, message.segments.count - recalled, recalled)
        case .failure:
            return (0, 0, 0)
        }
    }
}
