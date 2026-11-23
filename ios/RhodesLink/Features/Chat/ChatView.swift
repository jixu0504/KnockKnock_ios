//
//  ChatView.swift
//  聊天页（Phase 3/4）：标准聊天软件布局（参考微信 / QQ）。
//
//  布局：
//  ① 顶部：系统导航栏（返回 + 角色名 + 状态副标题），**不再使用页面内浮动玻璃状态卡**
//  ② 中部：消息时间线（LazyVStack + 稳定 Int64 id；真实 chat_messages 分页；时间分隔条；气泡非玻璃）
//  ③ 底部：**仅** 输入栏（附件 + 输入框 + 发送/停止），聊天页隐藏 Tab Bar
//
//  玻璃纪律：聊天内容区不使用玻璃；玻璃只保留在输入栏背景与导航 chrome（系统提供），
//  避免页面内出现无意义的椭圆/胶囊玻璃框。
//

import SwiftUI
import Combine
import PhotosUI

struct ChatView: View {
    @StateObject private var model: ChatViewModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// 首屏滚到底完成后才允许上滑分页（否则初始布局时顶部 header 的 onAppear 会误触发预加载）
    @State private var didInitialScroll = false
    /// T42：图片附件选择（+ → PhotosPicker → shared 媒体入库 → 引擎发送）
    @State private var pickedPhotoItem: PhotosPickerItem?
    @State private var isPhotoPickerPresented = false
    /// T42：等待删除确认的消息（本地删除）
    @State private var pendingDeleteMessage: ChatMessage?
    /// T55：聊天模式选择弹窗（线上 / 线下 / 导演）
    @State private var isModePickerPresented = false

    init(sessionId: String) {
        _model = StateObject(wrappedValue: ChatViewModel(sessionId: sessionId))
    }

