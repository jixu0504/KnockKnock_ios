//
//  GroupChatView.swift
//  T43 群聊页（P1 视觉重写，业务逻辑零改动）。
//
//  设计依据：docs/敲敲UI改版计划书-v1.1.md 图 A + 第 2.3 / 7.1 节：
//  - 顶栏换 44pt 自绘 `KnockHeader`（返回胶囊 + 群名/成员数副标题 + 成员按钮），隐藏系统导航栏；
//  - 纯黑底 + 斜切迷彩；消息区间距同人 10pt / 换人 18pt；时间分隔 `- … -` 灰色居中；
//  - 旁白使用漫画旁白（左 2pt 黄竖条 + 斜体灰白字）；系统消息灰色小字居中；
//  - 成员 Sheet 换敲敲面板 + 胶囊行 + 黄色开关，背景实色（无毛玻璃）。
//
//  能力保留（T43）：
//  - 多发言人气泡（senderName）+ 群标题/成员数头部 + 成员列表 Sheet（成员/禁言/权限/自动互动开关）；
//  - 长按 AI 气泡「撤回分段」、未送达「重试」；无「重说/继续说」（群聊引擎无此入口，差异白名单）；
//  - 自检钩子 `--group-selftest` / `--group-unread-selftest` / `--group-open-members` 全部保留。
//

import SwiftUI

