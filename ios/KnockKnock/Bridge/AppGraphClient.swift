//
//  AppGraphClient.swift
//  Swift 侧执行环境包装（自 T29 Demo 迁移并扩展，T33 正式壳）：
//  - Kotlin 对象（RhodesAppGraph / 各 Bridge / DTO）只在文件内部出现；
//  - SwiftUI 只接触本文件定义的 struct / enum / 方法；
//  - 单例（`AppGraphClient.shared`），整个 App 共用一份 graph 与 DB 连接。
//
//  Kotlin 侧入口：`IosAppGraphKt.makeAppGraph()`（Shared.framework，@ObjCName swiftName）。
//

import Foundation
import Shared

// MARK: - Swift 侧数据模型（与 Shared DTO 一一对应，不向上暴露 Kotlin 类型）

struct SessionItem: Identifiable, Hashable {
    let id: String
    let title: String
    let avatarId: String
    let unreadCount: Int
    let lastMessage: String
    let lastTime: Int64
    let mode: String
    /// T40：置顶（列表顺序已由 shared SQL `ORDER BY isPinned DESC, lastTime DESC` 保证）
    let isPinned: Bool
    /// 会话所属角色 id（私聊点对方头像 → 角色简介页）
    let operatorId: String

    init(dto: SessionDto) {
        id = dto.id
        title = dto.title
        avatarId = dto.avatarId
        unreadCount = Int(dto.unreadCount)
        lastMessage = dto.lastMessage
        lastTime = dto.lastTime
        mode = dto.mode
        isPinned = dto.isPinned
        operatorId = dto.operatorId
    }

    /// 最近一条消息时间（Int64 秒/毫秒自适应格式化）
    var lastTimeText: String {
        guard lastTime > 0 else { return "" }
        let seconds = lastTime > 10_000_000_000 ? Double(lastTime) / 1000 : Double(lastTime)
        let date = Date(timeIntervalSince1970: seconds)
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        if Calendar.current.isDateInToday(date) {
            formatter.dateFormat = "HH:mm"
        } else {
            formatter.dateFormat = "M/d"
        }
        return formatter.string(from: date)
    }

    /// 会话模式的人类可读标签
    var modeText: String {
        switch mode.lowercased() {
        case "online", "": return "线上"
        case "offline": return "线下"
        case "director": return "导演"
        default: return mode
        }
    }
}

struct MemoryItem: Identifiable, Hashable {
    let id: Int64
    let kind: String
    let content: String
    let createdAt: Int64

    init(dto: MemoryDto) {
        id = dto.id
        kind = dto.kind
        content = dto.content
        createdAt = dto.createdAt
    }

    var kindText: String {
        switch kind.uppercased() {
        case "LONG_TERM", "L3": return "长期印象"
        case "SHORT_TERM", "L1": return "短期印象"
        case "MID_TERM", "L2": return "中期印象"
        default: return kind
        }
    }

    var createdAtText: String {
        guard createdAt > 0 else { return "" }
        let seconds = createdAt > 10_000_000_000 ? Double(createdAt) / 1000 : Double(createdAt)
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.dateFormat = "yyyy/M/d HH:mm"
        return formatter.string(from: Date(timeIntervalSince1970: seconds))
    }
}

struct ChatRoundResult: Hashable {
    let ok: Bool
    let text: String
    let error: String
    let aiMessageId: Int64
    /// T37：true = 本轮走了演示模式（未配置模型供应商/无 Key）
    let isDemo: Bool
}

struct BridgeError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

/// 发送轮次句柄（对上层隐藏 Kotlin `IosCancellable`，SwiftUI 层不接触 Shared 类型）
final class ChatSendHandle {
    private let cancellable: IosCancellable

    init(cancellable: IosCancellable) {
        self.cancellable = cancellable
    }

    func cancel() {
        cancellable.cancel()
    }

    var isCancelled: Bool { cancellable.isCancelled() }
}

/// 桥接回调统一在 Main 队列（Kotlin 侧保证），此处用 MainQueue 断言保护。
func assertMainQueue(_ function: String = #function) {
    if !Thread.isMainThread {
        NSLog("[KnockKnock][Bridge] ⚠️ %@ 不在主线程", function)
    }
}

// MARK: - 桥接客户端（单例）

@MainActor
final class AppGraphClient {
    static let shared = AppGraphClient()

    private let graph: RhodesAppGraph

    private init() {
        graph = IosAppGraphKt.makeAppGraph()
        NSLog("[KnockKnock][Bridge] graph created db=%@", graph.databasePath())
    }

    var databasePath: String { graph.databasePath() }

    /// 完整备份桥（T38 设置「数据与备份」页复用 T29 BackupBridge：导出 / 两阶段恢复 / pending 查询）。
    var backupBridge: BackupBridge { graph.backup() }

    /// 数据备份桥（T38：角色卡 + 聊天记录导入导出，见 DataBackupClient.swift）。
    var dataBackupBridge: DataBackupBridge { graph.dataBackup() }

    /// Markdown 聊天记录导入桥（T58）：Swift 包装在 `DataBackupClient.swift` 的 extension 中。
    var markdownImportBridge: MarkdownChatImportBridge { graph.markdownImport() }

