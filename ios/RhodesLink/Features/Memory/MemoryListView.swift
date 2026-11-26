//
//  MemoryListView.swift
//  记忆管理（T47，Phase 5 P1）：`MemoryBridge` 记忆管理面的列表 + 详情。
//
//  列表：层级分段（全部/短期/日常/长期）+ 搜索框 + 来源/状态/时间/排序筛选；
//        行内显示内容摘要/层级标签/类型标签/来源/时间/重要度；滑动删除（二次确认）；
//        点击进入详情；分页加载（30/页）；空态/错误态。
//  详情：内容编辑（TextEditor + 保存）、重要度调整、锚点列表、隐私说明区、
//        删除按钮（二次确认）。
//
//  数据：真实 SQLite（`memory_items` V2 + 旧版 `memories` 只读行），经
//  `AppGraphClient.loadMemoryPage/loadMemoryDetail`；写操作经 MemoryBridge 事务原语
//  （删除传播/派生失效/向量清理复用 shared 仓库语义）。
//
//  敲敲视觉（P3）：隐藏系统导航栏 → `KnockHeader`（返回 + 标题）；
//  筛选 = 黄选中胶囊分段栏 + 胶囊搜索条 + 下拉胶囊；记忆行 = 胶囊卡片；
//  空态 = E M P T Y 宽字距 pill；删除确认 = 自绘 `KnockDialog`；
//  详情 = 面板（编辑区 / 锚点 / 隐私 / 危险操作）+ 黄主按钮。
//

import SwiftUI

extension Notification.Name {
    /// 记忆被编辑/删除后通知列表刷新（详情页与自动化共用）。
    static let memoryStoreDidChange = Notification.Name("rhodeslink.memory.storeChanged")
}

// MARK: - 记忆列表

