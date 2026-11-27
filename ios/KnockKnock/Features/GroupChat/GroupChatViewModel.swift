//
//  GroupChatViewModel.swift
//  T43 群聊页状态：群详情/成员 + 真实群消息分页（GroupChatBridge）+ 真实 GroupChatEngine 发送/取消
//  + 自动互动开关 + 分段撤回 / 重试。
//
//  关键差异（与私聊 ChatViewModel）：
//  - 同一条 `ai_json` 行会被拆成多位发言人的多个气泡（senderName + 心情 + 旁白）；
//  - 消息来自 `GroupChatBridge.page`，发送走 `GroupChatBridge.send`（引擎落库 `ai_json` 整包）；
//  - 打开群聊时 `setActiveGroup` 告知 shared（未读自增排除当前群），离开时清空。
//

import Foundation
import UIKit

/// 时间线展示行（一条 DB 行可展开成多段；id 稳定用于 ForEach/scrollTo）
struct GroupBubbleRow: Identifiable, Hashable {
    enum Kind: Hashable {
        case incoming
        case outgoing
        case system
        case narration
    }

    let id: String
    let messageId: Int64
    let segmentIndex: Int
    let kind: Kind
    let senderName: String
    let text: String
    let emotion: String
    let isRecalled: Bool
    let timeText: String
    let createdAt: Date
    let type: String
    let imageId: String

    var bubbleSide: BubbleSide {
        switch kind {
        case .incoming, .narration: return .incoming
        case .outgoing: return .outgoing
        case .system: return .system
        }
    }
}

@MainActor
final class GroupChatViewModel: ObservableObject {
    @Published private(set) var messages: [GroupMessageItem] = []
    @Published private(set) var rows: [GroupBubbleRow] = []
    @Published private(set) var detail: GroupDetailItem?
    @Published var draft: String = ""
    @Published private(set) var isStreaming = false
    @Published private(set) var streamingText = ""
    @Published private(set) var hasLoaded = false
    @Published private(set) var isLoading = false
    @Published private(set) var isLoadingMore = false
    @Published private(set) var hasMore = false
    @Published private(set) var errorText: String?
    @Published private(set) var isDemo = false
    /// 群聊模式切换进行中（防重复点击）
    @Published private(set) var isModeBusy = false
    @Published private(set) var actionFeedback: String?
    @Published private(set) var isActionBusy = false
    @Published private(set) var isAutoBusy = false
    /// 成员列表 Sheet
    @Published var showMembers = false
    /// 图片全屏（群图片消息；本卡主要文本，保留渲染入口）
    @Published var fullscreenImage: GroupBubbleRow?

    let sessionId: String
    private let bridge: AppGraphClient
    private static let pageSize = 50
    private var cursor: Int64 = 0
    private var cancellable: GroupSendHandle?
    private var feedbackTask: Task<Void, Never>?
    private var lastSendAt: Date = .distantPast
    private var lastSentText: String = ""
    private var nextOptimisticId: Int64 = -1

    init(sessionId: String, bridge: AppGraphClient = AppGraphClient.shared) {
        self.sessionId = sessionId
        self.bridge = bridge
    }

    var title: String { detail?.name ?? "群聊" }

    var subtitle: String {
        guard let detail else { return "群聊" }
        let muted = detail.mutedCount > 0 ? " · \(detail.mutedCount) 人禁言" : ""
        return "\(detail.memberCount) 位成员\(muted) · \(detail.modeText)"
    }

    /// 当前群聊模式（online / offline / director），来自 chat_sessions.mode。
    var mode: String { (detail?.mode ?? "online").lowercased() }

    /// 模式短标签（与私聊口径一致：线上 / 线下 / 导演）。
    var modeText: String {
        ChatViewModel.chatModes.first(where: { $0.code == mode })?.label ?? "线上"
    }

