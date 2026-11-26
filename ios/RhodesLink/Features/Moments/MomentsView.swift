//
//  MomentsView.swift
//  动态广场（T50，与 Android 原版 MomentsScreen 对齐的最小完整面）：
//  - 时间线：头像 / 作者名 / 正文 / 图片 / 相对时间 / 点赞数 / 评论数（含前 2 条评论预览）
//  - 下拉刷新 + 上滑分页 + 空态 / 加载态 / 错误态
//  - 发布器：文本 + PhotosPicker 图片（图片先经 shared 媒体入库 → 发布媒体 ID）
//  - 点赞（即时反馈，失败回滚）、评论 Sheet（列表 + 输入 + 回复）、删除本人动态（二次确认）
//  - 数据全部来自 shared `MomentsBridge` 真实 SQLite（无 mock）
//  - T60：发布后干员 AI 评论/点赞有 10–30s 拟人随机延迟（oracle MomentReplyTask），故发布后短轮询
//    刷新 3 次（8s/20s/35s）+ 回前台刷新；评论预览与点赞数随之更新。
//
//  敲敲视觉（P3）：隐藏系统导航栏 → `KnockHeader`（返回 + 黄色发布胶囊）；
//  动态 = 面板卡片 + 蓝圈头像 + 胶囊动作键（点赞激活 = 黄底黑字）；评论预览 = 面板内嵌块；
//  发布器/评论区 = 面板 + 胶囊输入 + 黄主按钮；删除确认 = 自绘 `KnockDialog`；无毛玻璃/渐变。
//

import PhotosUI
import SwiftUI

