//
//  ImpressionsView.swift
//  「大家的印象」（T54，与 Android 原版 ImpressionsScreen 对齐的最小完整面）：
//  - 三段分组：长期印象 / 持续了解 / 互动印象（oracle ImpressionsScreen.kt:137-145 标题与副标题）；
//  - 行：角色头像 + 名称 + 「级别 · 日期」+ 印象正文 + 类型标签 + 来源标签（记忆管线 / 旧版 memories）；
//  - 空态 / 加载态 / 错误态 / 下拉刷新；点行进入角色详情（oracle 点角色进私聊）；
//  - 数据全部来自 shared `ImpressionsBridge`（memory_items L1/L2/L3 + 旧版 memories 只读兜底），无 mock；
//  - `--impressions-selftest`：自动跑桥层自检 + UI 链路自检并写 Documents/t54-impressions-selftest.log。
//
//  敲敲视觉（P3）：隐藏系统导航栏 → `KnockHeader`（返回 + 刷新胶囊）；
//  概览 = 面板 + 灰阶计数胶囊；每条印象 = 胶囊卡片（头像 + 名称 + 级别/日期 + 正文）；
//  标签（类型 / 来源 / 重要度）用 `KnockChip` + 流式布局自动换行；
//  空态 = E M P T Y 宽字距 pill（`KnockEmptyState`）。
//

import SwiftUI

