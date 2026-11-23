//
//  ImpressionsView.swift
//  「大家的印象」（T54，与 Android 原版 ImpressionsScreen 对齐的最小完整面）：
//  - 三段分组：长期印象 / 持续了解 / 互动印象（oracle ImpressionsScreen.kt:137-145 标题与副标题）；
//  - 行：角色头像 + 名称 + 「级别 · 日期」+ 印象正文 + 类型标签 + 来源标签（记忆管线 / 旧版 memories）；
//  - 空态 / 加载态 / 错误态 / 下拉刷新；点行进入角色详情（oracle 点角色进私聊）；
//  - 数据全部来自 shared `ImpressionsBridge`（memory_items L1/L2/L3 + 旧版 memories 只读兜底），无 mock；
//  - `--impressions-selftest`：自动跑桥层自检 + UI 链路自检并写 Documents/t54-impressions-selftest.log。
//

import SwiftUI

@MainActor
struct ImpressionsView: View {
    @EnvironmentObject private var environment: AppEnvironment

    @State private var snapshot: ImpressionsSnapshotItem?
    @State private var isLoading = false
    @State private var errorText: String?
    @State private var loadedOnce = false
    @State private var automationReport = ""

    var body: some View {
        ZStack {
            AmbientBackground(parallax: 0, showsStandee: false)

            ScrollView {
                LazyVStack(spacing: 12) {
                    headerCard

                    if ImpressionsAutomation.isEnabled {
                        automationCard
                    }

                    if let errorText {
                        errorCard(errorText)
                    } else if isLoading && snapshot == nil {
                        loadingCard
                    } else if let snapshot, snapshot.entries.isEmpty {
                        emptyCard
                    } else if let snapshot {
                        sections(snapshot)
                    }
                }
                .padding(.horizontal, 14)
                .padding(.bottom, 24)
            }
            .refreshable { await reload() }
        }
        .navigationTitle("大家的印象")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    Task { await reload() }
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .accessibilityLabel("刷新印象")
            }
        }
        .task {
            await startIfNeeded()
        }
    }

    // MARK: - 头部

    private var headerCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 10) {
                Image(systemName: "person.crop.circle.badge.questionmark.fill")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 36, height: 36)
                    .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Color(hex: 0xFF9500)))

                VStack(alignment: .leading, spacing: 2) {
                    Text("大家对你们的印象")
                        .font(.headline)
                    Text("共 \(snapshot?.totalCount ?? 0) 条 · 长期印象 \(snapshot?.longTermCount ?? 0) · 持续了解 \(snapshot?.sustainedCount ?? 0) · 互动印象 \(snapshot?.interactionCount ?? 0)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
            }

            HStack(spacing: 6) {
                countPill(title: "长期印象", value: snapshot?.longTermCount ?? 0, tint: Color(hex: 0x07C160))
                countPill(title: "持续了解", value: snapshot?.sustainedCount ?? 0, tint: Color(hex: 0x1989FA))
                countPill(title: "互动印象", value: snapshot?.interactionCount ?? 0, tint: Color(hex: 0xFF9500))
                Spacer(minLength: 0)
            }

            if let snapshot {
                Text(snapshot.note)
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
                    .fixedSize(horizontal: false, vertical: true)
                if snapshot.legacyCount > 0 {
                    Text("含 \(snapshot.legacyCount) 条旧版 memories 长期印象（只读兜底，未与记忆管线 L3 重复）")
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                }
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(cornerRadius: 20)
    }

    private func countPill(title: String, value: Int, tint: Color) -> some View {
        HStack(spacing: 4) {
            Circle().fill(tint).frame(width: 6, height: 6)
            Text(title)
                .font(.caption2)
                .foregroundStyle(.secondary)
            Text("\(value)")
                .font(.caption.weight(.semibold))
                .monospacedDigit()
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 3)
        .background(Capsule().fill(GlassTokens.bubbleSystem))
    }

    // MARK: - 三段分组

    @ViewBuilder
    private func sections(_ snapshot: ImpressionsSnapshotItem) -> some View {
        ForEach(snapshot.sections, id: \.level) { section in
            VStack(alignment: .leading, spacing: 0) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(section.title)
                        .font(.subheadline.weight(.semibold))
                    Text(section.subtitle)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 10)

                ForEach(Array(section.entries.enumerated()), id: \.element.id) { index, entry in
                    NavigationLink(value: Route.roleDetail(roleId: entry.operatorId)) {
                        impressionRow(entry)
                    }
                    .buttonStyle(.plain)
                    if index < section.entries.count - 1 {
                        Divider()
                            .background(GlassTokens.glassStroke)
                            .padding(.leading, 14)
                    }
                }
            }
            .cardSurface(cornerRadius: 18)
        }
    }

    @ViewBuilder
    private func impressionRow(_ entry: ImpressionEntryItem) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 10) {
                RoleAvatar(avatarId: entry.avatarId, name: entry.operatorName, size: 40)
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(entry.operatorName)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.primary)
                            .lineLimit(1)
                        if entry.isLegacy {
                            Text("旧版")
                                .font(.system(size: 9, weight: .bold))
                                .foregroundStyle(.white)
                                .padding(.horizontal, 5)
                                .padding(.vertical, 1)
                                .background(Capsule().fill(Color(hex: 0x8E8E93)))
                        }
                    }
                    Text("\(entry.levelLabel) · \(entry.dateText)")
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                }
                Spacer(minLength: 0)
            }

            Text(entry.content)
                .font(.subheadline)
                .foregroundStyle(.primary)
                .fixedSize(horizontal: false, vertical: true)

            HStack(spacing: 6) {
                tag(entry.memoryTypeLabel, tint: Color(hex: 0xFF9500))
                tag(entry.sourceLabel, tint: Color(hex: 0x1989FA))
                Spacer(minLength: 0)
                if entry.importance > 0 {
                    Text("重要度 \(entry.importance)")
                        .font(.system(size: 9))
                        .foregroundStyle(.tertiary)
                }
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(entry.operatorName) \(entry.levelLabel) \(entry.content)")
    }

    private func tag(_ text: String, tint: Color) -> some View {
        Text(text)
            .font(.system(size: 9, weight: .medium))
            .foregroundStyle(tint)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(Capsule().fill(tint.opacity(0.12)))
    }

    // MARK: - 状态卡

    private var loadingCard: some View {
        HStack(spacing: 10) {
            ProgressView()
            Text("正在读取印象…")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(18)
        .cardSurface(cornerRadius: 18)
    }

    private var emptyCard: some View {
        VStack(spacing: 8) {
            Image(systemName: "person.crop.circle.badge.questionmark")
                .font(.system(size: 34, weight: .light))
                .foregroundStyle(.tertiary)
            Text("暂无印象数据")
                .font(.subheadline.weight(.semibold))
            Text("多聊几次后，干员会逐渐形成对你的近期了解和长期印象")
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(24)
        .cardSurface(cornerRadius: 18)
    }

    private func errorCard(_ text: String) -> some View {
        VStack(spacing: 10) {
            Label("印象读取失败", systemImage: "exclamationmark.triangle.fill")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.red)
            Text(text)
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            Button("重试") {
                Task { await reload() }
            }
            .buttonStyle(.borderedProminent)
            .tint(GlassTokens.brandOrange)
        }
        .frame(maxWidth: .infinity)
        .padding(20)
        .cardSurface(cornerRadius: 18)
    }

    private var automationCard: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Image(systemName: ImpressionsAutomation.passed == true ? "checkmark.seal.fill" : "hourglass")
                    .foregroundStyle(ImpressionsAutomation.passed == true ? Color(hex: 0x07C160) : Color(hex: 0xFF9500))
                Text("T54 自检 · \(ImpressionsAutomation.resultText)")
                    .font(.caption.weight(.semibold))
                    .lineLimit(1)
            }
            if !automationReport.isEmpty {
                Text(automationReport)
                    .font(.system(size: 9, design: .monospaced))
                    .foregroundStyle(.secondary)
                    .lineLimit(12)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(cornerRadius: 16)
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
