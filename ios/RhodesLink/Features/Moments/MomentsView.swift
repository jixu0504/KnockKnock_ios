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

import PhotosUI
import SwiftUI

struct MomentsView: View {
    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.scenePhase) private var scenePhase

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

            ScrollView {
                LazyVStack(spacing: 12) {
                    headerCard

                    if let errorText {
                        errorCard(errorText)
                    }

                    if isLoading && items.isEmpty {
                        loadingCard
                    } else if items.isEmpty && !isLoading {
                        EmptyStateView(
                            title: "还没有动态",
                            message: "干员们还没有发布动态。先写一条，记录今天和干员们的训练日常吧。",
                            systemImage: "bubble.left.and.text.bubble.right",
                            actionTitle: "发布第一条动态",
                            action: { showComposer = true }
                        )
                        .padding(.horizontal, 4)
                    } else {
                        ForEach(items) { item in
                            momentCard(item)
                                .onAppear {
                                    if item.id == items.last?.id { Task { await loadMore() } }
                                }
                        }

                        if isLoadingMore {
                            ProgressView().padding(.vertical, 8)
                        } else if !hasMore && !items.isEmpty {
                            Text("没有更多动态了")
                                .font(.caption)
                                .foregroundStyle(.tertiary)
                                .padding(.vertical, 12)
                        }
                    }
                }
                .padding(.horizontal, 14)
                .padding(.bottom, 24)
            }
            .refreshable { await reload() }

            if let toast {
                VStack {
                    Text(toast)
                        .font(.footnote.weight(.medium))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(Capsule().fill(Color.black.opacity(0.72)))
                        .padding(.top, 8)
                    Spacer()
                }
                .transition(.opacity)
            }
        }
        .navigationTitle("动态广场")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    showComposer = true
                } label: {
                    Image(systemName: "square.and.pencil")
                }
                .accessibilityLabel("发布动态")
            }
        }
        .task {
            guard !loadedOnce else { return }
            loadedOnce = true
            await reload()
            // 打开广场即清「新动态」角标（对齐 Android FeaturesScreen → markMomentsSeen）
            await environment.bridge.markMomentsSeen()
            badgeText = ""
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
        .alert("删除这条动态？", isPresented: Binding(
            get: { deleteTarget != nil },
            set: { if !$0 { deleteTarget = nil } }
        )) {
            Button("删除", role: .destructive) {
                if let target = deleteTarget {
                    deleteTarget = nil
                    Task { await deleteMoment(target) }
                }
            }
            Button("取消", role: .cancel) { deleteTarget = nil }
        } message: {
            Text("删除后无法恢复，该动态的点赞与评论会一并删除。")
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

    private var headerCard: some View {
        HStack(spacing: 10) {
            Image(systemName: "bubble.left.and.text.bubble.right.fill")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 34, height: 34)
                .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(GlassTokens.brandOrange))
            VStack(alignment: .leading, spacing: 2) {
                Text("干员动态")
                    .font(.subheadline.weight(.semibold))
                Text("\(items.count) 条动态\(badgeText.isEmpty ? "" : " · \(badgeText)")")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Button {
                showComposer = true
            } label: {
                Label("发布", systemImage: "plus")
                    .font(.footnote.weight(.semibold))
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(Capsule().fill(GlassTokens.brandOrange.opacity(0.14)))
                    .foregroundStyle(GlassTokens.brandOrange)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("发布动态")
        }
        .padding(12)
        .cardSurface(cornerRadius: 16)
    }

    private var loadingCard: some View {
        HStack(spacing: 10) {
            ProgressView()
            Text("读取动态…").font(.subheadline).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 28)
        .cardSurface(cornerRadius: 16)
    }

    private func errorCard(_ message: String) -> some View {
        VStack(spacing: 10) {
            Label("加载失败", systemImage: "exclamationmark.triangle")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.red)
            Text(message)
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            Button("重试") { Task { await reload() } }
                .buttonStyle(.borderedProminent)
                .tint(GlassTokens.brandOrange)
                .accessibilityLabel("重试")
        }
        .frame(maxWidth: .infinity)
        .padding(16)
        .cardSurface(cornerRadius: 16)
    }

    // MARK: - 时间线卡片

    private func momentCard(_ item: MomentItem) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 10) {
                RoleAvatar(avatarId: item.authorAvatarId, name: item.authorName, size: 44)

                VStack(alignment: .leading, spacing: 6) {
                    HStack(spacing: 6) {
                        Text(item.authorName)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(GlassTokens.brandOrange)
                        if item.isMine {
                            Text("我")
                                .font(.caption2.weight(.bold))
                                .padding(.horizontal, 5)
                                .padding(.vertical, 1)
                                .background(Capsule().fill(GlassTokens.brandOrange.opacity(0.16)))
                                .foregroundStyle(GlassTokens.brandOrange)
                        }
                        Spacer()
                        Text(item.createdAtText)
                            .font(.caption2)
                            .foregroundStyle(.tertiary)
                        if item.isMine {
                            Menu {
                                Button(role: .destructive) {
                                    deleteTarget = item
                                } label: {
                                    Label("删除动态", systemImage: "trash")
                                }
                            } label: {
                                Image(systemName: "ellipsis")
                                    .font(.footnote)
                                    .foregroundStyle(.secondary)
                                    .padding(.leading, 2)
                            }
                            .accessibilityLabel("动态操作")
                            .accessibilityIdentifier("moment-menu-\(item.id)")
                        }
                    }

                    if !item.text.isEmpty {
                        Text(item.text)
                            .font(.body)
                            .foregroundStyle(.primary)
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
                VStack(alignment: .leading, spacing: 4) {
                    ForEach(Array(comments.prefix(2))) { comment in
                        commentLine(comment)
                    }
                    if item.commentCount > 2 {
                        Text("查看全部 \(item.commentCount) 条评论")
                            .font(.caption2)
                            .foregroundStyle(GlassTokens.brandOrange)
                            .onTapGesture { commentTarget = item }
                    }
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 8)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(GlassTokens.bubbleSystem))
            }

            // 操作行：点赞 / 评论
            HStack(spacing: 22) {
                Button {
                    Task { await toggleLike(item) }
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: item.isLiked ? "heart.fill" : "heart")
                            .foregroundStyle(item.isLiked ? .red : .secondary)
                        Text("\(item.likeCount)")
                            .font(.caption.weight(.medium))
                            .foregroundStyle(item.isLiked ? .red : .secondary)
                    }
                }
                .buttonStyle(.plain)
                .accessibilityLabel(item.isLiked ? "取消点赞" : "点赞")

                Button {
                    commentTarget = item
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: "bubble.right")
                            .foregroundStyle(.secondary)
                        Text("\(item.commentCount)")
                            .font(.caption.weight(.medium))
                            .foregroundStyle(.secondary)
                    }
                }
                .buttonStyle(.plain)
                .accessibilityLabel("评论")

                Spacer()
            }
            .contentShape(Rectangle())
        }
        .padding(12)
        .cardSurface(cornerRadius: 18)
    }

    private func commentLine(_ comment: MomentCommentItem) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 4) {
            Text(comment.authorName)
                .font(.caption.weight(.semibold))
                .foregroundStyle(GlassTokens.brandOrange)
            if !comment.replyToName.isEmpty {
                Text("回复")
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
                Text("@\(comment.replyToName)")
                    .font(.caption.weight(.medium))
                    .foregroundStyle(Color(red: 0.98, green: 0.55, blue: 0.2))
            }
            Text("：\(comment.text)")
                .font(.caption)
                .foregroundStyle(.primary)
                .lineLimit(2)
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
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                .contentShape(Rectangle())
                .onTapGesture(perform: onTap)
                .accessibilityLabel("动态图片")
        } else {
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(GlassTokens.bubbleSystem)
                .frame(height: 120)
                .overlay(
                    Label("图片无法显示", systemImage: "photo")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                )
                .accessibilityLabel("图片无法显示")
        }
    }
}

