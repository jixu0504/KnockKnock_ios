//
//  ChatListView.swift
//  聊天列表（Tab 根，A1 入口）：会话来自 shared `SessionListBridge`（真实 SQLite）。
//  导航：点击会话 → Route.chat(sessionId:)（NavigationStack push）；
//  头像：chat_sessions.avatarUri 的内置数字串经 RoleAvatar 解析 Bundle 资源。
//
//  T40「会话列表操作对齐 Android 原版」（原版 SessionListScreen.kt:68-130）：
//  - 工具栏右上角只有「⋮」溢出菜单：移除主页所有会话（有确认）、全部标记已读（直接执行）；
//    原「新建聊天」铅笔按钮已移除——新建聊天入口在「联系人」Tab（对齐 Android ContactsScreen）；
//    原「刷新」按钮已移除——保留下拉刷新 `.refreshable` 作为刷新手段；
//  - 会话行长按 `.contextMenu`：置顶/取消置顶、标记已读、从主页移除（确认弹窗，文案对齐原版）；
//  - 数据语义：pin/read → shared SessionRepository；移除 → settings.hiddenIds（不删库/不删消息，
//    收到新消息后由 shared 引擎事件自动恢复）；列表顺序由 SQL `isPinned DESC, lastTime DESC` 保证。
//
//  T44 未读链路：
//  - 未读角标（`session.unreadCount > 0`）本页已渲染；shared 消费 `EngineEvent.UnreadHint` 自增未读后，
//    本页在「从聊天页返回」时 reload 拿最新未读数（`.onChange(of: router.chatPath.count)`）；
//  - 返回列表 = 离开会话页 → `ChatEngineBridge.setActiveSession("")` 清除「当前查看会话」标记，
//    之后到达的 AI 回复才会计未读（ChatView.swift 不在 T44 写域，故清理由本页 chatPath 观察完成）；
//  - 自检钩子 `--unread-selftest`：预置未读 → 列表角标 → 打开会话清零 → 当前会话回复不增 →
//    非当前会话回复 +1 → 桥直接 incrementUnread 再 +1（见文件末尾 runUnreadSelftestIfNeeded）。
//
//  玻璃元素计数：头部卡片 1（设计原则一屏 ≤5）。
//

import SwiftUI
import Foundation

struct ChatListView: View {
    @EnvironmentObject private var environment: AppEnvironment
    @EnvironmentObject private var router: AppRouter

    @State private var sessions: [SessionItem] = []
    @State private var isLoading = false
    @State private var errorText: String?
    @State private var actionError: String?
    @State private var scrollOffset: CGFloat = 0
    /// 二次确认弹窗（从主页移除单个 / 移除主页所有会话）
    @State private var confirmAction: ConfirmAction?
    /// T44 自检只跑一次（视图重建不重复跑）
    @State private var unreadSelftestDidStart = false