    /// 消息历史桥（T34）：Swift 包装在独立文件 `MessageHistoryClient.swift` 中，
    /// 这里只暴露 shared 桥对象供其 extension 使用（不向上导出 Kotlin 类型）。
    var historyBridge: MessageHistoryBridge { graph.history() }

    /// 私聊引擎桥（T42）：Swift 包装在独立文件 `ChatOpsClient.swift` 中，
    /// 这里只暴露 shared 桥对象供其 extension 使用（不向上导出 Kotlin 类型）。
    var chatEngineBridge: ChatEngineBridge { graph.chatEngine() }

    /// 群聊桥（T43）：Swift 包装在独立文件 `GroupChatClient.swift` 中，
    /// 这里只暴露 shared 桥对象供其 extension 使用（不向上导出 Kotlin 类型）。
    var groupBridge: GroupChatBridge { graph.groups() }

    /// 知识库桥（T48）：Swift 包装在独立文件 `KnowledgeClient.swift` 中，
    /// 这里只暴露 shared 桥对象供其 extension 使用（不向上导出 Kotlin 类型）。
    var knowledgeBridge: KnowledgeBaseBridge { graph.knowledge() }

    /// 记忆管理桥（T47）：Swift 包装在独立文件 `MemoryManagementClient.swift` 中，
    /// 这里只暴露 shared 桥对象供其 extension 使用（不向上导出 Kotlin 类型）。
    var memoryBridge: MemoryBridge { graph.memory() }

    /// 动态广场桥（T50）：Swift 包装在独立文件 `MomentsClient.swift` 中，
    /// 这里只暴露 shared 桥对象供其 extension 使用（不向上导出 Kotlin 类型）。
    var momentsBridge: MomentsBridge { graph.moments() }

    /// 干员日记桥（T51）：Swift 包装在独立文件 `DiaryClient.swift` 中，
    /// 这里只暴露 shared 桥对象供其 extension 使用（不向上导出 Kotlin 类型）。
    var diaryBridge: DiaryBridge { graph.diary() }

    /// 干员日记自动生成桥（T61）：Swift 包装在独立文件 `DiaryAutoClient.swift` 中，
    /// 这里只暴露 shared 桥对象供其 extension 使用（不向上导出 Kotlin 类型）。
    var diaryAutoBridge: DiaryAutoGlue { graph.diaryAuto() }

    /// 角色人设编辑桥（T49）：Swift 包装在独立文件 `OperatorClient.swift` 中，
    /// 这里只暴露 shared 桥对象供其 extension 使用（不向上导出 Kotlin 类型）。
    var operatorBridge: OperatorBridge { graph.operators() }

    /// 聊天排行榜 / 消费统计桥（T54）：Swift 包装在独立文件 `RankingClient.swift` 中，
    /// 这里只暴露 shared 桥对象供其 extension 使用（不向上导出 Kotlin 类型）。
    var rankingBridge: RankingBridge { graph.ranking() }

    /// 大家的印象桥（T54）：Swift 包装在独立文件 `ImpressionsClient.swift` 中，
    /// 这里只暴露 shared 桥对象供其 extension 使用（不向上导出 Kotlin 类型）。
    var impressionsBridge: ImpressionsBridge { graph.impressions() }

    /// 关系网编辑桥（T57）：Swift 包装在独立文件 `RelationsClient.swift` 中，
    /// 这里只暴露 shared 桥对象供其 extension 使用（不向上导出 Kotlin 类型）。
    var relationBridge: RelationBridge { graph.relations() }

    /// 用户身份桥（T57）：Swift 包装在独立文件 `ProfileClient.swift` 中，
    /// 这里只暴露 shared 桥对象供其 extension 使用（不向上导出 Kotlin 类型）。
    var identityBridge: IdentityBridge { graph.identity() }

    // MARK: 启动播种（幂等；Phase 3 壳在无会话时调用，正式 App 由 onboarding 取代）

    func bootstrap() async -> (count: Int, error: String?) {
        await withCheckedContinuation { continuation in
            graph.bootstrap { count, error in
                let value = Int(count.intValue)
                continuation.resume(returning: (value, error.isEmpty ? nil : error))
            }
        }
    }

    // MARK: 会话列表（真实 DB，SessionListBridge）

    func loadSessions() async -> Result<[SessionItem], BridgeError> {
        await withCheckedContinuation { continuation in
            graph.sessions().list { dtos, error in
                assertMainQueue()
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else {
                    continuation.resume(returning: .success(dtos.map(SessionItem.init(dto:))))
                }
            }
        }
    }

    func findSession(id: String) async -> SessionItem? {
        await withCheckedContinuation { continuation in
            graph.sessions().find(sessionId: id) { dto, error in
                assertMainQueue()
                if error != nil {
                    continuation.resume(returning: nil)
                } else {
                    continuation.resume(returning: dto.map(SessionItem.init(dto:)))
                }
            }
        }
    }

    // MARK: 会话操作（T40：置顶 / 已读 / 从主页移除；语义对齐 Android SessionViewModel + MainViewModel）

