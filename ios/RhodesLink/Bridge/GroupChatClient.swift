//
//  GroupChatClient.swift
//  T43 群聊桥的 Swift 包装（独立文件，遵循 ios-bridge-spec 纪律：
//  Kotlin 类型只在本文件内出现，SwiftUI 只接触 struct / async 方法 / GroupSendHandle）。
//
//  对应 shared 侧 `GroupChatBridge`（群列表 / 群详情+成员 / 群消息分页 / 真实
//  GroupChatEngine 发送 / 自动互动开关 / 分段撤回 / 重试 / 新建群聊）。
//
//  回调语义：`async -> Result` 或 `async -> String?`（nil = 成功，非 nil = 中文错误）。
//

import Foundation
import Shared

// MARK: - Swift 侧数据模型（与 Shared DTO 一一对应）

/// 群列表行（真实 chat_sessions：operatorId LIKE 'group_%'）
struct GroupItem: Identifiable, Hashable {
    let sessionId: String
    let name: String
    let memberCount: Int
    let mutedCount: Int
    let lastMessage: String
    let lastTime: Int64
    let unreadCount: Int
    let isPinned: Bool
    let mode: String
    let autoEnabled: Bool
    let autoStatus: String

    var id: String { sessionId }

    init(dto: GroupSummaryDto) {
        sessionId = dto.sessionId
        name = dto.name
        memberCount = Int(dto.memberCount)
        mutedCount = Int(dto.mutedCount)
        lastMessage = dto.lastMessage
        lastTime = dto.lastTime
        unreadCount = Int(dto.unreadCount)
        isPinned = dto.isPinned
        mode = dto.mode
        autoEnabled = dto.autoEnabled
        autoStatus = dto.autoStatus
    }

    var memberCountText: String { "\(memberCount) 位成员" }

    var lastTimeText: String {
        guard lastTime > 0 else { return "" }
        let seconds = lastTime > 10_000_000_000 ? Double(lastTime) / 1000 : Double(lastTime)
        let date = Date(timeIntervalSince1970: seconds)
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.dateFormat = Calendar.current.isDateInToday(date) ? "HH:mm" : "M/d"
        return formatter.string(from: date)
    }

    var modeText: String {
        switch mode.lowercased() {
        case "online", "": return "在线"
        case "offline": return "面对面"
        case "director": return "导演"
        default: return mode
        }
    }
}

/// 群成员（禁言 = Android 群聊唯一成员权限维度）
struct GroupMemberItem: Identifiable, Hashable {
    let id: String
    let name: String
    let title: String
    let avatarId: String
    let isMuted: Bool
    let isResolved: Bool
    let permissionText: String

    init(dto: GroupMemberDto) {
        id = dto.id
        name = dto.name
        title = dto.title
        avatarId = dto.avatarId
        isMuted = dto.isMuted
        isResolved = dto.isResolved
        permissionText = dto.permissionText
    }
}

/// 群详情 + 成员名册 + 自动互动开关状态
struct GroupDetailItem: Hashable {
    let sessionId: String
    let name: String
    let avatarId: String
    let mode: String
    let rules: String
    let memberCount: Int
    let mutedCount: Int
    let members: [GroupMemberItem]
    let lastMessage: String
    let lastTime: Int64
    let unreadCount: Int
    let isPinned: Bool
    let autoEnabled: Bool
    let autoMasterEnabled: Bool
    let autoStatus: String
    let autoMaxRounds: Int

    init(dto: GroupDetailDto) {
        sessionId = dto.sessionId
        name = dto.name
        avatarId = dto.avatarId
        mode = dto.mode
        rules = dto.rules
        memberCount = Int(dto.memberCount)
        mutedCount = Int(dto.mutedCount)
        members = dto.members.map(GroupMemberItem.init(dto:))
        lastMessage = dto.lastMessage
        lastTime = dto.lastTime
        unreadCount = Int(dto.unreadCount)
        isPinned = dto.isPinned
        autoEnabled = dto.autoEnabled
        autoMasterEnabled = dto.autoMasterEnabled
        autoStatus = dto.autoStatus
        autoMaxRounds = Int(dto.autoMaxRounds)
    }