    var body: some View {
        timeline
            .background(chatBackground)
            .navigationTitle("")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) {
                    navTitle
                }
                // T55：聊天模式切换——右上角常驻当前模式徽标，点击弹出「线上 / 线下 / 导演」三选一
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        isModePickerPresented = true
                    } label: {
                        HStack(spacing: 3) {
                            Text(model.modeBadgeText)
                                .font(.caption.weight(.medium))
                            Image(systemName: "chevron.down")
                                .font(.system(size: 8, weight: .bold))
                        }
                        .foregroundStyle(GlassTokens.brandOrange)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(GlassTokens.brandOrange.opacity(0.12), in: Capsule())
                    }
                    .buttonStyle(.plain)
                    .disabled(model.isModeBusy)
                    .accessibilityLabel("切换聊天模式，当前\(model.modeText)")
                    .accessibilityHint("线上 / 线下 / 导演，切换后下一轮回复生效")
                }
            }
            .confirmationDialog("切换模式", isPresented: $isModePickerPresented, titleVisibility: .visible) {
                ForEach(ChatViewModel.chatModes) { item in
                    Button {
                        Task { await model.setMode(item.code) }
                    } label: {
                        Text(item.code == model.mode ? "\(item.icon) \(item.label)（当前）" : "\(item.icon) \(item.label)")
                    }
                }
                Button("取消", role: .cancel) {}
            } message: {
                Text("线上：只写发送的文字；线下：面对面场景互动；导演：由你描述场景与事件。切换后下一轮回复生效。")
            }
            // 聊天详情页不显示底部 Tab Bar（微信/QQ 同款：进入会话即全屏沉浸）
            .toolbar(.hidden, for: .tabBar)
            .safeAreaInset(edge: .bottom, spacing: 0) {
                VStack(spacing: 0) {
                    if let err = model.errorText {
                        HStack(spacing: 6) {
                            Image(systemName: "exclamationmark.circle.fill")
                                .foregroundStyle(.red)
                            Text(err)
                                .font(.footnote)
                                .lineLimit(2)
                            Spacer(minLength: 0)
                            Button { model.clearError() } label: {
                                Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
                            }
                            .buttonStyle(.plain)
                        }
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .background(.bar)
                    }
                    if let quoted = model.quotedMessage {
                        quoteBar(for: quoted)
                    }
                    GlassInputBar(
                        text: $model.draft,
                        isStreaming: model.isStreaming,
                        onSend: { model.send() },
                        onCancel: { model.cancel() },
                        onAddAttachment: { isPhotoPickerPresented = true }
                    )
                }
            }
            // 键盘避让由 GlassInputBar 显式监听抬升（此处禁用系统自动推导，避免双重偏移）
            .ignoresSafeArea(.keyboard, edges: .bottom)
            .task {
                await model.load()
                // T42 消息操作自检（--chat-ops-selftest；真实引擎走撤回/重试/重说/继续/图片）
                if ChatOpsAutomation.isEnabled {
                    Task { await ChatOpsAutomation.runIfNeeded(model: model) }
                }
                // 图片发送自检钩子（仅 --image-send-demo；走与 PhotosPicker 同一条 sendImage 路径）
                if ProcessInfo.processInfo.arguments.contains("--image-send-demo") {
                    let renderer = UIGraphicsImageRenderer(size: CGSize(width: 320, height: 320))
                    let image = renderer.image { ctx in
                        UIColor.systemTeal.setFill()
                        ctx.fill(CGRect(x: 0, y: 0, width: 320, height: 320))
                        UIColor.white.setFill()
                        ctx.fill(CGRect(x: 80, y: 80, width: 160, height: 160))
                    }
                    if let data = image.jpegData(compressionQuality: 0.8) {
                        ImageSendLog.add("image-send-demo generated bytes=\(data.count)")
                        model.sendImage(data, caption: "")
                    } else {
                        ImageSendLog.add("image-send-demo FAILED to encode jpeg")
                    }
                }
                // T55 模式切换 UI 演示/取证（仅 --mode-switch-ui-demo / --mode-switch-menu）：
                // 自动展开模式弹窗并依次切三模式，供 xcrun simctl 定时截图；对用户路径零影响。
                if ModeSwitchAutomation.isEnabled || ModeSwitchAutomation.isMenuShotEnabled {
                    await ModeSwitchAutomation.run(
                        model: model,
                        presentPicker: { isModePickerPresented = true },
                        dismissPicker: { isModePickerPresented = false }
                    )
                }
                // T59 气泡分段自检钩子（仅 --bubble-split-selftest）
                if BubbleSplitAutomation.isEnabled {
                    Task { await BubbleSplitAutomation.runIfNeeded(model: model) }
                }
                // 历史分页自动化钩子（仅 --history-load-more；用于无 GUI 触控的模拟器证据）
                if ProcessInfo.processInfo.arguments.contains("--history-load-more") {
                    for _ in 0..<50 where !model.paginationEnabledForUI {
                        try? await Task.sleep(nanoseconds: 100_000_000)
                    }
                    SelfTestLog.add("history automation: loadMore start loaded=\(model.loadedCount)/\(model.totalCount) hasMore=\(model.hasMoreHistory) enabled=\(model.paginationEnabledForUI)")
                    await model.loadMore()
                    await model.loadMore()
                    SelfTestLog.add("history automation: done loaded=\(model.loadedCount)/\(model.totalCount) hasMore=\(model.hasMoreHistory)")
                }
            }
            .onReceive(NotificationCenter.default.publisher(for: .shellSelfTestSendMessage)) { notification in
                model.send(notification.object as? String)
            }
            // T42 图片附件：PhotosPicker → Data → 缩放 → shared 媒体入库 → 引擎 sendImageMessage
            .photosPicker(
                isPresented: $isPhotoPickerPresented,
                selection: $pickedPhotoItem,
                matching: .images,
                photoLibrary: .shared()
            )
            .onChange(of: pickedPhotoItem) { _, item in
                guard let item else { return }
                Task {
                    do {
                        guard let data = try await item.loadTransferable(type: Data.self) else {
                            SelfTestLog.add("chat image picker FAILED (no data)")
                            ImageSendLog.add("FAILED: PhotosPicker returned no data")
                            return
                        }
                        SelfTestLog.add("chat image picker bytes=\(data.count)")
                        ImageSendLog.add("picker bytes=\(data.count)")
                        model.sendImage(data, caption: model.draft)
                    } catch {
                        SelfTestLog.add("chat image picker FAILED error=\(error.localizedDescription)")
                        ImageSendLog.add("FAILED: PhotosPicker loadTransferable \(error.localizedDescription)")
                    }
                    pickedPhotoItem = nil
                }
            }
            // T42 本地删除二次确认（对齐原版危险操作确认习惯）
            .alert("删除这条消息？", isPresented: deleteAlertBinding) {
                Button("删除", role: .destructive) {
                    if let message = pendingDeleteMessage { model.deleteLocal(message) }
                    pendingDeleteMessage = nil
                }
                Button("取消", role: .cancel) { pendingDeleteMessage = nil }
            } message: {
                Text("仅从本机记录中删除，不会撤回或通知对方。此操作不可恢复。")
            }
            // T42 图片全屏查看
            .fullScreenCover(item: $model.fullscreenImage) { message in
                FullScreenImageView(
                    url: model.imageURL(for: message),
                    caption: message.text,
                    onClose: { model.fullscreenImage = nil }
                )
            }
            // T42 操作结果轻提示（复制/撤回/重说等 1.8s 自动消失）
            .overlay(alignment: .top) {
                if let feedback = model.actionFeedback {
                    Text(feedback)
                        .font(.footnote.weight(.medium))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 7)
                        .background(Capsule().fill(Color.black.opacity(0.72)))
                        .padding(.top, 8)
                        .transition(.opacity)
                        .accessibilityLabel("操作结果：\(feedback)")
                }
            }
            .onReceive(NotificationCenter.default.publisher(for: .chatOpsSelftestRefresh)) { _ in
                Task { await model.refresh() }
            }
    }

    // MARK: - T42 消息长按菜单（对齐 Android MessageList.kt:532-563 的菜单项与条件）

    @ViewBuilder
    private func messageMenu(for message: ChatMessage, copyText: String? = nil) -> some View {
        Button {
            if let copyText {
                model.copyText(copyText, messageId: message.id)
            } else {
                model.copyText(message)
            }
        } label: {
            Label("复制", systemImage: "doc.on.doc")
        }
        if message.side != .system {
            Button {
                model.quote(message)
            } label: {
                Label("引用", systemImage: "quote.bubble")
            }
        }
        // 撤回：自己的消息（系统提示除外）；引擎整条撤回 = 删行 + turn 终态
        if message.side == .outgoing && message.id > 0 {
            Button(role: .destructive) {
                model.recall(message)
            } label: {
                Label("撤回", systemImage: "arrow.uturn.backward")
            }
        }
        // 重试：未送达 / 图片识别失败（引擎 retryFailedMessage / resumeImageReply）
        if message.isSendFailed || message.isFailedImage {
            Button {
                model.retry(message)
            } label: {
                Label("重试", systemImage: "arrow.clockwise")
            }
        }
        if message.isAIMessage {
            Button {
                model.regenerate(message)
            } label: {
                Label("重说", systemImage: "arrow.triangle.2.circlepath")
            }
            Button {
                model.continueGenerating(message)
            } label: {
                Label("继续说", systemImage: "forward.end")
            }
        }
        if message.id > 0 {
            Button(role: .destructive) {
                pendingDeleteMessage = message
            } label: {
                Label("删除（本地）", systemImage: "trash")
            }
        }
    }

    /// 引用条（占位：引擎暂无 replyTo，发送时按普通消息发送并记录说明）
    private func quoteBar(for message: ChatMessage) -> some View {
        HStack(spacing: 8) {
            Image(systemName: "quote.bubble")
                .font(.system(size: 13))
                .foregroundStyle(GlassTokens.brandOrange)
            Text("引用 \(message.side == .outgoing ? "我" : model.title)：\(message.text.replacingOccurrences(of: "\n", with: " ").prefix(40))")
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)
            Spacer(minLength: 4)
            Button {
                model.clearQuote()
            } label: {
                Image(systemName: "xmark.circle.fill")
                    .foregroundStyle(.tertiary)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("取消引用")
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(.bar)
        .overlay(alignment: .top) {
            Rectangle().fill(Color.primary.opacity(0.08)).frame(height: 0.5)
        }
    }

    private var deleteAlertBinding: Binding<Bool> {
        Binding(
            get: { pendingDeleteMessage != nil },
            set: { if !$0 { pendingDeleteMessage = nil } }
        )
    }

    // MARK: 背景（标准聊天外观：浅色浅灰 / 深色近黑，不用环境光斑）

    private var chatBackground: some View {
        GlassTokens.chatBackground
            .ignoresSafeArea()
    }

    // MARK: 导航栏标题（角色名 + 在线/模式状态）

    private var navTitle: some View {
        VStack(spacing: 1) {
            Text(model.title)
                .font(.headline)
                .lineLimit(1)
            HStack(spacing: 4) {
                Circle()
                    .fill(model.isStreaming ? GlassTokens.brandOrange : GlassTokens.onlineDot)
                    .frame(width: 6, height: 6)
                Text(model.statusLine)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                if model.isDemoMode {
                    Text("演示")
                        .font(.system(size: 9, weight: .bold))
                        .foregroundStyle(GlassTokens.brandOrange)
                        .padding(.horizontal, 5)
                        .padding(.vertical, 1)
                        .background(GlassTokens.brandOrange.opacity(0.16), in: Capsule())
                }
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(model.title)，\(model.statusLine)")
    }

    // MARK: 时间线（LazyVStack，气泡非玻璃）

    private var timeline: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 8) {
                    // 加载更早消息：仅在加载中显示小菊花（微信/QQ 同款，不显示说明性文案）
                    if model.hasMoreHistory && model.isLoadingMore {
                        ProgressView()
                            .controlSize(.small)
                            .padding(.vertical, 10)
                    }

                    if model.messages.isEmpty && !model.isLoadingHistory {
                        emptyState
                    } else {
                        // T59：渲染派生显示项（ai_json 一条消息按旁白/台词段展开成多条独立气泡）
                        ForEach(model.displayItems) { item in
                            if item.showsTimeSeparator {
                                TimeSeparator(text: item.timeText)
                            }
                            displayRow(item)
                                .id(item.id)
                                .onAppear { model.loadMoreIfNeeded(currentID: item.messageId) }
                                .contextMenu { displayMenu(for: item) }
                        }
                    }

                    Color.clear
                        .frame(height: 1)
                        .id("timeline-bottom")
                }
                .padding(.horizontal, 12)
                .padding(.top, 10)
                .padding(.bottom, 12)
            }
            .scrollIndicators(.hidden)
            .scrollDismissesKeyboard(.interactively)
            // 只在「末尾新增」时滚到底（发送/首屏），前插更早消息时不打断阅读位置；
            // T59：以最后一个显示项 id/文本为准（一条消息展开多段后仍能跟随打字机滚动）。
            .onChange(of: model.displayItems.last?.id) { _, _ in
                scrollToBottom(proxy)
                if !didInitialScroll {
                    didInitialScroll = true
                    // 首屏滚动到底完成后再延迟开启分页：LazyVStack 初始布局的 onAppear
                    // 可能在滚到底后一拍才派发，立即开启会误触发一次预加载。
                    Task {
                        try? await Task.sleep(nanoseconds: 600_000_000)
                        model.enablePagination()
                    }
                }
            }
            .onChange(of: model.displayItems.last?.text ?? "") { _, _ in
                if model.isStreaming { scrollToBottom(proxy) }
            }
            // 前插更早一页后，把原最早一条消息滚回顶部锚点（保持阅读位置；锚点 = 首段 item id）
            .onChange(of: model.prependGeneration) { _, _ in
                if let anchor = model.lastPrependAnchor {
                    proxy.scrollTo(model.scrollTargetID(forMessageID: anchor), anchor: .top)
                }
            }
            .task {
                if TimelineStress.isEnabled {
                    try? await Task.sleep(nanoseconds: 300_000_000)
                    scrollToBottom(proxy, animated: false)
                }
            }
        }
    }

    // MARK: - T59 显示项渲染（旁白/台词交错，每段一个气泡）

    @ViewBuilder
    private func displayRow(_ item: ChatDisplayItem) -> some View {
        switch item.type {
        case .narration:
            // 旁白：居中浅色卡片、无头像，与台词气泡按段顺序交错
            NarrationBubble(text: item.text, isStreaming: item.isStreaming)
        case .system:
            MessageBubble(text: item.text, side: .system)
        case .image:
            imageRow(item)
        case .dialogue:
            // 台词：角色侧头像 + 气泡（角色侧=接收方向）
            HStack(alignment: .top, spacing: 8) {
                RoleAvatar(avatarId: model.roleAvatarId, name: model.title, size: 34)
                MessageBubble(
                    text: item.text,
                    side: .incoming,
                    isStreaming: item.isStreaming,
                    isRecalled: item.isRecalled
                )
            }
        case .text:
            let side: BubbleSide = item.side == .outgoing
                ? .outgoing
                : (item.side == .system ? .system : .incoming)
            MessageBubble(
                text: item.text,
                side: side,
                isStreaming: item.isStreaming,
                isRecalled: item.isRecalled
            )
        }
    }

    /// 图片消息行：仍按整条消息渲染（不拆段），点击全屏（T42 路径不变）
    @ViewBuilder
    private func imageRow(_ item: ChatDisplayItem) -> some View {
        let message = model.baseMessage(for: item)
        let side: BubbleSide = item.side == .outgoing ? .outgoing : .incoming
        MessageBubble(
            text: item.text,
            side: side,
            isStreaming: item.isStreaming,
            isRecalled: item.isRecalled,
            imageURL: message.flatMap { model.imageURL(for: $0) },
            isImageMessage: true,
            onImageTap: {
                guard let message, model.imageURL(for: message) != nil else { return }
                model.fullscreenImage = message
                SelfTestLog.add("chat ops fullscreen image messageId=\(message.id)")
            }
        )
    }

    /// 长按菜单：复制当前段文本；其它操作按所属 messageId 落到整条消息（撤回/重说/删除等语义不变）。
    /// 旁白段与 Android 一致只给「复制」（旁白不属于可撤回的台词段）。
    @ViewBuilder
    private func displayMenu(for item: ChatDisplayItem) -> some View {
        if item.type == .narration {
            // 旁白段：只复制旁白本身（与 Android 旁白长按菜单一致）
            Button {
                model.copyText(item)
            } label: {
                Label("复制", systemImage: "doc.on.doc")
            }
        } else if let message = model.baseMessage(for: item) {
            // 台词/图片/系统/纯文本：完整菜单，操作按所属 messageId 落到整条消息
            messageMenu(for: message, copyText: item.text)
        }
    }

    private var emptyState: some View {
        VStack(spacing: 10) {
            Image(systemName: "bubble.left.and.bubble.right")
                .font(.system(size: 40, weight: .light))
                .foregroundStyle(.tertiary)
            Text("还没有消息")
                .font(.subheadline.weight(.medium))
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 120)
    }

    private func scrollToBottom(_ proxy: ScrollViewProxy, animated: Bool = true) {
        guard !model.messages.isEmpty else { return }
        if reduceMotion || !animated {
            proxy.scrollTo("timeline-bottom", anchor: .bottom)
        } else {
            withAnimation(.easeOut(duration: 0.18)) {
                proxy.scrollTo("timeline-bottom", anchor: .bottom)
            }
        }
    }
}