    /// 置顶 / 取消置顶 → shared `SessionRepository.updatePinned`。返回 nil = 成功。
    func pinSession(id: String, pinned: Bool) async -> String? {
        await withCheckedContinuation { continuation in
            graph.sessions().pin(sessionId: id, pinned: pinned) { error in
                assertMainQueue()
                continuation.resume(returning: error)
            }
        }
    }

    /// 切换置顶（读当前状态取反）。返回 nil = 成功。
    func togglePinSession(id: String) async -> String? {
        await withCheckedContinuation { continuation in
            graph.sessions().togglePin(sessionId: id) { error in
                assertMainQueue()
                continuation.resume(returning: error)
            }
        }
    }

    /// 单会话标记已读 → shared `SessionRepository.markSessionRead`。返回 nil = 成功。
    func markSessionRead(id: String) async -> String? {
        await withCheckedContinuation { continuation in
            graph.sessions().markRead(sessionId: id) { error in
                assertMainQueue()
                continuation.resume(returning: error)
            }
        }
    }

    /// T55：切换会话聊天模式（online/offline/director）→ 写 `chat_sessions.mode` +
    /// `settings.last_mode_<operatorId>`；下一轮提示词按 T18 三套模板 + 旁白协议生效。返回 nil = 成功。
    func updateSessionMode(id: String, mode: String) async -> String? {
        await withCheckedContinuation { continuation in
            graph.sessions().updateMode(sessionId: id, mode: mode) { error in
                assertMainQueue()
                continuation.resume(returning: error)
            }
        }
    }

    /// 全部标记已读（原版无确认、直接执行）。返回 nil = 成功。
    func markAllSessionsRead() async -> String? {
        await withCheckedContinuation { continuation in
            graph.sessions().markAllRead { error in
                assertMainQueue()
                continuation.resume(returning: error)
            }
        }
    }

    /// 从主页移除单个会话（settings.hiddenIds；不删库/不删消息）。返回 nil = 成功。
    func hideSession(id: String) async -> String? {
        await withCheckedContinuation { continuation in
            graph.sessions().hide(sessionId: id) { error in
                assertMainQueue()
                continuation.resume(returning: error)
            }
        }
    }

    /// 移除主页所有会话（settings.hiddenIds ∪ 全部会话 id）。返回 nil = 成功。
    func hideAllSessions() async -> String? {
        await withCheckedContinuation { continuation in
            graph.sessions().hideAll { error in
                assertMainQueue()
                continuation.resume(returning: error)
            }
        }
    }

    // MARK: 长期印象（真实 DB，MemoryBridge 只读）

    func loadMemories() async -> Result<[MemoryItem], BridgeError> {
        await withCheckedContinuation { continuation in
            graph.memory().list { dtos, error in
                assertMainQueue()
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else {
                    continuation.resume(returning: .success(dtos.map(MemoryItem.init(dto:))))
                }
            }
        }
    }

    // MARK: 设置（真实 NSUserDefaults，同步）

    func settingsString(key: String) -> String { graph.settings().getString(key: key) }

    func setSettingsString(key: String, value: String) {
        graph.settings().putString(key: key, value: value)
    }

    func setDarkMode(_ value: Bool) { graph.settings().putDarkMode(value: value) }

    func appearanceSkin() -> String { graph.settings().appearanceSkin() }

    func settingsSnapshot() -> String { graph.settings().snapshot() }

    /// 通用 Int 设置读。`SettingsBridge.getInt` 的缺省固定为 0，无法区分「从未写入」与合法值
    /// （保留期 `-1` = 永久），故缺省由 Swift 侧按 shared 语义补；写入仍走 SettingsBridge（备份 / Android 同键）。
    func getIntSetting(key: String, default defaultValue: Int) -> Int {
        guard UserDefaults.standard.object(forKey: key) != nil else { return defaultValue }
        return Int(graph.settings().getInt(key: key))
    }

    func putIntSetting(key: String, value: Int) {
        graph.settings().putInt(key: key, value: Int32(value))
    }

    // MARK: 记忆隐私（settings 键，与 MemoryBridge.setPrivacyFlag 同源）

    /// 跨角色关系回忆（`private_recall_relationship_memory`，shared 默认开启）。
    /// `SettingsBridge.getBoolean` 的缺省是 false，无法区分「从未写入」与「显式关闭」，
    /// 故未落盘时按 shared 默认 true 返回；写入仍走 SettingsBridge（备份 / Android 同键）。
    func relationshipMemoryRecallEnabled() -> Bool {
        guard UserDefaults.standard.object(forKey: Self.relationshipMemoryRecallKey) != nil else { return true }
        return graph.settings().getBoolean(key: Self.relationshipMemoryRecallKey)
    }

    func setRelationshipMemoryRecallEnabled(_ value: Bool) {
        graph.settings().putBoolean(key: Self.relationshipMemoryRecallKey, value: value)
    }

    private static let relationshipMemoryRecallKey = "private_recall_relationship_memory"

    // MARK: 聊天（ChatEngineBridge：真实引擎 + fake AI 通道；onDelta 为打字机分片）

