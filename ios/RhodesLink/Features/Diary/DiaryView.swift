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
//  敲敲视觉（P3）：隐藏系统导航栏 → `KnockHeader`（返回 + 管理/刷新胶囊）；
//  筛选 = 黄选中胶囊分段栏；日记行 = 胶囊行（头像 + 标题 + 摘要 + 未读红角标）；
//  空态 = E M P T Y 宽字距 pill。
//

import SwiftUI

@MainActor
struct DiaryView: View {
    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.dismiss) private var dismiss

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

            VStack(spacing: 0) {
                KnockHeader(title: "代理人日记", showsBack: true, onBack: { dismiss() }, trailing: {
                    KnockCapsuleIconButton(
                        systemImage: "slider.horizontal.3",
                        variant: .neutral,
                        accessibilityLabel: "日记生成管理"
                    ) {
                        showAutoManage = true
                    }
                    KnockCapsuleIconButton(
                        systemImage: "arrow.clockwise",
                        variant: .neutral,
                        accessibilityLabel: "刷新日记"
                    ) {
                        Task { await reload(initial: false) }
                    }
                })

                ScrollView {
                    LazyVStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                        headerPanel

                        if DiaryAutomation.isEnabled {
                            automationPanel
                        }

                        if let errorText {
                            KnockErrorPanel(title: "日记读取失败", message: errorText) {
                                Task { await reload(initial: false) }
                            }
                        }

                        if isLoading && entries.isEmpty {
                            KnockLoadingPanel("正在读取日记…")
                        } else if entries.isEmpty && !isLoading {
                            emptyPanel
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
                                KnockText(
                                    "未读日记已全部读完",
                                    role: .caption,
                                    color: KnockTokens.textTertiary
                                )
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, KnockTokens.spacingS)
                            }
                        }
                    }
                    .padding(.horizontal, KnockTokens.screenPadding)
                    .padding(.top, KnockTokens.spacingS)
                    .padding(.bottom, KnockTokens.spacingXL)
                }
                .scrollIndicators(.hidden)
                .knockScrollEdgeHidden()
                .refreshable { await reload(initial: false) }
            }
        }
        .knockHiddenNavigationBar()
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
        .onChange(of: filter) { _, _ in
            Task { await reload(initial: false) }
        }
        .task {
            await reload(initial: true)
            // 截图/演示钩子（仅启动参数，对用户路径零影响）：
            // `--knock-diary-detail` 打开第一篇日记；`--knock-diary-auto` 打开日记生成管理。
            applyScreenshotHooksIfNeeded()
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

    private var headerPanel: some View {
        KnockPanel {
            VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                HStack(spacing: KnockTokens.spacingM) {
                    KnockRowGlyph(systemImage: "book.closed.fill")
                    VStack(alignment: .leading, spacing: KnockTokens.spacingXXS) {
                        KnockText("代理人们的内心独白", role: .rowTitle, lineLimit: 1)
                        KnockText(
                            "共 \(totalCount) 篇 · 未读代理人 \(unreadCount) 位",
                            role: .caption,
                            color: KnockTokens.textSecondary,
                            lineLimit: 1
                        )
                    }
                    Spacer(minLength: KnockTokens.spacingS)
                    if unreadCount > 0 {
                        KnockBadge(text: "\(unreadCount) 未读", variant: .danger)
                    }
                }

                KnockSegmentedBar(
                    items: DiaryFilter.allCases.map {
                        KnockSegmentItem(value: $0, title: $0.rawValue)
                    },
                    selection: $filter,
                    height: KnockTokens.segmentedSmallHeight,
                    showsIcons: false
                )
            }
        }
    }

    private var automationPanel: some View {
        KnockPanel(padding: KnockTokens.spacingM) {
            VStack(alignment: .leading, spacing: KnockTokens.spacingXS) {
                KnockText("T51 自检", role: .captionStrong, lineLimit: 1)
                KnockText(
                    automationResult.isEmpty
                        ? (DiaryAutomation.resultText.isEmpty ? "等待自检…" : DiaryAutomation.resultText)
                        : automationResult,
                    role: .caption,
                    color: KnockTokens.textSecondary,
                    lineSpacing: 2
                )
                if !fixtureLine.isEmpty {
                    KnockText(fixtureLine, role: .caption, color: KnockTokens.textTertiary, lineLimit: 2)
                }
            }
        }
    }

    private var emptyPanel: some View {
        KnockEmptyState(
            title: filter == .unread ? "没有未读日记" : "还没有代理人日记",
            message: filter == .unread
                ? "所有代理人日记都已读完。切换到「全部」可以回看历史独白。"
                : "代理人们还没有写下日记。日记由私聊/动态/关系事件后自动生成，或经备份恢复导入。"
        )
    }

    // MARK: 列表行

    private func diaryRow(_ entry: DiaryEntryItem) -> some View {
        KnockListRow(
            title: entry.title,
            subtitle: "第 \(entry.version) 篇 · \(entry.summary)",
            height: 96
        ) {
            KnockRowAvatar(avatarId: entry.avatarId, name: entry.operatorName, size: 46)
        } trailing: {
            VStack(alignment: .trailing, spacing: KnockTokens.spacingXS) {
                KnockText(entry.timeText, role: .caption, color: KnockTokens.textTertiary, lineLimit: 1)
                if entry.isUnread {
                    KnockBadge(text: "未读", variant: .danger)
                }
                KnockChevron()
            }
        }
        .accessibilityLabel("\(entry.operatorName) 日记：\(entry.title)，\(entry.isUnread ? "未读" : "已读")")
    }

    // MARK: 数据加载

    /// 截图钩子：`--knock-diary-detail` / `--knock-diary-auto`（仅模拟器自动化使用）。
    private func applyScreenshotHooksIfNeeded() {
        let args = ProcessInfo.processInfo.arguments
        if args.contains("--knock-diary-auto") {
            showAutoManage = true
        } else if args.contains("--knock-diary-detail"), let first = entries.first {
            selectedEntry = first
        }
    }

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