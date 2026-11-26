//
//  ChatListView.swift
//  聊天列表（Tab 根，A1 入口；P1 视觉重写，业务逻辑零改动）。
//
//  设计依据：docs/敲敲UI改版计划书-v1.1.md
//  - 第 1.2 节条形栏规格：品牌顶栏（黄手绘 logo + 白色 heavy 品牌字）+ 大标题 + 搜索条
//    （胶囊 + 1.5pt 描边 + 右侧竖分隔线 + 放大镜）+ **胶囊会话行**（底 `#171717`、1.5pt 描边、
//    左 44pt 圆形头像、主标题 15pt heavy、副标题 13pt 灰）；
//  - 顶栏溢出菜单换敲敲弹层（面板 + 胶囊行），二次确认用 `KnockConfirmDialog`；
//  - 会话行尾：未读 `KnockBadge`（聊天蓝，聊天语义）+ 置顶标记 + 时间；
//  - 第 7.1-9 / 第五章：隐藏系统导航栏与 TabBar，iOS 26+ 关闭滚动边缘玻璃效果。
//
//  数据与操作（T40/T44 语义完全保留）：
//  - 会话来自 shared `SessionListBridge`（真实 SQLite）；下拉刷新 `.refreshable`；
//  - 溢出菜单：移除主页所有会话（有确认）、全部标记已读（直接执行）；
//  - 会话行长按：置顶/取消置顶、标记已读、从主页移除（确认弹窗，文案对齐原版）；
//  - `--unread-selftest` 自检链路与网络/桥调用逻辑原样保留。
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
    /// 二次确认弹窗（从主页移除单个 / 移除主页所有会话）
    @State private var confirmAction: ConfirmAction?
    /// T44 自检只跑一次（视图重建不重复跑）
    @State private var unreadSelftestDidStart = false
    /// 搜索（本地过滤会话标题 / 最近消息）
    @State private var searchText = ""
    /// 顶栏「⋮」溢出菜单（敲敲弹层）
    @State private var isOverflowPresented = false

    // MARK: 派生

    private var filteredSessions: [SessionItem] {
        let keyword = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !keyword.isEmpty else { return sessions }
        return sessions.filter {
            $0.title.localizedCaseInsensitiveContains(keyword)
                || $0.lastMessage.localizedCaseInsensitiveContains(keyword)
        }
    }

    private var unreadTotal: Int {
        sessions.reduce(0) { $0 + $1.unreadCount }
    }

    /// 头部汇总：会话数 + 未读（产品化文案，不暴露桥/任务号）
    private var headerSummary: String {
        sessions.isEmpty ? "还没有开始对话" : "\(sessions.count) 个会话 · 最近消息优先"
    }

    var body: some View {
        ZStack {
            KnockCamoBackground()

            ScrollView {
                LazyVStack(spacing: KnockTokens.spacingM) {
                    topBar
                    titleBlock
                    KnockSearchBar(placeholder: "搜索会话", text: $searchText)

                    if let actionError {
                        errorRow(actionError)
                    }

                    contentRows
                }
                .padding(.horizontal, KnockTokens.screenPadding)
                .padding(.top, KnockTokens.spacingS)
                .padding(.bottom, KnockTokens.spacingL)
            }
            .scrollIndicators(.hidden)
            .knockScrollEdgeEffectHidden()
            .refreshable { await reload() }
        }
        // 计划书 2.3 / 7.1-9：隐藏系统导航栏（TabBar 由 RootTabView 统一隐藏）
        .toolbar(.hidden, for: .navigationBar)
        .overlay {
            if isOverflowPresented {
                overflowOverlay
            }
        }
        .overlay {
            if let action = confirmAction {
                confirmOverlay(action)
            }
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

    // MARK: - 顶部（品牌顶栏 + 大标题 + 搜索）

    private var topBar: some View {
        HStack(spacing: KnockTokens.spacingM) {
            KnockBrandMark()
            Spacer(minLength: KnockTokens.spacingS)
            KnockCapsuleIconButton(
                systemImage: "ellipsis",
                variant: .neutral,
                accessibilityLabel: "会话菜单"
            ) {
                isOverflowPresented = true
            }
        }
        .padding(.top, KnockTokens.spacingXS)
    }

    private var titleBlock: some View {
        VStack(alignment: .leading, spacing: KnockTokens.spacingXS) {
            HStack(alignment: .firstTextBaseline, spacing: KnockTokens.spacingS) {
                KnockText("聊天", role: .display, lineLimit: 1)
                Spacer(minLength: KnockTokens.spacingS)
                if unreadTotal > 0 {
                    KnockBadge(text: "\(unreadTotal) 未读", variant: .chat)
                }
            }
            KnockText(headerSummary, role: .caption, color: KnockTokens.textSecondary, lineLimit: 1)
        }
    }

    // MARK: - 内容区（加载 / 错误 / 空态 / 会话行）

    @ViewBuilder
    private var contentRows: some View {
        if let errorText {
            KnockEmptyState(
                title: "会话读取失败",
                message: errorText,
                systemImage: "exclamationmark.triangle",
                actionTitle: "重试",
                action: { Task { await reload() } }
            )
        } else if isLoading && sessions.isEmpty {
            loadingRow
        } else if sessions.isEmpty {
            // 与原版一致：聊天主页空态只有「暂无聊天记录」；新建入口在联系人 Tab。
            KnockEmptyState(
                title: "暂无聊天记录",
                message: "所有会话可能都已被移除主页，或尚未创建会话。新建聊天请到「联系人」Tab。",
                systemImage: "bubble.left.and.bubble.right"
            )
        } else if filteredSessions.isEmpty {
            KnockEmptyState(
                title: "没有匹配的会话",
                message: "没有标题或最近消息匹配「\(searchText)」的会话。",
                systemImage: "magnifyingglass"
            )
        } else {
            ForEach(filteredSessions) { session in
                sessionRow(session)
            }
        }
    }

    private var loadingRow: some View {
        HStack(spacing: KnockTokens.spacingM) {
            ProgressView()
                .controlSize(.small)
                .tint(KnockTokens.electric)
            KnockText("读取 shared 会话…", role: .caption, color: KnockTokens.textSecondary)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, KnockTokens.spacingL)
        .frame(minHeight: 56)
        .background(Capsule(style: .continuous).fill(KnockTokens.panel))
        .overlay {
            Capsule(style: .continuous)
                .strokeBorder(KnockTokens.stroke, lineWidth: KnockTokens.strokeWidth)
        }
    }

    /// 操作错误条：实色面板 + 危险红图标（无毛玻璃）。
    private func errorRow(_ text: String) -> some View {
        HStack(spacing: KnockTokens.spacingS) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.system(size: 14, weight: .heavy))
                .foregroundStyle(KnockTokens.dangerBright)
            KnockText(text, role: .caption, color: KnockTokens.textPrimary, lineLimit: 2)
            Spacer(minLength: 0)
            KnockCloseButton(label: "关闭提示") { actionError = nil }
        }
        .padding(.horizontal, KnockTokens.spacingM)
        .padding(.vertical, KnockTokens.spacingS)
        .background(RoundedRectangle(cornerRadius: KnockTokens.panelRadiusSmall, style: .continuous).fill(KnockTokens.panel))
        .overlay {
            RoundedRectangle(cornerRadius: KnockTokens.panelRadiusSmall, style: .continuous)
                .strokeBorder(KnockTokens.stroke, lineWidth: KnockTokens.strokeWidth)
        }
    }

    // MARK: - 会话行（胶囊行：头像 / 未读 / 置顶 / 时间）

    private func sessionRow(_ session: SessionItem) -> some View {
        KnockListRow(
            title: session.title,
            subtitle: rowSubtitle(session),
            action: { open(session) }
        ) {
            KnockAvatar(
                avatarId: session.avatarId,
                name: session.title,
                size: KnockTokens.rowIconSize
            )
            .accessibilityHidden(true)
        } trailing: {
            VStack(alignment: .trailing, spacing: 6) {
                KnockText(session.lastTimeText, role: .caption, color: KnockTokens.textTertiary, lineLimit: 1)
                HStack(spacing: 4) {
                    if session.isPinned {
                        Image(systemName: "pin.fill")
                            .font(.system(size: 9, weight: .heavy))
                            .foregroundStyle(KnockTokens.textTertiary)
                            .accessibilityHidden(true)
                    }
                    if session.unreadCount > 0 {
                        KnockBadge(text: "\(session.unreadCount)", variant: .chat)
                    }
                }
            }
        }
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
        .accessibilityLabel("\(session.title)，\(session.lastMessage)\(session.isPinned ? "，已置顶" : "")")
        .accessibilityHint("打开与 \(session.title) 的聊天；长按可置顶、标记已读或从主页移除")
    }

    /// 行副标题：最近消息；非线上模式前缀模式名（保留原模式信息，不额外增加行内控件）。
    private func rowSubtitle(_ session: SessionItem) -> String {
        // 最近消息可能含显式换行：行副标题压成单段（多行交给 KnockListRow 的 lineLimit）。
        let raw = session.lastMessage.replacingOccurrences(of: "\n", with: " ")
        let message = raw.isEmpty ? "（暂无消息，点击开始对话）" : raw
        let mode = session.modeText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !mode.isEmpty, mode != "线上" else { return message }
        return "\(mode) · \(message)"
    }

    private func open(_ session: SessionItem) {
        if session.id.hasPrefix("group_") {
            router.chatPath.append(Route.groupChat(sessionId: session.id))
        } else {
            router.chatPath.append(Route.chat(sessionId: session.id))
        }
    }

    // MARK: - 顶栏溢出菜单（敲敲弹层）

    private var overflowOverlay: some View {
        ZStack(alignment: .topTrailing) {
            Color.black.opacity(0.45)
                .ignoresSafeArea()
                .onTapGesture { isOverflowPresented = false }

            KnockDialogPanel(showsChecker: true) {
                VStack(alignment: .leading, spacing: KnockTokens.spacingXS) {
                    overflowRow("移除主页所有会话", systemImage: "trash", destructive: true) {
                        isOverflowPresented = false
                        confirmAction = .hideAll
                    }
                    overflowRow("全部标记已读", systemImage: "envelope.open") {
                        isOverflowPresented = false
                        Task { await markAllRead() }
                    }
                    Button("取消") { isOverflowPresented = false }
                        .buttonStyle(KnockSecondaryButtonStyle())
                        .padding(.top, KnockTokens.spacingXS)
                }
            }
            .frame(width: 268)
            .padding(.horizontal, KnockTokens.screenPadding)
            .padding(.top, 52)
        }
        .transition(.opacity)
    }

    private func overflowRow(
        _ title: String,
        systemImage: String,
        destructive: Bool = false,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: KnockTokens.spacingS) {
                Image(systemName: systemImage)
                    .font(.system(size: 14, weight: .heavy))
                    .foregroundStyle(destructive ? KnockTokens.dangerBright : KnockTokens.textPrimary)
                KnockText(
                    title,
                    role: .rowTitle,
                    color: destructive ? KnockTokens.dangerBright : KnockTokens.textPrimary,
                    lineLimit: 1
                )
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 14)
            .frame(minHeight: 44)
            .background(Capsule(style: .continuous).fill(KnockTokens.row))
            .overlay {
                Capsule(style: .continuous)
                    .strokeBorder(KnockTokens.strokeSecondary, lineWidth: KnockTokens.strokeWidth)
            }
            .contentShape(Capsule(style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title)
    }

    private func confirmOverlay(_ action: ConfirmAction) -> some View {
        ZStack {
            Color.black.opacity(0.55)
                .ignoresSafeArea()
                .onTapGesture { confirmAction = nil }

            KnockConfirmDialog(
                title: action.title,
                message: action.message,
                confirmTitle: "确认移除",
                isDestructive: true,
                onConfirm: {
                    confirmAction = nil
                    Task { await perform(action) }
                },
                onCancel: { confirmAction = nil }
            )
            .padding(.horizontal, 28)
        }
        .transition(.opacity)
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