struct MomentsView: View {
    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.dismiss) private var dismiss

    @State private var items: [MomentItem] = []
    @State private var commentsByMoment: [Int64: [MomentCommentItem]] = [:]
    @State private var isLoading = false
    @State private var isLoadingMore = false
    @State private var hasMore = true
    @State private var errorText: String?
    @State private var toast: String?
    @State private var loadedOnce = false
    /// T60：发布后短轮询任务（干员 AI 评论延迟到达后刷新评论/点赞数）。
    @State private var replyPollTask: Task<Void, Never>?

    @State private var showComposer = false
    @State private var commentTarget: MomentItem?
    @State private var deleteTarget: MomentItem?
    @State private var fullscreenImage: MomentItem?
    @State private var badgeText = ""

    private let pageSize = 20

    var body: some View {
        ZStack {
            AmbientBackground(parallax: 0, showsStandee: false)

            VStack(spacing: 0) {
                KnockHeader(title: "动态广场", showsBack: true, onBack: { dismiss() }, trailing: {
                    KnockCapsuleIconButton(
                        systemImage: "square.and.pencil",
                        variant: .accent,
                        accessibilityLabel: "发布动态"
                    ) {
                        showComposer = true
                    }
                })

                ScrollView {
                    LazyVStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                        headerPanel

                        if let errorText {
                            KnockErrorPanel(title: "加载失败", message: errorText) {
                                Task { await reload() }
                            }
                        }

                        if isLoading && items.isEmpty {
                            KnockLoadingPanel("读取动态…")
                        } else if items.isEmpty && !isLoading {
                            KnockEmptyState(
                                title: "还没有动态",
                                message: "干员们还没有发布动态。先写一条，记录今天和干员们的训练日常吧。",
                                actionTitle: "发布第一条动态",
                                action: { showComposer = true }
                            )
                        } else {
                            ForEach(items) { item in
                                momentCard(item)
                                    .onAppear {
                                        if item.id == items.last?.id { Task { await loadMore() } }
                                    }
                            }

                            if isLoadingMore {
                                KnockLoadingPanel("加载更多…")
                            } else if !hasMore && !items.isEmpty {
                                KnockText("没有更多动态了", role: .caption, color: KnockTokens.textTertiary)
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, KnockTokens.spacingM)
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

            if let toast {
                VStack {
                    KnockToast(text: toast)
                        .padding(.top, KnockTokens.spacingS)
                        .transition(.opacity)
                    Spacer()
                }
            }
        }
        .knockHiddenNavigationBar()
        .task {
            guard !loadedOnce else { return }
            loadedOnce = true
            await reload()
            // 打开广场即清「新动态」角标（对齐 Android FeaturesScreen → markMomentsSeen）
            await environment.bridge.markMomentsSeen()
            badgeText = ""
            // 截图/演示钩子（仅启动参数）：发表器 / 评论区。
            let args = ProcessInfo.processInfo.arguments
            if args.contains("--knock-moment-composer") {
                showComposer = true
            } else if args.contains("--knock-moment-comments"), let first = items.first {
                commentTarget = first
            }
        }
        .onAppear {
            if loadedOnce { Task { await reload() } }
        }
        .sheet(isPresented: $showComposer) {
            MomentComposerView { published in
                if published {
                    // T60：干员 AI 评论/点赞在 10–30s 后才落库 → 先刷一次，再短轮询。
                    Task { await reload() }
                    scheduleReplyPolling()
                }
            }
            .environmentObject(environment)
            .presentationDetents([.large])
        }
        .sheet(item: $commentTarget) { moment in
            MomentCommentsSheet(moment: moment) { newCount in
                if let index = items.firstIndex(where: { $0.id == moment.id }) {
                    items[index] = MomentItem(
                        copyOf: items[index],
                        commentCount: newCount
                    )
                    Task { await loadCommentsPreview(for: items[index]) }
                }
            }
            .environmentObject(environment)
        }
        .fullScreenCover(item: $fullscreenImage) { moment in
            MomentFullscreenImage(imageId: moment.imageId) { fullscreenImage = nil }
        }
        .knockConfirmDialog(
            isPresented: Binding(
                get: { deleteTarget != nil },
                set: { if !$0 { deleteTarget = nil } }
            ),
            title: "删除这条动态？",
            message: "删除后无法恢复，该动态的点赞与评论会一并删除。",
            confirmTitle: "删除",
            isDestructive: true
        ) {
            if let target = deleteTarget {
                deleteTarget = nil
                Task { await deleteMoment(target) }
            }
        }
        .onChange(of: scenePhase) { _, phase in
            // T60：回前台刷新（后台期间 A7 catch-up 可能补上干员评论/点赞）。
            if phase == .active, loadedOnce {
                Task { await reload() }
            }
        }
        .onDisappear {
            replyPollTask?.cancel()
        }
    }

    /// T60：发布后干员 AI 评论/点赞有 10–30s 随机延迟；短轮询 3 次（8s/20s/35s，覆盖最晚 30s+执行）刷新评论与点赞数，
    /// 超过次数不再打扰（下拉刷新 / 回前台仍会刷新）。
    private func scheduleReplyPolling() {
        replyPollTask?.cancel()
        replyPollTask = Task {
            for seconds in [8, 20, 35] {
                try? await Task.sleep(nanoseconds: UInt64(seconds) * 1_000_000_000)
                if Task.isCancelled { return }
                await reload()
            }
        }
    }

    // MARK: - 顶部概览卡（真实计数）

    private var headerPanel: some View {
        KnockPanel(padding: KnockTokens.spacingM) {
            HStack(spacing: KnockTokens.spacingM) {
                KnockRowGlyph(systemImage: "bubble.left.and.text.bubble.right.fill", size: 38)
                VStack(alignment: .leading, spacing: KnockTokens.spacingXXS) {
                    KnockText("干员动态", role: .rowTitle, lineLimit: 1)
                    KnockText(
                        "\(items.count) 条动态\(badgeText.isEmpty ? "" : " · \(badgeText)")",
                        role: .caption,
                        color: KnockTokens.textSecondary,
                        lineLimit: 1
                    )
                }
                Spacer(minLength: 0)
                Button {
                    showComposer = true
                } label: {
                    Label("发布", systemImage: "plus")
                        .font(.system(size: 13, weight: .heavy))
                }
                .buttonStyle(KnockPrimaryButtonStyle(fullWidth: false, height: 34))
                .accessibilityLabel("发布动态")
            }
        }
    }

    // MARK: - 时间线卡片

    private func momentCard(_ item: MomentItem) -> some View {
        KnockPanel {
            VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                HStack(alignment: .top, spacing: KnockTokens.spacingM) {
                    KnockAvatar(avatarId: item.authorAvatarId, name: item.authorName, size: 44)

                    VStack(alignment: .leading, spacing: KnockTokens.spacingXS) {
                        HStack(spacing: KnockTokens.spacingXS) {
                            KnockText(item.authorName, role: .rowTitle, lineLimit: 1)
                            if item.isMine {
                                KnockBadge(text: "我", variant: .neutral)
                            }
                            Spacer(minLength: 0)
                            KnockText(item.createdAtText, role: .caption, color: KnockTokens.textTertiary, lineLimit: 1)
                            if item.isMine {
                                Menu {
                                    Button(role: .destructive) {
                                        deleteTarget = item
                                    } label: {
                                        Label("删除动态", systemImage: "trash")
                                    }
                                } label: {
                                    Image(systemName: "ellipsis")
                                        .font(.system(size: 13, weight: .heavy))
                                        .foregroundStyle(KnockTokens.textSecondary)
                                        .frame(width: 32, height: 26)
                                        .background(Capsule(style: .continuous).fill(KnockTokens.rowIconWell))
                                        .frame(minHeight: 44)
                                        .contentShape(Rectangle())
                                }
                                .accessibilityLabel("动态操作")
                                .accessibilityIdentifier("moment-menu-\(item.id)")
                            }
                        }

                        if !item.text.isEmpty {
                            KnockText(item.text, role: .body, lineSpacing: 4)
                                .fixedSize(horizontal: false, vertical: true)
                        }

                        if item.hasImage {
                            MomentImageThumb(imageId: item.imageId) {
                                fullscreenImage = item
                            }
                        }
                    }
                }

                // 评论区预览（前 2 条，含回复缩进）
                if let comments = commentsByMoment[item.id], !comments.isEmpty {
                    VStack(alignment: .leading, spacing: KnockTokens.spacingXS) {
                        ForEach(Array(comments.prefix(2))) { comment in
                            commentLine(comment)
                        }
                        if item.commentCount > 2 {
                            Button {
                                commentTarget = item
                            } label: {
                                HStack(spacing: 4) {
                                    KnockText("查看全部 \(item.commentCount) 条评论", role: .captionStrong, lineLimit: 1)
                                    Image(systemName: "chevron.right")
                                        .font(.system(size: 9, weight: .heavy))
                                        .foregroundStyle(KnockTokens.textTertiary)
                                }
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(KnockTokens.spacingS)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(
                        RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .fill(KnockTokens.rowIconWell)
                    )
                }

                // 操作行：点赞 / 评论（胶囊动作键；点赞激活 = 黄底黑字）
                HStack(spacing: KnockTokens.spacingS) {
                    KnockPillActionButton(
                        title: "\(item.likeCount)",
                        systemImage: item.isLiked ? "heart.fill" : "heart",
                        isActive: item.isLiked
                    ) {
                        Task { await toggleLike(item) }
                    }
                    .accessibilityLabel(item.isLiked ? "取消点赞" : "点赞")

                    KnockPillActionButton(
                        title: "\(item.commentCount)",
                        systemImage: "bubble.right",
                        isActive: false
                    ) {
                        commentTarget = item
                    }
                    .accessibilityLabel("评论")

                    Spacer(minLength: 0)
                }
            }
        }
    }

    private func commentLine(_ comment: MomentCommentItem) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: KnockTokens.spacingXS) {
            KnockText(comment.authorName, role: .captionStrong, lineLimit: 1)
            if !comment.replyToName.isEmpty {
                KnockText("回复", role: .caption, color: KnockTokens.textTertiary, lineLimit: 1)
                KnockText("@\(comment.replyToName)", role: .captionStrong, lineLimit: 1)
            }
            KnockText("：\(comment.text)", role: .caption, color: KnockTokens.textSecondary, lineSpacing: 2, lineLimit: 2)
        }
        .padding(.leading, comment.parentCommentId > 0 ? 14 : 0)
    }

    // MARK: - 数据

    private func reload() async {
        if items.isEmpty { isLoading = true }
        errorText = nil
        let result = await environment.bridge.loadMoments(offset: 0, limit: pageSize)
        isLoading = false
        switch result {
        case .success(let page):
            items = page.items
            hasMore = page.hasMore
            await prefetchComments(page.items)
        case .failure(let error):
            errorText = error.message
        }
    }

    private func loadMore() async {
        guard hasMore, !isLoadingMore, !isLoading else { return }
        isLoadingMore = true
        let result = await environment.bridge.loadMoments(offset: items.count, limit: pageSize)
        isLoadingMore = false
        switch result {
        case .success(let page):
            let existing = Set(items.map(\.id))
            let fresh = page.items.filter { !existing.contains($0.id) }
            items.append(contentsOf: fresh)
            hasMore = page.hasMore && !fresh.isEmpty
            await prefetchComments(page.items)
        case .failure(let error):
            showToast("加载更多失败：\(error.message)")
        }
    }

    private func prefetchComments(_ page: [MomentItem]) async {
        // T60：评论数变化后必须重新拉取预览（干员 AI 评论异步落库），不能只在首次加载时取。
        for item in page {
            if item.commentCount > 0 {
                await loadCommentsPreview(for: item)
            } else {
                commentsByMoment[item.id] = []
            }
        }
    }

    private func loadCommentsPreview(for item: MomentItem) async {
        guard item.commentCount > 0 else {
            commentsByMoment[item.id] = []
            return
        }
        if case .success(let comments) = await environment.bridge.loadMomentComments(momentId: item.id) {
            commentsByMoment[item.id] = comments
        }
    }

    private func toggleLike(_ item: MomentItem) async {
        guard let index = items.firstIndex(where: { $0.id == item.id }) else { return }
        let optimistic = !items[index].isLiked
        let optimisticCount = max(0, items[index].likeCount + (optimistic ? 1 : -1))
        items[index] = MomentItem(copyOf: items[index], isLiked: optimistic, likeCount: optimisticCount)
        switch await environment.bridge.toggleMomentLike(momentId: item.id) {
        case .success(let result):
            if let current = items.firstIndex(where: { $0.id == item.id }) {
                items[current] = MomentItem(
                    copyOf: items[current],
                    isLiked: result.liked,
                    likeCount: result.likeCount
                )
            }
        case .failure(let error):
            if let current = items.firstIndex(where: { $0.id == item.id }) {
                items[current] = item
            }
            showToast("点赞失败：\(error.message)")
        }
    }

    private func deleteMoment(_ item: MomentItem) async {
        if let error = await environment.bridge.deleteMoment(momentId: item.id) {
            showToast("删除失败：\(error)")
            return
        }
        items.removeAll { $0.id == item.id }
        commentsByMoment[item.id] = nil
        showToast("已删除")
        SelfTestLog.add("moments delete ok id=\(item.id)")
    }

    private func showToast(_ message: String) {
        withAnimation { toast = message }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.8) {
            if toast == message { withAnimation { toast = nil } }
        }
    }
}

// MARK: - 动态卡片局部更新辅助

extension MomentItem {
    init(copyOf base: MomentItem, isLiked: Bool? = nil, likeCount: Int? = nil, commentCount: Int? = nil) {
        self.init(
            id: base.id,
            operatorId: base.operatorId,
            authorName: base.authorName,
            authorAvatarId: base.authorAvatarId,
            text: base.text,
            imageId: base.imageId,
            isUserPost: base.isUserPost,
            isMine: base.isMine,
            likeCount: likeCount ?? base.likeCount,
            commentCount: commentCount ?? base.commentCount,
            isLiked: isLiked ?? base.isLiked,
            createdAt: base.createdAt
        )
    }

    private init(
        id: Int64, operatorId: String, authorName: String, authorAvatarId: String,
        text: String, imageId: String, isUserPost: Bool, isMine: Bool,
        likeCount: Int, commentCount: Int, isLiked: Bool, createdAt: Int64
    ) {
        self.id = id
        self.operatorId = operatorId
        self.authorName = authorName
        self.authorAvatarId = authorAvatarId
        self.text = text
        self.imageId = imageId
        self.isUserPost = isUserPost
        self.isMine = isMine
        self.likeCount = likeCount
        self.commentCount = commentCount
        self.isLiked = isLiked
        self.createdAt = createdAt
    }
}

// MARK: - 图片缩略图 / 全屏

struct MomentImageThumb: View {
    let imageId: String
    var onTap: () -> Void

    var body: some View {
        let path = AppGraphClient.shared.momentImagePath(imageId: imageId)
        if let uiImage = path.isEmpty ? nil : UIImage(contentsOfFile: path) {
            Image(uiImage: uiImage)
                .resizable()
                .scaledToFill()
                .frame(maxWidth: .infinity)
                .frame(height: 180)
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .strokeBorder(KnockTokens.strokeSecondary, lineWidth: KnockTokens.strokeWidth)
                }
                .contentShape(Rectangle())
                .onTapGesture(perform: onTap)
                .accessibilityLabel("动态图片")
        } else {
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(KnockTokens.row)
                .frame(height: 120)
                .overlay {
                    Label("图片无法显示", systemImage: "photo")
                        .font(.system(size: 13, weight: .heavy))
                        .foregroundStyle(KnockTokens.textSecondary)
                }
                .overlay {
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .strokeBorder(KnockTokens.strokeSecondary, lineWidth: KnockTokens.strokeWidth)
                }
                .accessibilityLabel("图片无法显示")
        }
    }
}