    func sendChat(
        sessionId: String,
        text: String,
        onDelta: @escaping (String) -> Void,
        onReplySegments: @escaping ([MessageSegmentItem]) -> Void = { _ in },
        onDone: @escaping (ChatRoundResult) -> Void
    ) -> ChatSendHandle {
        let cancellable = graph.chatEngine().send(
            sessionId: sessionId,
            text: text,
            onDelta: { delta in
                assertMainQueue()
                onDelta(delta)
            },
            onReplySegments: { segments in
                assertMainQueue()
                onReplySegments(segments.map(MessageSegmentItem.init(dto:)))
            },
            onDone: { dto in
                assertMainQueue()
                onDone(ChatRoundResult(
                    ok: dto.ok,
                    text: dto.text,
                    error: dto.error,
                    aiMessageId: dto.aiMessageId,
                    isDemo: dto.demo
                ))
            }
        )
        return ChatSendHandle(cancellable: cancellable)
    }

    func cancelChat() {
        graph.chatEngine().cancel()
    }

    // MARK: 角色 / 关系网（T36：真实 operators + chat_sessions + relationships + memories）

    func loadRoles() async -> Result<[RoleItem], BridgeError> {
        await withCheckedContinuation { continuation in
            graph.roles().list { dtos, error in
                assertMainQueue()
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else {
                    continuation.resume(returning: .success(dtos.map(RoleItem.init(dto:))))
                }
            }
        }
    }

    /// roleId 兼容 T33 旧深链：既可以是 operatorId（如 `zzz008`），也可以是 sessionId（如 `session_zzz008`）。
    func loadRole(id: String) async -> Result<RoleItem?, BridgeError> {
        await withCheckedContinuation { continuation in
            graph.roles().find(roleId: id) { dto, error in
                assertMainQueue()
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else {
                    continuation.resume(returning: .success(dto.map(RoleItem.init(dto:))))
                }
            }
        }
    }

    func loadRelations(operatorId: String) async -> Result<[RelationItem], BridgeError> {
        await withCheckedContinuation { continuation in
            graph.roles().relations(operatorId: operatorId) { dtos, error in
                assertMainQueue()
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else {
                    continuation.resume(returning: .success(dtos.map(RelationItem.init(dto:))))
                }
            }
        }
    }

    /// 新建/复用会话：SessionRepository.getOrCreateSession 语义，成功返回 sessionId。
    /// 测试专用：创建独立会话（--memory-e2e 跨会话测试用）。
    func createTestSession(operatorId: String, sessionId: String) async -> Result<String, BridgeError> {
        await withCheckedContinuation { continuation in
            graph.roles().createTestSession(operatorId: operatorId, sessionId: sessionId) { sessionId, error in
                assertMainQueue()
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else if let sessionId {
                    continuation.resume(returning: .success(sessionId))
                } else {
                    continuation.resume(returning: .failure(BridgeError(message: "创建测试会话失败")))
                }
            }
        }
    }

    func startChat(operatorId: String) async -> Result<String, BridgeError> {
        await withCheckedContinuation { continuation in
            graph.roles().startChat(operatorId: operatorId) { sessionId, error in
                assertMainQueue()
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else if let sessionId {
                    continuation.resume(returning: .success(sessionId))
                } else {
                    continuation.resume(returning: .failure(BridgeError(message: "创建会话失败")))
                }
            }
        }
    }
}

// MARK: - 角色 / 关系网 Swift 模型（与 Shared RoleDto / RelationDto 一一对应）

struct RoleItem: Identifiable, Hashable {
    let id: String
    let name: String
    let title: String
    /// 角色简介（Kotlin 侧字段名为 intro，避免 NSObject.description 冲突）
    let intro: String
    let gender: String
    /// operators.avatarUri 原样："1".."147" 为内置 Bundle 头像；其它走占位。
    let avatarId: String
    /// operators.userRelation（用户与该角色的关系）
    let relationType: String
    /// operators.intimacy（亲密度）
    let level: Int
    /// 最新一条长期印象（L3 记忆内容）；无则空串
    let impression: String
    let mode: String
    let sessionId: String
    let location: String
    let activity: String
    let emotion: String
    let lastMessage: String
    let lastTime: Int64

    init(dto: RoleDto) {
        id = dto.id
        name = dto.name
        title = dto.title
        intro = dto.intro
        gender = dto.gender
        avatarId = dto.avatarId
        relationType = dto.relationType
        level = Int(dto.level)
        impression = dto.impression
        mode = dto.mode
        sessionId = dto.sessionId
        location = dto.location
        activity = dto.activity
        emotion = dto.emotion
        lastMessage = dto.lastMessage
        lastTime = dto.lastTime
    }

    var hasSession: Bool { !sessionId.isEmpty }

    var modeText: String {
        switch mode.lowercased() {
        case "online", "": return "线上"
        case "offline": return "线下"
        case "director": return "导演"
        default: return mode
        }
    }

    /// 关系标签：关系类型为空时退回「未设定关系」（与 Android 关系网空态口径一致）
    var relationLabel: String {
        relationType.trimmingCharacters(in: .whitespaces).isEmpty ? "未设定关系" : relationType
    }

    var levelText: String { "Lv.\(level)" }

    var impressionText: String {
        impression.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "" : impression
    }

