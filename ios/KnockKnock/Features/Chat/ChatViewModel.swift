//
//  ChatViewModel.swift
//  聊天页状态：真实历史消息（T34 MessageHistoryBridge 分页）+ 发送/取消 + 打字机流。
//
//  T34 接线：
//  - 打开会话 → `load()`：会话元数据（SessionDto）+ 首屏历史 50 条（id 倒序返回后翻转成时间正序）；
//  - 上滑到顶 → `loadMore()`：以 `nextBeforeId` 为游标取更早一页并前插（稳定 id 去重）；
//  - 发送 → 乐观插入（负数临时 id）+ 引擎整包落库后 onDone → 刷新最新一页（保留已加载的更早消息）；
//  - 所有桥接回调在 Main 队列；分页请求在 shared 后台队列，UI 不阻塞。
//
//  T44 未读链路：
//  - 打开会话时告知 shared「当前查看会话」（ChatEngineBridge.setActiveSession）+ markRead 清零未读；
//  - 离开会话页由 ChatListView 的 chatPath 观察清空 activeSession（ChatView.swift 不在本任务写域）；
//  - 之后到达的非当前会话 AI 回复由 shared 消费 UnreadHint 自增未读（见 ChatEngineBridge）。
//

import Foundation
import UIKit
import Combine

@MainActor
final class ChatViewModel: ObservableObject {
    @Published private(set) var messages: [ChatMessage] = []
    @Published var draft: String = ""
    @Published private(set) var isStreaming = false
    @Published private(set) var session: SessionItem?
    @Published private(set) var errorText: String?
    @Published private(set) var statusLine = "连接中…"
    @Published private(set) var hasLoaded = false
    /// T37：当前是否演示模式（未配置模型供应商/无 Key），聊天页标题栏显示「演示」标记
    @Published private(set) var isDemoMode = false
    /// T55：当前聊天模式（online / offline / director）；切换写 `chat_sessions.mode`，
    /// 下一轮提示词按 T18 三套模板 + 旁白协议生效（线上无旁白，线下/导演含【旁白硬规则】）。
    @Published private(set) var mode: String = "online"
    /// 模式切换请求在飞（防连点）
    @Published private(set) var isModeBusy = false

    // MARK: T42 消息操作状态（长按菜单 / 引用条 / 图片查看）

    /// 引用态（占位）：引擎暂无 replyTo/引用发送能力（Android 私聊同样不支持），
    /// 仅展示「引用 xxx」并允许取消；发送时按普通消息发送并记录未支持说明，不伪造引用字段。
    @Published var quotedMessage: ChatMessage?
    /// 操作结果轻提示（复制成功/撤回完成等，1.8s 自动消失）
    @Published private(set) var actionFeedback: String?
    /// 消息操作进行中（防止连点）
    @Published private(set) var isActionBusy = false
    /// 图片全屏查看（Conversation 内点击图片消息）
    @Published var fullscreenImage: ChatMessage?
    /// 图片媒体 ID → 本机 URL 缓存
    private var imageURLCache: [String: URL] = [:]
    private var feedbackTask: Task<Void, Never>?
    /// 外部数据变更通知（导入聊天记录 / 备份恢复）→ 已打开的聊天页刷新
    private var notificationCancellables: Set<AnyCancellable> = []
    /// 正在进行中的外部变更刷新（避免导入完成通知与页面 load 并发重复载入）
    private var externalRefreshTask: Task<Void, Never>?

    // 历史分页状态
    @Published private(set) var isLoadingHistory = false
    @Published private(set) var isLoadingMore = false
    @Published private(set) var hasMoreHistory = false
    @Published private(set) var loadedCount = 0
    @Published private(set) var totalCount = 0
    /// 前插更早一页后用于保持滚动位置的锚点（原最早一条消息 id）
    @Published private(set) var lastPrependAnchor: Int64?
    @Published private(set) var prependGeneration = 0

    /// 自检专用：复现修复前的合并逻辑（olderKept 只按 id、操作成功不做本地精确变更），
    /// 仅启动参数 `--chat-legacy-merge` 启用（配合 `--chat-refresh-selftest` 出 FAIL 基线证据），
    /// 正常运行路径恒为 false，零影响。
    static var isLegacyMergeSimulationEnabled: Bool {
        ProcessInfo.processInfo.arguments.contains("--chat-legacy-merge")
    }

    private let bridge: AppGraphClient
    private let sessionId: String
    /// 防重复发送：上次发送时间与文本
    private var lastSendAt: Date = .distantPast
    private var lastSentText: String = ""
    private static let pageSize = 50
    private var cancellable: ChatSendHandle?
    private var nextTempID: Int64 = -1
    private var streamingMessageID: Int64?
    /// 下一页游标（shared 侧 nextBeforeId；0 表示没有更早）
    private var historyCursor: Int64 = 0
    /// 首屏滚动到底后由视图开启分页（避免初始布局误触发预加载）
    private var isPaginationEnabled = false

    init(sessionId: String, bridge: AppGraphClient = AppGraphClient.shared) {
        self.sessionId = sessionId
        self.bridge = bridge
        observeExternalDataChanges()
    }

    /// 外部数据变更（聊天记录导入 / 备份恢复）监听：
    /// - 聊天页在导航栈/Tab 切换时**不会被销毁**（hasLoaded 守卫会挡住二次 load），
    ///   导入完成后必须由通知主动刷新，否则用户回到聊天页看到的仍是导入前的旧时间线；
    /// - 会话列表（ChatListView）也监听同一通知刷新预览/时间。
    private func observeExternalDataChanges() {
        NotificationCenter.default
            .publisher(for: .chatDataDidChangeExternally)
            .sink { [weak self] note in
                Task { @MainActor [weak self] in
                    await self?.handleExternalDataChange(note)
                }
            }
            .store(in: &notificationCancellables)
    }

    /// 通知处理：只处理本会话（无 sessionId 的全局变更也刷新），带导入 id 区间时立即定位导入块。
    private func handleExternalDataChange(_ note: Notification) async {
        let changedSessionId = note.userInfo?[ChatExternalChangeKey.sessionId] as? String ?? ""
        guard changedSessionId.isEmpty || changedSessionId == sessionId else {
            SelfTestLog.add("chat external change ignored session=\(changedSessionId) current=\(sessionId)")
            return
        }
        let info = ChatExternalChangeInfo(userInfo: note.userInfo)
        SelfTestLog.add(
            "chat external change session=\(sessionId) source=\(info.source) imported=\(info.importedCount) " +
            "hasLoaded=\(hasLoaded) firstImportedId=\(info.firstImportedId)"
        )
        guard hasLoaded else { return }
        await reloadAfterExternalDataChange(importInfo: info)
    }

