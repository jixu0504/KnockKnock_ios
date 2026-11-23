//
//  DiaryView.swift
//  干员日记列表（T51，与 Android 原版 DiaryScreen 对齐的最小完整面）：
//  - 列表：角色头像 / 标题（干员名 · 日期）/ 摘要 / 时间 / 未读点；按时间倒序（真实 diaries 表）
//  - 未读筛选（全部 / 未读）+ 未读干员数；下拉刷新；空态 / 加载态 / 错误态
//  - 点击进入 DiaryDetailView：全文阅读、自动标记已读（settings diary_read_at_* = 最新 createdAt）、
//    返回列表后未读点消失
//  - 数据全部来自 shared `DiaryBridge` 真实 SQLite + settings，无 mock
//  - `--diary-selftest`：自动跑桥层自检 + UI 链路自检并写 Documents/diary-selftest.log（截图证据）
//

import SwiftUI

@MainActor
struct DiaryView: View {
    @EnvironmentObject private var environment: AppEnvironment

    enum DiaryFilter: String, CaseIterable, Identifiable {
        case all = "全部"
        case unread = "未读"
        var id: String { rawValue }
        var unreadOnly: Bool { self == .unread }
    }

    @State private var entries: [DiaryEntryItem] = []
    @State private var unreadCount = 0
    @State private var totalCount = 0
    @State private var filter: DiaryFilter = .all
    @State private var isLoading = false
    @State private var errorText: String?
    @State private var selectedEntry: DiaryEntryItem?
    @State private var loadedOnce = false
    @State private var fixtureLine = ""
    @State private var automationResult = ""
    /// T61：日记生成管理页入口（进入后可逐个干员开关自动生成/立即生成）。
    @State private var showAutoManage = false

