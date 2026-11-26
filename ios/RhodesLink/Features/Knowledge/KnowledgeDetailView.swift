//
//  KnowledgeDetailView.swift
//  知识库详情（T48）：内容摘要 / 切块预览（前 N 块）/ 索引与重建（进度、失败重试、清除索引）/
//  召回测试（query → 相关块 + 分数，与聊天注入同链路）/ 关联干员（召回前提）/ 重命名 / 删除。
//
//  所有数据来自真实 Shared.framework（knowledge_bases / knowledge_base_chunks /
//  operator_knowledge_bases / vector_memories）。
//
//  敲敲视觉（P3）：隐藏系统导航栏 → `KnockHeader`（返回 + 名称 + 右上操作菜单胶囊）；
//  各分区 = 面板 + 分组标题；切块 / 召回命中 = 胶囊卡片；主操作 = 黄胶囊，危险操作 = 红胶囊；
//  重命名/删除/远程索引确认/失败提示全部走自绘 `KnockDialog`（无系统玻璃弹窗）。
//

import SwiftUI

struct KnowledgeDetailView: View {
    let bookId: String
    /// `--knowledge-selftest` 自动打开详情时传入：加载完成后自动执行召回测试（截图证据）
    var autoRunRecallQuery: String? = nil
    var autoOperatorId: String? = nil

    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.dismiss) private var dismiss

    @State private var book: KnowledgeBookItem?
    @State private var chunks: [KnowledgeChunkItem] = []
    @State private var totalChunks = 0
    @State private var assignedOperatorIds: [String] = []
    @State private var operators: [KnowledgeOperatorItem] = []
    @State private var isLoading = false
    @State private var errorText: String?

    @State private var plan: KnowledgeIndexPlanItem?
    @State private var isIndexing = false
    @State private var progressText = ""
    @State private var indexResultText: String?
    @State private var indexErrorText: String?

    @State private var recallQuery = ""
    @State private var recallOperatorId = ""
    @State private var recallResult: KnowledgeRecallItem?
    @State private var isRecalling = false
    @State private var recallError: String?

    @State private var showRename = false
    @State private var renameText = ""
    @State private var showDeleteConfirm = false
    @State private var showRemoteIndexConfirm = false
    @State private var actionError: String?
    @State private var loadedOnce = false
    @State private var didAutoRecall = false

    var body: some View {
        ZStack {
            AmbientBackground(parallax: 0, showsStandee: false)

            VStack(spacing: 0) {
                KnockHeader(title: book?.name ?? "知识库", showsBack: true, onBack: { dismiss() }, trailing: {
                    knowledgeMenu
                })

                ScrollView {
                    VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                        if let errorText, book == nil {
                            KnockErrorPanel(title: "读取失败", message: errorText) {
                                Task { await reloadAll() }
                            }
                        } else if book == nil {
                            KnockLoadingPanel("读取知识库…")
                        } else {
                            headerPanel
                            indexPanel
                            recallPanel
                            assignmentPanel
                            chunksPanel
                            dangerPanel
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
        .task {
            guard !loadedOnce else { return }
            loadedOnce = true
            await reloadAll()
            await runAutoRecallIfNeeded()
        }
        .knockConfirmDialog(
            isPresented: $showRemoteIndexConfirm,
            title: "确认远程向量化",
            message: "将调用远程嵌入模型，预计 \(plan?.chunkCount ?? 0) 次请求并产生费用。",
            confirmTitle: "继续索引"
        ) {
            Task { await performIndex() }
        }
        .knockConfirmDialog(
            isPresented: $showDeleteConfirm,
            title: "删除知识库？",
            message: "「\(book?.name ?? "")」的切块、干员关联与已建向量会一并删除，不可恢复。",
            confirmTitle: "删除",
            isDestructive: true
        ) {
            Task { await deleteBook() }
        }
        .knockTextPromptDialog(
            isPresented: $showRename,
            title: "重命名知识库",
            placeholder: "知识库名称",
            text: $renameText,
            confirmTitle: "保存"
        ) {
            Task { await rename() }
        }
        .knockNoticeDialog(
            isPresented: Binding(
                get: { actionError != nil },
                set: { if !$0 { actionError = nil } }
            ),
            title: "操作失败",
            message: actionError ?? ""
        )
    }

    private var knowledgeMenu: some View {
        Menu {
            Button {
                renameText = book?.name ?? ""
                showRename = true
            } label: {
                Label("重命名", systemImage: "pencil")
            }
            Button {
                Task { await reloadAll() }
            } label: {
                Label("刷新", systemImage: "arrow.clockwise")
            }
        } label: {
            Image(systemName: "ellipsis")
                .font(.system(size: 13, weight: .heavy))
                .foregroundStyle(KnockTokens.textPrimary)
                .frame(width: 34, height: 26)
                .background(Capsule(style: .continuous).fill(KnockTokens.panel))
                .overlay {
                    Capsule(style: .continuous)
                        .strokeBorder(KnockTokens.stroke, lineWidth: KnockTokens.strokeWidth)
                }
                .frame(minHeight: 44)
                .contentShape(Rectangle())
        }
        .accessibilityLabel("知识库操作")
    }

    // MARK: - 头部

    private var headerPanel: some View {
        KnockPanel {
            VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
                HStack(spacing: KnockTokens.spacingS) {
                    Image(systemName: "books.vertical.fill")
                        .font(.system(size: 15, weight: .heavy))
                        .foregroundStyle(KnockTokens.textPrimary)
                        .accessibilityHidden(true)
                    KnockText(book?.name ?? "", role: .rowTitle, lineLimit: 2)
                    Spacer(minLength: KnockTokens.spacingXS)
                    statusBadge
                }
                HStack(spacing: KnockTokens.spacingM) {
                    label("\(book?.chunkCount ?? 0) 块", systemImage: "square.stack.3d.up")
                    label("\(book?.indexedChunkCount ?? 0) 已索引", systemImage: "checkmark.circle")
                    label("\(book?.assignedOperatorCount ?? assignedOperatorIds.count) 位干员", systemImage: "person.2")
                }
                if let preview = book?.rawContentPreview, !preview.isEmpty {
                    KnockText(
                        preview.replacingOccurrences(of: "\n", with: " "),
                        role: .caption,
                        color: KnockTokens.textSecondary,
                        lineSpacing: 3,
                        lineLimit: 3
                    )
                }
                KnockText(
                    "格式：\(formatText) · 正文 \(book?.rawContentLength ?? 0) 字" +
                    ((book?.updatedAtText.isEmpty == false) ? " · 更新 \(book?.updatedAtText ?? "")" : ""),
                    role: .caption,
                    color: KnockTokens.textTertiary,
                    lineSpacing: 2
                )
            }
        }
    }

    private func label(_ text: String, systemImage: String) -> some View {
        HStack(spacing: 4) {
            Image(systemName: systemImage)
                .font(.system(size: 10, weight: .heavy))
                .foregroundStyle(KnockTokens.textSecondary)
                .accessibilityHidden(true)
            KnockText(text, role: .caption, color: KnockTokens.textSecondary, lineLimit: 1)
        }
    }

    private var statusBadge: some View {
        let variant: KnockBadgeVariant
        if book?.hasFailures == true {
            variant = .danger
        } else if book?.isIndexing == true {
            variant = .accent
        } else {
            variant = .neutral
        }
        return KnockBadge(text: book?.indexStatusText ?? "", variant: variant)
    }

    private var formatText: String {
        let source = book?.sourceFormat ?? ""
        if source.isEmpty { return "txt" }
        return source
    }

    // MARK: - 索引

    private var indexPanel: some View {
        KnockPanel {
            VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
                KnockGroupTitle(title: "索引与重建", systemImage: "bolt.badge.clock")
                KnockText(
                    "状态：\(book?.indexStatusText ?? "-") · \(book?.indexedChunkCount ?? 0)/\(book?.chunkCount ?? 0) 块已索引",
                    role: .caption,
                    color: KnockTokens.textSecondary,
                    lineSpacing: 2
                )
                if let plan {
                    KnockText(
                        plan.requiresUserConfirmation
                            ? "远程向量嵌入：预计 \(plan.chunkCount) 次 API 请求（会产生费用，需确认）"
                            : "本地哈希嵌入：预计 \(plan.chunkCount) 块，离线免费",
                        role: .caption,
                        color: KnockTokens.textTertiary,
                        lineSpacing: 2
                    )
                }
                if isIndexing {
                    ProgressView(value: progressFraction)
                        .tint(KnockTokens.electric)
                    KnockText(progressText.isEmpty ? "索引中…" : progressText, role: .caption, color: KnockTokens.textSecondary)
                }
                KnockFlowLayout(spacing: KnockTokens.spacingS) {
                    Button {
                        Task { await runIndex() }
                    } label: {
                        Label(book?.isIndexed == true ? "重建索引" : "开始索引", systemImage: "arrow.triangle.2.circlepath")
                            .font(.system(size: 13, weight: .heavy))
                    }
                    .buttonStyle(KnockPrimaryButtonStyle(fullWidth: false, height: 36))
                    .disabled(isIndexing)
                    .opacity(isIndexing ? 0.35 : 1)

                    if (book?.failedChunkCount ?? 0) > 0 {
                        Button {
                            Task { await retryFailed() }
                        } label: {
                            Label("重试失败块（\(book?.failedChunkCount ?? 0)）", systemImage: "arrow.clockwise.circle")
                                .font(.system(size: 13, weight: .heavy))
                        }
                        .buttonStyle(KnockSecondaryButtonStyle(fullWidth: false, height: 36))
                        .disabled(isIndexing)
                        .opacity(isIndexing ? 0.35 : 1)
                    }

                    if book?.isIndexed == true || (book?.indexedChunkCount ?? 0) > 0 {
                        Button {
                            Task { await invalidate() }
                        } label: {
                            Text("清除索引")
                                .font(.system(size: 13, weight: .heavy))
                        }
                        .buttonStyle(KnockDangerButtonStyle(fullWidth: false, height: 36))
                        .disabled(isIndexing)
                        .opacity(isIndexing ? 0.35 : 1)
                    }
                }
                if let indexResultText {
                    KnockText(indexResultText, role: .caption, color: KnockTokens.textPrimary, lineSpacing: 2)
                }
                if let indexErrorText {
                    KnockText(indexErrorText, role: .caption, color: KnockTokens.dangerBright, lineSpacing: 2)
                }
            }
        }
    }

    private var progressFraction: Double {
        // 进度文案 "12/25" → 0.48；未知时给一个不定进度
        let parts = progressText.split(separator: "/")
        guard parts.count == 2,
              let done = Double(parts[0]), let total = Double(parts[1]), total > 0 else { return 0.15 }
        return min(max(done / total, 0), 1)
    }

    // MARK: - 召回测试

    private var recallPanel: some View {
        KnockPanel {
            VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
                KnockGroupTitle(title: "召回测试（与聊天注入同链路）", systemImage: "magnifyingglass")

                HStack(spacing: KnockTokens.spacingS) {
                    if operators.isEmpty {
                        KnockText("干员列表为空", role: .caption, color: KnockTokens.textSecondary)
                    } else {
                        KnockDropdown(
                            items: operators.map { KnockSegmentItem(value: $0.id, title: $0.name) },
                            selection: $recallOperatorId
                        )
                    }
                    Spacer(minLength: 0)
                }

                KnockSearchBar(
                    placeholder: "输入查询，如：紧急通讯频道的确认时限",
                    text: $recallQuery,
                    onSubmit: { Task { await runRecall() } }
                )

                HStack(spacing: KnockTokens.spacingM) {
                    Button {
                        Task { await runRecall() }
                    } label: {
                        Label("召回测试", systemImage: "sparkle.magnifyingglass")
                            .font(.system(size: 13, weight: .heavy))
                    }
                    .buttonStyle(KnockPrimaryButtonStyle(fullWidth: false, height: 36))
                    .disabled(isRecalling || recallQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    .opacity(isRecalling || recallQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? 0.35 : 1)

                    if isRecalling {
                        ProgressView().controlSize(.small).tint(KnockTokens.textSecondary)
                    }
                    Spacer(minLength: 0)
                    if !assignedOperatorIds.contains(recallOperatorId) {
                        KnockText("该干员未关联本知识库", role: .caption, color: KnockTokens.textSecondary)
                    }
                }

                if let recallError {
                    KnockText(recallError, role: .caption, color: KnockTokens.dangerBright, lineSpacing: 2)
                }
                if let result = recallResult {
                    KnockText(
                        result.reasonText,
                        role: .caption,
                        color: result.isHit ? KnockTokens.textPrimary : KnockTokens.textSecondary,
                        lineSpacing: 2
                    )
                    ForEach(result.hits) { hit in
                        KnockCapsuleCard(padding: KnockTokens.spacingS) {
                            VStack(alignment: .leading, spacing: KnockTokens.spacingXXS) {
                                HStack(spacing: KnockTokens.spacingXS) {
                                    KnockText(hit.book, role: .captionStrong, lineLimit: 1)
                                    Spacer(minLength: 0)
                                    Text("分数 \(hit.scoreText)")
                                        .font(.system(size: 10, weight: .heavy, design: .monospaced))
                                        .foregroundStyle(KnockTokens.textSecondary)
                                }
                                KnockText(hit.snippet, role: .caption, color: KnockTokens.textSecondary, lineSpacing: 2, lineLimit: 4)
                            }
                        }
                    }
                }
            }
        }
    }

    // MARK: - 关联干员

    private var assignmentPanel: some View {
        KnockPanel {
            VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
                KnockGroupTitle(title: "关联干员（召回范围）", systemImage: "person.2.badge.gearshape")
                KnockText(
                    "只有勾选的干员在私聊/群聊时会召回这本知识库；关联语义与 Android 角色知识库绑定一致。",
                    role: .caption,
                    color: KnockTokens.textTertiary,
                    lineSpacing: 3
                )
                if operators.isEmpty {
                    KnockText("干员列表为空（operators 表无数据）", role: .caption, color: KnockTokens.textSecondary)
                } else {
                    VStack(spacing: 0) {
                        ForEach(Array(operators.enumerated()), id: \.element.id) { index, item in
                            assignmentRow(item)
                            if index < operators.count - 1 {
                                KnockDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    private func assignmentRow(_ item: KnowledgeOperatorItem) -> some View {
        let assigned = assignedOperatorIds.contains(item.id)
        return Button {
            Task { await toggleAssignment(item) }
        } label: {
            HStack(spacing: KnockTokens.spacingS) {
                Image(systemName: assigned ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 15, weight: .heavy))
                    .foregroundStyle(assigned ? KnockTokens.electric : KnockTokens.textTertiary)
                KnockText(item.name, role: .body, lineLimit: 1)
                Spacer(minLength: KnockTokens.spacingS)
                if item.id == recallOperatorId {
                    KnockBadge(text: "当前测试对象", variant: .neutral)
                }
            }
            .padding(.vertical, KnockTokens.spacingS)
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(item.name)\(assigned ? "，已关联" : "，未关联")")
        .accessibilityAddTraits(assigned ? [.isSelected] : [])
    }

    // MARK: - 切块预览

    private var chunksPanel: some View {
        KnockPanel {
            VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
                KnockGroupTitle(title: "切块预览（前 \(chunks.count)/\(totalChunks) 块）", systemImage: "square.stack.3d.up")
                if chunks.isEmpty {
                    KnockText("暂无切块", role: .caption, color: KnockTokens.textSecondary)
                } else {
                    ForEach(chunks) { chunk in
                        KnockCapsuleCard(padding: KnockTokens.spacingS) {
                            VStack(alignment: .leading, spacing: KnockTokens.spacingXS) {
                                HStack(spacing: KnockTokens.spacingXS) {
                                    Text("#\(chunk.ordinal)")
                                        .font(.system(size: 10, weight: .heavy, design: .monospaced))
                                        .foregroundStyle(KnockTokens.textSecondary)
                                    KnockText(chunk.titleText, role: .captionStrong, lineLimit: 1)
                                    Spacer(minLength: 0)
                                    Image(systemName: chunk.isIndexed ? "checkmark.seal.fill" : "circle.dashed")
                                        .font(.system(size: 11, weight: .heavy))
                                        .foregroundStyle(KnockTokens.textSecondary)
                                        .accessibilityLabel(chunk.isIndexed ? "已索引" : "未索引")
                                }
                                KnockText(chunk.content, role: .caption, color: KnockTokens.textSecondary, lineSpacing: 2, lineLimit: 5)
                                if !chunk.indexError.isEmpty {
                                    KnockText("索引错误：\(chunk.indexError)", role: .caption, color: KnockTokens.dangerBright, lineSpacing: 2, lineLimit: 2)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // MARK: - 删除

    private var dangerPanel: some View {
        KnockPanel {
            VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
                KnockGroupTitle(title: "危险操作", systemImage: "exclamationmark.triangle")
                Button {
                    showDeleteConfirm = true
                } label: {
                    Label("删除这本知识库", systemImage: "trash")
                }
                .buttonStyle(KnockDangerButtonStyle())
                KnockText(
                    "删除会同时移除切块、干员关联与已建向量；不可恢复。",
                    role: .caption,
                    color: KnockTokens.textTertiary,
                    lineSpacing: 2
                )
            }
        }
    }

    // MARK: - 数据加载

    private func reloadAll() async {
        isLoading = true
        defer { isLoading = false }
        switch await environment.bridge.loadKnowledgeDetail(id: bookId, chunkLimit: 50) {
        case .success(let detail):
            book = detail.book
            chunks = detail.chunks
            totalChunks = detail.totalChunks
            assignedOperatorIds = detail.assignedOperatorIds
            errorText = nil
        case .failure(let error):
            errorText = error.message
        }
        if case .success(let items) = await environment.bridge.loadKnowledgeOperators() {
            operators = items
            if recallOperatorId.isEmpty {
                recallOperatorId = assignedOperatorIds.first
                    ?? items.first(where: { $0.id == autoOperatorId })?.id
                    ?? items.first?.id
                    ?? ""
            }
        }
        if case .success(let item) = await environment.bridge.planKnowledgeIndex(id: bookId) {
            plan = item
        }
    }

    // MARK: - 动作

    private func runIndex() async {
        guard !isIndexing else { return }
        indexResultText = nil
        indexErrorText = nil
        if plan?.requiresUserConfirmation == true {
            // 远程嵌入需确认（避免产生意外费用）；确认弹窗由 body 中的自绘弹窗呈现
            showRemoteIndexConfirm = true
            return
        }
        await performIndex()
    }

    private func performIndex() async {
        isIndexing = true
        progressText = ""
        defer { isIndexing = false }
        let result = await environment.bridge.indexKnowledge(id: bookId) { done, total in
            progressText = "\(done)/\(total)"
        }
        switch result {
        case .success(let item):
            indexResultText = item.summary
            if !item.errors.isEmpty { indexErrorText = item.errors.joined(separator: "\n") }
        case .failure(let error):
            indexErrorText = error.message
        }
        await reloadAll()
    }

    private func retryFailed() async {
        guard !isIndexing else { return }
        isIndexing = true
        defer { isIndexing = false }
        indexResultText = nil
        indexErrorText = nil
        let result = await environment.bridge.retryFailedKnowledgeChunks(id: bookId) { done, total in
            progressText = "\(done)/\(total)"
        }
        switch result {
        case .success(let item):
            indexResultText = "重试完成：成功 \(item.succeeded)，失败 \(item.failed)"
            if !item.errors.isEmpty { indexErrorText = item.errors.joined(separator: "\n") }
        case .failure(let error):
            indexErrorText = error.message
        }
        await reloadAll()
    }

    private func invalidate() async {
        if let error = await environment.bridge.invalidateKnowledgeIndex(id: bookId) {
            indexErrorText = error
        } else {
            indexResultText = "已清除索引（正文与切块保留）"
        }
        await reloadAll()
    }

    private func runRecall() async {
        guard !isRecalling else { return }
        isRecalling = true
        recallError = nil
        defer { isRecalling = false }
        let query = recallQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        let result = await environment.bridge.recallKnowledge(
            operatorId: recallOperatorId,
            query: query,
            bookIds: [bookId]
        )
        switch result {
        case .success(let item):
            recallResult = item
        case .failure(let error):
            recallError = error.message
            recallResult = nil
        }
    }

    private func toggleAssignment(_ operatorItem: KnowledgeOperatorItem) async {
        let enabled = !assignedOperatorIds.contains(operatorItem.id)
        switch await environment.bridge.setKnowledgeAssignment(id: bookId, operatorId: operatorItem.id, enabled: enabled) {
        case .success(let assigned):
            assignedOperatorIds = assigned
            if recallOperatorId.isEmpty { recallOperatorId = operatorItem.id }
        case .failure(let error):
            actionError = error.message
        }
    }

    private func rename() async {
        let name = renameText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else { return }
        switch await environment.bridge.renameKnowledge(id: bookId, name: name) {
        case .success:
            await reloadAll()
        case .failure(let error):
            actionError = error.message
        }
    }

    private func deleteBook() async {
        if let error = await environment.bridge.deleteKnowledge(id: bookId) {
            actionError = error
            return
        }
        dismiss()
    }

    private func runAutoRecallIfNeeded() async {
        guard let query = autoRunRecallQuery, !didAutoRecall else { return }
        didAutoRecall = true
        try? await Task.sleep(nanoseconds: 5_000_000_000)
        recallQuery = query
        if recallOperatorId.isEmpty { recallOperatorId = autoOperatorId ?? "amiya" }
        await runRecall()
    }
}