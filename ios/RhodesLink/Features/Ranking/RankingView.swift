//
//  RankingView.swift
//  聊天排行榜 / 消费统计（T54，与 Android 原版 RankingScreen + TokenStatsScreen 对齐的最小完整面）：
//  - 维度分段：消息数 / 互动天数 / 消费（估算）；消息数支持「昨日 / 历史」区间（oracle 两个 tab）；
//  - 排行列表：排名徽章（1-3 名金/银/铜）、角色头像与名称、维度数值、好感列；点行进入角色详情（oracle 点角色进私聊）；
//  - 消费统计卡片：今日/本周/全部 Token（输入+输出）与 6 分类明细（oracle TokenStatsScreen.kt:65 口径）；
//  - 空态 / 加载态 / 错误态 / 下拉刷新；数据全部来自 shared `RankingBridge` 真实 SQLite + settings，无 mock；
//  - `--ranking-selftest`：自动跑桥层自检 + UI 链路自检并写 Documents/t54-ranking-selftest.log（截图证据）。
//

import SwiftUI

@MainActor
struct RankingView: View {
    @EnvironmentObject private var environment: AppEnvironment

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

    private let medalColors: [Color] = [
        Color(hex: 0xFFD700), // 金
        Color(hex: 0xC0C0C0), // 银
        Color(hex: 0xCD7F32), // 铜
    ]