    var modeText: String {
        switch mode.lowercased() {
        case "online", "": return "在线群聊"
        case "offline": return "面对面群聊"
        case "director": return "导演模式"
        default: return mode
        }
    }
}

/// 群消息的一个发言段（AI 行按发言人拆分）
struct GroupMessageSegmentItem: Identifiable, Hashable {
    let index: Int
    let speakerName: String
    let speakerId: String
    let avatarId: String
    let text: String
    let emotion: String
    let isNarration: Bool
    let isRecalled: Bool

    /// 稳定 id：同一消息内的段序号（消息 id 由上层 Message 提供）
    var id: Int { index }
}

/// 群消息行（一条 chat_messages 行；ai_json 行含多段）
struct GroupMessageItem: Identifiable, Hashable {
    let id: Int64
    let sessionId: String
    let senderName: String
    let isMe: Bool
    let type: String
    let text: String
    let imageId: String
    let timePayload: String
    let timestamp: Int64
    let mode: String
    let isRecalled: Bool
    let segments: [GroupMessageSegmentItem]

    init(dto: GroupMessageDto) {
        id = dto.id
        sessionId = dto.sessionId
        senderName = dto.senderName
        isMe = dto.isMe
        type = dto.type
        text = dto.text
        imageId = dto.imageId
        timePayload = dto.timePayload
        timestamp = dto.timestamp
        mode = dto.mode
        isRecalled = dto.isRecalled
        segments = dto.segments.map { segment in
            GroupMessageSegmentItem(
                index: Int(segment.index),
                speakerName: segment.speakerName,
                speakerId: segment.speakerId,
                avatarId: segment.avatarId,
                text: segment.text,
                emotion: segment.emotion,
                isNarration: segment.isNarration,
                isRecalled: segment.isRecalled
            )
        }
    }

    var createdAt: Date { Date(timeIntervalSince1970: Double(timestamp) / 1000.0) }
    var isSystem: Bool { type == "system" }
    var isSendFailed: Bool { type == "send_failed" || type == "gift_reply_failed" }
    var isAI: Bool { type == "ai_json" }
    var isImage: Bool { type == "image" }
}

struct GroupMessagePage: Hashable {
    let items: [GroupMessageItem]
    let hasMore: Bool
    let nextBeforeId: Int64
    let totalCount: Int
}

/// 群聊发送结果
struct GroupSendResultItem {
    let ok: Bool
    let userMessageId: Int64
    let aiMessageId: Int64
    let text: String
    let speakersCsv: String
    let narrationCount: Int
    let error: String
    let isDemo: Bool
}

/// 发送句柄（对上层隐藏 Kotlin IosCancellable）
final class GroupSendHandle {
    private let cancellable: IosCancellable
    init(cancellable: IosCancellable) { self.cancellable = cancellable }
    func cancel() { cancellable.cancel() }
}

// MARK: - AppGraphClient 群聊扩展

@MainActor
extension AppGraphClient {
    // MARK: 列表 / 详情 / 成员

    func loadGroups() async -> Result<[GroupItem], BridgeError> {
        await withCheckedContinuation { continuation in
            groupBridge.listGroups { dtos, error in
                assertMainQueue("groups.list")
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else {
                    continuation.resume(returning: .success(dtos.map(GroupItem.init(dto:))))
                }
            }
        }
    }

    func loadGroupDetail(sessionId: String) async -> Result<GroupDetailItem, BridgeError> {
        await withCheckedContinuation { continuation in
            groupBridge.groupDetail(sessionId: sessionId) { dto, error in
                assertMainQueue("groups.detail")
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else if let dto {
                    continuation.resume(returning: .success(GroupDetailItem(dto: dto)))
                } else {
                    continuation.resume(returning: .failure(BridgeError(message: "群聊不存在")))
                }
            }
        }
    }

    // MARK: 分页