    /// 外部变更后的权威刷新（不受 hasLoaded 守卫限制）：
    /// 1) 会话元数据（列表预览同源）；2) 最新一页（含更早已加载消息的合并）；
    /// 3) 导入记录的 id 区间 → 直接取回并并入时间线（导入记录时间戳通常早于首屏窗口，
    ///    仅刷新首屏在长会话里根本看不到它们），同时设置前插锚点让时间线滚动到导入块。
    func reloadAfterExternalDataChange(importInfo: ChatExternalChangeInfo) async {
        let task = Task { [weak self] in
            guard let self else { return }
            if let item = await self.bridge.findSession(id: self.sessionId) {
                self.session = item
                self.mode = item.mode.lowercased()
                if !self.isStreaming { self.statusLine = "\(item.modeText) · 私聊" }
            }
            await self.refreshAfterSend()
            if importInfo.importedCount > 0 {
                await self.mergeImportedMessages(
                    firstImportedId: importInfo.firstImportedId,
                    importedCount: importInfo.importedCount,
                    source: importInfo.source
                )
            }
            // 应用后清除「待定位」标记，避免下次进入重复插入
            self.clearPendingImportFocus()
        }
        externalRefreshTask?.cancel()
        externalRefreshTask = task
        await task.value
    }

    var title: String { session?.title ?? "会话" }

    // MARK: - T59 显示项（一条 ai_json 消息 → 多个交错气泡）

    /// 渲染层派生项：时间线只渲染这个数组；`messages` 仍是数据层（长按菜单/操作按 messageId 回落）。
    /// 纯函数派生（`ChatDisplayItemBuilder`），不额外维护状态，@Published messages 变化即重建。
    var displayItems: [ChatDisplayItem] { ChatDisplayItemBuilder.build(messages: messages) }

    /// 稳定滚动锚点：某条消息首段 item id（LazyVStack `.id(...)` 用）。
    func scrollTargetID(forMessageID messageId: Int64) -> Int64 {
        ChatDisplayItemBuilder.itemID(messageId: messageId, segmentIndex: 0)
    }

    /// 显示项 → 所属整条消息（操作菜单/图片全屏/预览用）。
    func baseMessage(for item: ChatDisplayItem) -> ChatMessage? {
        messages.first { $0.id == item.messageId }
    }

    /// 角色侧头像值（语句气泡头像用；会话元数据未加载时为空 → 首字占位）。
    var roleAvatarId: String { session?.avatarId ?? "" }
    /// 当前私聊对应的角色 id（点对方头像进角色简介）。
    var operatorId: String { session?.operatorId ?? "" }

    /// 自检/自动化钩子用（sessionId 为 init 注入的会话绑定，不随会话切换变化）
    var sessionIdForAutomation: String { sessionId }

    /// 清除错误提示（View 层 alert 关闭时调用）
    func clearError() { errorText = nil }

