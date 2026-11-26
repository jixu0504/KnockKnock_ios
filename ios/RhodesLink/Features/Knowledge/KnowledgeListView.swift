//
//  KnowledgeListView.swift
//  知识库列表（T48）：真实 knowledge_bases + knowledge_base_chunks。
//
//  - 行内展示：名称 / 切块数（已索引比例）/ 索引状态徽标 / 更新时间
//  - 新建：粘贴文本 / 文件导入（NewKnowledgeView）
//  - 左滑：删除（二次确认）
//  - 点击：进入 KnowledgeDetailView（切块预览 / 索引 / 召回测试 / 关联干员 / 删除）
//  - `--knowledge-selftest`：进入页面后自动跑 KnowledgeAutomation 全链路自检，
//    结果卡片展示；随后自动打开演示知识库详情页（供 simctl 截图证据）。
//
//  敲敲视觉（P3）：隐藏系统导航栏 → `KnockHeader`（返回 + 黄色「+」胶囊）；
//  列表 = 胶囊行（圆形书籍图标 + 名称 + 切块摘要 + 索引状态角标）；空态 = E M P T Y；
//  删除/失败确认走自绘 `KnockDialog`（避开 iOS 26 系统弹窗玻璃背景）。
//

import SwiftUI

struct KnowledgeListView: View {
    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.dismiss) private var dismiss

    @State private var books: [KnowledgeBookItem] = []
    @State private var isLoading = false
    @State private var errorText: String?
    @State private var showCreate = false
    @State private var deleteTarget: KnowledgeBookItem?
    @State private var isDeleting = false
    @State private var actionError: String?
    @State private var selftestReport: KnowledgeSelfTestReport?
    @State private var selftestRunning = false
    @State private var autoOpenBookId: String?
    @State private var loadedOnce = false
    /// 行点击 → 详情（Button + navigationDestination，避免 List 内 NavigationLink 的系统箭头）
    @State private var selectedBook: KnowledgeBookItem?

    private var selftestEnabled: Bool { KnowledgeAutomation.isEnabled }

    var body: some View {
        ZStack {
            AmbientBackground(parallax: 0, showsStandee: false)

            VStack(spacing: 0) {
                KnockHeader(title: "知识库", showsBack: true, onBack: { dismiss() }, trailing: {
                    KnockCapsuleIconButton(
                        systemImage: "plus",
                        variant: .accent,
                        accessibilityLabel: "新建知识库"
                    ) {
                        showCreate = true
                    }
                })

                List {
                    if selftestEnabled {
                        selftestPanel
                            .knockListRow()
                    }

                    if let errorText {
                        KnockErrorPanel(title: "知识库读取失败", message: errorText, retryTitle: nil)
                            .knockListRow()
                    }

                    if isLoading && books.isEmpty {
                        KnockLoadingPanel("读取知识库…")
                            .knockListRow()
                    } else if books.isEmpty {
                        KnockEmptyState(
                            title: "还没有知识库",
                            message: "把设定集、世界观文档或资料粘贴/导入为知识库，索引后聊天（私聊/群聊）会按相关度自动召回引用。",
                            actionTitle: "新建知识库",
                            action: { showCreate = true }
                        )
                        .knockListRow()
                    } else {
                        KnockGroupTitle(title: "共 \(books.count) 本 · 索引后聊天自动召回")
                            .knockListRow(top: 8, bottom: 2)

                        ForEach(books) { book in
                            Button {
                                selectedBook = book
                            } label: {
                                bookRow(book)
                            }
                            .buttonStyle(.plain)
                            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                                Button(role: .destructive) {
                                    deleteTarget = book
                                } label: {
                                    Label("删除", systemImage: "trash")
                                }
                            }
                            .knockListRow()
                        }
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
                .environment(\.defaultMinListRowHeight, 0)
                .scrollIndicators(.hidden)
                .knockScrollEdgeHidden()
                .refreshable { await reload() }
            }
        }
        .knockHiddenNavigationBar()
        .task {
            guard !loadedOnce else { return }
            loadedOnce = true
            await reload()
            // 截图/演示钩子（仅启动参数）：`--knock-knowledge-new` 打开新建知识库。
            if ProcessInfo.processInfo.arguments.contains("--knock-knowledge-new") {
                showCreate = true
            }
            await runSelfTestIfNeeded()
        }
        .onAppear {
            guard loadedOnce else { return }
            Task { await reload() }
        }
        .sheet(isPresented: $showCreate) {
            NewKnowledgeView { created in
                books.insert(created, at: 0)
                Task { await reload() }
            }
            .environmentObject(environment)
        }
        .knockConfirmDialog(
            isPresented: Binding(
                get: { deleteTarget != nil },
                set: { if !$0 { deleteTarget = nil } }
            ),
            title: "删除知识库？",
            message: "「\(deleteTarget?.name ?? "")」的切块、代理人关联与已建向量会一并删除，不可恢复。",
            confirmTitle: "删除",
            isDestructive: true
        ) {
            if let target = deleteTarget {
                deleteTarget = nil
                Task { await delete(book: target) }
            }
        }
        .knockNoticeDialog(
            isPresented: Binding(
                get: { actionError != nil },
                set: { if !$0 { actionError = nil } }
            ),
            title: "操作失败",
            message: actionError ?? ""
        )
        .navigationDestination(item: $autoOpenBookId) { id in
            KnowledgeDetailView(
                bookId: id,
                autoRunRecallQuery: KnowledgeAutomation.recallQuery,
                autoOperatorId: "amiya"
            )
        }
        .navigationDestination(item: $selectedBook) { book in
            KnowledgeDetailView(bookId: book.id)
        }
    }

    // MARK: - 行

    private func bookRow(_ book: KnowledgeBookItem) -> some View {
        KnockListRow(
            title: book.name,
            subtitle: bookSubtitle(book),
            height: KnockTokens.rowHeight
        ) {
            KnockRowGlyph(systemImage: "books.vertical.fill")
        } trailing: {
            HStack(spacing: KnockTokens.spacingS) {
                statusBadge(book)
                KnockChevron()
            }
        }
        .accessibilityLabel("知识库 \(book.name)，\(book.indexStatusText)，\(book.chunkCountText)")
    }

    private func bookSubtitle(_ book: KnowledgeBookItem) -> String {
        var parts = [book.chunkCountText]
        if book.assignedOperatorCount > 0 {
            parts.append("\(book.assignedOperatorCount) 位代理人")
        }
        if !book.updatedAtText.isEmpty {
            parts.append(book.updatedAtText)
        }
        return parts.joined(separator: " · ")
    }

    private func statusBadge(_ book: KnowledgeBookItem) -> some View {
        let variant: KnockBadgeVariant
        if book.hasFailures {
            variant = .danger
        } else if book.isIndexing {
            variant = .accent
        } else {
            variant = .neutral
        }
        return KnockBadge(text: book.indexStatusText, variant: variant)
    }

    // MARK: - 自检卡片

    private var selftestPanel: some View {
        KnockPanel(padding: KnockTokens.spacingM) {
            VStack(alignment: .leading, spacing: KnockTokens.spacingXS) {
                HStack(spacing: KnockTokens.spacingS) {
                    if selftestRunning {
                        ProgressView()
                            .tint(KnockTokens.textSecondary)
                    } else {
                        Image(systemName: (selftestReport?.passed ?? false) ? "checkmark.seal.fill" : "exclamationmark.triangle.fill")
                            .font(.system(size: 12, weight: .heavy))
                            .foregroundStyle((selftestReport?.passed ?? false) ? KnockTokens.textPrimary : KnockTokens.textSecondary)
                            .accessibilityHidden(true)
                    }
                    KnockText(selftestRunning ? "自检运行中…" : "T48 知识库自检", role: .captionStrong, lineLimit: 1)
                    Spacer(minLength: 0)
                    KnockBadge(
                        text: selftestReport?.passed == true ? "PASS" : (selftestReport == nil ? "…" : "FAIL"),
                        variant: selftestReport?.passed == true ? .neutral : .danger
                    )
                }
                if let report = selftestReport {
                    KnockText(report.summary, role: .caption, color: KnockTokens.textSecondary, lineSpacing: 2)
                    ForEach(Array(report.lines.suffix(10).enumerated()), id: \.offset) { _, line in
                        Text(line)
                            .font(.system(size: 9, weight: .medium, design: .monospaced))
                            .foregroundStyle(KnockTokens.textSecondary)
                            .lineLimit(1)
                    }
                    KnockText("完整日志：Documents/knowledge-selftest.log", role: .caption, color: KnockTokens.textTertiary)
                }
            }
        }
    }

    // MARK: - 数据

    private func reload() async {
        isLoading = true
        defer { isLoading = false }
        switch await environment.bridge.loadKnowledgeBases() {
        case .success(let items):
            books = items
            errorText = nil
        case .failure(let error):
            errorText = error.message
        }
    }

    private func delete(book: KnowledgeBookItem) async {
        guard !isDeleting else { return }
        isDeleting = true
        defer { isDeleting = false }
        if let error = await environment.bridge.deleteKnowledge(id: book.id) {
            actionError = error
        }
        await reload()
    }

    private func runSelfTestIfNeeded() async {
        guard selftestEnabled, selftestReport == nil, !selftestRunning else { return }
        selftestRunning = true
        let report = await KnowledgeAutomation.runIfNeeded(bridge: environment.bridge)
        selftestRunning = false
        selftestReport = report
        await reload()
        if let demoBookId = report?.demoBookId, !demoBookId.isEmpty {
            try? await Task.sleep(nanoseconds: 8_000_000_000)
            autoOpenBookId = demoBookId
        }
    }
}

// MARK: - List 行样式（去分隔线/底色，行内自带胶囊卡片）

private extension View {
    /// List 行：去掉系统分隔线/底色/默认内边距，保留胶囊行自身视觉。
    func knockListRow(top: CGFloat = 4, bottom: CGFloat = 4) -> some View {
        listRowSeparator(.hidden)
            .listRowBackground(Color.clear)
            .listRowInsets(EdgeInsets(
                top: top,
                leading: KnockTokens.screenPadding,
                bottom: bottom,
                trailing: KnockTokens.screenPadding
            ))
    }
}