    /// 列表副标题：身份 + 性别 + 状态
    var subtitle: String {
        [title, gender, location].filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }.joined(separator: " · ")
    }

    var lastTimeText: String {
        guard lastTime > 0 else { return "" }
        let seconds = lastTime > 10_000_000_000 ? Double(lastTime) / 1000 : Double(lastTime)
        let date = Date(timeIntervalSince1970: seconds)
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.dateFormat = Calendar.current.isDateInToday(date) ? "HH:mm" : "M/d"
        return formatter.string(from: date)
    }
}

struct RelationItem: Identifiable, Hashable {
    let id: Int64
    let operatorId: String
    let relatedOperatorId: String
    let relatedOperatorName: String
    let type: String
    let intimacy: Int
    let note: String
    /// "out" = 该角色对 related 的关系；"in" = related 对该角色的关系
    let direction: String

    init(dto: RelationDto) {
        id = dto.id
        operatorId = dto.operatorId
        relatedOperatorId = dto.relatedOperatorId
        relatedOperatorName = dto.relatedOperatorName
        type = dto.type
        intimacy = Int(dto.intimacy)
        note = dto.note
        direction = dto.direction
    }

    /// RelationshipType 枚举名 → 中文标签（与 Android 20+ 关系类型同一值域）
    var typeText: String {
        switch type.uppercased() {
        case "BIG_SISTER": return "姐姐"
        case "LITTLE_SISTER": return "妹妹"
        case "BIG_BROTHER": return "哥哥"
        case "LITTLE_BROTHER": return "弟弟"
        case "MOTHER": return "母亲"
        case "FATHER": return "父亲"
        case "DAUGHTER": return "女儿"
        case "SON": return "儿子"
        case "GUARDIAN": return "监护人"
        case "BOSS": return "上司"
        case "SUBORDINATE": return "下属"
        case "CAPTAIN": return "队长"
        case "MEMBER": return "队员"
        case "MENTOR": return "导师"
        case "STUDENT": return "学生"
        case "CLOSE_FRIEND": return "挚友"
        case "FRIEND": return "朋友"
        case "COMRADE": return "战友"
        case "TEAMMATE": return "队友"
        case "RIVAL": return "对手"
        case "LOVE_RIVAL": return "情敌"
        case "CRUSH": return "倾慕"
        case "LOVER": return "恋人"
        case "FAMILY": return "家人"
        default: return type
        }
    }

    var directionText: String { direction == "in" ? "→ 该角色" : "该角色 →" }
}

// MARK: - T37 模型供应商（真实模型接入）

/// 一家供应商的展示行（与 Shared ProviderDto 一一对应）
struct ProviderItem: Identifiable, Hashable {
    let id: String
    let name: String
    let modelsCsv: String
    let configured: Bool
    let selected: Bool
    let requiresCustomUrl: Bool
    let endpointHint: String

    init(dto: ProviderDto) {
        id = dto.id
        name = dto.name
        modelsCsv = dto.modelsCsv
        configured = dto.configured
        selected = dto.selected
        requiresCustomUrl = dto.requiresCustomUrl
        endpointHint = dto.endpointHint
    }

    var models: [String] {
        modelsCsv.split(separator: "\n").map(String.init).filter { !$0.isEmpty }
    }

    var defaultModel: String { models.first ?? "" }
}

/// 当前编辑态回填（与 Shared ProviderSelectionDto 一一对应）
struct ProviderSelectionItem {
    let providerId: String
    let providerName: String
    let model: String
    let customUrl: String
    let temperature: Double
    let keyConfigured: Bool
    let keyPreview: String
}

/// 供应商状态（真实模式 / 演示模式）
struct ProviderStatusItem {
    let mode: String
    let providerId: String
    let providerName: String
    let model: String
    let endpoint: String
    let keyConfigured: Bool
    let reason: String
    let summary: String
    let configured: Bool

    var isDemo: Bool { !configured || mode == "demo" }
}

/// 连接测试结果
struct ProviderTestResultItem {
    let ok: Bool
    let providerId: String
    let providerName: String
    let model: String
    let baseUrl: String
    let latencyMs: Int64
    let message: String
    let replyPreview: String
}

/// 连接测试句柄（可取消）
final class ProviderTestHandle {
    private let cancellable: IosCancellable
    init(cancellable: IosCancellable) { self.cancellable = cancellable }
    func cancel() { cancellable.cancel() }
}

extension AppGraphClient {
    // MARK: 列表 / 状态（同步：NSUserDefaults + Keychain 轻量读取）

    func loadProviders() -> [ProviderItem] {
        graph.providers().list().map(ProviderItem.init(dto:))
    }

    func providerSelection() -> ProviderSelectionItem {
        let dto = graph.providers().selection()
        return ProviderSelectionItem(
            providerId: dto.providerId,
            providerName: dto.providerName,
            model: dto.model,
            customUrl: dto.customUrl,
            temperature: dto.temperature,
            keyConfigured: dto.keyConfigured,
            keyPreview: dto.keyPreview
        )
    }

