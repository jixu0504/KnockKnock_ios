//
//  SessionsAutomation.swift
//  T40 会话列表操作自动化验证钩子（仅启动参数 `--sessions-selftest` 启用，对用户路径零影响）：
//  - 置顶排序（pin/togglePin → 列表首位，对齐原版 pinned 在前）；
//  - 标记已读 / 全部标记已读（unreadCount 归零）；
//  - 从主页移除（settings.hiddenIds 过滤，列表消失）；
//  - 新消息后自动恢复（引擎 EngineEvent.UnhideSession → 取消隐藏，列表重新出现）；
//  - 移除主页所有会话（hideAll）与恢复。
//
//  与 UI（ChatListView 的「⋮」菜单 / 长按 contextMenu）走完全相同的 AppGraphClient → SessionListBridge 路径。
//  证据：NSLog + `--selftest` 时 Documents/ios-shell-selftest.log（simctl 可取）。
//

import Foundation

@MainActor
enum SessionsAutomation {
    private static var didRun = false

    static func runIfNeeded(environment: AppEnvironment, router: AppRouter) async {
        guard !didRun else { return }
        didRun = true
        guard ProcessInfo.processInfo.arguments.contains("--sessions-selftest") else { return }
        await run(environment: environment, router: router)
        router.selectedTab = .chat
    }

    // MARK: - 主流程