/// 时间分隔条（居中灰色小字，微信/QQ 同款）
struct TimeSeparator: View {
    var text: String

    var body: some View {
        Text(text)
            .font(.caption2)
            .foregroundStyle(.tertiary)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 6)
    }
}

/// T42 图片全屏查看：黑底 + 等比缩放 + 关闭按钮（本地文件走 UIImage，远程走 AsyncImage）。
struct FullScreenImageView: View {
    let url: URL?
    let caption: String
    var onClose: () -> Void

    @State private var image: UIImage?

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .ignoresSafeArea()
            } else if let url, !url.isFileURL {
                AsyncImage(url: url) { phase in
                    switch phase {
                    case .success(let loaded):
                        loaded.resizable().scaledToFit()
                    case .failure:
                        unavailable
                    default:
                        ProgressView().tint(.white)
                    }
                }
            } else {
                unavailable
            }

            VStack {
                HStack {
                    Spacer()
                    Button(action: onClose) {
                        Image(systemName: "xmark")
                            .font(.system(size: 15, weight: .bold))
                            .foregroundStyle(.white)
                            .frame(width: 34, height: 34)
                            .background(Circle().fill(Color.white.opacity(0.18)))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("关闭图片")
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                Spacer()
                if !caption.isEmpty {
                    Text(caption)
                        .font(.footnote)
                        .foregroundStyle(.white.opacity(0.85))
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 24)
                        .padding(.bottom, 24)
                }
            }
        }
        .task(id: url) {
            guard let url, url.isFileURL else { return }
            let path = url.path
            image = await Task.detached(priority: .userInitiated) {
                UIImage(contentsOfFile: path)
            }.value
        }
        .onTapGesture { onClose() }
    }

    private var unavailable: some View {
        VStack(spacing: 8) {
            Image(systemName: "photo")
                .font(.system(size: 40, weight: .light))
            Text("图片无法显示")
                .font(.footnote)
        }
        .foregroundStyle(.white.opacity(0.7))
    }
}
