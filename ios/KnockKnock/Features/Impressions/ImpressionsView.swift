//
//  ImpressionsView.swift
//  「大家的印象」（T54/T82，与 Android 原版 ImpressionsScreen 对齐的完整面）：
//  - 按角色分组：每个角色一个 section（头像 + 名称 + 条数），组内沿用快照排序
//    （L3 优先 → updatedAt 降序 → importance 降序），行内保留「长期/持续/互动」层级标签；
//  - 层级筛选：全部 / 长期印象 / 持续了解 / 互动印象，只过滤展示，不改快照计数与排序；
//  - 行：角色头像 + 名称 + 「级别 · 日期」+ 印象正文 + 类型/来源标签；
//    来自 impressions 表（T82）的行额外展示置信度、证据数、状态（生效中/已冻结）与
//    「首次 · 最近确认」时间，并可展开证据链（≤8 条，memoryId|weight|时间）；
//  - 操作菜单（行尾 ⋮）：冻结 / 解冻 / 拒绝（拒绝后不再出现）；走 ImpressionsBridge 的
//    freeze/unfreeze/reject，人工裁决只改 status，不改文本与证据；
//  - 数据优先来自 shared `ImpressionsBridge` 的 impressions 表；老库空表时桥内回退
//    memory_items L1/L2/L3 + 旧版 memories 只读兜底（此时不显示操作菜单）；
//  - 空态 / 加载态 / 错误态 / 下拉刷新；点行进入角色详情（oracle 点角色进私聊）；
//  - `--impressions-selftest`：自动跑桥层自检 + UI 链路自检并写 Documents/t54-impressions-selftest.log。
//
//  敲敲视觉（P3）：隐藏系统导航栏 → `KnockHeader`（返回 + 刷新胶囊）；
//  概览 = 面板 + 灰阶计数胶囊；每条印象 = 胶囊卡片（头像 + 名称 + 级别/日期 + 正文）；
//  标签（类型 / 来源 / 置信度 / 证据 / 状态）用 `KnockChip` + 流式布局自动换行；
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
    /// 层级筛选："" = 全部；L3 / L2 / L1 = 长期印象 / 持续了解 / 互动印象。
    @State private var levelFilter: String = ""
    /// 人工裁决失败提示（不遮挡列表）。
    @State private var actionError: String?
    /// 正在提交裁决的 impressionId（防重复点击）。
    @State private var actionBusy: Set<Int64> = []
    /// 已展开的证据行（key = impressionId）。
    @State private var evidenceByEntry: [Int64: [ImpressionEvidenceItem]] = [:]
    /// 正在读取证据的 impressionId。
    @State private var evidenceLoading: Set<Int64> = []
    /// T82 分页：正在加载下一页（滚动到底 / 筛选未命中时自动续翻）。
    @State private var isLoadingMore = false
    /// T83 编辑弹窗目标（nil = 不展示）。
    @State private var editTarget: ImpressionEntryItem?
    /// T83 已拒绝管理弹层。
    @State private var showRejectedSheet = false
    @State private var rejectedEntries: [ImpressionEntryItem] = []
    @State private var isLoadingRejected = false
    @State private var rejectedError: String?
    @State private var rejectedBusy: Set<Int64> = []

    private static let pageSize = 20
    /// 筛选未命中时最多自动续翻的页数（防止一次滚到底把整表读光）。
    private static let maxAutoPages = 10

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

                        if let actionError {
                            KnockErrorPanel(
                                title: "印象操作失败",
                                message: actionError,
                                retryTitle: nil,
                                retry: nil
                            )
                        }

                        if ImpressionsAutomation.isEnabled {
                            automationPanel
                        }

                        if let errorText {
                            KnockErrorPanel(title: "印象读取失败", message: errorText) {
                                Task { await reload() }
                            }
                        } else if isLoading && snapshot == nil {
                            KnockLoadingPanel("正在读取印象…")
                        } else if let snapshot {
                            if snapshot.entries.isEmpty {
                                emptyCard
                            } else {
                                levelFilterBar
                                let groups = roleGroups(filteredEntries(snapshot.entries))
                                if groups.isEmpty {
                                    if snapshot.hasMore {
                                        loadMorePanel
                                    } else {
                                        emptyCard
                                    }
                                } else {
                                    roleSections(groups)
                                    if snapshot.hasMore {
                                        loadMorePanel
                                    } else {
                                        loadedAllFooter(snapshot)
                                    }
                                }
                            }
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
        .onChange(of: levelFilter) { _, _ in
            Task { await fillFilteredPageIfNeeded() }
        }
        .sheet(item: $editTarget) { entry in
            ImpressionEditSheet(entry: entry) {
                Task { await reload() }
            }
        }
        .sheet(isPresented: $showRejectedSheet) {
            RejectedImpressionsSheet(
                entries: rejectedEntries,
                isLoading: isLoadingRejected,
                errorText: rejectedError,
                busyIds: rejectedBusy,
                onUndo: { entry in Task { await undoReject(entry) } },
                onClose: { showRejectedSheet = false }
            )
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
                            "共 \(snapshot?.total ?? 0) 条 · 长期印象 \(snapshot?.longTermCount ?? 0) · 持续了解 \(snapshot?.sustainedCount ?? 0) · 互动印象 \(snapshot?.interactionCount ?? 0)",
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
                    if snapshot.hasMore {
                        KnockText(
                            "已加载 \(snapshot.totalCount) / \(snapshot.total) 条 · 下滑继续加载",
                            role: .caption,
                            color: KnockTokens.textTertiary,
                            lineLimit: 1
                        )
                        .monospacedDigit()
                    }
                    if snapshot.usesImpressionTable && snapshot.rejectedCount > 0 {
                        Button {
                            showRejectedSheet = true
                            Task { await reloadRejected() }
                        } label: {
                            HStack(spacing: 6) {
                                Image(systemName: "arrow.uturn.backward")
                                    .font(.system(size: 10, weight: .heavy))
                                    .accessibilityHidden(true)
                                KnockText(
                                    "已拒绝 \(snapshot.rejectedCount) 条 · 撤销",
                                    role: .captionStrong,
                                    color: KnockTokens.textSecondary,
                                    lineLimit: 1
                                )
                            }
                            .padding(.horizontal, 12)
                            .frame(minHeight: KnockTokens.chipHeight)
                            .background(Capsule(style: .continuous).fill(KnockTokens.rowIconWell))
                            .overlay {
                                Capsule(style: .continuous)
                                    .strokeBorder(KnockTokens.strokeSecondary, lineWidth: KnockTokens.strokeWidth)
                            }
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("管理已拒绝的印象（\(snapshot.rejectedCount) 条）")
                    }
                    if !snapshot.usesImpressionTable {
                        // 新印象表还没有数据时页面回退到记忆条目，必须明说，否则看起来像「印象已形成」。
                        KnockText(
                            "长期印象还在形成中：这里暂时展示的是已记录的记忆条目。",
                            role: .caption,
                            color: KnockTokens.textSecondary,
                            lineSpacing: 3
                        )
                    } else {
                        KnockText(
                            snapshot.note,
                            role: .caption,
                            color: KnockTokens.textTertiary,
                            lineSpacing: 3
                        )
                    }
                    if snapshot.legacyCount > 0 {
                        KnockText(
                            "含 \(snapshot.legacyCount) 条早期记录",
                            role: .caption,
                            color: KnockTokens.textTertiary,
                            lineSpacing: 3
                        )
                    }
                }
            }
        }
    }

    // MARK: - 层级筛选

    /// 层级筛选栏（二级分段；与记忆管理页同款）。只影响展示，不改快照计数。
    private var levelFilterBar: some View {
        KnockSegmentedBar(
            items: [
                KnockSegmentItem(value: "", title: "全部"),
                KnockSegmentItem(value: "L3", title: "长期印象"),
                KnockSegmentItem(value: "L2", title: "持续了解"),
                KnockSegmentItem(value: "L1", title: "互动印象"),
            ],
            selection: $levelFilter,
            height: KnockTokens.segmentedSmallHeight,
            showsIcons: false
        )
        .accessibilityLabel("按层级筛选印象")
    }

    private func filteredEntries(_ entries: [ImpressionEntryItem]) -> [ImpressionEntryItem] {
        guard !levelFilter.isEmpty else { return entries }
        return entries.filter { $0.level == levelFilter }
    }

    // MARK: - 按角色分组

    /// 按 operatorId 分组；组顺序 = 角色首条条目在快照中的顺序（即 L3 优先的角色靠前），
    /// 组内保持快照原始排序（updatedAt 降序 → importance 降序）。
    private func roleGroups(_ entries: [ImpressionEntryItem]) -> [RoleImpressionGroup] {
        var order: [String] = []
        var buckets: [String: [ImpressionEntryItem]] = [:]
        for entry in entries {
            let key = entry.operatorId.isEmpty ? "name:\(entry.operatorName)" : entry.operatorId
            if buckets[key] == nil {
                order.append(key)
                buckets[key] = []
            }
            buckets[key]?.append(entry)
        }
        return order.compactMap { key in
            guard let items = buckets[key], let first = items.first else { return nil }
            return RoleImpressionGroup(
                roleId: first.operatorId,
                operatorName: first.operatorName,
                avatarId: first.avatarId,
                entries: items
            )
        }
    }

    @ViewBuilder
    private func roleSections(_ groups: [RoleImpressionGroup]) -> some View {
        ForEach(groups) { group in
            VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
                roleSectionHeader(group)

                ForEach(group.entries) { entry in
                    impressionRow(entry)
                }
            }
        }
    }

    private func roleSectionHeader(_ group: RoleImpressionGroup) -> some View {
        HStack(spacing: KnockTokens.spacingS) {
            RoleAvatar(avatarId: group.avatarId, name: group.operatorName, size: 32)
            KnockText(group.operatorName, role: .sectionTitle, lineLimit: 1)
            Spacer(minLength: KnockTokens.spacingS)
            KnockText("\(group.entries.count) 条", role: .captionStrong, color: KnockTokens.textSecondary, lineLimit: 1)
                .monospacedDigit()
        }
        .padding(.horizontal, KnockTokens.spacingXS)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(group.operatorName)，\(group.entries.count) 条印象")
        .accessibilityAddTraits(.isHeader)
    }

    /// 一行印象：卡片内左侧是进角色详情的链接，右上角是操作菜单（⋮）；
    /// 证据展开放在链接外，避免按钮嵌在 NavigationLink 里互相抢点击。
    @ViewBuilder
    private func impressionRow(_ entry: ImpressionEntryItem) -> some View {
        KnockCapsuleCard {
            VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
                HStack(alignment: .top, spacing: KnockTokens.spacingS) {
                    NavigationLink(value: Route.roleDetail(roleId: entry.operatorId)) {
                        impressionCardBody(entry)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)

                    if entry.hasImpressionRow {
                        impressionMenuButton(entry)
                    }
                }

                if entry.hasImpressionRow {
                    evidenceToggle(entry)
                }
            }
        }
    }

    @ViewBuilder
    private func impressionCardBody(_ entry: ImpressionEntryItem) -> some View {
        VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
            HStack(spacing: KnockTokens.spacingM) {
                KnockAvatar(avatarId: entry.avatarId, name: entry.operatorName, size: 40)
                VStack(alignment: .leading, spacing: KnockTokens.spacingXXS) {
                    HStack(spacing: KnockTokens.spacingXS) {
                        KnockText(entry.operatorName, role: .rowTitle, lineLimit: 1)
                        if entry.isFrozen {
                            KnockBadge(text: "已冻结", variant: .accent)
                        }
                    }
                    KnockText(
                        "\(entry.levelLabel) · \(entry.dateText)",
                        role: .caption,
                        color: KnockTokens.textTertiary,
                        lineLimit: 1
                    )
                    if !entry.confirmText.isEmpty {
                        KnockText(entry.confirmText, role: .caption, color: KnockTokens.textTertiary, lineLimit: 1)
                            .monospacedDigit()
                    }
                }
                Spacer(minLength: KnockTokens.spacingS)
                if entry.hasImpressionRow {
                    KnockText(entry.confidenceText, role: .caption, color: KnockTokens.textTertiary, lineLimit: 1)
                        .monospacedDigit()
                }
            }

            KnockText(entry.content, role: .caption, color: KnockTokens.textPrimary, lineSpacing: 3)
                .font(.system(size: 15, weight: .medium))

            // 印象标签：类型 / 来源 / 证据 / 状态（Chip 流式布局，自动换行）
            KnockFlowLayout(spacing: KnockTokens.spacingXS) {
                KnockChip(title: entry.memoryTypeLabel, isSelected: false)
                if entry.sourceLabel != entry.memoryTypeLabel {
                    KnockChip(title: entry.sourceLabel, isSelected: false)
                }
                if entry.hasImpressionRow {
                    KnockChip(title: entry.evidenceText, isSelected: false)
                    KnockChip(title: entry.statusLabel, isSelected: entry.isFrozen)
                }
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(entry.operatorName) \(entry.levelLabel) \(entry.content)")
    }

    /// 行尾 ⋮：敲敲自绘菜单（替代系统 Menu），actions 按状态切换解冻/冻结。
    private func impressionMenuButton(_ entry: ImpressionEntryItem) -> some View {
        Button {
            KnockMenuCenter.shared.present(
                KnockMenuPresentation(title: "印象操作", actions: impressionMenuActions(entry))
            )
        } label: {
            Image(systemName: "ellipsis")
                .font(.system(size: 13, weight: .heavy))
                .foregroundStyle(KnockTokens.textSecondary)
                .frame(width: 32, height: 26)
                .background(Capsule(style: .continuous).fill(KnockTokens.rowIconWell))
                .frame(minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(actionBusy.contains(entry.impressionId))
        .opacity(actionBusy.contains(entry.impressionId) ? 0.4 : 1)
        .accessibilityLabel("印象操作")
        .accessibilityIdentifier("impression-menu-\(entry.impressionId)")
    }

    private func impressionMenuActions(_ entry: ImpressionEntryItem) -> [KnockMenuAction] {
        var actions: [KnockMenuAction] = [
            KnockMenuAction("编辑正文", systemImage: "square.and.pencil") {
                editTarget = entry
            },
        ]
        if entry.isFrozen {
            actions.append(
                KnockMenuAction("解冻印象", systemImage: "sun.max") {
                    Task { await applyImpressionAction(entry, action: .unfreeze) }
                }
            )
        } else {
            actions.append(
                KnockMenuAction("冻结印象", systemImage: "snowflake") {
                    Task { await applyImpressionAction(entry, action: .freeze) }
                }
            )
        }
        actions.append(
            KnockMenuAction("拒绝（不再出现）", systemImage: "hand.raised", role: .destructive) {
                Task { await applyImpressionAction(entry, action: .reject) }
            }
        )
        return actions
    }

    /// 证据链展开：点「查看 N 条证据」按需读取，再点收起；最多 8 条。
    @ViewBuilder
    private func evidenceToggle(_ entry: ImpressionEntryItem) -> some View {
        let expanded = evidenceByEntry[entry.impressionId] != nil
        let loading = evidenceLoading.contains(entry.impressionId)
        VStack(alignment: .leading, spacing: KnockTokens.spacingXS) {
            Button {
                Task { await toggleEvidence(entry) }
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: expanded ? "chevron.up" : "chevron.down")
                        .font(.system(size: 10, weight: .heavy))
                    KnockText(
                        loading ? "读取证据…" : (expanded ? "收起证据" : "查看 \(entry.evidenceCount) 条证据"),
                        role: .captionStrong,
                        color: KnockTokens.textSecondary,
                        lineLimit: 1
                    )
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(loading || entry.evidenceCount <= 0)
            .opacity(entry.evidenceCount <= 0 ? 0.5 : 1)
            .accessibilityLabel(expanded ? "收起证据" : "查看证据")

            if let rows = evidenceByEntry[entry.impressionId] {
                if rows.isEmpty {
                    KnockText("暂无证据记录", role: .caption, color: KnockTokens.textTertiary)
                } else {
                    VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
                        ForEach(rows) { row in
                            VStack(alignment: .leading, spacing: KnockTokens.spacingXXS) {
                                KnockText(row.summaryText, role: .caption, color: KnockTokens.textPrimary, lineSpacing: 2)
                                HStack(spacing: KnockTokens.spacingXS) {
                                    KnockText("记忆 #\(row.memoryId)", role: .caption, color: KnockTokens.textSecondary, lineLimit: 1)
                                    KnockText("权重 \(row.weightText)", role: .caption, color: KnockTokens.textTertiary, lineLimit: 1)
                                        .monospacedDigit()
                                    Spacer(minLength: 0)
                                    KnockText(row.dateText, role: .caption, color: KnockTokens.textTertiary, lineLimit: 1)
                                        .monospacedDigit()
                                }
                                if row.hasSession {
                                    NavigationLink(value: routeForEvidenceSession(row)) {
                                        HStack(spacing: 4) {
                                            Image(systemName: "arrow.up.forward.app")
                                                .font(.system(size: 10, weight: .heavy))
                                            KnockText("查看对应会话", role: .captionStrong, color: KnockTokens.textSecondary, lineLimit: 1)
                                        }
                                        .contentShape(Rectangle())
                                    }
                                    .buttonStyle(.plain)
                                    .accessibilityLabel("查看证据对应会话")
                                }
                            }
                            if row.id != rows.last?.id {
                                KnockDivider()
                            }
                        }
                    }
                    .padding(KnockTokens.spacingS)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .fill(KnockTokens.rowIconWell.opacity(0.5))
                    )
                }
            }
        }
    }

    // MARK: - 状态卡

    private var emptyCard: some View {
        KnockEmptyState(
            title: "暂无印象数据",
            message: "多聊几次后，代理人会逐渐形成对你的近期了解和长期印象"
        )
    }

    /// 分页底部：出现即自动加载下一页，也可点击重试。
    private var loadMorePanel: some View {
        Button {
            Task { await loadMore() }
        } label: {
            HStack(spacing: KnockTokens.spacingXS) {
                if isLoadingMore {
                    ProgressView()
                        .controlSize(.small)
                } else {
                    Image(systemName: "arrow.down.circle")
                        .font(.system(size: 12, weight: .heavy))
                        .accessibilityHidden(true)
                }
                KnockText(
                    isLoadingMore ? "正在加载更多…" : "加载更多",
                    role: .captionStrong,
                    color: KnockTokens.textSecondary,
                    lineLimit: 1
                )
            }
            .frame(maxWidth: .infinity, minHeight: 40)
            .background(Capsule(style: .continuous).fill(KnockTokens.rowIconWell))
            .overlay {
                Capsule(style: .continuous)
                    .strokeBorder(KnockTokens.strokeSecondary, lineWidth: KnockTokens.strokeWidth)
            }
            .contentShape(Capsule(style: .continuous))
        }
        .buttonStyle(.plain)
        .disabled(isLoadingMore)
        .onAppear { Task { await loadMore() } }
        .accessibilityLabel("加载更多印象")
        .accessibilityIdentifier("impressions-load-more")
    }

    private func loadedAllFooter(_ snapshot: ImpressionsSnapshotItem) -> some View {
        KnockText(
            "已加载全部 \(snapshot.total) 条印象",
            role: .caption,
            color: KnockTokens.textTertiary,
            lineLimit: 1
        )
        .frame(maxWidth: .infinity)
        .padding(.top, KnockTokens.spacingS)
        .monospacedDigit()
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
        actionError = nil
        evidenceByEntry = [:]
        evidenceLoading = []
        let result = await environment.bridge.loadImpressions(page: 1, pageSize: Self.pageSize)
        switch result {
        case .success(let item):
            snapshot = item
        case .failure(let error):
            errorText = error.message
        }
        isLoading = false
    }

    /// 加载下一页（累计快照）。层级筛选下若整页无命中，自动续翻（上限 maxAutoPages）。
    private func loadMore() async {
        guard !isLoading, !isLoadingMore, let start = snapshot, start.hasMore else { return }
        isLoadingMore = true
        defer { isLoadingMore = false }
        var rounds = 0
        while rounds < Self.maxAutoPages {
            rounds += 1
            guard let current = snapshot, current.hasMore else { return }
            let result = await environment.bridge.loadImpressions(
                page: current.page + 1,
                pageSize: Self.pageSize
            )
            switch result {
            case .failure(let error):
                actionError = "加载更多失败：\(error.message)"
                return
            case .success(let item):
                snapshot = item
                if !item.hasMore { return }
                if levelFilter.isEmpty { return }
                if !filteredEntries(item.entries).isEmpty { return }
            }
        }
    }

    /// 切换层级筛选后，若已加载页没有命中且还有下一页，继续加载直到出现命中或到底。
    private func fillFilteredPageIfNeeded() async {
        guard !levelFilter.isEmpty, !isLoadingMore, let snapshot else { return }
        guard snapshot.hasMore, filteredEntries(snapshot.entries).isEmpty else { return }
        await loadMore()
    }

    // MARK: - T82 人工裁决 + 证据

    private enum ImpressionAction {
        case reject
        case freeze
        case unfreeze

        var title: String {
            switch self {
            case .reject: return "拒绝"
            case .freeze: return "冻结"
            case .unfreeze: return "解冻"
            }
        }
    }

    /// 冻结 / 解冻 / 拒绝统一入口：成功刷新快照（拒绝后该行消失，冻结保留并显示「已冻结」）。
    private func applyImpressionAction(_ entry: ImpressionEntryItem, action: ImpressionAction) async {
        guard entry.hasImpressionRow, !actionBusy.contains(entry.impressionId) else { return }
        actionBusy.insert(entry.impressionId)
        defer { actionBusy.remove(entry.impressionId) }

        let result: Result<Void, BridgeError>
        switch action {
        case .reject:
            result = await environment.bridge.rejectImpression(id: entry.impressionId)
        case .freeze:
            result = await environment.bridge.freezeImpression(id: entry.impressionId)
        case .unfreeze:
            result = await environment.bridge.unfreezeImpression(id: entry.impressionId)
        }

        switch result {
        case .success:
            actionError = nil
            await reload()
        case .failure(let error):
            actionError = "\(action.title)失败：\(error.message)"
        }
    }

    private func toggleEvidence(_ entry: ImpressionEntryItem) async {
        guard entry.hasImpressionRow, entry.evidenceCount > 0 else { return }
        if evidenceByEntry[entry.impressionId] != nil {
            evidenceByEntry[entry.impressionId] = nil
            return
        }
        guard !evidenceLoading.contains(entry.impressionId) else { return }
        evidenceLoading.insert(entry.impressionId)
        let result = await environment.bridge.loadImpressionEvidence(id: entry.impressionId)
        evidenceLoading.remove(entry.impressionId)
        switch result {
        case .success(let rows):
            evidenceByEntry[entry.impressionId] = rows
        case .failure(let error):
            actionError = "证据读取失败：\(error.message)"
        }
    }

    /// 证据行 → 会话路由：group_ 前缀走群聊页，其余走私聊页。
    private func routeForEvidenceSession(_ row: ImpressionEvidenceItem) -> Route {
        row.isGroupSession
            ? .groupChat(sessionId: row.sessionId)
            : .chat(sessionId: row.sessionId)
    }

    // MARK: - T83 撤销拒绝

    private func reloadRejected() async {
        isLoadingRejected = true
        rejectedError = nil
        let result = await environment.bridge.loadRejectedImpressions()
        switch result {
        case .success(let items):
            rejectedEntries = items
        case .failure(let error):
            rejectedError = error.message
        }
        isLoadingRejected = false
    }

    private func undoReject(_ entry: ImpressionEntryItem) async {
        guard entry.hasImpressionRow, !rejectedBusy.contains(entry.impressionId) else { return }
        rejectedBusy.insert(entry.impressionId)
        defer { rejectedBusy.remove(entry.impressionId) }
        let result = await environment.bridge.undoRejectImpression(id: entry.impressionId)
        switch result {
        case .success:
            rejectedError = nil
            rejectedEntries.removeAll { $0.impressionId == entry.impressionId }
            await reloadRejected()
            await reload()
        case .failure(let error):
            rejectedError = "撤销失败：\(error.message)"
        }
    }

    // MARK: - `--impressions-selftest` UI 链路断言

    private func runUIAutomation() {
        guard let snapshot else {
            ImpressionsAutomation.add("FAIL ui.snapshot.nil")
            ImpressionsAutomation.finish(summary: "UI 断言失败：快照为空")
            return
        }
        ImpressionsAutomation.add(snapshot.entries.isEmpty ? "FAIL ui.list.empty" : "PASS ui.list.nonEmpty total=\(snapshot.total) loaded=\(snapshot.totalCount)")
        ImpressionsAutomation.add(
            snapshot.total == snapshot.longTermCount + snapshot.sustainedCount + snapshot.interactionCount
                ? "PASS ui.counts.consistent total=\(snapshot.total)"
                : "FAIL ui.counts.consistent total=\(snapshot.total) L3=\(snapshot.longTermCount) L2=\(snapshot.sustainedCount) L1=\(snapshot.interactionCount)"
        )
        ImpressionsAutomation.add(
            snapshot.hasMore == (snapshot.totalCount < snapshot.total) && snapshot.page >= 1
                ? "PASS ui.paging page=\(snapshot.page) loaded=\(snapshot.totalCount)/\(snapshot.total) hasMore=\(snapshot.hasMore) rejected=\(snapshot.rejectedCount)"
                : "FAIL ui.paging page=\(snapshot.page) loaded=\(snapshot.totalCount)/\(snapshot.total) hasMore=\(snapshot.hasMore)"
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
        ImpressionsAutomation.add("ui.dataSource=\(snapshot.dataSource) ui.legacy.count=\(legacy) sections=\(sections.map { "\($0.title):\($0.entries.count)" }.joined(separator: ","))")
        if snapshot.usesImpressionTable {
            ImpressionsAutomation.add(
                snapshot.entries.allSatisfy { $0.hasImpressionRow && !$0.confidenceText.isEmpty && !$0.statusLabel.isEmpty && !$0.evidenceText.isEmpty }
                    ? "PASS ui.impressionTable.meta confidence/evidence/status"
                    : "FAIL ui.impressionTable.meta"
            )
        }
        let groups = roleGroups(snapshot.entries)
        let grouped = groups.reduce(0) { $0 + $1.entries.count }
        ImpressionsAutomation.add(
            !groups.isEmpty && grouped == snapshot.entries.count
                ? "PASS ui.role.grouping roles=\(groups.count) entries=\(grouped)"
                : "FAIL ui.role.grouping roles=\(groups.count) entries=\(grouped)/\(snapshot.entries.count)"
        )
        ImpressionsAutomation.finish(summary: "UI 断言完成：角色分组 \(groups.count) 组 / 层级顺序 / 计数一致 / 字段完整 / 旧版兜底 \(legacy) 条；截图页面停在印象列表")
    }
}

/// 印象页的角色分组（纯展示模型：快照条目按 operatorId 聚合，组内保持原排序）。
struct RoleImpressionGroup: Identifiable {
    let roleId: String
    let operatorName: String
    let avatarId: String
    let entries: [ImpressionEntryItem]

    var id: String { roleId.isEmpty ? "name:\(operatorName)" : roleId }
}

// MARK: - T83 编辑正文弹窗

/// 编辑印象正文：只改 `impressions.value`（结构化事实的主体/谓词不动），桥内写一条 edit history。
private struct ImpressionEditSheet: View {
    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.dismiss) private var dismiss

    let entry: ImpressionEntryItem
    var onSaved: () -> Void

    @State private var text: String
    @State private var isSaving = false
    @State private var errorText: String?

    init(entry: ImpressionEntryItem, onSaved: @escaping () -> Void) {
        self.entry = entry
        self.onSaved = onSaved
        _text = State(initialValue: entry.rawValue.isEmpty ? entry.content : entry.rawValue)
    }

    private var trimmed: String {
        text.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    var body: some View {
        ZStack {
            AmbientBackground(parallax: 0, showsStandee: false)

            VStack(spacing: 0) {
                KnockHeader(title: "编辑印象", showsBack: true, onBack: { dismiss() })

                ScrollView {
                    VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                        KnockPanel {
                            VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                                HStack(spacing: KnockTokens.spacingS) {
                                    KnockAvatar(avatarId: entry.avatarId, name: entry.operatorName, size: 36)
                                    VStack(alignment: .leading, spacing: KnockTokens.spacingXXS) {
                                        KnockText(entry.operatorName, role: .rowTitle, lineLimit: 1)
                                        KnockText(
                                            "\(entry.levelLabel) · \(entry.memoryTypeLabel)",
                                            role: .caption,
                                            color: KnockTokens.textTertiary,
                                            lineLimit: 1
                                        )
                                    }
                                    Spacer(minLength: 0)
                                }

                                KnockText("当前展示", role: .captionStrong, color: KnockTokens.textSecondary, lineLimit: 1)
                                KnockText(entry.content, role: .caption, color: KnockTokens.textPrimary, lineSpacing: 3)

                                VStack(alignment: .leading, spacing: KnockTokens.spacingXS) {
                                    KnockText("正文", role: .captionStrong, color: KnockTokens.textSecondary, lineLimit: 1)
                                    TextField("印象正文", text: $text, axis: .vertical)
                                        .lineLimit(3...8)
                                        .textFieldStyle(KnockTextFieldStyle())
                                    KnockText(
                                        "只修改这条印象的表述，不改证据数、置信度与状态；保存后写入纠正记录。",
                                        role: .caption,
                                        color: KnockTokens.textTertiary,
                                        lineSpacing: 2
                                    )
                                }

                                if let errorText {
                                    KnockText(errorText, role: .caption, color: KnockTokens.danger, lineSpacing: 2)
                                }

                                Button {
                                    Task { await save() }
                                } label: {
                                    Text(isSaving ? "保存中…" : "保存")
                                }
                                .buttonStyle(KnockPrimaryButtonStyle())
                                .disabled(isSaving || trimmed.isEmpty || trimmed == entry.rawValue)
                                .accessibilityIdentifier("impression-edit-save")
                            }
                        }
                    }
                    .padding(.horizontal, KnockTokens.screenPadding)
                    .padding(.top, KnockTokens.spacingS)
                    .padding(.bottom, KnockTokens.spacingXL)
                }
                .scrollIndicators(.hidden)
                .knockScrollEdgeHidden()
            }
        }
        .knockHideSystemChrome()
    }

    private func save() async {
        guard !isSaving else { return }
        guard !trimmed.isEmpty else {
            errorText = "印象正文不能为空"
            return
        }
        if trimmed == entry.rawValue {
            dismiss()
            return
        }
        isSaving = true
        errorText = nil
        let result = await environment.bridge.editImpression(id: entry.impressionId, value: trimmed)
        isSaving = false
        switch result {
        case .success:
            onSaved()
            dismiss()
        case .failure(let error):
            errorText = "保存失败：\(error.message)"
        }
    }
}