    var canSend: Bool {
        !isStreaming && !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    /// 状态条第二行：真实历史条数（截图/自检证据）
    var historySummary: String {
        guard totalCount > 0 || loadedCount > 0 else { return "历史 · 空" }
        return "历史 · \(loadedCount)/\(totalCount) 条（真实 DB）"
    }

    // MARK: 加载（会话 + 首屏历史）

    func load() async {
        guard !hasLoaded else { return }
        hasLoaded = true

        if let item = await bridge.findSession(id: sessionId) {
            session = item
            mode = item.mode.lowercased()
            statusLine = "\(item.modeText) · 私聊"
            // T44 未读链路：打开会话 = 「当前查看会话」+ 已读清零
            // （对齐 Android ChatViewModel `_currentSession` / `markSessionRead`）。
            // 先告知 shared，再 markRead：避免「打开瞬间到达的回复」被误计为未读。
            bridge.chatEngineBridge.setActiveSession(sessionId: sessionId)
            let unreadBefore = item.unreadCount
            let readError = await bridge.markSessionRead(id: sessionId)
            if let refreshed = await bridge.findSession(id: sessionId) { session = refreshed }
            SelfTestLog.add(
                "chat unread active session=\(sessionId) unreadBefore=\(unreadBefore) " +
                "markReadError=\(readError ?? "-") unreadAfter=\(session?.unreadCount ?? -1)"
            )
        } else {
            errorText = "会话 \(sessionId) 不存在（shared DB 中未找到）"
            statusLine = "会话不可用"
        }

        let providerStatus = bridge.providerStatus()
        isDemoMode = providerStatus.isDemo
        SelfTestLog.add("chat provider mode=\(providerStatus.mode) summary=\(providerStatus.summary)")

        await loadFirstPage()

        if TimelineStress.isEnabled {
            // T12 性能基线：覆盖为 2,000 条本地压力数据（独立启动参数，正常路径不触发）
            messages = TimelineStress.makeMessages()
            hasMoreHistory = false
            loadedCount = messages.count
            totalCount = messages.count
            statusLine = "性能基线 · \(messages.count) 条本地消息（LazyVStack / T12）"
        } else if let current = session, !isStreaming {
            statusLine = "\(current.modeText) · 私聊"
        }

        SelfTestLog.add(
            "chat loaded session=\(sessionId) title=\(session?.title ?? "-") messages=\(messages.count) " +
            "total=\(totalCount) hasMore=\(hasMoreHistory) stress=\(TimelineStress.isEnabled)"
        )

        // 导入聊天记录后首次进入会话：把导入块并入时间线并定位
        // （导入记录通常时间戳更早、不在首屏窗口内，不处理会给人「导入没生效」的错觉）。
        await applyPendingImportFocusIfNeeded()

        // 消息操作/外部变更刷新自检钩子（仅 --chat-refresh-selftest；对用户路径零影响）
        if ChatRefreshAutomation.isEnabled {
            Task { await ChatRefreshAutomation.runIfNeeded(model: self) }
        }
    }

    /// 首屏历史（最新 pageSize 条）；乐观占位（负数 id）保留在末尾。
    private func loadFirstPage() async {
        isLoadingHistory = true
        defer { isLoadingHistory = false }

        switch await bridge.loadMessagePage(sessionId: sessionId, beforeId: 0, limit: Self.pageSize) {
        case .success(let page):
            let ascending = page.items.reversed().map(ChatMessage.init(item:))
            let pending = messages.filter { $0.id < 0 }
            messages = ascending + pending
            historyCursor = page.nextBeforeId
            hasMoreHistory = page.hasMore
            loadedCount = messages.filter { $0.id > 0 }.count
            totalCount = page.totalCount
            SelfTestLog.add(
                "chat history page1 session=\(sessionId) count=\(page.items.count) hasMore=\(page.hasMore) " +
                "nextBeforeId=\(page.nextBeforeId) total=\(page.totalCount) " +
                "oldestId=\(page.items.last?.id ?? 0) newestId=\(page.items.first?.id ?? 0)"
            )
            if let newest = page.items.first {
                SelfTestLog.add(
                    "chat history newest id=\(newest.id) isMe=\(newest.isMe) type=\(newest.type) " +
                    "time=\(newest.timePayload) recalled=\(newest.isRecalled) text=\(newest.text.prefix(40))"
                )
            }
        case .failure(let error):
            errorText = "历史加载失败：\(error.message)"
            statusLine = "历史加载失败"
            SelfTestLog.add("chat history page1 FAILED error=\(error.message)")
        }
    }

    /// 重新拉取会话元数据 + 最新一页（T42 消息操作 / 自检刷新用；保留已加载的更早消息）。
    func refresh() async {
        if let item = await bridge.findSession(id: sessionId) {
            session = item
            mode = item.mode.lowercased()
            if !isStreaming { statusLine = "\(item.modeText) · 私聊" }
        }
        await loadFirstPage()
        SelfTestLog.add("chat refresh messages=\(messages.count) total=\(totalCount)")
    }

    // MARK: - 外部数据变更（导入聊天记录 / 备份恢复）→ 立即刷新

    /// 「待定位导入块」在用户设置里的键（导入成功后由设置页写入，聊天页应用后清除）。
    /// 值格式 `<firstImportedId>:<count>`（见 [ChatExternalChangeInfo.markerValue]）。
    static func pendingImportFocusKey(sessionId: String) -> String {
        "chat_import_focus_\(sessionId)"
    }

    /// 清除待定位标记（已应用或数据非法时）。
    private func clearPendingImportFocus() {
        let key = Self.pendingImportFocusKey(sessionId: sessionId)
        if !bridge.settingsString(key: key).isEmpty {
            bridge.setSettingsString(key: key, value: "")
        }
    }

    /// 首次进入会话时消费「待定位导入块」标记：导入发生在聊天页未打开时（设置页导入 → 再进入私聊），
    /// 首屏只取最新 50 条，而导入记录时间戳通常更早 → 用户会以为导入没生效。
    func applyPendingImportFocusIfNeeded() async {
        let key = Self.pendingImportFocusKey(sessionId: sessionId)
        let raw = bridge.settingsString(key: key)
        guard !raw.isEmpty else { return }
        guard let info = ChatExternalChangeInfo(markerValue: raw) else {
            SelfTestLog.add("chat import focus marker INVALID session=\(sessionId) value=\(raw)")
            clearPendingImportFocus()
            return
        }
        SelfTestLog.add(
            "chat import focus pending session=\(sessionId) firstImportedId=\(info.firstImportedId) count=\(info.importedCount)"
        )
        await mergeImportedMessages(
            firstImportedId: info.firstImportedId,
            importedCount: info.importedCount,
            source: "marker"
        )
        clearPendingImportFocus()
    }

    /// 把导入块并入时间线（不改分页/流式语义）：
    /// - 只取最多 [ChatExternalChangeInfo.maxImportedMerge] 条「最新的导入记录」；
    /// - 按 (createdAt,id) 排序去重合并，负数乐观占位始终留在末尾；
    /// - 设置 `lastPrependAnchor` / `prependGeneration`，复用 ChatView 已有的前插锚点滚动逻辑把
    ///   时间线滚到导入块（无需改视图代码）；
    /// - 历史游标不变：上滑分页照旧按游标取页，并以 id 去重避免重复。
    func mergeImportedMessages(firstImportedId: Int64, importedCount: Int, source: String) async {
        guard firstImportedId > 0, importedCount > 0, !TimelineStress.isEnabled else { return }
        let take = min(importedCount, ChatExternalChangeInfo.maxImportedMerge)
        let fromId = firstImportedId + Int64(importedCount - take)
        let result = await bridge.loadMessagesByIdRange(sessionId: sessionId, fromId: fromId, count: take)
        guard case .success(let items) = result, !items.isEmpty else {
            SelfTestLog.add("chat import merge FAILED session=\(sessionId) fromId=\(fromId) take=\(take)")
            return
        }
        let imported = items.map(ChatMessage.init(item:))
        let existingIds = Set(messages.map(\.id))
        let newOnes = imported.filter { !existingIds.contains($0.id) }
        guard !newOnes.isEmpty else {
            SelfTestLog.add(
                "chat import merge noop session=\(sessionId) source=\(source) fetched=\(imported.count) (already in timeline)"
            )
            return
        }
        let pending = messages.filter { $0.id < 0 }
        let reals = (messages.filter { $0.id > 0 } + newOnes)
            .uniqued(by: \.id)
            .sorted { lhs, rhs in
                if lhs.createdAt == rhs.createdAt { return lhs.id < rhs.id }
                return lhs.createdAt < rhs.createdAt
            }
        messages = reals + pending
        loadedCount = reals.count
        totalCount = max(totalCount, loadedCount)
        lastPrependAnchor = newOnes.map(\.id).min()
        prependGeneration += 1
        let firstText = String(newOnes.first?.text.prefix(24) ?? "")
        SelfTestLog.add(
            "chat import merge ok session=\(sessionId) source=\(source) fetched=\(imported.count) inserted=\(newOnes.count) " +
            "messages=\(messages.count) total=\(totalCount) anchor=\(lastPrependAnchor ?? 0) " +
            "firstTime=\(newOnes.first?.timeText ?? "-") firstText=\(firstText)"
        )
        showFeedback("已载入 \(newOnes.count) 条导入聊天记录")
    }

    // MARK: 模式切换（T55：线上 / 线下 / 导演）

    /// 三模式展示顺序与文案（与 Android `ChatInputBar` 模式选择对话框一致：线上/线下/导演）。
    static let chatModes: [ChatModeOption] = [
        ChatModeOption(code: "online", label: "线上", icon: "🟢"),
        ChatModeOption(code: "offline", label: "线下", icon: "🏠"),
        ChatModeOption(code: "director", label: "导演", icon: "🎬"),
    ]

    /// 当前模式的人类可读标签（线上/线下/导演）。
    var modeText: String {
        Self.chatModes.first(where: { $0.code == mode })?.label ?? "线上"
    }

    /// 当前模式的图标（🟢/🏠/🎬，与 Android ChatHeader 一致）。
    var modeIcon: String {
        Self.chatModes.first(where: { $0.code == mode })?.icon ?? "🟢"
    }

    /// 导航栏胶囊文案：「🟢 线上」。
    var modeBadgeText: String { "\(modeIcon) \(modeText)" }

    /// 切换聊天模式：写 `chat_sessions.mode`（shared SessionRepository）→ 重读会话刷新 UI；
    /// 下一轮发送由 shared 引擎重新解析会话 mode 并按 T18 模板构建提示词，即时生效。
    func setMode(_ newMode: String) async {
        let normalized = newMode.lowercased()
        guard Self.chatModes.contains(where: { $0.code == normalized }) else { return }
        guard normalized != mode, !isModeBusy else { return }
        let old = mode
        isModeBusy = true
        defer { isModeBusy = false }
        let error = await bridge.updateSessionMode(id: sessionId, mode: normalized)
        if let error {
            errorText = "切换模式失败：\(error)"
            SelfTestLog.add("chat mode switch FAILED session=\(sessionId) \(old)->\(normalized) error=\(error)")
            return
        }
        mode = normalized
        if let refreshed = await bridge.findSession(id: sessionId) {
            session = refreshed
            mode = refreshed.mode.lowercased()
            if !isStreaming { statusLine = "\(refreshed.modeText) · 私聊" }
        } else if !isStreaming {
            statusLine = "\(modeText) · 私聊"
        }
        showFeedback("已切换到\(modeText)模式")
        SelfTestLog.add(
            "chat mode switched session=\(sessionId) \(old) -> \(mode) " +
            "dbMode=\(session?.mode ?? "-") status=\(statusLine)"
        )
    }

    /// 上滑加载更早一页（前插；稳定 id 去重；记录滚动锚点）。
    func loadMore() async {
        guard isPaginationEnabled else { return }
        guard hasMoreHistory, !isLoadingMore, !isLoadingHistory, historyCursor > 0, !TimelineStress.isEnabled else { return }
        isLoadingMore = true
        defer { isLoadingMore = false }

        let anchor = messages.first(where: { $0.id > 0 })?.id
        switch await bridge.loadMessagePage(sessionId: sessionId, beforeId: historyCursor, limit: Self.pageSize) {
        case .success(let page):
            let existing = Set(messages.map(\.id))
            let older = page.items.reversed().map(ChatMessage.init(item:)).filter { !existing.contains($0.id) }
            messages = older + messages
            if page.nextBeforeId > 0 { historyCursor = page.nextBeforeId }
            hasMoreHistory = page.hasMore && !older.isEmpty
            loadedCount = messages.filter { $0.id > 0 }.count
            totalCount = page.totalCount
            lastPrependAnchor = anchor
            prependGeneration += 1
            SelfTestLog.add(
                "chat history pageMore session=\(sessionId) prepended=\(older.count) hasMore=\(page.hasMore) " +
                "cursor=\(historyCursor) loaded=\(loadedCount)/\(totalCount)"
            )
        case .failure(let error):
            errorText = "加载更早消息失败：\(error.message)"
            SelfTestLog.add("chat history pageMore FAILED error=\(error.message)")
        }
    }

    /// 列表行出现时按需触发分页（仅当前最早一条真实消息触发）。
    func loadMoreIfNeeded(currentID: Int64) {
        guard isPaginationEnabled, hasMoreHistory, !isLoadingMore, !isLoadingHistory else { return }
        guard let firstReal = messages.first(where: { $0.id > 0 })?.id, currentID == firstReal else { return }
        Task { await loadMore() }
    }

    /// 首屏滚动到底后开启上滑分页（由 ChatView 在首次滚到底后延迟调用）。
    func enablePagination() {
        isPaginationEnabled = true
        SelfTestLog.add("chat pagination enabled loaded=\(loadedCount)/\(totalCount) hasMore=\(hasMoreHistory)")
    }

    /// 分页是否已就绪（自动化钩子轮询用）。
    var paginationEnabledForUI: Bool { isPaginationEnabled }

    // MARK: 发送（ChatEngineBridge：整包落库后按 2 字/20ms 回调 onDelta）

    func send(_ overrideText: String? = nil) {
        let text = (overrideText ?? draft).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !isStreaming else { return }
        // 防抖：输入法回车提交 / 按钮点击可能在同一瞬间重复触发，0.8s 内同文本只发一次
        let now = Date()
        if text == lastSentText, now.timeIntervalSince(lastSendAt) < 0.8 {
            SelfTestLog.add("chat send ignored duplicate text=\(text)")
            return
        }
        lastSentText = text
        lastSendAt = now
        if overrideText == nil { draft = "" }
        errorText = nil

        // T42 引用（占位）：引擎无 replyTo 字段 → 引用仅作 UI 展示，不伪造发送字段。
        if let quoted = quotedMessage {
            SelfTestLog.add(
                "chat ops quote send placeholder quotedId=\(quoted.id) — 引擎无 replyTo，本条按普通消息发送"
            )
            quotedMessage = nil
        }

        // 乐观插入：用户气泡 + AI 流式占位（负数临时 id，onDone 刷新后由真实 DB id 替换）
        append(side: .outgoing, text: text, mode: mode)
        let placeholderID = append(side: .incoming, text: "", isStreaming: true, mode: mode, typedCharacters: 0)
        streamingMessageID = placeholderID
        isStreaming = true
        statusLine = "正在回应…"
        SelfTestLog.add("chat send text=\(text) optimisticId=\(placeholderID)")

        cancellable = bridge.sendChat(
            sessionId: sessionId,
            text: text,
            onDelta: { [weak self] delta in
                Task { @MainActor in self?.appendDelta(delta) }
            },
            onReplySegments: { [weak self] segments in
                Task { @MainActor in self?.applyStreamingSegments(segments) }
            },
            onDone: { [weak self] result in
                Task { @MainActor in self?.finish(result) }
            }
        )
    }

    /// 表情目录（19 个绳网表情）。
    func emojiCatalog() -> [EmojiItem] { bridge.emojiCatalog() }

    /// 发送表情（仅线上模式）。表情不走识图：shared 侧用目录里的情绪+画面文字送给模型。
    func sendEmoji(_ emoji: EmojiItem) {
        guard !isStreaming else { return }
        guard mode.lowercased() == "online" else {
            errorText = "表情仅支持线上模式发送"
            SelfTestLog.add("chat emoji blocked mode=\(mode)")
            return
        }
        errorText = nil
        // 乐观插入：表情气泡 + AI 流式占位（与文本发送同构，onDone 后按 DB 行替换）
        append(
            side: .outgoing,
            text: "[表情：\(emoji.label)]",
            mode: mode,
            type: "emoji",
            imageId: "emoji:\(emoji.id)"
        )
        let placeholderID = append(side: .incoming, text: "", isStreaming: true, mode: mode, typedCharacters: 0)
        streamingMessageID = placeholderID
        isStreaming = true
        statusLine = "正在回应…"
        SelfTestLog.add("chat emoji send id=\(emoji.id) label=\(emoji.label) mode=\(mode)")

        cancellable = bridge.sendEmojiChat(
            sessionId: sessionId,
            emojiId: emoji.id,
            onDelta: { [weak self] delta in
                Task { @MainActor in self?.appendDelta(delta) }
            },
            onReplySegments: { [weak self] segments in
                Task { @MainActor in self?.applyStreamingSegments(segments) }
            },
            onDone: { [weak self] result in
                Task { @MainActor in self?.finish(result) }
            }
        )
    }

    /// 取消当前轮次（IosCancellable.cancel + 引擎 generation 失效）
    func cancel() {
        cancellable?.cancel()
        cancellable = nil
        bridge.cancelChat()
        isStreaming = false
        if let id = streamingMessageID, let index = messages.firstIndex(where: { $0.id == id }) {
            messages[index].isStreaming = false
            if messages[index].text.isEmpty { messages[index].text = "（已停止生成）" }
        }
        streamingMessageID = nil
        statusLine = session.map { "\($0.modeText) · 私聊" } ?? "在线"
        SelfTestLog.add("chat cancel session=\(sessionId)")
    }

    // MARK: - T42 消息操作（长按菜单：复制 / 引用 / 撤回 / 重试 / 重说 / 继续说 / 删除本地）

    /// 图片消息 → 本机可渲染 URL（shared 媒体 ID → 绝对路径；http(s) 原样）。
    func imageURL(for message: ChatMessage) -> URL? {
        guard !message.imageId.isEmpty else { return nil }
        if let cached = imageURLCache[message.imageId] { return cached }
        let path = bridge.imagePath(for: message.imageId)
        guard !path.isEmpty else { return nil }
        let url: URL
        if path.hasPrefix("http://") || path.hasPrefix("https://") {
            guard let remote = URL(string: path) else { return nil }
            url = remote
        } else {
            url = URL(fileURLWithPath: path)
        }
        imageURLCache[message.imageId] = url
        return url
    }

    /// T59：复制指定文本（显示段复制用：复制当前段而不是整条消息）。
    func copyText(_ text: String, messageId: Int64) {
        UIPasteboard.general.string = text
        showFeedback("已复制")
        SelfTestLog.add("chat ops copy messageId=\(messageId) chars=\(text.count)")
    }

    /// T59：复制单个显示段（旁白/台词各复制各的；对齐 Android 长按旁白复制旁白内容）。
    func copyText(_ item: ChatDisplayItem) {
        UIPasteboard.general.string = item.text
        showFeedback("已复制")
        SelfTestLog.add(
            "chat ops copy messageId=\(item.messageId) seg=\(item.segmentIndex) type=\(item.type.rawValue) chars=\(item.text.count)"
        )
    }

    /// 复制文本（对齐 Android MessageList.kt:413 复制 → 剪贴板 + Toast「已复制」）。
    func copyText(_ message: ChatMessage) {
        UIPasteboard.general.string = message.text
        showFeedback("已复制")
        SelfTestLog.add("chat ops copy messageId=\(message.id) chars=\(message.text.count)")
    }

    /// 引用（占位）：显示引用条，发送时不携带引用字段（引擎无 replyTo；Android 私聊同样不做引用发送）。
    func quote(_ message: ChatMessage) {
        quotedMessage = message
        showFeedback("已引用（发送暂按普通消息）")
        SelfTestLog.add("chat ops quote placeholder messageId=\(message.id) text=\(message.text.prefix(20))")
    }

    func clearQuote() {
        quotedMessage = nil
        SelfTestLog.add("chat ops quote cleared")
    }

    /// 撤回整条消息（引擎语义 = 删行；DB 行消失，时间线气泡应即时消失）。
    func recall(_ message: ChatMessage) {
        runOperation("撤回", mutation: .remove(message.id)) { [weak self] in
            guard let self else { return "已取消" }
            return await self.bridge.recallMessage(sessionId: self.sessionId, messageId: message.id)
        }
    }

    /// 撤回 AI 消息的单个分段（引擎改写 content 的 recalled 标记；行仍在，时间线需就地换成撤回态）。
    func recallSegment(_ message: ChatMessage, segmentIndex: Int32 = 0) {
        runOperation("撤回分段", mutation: .reload(message.id)) { [weak self] in
            guard let self else { return "已取消" }
            return await self.bridge.recallMessage(
                sessionId: self.sessionId,
                messageId: message.id,
                segmentIndex: segmentIndex
            )
        }
    }

    func retry(_ message: ChatMessage) {
        runOperation("重试", mutation: .reload(message.id)) { [weak self] in
            guard let self else { return "已取消" }
            if message.isFailedImage {
                return await self.bridge.retryImageMessage(sessionId: self.sessionId, messageId: message.id)
            }
            return await self.bridge.retryMessage(sessionId: self.sessionId, messageId: message.id)
        }
    }

    func regenerate(_ message: ChatMessage) {
        runOperation("重说", mutation: .reload(message.id)) { [weak self] in
            guard let self else { return "已取消" }
            return await self.bridge.regenerateMessage(sessionId: self.sessionId, messageId: message.id)
        }
    }

    func continueGenerating(_ message: ChatMessage) {
        runOperation("继续说") { [weak self] in
            guard let self else { return "已取消" }
            return await self.bridge.continueMessage(sessionId: self.sessionId, messageId: message.id)
        }
    }

    /// 本地删除（仅删本机 DB 行）：时间线气泡应即时消失。
    func deleteLocal(_ message: ChatMessage) {
        runOperation("删除", mutation: .remove(message.id)) { [weak self] in
            guard let self else { return "已取消" }
            return await self.bridge.deleteMessageLocal(sessionId: self.sessionId, messageId: message.id)
        }
    }

    /// 操作成功后对本地时间线的精确变更（DB 行已由 shared 落地）：
    /// - `.remove`：撤回整条/本地删除 → 行已删除；
    /// - `.reload`：分段撤回/重试/重说 → 行仍在但内容/type 变了。
    /// 仅靠 `refreshAfterSend` 的首屏合并是**不够**的：比最新一页更早的已加载消息走
    /// `olderKept` 原样保留，被删/被改的行不会消失，必须按 messageId 精确落变更。
    private enum LocalMutation {
        case remove(Int64)
        case reload(Int64)

        var messageIds: Set<Int64> {
            switch self {
            case .remove(let id): return [id]
            case .reload(let id): return [id]
            }
        }

        var removedIds: Set<Int64> {
            switch self {
            case .remove(let id): return [id]
            case .reload: return []
            }
        }
    }

    /// 统一操作执行：Main 队列回调 → 轻提示/错误 → 本地精确变更（即时 UI）→ 刷新最新一页（DB 为唯一事实来源）。
    private func runOperation(
        _ name: String,
        mutation: LocalMutation? = nil,
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
                SelfTestLog.add("chat ops \(name) FAILED error=\(error)")
            } else {
                showFeedback("\(name)完成")
                SelfTestLog.add("chat ops \(name) ok")
                // 先按 messageId 精确落变更：不等下一次网络/分页刷新，气泡立即变（撤回/删除即时生效）。
                if let mutation, !Self.isLegacyMergeSimulationEnabled {
                    await applyLocalMutation(mutation, operation: name)
                }
            }
            await refreshAfterSend(dropping: mutation?.removedIds ?? [])
        }
    }

    /// 操作成功后的本地时间线精确同步（同步读 DB 单条，O(1)）：
    /// - remove：行已删 → 从时间线删除（撤回/删除即时生效，覆盖 olderKept 残留场景）；
    /// - reload：行还在但内容变化 → 用 DB 最新值替换（撤回态/新回复即时生效）。
    private func applyLocalMutation(_ mutation: LocalMutation, operation: String) async {
        switch mutation {
        case .remove(let messageId):
            let before = messages.count
            messages.removeAll { $0.id == messageId }
            let removed = before - messages.count
            loadedCount = messages.filter { $0.id > 0 }.count
            if removed > 0 { totalCount = max(totalCount - removed, loadedCount) }
            SelfTestLog.add(
                "chat ops local remove operation=\(operation) messageId=\(messageId) removed=\(removed) " +
                "messages=\(messages.count) loaded=\(loadedCount)/\(totalCount)"
            )
        case .reload(let messageId):
            let fresh = await bridge.findMessage(id: messageId)
            if let index = messages.firstIndex(where: { $0.id == messageId }) {
                if let fresh {
                    messages[index] = ChatMessage(item: fresh)
                } else {
                    messages.remove(at: index)
                }
            } else if let fresh {
                // 不在当前已加载窗口（极少见）：不做插入，交给 refreshAfterSend 的 DB 刷新
                SelfTestLog.add("chat ops local reload not-loaded operation=\(operation) messageId=\(messageId)")
            }
            let recalled = fresh?.isRecalled ?? false
            let segments = fresh?.segments.count ?? 0
            SelfTestLog.add(
                "chat ops local reload operation=\(operation) messageId=\(messageId) " +
                "exists=\(fresh != nil) recalled=\(recalled) segments=\(segments) messages=\(messages.count)"
            )
        }
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

    // MARK: - T42 图片消息（PhotosPicker → 缩放 → shared 媒体入库 → 引擎发送）

    /// 发送图片：Swift 侧先缩放为 ≤1920px JPEG 0.8（镜像 Android 相机/相册落盘），
    /// 再交 shared `ingestImage` 落 `Application Support/media/chat_images`，最后走引擎 sendImageMessage。
    func sendImage(_ data: Data, caption: String) {
        ImageSendLog.add("sendImage start bytes=\(data.count) caption=\(caption.prefix(20))")
        guard !isStreaming else {
            SelfTestLog.add("chat image ignored (streaming)")
            ImageSendLog.add("ignored: streaming in progress")
            return
        }
        guard let prepared = Self.prepareChatImage(data) else {
            errorText = "无法读取这张图片，请重新选择"
            SelfTestLog.add("chat image prepare FAILED (decode)")
            ImageSendLog.add("FAILED: prepare (decode)")
            return
        }
        let tempURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("rhodes-pick-\(UUID().uuidString).jpg")
        do {
            try prepared.write(to: tempURL, options: .atomic)
        } catch {
            errorText = "图片写入失败：\(error.localizedDescription)"
            SelfTestLog.add("chat image temp write FAILED error=\(error.localizedDescription)")
            ImageSendLog.add("FAILED: temp write \(error.localizedDescription)")
            return
        }
        let captionText = caption.trimmingCharacters(in: .whitespacesAndNewlines)
        draft = ""
        quotedMessage = nil
        errorText = nil
        isStreaming = true
        statusLine = "正在发送图片…"
        SelfTestLog.add("chat image prepare bytes=\(prepared.count) caption=\(captionText) temp=\(tempURL.lastPathComponent)")
        ImageSendLog.add("prepared jpeg=\(prepared.count) temp=\(tempURL.lastPathComponent)")

        Task {
            let ingestion = await bridge.ingestChatImage(sourcePath: tempURL.path)
            try? FileManager.default.removeItem(at: tempURL)
            switch ingestion {
            case .failure(let error):
                isStreaming = false
                statusLine = session.map { "\($0.modeText) · 私聊" } ?? "在线"
                errorText = "图片保存失败：\(error.message)"
                SelfTestLog.add("chat image ingest FAILED error=\(error.message)")
                ImageSendLog.add("FAILED: ingest \(error.message)")
            case .success(let imageId):
                SelfTestLog.add("chat image ingest ok id=\(imageId)")
                ImageSendLog.add("ingest ok id=\(imageId)")
                cancellable = bridge.sendImageChat(
                    sessionId: sessionId,
                    imageId: imageId,
                    caption: captionText,
                    onDelta: { [weak self] delta in
                        Task { @MainActor in self?.appendDelta(delta) }
                    },
                    onReplySegments: { [weak self] segments in
                        Task { @MainActor in self?.applyStreamingSegments(segments) }
                    },
                    onDone: { [weak self] result in
                        Task { @MainActor in
                            ImageSendLog.add("engine done ok=\(result.ok) error=\(result.error) aiId=\(result.aiMessageId)")
                            self?.finish(result)
                        }
                    }
                )
            }
        }
    }

    /// ≤1920px + JPEG 0.8（镜像 Android MainActivity.copyChatImageToFiles / prepareImageForModel 缩放口径）。
    private static func prepareChatImage(_ data: Data) -> Data? {
        guard let image = UIImage(data: data) else { return nil }
        let maxDimension: CGFloat = 1920
        let longest = max(image.size.width, image.size.height)
        let scaled: UIImage
        if longest > maxDimension, longest > 0 {
            let ratio = maxDimension / longest
            let size = CGSize(width: image.size.width * ratio, height: image.size.height * ratio)
            let renderer = UIGraphicsImageRenderer(size: size)
            scaled = renderer.image { _ in
                image.draw(in: CGRect(origin: .zero, size: size))
            }
        } else {
            scaled = image
        }
        return scaled.jpegData(compressionQuality: 0.8)
    }

    // MARK: 内部

    @discardableResult
    private func append(
        side: ChatMessage.Side,
        text: String,
        isStreaming: Bool = false,
        mode: String = "",
        typedCharacters: Int = Int.max,
        type: String = "text",
        imageId: String = ""
    ) -> Int64 {
        let id = nextTempID
        nextTempID -= 1
        messages.append(
            ChatMessage(
                id: id,
                side: side,
                text: text,
                isStreaming: isStreaming,
                timeText: Self.nowTimeText(),
                imageId: imageId,
                type: type,
                mode: mode.isEmpty ? self.mode : mode,
                typedCharacters: typedCharacters
            )
        )
        loadedCount = messages.filter { $0.id > 0 }.count
        return id
    }

    /// T59 流式：整包落库后桥先回调可见段（旁白/台词交错），再按 2 字/20ms 回调 delta。
    /// 这里只累计「已到达字符数」；每段的展示前缀由 [ChatDisplayItemBuilder] 按段长度切分，
    /// 因此前面的段先完整出现，最后一段随 delta 增长（每段独立气泡）。
    private func appendDelta(_ delta: String) {
        guard let id = streamingMessageID,
              let index = messages.firstIndex(where: { $0.id == id }) else { return }
        if messages[index].typedCharacters == Int.max {
            messages[index].typedCharacters = 0
        }
        messages[index].typedCharacters += delta.count
        // 段尚未到达（或非 ai_json 通道）时仍累积原始文本，保证占位气泡有内容可显示。
        if messages[index].segments.isEmpty {
            messages[index].text += delta
        }
    }

    /// 流式回复的可见段（桥在打字机开始前回调一次）：记录分段并重置打字进度。
    private func applyStreamingSegments(_ segments: [MessageSegmentItem]) {
        guard let id = streamingMessageID,
              let index = messages.firstIndex(where: { $0.id == id }) else { return }
        guard !segments.isEmpty else { return }
        messages[index].segments = segments
        if messages[index].typedCharacters == Int.max {
            messages[index].typedCharacters = 0
        }
        SelfTestLog.add(
            "chat stream segments messageId=\(id) count=\(segments.count) " +
            "types=\(segments.map(\.type).joined(separator: ",")) typed=\(messages[index].typedCharacters)"
        )
    }

    private func finish(_ result: ChatRoundResult) {
        guard isStreaming else {
            SelfTestLog.add("chat done ignored (cancelled) ok=\(result.ok)")
            return
        }
        isStreaming = false
        cancellable = nil
        isDemoMode = result.isDemo
        let finalID = streamingMessageID
        streamingMessageID = nil

        if let id = finalID, let index = messages.firstIndex(where: { $0.id == id }) {
            var message = messages[index]
            message.isStreaming = false
            message.typedCharacters = Int.max
            if result.ok {
                let final = result.text.trimmingCharacters(in: .whitespacesAndNewlines)
                if !final.isEmpty { message.text = final }
                if message.text.isEmpty { message.text = "（本轮无可见文本）" }
                statusLine = session.map { "\($0.modeText) · 私聊" } ?? "在线"
            } else {
                let reason = result.error.isEmpty ? "本轮未生成可见回复" : result.error
                message.text = "发送失败：\(reason)"
                errorText = reason
                statusLine = "回应失败"
            }
            messages[index] = message
        }

        SelfTestLog.add("chat done ok=\(result.ok) aiId=\(result.aiMessageId) chars=\(result.text.count) error=\(result.error)")

        Task { [weak self] in
            guard let self else { return }
            if let item = await self.bridge.findSession(id: self.sessionId) {
                self.session = item
                if !self.isStreaming {
                    self.statusLine = "\(item.modeText) · 私聊"
                }
            }
            // 发送后刷新：用真实 DB 最新一页替换乐观占位（保留已加载的更早消息）
            await self.refreshAfterSend()
        }
    }

    /// 发送后刷新最新一页；保留比最新页更早的已加载消息，避免时间线跳变。
    /// 发送/操作后刷新最新一页；保留比最新页更早的已加载消息，避免时间线跳变。
    /// - Parameter dropping: 本次操作已从 DB 删除的消息 id。`olderKept` 只按「id 小于最新页最早一条」
    ///   判断保留，被删的**更早消息**（已通过上滑分页加载过）会残留在这里永不消失——
    ///   这正是真机上「撤回/删除旧消息后气泡还在，必须退出重进」的根因，必须按 id 剔除。
    private func refreshAfterSend(dropping removedIds: Set<Int64> = []) async {
        guard !TimelineStress.isEnabled else { return }
        switch await bridge.loadMessagePage(sessionId: sessionId, beforeId: 0, limit: Self.pageSize) {
        case .success(let page):
            let fresh = page.items.reversed().map(ChatMessage.init(item:))
            // 更早已加载消息的保留条件用**时间**而不是 id：最新一页按 timestamp 倒序取 50 条，
            // 而导入的聊天记录 id 更大（MAX(id)+1 起分配）但 timestamp 更早（历史时间），
            // 用 id 比较会把整块导入记录当成「比最新页新」而丢弃。
            let oldestFreshTime = fresh.first?.createdAt ?? .distantFuture
            let oldestFreshId = fresh.first?.id ?? Int64.max
            let freshIds = Set(fresh.map(\.id))
            let legacy = Self.isLegacyMergeSimulationEnabled
            var olderKept = messages.filter { message in
                guard message.id > 0, !freshIds.contains(message.id) else { return false }
                if legacy {
                    // 修复前实现：只按「id 小于最新页最早一条」保留（已删的旧消息残留 = 真机 bug）。
                    return message.id < oldestFreshId
                }
                guard !removedIds.contains(message.id) else { return false }
                return message.createdAt <= oldestFreshTime
            }
            if !removedIds.isEmpty {
                let before = messages.filter { $0.id > 0 }.count - fresh.count
                SelfTestLog.add(
                    "chat merge drop removedIds=\(removedIds.sorted()) olderKept=\(max(before, 0))->\(olderKept.count) " +
                    "stillPresent=\(messages.filter { removedIds.contains($0.id) && !freshIds.contains($0.id) }.map(\.id))"
                )
            }
            // 只保留仍在流式中的乐观气泡；已完成的那一轮已由 DB 真实行替代，
            // 残留的乐观用户气泡/占位气泡必须丢弃，否则同一条消息会显示两次。
            let pending = messages.filter { $0.id < 0 && $0.isStreaming }
            messages = olderKept + fresh + pending
            SelfTestLog.add("chat merge after send droppedOptimistic=\(messages.filter { $0.id < 0 }.count) fresh=\(fresh.count)")
            historyCursor = page.nextBeforeId
            hasMoreHistory = page.hasMore && !olderKept.isEmpty
            loadedCount = messages.filter { $0.id > 0 }.count
            totalCount = page.totalCount
            SelfTestLog.add(
                "chat history refreshed after send session=\(sessionId) page=\(page.items.count) " +
                "olderKept=\(olderKept.count) loaded=\(loadedCount)/\(totalCount) " +
                "newestId=\(page.items.first?.id ?? 0) newestIsMe=\(page.items.first?.isMe ?? false)"
            )
        case .failure(let error):
            SelfTestLog.add("chat history refresh FAILED error=\(error.message)")
        }
    }

    private static func nowTimeText() -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.dateFormat = "HH:mm"
        return formatter.string(from: Date())
    }
}