    private static func run(environment: AppEnvironment, router: AppRouter) async {
        SelfTestLog.add("sessions selftest started")

        // 0) 清空历史 hiddenIds，从已知状态开始（与用户「移除主页所有会话」后手动恢复同路径）
        environment.bridge.setSettingsString(key: "hidden_ids", value: "[]")

        // 至少 3 个会话，才能验证置顶排序
        if case .success(let roles) = await environment.bridge.loadRoles() {
            for role in roles.prefix(3) {
                _ = await environment.bridge.startChat(operatorId: role.id)
            }
        }

        var list = await load(environment)
        SelfTestLog.add("sessions selftest baseline count=\(list.count) ids=\(list.map(\.id)) pinned=\(list.filter(\.isPinned).map(\.id))")
        guard let head = list.first else {
            SelfTestLog.add("sessions selftest baseline empty FAIL")
            return
        }

        // 1) 置顶：把最后一条（lastTime 最小）置顶后必须排到第 0 位（原版 SQL isPinned DESC, lastTime DESC）
        if list.count >= 2, let tail = list.last {
            let pinError = await environment.bridge.pinSession(id: tail.id, pinned: true)
            list = await load(environment)
            let pinnedHead = list.first?.id == tail.id
            SelfTestLog.add("sessions selftest pin session=\(tail.id) error=\(pinError ?? "-") head=\(list.first?.id ?? "-") headIsPinned=\(list.first?.isPinned ?? false) PASS=\(pinError == nil && pinnedHead)")

            // 2) 取消置顶（长按菜单「取消置顶」）→ 回到末位
            let unpinError = await environment.bridge.togglePinSession(id: tail.id)
            list = await load(environment)
            let restoredTail = list.last?.id == tail.id && !(list.first?.isPinned ?? true)
            SelfTestLog.add("sessions selftest unpin session=\(tail.id) error=\(unpinError ?? "-") head=\(list.first?.id ?? "-") tail=\(list.last?.id ?? "-") PASS=\(unpinError == nil && restoredTail)")
        }

        // 3) 标记已读：unreadCount > 0 的会话（UI 截图轮次由 sqlite 预置未读）归零
        list = await load(environment)
        if let unread = list.first(where: { $0.unreadCount > 0 }) {
            let readError = await environment.bridge.markSessionRead(id: unread.id)
            let after = await load(environment)
            let nowUnread = after.first(where: { $0.id == unread.id })?.unreadCount ?? -1
            SelfTestLog.add("sessions selftest markRead session=\(unread.id) before=\(unread.unreadCount) after=\(nowUnread) error=\(readError ?? "-") PASS=\(readError == nil && nowUnread == 0)")
        } else {
            SelfTestLog.add("sessions selftest markRead SKIPPED (no unread; sqlite seed path)")
        }
        let markAllError = await environment.bridge.markAllSessionsRead()
        list = await load(environment)
        let totalUnread = list.reduce(0) { $0 + $1.unreadCount }
        SelfTestLog.add("sessions selftest markAllRead error=\(markAllError ?? "-") totalUnread=\(totalUnread) PASS=\(markAllError == nil && totalUnread == 0)")

        // 4) 从主页移除（长按菜单「从主页移除」）→ 列表消失；新消息（引擎 UnhideSession）后自动恢复
        list = await load(environment)
        guard let target = list.first else {
            SelfTestLog.add("sessions selftest hide SKIPPED (no sessions)")
            return
        }
        let hideError = await environment.bridge.hideSession(id: target.id)
        let hiddenList = await load(environment)
        let hiddenGone = !hiddenList.contains(where: { $0.id == target.id })
        SelfTestLog.add("sessions selftest hide session=\(target.id) error=\(hideError ?? "-") count=\(list.count)->\(hiddenList.count) gone=\(hiddenGone) PASS=\(hideError == nil && hiddenGone)")
        logHiddenIds(environment, label: "afterHide")

        // 通过真实引擎发送一条消息（engine 会 emit EngineEvent.UnhideSession）触发自动恢复
        let sendResult = await sendOnce(environment: environment, sessionId: target.id)
        var restored = false
        for _ in 0..<20 {
            let now = await load(environment)
            if now.contains(where: { $0.id == target.id }) { restored = true; break }
            try? await Task.sleep(nanoseconds: 100_000_000)
        }
        let finalList = await load(environment)
        let recovered = finalList.first(where: { $0.id == target.id })
        SelfTestLog.add("sessions selftest unhideByNewMessage session=\(target.id) sendOk=\(sendResult.ok) demo=\(sendResult.isDemo) sendError=\(sendResult.error.isEmpty ? "-" : sendResult.error) restored=\(restored) lastMessage=\(recovered?.lastMessage.prefix(30) ?? "-") PASS=\(restored)")

        // 5) 移除主页所有会话（「⋮」菜单第一条）→ 空列表；恢复 hiddenIds 后恢复
        list = await load(environment)
        let hideAllError = await environment.bridge.hideAllSessions()
        let emptyList = await load(environment)
        let emptied = emptyList.isEmpty
        SelfTestLog.add("sessions selftest hideAll error=\(hideAllError ?? "-") count=\(list.count)->\(emptyList.count) empty=\(emptied) PASS=\(hideAllError == nil && emptied)")
        logHiddenIds(environment, label: "afterHideAll")

        environment.bridge.setSettingsString(key: "hidden_ids", value: "[]")
        let restoredList = await load(environment)
        SelfTestLog.add("sessions selftest hideAllRestore count=\(restoredList.count) restored=\(restoredList.count == list.count) PASS=\(restoredList.count == list.count)")

        await environment.refreshCounts()
        SelfTestLog.add("sessions selftest done sessions=\(environment.sessionCount)")
    }

    // MARK: - 辅助

    private static func load(_ environment: AppEnvironment) async -> [SessionItem] {
        switch await environment.bridge.loadSessions() {
        case .success(let items): return items
        case .failure(let error):
            SelfTestLog.add("sessions selftest load FAILED error=\(error.message)")
            return []
        }
    }

    private static func sendOnce(environment: AppEnvironment, sessionId: String) async -> ChatRoundResult {
        await withCheckedContinuation { continuation in
            _ = environment.bridge.sendChat(
                sessionId: sessionId,
                text: "自检：会话列表操作验证（隐藏后新消息应自动恢复）",
                onDelta: { _ in },
                onDone: { result in continuation.resume(returning: result) }
            )
        }
    }

    /// 读回 shared settings.hiddenIds（经 SettingsBridge 原始字符串键，与 Android 键名一致：hidden_ids）
    private static func logHiddenIds(_ environment: AppEnvironment, label: String) {
        let raw = environment.bridge.settingsString(key: "hidden_ids")
        SelfTestLog.add("sessions selftest hiddenIds \(label)=\(raw)")
    }
}
