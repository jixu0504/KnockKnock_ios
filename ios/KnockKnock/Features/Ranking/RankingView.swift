//
//  RankingView.swift
//  聊天排行榜 / 消费统计（T54，与 Android 原版 RankingScreen + TokenStatsScreen 对齐的最小完整面）：
//  - 维度分段：消息数 / 互动天数 / 消费（估算）；消息数支持「昨日 / 历史」区间（oracle 两个 tab）；
//  - 排行列表：排名徽章（前 3 名黄胶囊 + 浅蓝外圈，其余描边）、角色头像与名称、维度数值、好感列；
//    点行进入角色详情（oracle 点角色进私聊）；
//  - 消费统计卡片：今日/本周/全部 Token（输入+输出）与 6 分类明细（oracle TokenStatsScreen.kt:65 口径）；
//  - 空态 / 加载态 / 错误态 / 下拉刷新；数据全部来自 shared `RankingBridge` 真实 SQLite + settings，无 mock；
//  - `--ranking-selftest`：自动跑桥层自检 + UI 链路自检并写 Documents/t54-ranking-selftest.log（截图证据）。
//
//  敲敲视觉（P3）：隐藏系统导航栏 → `KnockHeader`（返回 + 刷新胶囊）；
//  维度/区间 = 黄选中胶囊分段栏；榜单 = 面板 + 胶囊行 + 名次徽章（黄 / 描边）；
//  消费统计 = 面板内等宽数字；空态 = E M P T Y。
//

import SwiftUI