    func providerStatus() -> ProviderStatusItem {
        let dto = graph.providers().status()
        return ProviderStatusItem(
            mode: dto.mode,
            providerId: dto.providerId,
            providerName: dto.providerName,
            model: dto.model,
            endpoint: dto.endpoint,
            keyConfigured: dto.keyConfigured,
            reason: dto.reason,
            summary: dto.summary,
            configured: dto.configured
        )
    }

    /// 空串 = 保存成功。
    func saveProviderConfig(providerId: String, model: String, customUrl: String, apiKey: String, temperature: Double) -> String {
        let result = graph.providers().save(
            providerId: providerId,
            model: model,
            customUrl: customUrl,
            apiKey: apiKey,
            temperature: temperature
        )
        if result.isEmpty {
            NSLog("[KnockKnock][Provider] saved provider=%@ model=%@ url=%@ keyWritten=%d",
                  providerId, model, customUrl, apiKey.isEmpty ? 0 : 1)
        } else {
            NSLog("[KnockKnock][Provider] save FAILED provider=%@ reason=%@", providerId, result)
        }
        return result
    }

    func clearProviderApiKey(providerId: String) -> Bool {
        let cleared = graph.providers().clearApiKey(providerId: providerId)
        NSLog("[KnockKnock][Provider] clearKey provider=%@ ok=%d", providerId, cleared ? 1 : 0)
        return cleared
    }

    /// 连接测试（回调在 Main 队列）；返回可取消句柄。
    @discardableResult
    func testProviderConnection(
        providerId: String,
        model: String,
        customUrl: String,
        apiKey: String,
        temperature: Double,
        onDone: @escaping (ProviderTestResultItem) -> Void
    ) -> ProviderTestHandle {
        let cancellable = graph.providers().test(
            providerId: providerId,
            model: model,
            customUrl: customUrl,
            apiKey: apiKey,
            temperature: temperature,
            onDone: { dto in
                assertMainQueue()
                onDone(ProviderTestResultItem(
                    ok: dto.ok,
                    providerId: dto.providerId,
                    providerName: dto.providerName,
                    model: dto.model,
                    baseUrl: dto.baseUrl,
                    latencyMs: dto.latencyMs,
                    message: dto.message,
                    replyPreview: dto.replyPreview
                ))
            }
        )
        return ProviderTestHandle(cancellable: cancellable)
    }

    /// 密钥存储诊断（Keychain 状态码 / 回退后端；不含密钥）。
    func providerKeyStoreDiagnostics() -> String {
        graph.providers().keyStoreDiagnostics()
    }

    /// 连接测试当前超时预算（诊断用）。
    func providerProbeTimeoutsSummary() -> String {
        graph.providers().probeTimeoutsSummary()
    }

    /// 自动化验证用：缩小连接测试超时（生产 UI 不调用）。
    func overrideProviderProbeTimeouts(firstTokenMs: Int64, totalMs: Int64) {
        graph.providers().overrideProbeTimeouts(firstTokenMs: firstTokenMs, totalMs: totalMs)
    }
}

// MARK: - T46 设置扩展（聊天表现 / 提示词编辑器 / 识图模型）

/// 聊天表现（与 Shared `ChatPerformanceDto` 一一对应）
struct ChatPerformanceItem {
    let temperature: Double
    let deepseekThinking: Bool
    let historyMessages: Int
    let dialogueMaxChars: Int
    let narrationMaxChars: Int
    let contextMode: String
    let dialogueMinChars: Int
    let narrationMinChars: Int
    let dialogueSegMin: Int
    let dialogueSegMax: Int
    let narrationSegMin: Int
    let narrationSegMax: Int
    let groupMsgMin: Int
    let groupMsgMax: Int
    let groupSpeechMin: Int
    let groupSpeechMax: Int
    let groupNarSegMin: Int
    let groupNarSegMax: Int
    let groupNarMin: Int
    let groupNarMax: Int
    let automaticContextWindow: Bool
    let maxContextTokens: Int
}

/// 提示词目录行（与 Shared `PromptEntryDto` 一一对应；正文按需读取）
struct PromptEntryItem: Identifiable, Hashable {
    let type: String
    let mode: String
    let module: String
    let displayName: String
    let groupName: String
    let isCustom: Bool
    let isDisabled: Bool

    var id: String { "\(type)|\(mode)|\(module)" }
}

/// 提示词详情（当前生效文本 = 解析后的文本，含「自定义空串=禁用」语义）
struct PromptItemDetail {
    let type: String
    let mode: String
    let module: String
    let displayName: String
    let groupName: String
    let currentText: String
    let defaultText: String
    let isCustom: Bool
    let isDisabled: Bool
    let isEmpty: Bool
    let version: Int
    let placeholders: [String]
    let recommended: [String]
    let legacy: [String]
}

/// 提示词保存结果
struct PromptSaveResult {
    let ok: Bool
    let warnings: [String]
    let error: String
}

/// 识图模型配置状态（与 Shared `VisionStatusDto` 一一对应）
struct VisionStatusItem {
    let mode: String
    let provider: String
    let providerName: String
    let endpoint: String
    let model: String
    let keyConfigured: Bool
    /// 严格等于 commonMain `effectiveVisionConfiguration().configured`
    let configured: Bool
    /// 聊天引擎实际可用（含 Keychain 密钥兜底）
    let engineConfigured: Bool
    let effectiveProvider: String
    let effectiveEndpoint: String
    let effectiveModel: String
    let keyStoreBackend: String
    let summary: String
}