struct GroupChatView: View {
    @StateObject private var model: GroupChatViewModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dismiss) private var dismiss
    @State private var pendingRecall: GroupBubbleRow?
    /// 自己发的群消息：待撤回 / 待删除（二次确认）
    @State private var pendingOwnRecall: GroupBubbleRow?
    @State private var pendingOwnDelete: GroupBubbleRow?
    /// 群聊模式切换弹窗（线上 / 线下 / 导演）
    @State private var isModePickerPresented = false

    init(sessionId: String) {
        _model = StateObject(wrappedValue: GroupChatViewModel(sessionId: sessionId))
    }

    var body: some View {
        VStack(spacing: 6) {
            header
            VStack(spacing: 0) {
                timeline
                GlassInputBar(
                    text: $model.draft,
                    isStreaming: model.isStreaming,
                    placeholder: "在群里说点什么…",
                    onSend: { model.send() },
                    onCancel: { model.cancel() }
                )
            }
            .background(KnockTokens.chatPanel)
            .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
            .padding(.horizontal, KnockTokens.screenPadding)
            .padding(.bottom, 4)
        }
        .background(GlassTokens.chatBackground)
        // 计划书 2.3 / 7.1-9：隐藏系统导航栏与 TabBar
        .toolbar(.hidden, for: .navigationBar)
        .toolbar(.hidden, for: .tabBar)
        .ignoresSafeArea(.keyboard, edges: .bottom)
        .task {
            await model.load()
            // 自动化证据钩子：`--group-open-members` 启动参数直接展开成员 Sheet（无触控环境截图用）
            if ProcessInfo.processInfo.arguments.contains("--group-open-members") {
                try? await Task.sleep(nanoseconds: 400_000_000)
                model.openMembers()
                GroupSelfTestLog.add("group view members auto-opened (launch arg)")
            }
        }
        .onDisappear { model.onDisappear() }
        .sheet(isPresented: $model.showMembers) {
            GroupMembersSheet(
                detail: model.detail,
                isBusy: model.isAutoBusy,
                onToggleAuto: { enabled in model.toggleAutoChat(enabled) }
            )
        }
        .alert("撤回这一段？", isPresented: recallAlertBinding) {
            Button("撤回", role: .destructive) {
                if let row = pendingRecall { model.recallSegment(row: row) }
                pendingRecall = nil
            }
            Button("取消", role: .cancel) { pendingRecall = nil }
        } message: {
            Text("只撤回这一位成员的发言段；全部段撤回时整条消息会消失。")
        }
        .alert("撤回这条消息？", isPresented: ownRecallAlertBinding) {
            Button("撤回", role: .destructive) {
                if let row = pendingOwnRecall { model.recallOwn(row: row) }
                pendingOwnRecall = nil
            }
            Button("取消", role: .cancel) { pendingOwnRecall = nil }
        } message: {
            Text("这条消息会从本机的群聊记录里移除。")
        }
        .alert("删除这条消息？", isPresented: ownDeleteAlertBinding) {
            Button("删除", role: .destructive) {
                if let row = pendingOwnDelete { model.deleteLocal(row: row) }
                pendingOwnDelete = nil
            }
            Button("取消", role: .cancel) { pendingOwnDelete = nil }
        } message: {
            Text("仅从本机记录中删除，不会撤回。此操作不可恢复。")
        }
        .alert("操作失败", isPresented: errorAlertBinding) {
            Button("好", role: .cancel) { model.clearError() }
        } message: {
            Text(model.errorText ?? "")
        }
        // 操作结果轻提示：敲敲黑底胶囊
        .overlay(alignment: .top) {
            if let feedback = model.actionFeedback {
                KnockText(feedback, role: .captionStrong, color: KnockTokens.textOnDark, lineLimit: 1)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .background(Capsule(style: .continuous).fill(KnockTokens.panelInset))
                    .overlay {
                        Capsule(style: .continuous)
                            .strokeBorder(KnockTokens.stroke, lineWidth: KnockTokens.strokeWidth)
                    }
                    .padding(.top, 8)
                    .transition(.opacity)
                    .accessibilityLabel("操作结果：\(feedback)")
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: .groupSelfTestRefresh)) { _ in
            Task { await model.refresh() }
        }
        // 群聊模式切换弹窗（自绘敲敲面板）
        .overlay {
            if isModePickerPresented {
                modePickerOverlay
            }
        }
        // 自动化取证钩子：`--group-mode-picker` 直接展开模式弹窗
        .task {
            if ProcessInfo.processInfo.arguments.contains("--group-mode-picker") {
                try? await Task.sleep(nanoseconds: 900_000_000)
                isModePickerPresented = true
                GroupSelfTestLog.add("group mode picker auto-opened (launch arg) current=\(model.modeText)")
            }
            // 自动化自检：`--group-mode-selftest` 依次切三模式并落盘证据（实机无触控）
            if ProcessInfo.processInfo.arguments.contains("--group-mode-selftest") {
                try? await Task.sleep(nanoseconds: 700_000_000)
                for target in ["offline", "director", "online"] {
                    await model.setMode(target)
                    GroupSelfTestLog.add("group mode selftest target=\(target) -> mode=\(model.mode) text=\(model.modeText)")
                }
                let ok = model.mode == "online"
                GroupSelfTestLog.add("group mode selftest RESULT PASS=\(ok) final=\(model.mode)")
            }
            // 自动化自检：`--group-recall-selftest` 发一条 → 撤回 → 断言消失（实机无触控取证）
            if ProcessInfo.processInfo.arguments.contains("--group-recall-selftest") {
                try? await Task.sleep(nanoseconds: 800_000_000)
                model.draft = "撤回自检消息 A"
                model.send()
                // 等发送完成（真实供应商可能较慢）
                for _ in 0..<60 where model.isStreaming {
                    try? await Task.sleep(nanoseconds: 500_000_000)
                }
                try? await Task.sleep(nanoseconds: 1_200_000_000)
                let mine = model.rows.last { $0.kind == .outgoing && $0.messageId > 0 }
                GroupSelfTestLog.add("group recall selftest sent ok=\(mine != nil) rows=\(model.rows.count)")
                if let mine {
                    let mid = mine.messageId
                    model.recallOwn(row: mine)
                    try? await Task.sleep(nanoseconds: 1_500_000_000)
                    let stillThere = model.rows.contains { $0.messageId == mid }
                    GroupSelfTestLog.add("group recall selftest PASS=\(!stillThere) messageId=\(mid) stillInTimeline=\(stillThere) rows=\(model.rows.count)")
                } else {
                    GroupSelfTestLog.add("group recall selftest FAIL reason=no-outgoing-row")
                }
                // 再测删除：发一条 → 删除
                model.draft = "删除自检消息 B"
                model.send()
                for _ in 0..<60 where model.isStreaming {
                    try? await Task.sleep(nanoseconds: 500_000_000)
                }
                try? await Task.sleep(nanoseconds: 1_200_000_000)
                if let mine2 = model.rows.last(where: { $0.kind == .outgoing && $0.messageId > 0 }) {
                    let mid2 = mine2.messageId
                    model.deleteLocal(row: mine2)
                    try? await Task.sleep(nanoseconds: 1_500_000_000)
                    let still2 = model.rows.contains { $0.messageId == mid2 }
                    GroupSelfTestLog.add("group delete selftest PASS=\(!still2) messageId=\(mid2) stillInTimeline=\(still2) rows=\(model.rows.count)")
                } else {
                    GroupSelfTestLog.add("group delete selftest FAIL reason=no-outgoing-row")
                }
                GroupSelfTestLog.add("group recall/delete selftest done")
                await runLegacyCleanup()
            }
            // 自动化取证：`--group-mode-offline-demo` 切到线下模式后保持（供截图取证胶囊文案）
            if ProcessInfo.processInfo.arguments.contains("--group-mode-offline-demo") {
                try? await Task.sleep(nanoseconds: 700_000_000)
                await model.setMode("offline")
                GroupSelfTestLog.add("group mode offline demo mode=\(model.modeText)")
            }
        }
    }

    /// 清理早期自检遗留的「自检消息 A/B」（当时钩子用乐观 id 删除无效导致残留）。
    private func runLegacyCleanup() async {
        try? await Task.sleep(nanoseconds: 600_000_000)
        var candidates: [GroupBubbleRow] = []
        for row in model.rows {
            guard row.kind == .outgoing, row.messageId > 0 else { continue }
            if row.text.contains("自检消息 A") || row.text.contains("自检消息 B") {
                candidates.append(row)
            }
        }
        GroupSelfTestLog.add("group selftest cleanup candidates=\(candidates.count)")
        for row in candidates {
            model.deleteLocal(row: row)
            try? await Task.sleep(nanoseconds: 1_200_000_000)
        }
        var leftover = -1
        switch await model.reloadPageFromDB() {
        case .success(let items):
            leftover = items.filter { $0.text.contains("自检消息 A") || $0.text.contains("自检消息 B") }.count
        case .failure:
            leftover = -1
        }
        GroupSelfTestLog.add("group selftest cleanup RESULT leftoverInDB=\(leftover)")
    }

    // MARK: - 模式切换（线上 / 线下 / 导演）

    /// 顶栏模式胶囊：显示当前模式，点击展开三模式弹窗。
    private var modeChip: some View {
        Button {
            isModePickerPresented = true
        } label: {
            HStack(spacing: 3) {
                KnockText(model.modeText, role: .captionStrong, lineLimit: 1)
                Image(systemName: "chevron.down")
                    .font(.system(size: 8, weight: .heavy))
                    .foregroundStyle(KnockTokens.textSecondary)
            }
            .padding(.horizontal, 10)
            .frame(minHeight: 26)
            .background(Capsule(style: .continuous).fill(KnockTokens.row))
            .overlay {
                Capsule(style: .continuous)
                    .strokeBorder(KnockTokens.strokeSecondary, lineWidth: KnockTokens.strokeWidth)
            }
            .contentShape(Capsule(style: .continuous))
        }
        .buttonStyle(.plain)
        .disabled(model.isModeBusy)
        .accessibilityLabel("群聊模式：\(model.modeText)")
        .accessibilityHint("切换线上、线下或导演模式")
    }

    /// 模式选择弹窗（与私聊同款自绘面板）。
    private var modePickerOverlay: some View {
        ZStack {
            Color.black.opacity(0.55)
                .ignoresSafeArea()
                .onTapGesture { isModePickerPresented = false }

            KnockDialogPanel(title: "切换群聊模式") {
                VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                    ForEach(ChatViewModel.chatModes) { item in
                        modeOptionRow(item)
                    }
                    KnockText(
                        "线上：成员只发文字，像在群里打字；线下：面对面群聊，成员会带【旁白】描述动作与环境；导演：你描述场景，成员按你的安排演出。切换后下一轮回复生效。",
                        role: .caption,
                        color: KnockTokens.textSecondary,
                        lineSpacing: 3
                    )
                    Button("取消") { isModePickerPresented = false }
                        .buttonStyle(KnockSecondaryButtonStyle())
                }
            }
            .padding(.horizontal, 28)
        }
        .transition(.opacity)
    }

    private func modeOptionRow(_ item: ChatModeOption) -> some View {
        let isCurrent = item.code == model.mode
        return Button {
            isModePickerPresented = false
            guard !isCurrent else { return }
            Task { await model.setMode(item.code) }
        } label: {
            HStack(spacing: KnockTokens.spacingS) {
                Text(item.icon)
                    .font(.system(size: 15))
                KnockText(
                    item.label,
                    role: .rowTitle,
                    color: isCurrent ? KnockTokens.onElectric : KnockTokens.textPrimary,
                    lineLimit: 1
                )
                Spacer(minLength: KnockTokens.spacingS)
                if isCurrent {
                    Image(systemName: "checkmark")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(KnockTokens.onElectric)
                }
            }
            .padding(.horizontal, KnockTokens.spacingM)
            .frame(minHeight: 44)
            .background(
                Capsule(style: .continuous)
                    .fill(isCurrent ? KnockTokens.electric : KnockTokens.row)
            )
            .overlay {
                Capsule(style: .continuous)
                    .strokeBorder(
                        isCurrent ? KnockTokens.electricRing : KnockTokens.strokeSecondary,
                        lineWidth: KnockTokens.strokeWidth
                    )
            }
            .contentShape(Capsule(style: .continuous))
        }
        .buttonStyle(.plain)
        .disabled(model.isModeBusy)
        .accessibilityLabel("\(item.label)模式")
        .accessibilityAddTraits(isCurrent ? .isSelected : [])
    }

    // MARK: - 顶栏

    /// 参考图聊天顶栏（群聊版）：浮动圆角黑条 = 返回 + 黄色手机标识 + 群名 + 成员胶囊。
    private var header: some View {
        HStack(spacing: KnockTokens.spacingS) {
            backButton
            Image("KnockPhone")
                .resizable()
                .scaledToFit()
                .frame(width: 34, height: 34)
                .accessibilityHidden(true)
            KnockText(model.title, role: .pageTitle, lineLimit: 1, minimumScaleFactor: 0.7)
            Spacer(minLength: KnockTokens.spacingS)
            modeChip
            KnockCapsuleIconButton(
                systemImage: "person.2.fill",
                variant: .neutral,
                accessibilityLabel: "群成员"
            ) {
                model.openMembers()
            }
            .accessibilityHint("查看成员、禁言状态与自动互动开关")
        }
        .padding(.horizontal, KnockTokens.spacingM)
        .frame(height: 50)
        .background(
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .fill(KnockTokens.chatHeaderBar)
        )
        .padding(.horizontal, KnockTokens.screenPadding)
        .padding(.top, KnockTokens.spacingXS)
    }

    /// 返回胶囊（左侧操作槽；不参与标题居中计算）。
    private var backButton: some View {
        Button {
            dismiss()
        } label: {
            Image(systemName: "chevron.left")
                .font(.system(size: 13, weight: .heavy))
                .foregroundStyle(KnockTokens.textPrimary)
                .frame(width: 30, height: 30)
                .background(Capsule(style: .continuous).fill(KnockTokens.panel))
                .overlay {
                    Capsule(style: .continuous)
                        .strokeBorder(KnockTokens.stroke, lineWidth: KnockTokens.strokeWidth)
                }
        }
        .buttonStyle(.plain)
        .accessibilityLabel("返回")
    }

    private var headerSubtitle: String {
        model.isDemo ? "\(model.subtitle) · 演示" : model.subtitle
    }

    // MARK: - 时间线

    private var timeline: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 0) {
                    if model.hasMore && model.isLoadingMore {
                        ProgressView()
                            .controlSize(.small)
                            .tint(KnockTokens.electric)
                            .padding(.vertical, 10)
                    }

                    if model.rows.isEmpty && !model.isLoading {
                        emptyState
                    } else {
                        let rows = model.rows
                        ForEach(Array(rows.enumerated()), id: \.element.id) { index, row in
                            VStack(spacing: 0) {
                                if showsTimeSeparator(at: index, row: row) {
                                    TimeSeparator(text: row.timeText)
                                        .padding(.top, index == 0 ? 0 : 12)
                                }
                                rowView(row)
                                    .padding(.top, topSpacing(at: index, row: row, in: rows))
                            }
                            .id(row.id)
                            .onAppear { model.loadMoreIfNeeded(currentRowID: row.id) }
                        }
                    }

                    Color.clear.frame(height: 1).id("group-timeline-bottom")
                }
                .padding(.horizontal, 12)
                .padding(.top, 10)
                .padding(.bottom, 12)
            }
            .scrollIndicators(.hidden)
            .scrollDismissesKeyboard(.interactively)
            .knockScrollEdgeEffectHidden()
            .onChange(of: model.rows.last?.id) { _, _ in
                scrollToBottom(proxy)
            }
            .onChange(of: model.rows.last?.text ?? "") { _, _ in
                if model.isStreaming { scrollToBottom(proxy) }
            }
        }
    }

    @ViewBuilder
    private func rowView(_ row: GroupBubbleRow) -> some View {
        switch row.kind {
        case .narration:
            // 旁白：漫画旁白（左 2pt 黄竖条 + 斜体灰白字），无头像
            NarrationBubble(text: row.text)
        case .system:
            MessageBubble(text: row.text, side: .system)
        case .incoming:
            bubbleRow(
                row,
                side: .incoming,
                avatarName: row.senderName.isEmpty ? "成员" : row.senderName
            )
        case .outgoing:
            bubbleRow(row, side: .outgoing, avatarName: "我")
        }
    }

    /// 气泡行：头像 36pt 每消息独立（接收在左 / 发送在右），气泡本身见 MessageBubble。
    private func bubbleRow(
        _ row: GroupBubbleRow,
        side: BubbleSide,
        avatarName: String
    ) -> some View {
        HStack(alignment: .top, spacing: KnockTokens.spacingS) {
            if side == .incoming {
                groupAvatar(name: avatarName)
            }
            MessageBubble(
                text: row.text,
                side: side,
                isStreaming: row.type == "streaming",
                isRecalled: row.isRecalled,
                imageURL: row.imageId.isEmpty ? nil : model.imageURL(for: row),
                isImageMessage: !row.imageId.isEmpty,
                onImageTap: row.imageId.isEmpty ? nil : {
                    model.fullscreenImage = row
                    GroupSelfTestLog.add("group view fullscreen image row=\(row.id)")
                },
                senderName: row.kind == .incoming ? row.senderName : ""
            )
            if side == .outgoing {
                groupAvatar(name: avatarName)
            }
        }
        .frame(maxWidth: .infinity, alignment: side == .outgoing ? .trailing : .leading)
        // 长按菜单：敲敲自绘面板（替代系统 `.contextMenu` 的 iOS 26 液态玻璃）
        .knockContextMenu(actions: messageMenuActions(for: row))
    }

    private func groupAvatar(name: String) -> some View {
        KnockAvatar(name: name, size: KnockTokens.chatAvatarSize)
            .accessibilityHidden(true)
    }

    /// 行间距（计划书 1.1）：同人 10pt / 换人 18pt；时间分隔后 8pt；旁白/系统 12pt。
    private func topSpacing(at index: Int, row: GroupBubbleRow, in rows: [GroupBubbleRow]) -> CGFloat {
        guard index > 0 else { return 0 }
        if showsTimeSeparator(at: index, row: row) { return 8 }
        let previous = rows[index - 1]
        if row.kind == .narration || previous.kind == .narration { return 12 }
        if row.kind == .system || previous.kind == .system { return 12 }
        if row.kind != previous.kind { return 18 }
        if row.senderName != previous.senderName { return 18 }
        return 10
    }

    /// 群消息操作动作集合：复制 → 撤回这一段（destructive）→ 重试。
    /// 与系统 `.contextMenu` 版的文案 / 图标 / 顺序 / 条件 / destructive 标记逐一对应。
    private func messageMenuActions(for row: GroupBubbleRow) -> [KnockMenuAction] {
        var actions: [KnockMenuAction] = [
            KnockMenuAction("复制", systemImage: "doc.on.doc") {
                model.copy(row: row)
            }
        ]
        // 自己发出去的消息：撤回 / 删除（群聊无「对方」语义，两者都从本机群记录移除）
        if row.kind == .outgoing && row.messageId > 0 && !row.isRecalled {
            actions.append(
                KnockMenuAction("撤回", systemImage: "arrow.uturn.backward", role: .destructive) {
                    pendingOwnRecall = row
                }
            )
            actions.append(
                KnockMenuAction("删除（本地）", systemImage: "trash", role: .destructive) {
                    pendingOwnDelete = row
                }
            )
        }
        if row.kind == .incoming && row.type == "ai_json" {
            actions.append(
                KnockMenuAction("撤回这一段", systemImage: "arrow.uturn.backward", role: .destructive) {
                    pendingRecall = row
                }
            )
        }
        if row.type == "send_failed" || row.type == "gift_reply_failed" {
            actions.append(
                KnockMenuAction("重试", systemImage: "arrow.clockwise") {
                    model.retry(row: row)
                }
            )
        }
        return actions
    }

    private func showsTimeSeparator(at index: Int, row: GroupBubbleRow) -> Bool {
        guard !row.timeText.isEmpty else { return false }
        guard index > 0 else { return true }
        let previous = model.rows[index - 1]
        return row.createdAt.timeIntervalSince(previous.createdAt) >= 5 * 60
    }

    private var emptyState: some View {
        KnockEmptyState(
            title: "群里还没有消息",
            message: "说句话，让群成员依次回应你。",
            systemImage: "person.3.fill"
        )
        .padding(.top, 80)
        .padding(.horizontal, KnockTokens.spacingXS)
    }

    private func scrollToBottom(_ proxy: ScrollViewProxy) {
        guard !model.rows.isEmpty else { return }
        if reduceMotion {
            proxy.scrollTo("group-timeline-bottom", anchor: .bottom)
        } else {
            withAnimation(.easeOut(duration: 0.18)) {
                proxy.scrollTo("group-timeline-bottom", anchor: .bottom)
            }
        }
    }

    private var ownRecallAlertBinding: Binding<Bool> {
        Binding(
            get: { pendingOwnRecall != nil },
            set: { if !$0 { pendingOwnRecall = nil } }
        )
    }

    private var ownDeleteAlertBinding: Binding<Bool> {
        Binding(
            get: { pendingOwnDelete != nil },
            set: { if !$0 { pendingOwnDelete = nil } }
        )
    }

    private var recallAlertBinding: Binding<Bool> {
        Binding(
            get: { pendingRecall != nil },
            set: { if !$0 { pendingRecall = nil } }
        )
    }

    private var errorAlertBinding: Binding<Bool> {
        Binding(
            get: { model.errorText != nil },
            set: { if !$0 { model.clearError() } }
        )
    }
}