// MARK: - 聊天数据外部变更（导入聊天记录 / 备份恢复）

extension Notification.Name {
    /// 聊天数据被页面外的流程改写（设置页导入 Markdown 聊天记录、恢复备份等）。
    /// 已打开的聊天页（ChatViewModel）与会话列表（ChatListView）监听后刷新；
    /// userInfo 见 [ChatExternalChangeKey]（可选，缺失时按「全局变更」刷新）。
    static let chatDataDidChangeExternally = Notification.Name("rhodeslink.chat.dataChangedExternally")
}

enum ChatExternalChangeKey {
    static let source = "source"
    static let sessionId = "sessionId"
    static let operatorId = "operatorId"
    /// 本次新增（导入）条数；0/缺失 = 无新增消息，只刷新。
    static let importedCount = "importedCount"
    /// 本次新增消息的第一个 id（导入器连续分配，见 MarkdownChatImportBridge.insertedIds）。
    static let firstImportedId = "firstImportedId"
}

/// 聊天数据外部变更载荷（通知 userInfo 或设置项「待定位导入块」标记的统一解析）。
struct ChatExternalChangeInfo {
    /// 单次最多并入时间线的导入条数（避免一次插入上万条历史拖慢 LazyVStack；其余走上滑分页）。
    static let maxImportedMerge = 500