extension AppGraphClient {
    // MARK: 聊天表现（真实 SettingsRepository 现有键）

    func chatPerformance() -> ChatPerformanceItem {
        let dto = graph.settings().chatPerformance()
        return ChatPerformanceItem(
            temperature: dto.temperature,
            deepseekThinking: dto.deepseekThinking,
            historyMessages: Int(dto.historyMessages),
            dialogueMaxChars: Int(dto.dialogueMaxChars),
            narrationMaxChars: Int(dto.narrationMaxChars),
            contextMode: dto.contextMode,
            dialogueMinChars: Int(dto.dialogueMinChars),
            narrationMinChars: Int(dto.narrationMinChars),
            dialogueSegMin: Int(dto.dialogueSegMin),
            dialogueSegMax: Int(dto.dialogueSegMax),
            narrationSegMin: Int(dto.narrationSegMin),
            narrationSegMax: Int(dto.narrationSegMax),
            groupMsgMin: Int(dto.groupMsgMin),
            groupMsgMax: Int(dto.groupMsgMax),
            groupSpeechMin: Int(dto.groupSpeechMin),
            groupSpeechMax: Int(dto.groupSpeechMax),
            groupNarSegMin: Int(dto.groupNarSegMin),
            groupNarSegMax: Int(dto.groupNarSegMax),
            groupNarMin: Int(dto.groupNarMin),
            groupNarMax: Int(dto.groupNarMax),
            automaticContextWindow: dto.automaticContextWindow,
            maxContextTokens: Int(dto.maxContextTokens)
        )
    }

    /// 空串 = 成功；保存即写 settings，下一轮请求重新读取后生效。
    @discardableResult
    func saveChatPerformance(
        temperature: Double,
        deepseekThinking: Bool,
        historyMessages: Int,
        dialogueMaxChars: Int,
        narrationMaxChars: Int,
        dialogueMinChars: Int,
        narrationMinChars: Int,
        dialogueSegMin: Int,
        dialogueSegMax: Int,
        narrationSegMin: Int,
        narrationSegMax: Int,
        groupMsgMin: Int,
        groupMsgMax: Int,
        groupSpeechMin: Int,
        groupSpeechMax: Int,
        groupNarSegMin: Int,
        groupNarSegMax: Int,
        groupNarMin: Int,
        groupNarMax: Int
    ) -> String {
        let result = graph.settings().saveChatPerformance(
            temperature: temperature,
            deepseekThinking: deepseekThinking,
            historyMessages: Int32(historyMessages),
            dialogueMaxChars: Int32(dialogueMaxChars),
            narrationMaxChars: Int32(narrationMaxChars),
            dialogueMinChars: Int32(dialogueMinChars),
            narrationMinChars: Int32(narrationMinChars),
            dialogueSegMin: Int32(dialogueSegMin),
            dialogueSegMax: Int32(dialogueSegMax),
            narrationSegMin: Int32(narrationSegMin),
            narrationSegMax: Int32(narrationSegMax),
            groupMsgMin: Int32(groupMsgMin),
            groupMsgMax: Int32(groupMsgMax),
            groupSpeechMin: Int32(groupSpeechMin),
            groupSpeechMax: Int32(groupSpeechMax),
            groupNarSegMin: Int32(groupNarSegMin),
            groupNarSegMax: Int32(groupNarSegMax),
            groupNarMin: Int32(groupNarMin),
            groupNarMax: Int32(groupNarMax)
        )
        NSLog("[KnockKnock][Settings] saveChatPerformance result=%@ temp=%.2f thinking=%d history=%d dia=%d nar=%d",
              result, temperature, deepseekThinking ? 1 : 0, historyMessages, dialogueMaxChars, narrationMaxChars)
        return result
    }

    // MARK: 提示词编辑器（T18 契约：prompt_<type>_<mode> 键）

    func promptCatalog() -> [PromptEntryItem] {
        graph.settings().promptCatalog().map { dto in
            PromptEntryItem(
                type: dto.type,
                mode: dto.mode,
                module: dto.module,
                displayName: dto.displayName,
                groupName: dto.groupName,
                isCustom: dto.isCustom,
                isDisabled: dto.isDisabled
            )
        }
    }

    func promptItem(type: String, mode: String, module: String) -> PromptItemDetail {
        let dto = graph.settings().promptItem(type: type, mode: mode, module: module)
        return PromptItemDetail(
            type: dto.type,
            mode: dto.mode,
            module: dto.module,
            displayName: dto.displayName,
            groupName: dto.groupName,
            currentText: dto.currentText,
            defaultText: dto.defaultText,
            isCustom: dto.isCustom,
            isDisabled: dto.isDisabled,
            isEmpty: dto.isEmpty,
            version: Int(dto.version),
            placeholders: dto.placeholdersCsv.split(separator: "\n").map(String.init),
            recommended: dto.recommendedCsv.split(separator: "\n").map(String.init),
            legacy: dto.legacyCsv.split(separator: "\n").map(String.init)
        )
    }