// MARK: - T83 撤销拒绝弹层

/// 已拒绝印象管理：正常列表不展示 rejected；这里提供「撤销拒绝」（rejected → active，桥内写 history）。
private struct RejectedImpressionsSheet: View {
    let entries: [ImpressionEntryItem]
    let isLoading: Bool
    let errorText: String?
    let busyIds: Set<Int64>
    let onUndo: (ImpressionEntryItem) -> Void
    let onClose: () -> Void

    var body: some View {
        ZStack {
            AmbientBackground(parallax: 0, showsStandee: false)

            VStack(spacing: 0) {
                KnockHeader(title: "已拒绝的印象", showsBack: true, onBack: onClose)

                ScrollView {
                    VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                        KnockText(
                            "撤销后该印象恢复为生效中，并写入一条纠正记录。",
                            role: .caption,
                            color: KnockTokens.textTertiary,
                            lineSpacing: 3
                        )

                        if let errorText {
                            KnockErrorPanel(title: "读取失败", message: errorText, retryTitle: nil, retry: nil)
                        }

                        if isLoading {
                            KnockLoadingPanel("正在读取已拒绝印象…")
                        } else if entries.isEmpty {
                            KnockEmptyState(
                                title: "没有已拒绝的印象",
                                message: "拒绝过的印象会出现在这里，可随时撤销。"
                            )
                        } else {
                            ForEach(entries) { entry in
                                KnockCapsuleCard {
                                    VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
                                        HStack(spacing: KnockTokens.spacingS) {
                                            KnockAvatar(avatarId: entry.avatarId, name: entry.operatorName, size: 32)
                                            VStack(alignment: .leading, spacing: KnockTokens.spacingXXS) {
                                                KnockText(entry.operatorName, role: .rowTitle, lineLimit: 1)
                                                KnockText(
                                                    "\(entry.levelLabel) · 已拒绝",
                                                    role: .caption,
                                                    color: KnockTokens.textTertiary,
                                                    lineLimit: 1
                                                )
                                            }
                                            Spacer(minLength: 0)
                                        }

                                        KnockText(entry.content, role: .caption, color: KnockTokens.textPrimary, lineSpacing: 3)

                                        Button {
                                            onUndo(entry)
                                        } label: {
                                            Text(busyIds.contains(entry.impressionId) ? "撤销中…" : "撤销拒绝")
                                        }
                                        .buttonStyle(KnockSecondaryButtonStyle(fullWidth: false, height: 36))
                                        .disabled(busyIds.contains(entry.impressionId))
                                        .accessibilityIdentifier("impression-undo-reject-\(entry.impressionId)")
                                    }
                                }
                            }
                        }
                    }
                    .padding(.horizontal, KnockTokens.screenPadding)
                    .padding(.top, KnockTokens.spacingS)
                    .padding(.bottom, KnockTokens.spacingXL)
                }
                .scrollIndicators(.hidden)
                .knockScrollEdgeHidden()
            }
        }
        .knockHideSystemChrome()
    }
}
