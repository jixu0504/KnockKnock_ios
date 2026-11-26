//
//  MessageHistoryClient.swift
//  T34 消息历史桥的 Swift 包装（独立文件，避免与并行卡在 AppGraphClient.swift 内互相覆盖）：
//  - MessageItem / MessagePage：与 Shared MessageDto / MessagePageDto 一一对应的 Swift 值类型；
//  - `AppGraphClient.loadMessagePage(...)`：Kotlin 回调桥 → async/await；
//  - `AppGraphClient.findMessage(id:)`：单条消息（诊断用）。
//
//  分页语义（shared 侧 MessageHistoryBridge.page）：
//  - beforeId == 0 → 首屏（最新 limit 条，items 按 id 倒序）；
//  - beforeId == 上一页 nextBeforeId → 更早一页；
//  - hasMore / nextBeforeId / totalCount 一并返回，Swift 侧无需额外 COUNT。
//

import Foundation
import Shared

// MARK: - Swift 侧数据模型

/// T59：单个可见段（旁白 / 台词）的 Swift 值类型（桥 `MessageSegmentDto` 的一一对应，
/// Feature 层不接触 Kotlin 类型）。`index` 是原始 segments 下标，`type` 为 `dialogue`/`narration`。
struct MessageSegmentItem: Identifiable, Hashable {
    let index: Int
    let type: String
    let text: String

    var id: Int { index }

    var isNarration: Bool { type == "narration" }

    init(index: Int, type: String, text: String) {
        self.index = index
        self.type = type
        self.text = text
    }

    init(dto: MessageSegmentDto) {
        index = Int(dto.index)
        type = dto.type
        text = dto.text
    }
}

struct MessageItem: Identifiable, Hashable {
    let id: Int64
    let sessionId: String
    let senderName: String
    let isMe: Bool
    /// 可见正文（ai_json 已解析出台词；image 为附言；gift/system 为占位文案）
    let text: String
    /// 旁白（仅 ai_json 线下/导演模式）
    let narration: String
    /// chat_messages.type：text / ai_json / image / gift_hidden / gift_reply_failed / system
    let type: String
    /// image 消息的本地 imageUri（其它为空串）
    let imageId: String
    /// 展示用时间（今天 HH:mm；更早 M/d HH:mm），由 shared 侧格式化
    let timePayload: String
    let timestamp: Int64
    let mode: String
    let isRecalled: Bool
    /// T59：ai_json 可见段（旁白/台词交错，已过滤撤回与空段）；非 ai_json 为空数组
    let segments: [MessageSegmentItem]

    init(dto: MessageDto) {
        id = dto.id
        sessionId = dto.sessionId
        senderName = dto.senderName
        isMe = dto.isMe
        text = dto.text
        narration = dto.narration
        type = dto.type
        imageId = dto.imageId
        timePayload = dto.timePayload
        timestamp = dto.timestamp
        mode = dto.mode
        isRecalled = dto.isRecalled
        segments = dto.segments.map(MessageSegmentItem.init(dto:))
    }

    var isSystem: Bool { type == "system" }
}

struct MessagePage: Hashable {
    let items: [MessageItem]
    let hasMore: Bool
    let nextBeforeId: Int64
    let totalCount: Int
}

// MARK: - AppGraphClient 历史查询

@MainActor
extension AppGraphClient {
    /// 分页拉取真实 `chat_messages`（只读）。`beforeId = 0` 为首屏。
    func loadMessagePage(
        sessionId: String,
        beforeId: Int64 = 0,
        limit: Int = 50
    ) async -> Result<MessagePage, BridgeError> {
        await withCheckedContinuation { continuation in
            historyBridge.page(
                sessionId: sessionId,
                beforeId: beforeId,
                limit: Int32(limit)
            ) { dto, error in
                if !Thread.isMainThread {
                    NSLog("[RhodesLink][Bridge] ⚠️ history.page 回调不在主线程")
                }
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else {
                    continuation.resume(
                        returning: .success(
                            MessagePage(
                                items: dto.items.map(MessageItem.init(dto:)),
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

    /// 单条消息（诊断/自检用；不存在返回 nil）。
    func findMessage(id: Int64) async -> MessageItem? {
        await withCheckedContinuation { continuation in
            historyBridge.find(messageId: id) { dto, error in
                if error != nil {
                    continuation.resume(returning: nil)
                } else {
                    continuation.resume(returning: dto.map(MessageItem.init(dto:)))
                }
            }
        }
    }

    /// 按连续 id 区间读取（聊天记录导入定位用）：
    /// 导入器从 `MAX(id)+1` 起连续分配 id，这里一次取回整块导入记录（时间戳通常早于首屏分页窗口）。
    /// 不存在的 id 由 shared 跳过；结果按 id 升序。
    func loadMessagesByIdRange(
        sessionId: String,
        fromId: Int64,
        count: Int
    ) async -> Result<[MessageItem], BridgeError> {
        guard fromId > 0, count > 0 else { return .success([]) }
        return await withCheckedContinuation { continuation in
            historyBridge.pageByIdRange(
                sessionId: sessionId,
                fromId: fromId,
                count: Int32(min(count, 500))
            ) { dtos, error in
                if !Thread.isMainThread {
                    NSLog("[RhodesLink][Bridge] ⚠️ history.pageByIdRange 回调不在主线程")
                }
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else {
                    continuation.resume(returning: .success(dtos.map(MessageItem.init(dto:))))
                }
            }
        }
    }
}
