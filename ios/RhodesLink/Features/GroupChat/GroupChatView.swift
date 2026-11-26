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

    init(sessionId: String) {
        _model = StateObject(wrappedValue: GroupChatViewModel(sessionId: sessionId))
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            timeline
        }
        .background(GlassTokens.chatBackground)
        // 计划书 2.3 / 7.1-9：隐藏系统导航栏与 TabBar
        .toolbar(.hidden, for: .navigationBar)
        .toolbar(.hidden, for: .tabBar)
        .safeAreaInset(edge: .bottom, spacing: 0) {
            GlassInputBar(
                text: $model.draft,
                isStreaming: model.isStreaming,
                placeholder: "在群里说点什么…",
                onSend: { model.send() },
                onCancel: { model.cancel() }
            )
        }
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
    }

    // MARK: - 顶栏

    private var header: some View {
        ZStack(alignment: .center) {
            VStack(spacing: 3) {
                // 标题行铺满可用宽度并居中 → 与屏幕中线严格对齐
                KnockText(model.title, role: .pageTitle, lineLimit: 1, minimumScaleFactor: 0.7)
                    .frame(maxWidth: .infinity, alignment: .center)
                KnockText(
                    headerSubtitle,
                    role: .caption,
                    color: KnockTokens.textSecondary,
                    lineLimit: 1,
                    minimumScaleFactor: 0.8
                )
                .frame(maxWidth: .infinity, alignment: .center)
            }
            // 两侧等宽预留（返回胶囊 / 成员按钮），保证标题块真正居中
            .padding(.horizontal, 96)

            HStack(spacing: 0) {
                backButton
                Spacer(minLength: KnockTokens.spacingS)
                KnockCapsuleIconButton(
                    systemImage: "person.2.fill",
                    variant: .neutral,
                    accessibilityLabel: "群成员"
                ) {
                    model.openMembers()
                }
                .accessibilityHint("查看成员、禁言状态与自动互动开关")
            }
        }
        .padding(.horizontal, KnockTokens.screenPadding)
        .padding(.vertical, 6)
        .frame(maxWidth: .infinity)
        .background(KnockTokens.canvas)
        .overlay(alignment: .bottom) {
            Rectangle()
                .fill(KnockTokens.stroke)
                .frame(height: KnockTokens.hairline)
                .accessibilityHidden(true)
        }
    }

    /// 返回胶囊（左侧操作槽；不参与标题居中计算）。
    private var backButton: some View {
        Button {
            dismiss()
        } label: {
            HStack(spacing: 4) {
                Image(systemName: "chevron.left")
                    .font(.system(size: 13, weight: .heavy))
                KnockText("返回", role: .captionStrong, lineLimit: 1)
            }
            .foregroundStyle(KnockTokens.textPrimary)
            .padding(.horizontal, 12)
            .frame(minHeight: 30)
            .background(Capsule(style: .continuous).fill(KnockTokens.panel))
            .overlay {
                Capsule(style: .continuous)
                    .strokeBorder(KnockTokens.stroke, lineWidth: KnockTokens.strokeWidth)
            }
            .frame(minHeight: 44)
            .contentShape(Rectangle())
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
            ) {
                messageMenu(for: row)
            }
        case .outgoing:
            bubbleRow(row, side: .outgoing, avatarName: "我") {
                messageMenu(for: row)
            }
        }
    }

    /// 气泡行：头像 36pt 每消息独立（接收在左 / 发送在右），气泡本身见 MessageBubble。
    private func bubbleRow<Menu: View>(
        _ row: GroupBubbleRow,
        side: BubbleSide,
        avatarName: String,
        @ViewBuilder menu: () -> Menu
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
        .contextMenu { menu() }
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

    @ViewBuilder
    private func messageMenu(for row: GroupBubbleRow) -> some View {
        Button {
            model.copy(row: row)
        } label: {
            Label("复制", systemImage: "doc.on.doc")
        }
        if row.kind == .incoming && row.type == "ai_json" {
            Button(role: .destructive) {
                pendingRecall = row
            } label: {
                Label("撤回这一段", systemImage: "arrow.uturn.backward")
            }
        }
        if row.type == "send_failed" || row.type == "gift_reply_failed" {
            Button {
                model.retry(row: row)
            } label: {
                Label("重试", systemImage: "arrow.clockwise")
            }
        }
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