    /// 切换群聊模式：写 chat_sessions.mode → 重读群详情刷新 UI；下一轮发送按新规则生效。
    func setMode(_ newMode: String) async {
        let normalized = newMode.lowercased()
        guard ChatViewModel.chatModes.contains(where: { $0.code == normalized }) else { return }
        guard normalized != mode, !isModeBusy else { return }
        let old = mode
        isModeBusy = true
        defer { isModeBusy = false }
        let error = await bridge.setGroupMode(sessionId: sessionId, mode: normalized)
        if let error {
            errorText = "切换模式失败：\(error)"
            GroupSelfTestLog.add("group mode switch FAILED session=\(sessionId) \(old)->\(normalized) error=\(error)")
            return
        }
        if case .success(let item) = await bridge.loadGroupDetail(sessionId: sessionId) {
            detail = item
        }
        showFeedback("已切换到\(modeText)模式")
        GroupSelfTestLog.add("group mode switch session=\(sessionId) \(old)->\(normalized) ok")
    }

    var canSend: Bool {
        !isStreaming && !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var historySummary: String {
        let total = messages.filter { $0.id > 0 }.count
        return total > 0 ? "群消息 · \(total) 条（真实 DB）" : "群消息 · 空"
    }

    // MARK: - 加载

    func load() async {
        guard !hasLoaded else { return }
        hasLoaded = true
        isLoading = true
        defer { isLoading = false }
        bridge.setActiveGroup(sessionId: sessionId)
        isDemo = bridge.isGroupDemoMode()
        // 打开群聊即标记已读（与 Android GroupDetailScreen 进入群聊清零未读同语义；T44 私聊同款链路）
        _ = await bridge.markSessionRead(id: sessionId)

        switch await bridge.loadGroupDetail(sessionId: sessionId) {
        case .success(let item):
            detail = item
        case .failure(let error):
            errorText = "群聊详情加载失败：\(error.message)"
            GroupSelfTestLog.add("group view detail FAILED session=\(sessionId) error=\(error.message)")
        }

        switch await bridge.loadGroupMessagePage(sessionId: sessionId, beforeId: 0, limit: Self.pageSize) {
        case .success(let page):
            messages = page.items.reversed()
            cursor = page.nextBeforeId
            hasMore = page.hasMore
            rebuildRows()
            GroupSelfTestLog.add(
                "group view page1 session=\(sessionId) count=\(page.items.count) hasMore=\(page.hasMore) " +
                "total=\(page.totalCount) newestId=\(page.items.first?.id ?? 0)"
            )
        case .failure(let error):
            errorText = "群消息加载失败：\(error.message)"
            GroupSelfTestLog.add("group view page1 FAILED error=\(error.message)")
        }
        GroupSelfTestLog.add(
            "group view loaded session=\(sessionId) title=\(title) members=\(detail?.memberCount ?? 0) " +
            "auto=\(detail?.autoEnabled ?? false) status=\(detail?.autoStatus ?? "-") demo=\(isDemo)"
        )
    }

    /// 上滑加载更早一页（前插；稳定 id 去重）
    func loadMore() async {
        guard hasMore, !isLoadingMore, !isLoading, cursor > 0 else { return }
        isLoadingMore = true
        defer { isLoadingMore = false }
        switch await bridge.loadGroupMessagePage(sessionId: sessionId, beforeId: cursor, limit: Self.pageSize) {
        case .success(let page):
            let existing = Set(messages.map(\.id))
            let older = page.items.reversed().filter { !existing.contains($0.id) }
            messages = older + messages
            if page.nextBeforeId > 0 { cursor = page.nextBeforeId }
            hasMore = page.hasMore && !older.isEmpty
            rebuildRows()
            GroupSelfTestLog.add("group view pageMore prepended=\(older.count) hasMore=\(page.hasMore) cursor=\(cursor)")
        case .failure(let error):
            errorText = "加载更早消息失败：\(error.message)"
        }
    }

    func loadMoreIfNeeded(currentRowID: String) {
        guard hasMore, !isLoadingMore else { return }
        guard rows.first(where: { !$0.isOptimistic })?.id == currentRowID else { return }
        Task { await loadMore() }
    }

    /// 重新拉取详情 + 最新一页（发送后/操作后）
    /// 刷新群聊列表。
    /// - Parameter dropping: 刚刚撤回/删除的消息 id；这些行必须从「更早已加载」缓存里剔除，
    ///   否则 `olderKept` 会按 id 把它们原样回填（首屏只取最新 50 条，删旧消息时必然残留）。
    func refresh(dropping: Set<Int64> = []) async {
        if case .success(let item) = await bridge.loadGroupDetail(sessionId: sessionId) {
            detail = item
        }
        switch await bridge.loadGroupMessagePage(sessionId: sessionId, beforeId: 0, limit: Self.pageSize) {
        case .success(let page):
            let fresh = page.items.reversed().filter { !dropping.contains($0.id) }
            let oldestFresh = fresh.first?.id ?? Int64.max
            let olderKept = messages.filter { $0.id > 0 && $0.id < oldestFresh && !dropping.contains($0.id) }
            let pending = messages.filter { $0.id < 0 }
            messages = olderKept + fresh + pending
            cursor = page.nextBeforeId
            hasMore = page.hasMore && !olderKept.isEmpty
            rebuildRows()
            GroupSelfTestLog.add("group view refresh total=\(page.totalCount) loaded=\(messages.count) newest=\(page.items.first?.id ?? 0)")
        case .failure(let error):
            GroupSelfTestLog.add("group view refresh FAILED error=\(error.message)")
        }
    }

    /// 直接从 DB 读最新一页（自检用：区分「内存里消失」和「DB 里真的删掉」）。
    func reloadPageFromDB() async -> Result<[GroupMessageItem], BridgeError> {
        switch await bridge.loadGroupMessagePage(sessionId: sessionId, beforeId: 0, limit: Self.pageSize) {
        case .success(let page): return .success(page.items)
        case .failure(let error): return .failure(error)
        }
    }

    // MARK: - 发送（真实 GroupChatEngine）

    func send(_ overrideText: String? = nil) {
        let text = (overrideText ?? draft).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !isStreaming else { return }
        let now = Date()
        if text == lastSentText, now.timeIntervalSince(lastSendAt) < 0.8 {
            GroupSelfTestLog.add("group view send ignored duplicate text=\(text)")
            return
        }
        lastSentText = text
        lastSendAt = now
        if overrideText == nil { draft = "" }
        errorText = nil
        streamingText = ""
        isStreaming = true

        // 乐观用户气泡（负数 id；onDone 刷新后被真实 DB 行替换）
        let optimisticId = nextOptimisticId
        nextOptimisticId -= 1
        messages.append(
            GroupMessageItem(
                optimisticId: optimisticId,
                sessionId: sessionId,
                senderName: "我",
                text: text
            )
        )
        rebuildRows()
        GroupSelfTestLog.add("group view send text=\(text) optimisticId=\(optimisticId)")

        cancellable = bridge.sendGroupChat(
            sessionId: sessionId,
            text: text,
            onDelta: { [weak self] delta in
                Task { @MainActor in
                    guard let self else { return }
                    self.streamingText += delta
                    self.rebuildRows()
                }
            },
            onDone: { [weak self] result in
                Task { @MainActor in self?.finish(result) }
            }
        )
    }

    func cancel() {
        cancellable?.cancel()
        cancellable = nil
        bridge.cancelGroupChat()
        isStreaming = false
        streamingText = ""
        rebuildRows()
        GroupSelfTestLog.add("group view cancel session=\(sessionId)")
    }

    private func finish(_ result: GroupSendResultItem) {
        guard isStreaming else {
            GroupSelfTestLog.add("group view done ignored ok=\(result.ok)")
            return
        }
        isStreaming = false
        streamingText = ""
        isDemo = result.isDemo
        if !result.ok {
            let reason = result.error.isEmpty ? "本轮未生成可见回复" : result.error
            errorText = reason
            showFeedback("发送失败：\(reason)")
        } else {
            showFeedback("群成员已回应")
        }
        GroupSelfTestLog.add(
            "group view send done ok=\(result.ok) user=\(result.userMessageId) ai=\(result.aiMessageId) " +
            "speakers=\(result.speakersCsv) narration=\(result.narrationCount) chars=\(result.text.count) error=\(result.error)"
        )
        Task { [weak self] in
            await self?.refresh()
        }
    }

    // MARK: - 成员 / 自动互动 / 消息操作

    func openMembers() {
        showMembers = true
        GroupSelfTestLog.add("group view members opened session=\(sessionId) count=\(detail?.memberCount ?? 0)")
    }

    func toggleAutoChat(_ enabled: Bool) {
        guard !isAutoBusy else { return }
        isAutoBusy = true
        Task {
            let result = await bridge.setGroupAutoChat(sessionId: sessionId, enabled: enabled)
            isAutoBusy = false
            switch result {
            case .success(let status):
                showFeedback(enabled ? "已开启自动互动" : "已关闭自动互动")
                GroupSelfTestLog.add("group view auto enabled=\(enabled) status=\(status)")
                if case .success(let item) = await bridge.loadGroupDetail(sessionId: sessionId) { detail = item }
            case .failure(let error):
                errorText = "切换自动互动失败：\(error.message)"
                GroupSelfTestLog.add("group view auto FAILED enabled=\(enabled) error=\(error.message)")
            }
        }
    }

    /// 撤回自己发的群消息（仅 outgoing 行；整条移除）。
    func recallOwn(row: GroupBubbleRow) {
        runOperation("撤回", dropping: [row.messageId]) { [weak self] in
            guard let self else { return "已取消" }
            return await self.bridge.recallGroupMessage(sessionId: self.sessionId, messageId: row.messageId)
        }
    }

    /// 本地删除群消息（仅 outgoing 行；整条移除）。
    func deleteLocal(row: GroupBubbleRow) {
        runOperation("删除", dropping: [row.messageId]) { [weak self] in
            guard let self else { return "已取消" }
            return await self.bridge.deleteGroupMessage(sessionId: self.sessionId, messageId: row.messageId)
        }
    }

    func recallSegment(row: GroupBubbleRow) {
        runOperation("撤回分段", dropping: [row.messageId]) { [weak self] in
            guard let self else { return "已取消" }
            return await self.bridge.recallGroupSegment(
                sessionId: self.sessionId,
                messageId: row.messageId,
                segmentIndex: Int32(row.segmentIndex)
            )
        }
    }

    func retry(row: GroupBubbleRow) {
        runOperation("重试") { [weak self] in
            guard let self else { return "已取消" }
            return await self.bridge.retryGroupMessage(sessionId: self.sessionId, messageId: row.messageId)
        }
    }

    func copy(row: GroupBubbleRow) {
        UIPasteboard.general.string = row.text
        showFeedback("已复制")
        GroupSelfTestLog.add("group view copy messageId=\(row.messageId) segment=\(row.segmentIndex)")
    }

    func imageURL(for row: GroupBubbleRow) -> URL? {
        guard !row.imageId.isEmpty else { return nil }
        let path = bridge.imagePath(for: row.imageId)
        guard !path.isEmpty else { return nil }
        if path.hasPrefix("http://") || path.hasPrefix("https://") { return URL(string: path) }
        return URL(fileURLWithPath: path)
    }

    private func runOperation(
        _ name: String,
        dropping: Set<Int64> = [],
        _ operation: @escaping () async -> String?
    ) {
        guard !isActionBusy else { return }
        isActionBusy = true
        Task {
            let error = await operation()
            isActionBusy = false
            if let error {
                errorText = "\(name)失败：\(error)"
                showFeedback("\(name)失败")
                GroupSelfTestLog.add("group view \(name) FAILED error=\(error)")
            } else {
                showFeedback("\(name)完成")
                GroupSelfTestLog.add("group view \(name) ok dropped=\(dropping.count)")
            }
            await refresh(dropping: dropping)
        }
    }

    func clearError() { errorText = nil }

    func onDisappear() {
        bridge.setActiveGroup(sessionId: "")
    }

    private func showFeedback(_ text: String) {
        actionFeedback = text
        feedbackTask?.cancel()
        feedbackTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 1_800_000_000)
            guard !Task.isCancelled else { return }
            self?.actionFeedback = nil
        }
    }

    // MARK: - 行展开

    private func rebuildRows() {
        var result: [GroupBubbleRow] = []
        for message in messages {
            if message.id < 0 {
                result.append(
                    GroupBubbleRow(
                        id: "optimistic-\(message.id)",
                        messageId: message.id,
                        segmentIndex: 0,
                        kind: .outgoing,
                        senderName: "我",
                        text: message.text,
                        emotion: "",
                        isRecalled: false,
                        timeText: "",
                        createdAt: message.createdAt,
                        type: "text",
                        imageId: ""
                    )
                )
                continue
            }
            if message.isSystem {
                result.append(
                    GroupBubbleRow(
                        id: "\(message.id)-system",
                        messageId: message.id,
                        segmentIndex: 0,
                        kind: .system,
                        senderName: "系统",
                        text: message.text,
                        emotion: "",
                        isRecalled: message.isRecalled,
                        timeText: message.timePayload,
                        createdAt: message.createdAt,
                        type: message.type,
                        imageId: ""
                    )
                )
                continue
            }
            if !message.isAI {
                result.append(
                    GroupBubbleRow(
                        id: "\(message.id)-0",
                        messageId: message.id,
                        segmentIndex: 0,
                        kind: message.isMe ? .outgoing : .incoming,
                        senderName: message.isMe ? "我" : message.senderName,
                        text: message.text,
                        emotion: "",
                        isRecalled: message.isRecalled,
                        timeText: message.timePayload,
                        createdAt: message.createdAt,
                        type: message.type,
                        imageId: message.imageId
                    )
                )
                continue
            }
            if message.segments.isEmpty {
                result.append(
                    GroupBubbleRow(
                        id: "\(message.id)-0",
                        messageId: message.id,
                        segmentIndex: 0,
                        kind: .incoming,
                        senderName: message.senderName,
                        text: message.text,
                        emotion: "",
                        isRecalled: message.isRecalled,
                        timeText: message.timePayload,
                        createdAt: message.createdAt,
                        type: message.type,
                        imageId: ""
                    )
                )
                continue
            }
            for segment in message.segments {
                if segment.isRecalled { continue }
                let kind: GroupBubbleRow.Kind = segment.isNarration ? .narration : .incoming
                let emotionSuffix = segment.emotion.isEmpty ? "" : "（\(segment.emotion)）"
                result.append(
                    GroupBubbleRow(
                        id: "\(message.id)-\(segment.index)",
                        messageId: message.id,
                        segmentIndex: segment.index,
                        kind: kind,
                        senderName: segment.speakerName + emotionSuffix,
                        text: segment.text,
                        emotion: segment.emotion,
                        isRecalled: segment.isRecalled,
                        timeText: message.timePayload,
                        createdAt: message.createdAt,
                        type: message.type,
                        imageId: ""
                    )
                )
            }
        }
        if isStreaming, !streamingText.isEmpty {
            result.append(
                GroupBubbleRow(
                    id: "streaming",
                    messageId: 0,
                    segmentIndex: 0,
                    kind: .incoming,
                    senderName: "群成员",
                    text: streamingText,
                    emotion: "",
                    isRecalled: false,
                    timeText: "",
                    createdAt: .now,
                    type: "streaming",
                    imageId: ""
                )
            )
        }
        rows = result
    }
}