    var source: String = ""
    var sessionId: String = ""
    var operatorId: String = ""
    var importedCount: Int = 0
    var firstImportedId: Int64 = 0

    init() {}

    init(source: String, sessionId: String, operatorId: String, importedCount: Int, firstImportedId: Int64) {
        self.source = source
        self.sessionId = sessionId
        self.operatorId = operatorId
        self.importedCount = importedCount
        self.firstImportedId = firstImportedId
    }

    init(userInfo: [AnyHashable: Any]?) {
        guard let userInfo else { return }
        source = userInfo[ChatExternalChangeKey.source] as? String ?? "notification"
        sessionId = userInfo[ChatExternalChangeKey.sessionId] as? String ?? ""
        operatorId = userInfo[ChatExternalChangeKey.operatorId] as? String ?? ""
        importedCount = (userInfo[ChatExternalChangeKey.importedCount] as? NSNumber)?.intValue
            ?? (userInfo[ChatExternalChangeKey.importedCount] as? Int) ?? 0
        firstImportedId = (userInfo[ChatExternalChangeKey.firstImportedId] as? NSNumber)?.int64Value
            ?? (userInfo[ChatExternalChangeKey.firstImportedId] as? Int64) ?? 0
    }

    /// 设置项持久化格式 `<firstImportedId>:<count>`（导入时聊天页未打开 → 下次进入消费）。
    init?(markerValue: String) {
        let parts = markerValue.split(separator: ":")
        guard parts.count >= 2,
              let first = Int64(parts[0]),
              let count = Int(parts[1]),
              first > 0,
              count > 0 else { return nil }
        source = "marker"
        firstImportedId = first
        importedCount = count
    }