@MainActor
struct RankingView: View {
    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.dismiss) private var dismiss

    @State private var dimension: RankingDimension = .messages
    @State private var range: RankingRange = .history
    @State private var snapshot: RankingSnapshotItem?
    @State private var tokenRange: TokenStatsRange = .today
    @State private var tokenStats: TokenStatsItem?
    @State private var isLoading = false
    @State private var tokenLoading = false
    @State private var errorText: String?
    @State private var tokenErrorText: String?
    @State private var loadedOnce = false

    var body: some View {
        ZStack {
            AmbientBackground(parallax: 0, showsStandee: false)

            VStack(spacing: 0) {
                KnockHeader(title: "聊天排行榜", showsBack: true, onBack: { dismiss() }, trailing: {
                    KnockCapsuleIconButton(
                        systemImage: "arrow.clockwise",
                        variant: .neutral,
                        accessibilityLabel: "刷新排行榜"
                    ) {
                        Task {
                            await reload()
                            await reloadTokens()
                        }
                    }
                })

                ScrollView {
                    LazyVStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                        headerPanel
                        dimensionPanel
                        rangeBar

                        if let errorText {
                            KnockErrorPanel(title: "排行榜读取失败", message: errorText) {
                                Task { await reload() }
                            }
                        } else if isLoading && snapshot == nil {
                            KnockLoadingPanel("正在读取排行榜…")
                        } else if let snapshot, snapshot.entries.isEmpty {
                            emptyPanel
                        } else if let snapshot {
                            rankingList(snapshot)
                        }

                    }
                    .padding(.horizontal, KnockTokens.screenPadding)
                    .padding(.top, KnockTokens.spacingS)
                    .padding(.bottom, KnockTokens.spacingXL)
                }
                .scrollIndicators(.hidden)
                .knockScrollEdgeHidden()
                .refreshable {
                    await reload()
                    await reloadTokens()
                }
            }
        }
        .knockHiddenNavigationBar()
        .task {
            await startIfNeeded()
        }
        .onChange(of: dimension) { _, _ in
            Task { await reload() }
        }
        .onChange(of: range) { _, _ in
            Task { await reload() }
        }
        .onChange(of: tokenRange) { _, _ in
            Task { await reloadTokens() }
        }
    }

    // MARK: - 头部 / 分段

    private var headerPanel: some View {
        KnockPanel {
            VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                HStack(spacing: KnockTokens.spacingM) {
                    KnockRowGlyph(systemImage: "chart.bar.fill")
                    VStack(alignment: .leading, spacing: KnockTokens.spacingXXS) {
                        KnockText("聊天排行榜", role: .rowTitle, lineLimit: 1)
                        KnockText(
                            "\(snapshot?.rangeLabel ?? "历史") · \(snapshot?.dimensionLabel ?? "消息数") · 共 \(snapshot?.entries.count ?? 0) 位代理人",
                            role: .caption,
                            color: KnockTokens.textSecondary,
                            lineLimit: 1
                        )
                    }
                    Spacer(minLength: 0)
                }

                if let snapshot {
                    HStack(spacing: KnockTokens.spacingS) {
                        KnockCountPill(title: "合计", value: "\(snapshot.totalValue)")
                        if snapshot.estimated {
                            KnockBadge(text: "估算", variant: .neutral)
                        }
                        Spacer(minLength: 0)
                    }
                    KnockText(snapshot.note, role: .caption, color: KnockTokens.textTertiary, lineSpacing: 3)
                }
            }
        }
    }

    private var dimensionPanel: some View {
        VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
            KnockGroupTitle(title: "维度", systemImage: "square.grid.2x2.fill")
            KnockSegmentedBar(
                items: RankingDimension.allCases
                    .filter { $0 != .consumption }
                    .map { KnockSegmentItem(value: $0, title: $0.label) },
                selection: $dimension,
                height: KnockTokens.segmentedSmallHeight,
                showsIcons: false
            )
        }
    }

    private var rangeBar: some View {
        KnockSegmentedBar(
            items: RankingRange.allCases.map { item in
                KnockSegmentItem(
                    value: item,
                    title: item == .yesterday ? "昨日排行" : "历史总榜"
                )
            },
            selection: $range,
            height: KnockTokens.segmentedSmallHeight,
            showsIcons: false
        )
        .accessibilityLabel("排行区间")
    }

    // MARK: - 排行榜列表

    @ViewBuilder
    private func rankingList(_ snapshot: RankingSnapshotItem) -> some View {
        VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
            HStack(spacing: KnockTokens.spacingS) {
                KnockText("排名", role: .captionStrong, color: KnockTokens.textSecondary)
                    .frame(width: 38, alignment: .leading)
                KnockText("代理人", role: .captionStrong, color: KnockTokens.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                KnockText(dimension.valueColumnTitle, role: .captionStrong, color: KnockTokens.textSecondary)
                    .frame(width: 64, alignment: .trailing)
                KnockText("好感", role: .captionStrong, color: KnockTokens.textSecondary)
                    .frame(width: 44, alignment: .trailing)
            }
            .padding(.horizontal, KnockTokens.spacingS)

            ForEach(Array(snapshot.entries.enumerated()), id: \.element.id) { index, entry in
                NavigationLink(value: Route.roleDetail(roleId: entry.operatorId)) {
                    rankingRow(entry: entry)
                }
                .buttonStyle(.plain)
            }
        }
    }

    @ViewBuilder
    private func rankingRow(entry: RankingEntryItem) -> some View {
        KnockCapsuleCard(padding: KnockTokens.spacingM) {
            HStack(spacing: KnockTokens.spacingS) {
                rankBadge(rank: entry.rank)
                    .frame(width: 38, alignment: .leading)

                KnockAvatar(avatarId: entry.avatarId, name: entry.operatorName, size: 36)

                VStack(alignment: .leading, spacing: KnockTokens.spacingXXS) {
                    KnockText(entry.operatorName, role: .rowTitle, lineLimit: 1)
                    KnockText(entry.unit, role: .caption, color: KnockTokens.textTertiary, lineLimit: 1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                KnockText(entry.valueText, role: .rowTitle, lineLimit: 1)
                    .monospacedDigit()
                    .frame(width: 64, alignment: .trailing)

                KnockText(entry.secondaryText, role: .caption, color: KnockTokens.textSecondary, lineLimit: 1)
                    .monospacedDigit()
                    .frame(width: 44, alignment: .trailing)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("第\(entry.rank)名 \(entry.operatorName) \(entry.valueText)\(entry.unit)")
    }

    /// 名次徽章：前 3 名 = 黄底 + 浅蓝外圈 + 黑字；其余 = 描边胶囊 + 白字。
    @ViewBuilder
    private func rankBadge(rank: Int) -> some View {
        if rank <= 3 {
            Text("\(rank)")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(KnockTokens.onElectric)
                .frame(width: 28, height: 28)
                .background(Capsule(style: .continuous).fill(KnockTokens.electric))
                .overlay(
                    Capsule(style: .continuous)
                        .strokeBorder(KnockTokens.electricRing, lineWidth: KnockTokens.strokeWidth)
                )
                .accessibilityLabel("第\(rank)名")
        } else {
            Text("#\(rank)")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(KnockTokens.textSecondary)
                .frame(width: 28, height: 28)
                .background(Capsule(style: .continuous).fill(KnockTokens.rowIconWell))
                .overlay(
                    Capsule(style: .continuous)
                        .strokeBorder(KnockTokens.strokeSecondary, lineWidth: KnockTokens.strokeWidth)
                )
                .accessibilityLabel("第\(rank)名")
        }
    }

    // MARK: - 消费统计（Token）

    private var tokenStatsPanel: some View {
        KnockPanel {
            VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                HStack(spacing: KnockTokens.spacingM) {
                    KnockRowGlyph(systemImage: "creditcard.fill", size: 36)
                    VStack(alignment: .leading, spacing: KnockTokens.spacingXXS) {
                        KnockText("消费统计（Token）", role: .rowTitle, lineLimit: 1)
                        KnockText(
                            "输入 / 输出 Token 统计 · \(tokenStats?.rangeLabel ?? "今日")",
                            role: .caption,
                            color: KnockTokens.textSecondary,
                            lineLimit: 1
                        )
                    }
                    Spacer(minLength: 0)
                }

                KnockSegmentedBar(
                    items: TokenStatsRange.allCases.map {
                        KnockSegmentItem(value: $0, title: $0.label)
                    },
                    selection: $tokenRange,
                    height: KnockTokens.segmentedSmallHeight,
                    showsIcons: false
                )

                if let tokenErrorText {
                    KnockText(tokenErrorText, role: .caption, color: KnockTokens.dangerBright, lineSpacing: 3)
                } else if tokenLoading && tokenStats == nil {
                    KnockLoadingPanel("正在统计 Token…")
                } else if let tokenStats {
                    VStack(alignment: .leading, spacing: KnockTokens.spacingXXS) {
                        KnockText(formatRankingTokens(tokenStats.totalTokens), role: .pageTitle, lineLimit: 1)
                            .monospacedDigit()
                        KnockText(
                            "输入 \(formatRankingTokens(tokenStats.inputTokens))  +  输出 \(formatRankingTokens(tokenStats.outputTokens))",
                            role: .caption,
                            color: KnockTokens.textSecondary,
                            lineLimit: 1
                        )
                    }

                    VStack(spacing: 0) {
                        ForEach(tokenStats.categories) { cat in
                            HStack(spacing: KnockTokens.spacingS) {
                                KnockText(cat.name, role: .caption, lineLimit: 1)
                                    .frame(width: 44, alignment: .leading)
                                KnockText("输入 \(formatRankingTokens(cat.inputTokens))", role: .caption, color: KnockTokens.textSecondary, lineLimit: 1)
                                    .frame(maxWidth: .infinity, alignment: .trailing)
                                KnockText("输出 \(formatRankingTokens(cat.outputTokens))", role: .caption, color: KnockTokens.textSecondary, lineLimit: 1)
                                    .frame(maxWidth: .infinity, alignment: .trailing)
                                KnockText(formatRankingTokens(cat.totalTokens), role: .captionStrong, lineLimit: 1)
                                    .monospacedDigit()
                                    .frame(width: 54, alignment: .trailing)
                            }
                            .padding(.vertical, KnockTokens.spacingXS)
                            if cat.id != tokenStats.categories.last?.id {
                                KnockDivider()
                            }
                        }
                    }

                    KnockText(
                        "数据源：settings token_in/out_* 与 daily_in/out_*_<yyyy-MM-dd>（北京时间）",
                        role: .caption,
                        color: KnockTokens.textTertiary,
                        lineSpacing: 3
                    )
                }
            }
        }
    }

    // MARK: - 状态卡

    private var emptyPanel: some View {
        KnockEmptyState(
            title: "暂无排名数据",
            message: "先和代理人聊几句，排行榜就会记录你们的消息数与互动天数"
        )
    }

    // MARK: - 数据加载

    private func startIfNeeded() async {
        guard !loadedOnce else { return }
        loadedOnce = true
        if RankingAutomation.beginIfNeeded() {
            let bridgeResult = await environment.bridge.runRankingSelfTest()
            let bridgePassed = !bridgeResult.report.contains("FAIL")
            RankingAutomation.add("\(bridgePassed ? "PASS" : "FAIL") bridge.selftest.result \(bridgePassed ? "PASS" : "FAIL")")
            if let error = bridgeResult.error {
                RankingAutomation.add("FAIL bridge.selftest.error \(error.message)")
            }
        }
        await reload()
        await reloadTokens()
        if RankingAutomation.isEnabled {
            await runUIAutomation()
        }
    }

    private func reload() async {
        isLoading = true
        errorText = nil
        let result = await environment.bridge.loadRanking(dimension: dimension, range: range)
        switch result {
        case .success(let item):
            snapshot = item
        case .failure(let error):
            errorText = error.message
        }
        isLoading = false
    }

    private func reloadTokens() async {
        tokenLoading = true
        tokenErrorText = nil
        let result = await environment.bridge.loadTokenStats(range: tokenRange)
        switch result {
        case .success(let item):
            tokenStats = item
        case .failure(let error):
            tokenErrorText = error.message
        }
        tokenLoading = false
    }

    // MARK: - `--ranking-selftest` UI 链路断言

    private func runUIAutomation() async {
        RankingAutomation.add("ui.header range=\(snapshot?.rangeLabel ?? "-") dimension=\(snapshot?.dimensionLabel ?? "-")")

        let historyCounts = snapshot?.entries.map { ($0.operatorId, $0.value) } ?? []
        let historyTotal = snapshot?.totalValue ?? 0
        RankingAutomation.add(historyCounts.isEmpty ? "FAIL ui.messages.empty" : "PASS ui.messages.nonEmpty entries=\(historyCounts.count) total=\(historyTotal)")
        if let entries = snapshot?.entries {
            let ranksOk = entries.enumerated().allSatisfy { index, entry in entry.rank == index + 1 }
            let sortedOk = zip(entries, entries.dropFirst()).allSatisfy { $0.value >= $1.value }
            RankingAutomation.add(ranksOk ? "PASS ui.messages.ranksSequential" : "FAIL ui.messages.ranksSequential ranks=\(entries.map(\.rank))")
            RankingAutomation.add(sortedOk ? "PASS ui.messages.sortedDesc" : "FAIL ui.messages.sortedDesc")
            RankingAutomation.add(entries.allSatisfy { !$0.operatorName.isEmpty && !$0.unit.isEmpty } ? "PASS ui.messages.fields" : "FAIL ui.messages.fields")
        }

        // 维度切换：互动天数
        dimension = .days
        await reload()
        let days = snapshot
        RankingAutomation.add((days?.entries.isEmpty == false) ? "PASS ui.days.nonEmpty entries=\(days?.entries.count ?? 0)" : "FAIL ui.days.empty")
        if let days {
            let fits = days.entries.allSatisfy { entry in
                (historyCounts.first { $0.0 == entry.operatorId }?.1 ?? 0) >= entry.value
            }
            RankingAutomation.add(fits ? "PASS ui.days.lteMessageCount" : "FAIL ui.days.lteMessageCount")
        }

        // 维度切换：消费（估算）
        dimension = .consumption
        await reload()
        let consumption = snapshot
        RankingAutomation.add((consumption?.estimated == true) ? "PASS ui.consumption.estimated" : "FAIL ui.consumption.estimated")
        RankingAutomation.add((consumption?.entries.isEmpty == false) ? "PASS ui.consumption.nonEmpty entries=\(consumption?.entries.count ?? 0)" : "FAIL ui.consumption.empty")

        // 昨日区间（oracle 口径：>= 昨日 00:00，含今天）
        dimension = .messages
        range = .yesterday
        await reload()
        let yesterday = snapshot
        let yesterdayOk = (yesterday?.entries ?? []).allSatisfy { entry in
            (historyCounts.first { $0.0 == entry.operatorId }?.1 ?? 0) >= entry.value
        }
        RankingAutomation.add(yesterdayOk ? "PASS ui.yesterday.lteHistory total=\(yesterday?.totalValue ?? -1)" : "FAIL ui.yesterday.lteHistory")
        range = .history
        await reload()

        // Token 三分段
        for item in TokenStatsRange.allCases {
            tokenRange = item
            await reloadTokens()
            let ok = tokenStats?.categories.count == 6 && (tokenStats?.totalTokens ?? -1) >= 0
            RankingAutomation.add(ok ? "PASS ui.token.\(item.rawValue) total=\(tokenStats?.totalTokens ?? -1)" : "FAIL ui.token.\(item.rawValue)")
        }
        tokenRange = .today
        await reloadTokens()

        RankingAutomation.finish(summary:
            "UI 断言完成：bridge selftest + 三维度切换 + 昨日/历史 + Token 三分段；截图页面停在「历史 · 消息数」"
        )
    }
}