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

    private var selftestEnabled: Bool { KnowledgeAutomation.isEnabled }

    var body: some View {
        List {
            if selftestEnabled {
                Section {
                    selftestCard
                } header: {
                    Text("T48 自检")
                }
            }

            if let errorText {
                Section {
                    Text(errorText)
                        .font(.footnote)
                        .foregroundStyle(.red)
                }
            }

            if isLoading && books.isEmpty {
                Section {
                    HStack(spacing: 10) {
                        ProgressView()
                        Text("读取知识库…").foregroundStyle(.secondary)
                    }
                }
            } else if books.isEmpty {
                Section {
                    EmptyStateView(
                        title: "还没有知识库",
                        message: "把设定集、世界观文档或资料粘贴/导入为知识库，索引后聊天（私聊/群聊）会按相关度自动召回引用。",
                        systemImage: "books.vertical",
                        actionTitle: "新建知识库",
                        action: { showCreate = true }
                    )
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
                }
            } else {
                Section {
                    ForEach(books) { book in
                        NavigationLink {
                            KnowledgeDetailView(bookId: book.id)
                        } label: {
                            bookRow(book)
                        }
                        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                            Button(role: .destructive) {
                                deleteTarget = book
                            } label: {
                                Label("删除", systemImage: "trash")
                            }
                        }
                    }
                } header: {
                    Text("共 \(books.count) 本 · 索引后聊天自动召回")
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("知识库")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    showCreate = true
                } label: {
                    Image(systemName: "plus")
                }
                .accessibilityLabel("新建知识库")
            }
        }
        .refreshable { await reload() }
        .task {
            guard !loadedOnce else { return }
            loadedOnce = true
            await reload()
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
        .confirmationDialog(
            "删除知识库「\(deleteTarget?.name ?? "")」？",
            isPresented: Binding(
                get: { deleteTarget != nil },
                set: { if !$0 { deleteTarget = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button("删除（含切块与关联，不可恢复）", role: .destructive) {
                if let target = deleteTarget {
                    deleteTarget = nil
                    Task { await delete(book: target) }
                }
            }
            Button("取消", role: .cancel) { deleteTarget = nil }
        }
        .alert("操作失败", isPresented: Binding(
            get: { actionError != nil },
            set: { if !$0 { actionError = nil } }
        )) {
            Button("知道了", role: .cancel) { actionError = nil }
        } message: {
            Text(actionError ?? "")
        }
        .navigationDestination(item: $autoOpenBookId) { id in
            KnowledgeDetailView(
                bookId: id,
                autoRunRecallQuery: KnowledgeAutomation.recallQuery,
                autoOperatorId: "amiya"
            )
        }
    }

    // MARK: - 行

    private func bookRow(_ book: KnowledgeBookItem) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Text(book.name)
                    .font(.body.weight(.medium))
                    .lineLimit(1)
                Spacer(minLength: 6)
                statusBadge(book)
            }
            HStack(spacing: 10) {
                Label(book.chunkCountText, systemImage: "square.stack.3d.up")
                if book.assignedOperatorCount > 0 {
                    Label("\(book.assignedOperatorCount) 位干员", systemImage: "person.2")
                }
                Spacer(minLength: 0)
                if !book.updatedAtText.isEmpty {
                    Text(book.updatedAtText)
                }
            }
            .font(.caption)
            .foregroundStyle(.secondary)
        }
        .padding(.vertical, 2)
    }

    private func statusBadge(_ book: KnowledgeBookItem) -> some View {
        Text(book.indexStatusText)
            .font(.caption2.weight(.semibold))
            .foregroundStyle(statusColor(book))
            .padding(.horizontal, 7)
            .padding(.vertical, 2)
            .background(statusColor(book).opacity(0.12), in: Capsule())
    }

    private func statusColor(_ book: KnowledgeBookItem) -> Color {
        if book.isIndexing { return .orange }
        if book.isIndexed { return .green }
        if book.hasFailures { return .red }
        return .secondary
    }

    // MARK: - 自检卡片

    private var selftestCard: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                if selftestRunning {
                    ProgressView()
                } else {
                    Image(systemName: (selftestReport?.passed ?? false) ? "checkmark.seal.fill" : "exclamationmark.triangle.fill")
                        .foregroundStyle((selftestReport?.passed ?? false) ? .green : .orange)
                }
                Text(selftestRunning ? "自检运行中…" : "T48 知识库自检")
                    .font(.subheadline.weight(.semibold))
                Spacer(minLength: 0)
                Text(selftestReport?.passed == true ? "PASS" : (selftestReport == nil ? "…" : "FAIL"))
                    .font(.caption2.weight(.bold))
                    .foregroundStyle((selftestReport?.passed ?? false) ? .green : .secondary)
            }
            if let report = selftestReport {
                Text(report.summary)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                ForEach(Array(report.lines.suffix(10).enumerated()), id: \.offset) { _, line in
                    Text(line)
                        .font(.system(size: 9, design: .monospaced))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                Text("完整日志：Documents/knowledge-selftest.log")
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }
        }
        .padding(.vertical, 2)
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