    func savePromptItem(type: String, mode: String, module: String, text: String) -> PromptSaveResult {
        let dto = graph.settings().savePromptItem(type: type, mode: mode, module: module, text: text)
        let result = PromptSaveResult(
            ok: dto.ok,
            warnings: dto.warningsCsv.split(separator: "\n").map(String.init),
            error: dto.error
        )
        NSLog("[KnockKnock][Prompt] save type=%@ mode=%@ module=%@ ok=%d warnings=%d error=%@",
              type, mode, module, result.ok ? 1 : 0, result.warnings.count, result.error)
        return result
    }

    /// 诊断（仅 --prompt-dump-selftest 钩子使用）：取模板解析文本，用于验证导演模式提示词真实生效。
    func promptResolvedText(type: String, mode: String, module: String = "") -> String {
        graph.settings().promptResolvedText(type: type, mode: mode, module: module)
    }

    func resetPromptItem(type: String, mode: String, module: String) {
        graph.settings().resetPromptItem(type: type, mode: mode, module: module)
        NSLog("[KnockKnock][Prompt] reset type=%@ mode=%@ module=%@", type, mode, module)
    }

    // MARK: 识图模型（vision_* 设置键 + Keychain）

    func visionStatus() -> VisionStatusItem {
        let dto = graph.settings().visionStatus()
        return VisionStatusItem(
            mode: dto.mode,
            provider: dto.provider,
            providerName: dto.providerName,
            endpoint: dto.endpoint,
            model: dto.model,
            keyConfigured: dto.keyConfigured,
            configured: dto.configured,
            engineConfigured: dto.engineConfigured,
            effectiveProvider: dto.effectiveProvider,
            effectiveEndpoint: dto.effectiveEndpoint,
            effectiveModel: dto.effectiveModel,
            keyStoreBackend: dto.keyStoreBackend,
            summary: dto.summary
        )
    }

    /// 空串 = 成功。API Key 写 Keychain；同时镜像 vision_api_key 供 commonMain 解析器判 configured。
    @discardableResult
    func saveVisionConfiguration(provider: String, endpoint: String, model: String, apiKey: String) -> String {
        let result = graph.settings().saveVisionConfiguration(
            provider: provider, endpoint: endpoint, model: model, apiKey: apiKey
        )
        NSLog("[KnockKnock][Vision] save provider=%@ model=%@ endpoint=%@ keyWritten=%d result=%@",
              provider, model, endpoint, apiKey.isEmpty ? 0 : 1, result)
        return result
    }

    @discardableResult
    func clearVisionConfiguration() -> Bool {
        let cleared = graph.settings().clearVisionConfiguration()
        NSLog("[KnockKnock][Vision] clear ok=%d", cleared ? 1 : 0)
        return cleared
    }

    // MARK: T56 A7 自动化接线（延迟任务 / 前台 tick / 通知事件源）

    /// 启动 A7 glue（幂等）：前台 tick + 启动 catch-up（重挂 24h 内到期任务并立即补算）。
    func startA7Glue() {
        graph.a7().start()
        NSLog("[KnockKnock][A7] start: %@", graph.a7().statusLine())
    }

    /// 前后台切换：前台恢复 tick + 补算；后台暂停计时器（挂起表保留）。
    func setA7Foreground(_ foreground: Bool) {
        graph.a7().setForeground(value: foreground)
        NSLog("[KnockKnock][A7] foreground=%d %@", foreground ? 1 : 0, graph.a7().statusLine())
    }

    /// BGTaskScheduler 兜底：立即 catch-up 并结算，返回本次补算执行数。
    func a7CatchUp() async -> Int {
        await withCheckedContinuation { continuation in
            graph.a7().catchUpNow { executed in
                continuation.resume(returning: Int(truncating: executed))
            }
        }
    }

    /// UI 当前查看的会话（同会话通知静音）。
    func setA7ActiveSession(_ sessionId: String) {
        graph.a7().setActiveSessionId(sessionId: sessionId)
    }

    /// 备份/恢复护栏：恢复中到期任务不执行、直接 retry。
    func setA7RecoveryActive(_ active: Bool) {
        graph.a7().setRecoveryActive(value: active)
    }

    /// T64：手动重建记忆索引（恢复备份 / 换嵌入模型后用），返回一行结果摘要。
    func a7RebuildMemoryIndex() async -> String {
        await withCheckedContinuation { continuation in
            graph.a7().rebuildMemoryIndex { summary in
                continuation.resume(returning: summary)
            }
        }
    }

    /// T66：记忆索引体检（脱敏，只有计数）。
    func a7MemoryHealthLine() async -> String {
        await withCheckedContinuation { continuation in
            graph.a7().memoryHealthLine { line in
                continuation.resume(returning: line)
            }
        }
    }

    func a7StatusLine() -> String { graph.a7().statusLine() }

    func a7RecentNotifications() -> String { graph.a7().recentNotificationsCsv() }

    /// 自检/证据用：强制 NSUserDefaults 落盘后返回是否成功。
    @discardableResult
    func flushSettingsDefaults() -> Bool {
        graph.settings().flushDefaults()
    }
}
