//
//  GroupChatView.swift
//  T43 群聊页：多发言人气泡（senderName）+ 群标题/成员数头部 + 成员列表 Sheet（成员/禁言/权限/
//  自动互动开关）+ 复用 GlassInputBar + 真实 GroupChatEngine 发送。
//
//  与私聊 ChatView 的差异：
//  - 导航栏副标题显示成员数与禁言数，右侧「成员」按钮打开成员 Sheet；
//  - AI 行按发言人拆成多个气泡（旁白单独一行中文叙述，不套气泡）；
//  - 长按 AI 气泡可「撤回分段」，未送达消息可「重试」；无「重说/继续说」（群聊引擎无此入口，差异白名单）。
//

import SwiftUI

struct GroupChatView: View {
    @StateObject private var model: GroupChatViewModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var pendingRecall: GroupBubbleRow?

    init(sessionId: String) {
        _model = StateObject(wrappedValue: GroupChatViewModel(sessionId: sessionId))
    }

    var body: some View {
        timeline
            .background(chatBackground)
            .navigationTitle("")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) { navTitle }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        model.openMembers()
                    } label: {
                        Image(systemName: "person.2.fill")
                    }
                    .accessibilityLabel("群成员")
                    .accessibilityHint("查看成员、禁言状态与自动互动开关")
                }
            }
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
            .overlay(alignment: .top) {
                if let feedback = model.actionFeedback {
                    Text(feedback)
                        .font(.footnote.weight(.medium))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 7)
                        .background(Capsule().fill(Color.black.opacity(0.72)))
                        .padding(.top, 8)
                        .transition(.opacity)
                        .accessibilityLabel("操作结果：\(feedback)")
                }
            }
            .onReceive(NotificationCenter.default.publisher(for: .groupSelfTestRefresh)) { _ in
                Task { await model.refresh() }
            }
    }

    // MARK: 导航栏标题

    private var navTitle: some View {
        VStack(spacing: 1) {
            Text(model.title)
                .font(.headline)
                .lineLimit(1)
            HStack(spacing: 4) {
                Image(systemName: "person.2.fill")
                    .font(.system(size: 8))
                    .foregroundStyle(.secondary)
                Text(model.subtitle)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                if model.isDemo {
                    Text("演示")
                        .font(.system(size: 9, weight: .bold))
                        .foregroundStyle(GlassTokens.brandOrange)
                        .padding(.horizontal, 5)
                        .padding(.vertical, 1)
                        .background(GlassTokens.brandOrange.opacity(0.16), in: Capsule())
                }
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(model.title)，\(model.subtitle)")
    }

    // MARK: 时间线

    private var timeline: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 8) {
                    if model.hasMore && model.isLoadingMore {
                        ProgressView().controlSize(.small).padding(.vertical, 10)
                    }

                    if model.rows.isEmpty && !model.isLoading {
                        emptyState
                    } else {
                        ForEach(Array(model.rows.enumerated()), id: \.element.id) { index, row in
                            if showsTimeSeparator(at: index, row: row) {
                                TimeSeparator(text: row.timeText)
                            }
                            rowView(row)
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
            narrationRow(row)
        case .system:
            MessageBubble(text: row.text, side: .system)
        case .incoming, .outgoing:
            MessageBubble(
                text: row.text,
                side: row.bubbleSide,
                isStreaming: row.type == "streaming",
                imageURL: row.imageId.isEmpty ? nil : model.imageURL(for: row),
                isImageMessage: !row.imageId.isEmpty,
                onImageTap: row.imageId.isEmpty ? nil : {
                    model.fullscreenImage = row
                    GroupSelfTestLog.add("group view fullscreen image row=\(row.id)")
                },
                senderName: row.kind == .incoming ? row.senderName : ""
            )
            .contextMenu { messageMenu(for: row) }
        }
    }

    /// 旁白：居中的第三人称叙述（群聊线下/导演模式；线上模式引擎会过滤掉）
    private func narrationRow(_ row: GroupBubbleRow) -> some View {
        Text(row.text)
            .font(.footnote)
            .italic()
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
            .padding(.horizontal, 24)
            .padding(.vertical, 2)
            .frame(maxWidth: .infinity)
            .accessibilityLabel("旁白：\(row.text)")
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
        VStack(spacing: 10) {
            Image(systemName: "person.3.fill")
                .font(.system(size: 40, weight: .light))
                .foregroundStyle(.tertiary)
            Text("群里还没有消息")
                .font(.subheadline.weight(.medium))
                .foregroundStyle(.secondary)
            Text("说句话，让群成员依次回应你。")
                .font(.caption)
                .foregroundStyle(.tertiary)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 120)
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

    private var chatBackground: some View {
        Color(uiColor: .systemGroupedBackground).ignoresSafeArea()
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
        NavigationStack {
            List {
                if let detail {
                    Section {
                        LabeledContent("群名", value: detail.name)
                        LabeledContent("模式", value: detail.modeText)
                        LabeledContent("成员", value: "\(detail.memberCount) 人")
                        LabeledContent("禁言", value: detail.mutedCount == 0 ? "无" : "\(detail.mutedCount) 人")
                        if !detail.rules.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                            VStack(alignment: .leading, spacing: 4) {
                                Text("群规则").font(.caption).foregroundStyle(.secondary)
                                Text(detail.rules).font(.footnote)
                            }
                        }
                    } header: {
                        Text("群资料（真实 chat_sessions）")
                    }

                    Section {
                        Toggle(isOn: Binding(
                            get: { autoEnabled },
                            set: { value in
                                autoEnabled = value
                                onToggleAuto(value)
                            }
                        )) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("自动互动")
                                Text(detail.autoEnabled ? detail.autoStatus : "本群已关闭")
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                            }
                        }
                        .disabled(isBusy)
                        LabeledContent("总自动内容开关", value: detail.autoMasterEnabled ? "已开启" : "已关闭")
                        LabeledContent("轮数上限", value: "\(detail.autoMaxRounds) 轮")
                        if !detail.autoMasterEnabled {
                            Text("总自动内容开关关闭时，即使群开关打开也不会自动产生轮次。")
                                .font(.caption2)
                                .foregroundStyle(.tertiary)
                        }
                    } header: {
                        Text("自动互动")
                    } footer: {
                        Text("自动互动在前台或系统允许的后台时机运行，可能不是准点触发。")
                            .font(.caption2)
                    }

                    Section {
                        ForEach(detail.members) { member in
                            HStack(spacing: 10) {
                                RoleAvatar(avatarId: member.avatarId, name: member.name, size: 34)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(member.name)
                                        .font(.subheadline.weight(.medium))
                                    if !member.title.isEmpty {
                                        Text(member.title)
                                            .font(.caption2)
                                            .foregroundStyle(.secondary)
                                    }
                                }
                                Spacer()
                                Text(member.permissionText)
                                    .font(.caption2.weight(.semibold))
                                    .foregroundStyle(member.isMuted ? Color.red : Color.green)
                                    .padding(.horizontal, 8)
                                    .padding(.vertical, 3)
                                    .background(
                                        (member.isMuted ? Color.red : Color.green).opacity(0.12),
                                        in: Capsule()
                                    )
                                if !member.isResolved {
                                    Text("资料未同步")
                                        .font(.caption2)
                                        .foregroundStyle(.orange)
                                }
                            }
                        }
                    } header: {
                        Text("成员（\(detail.memberCount) 人 · 禁言=权限）")
                    } footer: {
                        Text("成员权限：「禁言」或「可发言」。")
                            .font(.caption2)
                    }
                } else {
                    ProgressView("读取群成员…")
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 40)
                }
            }
            .navigationTitle("群成员")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("完成") { dismiss() }
                }
            }
            .onAppear { autoEnabled = detail?.autoEnabled ?? false }
            .onChange(of: detail?.autoEnabled) { _, newValue in
                autoEnabled = newValue ?? false
            }
        }
        .presentationDetents([.large, .medium], selection: $detent)
    }
}

extension Notification.Name {
    /// T43 自检：请群聊页刷新最新一页（自动化钩子使用）
    static let groupSelfTestRefresh = Notification.Name("rhodeslink.group.selftest.refresh")
}