    func loadGroupMessagePage(
        sessionId: String,
        beforeId: Int64 = 0,
        limit: Int = 50
    ) async -> Result<GroupMessagePage, BridgeError> {
        await withCheckedContinuation { continuation in
            groupBridge.page(sessionId: sessionId, beforeId: beforeId, limit: Int32(limit)) { dto, error in
                assertMainQueue("groups.page")
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else {
                    continuation.resume(
                        returning: .success(
                            GroupMessagePage(
                                items: dto.items.map(GroupMessageItem.init(dto:)),
                                hasMore: dto.hasMore,
                                nextBeforeId: dto.nextBeforeId,
                                totalCount: Int(dto.totalCount)
                            )
                        )
                    )
                }
            }
        }
    }

    // MARK: 发送 / 取消

    func sendGroupChat(
        sessionId: String,
        text: String,
        onDelta: @escaping (String) -> Void,
        onDone: @escaping (GroupSendResultItem) -> Void
    ) -> GroupSendHandle {
        let cancellable = groupBridge.send(
            sessionId: sessionId,
            text: text,
            onDelta: { delta in
                assertMainQueue("group.send.onDelta")
                onDelta(delta)
            },
            onDone: { dto in
                assertMainQueue("group.send.onDone")
                onDone(GroupSendResultItem(
                    ok: dto.ok,
                    userMessageId: dto.userMessageId,
                    aiMessageId: dto.aiMessageId,
                    text: dto.text,
                    speakersCsv: dto.speakersCsv,
                    narrationCount: Int(dto.narrationCount),
                    error: dto.error,
                    isDemo: dto.demo
                ))
            }
        )
        return GroupSendHandle(cancellable: cancellable)
    }

    func cancelGroupChat() { groupBridge.cancel() }

    /// 当前查看的群（未读自增排除当前群；进入/退出群聊页时更新）
    func setActiveGroup(sessionId: String) { groupBridge.setActiveGroup(sessionId: sessionId) }

    func isGroupDemoMode() -> Bool { groupBridge.isDemoMode() }

    // MARK: 群操作

    /// 自动互动开关：回调更新后的状态文案（如「等待下一轮自动聊天（约N秒）」）。返回 nil = 成功。
    func setGroupAutoChat(sessionId: String, enabled: Bool) async -> Result<String, BridgeError> {
        await withCheckedContinuation { continuation in
            groupBridge.setAutoGroupChatEnabled(sessionId: sessionId, enabled: enabled) { status, error in
                assertMainQueue("groups.setAuto")
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else {
                    continuation.resume(returning: .success(status ?? ""))
                }
            }
        }
    }

    func groupAutoChatStatus(sessionId: String) -> String {
        groupBridge.autoGroupChatStatus(sessionId: sessionId)
    }

    /// 撤回 AI 消息分段（全段撤回 → 引擎删整条）。nil = 成功。
    func recallGroupSegment(sessionId: String, messageId: Int64, segmentIndex: Int32) async -> String? {
        await withCheckedContinuation { continuation in
            groupBridge.recallSegment(sessionId: sessionId, messageId: messageId, segmentIndex: segmentIndex) { error in
                assertMainQueue("groups.recallSegment")
                continuation.resume(returning: error)
            }
        }
    }

    /// 重试未送达群消息。nil = 成功。
    func retryGroupMessage(sessionId: String, messageId: Int64) async -> String? {
        await withCheckedContinuation { continuation in
            groupBridge.retryMessage(sessionId: sessionId, messageId: messageId) { error in
                assertMainQueue("groups.retryMessage")
                continuation.resume(returning: error)
            }
        }
    }

    /// 新建群聊：写 chat_sessions（group_<hex>），成功返回新群 sessionId。
    func createGroup(name: String, memberIds: [String]) async -> Result<String, BridgeError> {
        await withCheckedContinuation { continuation in
            groupBridge.createGroup(name: name, memberIds: memberIds) { sessionId, error in
                assertMainQueue("groups.create")
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else if let sessionId {
                    continuation.resume(returning: .success(sessionId))
                } else {
                    continuation.resume(returning: .failure(BridgeError(message: "新建群聊失败")))
                }
            }
        }
    }
}