@MainActor
struct ImpressionsView: View {
    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.dismiss) private var dismiss

    @State private var snapshot: ImpressionsSnapshotItem?
    @State private var isLoading = false
    @State private var errorText: String?
    @State private var loadedOnce = false
    @State private var automationReport = ""

    var body: some View {
        ZStack {
            AmbientBackground(parallax: 0, showsStandee: false)

            VStack(spacing: 0) {
                KnockHeader(title: "大家的印象", showsBack: true, onBack: { dismiss() }, trailing: {
                    KnockCapsuleIconButton(
                        systemImage: "arrow.clockwise",
                        variant: .neutral,
                        accessibilityLabel: "刷新印象"
                    ) {
                        Task { await reload() }
                    }
                })

                ScrollView {
                    LazyVStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                        headerPanel

                        if ImpressionsAutomation.isEnabled {
                            automationPanel
                        }

                        if let errorText {
                            KnockErrorPanel(title: "印象读取失败", message: errorText) {
                                Task { await reload() }
                            }
                        } else if isLoading && snapshot == nil {
                            KnockLoadingPanel("正在读取印象…")
                        } else if let snapshot, snapshot.entries.isEmpty {
                            emptyCard
                        } else if let snapshot {
                            sections(snapshot)
                        }
                    }
                    .padding(.horizontal, KnockTokens.screenPadding)
                    .padding(.top, KnockTokens.spacingS)
                    .padding(.bottom, KnockTokens.spacingXL)
                }
                .scrollIndicators(.hidden)
                .knockScrollEdgeHidden()
                .refreshable { await reload() }
            }
        }
        .knockHiddenNavigationBar()
        .task {
            await startIfNeeded()
        }
    }

    // MARK: - 头部

    private var headerPanel: some View {
        KnockPanel {
            VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                HStack(spacing: KnockTokens.spacingM) {
                    KnockRowGlyph(systemImage: "person.crop.circle.badge.questionmark.fill")
                    VStack(alignment: .leading, spacing: KnockTokens.spacingXXS) {
                        KnockText("大家对你们的印象", role: .rowTitle, lineLimit: 1)
                        KnockText(
                            "共 \(snapshot?.totalCount ?? 0) 条 · 长期印象 \(snapshot?.longTermCount ?? 0) · 持续了解 \(snapshot?.sustainedCount ?? 0) · 互动印象 \(snapshot?.interactionCount ?? 0)",
                            role: .caption,
                            color: KnockTokens.textSecondary,
                            lineLimit: 2
                        )
                    }
                    Spacer(minLength: 0)
                }

                KnockFlowLayout(spacing: KnockTokens.spacingS) {
                    KnockCountPill(title: "长期印象", value: "\(snapshot?.longTermCount ?? 0)")
                    KnockCountPill(title: "持续了解", value: "\(snapshot?.sustainedCount ?? 0)")
                    KnockCountPill(title: "互动印象", value: "\(snapshot?.interactionCount ?? 0)")
                }

                if let snapshot {
                    KnockText(
                        snapshot.note,
                        role: .caption,
                        color: KnockTokens.textTertiary,
                        lineSpacing: 3
                    )
                    if snapshot.legacyCount > 0 {
                        KnockText(
                            "含 \(snapshot.legacyCount) 条旧版 memories 长期印象（只读兜底，未与记忆管线 L3 重复）",
                            role: .caption,
                            color: KnockTokens.textTertiary,
                            lineSpacing: 3
                        )
                    }
                }
            }
        }
    }

    // MARK: - 三段分组

    @ViewBuilder
    private func sections(_ snapshot: ImpressionsSnapshotItem) -> some View {
        ForEach(snapshot.sections, id: \.level) { section in
            VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
                VStack(alignment: .leading, spacing: KnockTokens.spacingXXS) {
                    KnockGroupTitle(title: section.title)
                    KnockText(section.subtitle, role: .caption, color: KnockTokens.textSecondary, lineSpacing: 2)
                }
                .padding(.horizontal, KnockTokens.spacingXS)

                ForEach(section.entries) { entry in
                    NavigationLink(value: Route.roleDetail(roleId: entry.operatorId)) {
                        impressionCard(entry)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    @ViewBuilder
    private func impressionCard(_ entry: ImpressionEntryItem) -> some View {
        KnockCapsuleCard {
            VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
                HStack(spacing: KnockTokens.spacingM) {
                    KnockAvatar(avatarId: entry.avatarId, name: entry.operatorName, size: 40)
                    VStack(alignment: .leading, spacing: KnockTokens.spacingXXS) {
                        HStack(spacing: KnockTokens.spacingXS) {
                            KnockText(entry.operatorName, role: .rowTitle, lineLimit: 1)
                            if entry.isLegacy {
                                KnockBadge(text: "旧版", variant: .neutral)
                            }
                        }
                        KnockText(
                            "\(entry.levelLabel) · \(entry.dateText)",
                            role: .caption,
                            color: KnockTokens.textTertiary,
                            lineLimit: 1
                        )
                    }
                    Spacer(minLength: KnockTokens.spacingS)
                    if entry.importance > 0 {
                        KnockText("重要度 \(entry.importance)", role: .caption, color: KnockTokens.textTertiary, lineLimit: 1)
                            .monospacedDigit()
                    }
                }

                KnockText(entry.content, role: .caption, color: KnockTokens.textPrimary, lineSpacing: 3)
                    .font(.system(size: 15, weight: .medium))

                // 印象标签：类型 / 来源（Chip 流式布局，自动换行）
                KnockFlowLayout(spacing: KnockTokens.spacingXS) {
                    KnockChip(title: entry.memoryTypeLabel, isSelected: false)
                    KnockChip(title: entry.sourceLabel, isSelected: false)
                }
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(entry.operatorName) \(entry.levelLabel) \(entry.content)")
    }

    // MARK: - 状态卡

    private var emptyCard: some View {
        KnockEmptyState(
            title: "暂无印象数据",
            message: "多聊几次后，代理人会逐渐形成对你的近期了解和长期印象"
        )
    }

    private var automationPanel: some View {
        KnockPanel(padding: KnockTokens.spacingM) {
            VStack(alignment: .leading, spacing: KnockTokens.spacingXS) {
                HStack(spacing: KnockTokens.spacingXS) {
                    Image(systemName: ImpressionsAutomation.passed == true ? "checkmark.seal.fill" : "hourglass")
                        .font(.system(size: 12, weight: .heavy))
                        .foregroundStyle(ImpressionsAutomation.passed == true ? KnockTokens.textPrimary : KnockTokens.textSecondary)
                        .accessibilityHidden(true)
                    KnockText("T54 自检 · \(ImpressionsAutomation.resultText)", role: .captionStrong, lineLimit: 1)
                    Spacer(minLength: 0)
                }
                if !automationReport.isEmpty {
                    Text(automationReport)
                        .font(.system(size: 9, weight: .medium, design: .monospaced))
                        .foregroundStyle(KnockTokens.textSecondary)
                        .lineLimit(12)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
    }

    // MARK: - 数据加载

    private func startIfNeeded() async {
        guard !loadedOnce else { return }
        loadedOnce = true
        if ImpressionsAutomation.beginIfNeeded() {
            let bridgeResult = await environment.bridge.runImpressionsSelfTest()
            automationReport = bridgeResult.report
            let bridgePassed = !bridgeResult.report.contains("FAIL")
            ImpressionsAutomation.add("\(bridgePassed ? "PASS" : "FAIL") bridge.selftest.result \(bridgePassed ? "PASS" : "FAIL")")
            if let error = bridgeResult.error {
                ImpressionsAutomation.add("FAIL bridge.selftest.error \(error.message)")
            }
        }
        await reload()
        if ImpressionsAutomation.isEnabled {
            runUIAutomation()
        }
    }

    private func reload() async {
        isLoading = true
        errorText = nil
        let result = await environment.bridge.loadImpressions()
        switch result {
        case .success(let item):
            snapshot = item
        case .failure(let error):
            errorText = error.message
        }
        isLoading = false
    }

    // MARK: - `--impressions-selftest` UI 链路断言

    private func runUIAutomation() {
        guard let snapshot else {
            ImpressionsAutomation.add("FAIL ui.snapshot.nil")
            ImpressionsAutomation.finish(summary: "UI 断言失败：快照为空")
            return
        }
        ImpressionsAutomation.add(snapshot.entries.isEmpty ? "FAIL ui.list.empty" : "PASS ui.list.nonEmpty total=\(snapshot.totalCount)")
        ImpressionsAutomation.add(
            snapshot.totalCount == snapshot.longTermCount + snapshot.sustainedCount + snapshot.interactionCount
                ? "PASS ui.counts.consistent total=\(snapshot.totalCount)"
                : "FAIL ui.counts.consistent total=\(snapshot.totalCount) L3=\(snapshot.longTermCount) L2=\(snapshot.sustainedCount) L1=\(snapshot.interactionCount)"
        )
        let sections = snapshot.sections
        let order = sections.map(\.level)
        ImpressionsAutomation.add(order == order.sorted(by: >) ? "PASS ui.sections.order \(order.joined(separator: "→"))" : "FAIL ui.sections.order \(order)")
        ImpressionsAutomation.add(
            snapshot.entries.allSatisfy { !$0.operatorName.isEmpty && !$0.content.isEmpty && !$0.levelLabel.isEmpty && !$0.sourceLabel.isEmpty }
                ? "PASS ui.fields.complete"
                : "FAIL ui.fields.complete"
        )
        let legacy = snapshot.legacyCount
        ImpressionsAutomation.add("ui.legacy.count=\(legacy) sections=\(sections.map { "\($0.title):\($0.entries.count)" }.joined(separator: ","))")
        ImpressionsAutomation.finish(summary: "UI 断言完成：三段分组 / 计数一致 / 字段完整 / 旧版兜底 \(legacy) 条；截图页面停在印象列表")
    }
}