    var body: some View {
        ZStack {
            AmbientBackground(parallax: 0, showsStandee: false)

            ScrollView {
                LazyVStack(spacing: 12) {
                    headerCard

                    if DiaryAutomation.isEnabled {
                        automationCard
                    }

                    if let errorText {
                        errorCard(errorText)
                    }

                    if isLoading && entries.isEmpty {
                        loadingCard
                    } else if entries.isEmpty && !isLoading {
                        emptyCard
                    } else {
                        ForEach(entries) { entry in
                            Button {
                                selectedEntry = entry
                            } label: {
                                diaryRow(entry)
                            }
                            .buttonStyle(.plain)
                        }

                        if filter == .unread && !isLoading {
                            Text("未读日记已全部读完")
                                .font(.caption)
                                .foregroundStyle(.tertiary)
                                .padding(.vertical, 8)
                        }
                    }
                }
                .padding(.horizontal, 14)
                .padding(.bottom, 24)
            }
            .refreshable { await reload(initial: false) }
        }
        .navigationTitle("干员日记")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    showAutoManage = true
                } label: {
                    Image(systemName: "slider.horizontal.3")
                }
                .accessibilityLabel("日记生成管理")
            }
            ToolbarItem(placement: .primaryAction) {
                Button {
                    Task { await reload(initial: false) }
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .accessibilityLabel("刷新日记")
            }
        }
        .navigationDestination(item: $selectedEntry) { entry in
            DiaryDetailView(entry: entry) {
                Task { await reload(initial: false) }
            }
            .environmentObject(environment)
        }
        .navigationDestination(isPresented: $showAutoManage) {
            DiaryAutoManageView()
                .environmentObject(environment)
        }
        .onChange(of: showAutoManage) { _, shown in
            // 从「日记生成管理」返回时刷新列表（可能刚补齐/生成过日记）。
            if !shown { Task { await reload(initial: false) } }
        }
        .task {
            await reload(initial: true)
            if DiaryAutoAutomation.isEnabled {
                // `--diary-auto-selftest`：自动进入管理页跑 UI 链路自检（对用户路径零影响）。
                showAutoManage = true
            } else {
                // T61：进入日记页时做一次每日 tick（总开关关闭时桥内立即返回，无 AI 调用）。
                let tick = await environment.bridge.runDiaryAutoTick(limit: 0)
                if case .success(let result) = tick, result.generated > 0 {
                    await reload(initial: false)
                }
            }
            await runSelfTestFlowIfNeeded()
        }
    }

    // MARK: 头部 / 状态卡

    private var headerCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 10) {
                Image(systemName: "book.closed.fill")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 36, height: 36)
                    .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(GlassTokens.brandOrange))

                VStack(alignment: .leading, spacing: 2) {
                    Text("干员们的内心独白")
                        .font(.headline)
                    Text("共 \(totalCount) 篇 · 未读干员 \(unreadCount) 位")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Spacer(minLength: 8)

                if unreadCount > 0 {
                    Text("\(unreadCount) 未读")
                        .font(.caption2.weight(.bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(Capsule().fill(Color.red.opacity(0.82)))
                }
            }

            Picker("筛选", selection: $filter) {
                ForEach(DiaryFilter.allCases) { option in
                    Text(option.rawValue).tag(option)
                }
            }
            .pickerStyle(.segmented)
            .onChange(of: filter) { _, _ in
                Task { await reload(initial: false) }
            }
        }
        .padding(14)
        .cardSurface(cornerRadius: 20, strong: true)
    }

    private var automationCard: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("T51 自检")
                .font(.caption.weight(.semibold))
                .foregroundStyle(GlassTokens.brandOrange)
            Text(automationResult.isEmpty ? (DiaryAutomation.resultText.isEmpty ? "等待自检…" : DiaryAutomation.resultText) : automationResult)
                .font(.footnote)
            if !fixtureLine.isEmpty {
                Text(fixtureLine)
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
                    .lineLimit(2)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .cardSurface(cornerRadius: 16)
    }

    private var loadingCard: some View {
        HStack(spacing: 10) {
            ProgressView()
            Text("正在读取日记…")
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(20)
        .cardSurface(cornerRadius: 18)
    }

    private func errorCard(_ message: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("日记读取失败", systemImage: "exclamationmark.triangle.fill")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.red)
            Text(message)
                .font(.caption)
                .foregroundStyle(.secondary)
            Button("重试") {
                Task { await reload(initial: false) }
            }
            .buttonStyle(.bordered)
            .tint(GlassTokens.brandOrange)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .cardSurface(cornerRadius: 18)
    }

    private var emptyCard: some View {
        EmptyStateView(
            title: filter == .unread ? "没有未读日记" : "还没有干员日记",
            message: filter == .unread
                ? "所有干员日记都已读完。切换到「全部」可以回看历史独白。"
                : "干员们还没有写下日记。日记由私聊/动态/关系事件后自动生成，或经备份恢复导入。",
            systemImage: filter == .unread ? "checkmark.circle" : "book.closed"
        )
        .padding(.horizontal, 2)
    }

    // MARK: 列表行

    private func diaryRow(_ entry: DiaryEntryItem) -> some View {
        HStack(alignment: .top, spacing: 12) {
            ZStack(alignment: .topTrailing) {
                RoleAvatar(avatarId: entry.avatarId, name: entry.operatorName, size: 46)
                if entry.isUnread {
                    Circle()
                        .fill(Color.red)
                        .frame(width: 10, height: 10)
                        .overlay(Circle().strokeBorder(GlassTokens.surface, lineWidth: 1.5))
                        .offset(x: 2, y: -2)
                }
            }

            VStack(alignment: .leading, spacing: 4) {
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text(entry.title)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                    if entry.isUnread {
                        Text("未读")
                            .font(.caption2.weight(.bold))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 5)
                            .padding(.vertical, 1)
                            .background(Capsule().fill(Color.red.opacity(0.82)))
                    }
                    Spacer(minLength: 4)
                    Text(entry.timeText)
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                }
                Text(entry.summary)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
                Text("第 \(entry.version) 篇 · \(entry.date)")
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(cornerRadius: 18)
        .contentShape(Rectangle())
    }

    // MARK: 数据加载

    private func reload(initial: Bool) async {
        if initial { isLoading = true }
        let result = await environment.bridge.loadDiaryList(unreadOnly: filter.unreadOnly)
        switch result {
        case .success(let snapshot):
            entries = snapshot.items
            unreadCount = snapshot.unreadCount
            totalCount = snapshot.totalCount
            errorText = nil
        case .failure(let error):
            errorText = error.message
            DiaryAutomation.add("FAIL ui.reload \(error.message)")
        }
        isLoading = false
        loadedOnce = true
    }

    // MARK: `--diary-selftest` UI 链路

    private func runSelfTestFlowIfNeeded() async {
        guard DiaryAutomation.beginIfNeeded() else { return }
        fixtureLine = await environment.bridge.ensureDiaryFixture()
        DiaryAutomation.add("fixture \(fixtureLine)")

        let report = await environment.bridge.runDiarySelfTest()
        for line in report.split(separator: "\n").map(String.init) {
            DiaryAutomation.add(line)
        }
        let bridgePassed = report.contains("RESULT PASS")
        DiaryAutomation.add("\(bridgePassed ? "PASS" : "FAIL") bridge.selftest.result \(bridgePassed ? "PASS" : "FAIL")")

        // 桥层自检已把夹具重新置为未读：重载列表，等待截图（未读点可见）。
        await reload(initial: false)
        let unreadEntries = entries.filter { $0.isUnread }
        DiaryAutomation.add("\(unreadEntries.isEmpty ? "FAIL" : "PASS") ui.list.unread entries=\(entries.count) unreadEntries=\(unreadEntries.count) unreadOperators=\(unreadCount)")

        try? await Task.sleep(nanoseconds: 6_000_000_000)

        guard let target = unreadEntries.first ?? entries.first else {
            DiaryAutomation.add("FAIL ui.open entries=0")
            DiaryAutomation.finish(summary: "UI 自检失败：列表为空")
            return
        }
        DiaryAutomation.add("ui.open id=\(target.id) operator=\(target.operatorName) isUnread=\(target.isUnread)")
        selectedEntry = target

        // 详情页自动加载全文并 markRead（用户路径同一逻辑），等待其完成。
        try? await Task.sleep(nanoseconds: 4_000_000_000)
        await reload(initial: false)
        let stillUnread = entries.contains { $0.operatorId == target.operatorId && $0.isUnread }
        let remaining = await environment.bridge.diaryUnreadCount()
        DiaryAutomation.add("ui.detail.operator=\(target.operatorId) stillUnread=\(stillUnread) remainingUnreadOperators=\(remaining)")
        DiaryAutomation.add("\(stillUnread ? "FAIL" : "PASS") ui.unreadCleared operator=\(target.operatorId)")

        let detail = await environment.bridge.loadDiaryDetail(id: target.id)
        switch detail {
        case .success(let item):
            DiaryAutomation.add("\(item.content.isEmpty ? "FAIL" : "PASS") ui.detail.content id=\(item.id) len=\(item.content.count)")
        case .failure(let error):
            DiaryAutomation.add("FAIL ui.detail \(error.message)")
        }

        DiaryAutomation.finish(summary: "列表 \(entries.count) 条 / 未读干员 \(unreadCount) 位 / 打开 \(target.operatorName) 日记 id=\(target.id)")
        automationResult = DiaryAutomation.resultText
    }
}