// MARK: - 成员列表 Sheet（成员 / 禁言 / 权限 / 自动互动开关）

struct GroupMembersSheet: View {
    let detail: GroupDetailItem?
    let isBusy: Bool
    var onToggleAuto: (Bool) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var autoEnabled: Bool = false
    /// 默认展开为大尺寸（成员列表可见；系统默认会选最小 detent）
    @State private var detent: PresentationDetent = .large

    var body: some View {
        VStack(spacing: 0) {
            header
            ScrollView {
                VStack(spacing: KnockTokens.spacingL) {
                    if let detail {
                        infoPanel(detail)
                        autoPanel(detail)
                        membersPanel(detail)
                    } else {
                        loadingRow
                    }
                }
                .padding(.horizontal, KnockTokens.screenPadding)
                .padding(.vertical, KnockTokens.spacingM)
            }
            .scrollIndicators(.hidden)
            .knockScrollEdgeEffectHidden()
        }
        .background(KnockTokens.canvas)
        .toolbar(.hidden, for: .navigationBar)
        .presentationDetents([.large, .medium], selection: $detent)
        .presentationBackground(KnockTokens.canvas)
        .onAppear { autoEnabled = detail?.autoEnabled ?? false }
        .onChange(of: detail?.autoEnabled) { _, newValue in
            autoEnabled = newValue ?? false
        }
    }