    var body: some View {
        ZStack {
            AmbientBackground(parallax: scrollOffset, showsStandee: false)

            ScrollView {
                LazyVStack(spacing: 10) {
                    headerCard
                        .padding(.horizontal, 14)

                    if let actionError {
                        Label(actionError, systemImage: "exclamationmark.triangle")
                            .font(.footnote)
                            .foregroundStyle(.red)
                            .padding(12)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .cardSurface(cornerRadius: 14)
                    }

                    if let errorText {
                        EmptyStateView(
                            title: "会话读取失败",
                            message: errorText,
                            systemImage: "exclamationmark.triangle"
                        )
                    } else if isLoading && sessions.isEmpty {
                        ProgressView("读取 shared 会话…")
                            .padding(40)
                    } else if sessions.isEmpty {
                        // 与原版一致：聊天主页空态只有「暂无聊天记录」；新建入口在联系人 Tab。
                        EmptyStateView(
                            title: "暂无聊天记录",
                            message: "所有会话可能都已被移除主页，或尚未创建会话。新建聊天请到「联系人」Tab。",
                            systemImage: "bubble.left.and.bubble.right"
                        )
                    } else {
                        ForEach(sessions) { session in
                            sessionRow(session)
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .padding(.bottom, 16)
            }
            .scrollIndicators(.hidden)
            .onScrollGeometryChange(for: CGFloat.self) { geometry in
                geometry.contentOffset.y
            } action: { _, newValue in
                scrollOffset = newValue
            }
            .refreshable { await reload() }
        }
        .navigationTitle("聊天")
        .toolbar {
            // 原版聊天列表右上角唯一的「⋮」溢出菜单（移除主页所有会话 / 全部标记已读）
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button(role: .destructive) {
                        confirmAction = .hideAll
                    } label: {
                        Label("移除主页所有会话", systemImage: "trash")
                    }
                    Button {
                        Task { await markAllRead() }
                    } label: {
                        Label("全部标记已读", systemImage: "envelope.open")
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
                .accessibilityLabel("会话菜单")
                .accessibilityHint("移除主页所有会话 / 全部标记已读")
            }
        }
        .alert(
            confirmAction?.title ?? "",
            isPresented: Binding(
                get: { confirmAction != nil },
                set: { if !$0 { confirmAction = nil } }
            ),
            presenting: confirmAction
        ) { action in
            Button("取消", role: .cancel) { confirmAction = nil }
            Button("确认移除", role: .destructive) {
                confirmAction = nil
                Task { await perform(action) }
            }
        } message: { action in
            Text(action.message)
        }
        .task {
            await reload()
            // T44 未读链路自检（仅 --unread-selftest；对用户路径零影响）
            await runUnreadSelftestIfNeeded()
        }
        .onChange(of: router.chatPath.count) { _, newCount in
            if newCount == 0 {
                // T44：返回列表 = 离开会话页，清除「当前查看会话」标记；
                // 之后到达的非当前会话 AI 回复才会累计未读（对齐 Android _currentSession）。
                environment.bridge.chatEngineBridge.setActiveSession(sessionId: "")
                SelfTestLog.add("chat unread active cleared (back to list)")
            }
            // 从聊天页返回列表时刷新（新消息可能已解除隐藏 / 更新未读与顺序）
            Task { await reload() }
        }
    }

    // MARK: 头部（玻璃 1）

    private var headerCard: some View {
        GlassPanel(style: .clear, cornerRadius: 20) {
            HStack(spacing: 12) {
                Image(systemName: "bubble.left.and.bubble.right.fill")
                    .font(.system(size: 20))
                    .foregroundStyle(GlassTokens.brandOrange)
                VStack(alignment: .leading, spacing: 2) {
                    Text("全部会话")
                        .font(.subheadline.weight(.semibold))
                    Text(headerSummary)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 8)
                if unreadTotal > 0 {
                    Text("\(unreadTotal) 条未读")
                        .font(.caption2.weight(.bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(Capsule().fill(GlassTokens.unreadBadge))
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
        }
    }

    /// 头部汇总：会话数 + 未读（产品化文案，不暴露桥/任务号）
    private var headerSummary: String {
        sessions.isEmpty ? "还没有开始对话" : "\(sessions.count) 个会话 · 最近消息优先"
    }

    private var unreadTotal: Int {
        sessions.reduce(0) { $0 + $1.unreadCount }
    }

    // MARK: 会话行（非玻璃）

    private func sessionRow(_ session: SessionItem) -> some View {
        Button {
            if session.id.hasPrefix("group_") {
                router.chatPath.append(Route.groupChat(sessionId: session.id))
            } else {
                router.chatPath.append(Route.chat(sessionId: session.id))
            }
        } label: {
            HStack(alignment: .top, spacing: 12) {
                RoleAvatar(avatarId: session.avatarId, name: session.title, size: 48)

                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 6) {
                        if session.isPinned {
                            Image(systemName: "pin.fill")
                                .font(.caption2)
                                .foregroundStyle(GlassTokens.brandOrange)
                                .accessibilityLabel("已置顶")
                        }
                        Text(session.title)
                            .font(.headline)
                            .foregroundStyle(.primary)
                            .lineLimit(1)
                        Text(session.modeText)
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 1)
                            .background(GlassTokens.bubbleSystem, in: Capsule())
                        Spacer(minLength: 0)
                        Text(session.lastTimeText)
                            .font(.caption2)
                            .foregroundStyle(.tertiary)
                    }
                    Text(session.lastMessage.isEmpty ? "（暂无消息，点击开始对话）" : session.lastMessage)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                }

                if session.unreadCount > 0 {
                    Text("\(session.unreadCount)")
                        .font(.caption2.weight(.bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 7)
                        .padding(.vertical, 3)
                        .background(Capsule().fill(GlassTokens.brandOrange))
                }
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .cardSurface(cornerRadius: 20)
        }
        .buttonStyle(.plain)
        // 原版：长按会话行弹出「置顶/取消置顶、标记已读、从主页移除」
        .contextMenu {
            Button {
                Task { await togglePin(session) }
            } label: {
                Label(session.isPinned ? "取消置顶" : "置顶",
                      systemImage: session.isPinned ? "pin.slash" : "pin")
            }
            Button {
                Task { await markRead(session) }
            } label: {
                Label("标记已读", systemImage: "envelope.open")
            }
            Button(role: .destructive) {
                confirmAction = .hide(session)
            } label: {
                Label("从主页移除", systemImage: "trash")
            }
        }
        .accessibilityLabel("\(session.title)，\(session.lastMessage)")
        .accessibilityHint("打开与 \(session.title) 的聊天；长按可置顶、标记已读或从主页移除")
    }

    // MARK: - 操作（T40）

    /// 二次确认类型（文案与原版 SessionListScreen.kt:104-111 一致）
    private enum ConfirmAction: Identifiable {
        case hide(SessionItem)
        case hideAll

        var id: String {
            switch self {
            case .hide(let session): return "hide-\(session.id)"
            case .hideAll: return "hideAll"
            }
        }

        var title: String {
            switch self {
            case .hide: return "从主页移除"
            case .hideAll: return "移除主页所有会话"
            }
        }

        var message: String {
            switch self {
            case .hide:
                return "会话将暂时不在聊天主页显示。收到新的消息后，会自动重新出现在主页。"
            case .hideAll:
                return "所有会话将暂时不在聊天主页显示。任一会话收到新的消息后，会自动重新出现在主页。"
            }
        }
    }

    private func perform(_ action: ConfirmAction) async {
        let error: String?
        switch action {
        case .hide(let session):
            error = await environment.bridge.hideSession(id: session.id)
        case .hideAll:
            error = await environment.bridge.hideAllSessions()
        }
        actionError = error
        SelfTestLog.add("sessions action \(action.id) error=\(error ?? "-")")
        await reload()
    }

    private func togglePin(_ session: SessionItem) async {
        let error = await environment.bridge.togglePinSession(id: session.id)
        actionError = error
        SelfTestLog.add("sessions action togglePin session=\(session.id) -> \(session.isPinned ? "unpin" : "pin") error=\(error ?? "-")")
        await reload()
    }

    private func markRead(_ session: SessionItem) async {
        let error = await environment.bridge.markSessionRead(id: session.id)
        actionError = error
        SelfTestLog.add("sessions action markRead session=\(session.id) unreadBefore=\(session.unreadCount) error=\(error ?? "-")")
        await reload()
    }

    private func markAllRead() async {
        let error = await environment.bridge.markAllSessionsRead()
        actionError = error
        SelfTestLog.add("sessions action markAllRead error=\(error ?? "-")")
        await reload()
    }

    // MARK: 数据

    private func reload() async {
        isLoading = true
        defer { isLoading = false }

        switch await environment.bridge.loadSessions() {
        case .success(let items):
            sessions = items
            errorText = nil
            SelfTestLog.add("sessions list loaded count=\(items.count) first=\(items.first?.id ?? "-") pinned=\(items.filter(\.isPinned).map(\.id))")
        case .failure(let error):
            errorText = error.message
            SelfTestLog.add("sessions list failed error=\(error.message)")
        }

        await environment.refreshCounts()
    }

    // MARK: - T44 未读链路自检（仅 `--unread-selftest` 启动参数启用）

    /// 校验链路（全部走真实 Shared.framework / SQLite，不 mock）：
    /// 1) DB 预置未读 → 列表角标显示（`unreadCount` > 0）；
    /// 2) 打开会话（真实路由 → ChatView → ChatViewModel.load）→ shared markRead → 未读清零、角标消失；
    /// 3) 当前会话内发送 → 引擎 `UnreadHint` 被 ChatEngineBridge 忽略（当前会话）→ 未读仍为 0；
    /// 4) 返回列表（清除 activeSession）→ 直接经桥发送模拟「非当前会话收到 AI 回复」→ 未读 +1 → 角标 +1；
    /// 5) 桥 `debugIncrementUnread` 再 +1（与真实 UnreadHint 同一 DB 列，断言精确增量）。
    /// 结束时停在列表页并保留未读角标（截图证据）。
    private func runUnreadSelftestIfNeeded() async {
        guard ProcessInfo.processInfo.arguments.contains("--unread-selftest"), !unreadSelftestDidStart else { return }
        unreadSelftestDidStart = true
        SelfTestLog.add("=== unread selftest start ===")
        let bridge = environment.bridge

        // 等 shared 桥/DB 就绪（AppEnvironment.start 幂等）
        await environment.start()
        var waitCount = 0
        while !environment.ready && waitCount < 100 {
            try? await Task.sleep(nanoseconds: 100_000_000)
            waitCount += 1
        }
        // `--selftest` 会同时跑整壳自检（含深链打开聊天页 + 发消息）；等它收尾再开始，避免互相干扰。
        if ProcessInfo.processInfo.arguments.contains("--selftest") {
            _ = await waitForLogMarker("=== selftest done ===", deadlineSeconds: 120)
            SelfTestLog.add("unread selftest start after shell selftest done")
        }

        // 起始态：全部已读、无「当前查看会话」
        bridge.chatEngineBridge.setActiveSession(sessionId: "")
        _ = await bridge.markAllSessionsRead()
        guard let list0 = await loadSessionsForAutomation(bridge),
              let target = list0.first(where: { !$0.id.hasPrefix("group_") }) else {
            SelfTestLog.add("unread selftest baseline FAIL (no private session)")
            return
        }
        let targetId = target.id
        SelfTestLog.add("unread selftest baseline session=\(targetId) title=\(target.title) unread=\(target.unreadCount)")

        // 1) DB 预置未读 2/1 → 列表角标
        let seedError = await seedUnread(bridge, sessionId: targetId, delta: 2)
        let seededBadge = (await loadSessionsForAutomation(bridge))?
            .first(where: { $0.id == targetId })?.unreadCount ?? -1
        let seedPass = seedError == nil && seededBadge == 2
        SelfTestLog.add("unread selftest seed session=\(targetId) delta=2 error=\(seedError ?? "-") badge=\(seededBadge) PASS=\(seedPass)")
        await reload()
        try? await Task.sleep(nanoseconds: 1_500_000_000) // 截图点：列表未读角标

        // 2) 打开会话 → ChatViewModel.load 调 markRead 清零
        router.openChat(sessionId: targetId)
        let activeReady = await waitForLogMarker("chat unread active session=\(targetId)")
        try? await Task.sleep(nanoseconds: 800_000_000)
        let unreadAfterOpen = (await bridge.findSession(id: targetId))?.unreadCount ?? -1
        let openPass = unreadAfterOpen == 0
        SelfTestLog.add("unread selftest openChat session=\(targetId) activeReady=\(activeReady) unreadAfterOpen=\(unreadAfterOpen) PASS=\(openPass)")
        try? await Task.sleep(nanoseconds: 1_500_000_000) // 截图点：聊天页（已读清零后）

        // 3) 当前查看会话内收到回复 → 不应累加未读（真实 UnreadHint 路径）
        let viewingSendOK = await sendProbe(bridge, sessionId: targetId, text: "T44自检：当前会话内发送，不应产生未读。")
        let unreadWhileViewing = (await bridge.findSession(id: targetId))?.unreadCount ?? -1
        let viewingPass = viewingSendOK && unreadWhileViewing == 0
        SelfTestLog.add("unread selftest viewingSend session=\(targetId) sendOk=\(viewingSendOK) unread=\(unreadWhileViewing) PASS=\(viewingPass)")

        // 4) 返回列表 → 角标仍为 0（reload 拿最新未读数）
        router.chatPath = NavigationPath()
        _ = await waitForLogMarker("chat unread active cleared")
        try? await Task.sleep(nanoseconds: 800_000_000)
        await reload()
        let badgeAfterBack = (await loadSessionsForAutomation(bridge))?
            .first(where: { $0.id == targetId })?.unreadCount ?? -1
        let backPass = badgeAfterBack == 0
        SelfTestLog.add("unread selftest backToList session=\(targetId) badge=\(badgeAfterBack) PASS=\(backPass)")

        // 5) 非当前会话收到 AI 回复（真实引擎 → 真实 UnreadHint → incrementUnread）
        let replySendOK = await sendProbe(bridge, sessionId: targetId, text: "T44自检：模拟非当前会话收到 AI 回复。")
        let unreadAfterAi = await pollUnread(bridge, sessionId: targetId, deadlineSeconds: 20) { $0 >= 1 }
        let aiPass = replySendOK && unreadAfterAi >= 1
        SelfTestLog.add("unread selftest aiReplyWhileAway session=\(targetId) sendOk=\(replySendOK) unread=\(unreadAfterAi) PASS=\(aiPass)")
        await reload()
        try? await Task.sleep(nanoseconds: 1_200_000_000) // 截图点：AI 回复后角标出现

        // 6) 桥直接 incrementUnread 1 → 角标精确 +1（模拟 AI 回复的落库结果）
        let beforeInc = (await bridge.findSession(id: targetId))?.unreadCount ?? -1
        let incError = await seedUnread(bridge, sessionId: targetId, delta: 1)
        let afterInc = (await bridge.findSession(id: targetId))?.unreadCount ?? -1
        let incPass = incError == nil && beforeInc > 0 && afterInc == beforeInc + 1
        SelfTestLog.add("unread selftest increment session=\(targetId) before=\(beforeInc) after=\(afterInc) error=\(incError ?? "-") incrementPASS=\(incPass)")
        await reload()

        let allPass = seedPass && openPass && viewingPass && backPass && aiPass && incPass
        SelfTestLog.add("=== unread selftest done allPASS=\(allPass) ===")
        SelfTestLog.flushToDisk(
            summary: "T44 unread selftest（预置未读→角标→打开清零→当前会话不增→非当前回复+1→增量+1）"
        )
    }

    // MARK: - T44 自检辅助

    /// 读会话列表（reload() 会更新 UI；自检断言直接读桥返回值，避免 @State 时序影响）。
    private func loadSessionsForAutomation(_ bridge: AppGraphClient) async -> [SessionItem]? {
        if case .success(let items) = await bridge.loadSessions() { return items }
        return nil
    }

    /// 桥自检夹具：直接对 DB `chat_sessions.unreadCount` 加 delta（模拟 AI 回复落库后的未读）。
    private func seedUnread(_ bridge: AppGraphClient, sessionId: String, delta: Int32) async -> String? {
        await withCheckedContinuation { continuation in
            bridge.chatEngineBridge.debugIncrementUnread(sessionId: sessionId, delta: delta) { error in
                continuation.resume(returning: error)
            }
        }
    }

    /// 经真实 ChatEngineBridge 发送一条探测消息（等价 ChatViewModel.send 的引擎路径，桥不感知 UI）。
    private func sendProbe(_ bridge: AppGraphClient, sessionId: String, text: String) async -> Bool {
        await withCheckedContinuation { continuation in
            var resumed = false
            _ = bridge.sendChat(sessionId: sessionId, text: text, onDelta: { _ in }, onDone: { result in
                SelfTestLog.add("unread selftest probe reply ok=\(result.ok) aiId=\(result.aiMessageId) chars=\(result.text.count) error=\(result.error)")
                if !resumed {
                    resumed = true
                    continuation.resume(returning: result.ok)
                }
            })
        }
    }

    /// 轮询会话未读数直到满足 predicate 或超时；返回最后一次读到的值。
    private func pollUnread(
        _ bridge: AppGraphClient,
        sessionId: String,
        deadlineSeconds: TimeInterval,
        until predicate: (Int) -> Bool
    ) async -> Int {
        let deadline = Date().addingTimeInterval(deadlineSeconds)
        var value = -1
        while Date() < deadline {
            value = (await bridge.findSession(id: sessionId))?.unreadCount ?? -1
            if predicate(value) { return value }
            try? await Task.sleep(nanoseconds: 200_000_000)
        }
        return value
    }

    /// 等待 self test 落盘日志出现标记（--selftest 时才记录；未开启时退化为短等待）。
    private func waitForLogMarker(_ marker: String, deadlineSeconds: TimeInterval = 20) async -> Bool {
        guard SelfTestLog.isEnabled else {
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            return false
        }
        let deadline = Date().addingTimeInterval(deadlineSeconds)
        while Date() < deadline {
            if SelfTestLog.snapshot().contains(where: { $0.contains(marker) }) { return true }
            try? await Task.sleep(nanoseconds: 200_000_000)
        }
        return false
    }
}
