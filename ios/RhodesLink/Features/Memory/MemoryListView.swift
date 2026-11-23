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

import SwiftUI

extension Notification.Name {
    /// 记忆被编辑/删除后通知列表刷新（详情页与自动化共用）。
    static let memoryStoreDidChange = Notification.Name("rhodeslink.memory.storeChanged")
}

// MARK: - 记忆列表

struct MemoryListView: View {
    @EnvironmentObject private var environment: AppEnvironment

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

            List {
                headerCard
                    .listRowStyle()
                filterCard
                    .listRowStyle()

                if let actionError {
                    errorBanner(actionError)
                        .listRowStyle()
                }

                if let errorText, memories.isEmpty {
                    EmptyStateView(title: "记忆读取失败", message: errorText, systemImage: "exclamationmark.triangle")
                        .listRowStyle()
                    retryButton
                        .listRowStyle()
                } else if isLoading && memories.isEmpty {
                    ProgressView("读取记忆…")
                        .padding(40)
                        .listRowStyle()
                } else if memories.isEmpty {
                    emptyState
                        .listRowStyle()
                } else {
                    ForEach(memories, id: \.identity) { memory in
                        memoryRow(memory)
                            .listRowStyle()
                            .onAppear {
                                if memory.identity == memories.last?.identity, hasMore, !isLoadingMore {
                                    Task { await loadMore() }
                                }
                            }
                    }
                    if isLoadingMore {
                        ProgressView("加载更多…")
                            .padding(.vertical, 12)
                            .listRowStyle()
                    }
                    if !hasMore {
                        Text("共 \(total) 条记忆")
                            .font(.caption2)
                            .foregroundStyle(.tertiary)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 8)
                            .listRowStyle()
                    }
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .environment(\.defaultMinListRowHeight, 0)
            .scrollIndicators(.hidden)
            .onScrollGeometryChange(for: CGFloat.self) { geometry in
                geometry.contentOffset.y
            } action: { _, newValue in
                scrollOffset = newValue
            }
            .refreshable { await reload() }
        }
        .navigationTitle("记忆管理")
        .task { await reload() }
        .onReceive(NotificationCenter.default.publisher(for: .memoryStoreDidChange)) { _ in
            Task { await reload() }
        }
        .confirmationDialog(
            "删除这条记忆？",
            isPresented: Binding(get: { pendingDelete != nil }, set: { if !$0 { pendingDelete = nil } }),
            titleVisibility: .visible
        ) {
            Button("删除", role: .destructive) {
                if let memory = pendingDelete {
                    pendingDelete = nil
                    Task { await performDelete(memory) }
                }
            }
            Button("取消", role: .cancel) { pendingDelete = nil }
        } message: {
            Text("删除后，这条记忆及由它形成的长期认知会停止被角色使用。此操作不能撤销。")
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

    private var headerCard: some View {
        GlassPanel(style: .clear, cornerRadius: 20) {
            HStack(spacing: 12) {
                Image(systemName: "brain.head.profile.fill")
                    .font(.system(size: 20))
                    .foregroundStyle(GlassTokens.brandOrange)
                VStack(alignment: .leading, spacing: 2) {
                    Text("记忆")
                        .font(.subheadline.weight(.semibold))
                    Text(memoryHeaderSummary)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 8)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
        }
    }

    /// 头部汇总：条数 + 三层说明（产品化文案，不暴露桥/任务号）
    private var memoryHeaderSummary: String {
        hasActiveFilter ? "筛选后 \(memories.count) 条 · 短期 / 日常 / 长期" : "\(total) 条 · 短期 / 日常 / 长期"
    }

    // MARK: 筛选

    private var filterCard: some View {
        VStack(spacing: 10) {
            // 层级分段（全部 / 短期 L1 / 日常 L2 / 长期 L3）
            Picker("层级", selection: $levelFilter) {
                Text("全部").tag("")
                Text("短期").tag("L1")
                Text("日常").tag("L2")
                Text("长期").tag("L3")
            }
            .pickerStyle(.segmented)

            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                TextField("搜索内容 / 说话人", text: $keyword)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.search)
                if !keyword.isEmpty {
                    Button {
                        keyword = ""
                    } label: {
                        Image(systemName: "xmark.circle.fill").foregroundStyle(.tertiary)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 9)
            .background(GlassTokens.bubbleSystem, in: RoundedRectangle(cornerRadius: 12, style: .continuous))

            HStack(spacing: 8) {
                filterMenu(title: sourceTitle, systemImage: "tray.full", options: {
                    Button("全部来源") { sourceFilter = "" }
                    Button("私聊") { sourceFilter = "PRIVATE_CHAT" }
                    Button("群聊") { sourceFilter = "GROUP_CHAT" }
                    Button("动态") { sourceFilter = "MOMENT" }
                    Button("评论") { sourceFilter = "MOMENT_COMMENT" }
                    Button("日记") { sourceFilter = "DIARY" }
                    Button("手动添加") { sourceFilter = "MANUAL_MEMORY" }
                })
                filterMenu(title: statusTitle, systemImage: "line.3.horizontal.decrease", options: {
                    Button("正在使用") { statusFilter = "active" }
                    Button("已归档") { statusFilter = "archived" }
                    Button("已过期") { statusFilter = "expired" }
                    Button("全部状态") { statusFilter = "" }
                })
                Spacer(minLength: 0)
            }
            HStack(spacing: 8) {
                filterMenu(title: timeTitle, systemImage: "clock", options: {
                    Button("全部时间") { timeFilter = "" }
                    Button("近 7 天") { timeFilter = "7d" }
                    Button("近 30 天") { timeFilter = "30d" }
                })
                filterMenu(title: sortTitle, systemImage: "arrow.up.arrow.down", options: {
                    Button("最新优先") { sortOrder = "newest" }
                    Button("最早优先") { sortOrder = "oldest" }
                    Button("重要度优先") { sortOrder = "importance" }
                })
                Spacer(minLength: 0)
            }
        }
        .padding(14)
        .cardSurface(cornerRadius: 18)
    }

    private var sourceTitle: String {
        switch sourceFilter {
        case "PRIVATE_CHAT": return "私聊"
        case "GROUP_CHAT": return "群聊"
        case "MOMENT": return "动态"
        case "MOMENT_COMMENT": return "评论"
        case "DIARY": return "日记"
        case "MANUAL_MEMORY": return "手动添加"
        default: return "全部来源"
        }
    }

    private var statusTitle: String {
        switch statusFilter {
        case "active": return "正在使用"
        case "archived": return "已归档"
        case "expired": return "已过期"
        default: return "全部状态"
        }
    }

    private var timeTitle: String {
        switch timeFilter {
        case "7d": return "近 7 天"
        case "30d": return "近 30 天"
        default: return "全部时间"
        }
    }

    private var sortTitle: String {
        switch sortOrder {
        case "oldest": return "最早优先"
        case "importance": return "重要度"
        default: return "最新优先"
        }
    }

    private func filterMenu<Content: View>(title: String, systemImage: String, @ViewBuilder options: () -> Content) -> some View {
        Menu {
            options()
        } label: {
            HStack(spacing: 4) {
                Image(systemName: systemImage).font(.caption2)
                Text(title).font(.caption.weight(.medium)).lineLimit(1)
                Image(systemName: "chevron.down").font(.system(size: 8, weight: .bold))
            }
            .foregroundStyle(.secondary)
            .padding(.horizontal, 9)
            .padding(.vertical, 6)
            .background(GlassTokens.bubbleSystem, in: Capsule())
        }
    }

    // MARK: 行

    private func memoryRow(_ memory: MemoryRecord) -> some View {
        NavigationLink {
            MemoryDetailView(memoryID: memory.id, sourceTable: memory.sourceTable)
        } label: {
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 6) {
                    levelBadge(memory)
                    Text(memory.typeText)
                        .font(.caption2.weight(.bold))
                        .foregroundStyle(GlassTokens.brandOrange)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(GlassTokens.bubbleSystem, in: Capsule())
                    if memory.isLegacy {
                        Text("旧版")
                            .font(.caption2.weight(.bold))
                            .foregroundStyle(.secondary)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 3)
                            .background(GlassTokens.bubbleSystem, in: Capsule())
                    }
                    Spacer(minLength: 0)
                    Text(memory.createdAtText)
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                    Image(systemName: "chevron.right")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(.tertiary)
                }
                Text(memory.content)
                    .font(.body)
                    .foregroundStyle(.primary)
                    .multilineTextAlignment(.leading)
                    .lineLimit(3)
                    .frame(maxWidth: .infinity, alignment: .leading)
                HStack(spacing: 8) {
                    Text("\(memory.sourceText) · \(memory.ownerText)")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                    Spacer(minLength: 0)
                    Text("重要度 \(memory.importanceText)")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                    Text(memory.privacyText)
                        .font(.caption2.weight(.medium))
                        .foregroundStyle(memory.privacy == "private" ? GlassTokens.brandOrange : .secondary)
                }
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .cardSurface(cornerRadius: 20)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(memory.levelText)记忆，\(memory.typeText)：\(memory.content)")
        .accessibilityHint("打开记忆详情；向左滑动可删除")
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
    }

    private func levelBadge(_ memory: MemoryRecord) -> some View {
        Text(memory.levelBadgeText)
            .font(.caption2.weight(.bold))
            .foregroundStyle(levelColor(memory.level))
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(levelColor(memory.level).opacity(0.14), in: Capsule())
    }

    private func levelColor(_ level: String) -> Color {
        switch level {
        case "L1": return .blue
        case "L2": return .orange
        case "L3": return GlassTokens.brandOrange
        default: return .gray
        }
    }

    // MARK: 空态 / 错误态

    private var emptyState: some View {
        EmptyStateView(
            title: hasActiveFilter ? "没有符合条件的记忆" : "还没有记忆",
            message: hasActiveFilter
                ? "试试清空搜索词或切换层级/来源筛选。"
                : "角色的记忆由私聊/群聊提取管线写入 memory_items（M1/L1→L2→L3），也可以手动添加。Memory V2 生成开关关闭时不会产生新记忆。",
            systemImage: "brain.head.profile"
        )
    }

    private var retryButton: some View {
        Button("重试") { Task { await reload() } }
            .buttonStyle(.bordered)
            .padding(.bottom, 8)
    }

    private func errorBanner(_ text: String) -> some View {
        HStack(spacing: 8) {
            Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
            Text(text).font(.footnote).foregroundStyle(.secondary)
            Spacer(minLength: 0)
            Button("关闭") { actionError = nil }
                .font(.caption)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(cornerRadius: 14)
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

            ScrollView {
                VStack(spacing: 14) {
                    if isLoading {
                        ProgressView("读取记忆…").padding(40)
                    } else if let detail {
                        contentCard(detail)
                        anchorsCard(detail)
                        privacyCard(detail)
                        dangerCard(detail)
                    } else {
                        EmptyStateView(
                            title: "记忆不存在",
                            message: loadError ?? "shared DB 中未找到记忆 \(memoryID)。可能已被删除。",
                            systemImage: "brain"
                        )
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .padding(.bottom, 24)
            }
            .scrollIndicators(.hidden)
        }
        .navigationTitle("记忆详情")
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
        .onReceive(NotificationCenter.default.publisher(for: .memoryStoreDidChange)) { _ in
            // 自动化/其他页面删除后，详情页同步刷新（若已不存在则显示空态）
            Task { await load() }
        }
        .confirmationDialog("删除这条记忆？", isPresented: $showDeleteConfirm, titleVisibility: .visible) {
            Button("删除", role: .destructive) {
                Task { await deleteCurrent() }
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text("删除后，这条记忆及由它形成的长期认知会停止被角色使用（派生认知归档 + 向量清理）。此操作不能撤销。")
        }
    }

    // MARK: 内容编辑卡

    private func contentCard(_ detail: MemoryDetail) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                Text(detail.record.levelBadgeText)
                    .font(.caption.weight(.bold))
                    .foregroundStyle(GlassTokens.brandOrange)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(GlassTokens.bubbleSystem, in: Capsule())
                Text(detail.record.typeText)
                    .font(.caption.weight(.bold))
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(GlassTokens.bubbleSystem, in: Capsule())
                Spacer()
                Text("ID \(detail.record.identity)")
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }

            HStack(spacing: 8) {
                Text(detail.record.ownerText)
                Text("·")
                Text(detail.record.sourceText)
                Text("·")
                Text(detail.record.createdAtText)
            }
            .font(.caption)
            .foregroundStyle(.secondary)

            if detail.canEditContent {
                TextEditor(text: $editText)
                    .font(.body)
                    .scrollContentBackground(.hidden)
                    .frame(minHeight: 140)
                    .padding(8)
                    .background(GlassTokens.bubbleSystem, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .accessibilityIdentifier("memory-detail-editor")
                Text("\(editText.count) / 500 字")
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            } else {
                Text(detail.record.content)
                    .font(.body)
                    .fixedSize(horizontal: false, vertical: true)
                Text("旧版长期印象（legacy memories 表）只读，仅支持删除。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            HStack(spacing: 10) {
                Text("重要度")
                    .font(.caption.weight(.semibold))
                if detail.canEditImportance {
                    Slider(value: $editImportance, in: 0...100, step: 1)
                        .accessibilityIdentifier("memory-detail-importance")
                    Text("\(Int(editImportance))")
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(GlassTokens.brandOrange)
                        .frame(width: 30, alignment: .trailing)
                } else {
                    Text(detail.record.importanceText)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Spacer()
                }
            }

            if let message {
                Text(message)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            if detail.canEditContent {
                Button {
                    Task { await save() }
                } label: {
                    HStack {
                        if isSaving { ProgressView().controlSize(.small) }
                        Text(isSaving ? "保存中…" : "保存")
                            .font(.subheadline.weight(.semibold))
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
                }
                .buttonStyle(.borderedProminent)
                .tint(GlassTokens.brandOrange)
                .disabled(isSaving || editText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                .accessibilityIdentifier("memory-detail-save")
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(cornerRadius: 22)
    }

    // MARK: 锚点卡

    private func anchorsCard(_ detail: MemoryDetail) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("锚点")
                    .font(.subheadline.weight(.semibold))
                Spacer()
                Text("\(detail.sourceAnchors.count) 个来源锚点\(detail.policyAnchors.isEmpty ? "" : " · \(detail.policyAnchors.count) 个六类锚点")")
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }
            ForEach(detail.sourceAnchors) { anchor in
                HStack(alignment: .top, spacing: 8) {
                    Text(anchor.type)
                        .font(.caption2.weight(.bold))
                        .foregroundStyle(.secondary)
                        .frame(width: 64, alignment: .leading)
                    Text(anchor.content)
                        .font(.caption)
                        .foregroundStyle(.primary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(.vertical, 3)
            }
            if !detail.policyAnchors.isEmpty {
                Divider()
                Text("旧版六类锚点（memory_anchors）")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                ForEach(detail.policyAnchors) { anchor in
                    HStack(alignment: .top, spacing: 8) {
                        Text(anchor.typeText)
                            .font(.caption2.weight(.bold))
                            .foregroundStyle(GlassTokens.brandOrange)
                            .frame(width: 44, alignment: .leading)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(anchor.content)
                                .font(.caption)
                                .frame(maxWidth: .infinity, alignment: .leading)
                            if !anchor.knownFrom.isEmpty {
                                Text(anchor.knownFrom)
                                    .font(.caption2)
                                    .foregroundStyle(.tertiary)
                            }
                        }
                        Text(anchor.importanceText + (anchor.isPrivate ? " · 私密" : ""))
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 3)
                }
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(cornerRadius: 18)
    }

    // MARK: 隐私说明卡

    private func privacyCard(_ detail: MemoryDetail) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Image(systemName: "lock.shield")
                    .foregroundStyle(GlassTokens.brandOrange)
                Text("隐私说明")
                    .font(.subheadline.weight(.semibold))
                Spacer()
                Text("当前：\(detail.record.privacyText)")
                    .font(.caption2.weight(.bold))
                    .foregroundStyle(detail.record.privacy == "private" ? GlassTokens.brandOrange : .secondary)
            }
            ForEach(detail.privacyLines, id: \.self) { line in
                Text("• \(line)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if sensitiveHit {
                Text("本条内容命中敏感词 → MemoryPrivacyPolicy 判定为私密来源（private）。")
                    .font(.caption2)
                    .foregroundStyle(GlassTokens.brandOrange)
            }
            Divider()
            Toggle(isOn: Binding(
                get: { privacy.distinguishPrivateMemory },
                set: { newValue in
                    environment.bridge.setMemoryPrivacyFlag(key: "distinguish_private_memory", value: newValue)
                    privacy = environment.bridge.memoryPrivacySettings()
                    SelfTestLog.add("memory mgmt privacy distinguish_private_memory=\(newValue)")
                }
            )) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("区分私密记忆")
                        .font(.caption.weight(.semibold))
                    Text("键 distinguish_private_memory：关闭后公开/私密锚点不再区分来源可见性。")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
            Text("敏感词（\(privacy.sensitiveWords.count) 条）：" + privacy.sensitiveWords.joined(separator: "、"))
                .font(.caption2)
                .foregroundStyle(.tertiary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(cornerRadius: 18)
    }

    // MARK: 危险操作卡

    private func dangerCard(_ detail: MemoryDetail) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Button(role: .destructive) {
                showDeleteConfirm = true
            } label: {
                Label("删除这条记忆", systemImage: "trash")
                    .font(.subheadline.weight(.semibold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
            }
            .buttonStyle(.bordered)
            .tint(.red)
            .disabled(!detail.canDelete)
            .accessibilityIdentifier("memory-detail-delete")
            if !detail.canDelete {
                Text("旧版短期/日常记录不支持逐条删除。")
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(cornerRadius: 18)
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


// MARK: - 列表行样式

private extension View {
    /// List 行：去分隔线/底色/默认内边距，保持玻璃卡片外观。
    func listRowStyle() -> some View {
        listRowSeparator(.hidden)
            .listRowBackground(Color.clear)
            .listRowInsets(EdgeInsets(top: 5, leading: 16, bottom: 5, trailing: 16))
    }
}