struct MomentFullscreenImage: View {
    let imageId: String
    var onClose: () -> Void

    var body: some View {
        ZStack {
            KnockTokens.canvas.ignoresSafeArea()
            let path = AppGraphClient.shared.momentImagePath(imageId: imageId)
            if let uiImage = path.isEmpty ? nil : UIImage(contentsOfFile: path) {
                Image(uiImage: uiImage)
                    .resizable()
                    .scaledToFit()
                    .ignoresSafeArea()
            } else {
                KnockText("图片无法显示", role: .body)
            }
            VStack {
                HStack {
                    Spacer()
                    KnockCloseButton(label: "关闭图片") { onClose() }
                }
                .padding(KnockTokens.screenPadding)
                Spacer()
            }
        }
        .knockHiddenNavigationBar()
    }
}

// MARK: - 发布器（文本 + PhotosPicker 图片 → 媒体入库）

struct MomentComposerView: View {
    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.dismiss) private var dismiss

    @State private var text = ""
    @State private var pickedItem: PhotosPickerItem?
    @State private var pickedImage: UIImage?
    @State private var isIngesting = false
    @State private var isPublishing = false
    @State private var errorText: String?
    private let onFinished: (Bool) -> Void

    private var canPublish: Bool {
        !isPublishing && !isIngesting
            && !(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && pickedImage == nil)
    }

    init(onFinished: @escaping (Bool) -> Void) {
        self.onFinished = onFinished
    }

    var body: some View {
        ZStack {
            AmbientBackground(parallax: 0, showsStandee: false)

            VStack(spacing: 0) {
                KnockHeader(title: "发布动态", trailing: {
                    HStack(spacing: KnockTokens.spacingS) {
                        Button {
                            dismiss()
                        } label: {
                            Text("取消")
                        }
                        .buttonStyle(KnockSecondaryButtonStyle(fullWidth: false, height: 34))
                        .accessibilityLabel("取消发布")

                        Button {
                            Task { await publish() }
                        } label: {
                            if isPublishing || isIngesting {
                                ProgressView().controlSize(.small).tint(KnockTokens.onElectric)
                            } else {
                                Text("发布")
                            }
                        }
                        .buttonStyle(KnockPrimaryButtonStyle(fullWidth: false, height: 34))
                        .disabled(!canPublish)
                        .opacity(canPublish ? 1 : 0.35)
                        .accessibilityLabel("确认发布")
                    }
                })

                ScrollView {
                    VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                        KnockPanel {
                            VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
                                KnockGroupTitle(title: "正文", systemImage: "text.alignleft")
                                textEditor
                                KnockText("\(text.count) 字", role: .caption, color: KnockTokens.textTertiary)
                            }
                        }

                        if let pickedImage {
                            imagePreview(pickedImage)
                        }

                        KnockPanel(padding: KnockTokens.spacingM) {
                            VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
                                PhotosPicker(selection: $pickedItem, matching: .images, photoLibrary: .shared()) {
                                    HStack(spacing: KnockTokens.spacingS) {
                                        Image(systemName: "photo")
                                            .font(.system(size: 13, weight: .heavy))
                                        Text(pickedImage == nil ? "选择图片" : "更换图片")
                                    }
                                    .font(.system(size: 13, weight: .heavy))
                                    .foregroundStyle(KnockTokens.textPrimary)
                                    .padding(.horizontal, KnockTokens.spacingL)
                                    .frame(minHeight: KnockTokens.buttonHeight)
                                    .background(
                                        Capsule(style: .continuous)
                                            .strokeBorder(
                                                KnockTokens.strokeDashed,
                                                style: StrokeStyle(lineWidth: KnockTokens.strokeWidth, dash: [5, 4])
                                            )
                                    )
                                }
                                .accessibilityLabel("选择图片")

                                if isIngesting {
                                    KnockLoadingPanel("图片处理中…")
                                }
                            }
                        }

                        if let errorText {
                            KnockErrorPanel(title: "发布失败", message: errorText, retryTitle: nil)
                                .accessibilityLabel("发布错误")
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
        .onChange(of: pickedItem) { _, item in
            guard let item else { return }
            Task { await loadPhoto(item) }
        }
    }

    private var textEditor: some View {
        TextEditor(text: $text)
            .knockFont(.body)
            .scrollContentBackground(.hidden)
            .foregroundStyle(KnockTokens.textPrimary)
            .tint(KnockTokens.electric)
            .frame(minHeight: 160)
            .padding(KnockTokens.spacingS)
            .background(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(KnockTokens.row)
            )
            .overlay {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .strokeBorder(KnockTokens.strokeSecondary, lineWidth: KnockTokens.strokeWidth)
            }
            .overlay(alignment: .topLeading) {
                if text.isEmpty {
                    KnockText("说点什么…（记录和干员们的日常）", role: .body, color: KnockTokens.textTertiary)
                        .padding(.horizontal, KnockTokens.spacingM)
                        .padding(.vertical, KnockTokens.spacingM)
                        .allowsHitTesting(false)
                }
            }
            .accessibilityLabel("动态正文")
    }

    private func imagePreview(_ image: UIImage) -> some View {
        ZStack(alignment: .topTrailing) {
            Image(uiImage: image)
                .resizable()
                .scaledToFill()
                .frame(height: 180)
                .frame(maxWidth: .infinity)
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .strokeBorder(KnockTokens.strokeSecondary, lineWidth: KnockTokens.strokeWidth)
                }
            KnockCloseButton(label: "移除图片") {
                self.pickedImage = nil
                pickedItem = nil
            }
            .padding(KnockTokens.spacingS)
        }
    }

    private func loadPhoto(_ item: PhotosPickerItem) async {
        isIngesting = true
        errorText = nil
        defer { isIngesting = false }
        do {
            guard let data = try await item.loadTransferable(type: Data.self),
                  let image = UIImage(data: data) else {
                errorText = "无法读取这张图片，请重新选择"
                return
            }
            pickedImage = image
        } catch {
            errorText = "读取图片失败：\(error.localizedDescription)"
        }
    }

    private func publish() async {
        isPublishing = true
        errorText = nil
        defer { isPublishing = false }
        var mediaId = ""
        if let pickedImage {
            guard let prepared = Self.prepareImage(pickedImage) else {
                errorText = "图片处理失败，请重新选择"
                return
            }
            let tempURL = FileManager.default.temporaryDirectory
                .appendingPathComponent("rhodes-moment-\(UUID().uuidString).jpg")
            do {
                try prepared.write(to: tempURL, options: .atomic)
            } catch {
                errorText = "图片写入失败：\(error.localizedDescription)"
                return
            }
            let ingest = await environment.bridge.ingestMomentImage(sourcePath: tempURL.path)
            try? FileManager.default.removeItem(at: tempURL)
            switch ingest {
            case .failure(let error):
                errorText = "图片保存失败：\(error.message)"
                return
            case .success(let id):
                mediaId = id
            }
        }
        let cleanText = text.trimmingCharacters(in: .whitespacesAndNewlines)
        switch await environment.bridge.publishMoment(text: cleanText, imageId: mediaId) {
        case .success(let momentId):
            SelfTestLog.add("moments publish ok id=\(momentId) image=\(mediaId.isEmpty ? "-" : mediaId) chars=\(cleanText.count)")
            onFinished(true)
            dismiss()
        case .failure(let error):
            errorText = "发布失败：\(error.message)"
        }
    }

    /// ≤1920px + JPEG 0.8（与聊天图片落盘口径一致）
    private static func prepareImage(_ image: UIImage) -> Data? {
        let maxDimension: CGFloat = 1920
        let longest = max(image.size.width, image.size.height)
        if longest > maxDimension, longest > 0 {
            let ratio = maxDimension / longest
            let size = CGSize(width: image.size.width * ratio, height: image.size.height * ratio)
            let renderer = UIGraphicsImageRenderer(size: size)
            let scaled = renderer.image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
            return scaled.jpegData(compressionQuality: 0.8)
        }
        return image.jpegData(compressionQuality: 0.8)
    }
}