struct MemoryListView: View {
    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.dismiss) private var dismiss

    @State private var memories: [MemoryRecord] = []
    @State private var total = 0
    @State private var hasMore = false
    @State private var isLoading = false
    @State private var isLoadingMore = false
    @State private var errorText: String?
    @State private var scrollOffset: CGFloat = 0

    // 筛选状态
    @State private var levelFilter: String = ""          // "" / L1 / L2 / L3
    @State private var sourceFilter: String = ""         // "" / PRIVATE_CHAT / ...
    @State private var statusFilter: String = "active"   // active / archived / expired / ""
    @State private var timeFilter: String = ""           // "" / 7d / 30d
    @State private var sortOrder: String = "newest"      // newest / oldest / importance
    @State private var keyword: String = ""

    @State private var pendingDelete: MemoryRecord?
    @State private var actionError: String?
    /// 行点击 → 详情（用 Button + navigationDestination 替代 List 内 NavigationLink 的系统箭头）
    @State private var selectedMemory: MemoryRecord?

    private let pageSize = 30

    private var query: MemoryQuery {
        var q = MemoryQuery()
        q.level = levelFilter
        q.sourceKind = sourceFilter
        q.status = statusFilter
        q.sort = sortOrder
        q.keyword = keyword.trimmingCharacters(in: .whitespacesAndNewlines)
        switch timeFilter {
        case "7d":
            q.fromMillis = Int64(Date().timeIntervalSince1970 * 1000) - 7 * 86_400_000
        case "30d":
            q.fromMillis = Int64(Date().timeIntervalSince1970 * 1000) - 30 * 86_400_000
        default:
            break
        }
        return q
    }

    private var hasActiveFilter: Bool {
        !levelFilter.isEmpty || !sourceFilter.isEmpty || !statusFilter.isEmpty
            || !timeFilter.isEmpty || !keyword.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var body: some View {
        ZStack {
            AmbientBackground(parallax: scrollOffset, showsStandee: false)

            VStack(spacing: 0) {
                KnockHeader(title: "记忆管理", showsBack: true, onBack: { dismiss() })

                List {
                    headerPanel
                        .memoryListRow()
                    filterPanel
                        .memoryListRow()

                    if let actionError {
                        errorBanner(actionError)
                            .memoryListRow()
                    }

                    if let errorText, memories.isEmpty {
                        KnockErrorPanel(title: "记忆读取失败", message: errorText) {
                            Task { await reload() }
                        }
                        .memoryListRow()
                    } else if isLoading && memories.isEmpty {
                        KnockLoadingPanel("读取记忆…")
                            .memoryListRow()
                    } else if memories.isEmpty {
                        emptyPanel
                            .memoryListRow()
                    } else {
                        ForEach(memories, id: \.identity) { memory in
                            Button {
                                selectedMemory = memory
                            } label: {
                                memoryRow(memory)
                            }
                            .buttonStyle(.plain)
                            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                                Button(role: .destructive) {
                                    pendingDelete = memory
                                } label: {
                                    Label("删除", systemImage: "trash")
                                }
                            }
                            .contextMenu {
                                Button(role: .destructive) {
                                    pendingDelete = memory
                                } label: {
                                    Label("删除记忆", systemImage: "trash")
                                }
                            }
                            .memoryListRow()
                            .onAppear {
                                if memory.identity == memories.last?.identity, hasMore, !isLoadingMore {
                                    Task { await loadMore() }
                                }
                            }
                        }
                        if isLoadingMore {
                            KnockLoadingPanel("加载更多…")
                                .memoryListRow()
                        }
                        if !hasMore {
                            KnockText("共 \(total) 条记忆", role: .caption, color: KnockTokens.textTertiary)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, KnockTokens.spacingS)
                                .memoryListRow()
                        }
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
                .environment(\.defaultMinListRowHeight, 0)
                .scrollIndicators(.hidden)
                .knockScrollEdgeHidden()
                .onScrollGeometryChange(for: CGFloat.self) { geometry in
                    geometry.contentOffset.y
                } action: { _, newValue in
                    scrollOffset = newValue
                }
                .refreshable { await reload() }
            }
        }
        .knockHiddenNavigationBar()
        .navigationDestination(item: $selectedMemory) { memory in
            MemoryDetailView(memoryID: memory.id, sourceTable: memory.sourceTable)
        }
        .task { await reload() }
        .onReceive(NotificationCenter.default.publisher(for: .memoryStoreDidChange)) { _ in
            Task { await reload() }
        }
        .knockConfirmDialog(
            isPresented: Binding(
                get: { pendingDelete != nil },
                set: { if !$0 { pendingDelete = nil } }
            ),
            title: "删除这条记忆？",
            message: "删除后，这条记忆及由它形成的长期认知会停止被角色使用。此操作不能撤销。",
            confirmTitle: "删除",
            isDestructive: true
        ) {
            if let memory = pendingDelete {
                pendingDelete = nil
                Task { await performDelete(memory) }
            }
        }
        .onChange(of: levelFilter) { _, _ in Task { await reload() } }
        .onChange(of: sourceFilter) { _, _ in Task { await reload() } }
        .onChange(of: statusFilter) { _, _ in Task { await reload() } }
        .onChange(of: timeFilter) { _, _ in Task { await reload() } }
        .onChange(of: sortOrder) { _, _ in Task { await reload() } }
        .onChange(of: keyword) { _, _ in
            searchTask?.cancel()
            searchTask = Task {
                try? await Task.sleep(nanoseconds: 300_000_000)
                guard !Task.isCancelled else { return }
                await reload()
            }
        }
    }

    @State private var searchTask: Task<Void, Never>?

    // MARK: 头部

    private var headerPanel: some View {
        KnockPanel {
            HStack(spacing: KnockTokens.spacingM) {
                KnockRowGlyph(systemImage: "brain.head.profile.fill")
                VStack(alignment: .leading, spacing: KnockTokens.spacingXXS) {
                    KnockText("记忆", role: .rowTitle, lineLimit: 1)
                    KnockText(memoryHeaderSummary, role: .caption, color: KnockTokens.textSecondary, lineLimit: 2)
                }
                Spacer(minLength: KnockTokens.spacingS)
            }
        }
    }

    /// 头部汇总：条数 + 三层说明（产品化文案，不暴露桥/任务号）
    private var memoryHeaderSummary: String {
        hasActiveFilter ? "筛选后 \(memories.count) 条 · 短期 / 日常 / 长期" : "\(total) 条 · 短期 / 日常 / 长期"
    }

    // MARK: 筛选

    private var filterPanel: some View {
        KnockPanel {
            VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                KnockSegmentedBar(
                    items: [
                        KnockSegmentItem(value: "", title: "全部"),
                        KnockSegmentItem(value: "L1", title: "短期"),
                        KnockSegmentItem(value: "L2", title: "日常"),
                        KnockSegmentItem(value: "L3", title: "长期"),
                    ],
                    selection: $levelFilter,
                    height: KnockTokens.segmentedSmallHeight,
                    showsIcons: false
                )

                HStack(spacing: KnockTokens.spacingS) {
                    KnockSearchBar(placeholder: "搜索内容 / 说话人", text: $keyword)
                    if !keyword.isEmpty {
                        KnockCloseButton(label: "清空搜索") {
                            keyword = ""
                        }
                    }
                }

                KnockFlowLayout(spacing: KnockTokens.spacingS) {
                    KnockDropdown(
                        items: [
                            KnockSegmentItem(value: "", title: "全部来源"),
                            KnockSegmentItem(value: "PRIVATE_CHAT", title: "私聊"),
                            KnockSegmentItem(value: "GROUP_CHAT", title: "群聊"),
                            KnockSegmentItem(value: "MOMENT", title: "动态"),
                            KnockSegmentItem(value: "MOMENT_COMMENT", title: "评论"),
                            KnockSegmentItem(value: "DIARY", title: "日记"),
                            KnockSegmentItem(value: "MANUAL_MEMORY", title: "手动添加"),
                        ],
                        selection: $sourceFilter
                    )
                    KnockDropdown(
                        items: [
                            KnockSegmentItem(value: "active", title: "正在使用"),
                            KnockSegmentItem(value: "archived", title: "已归档"),
                            KnockSegmentItem(value: "expired", title: "已过期"),
                            KnockSegmentItem(value: "", title: "全部状态"),
                        ],
                        selection: $statusFilter
                    )
                    KnockDropdown(
                        items: [
                            KnockSegmentItem(value: "", title: "全部时间"),
                            KnockSegmentItem(value: "7d", title: "近 7 天"),
                            KnockSegmentItem(value: "30d", title: "近 30 天"),
                        ],
                        selection: $timeFilter
                    )
                    KnockDropdown(
                        items: [
                            KnockSegmentItem(value: "newest", title: "最新优先"),
                            KnockSegmentItem(value: "oldest", title: "最早优先"),
                            KnockSegmentItem(value: "importance", title: "重要度优先"),
                        ],
                        selection: $sortOrder
                    )
                }
            }
        }
    }

    // MARK: 行

    private func memoryRow(_ memory: MemoryRecord) -> some View {
        KnockCapsuleCard {
            VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
                HStack(spacing: KnockTokens.spacingXS) {
                    KnockBadge(text: memory.levelBadgeText, variant: .neutral)
                    KnockBadge(text: memory.typeText, variant: .neutral)
                    Spacer(minLength: 0)
                    KnockText(memory.createdAtText, role: .caption, color: KnockTokens.textTertiary, lineLimit: 1)
                    KnockChevron()
                }
                KnockText(memory.content, role: .caption, color: KnockTokens.textPrimary, lineSpacing: 3, lineLimit: 3)
                    .font(.system(size: 15, weight: .medium))
                HStack(spacing: KnockTokens.spacingS) {
                    KnockText("\(memory.sourceText) · \(memory.ownerText)", role: .caption, color: KnockTokens.textSecondary, lineLimit: 1)
                    Spacer(minLength: KnockTokens.spacingXS)
                    KnockText("重要度 \(memory.importanceText)", role: .caption, color: KnockTokens.textSecondary, lineLimit: 1)
                    KnockBadge(text: memory.privacyText, variant: .neutral)
                }
            }
        }
        .accessibilityLabel("\(memory.levelText)记忆，\(memory.typeText)：\(memory.content)")
        .accessibilityHint("打开记忆详情；向左滑动可删除")
    }

    // MARK: 空态 / 错误态

    private var emptyPanel: some View {
        KnockEmptyState(
            title: hasActiveFilter ? "没有符合条件的记忆" : "还没有记忆",
            message: hasActiveFilter
                ? "试试清空搜索词或切换层级/来源筛选。"
                : "角色的记忆由私聊/群聊提取管线写入 memory_items（M1/L1→L2→L3），也可以手动添加。Memory V2 生成开关关闭时不会产生新记忆。"
        )
    }

    private func errorBanner(_ text: String) -> some View {
        KnockPanel(padding: KnockTokens.spacingM) {
            HStack(alignment: .top, spacing: KnockTokens.spacingS) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.system(size: 12, weight: .heavy))
                    .foregroundStyle(KnockTokens.dangerBright)
                    .accessibilityHidden(true)
                KnockText(text, role: .caption, color: KnockTokens.textSecondary, lineSpacing: 2)
                Spacer(minLength: 0)
                Button {
                    actionError = nil
                } label: {
                    KnockText("关闭", role: .captionStrong, lineLimit: 1)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("关闭错误提示")
            }
        }
    }

    // MARK: 数据

    private func reload() async {
        isLoading = true
        defer { isLoading = false }
        switch await environment.bridge.loadMemoryPage(offset: 0, limit: pageSize, query: query) {
        case .success(let page):
            memories = page.items
            total = page.total
            hasMore = page.hasMore
            errorText = nil
            SelfTestLog.add("memory mgmt list loaded count=\(page.items.count) total=\(page.total) level=\(levelFilter.isEmpty ? "-" : levelFilter) source=\(sourceFilter.isEmpty ? "-" : sourceFilter) keyword=\(keyword) ids=\(page.items.map(\.identity))")
        case .failure(let error):
            errorText = error.message
            SelfTestLog.add("memory mgmt list failed error=\(error.message)")
        }
    }

    private func loadMore() async {
        guard hasMore, !isLoadingMore else { return }
        isLoadingMore = true
        defer { isLoadingMore = false }
        switch await environment.bridge.loadMemoryPage(offset: memories.count, limit: pageSize, query: query) {
        case .success(let page):
            let known = Set(memories.map(\.identity))
            memories.append(contentsOf: page.items.filter { !known.contains($0.identity) })
            hasMore = page.hasMore
            total = page.total
            SelfTestLog.add("memory mgmt loadMore count=\(page.items.count) total=\(page.total) hasMore=\(page.hasMore)")
        case .failure(let error):
            actionError = error.message
            SelfTestLog.add("memory mgmt loadMore failed error=\(error.message)")
        }
    }

    private func performDelete(_ memory: MemoryRecord) async {
        switch await environment.bridge.deleteMemory(id: memory.id, sourceTable: memory.sourceTable) {
        case .success(let result):
            actionError = result.ok ? nil : result.message
            SelfTestLog.add("memory mgmt delete id=\(memory.id) table=\(memory.sourceTable) ok=\(result.ok) message=\(result.message)")
            NotificationCenter.default.post(name: .memoryStoreDidChange, object: nil)
            await reload()
        case .failure(let error):
            actionError = error.message
            SelfTestLog.add("memory mgmt delete failed id=\(memory.id) error=\(error.message)")
        }
        await environment.refreshCounts()
    }
}