struct MomentFullscreenImage: View {
    let imageId: String
    var onClose: () -> Void

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            let path = AppGraphClient.shared.momentImagePath(imageId: imageId)
            if let uiImage = path.isEmpty ? nil : UIImage(contentsOfFile: path) {
                Image(uiImage: uiImage)
                    .resizable()
                    .scaledToFit()
                    .ignoresSafeArea()
            } else {
                Text("图片无法显示").foregroundStyle(.white)
            }
            VStack {
                HStack {
                    Spacer()
                    Button {
                        onClose()
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.title2)
                            .foregroundStyle(.white.opacity(0.8))
                    }
                    .accessibilityLabel("关闭图片")
                    .padding()
                }
                Spacer()
            }
        }
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

    init(onFinished: @escaping (Bool) -> Void) {
        self.onFinished = onFinished
    }

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 14) {
                Text("发布动态")
                    .font(.title3.weight(.semibold))
                    .padding(.top, 8)

                TextEditor(text: $text)
                    .frame(minHeight: 140)
                    .padding(8)
                    .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(GlassTokens.bubbleSystem))
                    .overlay(alignment: .topLeading) {
                        if text.isEmpty {
                            Text("说点什么…（记录和干员们的日常）")
                                .font(.body)
                                .foregroundStyle(.tertiary)
                                .padding(.horizontal, 13)
                                .padding(.vertical, 16)
                                .allowsHitTesting(false)
                        }
                    }
                    .accessibilityLabel("动态正文")

                if let pickedImage {
                    ZStack(alignment: .topTrailing) {
                        Image(uiImage: pickedImage)
                            .resizable()
                            .scaledToFill()
                            .frame(height: 160)
                            .frame(maxWidth: .infinity)
                            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                        Button {
                            self.pickedImage = nil
                            pickedItem = nil
                        } label: {
                            Image(systemName: "xmark.circle.fill")
                                .font(.title3)
                                .foregroundStyle(.white, .black.opacity(0.6))
                        }
                        .padding(6)
                        .accessibilityLabel("移除图片")
                    }
                }

                HStack(spacing: 10) {
                    PhotosPicker(selection: $pickedItem, matching: .images, photoLibrary: .shared()) {
                        Label(pickedImage == nil ? "选择图片" : "更换图片", systemImage: "photo")
                            .font(.subheadline)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 8)
                            .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(GlassTokens.bubbleSystem))
                    }
                    .accessibilityLabel("选择图片")

                    if isIngesting {
                        ProgressView()
                    }
                    Spacer()
                }

                if let errorText {
                    Text(errorText)
                        .font(.footnote)
                        .foregroundStyle(.red)
                        .accessibilityLabel("发布错误")
                }

                Spacer()

                HStack {
                    Text("\(text.count) 字")
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                    Spacer()
                    Button {
                        dismiss()
                    } label: {
                        Text("取消").font(.body)
                    }
                    .accessibilityLabel("取消发布")
                    .padding(.trailing, 8)
                    Button {
                        Task { await publish() }
                    } label: {
                        if isPublishing || isIngesting {
                            ProgressView().frame(width: 20, height: 20)
                        } else {
                            Text("发布").fontWeight(.semibold)
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(GlassTokens.brandOrange)
                    .disabled(isPublishing || isIngesting || (text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && pickedImage == nil))
                    .accessibilityLabel("确认发布")
                }
            }
            .padding(16)
            .navigationBarTitleDisplayMode(.inline)
            .onChange(of: pickedItem) { _, item in
                guard let item else { return }
                Task { await loadPhoto(item) }
            }
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
        NavigationStack {
            VStack(spacing: 0) {
                if isLoading {
                    Spacer()
                    ProgressView("读取评论…")
                    Spacer()
                } else if comments.isEmpty {
                    Spacer()
                    EmptyStateView(
                        title: "还没有评论",
                        message: "说点什么，让干员知道你的想法。",
                        systemImage: "bubble.right",
                        actionTitle: nil,
                        action: nil
                    )
                    .padding(.horizontal, 20)
                    Spacer()
                } else {
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 10) {
                            ForEach(comments) { comment in
                                HStack(alignment: .top, spacing: 8) {
                                    RoleAvatar(avatarId: "", name: comment.authorName, size: 30)
                                    VStack(alignment: .leading, spacing: 3) {
                                        HStack(spacing: 4) {
                                            Text(comment.authorName)
                                                .font(.caption.weight(.semibold))
                                                .foregroundStyle(GlassTokens.brandOrange)
                                            if !comment.replyToName.isEmpty {
                                                Text("回复 @\(comment.replyToName)")
                                                    .font(.caption2)
                                                    .foregroundStyle(.tertiary)
                                            }
                                            Spacer()
                                            Text(comment.createdAtText)
                                                .font(.caption2)
                                                .foregroundStyle(.tertiary)
                                        }
                                        Text(comment.text)
                                            .font(.subheadline)
                                            .fixedSize(horizontal: false, vertical: true)
                                        Button("回复") {
                                            replyTarget = comment
                                            draft = ""
                                        }
                                        .font(.caption2)
                                        .foregroundStyle(GlassTokens.brandOrange)
                                        .accessibilityLabel("回复 \(comment.authorName)")
                                    }
                                }
                                .padding(.leading, comment.parentCommentId > 0 ? 18 : 0)
                                .padding(.vertical, 4)
                                Divider()
                            }
                        }
                        .padding(14)
                    }
                }

                if let replyTarget {
                    HStack {
                        Text("回复 @\(replyTarget.authorName)")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Spacer()
                        Button("取消") { self.replyTarget = nil }
                            .font(.caption)
                            .accessibilityLabel("取消回复")
                    }
                    .padding(.horizontal, 14)
                    .padding(.top, 6)
                }

                if let errorText {
                    Text(errorText)
                        .font(.caption)
                        .foregroundStyle(.red)
                        .padding(.horizontal, 14)
                }

                HStack(spacing: 8) {
                    TextField(resolveTextFieldPlaceholder(), text: $draft, axis: .vertical)
                        .lineLimit(1...3)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 8)
                        .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(GlassTokens.bubbleSystem))
                        .accessibilityLabel("评论输入")
                    Button {
                        Task { await send() }
                    } label: {
                        if isSending {
                            ProgressView().frame(width: 22, height: 22)
                        } else {
                            Image(systemName: "paperplane.fill")
                                .font(.system(size: 16, weight: .semibold))
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(GlassTokens.brandOrange)
                    .disabled(isSending || draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    .accessibilityLabel("发送评论")
                }
                .padding(12)
            }
            .navigationTitle("评论")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭") { dismiss() }
                        .accessibilityLabel("关闭评论")
                }
            }
            .task { await reload() }
        }
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