// MARK: - 评论 Sheet（列表 + 输入 + 回复）

struct MomentCommentsSheet: View {
    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.dismiss) private var dismiss

    let moment: MomentItem
    private let onCommentCountChanged: (Int) -> Void

    @State private var comments: [MomentCommentItem] = []
    @State private var draft = ""
    @State private var replyTarget: MomentCommentItem?
    @State private var isLoading = true
    @State private var isSending = false
    @State private var errorText: String?

    init(moment: MomentItem, onCommentCountChanged: @escaping (Int) -> Void) {
        self.moment = moment
        self.onCommentCountChanged = onCommentCountChanged
    }

    var body: some View {
        ZStack {
            AmbientBackground(parallax: 0, showsStandee: false)

            VStack(spacing: 0) {
                KnockHeader(
                    title: "评论",
                    subtitle: "\(comments.count) 条",
                    showsBack: false,
                    trailing: {
                        KnockCloseButton(label: "关闭评论") { dismiss() }
                    }
                )

                Group {
                    if isLoading {
                        KnockLoadingPanel("读取评论…")
                            .padding(.horizontal, KnockTokens.screenPadding)
                            .padding(.top, KnockTokens.spacingL)
                    } else if comments.isEmpty {
                        ScrollView {
                            KnockEmptyState(
                                title: "还没有评论",
                                message: "说点什么，让干员知道你的想法。"
                            )
                            .padding(.horizontal, KnockTokens.screenPadding)
                            .padding(.top, KnockTokens.spacingL)
                        }
                    } else {
                        ScrollView {
                            LazyVStack(alignment: .leading, spacing: KnockTokens.spacingS) {
                                ForEach(comments) { comment in
                                    commentRow(comment)
                                }
                            }
                            .padding(.horizontal, KnockTokens.screenPadding)
                            .padding(.vertical, KnockTokens.spacingM)
                        }
                        .scrollIndicators(.hidden)
                        .knockScrollEdgeHidden()
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)

                if let replyTarget {
                    HStack(spacing: KnockTokens.spacingS) {
                        KnockText("回复 @\(replyTarget.authorName)", role: .caption, color: KnockTokens.textSecondary, lineLimit: 1)
                        Spacer(minLength: 0)
                        Button {
                            self.replyTarget = nil
                        } label: {
                            KnockText("取消", role: .captionStrong, lineLimit: 1)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("取消回复")
                    }
                    .padding(.horizontal, KnockTokens.screenPadding)
                    .padding(.top, KnockTokens.spacingS)
                }

                if let errorText {
                    KnockText(errorText, role: .caption, color: KnockTokens.dangerBright)
                        .padding(.horizontal, KnockTokens.screenPadding)
                        .padding(.top, KnockTokens.spacingS)
                }

                HStack(spacing: KnockTokens.spacingS) {
                    TextField(resolveTextFieldPlaceholder(), text: $draft, axis: .vertical)
                        .lineLimit(1...3)
                        .textFieldStyle(KnockTextFieldStyle(height: 44))
                        .accessibilityLabel("评论输入")
                    Button {
                        Task { await send() }
                    } label: {
                        if isSending {
                            ProgressView().controlSize(.small).tint(KnockTokens.onElectric)
                        } else {
                            Image(systemName: "paperplane.fill")
                                .font(.system(size: 15, weight: .heavy))
                        }
                    }
                    .buttonStyle(KnockPrimaryButtonStyle(fullWidth: false, height: 44))
                    .disabled(isSending || draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    .opacity(isSending || draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? 0.35 : 1)
                    .accessibilityLabel("发送评论")
                }
                .padding(.horizontal, KnockTokens.screenPadding)
                .padding(.vertical, KnockTokens.spacingM)
                .background(KnockTokens.canvas)
            }
        }
        .knockHiddenNavigationBar()
        .task { await reload() }
    }

    private func commentRow(_ comment: MomentCommentItem) -> some View {
        HStack(alignment: .top, spacing: KnockTokens.spacingS) {
            KnockAvatar(avatarId: "", name: comment.authorName, size: 30)
            VStack(alignment: .leading, spacing: KnockTokens.spacingXXS) {
                HStack(spacing: KnockTokens.spacingXS) {
                    KnockText(comment.authorName, role: .captionStrong, lineLimit: 1)
                    if !comment.replyToName.isEmpty {
                        KnockText("回复 @\(comment.replyToName)", role: .caption, color: KnockTokens.textTertiary, lineLimit: 1)
                    }
                    Spacer(minLength: 0)
                    KnockText(comment.createdAtText, role: .caption, color: KnockTokens.textTertiary, lineLimit: 1)
                }
                KnockText(comment.text, role: .caption, color: KnockTokens.textPrimary, lineSpacing: 3)
                    .fixedSize(horizontal: false, vertical: true)
                Button {
                    replyTarget = comment
                    draft = ""
                } label: {
                    KnockText("回复", role: .captionStrong, lineLimit: 1)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("回复 \(comment.authorName)")
            }
        }
        .padding(.leading, comment.parentCommentId > 0 ? 18 : 0)
        .padding(.vertical, KnockTokens.spacingXS)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func resolveTextFieldPlaceholder() -> String {
        if let replyTarget { return "回复 @\(replyTarget.authorName)…" }
        return "说点什么…"
    }

    private func reload() async {
        isLoading = true
        switch await environment.bridge.loadMomentComments(momentId: moment.id) {
        case .success(let list):
            comments = list
        case .failure(let error):
            errorText = "读取评论失败：\(error.message)"
        }
        isLoading = false
    }

    private func send() async {
        let clean = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return }
        isSending = true
        errorText = nil
        defer { isSending = false }
        let result = await environment.bridge.addMomentComment(
            momentId: moment.id,
            text: clean,
            parentCommentId: replyTarget?.id ?? 0,
            replyToName: replyTarget?.authorName ?? ""
        )
        switch result {
        case .success:
            draft = ""
            replyTarget = nil
            await reload()
            onCommentCountChanged(comments.count)
            SelfTestLog.add("moments comment ok moment=\(moment.id) count=\(comments.count)")
        case .failure(let error):
            errorText = "发送失败：\(error.message)"
        }
    }
}