    var markerValue: String { "\(firstImportedId):\(importedCount)" }
}

// MARK: - 数组去重（保持首次出现顺序）

extension Array {
    /// 按 keyPath 去重（保持首次出现顺序）。导入消息并入时间线时用于避免与已加载行重复。
    func uniqued<Key: Hashable>(by keyPath: KeyPath<Element, Key>) -> [Element] {
        var seen = Set<Key>()
        var result: [Element] = []
        result.reserveCapacity(count)
        for element in self where seen.insert(element[keyPath: keyPath]).inserted {
            result.append(element)
        }
        return result
    }
}

// MARK: - 图片发送诊断日志（无条件写文件，便于真机排查；不参与业务）

enum ImageSendLog {
    static let fileName = "image-send.log"

    static func add(_ line: String) {
        let ts = ISO8601DateFormatter().string(from: Date())
        NSLog("[image-send] %@", line)
        let fm = FileManager.default
        guard let dir = fm.urls(for: .documentDirectory, in: .userDomainMask).first else { return }
        let url = dir.appendingPathComponent(fileName)
        let text = "[\(ts)] \(line)\n"
        guard let data = text.data(using: .utf8) else { return }
        if fm.fileExists(atPath: url.path), let handle = try? FileHandle(forWritingTo: url) {
            handle.seekToEndOfFile()
            handle.write(data)
            try? handle.close()
        } else {
            try? data.write(to: url, options: .atomic)
        }
    }
}