    var body: some View {
        ZStack {
            AmbientBackground(parallax: 0, showsStandee: false)

            ScrollView {
                LazyVStack(spacing: 12) {
                    headerCard

                    dimensionPicker
                    rangePicker

                    if let errorText {
                        errorCard(errorText) { Task { await reload() } }
                    } else if isLoading && snapshot == nil {
                        loadingCard
                    } else if let snapshot, snapshot.entries.isEmpty {
                        emptyCard
                    } else if let snapshot {
                        rankingList(snapshot)
                    }

                    tokenStatsCard
                }
                .padding(.horizontal, 14)
                .padding(.bottom, 24)
            }
            .refreshable {
                await reload()
                await reloadTokens()
            }
        }
        .navigationTitle("聊天排行榜")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    Task {
                        await reload()
                        await reloadTokens()
                    }
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .accessibilityLabel("刷新排行榜")
            }
        }
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

    private var headerCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 10) {
                Image(systemName: "chart.bar.fill")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 36, height: 36)
                    .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Color(hex: 0xFFB300)))

                VStack(alignment: .leading, spacing: 2) {
                    Text("聊天排行榜")
                        .font(.headline)
                    Text("\(snapshot?.rangeLabel ?? "历史") · \(snapshot?.dimensionLabel ?? "消息数") · 共 \(snapshot?.entries.count ?? 0) 位干员")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
            }

            if let snapshot {
                HStack(spacing: 6) {
                    statPill(title: "合计", value: "\(snapshot.totalValue)")
                    if snapshot.estimated {
                        Text("估算")
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 7)
                            .padding(.vertical, 3)
                            .background(Capsule().fill(Color(hex: 0xFF9500)))
                    }
                    Spacer(minLength: 0)
                }
                Text(snapshot.note)
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(cornerRadius: 20)
    }

    private var dimensionPicker: some View {
        VStack(alignment: .leading, spacing: 8) {
            SectionTitle(text: "维度", systemImage: "square.grid.2x2.fill")
            Picker("维度", selection: $dimension) {
                ForEach(RankingDimension.allCases) { dim in
                    Text(dim.label).tag(dim)
                }
            }
            .pickerStyle(.segmented)
        }
        .padding(14)
        .cardSurface(cornerRadius: 18)
    }

    private var rangePicker: some View {
        HStack(spacing: 8) {
            ForEach(RankingRange.allCases) { item in
                Button {
                    range = item
                } label: {
                    Text(item == .yesterday ? "昨日排行" : "历史总榜")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(range == item ? .white : .secondary)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 7)
                        .background(
                            Capsule().fill(range == item ? Color(hex: 0xFFB300) : GlassTokens.bubbleSystem)
                        )
                }
                .buttonStyle(.plain)
                .accessibilityLabel("切换\(item.label)排行")
            }
            Spacer(minLength: 0)
            Text(dimension.valueColumnTitle)
                .font(.caption2)
                .foregroundStyle(.tertiary)
        }
        .padding(.horizontal, 4)
        .padding(.vertical, 2)
    }

    // MARK: - 排行榜列表

    @ViewBuilder
    private func rankingList(_ snapshot: RankingSnapshotItem) -> some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                Text("排名")
                    .frame(width: 42, alignment: .leading)
                Text("干员")
                    .frame(maxWidth: .infinity, alignment: .leading)
                Text(dimension.valueColumnTitle)
                    .frame(width: 64, alignment: .trailing)
                Text("好感")
                    .frame(width: 44, alignment: .trailing)
            }
            .font(.caption2)
            .foregroundStyle(.secondary)
            .padding(.horizontal, 14)
            .padding(.vertical, 10)

            ForEach(Array(snapshot.entries.enumerated()), id: \.element.id) { index, entry in
                NavigationLink(value: Route.roleDetail(roleId: entry.operatorId)) {
                    rankingRow(entry: entry, index: index)
                }
                .buttonStyle(.plain)

                if index < snapshot.entries.count - 1 {
                    Divider()
                        .background(GlassTokens.glassStroke)
                        .padding(.leading, 14)
                }
            }
        }
        .cardSurface(cornerRadius: 18)
    }

    @ViewBuilder
    private func rankingRow(entry: RankingEntryItem, index: Int) -> some View {
        HStack(spacing: 10) {
            rankBadge(rank: entry.rank)
                .frame(width: 42, alignment: .leading)

            RoleAvatar(avatarId: entry.avatarId, name: entry.operatorName, size: 36)

            VStack(alignment: .leading, spacing: 2) {
                Text(entry.operatorName)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                Text(entry.unit)
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Text(entry.valueText)
                .font(.subheadline.weight(.semibold))
                .monospacedDigit()
                .foregroundStyle(.primary)
                .frame(width: 64, alignment: .trailing)

            Text(entry.secondaryText)
                .font(.caption.weight(.medium))
                .monospacedDigit()
                .foregroundStyle(Color(hex: 0xFF9800))
                .frame(width: 44, alignment: .trailing)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityLabel("第\(entry.rank)名 \(entry.operatorName) \(entry.valueText)\(entry.unit)")
    }

    @ViewBuilder
    private func rankBadge(rank: Int) -> some View {
        if rank <= 3 {
            Text("\(rank)")
                .font(.caption.weight(.bold))
                .foregroundStyle(.white)
                .frame(width: 26, height: 26)
                .background(Circle().fill(medalColors[rank - 1]))
                .accessibilityLabel("第\(rank)名")
        } else {
            Text("#\(rank)")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
                .frame(width: 26, height: 26)
                .background(Circle().fill(GlassTokens.bubbleSystem))
                .accessibilityLabel("第\(rank)名")
        }
    }

    // MARK: - 消费统计（Token）

    private var tokenStatsCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 10) {
                Image(systemName: "creditcard.fill")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 32, height: 32)
                    .background(RoundedRectangle(cornerRadius: 9, style: .continuous).fill(Color(hex: 0x1989FA)))
                VStack(alignment: .leading, spacing: 2) {
                    Text("消费统计（Token）")
                        .font(.headline)
                    Text("输入 / 输出 Token 统计 · \(tokenStats?.rangeLabel ?? "今日")")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
            }

            Picker("统计区间", selection: $tokenRange) {
                ForEach(TokenStatsRange.allCases) { item in
                    Text(item.label).tag(item)
                }
            }
            .pickerStyle(.segmented)

            if let tokenErrorText {
                Text(tokenErrorText)
                    .font(.caption)
                    .foregroundStyle(.red)
            } else if tokenLoading && tokenStats == nil {
                ProgressView()
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
            } else if let tokenStats {
                VStack(alignment: .leading, spacing: 4) {
                    Text(formatRankingTokens(tokenStats.totalTokens))
                        .font(.title2.weight(.bold))
                        .monospacedDigit()
                        .foregroundStyle(.primary)
                    Text("输入 \(formatRankingTokens(tokenStats.inputTokens))  +  输出 \(formatRankingTokens(tokenStats.outputTokens))")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .padding(.vertical, 2)

                VStack(spacing: 0) {
                    ForEach(tokenStats.categories) { cat in
                        HStack(spacing: 8) {
                            Text(cat.name)
                                .font(.caption)
                                .foregroundStyle(.primary)
                                .frame(width: 44, alignment: .leading)
                            Text("输入 \(formatRankingTokens(cat.inputTokens))")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                                .frame(maxWidth: .infinity, alignment: .trailing)
                            Text("输出 \(formatRankingTokens(cat.outputTokens))")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                                .frame(maxWidth: .infinity, alignment: .trailing)
                            Text(formatRankingTokens(cat.totalTokens))
                                .font(.caption.weight(.semibold))
                                .monospacedDigit()
                                .foregroundStyle(.primary)
                                .frame(width: 54, alignment: .trailing)
                        }
                        .padding(.vertical, 6)
                        if cat.id != tokenStats.categories.last?.id {
                            Divider().background(GlassTokens.glassStroke)
                        }
                    }
                }
                Text("数据源：settings token_in/out_* 与 daily_in/out_*_<yyyy-MM-dd>（北京时间）")
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(cornerRadius: 20)
    }

    // MARK: - 状态卡

    private var loadingCard: some View {
        HStack(spacing: 10) {
            ProgressView()
            Text("正在读取排行榜…")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(18)
        .cardSurface(cornerRadius: 18)
    }

    private var emptyCard: some View {
        VStack(spacing: 8) {
            Image(systemName: "chart.bar.xaxis")
                .font(.system(size: 34, weight: .light))
                .foregroundStyle(.tertiary)
            Text("暂无数据")
                .font(.subheadline.weight(.semibold))
            Text("先和干员聊几句，排行榜就会记录你们的消息数、互动天数与消费")
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(24)
        .cardSurface(cornerRadius: 18)
    }

    private func errorCard(_ text: String, retry: @escaping () -> Void) -> some View {
        VStack(spacing: 10) {
            Label("排行榜读取失败", systemImage: "exclamationmark.triangle.fill")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.red)
            Text(text)
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            Button("重试", action: retry)
                .buttonStyle(.borderedProminent)
                .tint(GlassTokens.brandOrange)
        }
        .frame(maxWidth: .infinity)
        .padding(20)
        .cardSurface(cornerRadius: 18)
    }

    private func statPill(title: String, value: String) -> some View {
        HStack(spacing: 4) {
            Text(title)
                .font(.caption2)
                .foregroundStyle(.secondary)
            Text(value)
                .font(.caption.weight(.semibold))
                .monospacedDigit()
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 3)
        .background(Capsule().fill(GlassTokens.bubbleSystem))
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