    // MARK: 顶栏

    private var header: some View {
        HStack(spacing: KnockTokens.spacingM) {
            KnockText("群成员", role: .pageTitle, lineLimit: 1)
            Spacer(minLength: KnockTokens.spacingS)
            KnockCloseButton(label: "关闭群成员") { dismiss() }
        }
        .padding(.horizontal, KnockTokens.screenPadding)
        .frame(minHeight: KnockTokens.headerHeight)
        .background(KnockTokens.canvas)
        .overlay(alignment: .bottom) {
            Rectangle()
                .fill(KnockTokens.stroke)
                .frame(height: KnockTokens.hairline)
        }
    }

    private var loadingRow: some View {
        HStack(spacing: KnockTokens.spacingM) {
            ProgressView().controlSize(.small).tint(KnockTokens.electric)
            KnockText("读取群成员…", role: .caption, color: KnockTokens.textSecondary)
            Spacer(minLength: 0)
        }
        .padding(KnockTokens.spacingL)
        .background(Capsule(style: .continuous).fill(KnockTokens.panel))
        .overlay {
            Capsule(style: .continuous)
                .strokeBorder(KnockTokens.stroke, lineWidth: KnockTokens.strokeWidth)
        }
    }

    // MARK: 群资料

    private func infoPanel(_ detail: GroupDetailItem) -> some View {
        KnockPanel {
            VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                KnockGroupTitle(title: "群资料", systemImage: "person.3.fill")
                infoRow("群名", detail.name)
                infoRow("模式", detail.modeText)
                infoRow("成员", "\(detail.memberCount) 人")
                infoRow("禁言", detail.mutedCount == 0 ? "无" : "\(detail.mutedCount) 人")
                if !detail.rules.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    VStack(alignment: .leading, spacing: KnockTokens.spacingXS) {
                        KnockText("群规则", role: .caption, color: KnockTokens.textSecondary)
                        KnockText(detail.rules, role: .caption, color: KnockTokens.textPrimary, lineSpacing: 3)
                    }
                }
            }
        }
    }

    private func infoRow(_ label: String, _ value: String) -> some View {
        HStack(spacing: KnockTokens.spacingM) {
            KnockText(label, role: .caption, color: KnockTokens.textSecondary, lineLimit: 1)
            Spacer(minLength: KnockTokens.spacingS)
            KnockText(value, role: .captionStrong, lineLimit: 1)
        }
        .frame(minHeight: 24)
    }

    // MARK: 自动互动

    private func autoPanel(_ detail: GroupDetailItem) -> some View {
        KnockPanel {
            VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                KnockGroupTitle(title: "自动互动", systemImage: "bolt.fill")
                Toggle(isOn: Binding(
                    get: { autoEnabled },
                    set: { value in
                        autoEnabled = value
                        onToggleAuto(value)
                    }
                )) {
                    VStack(alignment: .leading, spacing: KnockTokens.spacingXXS) {
                        KnockText("自动互动", role: .rowTitle, lineLimit: 1)
                        KnockText(
                            detail.autoEnabled ? detail.autoStatus : "本群已关闭",
                            role: .caption,
                            color: KnockTokens.textSecondary,
                            lineLimit: 2
                        )
                    }
                }
                .tint(KnockTokens.electric)
                .disabled(isBusy)

                infoRow("总自动内容开关", detail.autoMasterEnabled ? "已开启" : "已关闭")
                infoRow("轮数上限", "\(detail.autoMaxRounds) 轮")
                if !detail.autoMasterEnabled {
                    KnockText(
                        "总自动内容开关关闭时，即使群开关打开也不会自动产生轮次。",
                        role: .caption,
                        color: KnockTokens.textTertiary,
                        lineSpacing: 2
                    )
                }
                KnockText(
                    "自动互动在前台或系统允许的后台时机运行，可能不是准点触发。",
                    role: .caption,
                    color: KnockTokens.textTertiary,
                    lineSpacing: 2
                )
            }
        }
    }

    // MARK: 成员

    private func membersPanel(_ detail: GroupDetailItem) -> some View {
        VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
            KnockGroupTitle(title: "成员（\(detail.memberCount) 人 · 禁言=权限）")
                .padding(.horizontal, KnockTokens.spacingXS)
            ForEach(detail.members) { member in
                memberRow(member)
            }
            KnockText("成员权限：「禁言」或「可发言」。", role: .caption, color: KnockTokens.textTertiary)
                .padding(.horizontal, KnockTokens.spacingXS)
        }
    }

    private func memberRow(_ member: GroupMemberItem) -> some View {
        HStack(spacing: KnockTokens.spacingM) {
            KnockAvatar(avatarId: member.avatarId, name: member.name, size: KnockTokens.rowIconSize)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: KnockTokens.spacingXXS) {
                KnockText(member.name, role: .rowTitle, lineLimit: 1)
                if !member.title.isEmpty {
                    KnockText(member.title, role: .caption, color: KnockTokens.textSecondary, lineLimit: 1)
                }
                if !member.isResolved {
                    KnockText("资料未同步", role: .caption, color: KnockTokens.textTertiary, lineLimit: 1)
                }
            }
            Spacer(minLength: KnockTokens.spacingS)
            KnockBadge(text: member.permissionText, variant: member.isMuted ? .danger : .neutral)
        }
        .padding(.horizontal, KnockTokens.rowPaddingLeading)
        .padding(.trailing, KnockTokens.rowPaddingTrailing)
        .padding(.vertical, KnockTokens.spacingS)
        .frame(minHeight: KnockTokens.rowHeight)
        .background {
            Capsule(style: .continuous)
                .fill(KnockTokens.row)
                .overlay(KnockCheckerOverlay())
                .clipShape(Capsule(style: .continuous))
        }
        .overlay {
            Capsule(style: .continuous)
                .strokeBorder(KnockTokens.strokeSecondary, lineWidth: KnockTokens.strokeWidth)
        }
    }
}

extension Notification.Name {
    /// T43 自检：请群聊页刷新最新一页（自动化钩子使用）
    static let groupSelfTestRefresh = Notification.Name("rhodeslink.group.selftest.refresh")
}