private extension GroupBubbleRow {
    var isOptimistic: Bool { messageId < 0 || messageId == 0 }
}

// MARK: - 群消息的乐观构造

extension GroupMessageItem {
    /// 乐观用户气泡（负数 id，仅 UI 占位；发送回调后由真实 DB 行替换）
    init(optimisticId: Int64, sessionId: String, senderName: String, text: String) {
        self.init(
            id: optimisticId,
            sessionId: sessionId,
            senderName: senderName,
            isMe: true,
            type: "text",
            text: text,
            imageId: "",
            timePayload: "",
            timestamp: Int64(Date().timeIntervalSince1970 * 1000),
            mode: "online",
            isRecalled: false,
            segments: []
        )
    }

    init(
        id: Int64,
        sessionId: String,
        senderName: String,
        isMe: Bool,
        type: String,
        text: String,
        imageId: String,
        timePayload: String,
        timestamp: Int64,
        mode: String,
        isRecalled: Bool,
        segments: [GroupMessageSegmentItem]
    ) {
        self.id = id
        self.sessionId = sessionId
        self.senderName = senderName
        self.isMe = isMe
        self.type = type
        self.text = text
        self.imageId = imageId
        self.timePayload = timePayload
        self.timestamp = timestamp
        self.mode = mode
        self.isRecalled = isRecalled
        self.segments = segments
    }
}