// MARK: - 记忆详情

struct MemoryDetailView: View {
    @EnvironmentObject private var environment: AppEnvironment
    @EnvironmentObject private var router: AppRouter
    @Environment(\.dismiss) private var dismiss

    let memoryID: Int64
    /// "v2" / "legacy" / ""（深链入口自动识别）
    let sourceTable: String

    @State private var detail: MemoryDetail?
    @State private var isLoading = true
    @State private var loadError: String?
    @State private var editText: String = ""
    @State private var editImportance: Double = 0
    @State private var isSaving = false
    @State private var message: String?
    @State private var showDeleteConfirm = false
    @State private var privacy = AppGraphClient.shared.memoryPrivacySettings()
    @State private var sensitiveHit = false

    init(memoryID: Int64, sourceTable: String = "") {
        self.memoryID = memoryID
        self.sourceTable = sourceTable
    }

    var body: some View {
        ZStack {
            AmbientBackground(parallax: 0, showsStandee: false)

            VStack(spacing: 0) {
                KnockHeader(
                    title: "记忆详情",
                    subtitle: "ID \(memoryID)",
                    showsBack: true,
                    onBack: { dismiss() }
                )

                ScrollView {
                    VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                        if isLoading {
                            KnockLoadingPanel("读取记忆…")
                        } else if let detail {
                            contentPanel(detail)
                            anchorsPanel(detail)
                            privacyPanel(detail)
                            dangerPanel(detail)
                        } else {
                            KnockErrorPanel(
                                title: "记忆不存在",
                                message: loadError ?? "shared DB 中未找到记忆 \(memoryID)。可能已被删除。"
                            )
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
        .knockHiddenNavigationBar()
        .task { await load() }
        .onReceive(NotificationCenter.default.publisher(for: .memoryStoreDidChange)) { _ in
            // 自动化/其他页面删除后，详情页同步刷新（若已不存在则显示空态）
            Task { await load() }
        }
        .knockConfirmDialog(
            isPresented: $showDeleteConfirm,
            title: "删除这条记忆？",
            message: "删除后，这条记忆及由它形成的长期认知会停止被角色使用（派生认知归档 + 向量清理）。此操作不能撤销。",
            confirmTitle: "删除",
            isDestructive: true
        ) {
            Task { await deleteCurrent() }
        }
    }

    // MARK: 内容编辑卡

    private func contentPanel(_ detail: MemoryDetail) -> some View {
        KnockPanel {
            VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                HStack(spacing: KnockTokens.spacingXS) {
                    KnockBadge(text: detail.record.levelBadgeText, variant: .neutral)
                    KnockBadge(text: detail.record.typeText, variant: .neutral)
                    Spacer(minLength: 0)
                    KnockText("ID \(detail.record.identity)", role: .caption, color: KnockTokens.textTertiary, lineLimit: 1)
                }

                KnockText(
                    "\(detail.record.ownerText) · \(detail.record.sourceText) · \(detail.record.createdAtText)",
                    role: .caption,
                    color: KnockTokens.textSecondary,
                    lineSpacing: 2
                )

                if detail.canEditContent {
                    TextEditor(text: $editText)
                        .knockFont(.body)
                        .scrollContentBackground(.hidden)
                        .foregroundStyle(KnockTokens.textPrimary)
                        .tint(KnockTokens.electric)
                        .frame(minHeight: 140)
                        .padding(KnockTokens.spacingS)
                        .background(
                            RoundedRectangle(cornerRadius: 14, style: .continuous)
                                .fill(KnockTokens.row)
                        )
                        .overlay {
                            RoundedRectangle(cornerRadius: 14, style: .continuous)
                                .strokeBorder(KnockTokens.strokeSecondary, lineWidth: KnockTokens.strokeWidth)
                        }
                        .accessibilityIdentifier("memory-detail-editor")
                    KnockText("\(editText.count) / 500 字", role: .caption, color: KnockTokens.textTertiary)
                } else {
                    KnockText(detail.record.content, role: .body, lineSpacing: 4)
                        .fixedSize(horizontal: false, vertical: true)
                    KnockText(
                        "旧版长期印象（legacy memories 表）只读，仅支持删除。",
                        role: .caption,
                        color: KnockTokens.textSecondary,
                        lineSpacing: 2
                    )
                }

                HStack(spacing: KnockTokens.spacingM) {
                    KnockText("重要度", role: .captionStrong, color: KnockTokens.textSecondary, lineLimit: 1)
                    if detail.canEditImportance {
                        Slider(value: $editImportance, in: 0...100, step: 1)
                            .tint(KnockTokens.electric)
                            .accessibilityIdentifier("memory-detail-importance")
                        KnockText("\(Int(editImportance))", role: .captionStrong, lineLimit: 1)
                            .monospacedDigit()
                            .frame(width: 30, alignment: .trailing)
                    } else {
                        KnockText(detail.record.importanceText, role: .caption, color: KnockTokens.textSecondary)
                        Spacer()
                    }
                }

                if let message {
                    KnockText(message, role: .caption, color: KnockTokens.textSecondary, lineSpacing: 2)
                }

                if detail.canEditContent {
                    Button {
                        Task { await save() }
                    } label: {
                        HStack(spacing: KnockTokens.spacingS) {
                            if isSaving { ProgressView().controlSize(.small).tint(KnockTokens.onElectric) }
                            Text(isSaving ? "保存中…" : "保存")
                        }
                    }
                    .buttonStyle(KnockPrimaryButtonStyle())
                    .disabled(isSaving || editText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    .opacity(isSaving || editText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? 0.35 : 1)
                    .accessibilityIdentifier("memory-detail-save")
                }
            }
        }
    }

    // MARK: 锚点卡

    private func anchorsPanel(_ detail: MemoryDetail) -> some View {
        KnockPanel {
            VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
                HStack(spacing: KnockTokens.spacingS) {
                    KnockText("锚点", role: .sectionTitle, lineLimit: 1)
                    Spacer(minLength: 0)
                    KnockText(
                        "\(detail.sourceAnchors.count) 个来源锚点" +
                        (detail.policyAnchors.isEmpty ? "" : " · \(detail.policyAnchors.count) 个六类锚点"),
                        role: .caption,
                        color: KnockTokens.textTertiary,
                        lineLimit: 1
                    )
                }
                ForEach(detail.sourceAnchors) { anchor in
                    HStack(alignment: .top, spacing: KnockTokens.spacingS) {
                        KnockBadge(text: anchor.type, variant: .neutral)
                            .frame(width: 64, alignment: .leading)
                        KnockText(anchor.content, role: .caption, color: KnockTokens.textPrimary, lineSpacing: 2)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .padding(.vertical, KnockTokens.spacingXXS)
                }
                if !detail.policyAnchors.isEmpty {
                    KnockDivider()
                    KnockText("旧版六类锚点（memory_anchors）", role: .captionStrong, color: KnockTokens.textSecondary)
                    ForEach(detail.policyAnchors) { anchor in
                        HStack(alignment: .top, spacing: KnockTokens.spacingS) {
                            KnockText(anchor.typeText, role: .captionStrong, lineLimit: 1)
                                .frame(width: 44, alignment: .leading)
                            VStack(alignment: .leading, spacing: KnockTokens.spacingXXS) {
                                KnockText(anchor.content, role: .caption, lineSpacing: 2)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                if !anchor.knownFrom.isEmpty {
                                    KnockText(anchor.knownFrom, role: .caption, color: KnockTokens.textTertiary)
                                }
                            }
                            KnockText(
                                anchor.importanceText + (anchor.isPrivate ? " · 私密" : ""),
                                role: .caption,
                                color: KnockTokens.textSecondary,
                                lineLimit: 1
                            )
                        }
                        .padding(.vertical, KnockTokens.spacingXXS)
                    }
                }
            }
        }
    }

    // MARK: 隐私说明卡

    private func privacyPanel(_ detail: MemoryDetail) -> some View {
        KnockPanel {
            VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
                HStack(spacing: KnockTokens.spacingS) {
                    Image(systemName: "lock.shield")
                        .font(.system(size: 13, weight: .heavy))
                        .foregroundStyle(KnockTokens.textPrimary)
                        .accessibilityHidden(true)
                    KnockText("隐私说明", role: .sectionTitle, lineLimit: 1)
                    Spacer(minLength: 0)
                    KnockBadge(text: "当前：\(detail.record.privacyText)", variant: .neutral)
                }
                ForEach(detail.privacyLines, id: \.self) { line in
                    KnockText("• \(line)", role: .caption, color: KnockTokens.textSecondary, lineSpacing: 3)
                }
                if sensitiveHit {
                    KnockText(
                        "本条内容命中敏感词 → MemoryPrivacyPolicy 判定为私密来源（private）。",
                        role: .caption,
                        color: KnockTokens.textPrimary,
                        lineSpacing: 2
                    )
                }
                KnockDivider()
                Toggle(isOn: Binding(
                    get: { privacy.distinguishPrivateMemory },
                    set: { newValue in
                        environment.bridge.setMemoryPrivacyFlag(key: "distinguish_private_memory", value: newValue)
                        privacy = environment.bridge.memoryPrivacySettings()
                        SelfTestLog.add("memory mgmt privacy distinguish_private_memory=\(newValue)")
                    }
                )) {
                    VStack(alignment: .leading, spacing: KnockTokens.spacingXXS) {
                        KnockText("区分私密记忆", role: .captionStrong, lineLimit: 1)
                        KnockText(
                            "键 distinguish_private_memory：关闭后公开/私密锚点不再区分来源可见性。",
                            role: .caption,
                            color: KnockTokens.textSecondary,
                            lineSpacing: 2
                        )
                    }
                }
                .tint(KnockTokens.electric)
                KnockText(
                    "敏感词（\(privacy.sensitiveWords.count) 条）：" + privacy.sensitiveWords.joined(separator: "、"),
                    role: .caption,
                    color: KnockTokens.textTertiary,
                    lineSpacing: 2
                )
            }
        }
    }

    // MARK: 危险操作卡

    private func dangerPanel(_ detail: MemoryDetail) -> some View {
        KnockPanel {
            VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
                Button(role: .destructive) {
                    showDeleteConfirm = true
                } label: {
                    Label("删除这条记忆", systemImage: "trash")
                }
                .buttonStyle(KnockDangerButtonStyle())
                .disabled(!detail.canDelete)
                .opacity(detail.canDelete ? 1 : 0.35)
                .accessibilityIdentifier("memory-detail-delete")
                if !detail.canDelete {
                    KnockText("旧版短期/日常记录不支持逐条删除。", role: .caption, color: KnockTokens.textTertiary)
                }
            }
        }
    }

    // MARK: 数据

    private func load() async {
        isLoading = true
        defer { isLoading = false }

        let resolvedTable: String
        if !sourceTable.isEmpty {
            resolvedTable = sourceTable
        } else {
            // 深链入口（rhodes://memory/{id}）无表信息：先 v2 后 legacy。
            resolvedTable = await resolveTableForDeepLink()
        }

        switch await environment.bridge.loadMemoryDetail(id: memoryID, sourceTable: resolvedTable) {
        case .success(let value):
            detail = value
            editText = value.record.content
            editImportance = Double(value.record.importance)
            sensitiveHit = environment.bridge.isSensitiveMemoryText(value.record.content)
            loadError = nil
            SelfTestLog.add("memory detail loaded id=\(memoryID) table=\(value.record.sourceTable) content=\(value.record.content.prefix(30)) importance=\(value.record.importance) anchors=\(value.sourceAnchors.count)/\(value.policyAnchors.count)")
        case .failure(let error):
            detail = nil
            loadError = error.message
            SelfTestLog.add("memory detail failed id=\(memoryID) table=\(resolvedTable) error=\(error.message)")
        }
        privacy = environment.bridge.memoryPrivacySettings()
    }

    private func resolveTableForDeepLink() async -> String {
        if case .success(let page) = await environment.bridge.loadMemoryPage(offset: 0, limit: 200, query: .all),
           page.items.contains(where: { $0.id == memoryID && $0.sourceTable == "v2" }) {
            return "v2"
        }
        return "legacy"
    }

    private func save() async {
        guard let detail else { return }
        isSaving = true
        defer { isSaving = false }
        let targetImportance = Int(editImportance.rounded())
        switch await environment.bridge.updateMemory(
            id: detail.record.id,
            sourceTable: detail.record.sourceTable,
            content: editText,
            importance: targetImportance
        ) {
        case .success(let result):
            message = result.ok ? result.message : result.message
            SelfTestLog.add("memory detail save id=\(detail.record.id) ok=\(result.ok) contentLen=\(editText.count) importance=\(targetImportance) message=\(result.message)")
            NotificationCenter.default.post(name: .memoryStoreDidChange, object: nil)
            await load()
        case .failure(let error):
            message = "保存失败：\(error.message)"
            SelfTestLog.add("memory detail save failed id=\(detail.record.id) error=\(error.message)")
        }
    }

    private func deleteCurrent() async {
        guard let detail else { return }
        switch await environment.bridge.deleteMemory(id: detail.record.id, sourceTable: detail.record.sourceTable) {
        case .success(let result):
            SelfTestLog.add("memory detail delete id=\(detail.record.id) ok=\(result.ok) message=\(result.message)")
            NotificationCenter.default.post(name: .memoryStoreDidChange, object: nil)
            if result.ok {
                await environment.refreshCounts()
                dismiss()
            } else {
                message = result.message
            }
        case .failure(let error):
            message = "删除失败：\(error.message)"
            SelfTestLog.add("memory detail delete failed id=\(detail.record.id) error=\(error.message)")
        }
    }
}

// MARK: - List 行样式

private extension View {
    /// List 行：去分隔线/底色/默认内边距，保持胶囊卡片外观。
    func memoryListRow() -> some View {
        listRowSeparator(.hidden)
            .listRowBackground(Color.clear)
            .listRowInsets(EdgeInsets(
                top: 5,
                leading: KnockTokens.screenPadding,
                bottom: 5,
                trailing: KnockTokens.screenPadding
            ))
    }
}