//
//  ChatModels.swift
//  聊天时间线数据模型（T12 结论沿用：稳定 id + LazyVStack 惰性实例化；T34 接真实 DB 历史）。
//
//  id 语义：正数 = shared `chat_messages.id`（真实历史，单调递增）；
//  负数 = 本次发送的乐观占位（发送成功后 onDone 刷新会用 DB 行替换）。
//  `--stress-2000` 启动参数仍注入 2,000 条确定性本地消息，用于 LazyVStack 基线冒烟
//  （正式性能数据见 ios-port/handoffs/perf-result-lazyvstack.json）。
//

import Foundation

struct ChatMessage: Identifiable, Hashable {
    enum Side: Hashable {
        case incoming
        case outgoing
        case system
    }

    /// 稳定 Int64 id（T12：供 ForEach/scrollTo 精确定位；T34：真实 DB 消息 id）
    let id: Int64
    let side: Side
    var text: String
    var isStreaming: Bool
    /// 旁白（ai_json 线下/导演模式的 narration 段；普通文本为空）
    var narration: String
    /// shared 侧格式化的展示时间（今天 HH:mm / 更早 M/d HH:mm）
    var timeText: String
    /// image 消息的本地 imageUri（非图片为空；iOS 为媒体 ID `media/<uuid>.<ext>`）
    var imageId: String
    /// ai_json 全部段落已撤回
    var isRecalled: Bool
    /// chat_messages.type：text / ai_json / image / gift_hidden / gift_reply_failed / send_failed / system（T42）
    var type: String
    /// 消息落库时的会话模式（online/offline/director）；T59 旁白展示按该模式判定。
    var mode: String
    /// T59：ai_json 可见段（旁白/台词交错，已过滤撤回与空段）；非 ai_json 为空。
    var segments: [MessageSegmentItem]
    /// T59 流式打字机进度：已到达的可见字符数（跨全部段累计；`Int.max` = 全部展示）。
    var typedCharacters: Int
    let createdAt: Date

    init(
        id: Int64,
        side: Side,
        text: String,
        isStreaming: Bool = false,
        narration: String = "",
        timeText: String = "",
        imageId: String = "",
        isRecalled: Bool = false,
        type: String = "text",
        mode: String = "online",
        segments: [MessageSegmentItem] = [],
        typedCharacters: Int = Int.max,
        createdAt: Date = .now
    ) {
        self.id = id
        self.side = side
        self.text = text
        self.isStreaming = isStreaming
        self.narration = narration
        self.timeText = timeText
        self.imageId = imageId
        self.isRecalled = isRecalled
        self.type = type
        self.mode = mode
        self.segments = segments
        self.typedCharacters = typedCharacters
        self.createdAt = createdAt
    }

    /// 真实 DB 消息 → 时间线模型（T34 MessageHistoryBridge / MessageDto）。
    init(item: MessageItem) {
        id = item.id
        if item.isMe {
            side = .outgoing
        } else if item.isSystem {
            side = .system
        } else {
            side = .incoming
        }
        if item.isRecalled {
            text = "（此消息已撤回）"
        } else if item.text.isEmpty {
            text = item.type == "image" ? "[图片]" : "（空消息）"
        } else {
            text = item.text
        }
        isStreaming = false
        narration = item.isRecalled ? "" : item.narration
        timeText = item.timePayload
        imageId = item.imageId
        isRecalled = item.isRecalled
        type = item.type
        mode = item.mode
        segments = item.isRecalled ? [] : item.segments
        typedCharacters = Int.max
        createdAt = Date(timeIntervalSince1970: Double(item.timestamp) / 1000.0)
    }

    var bubbleSide: BubbleSide {
        switch side {
        case .incoming: return .incoming
        case .outgoing: return .outgoing
        case .system: return .system
        }
    }

    // MARK: - T42 消息操作可用性（对齐 Android MessageList.kt 菜单条件）

    /// 图片消息（有 imageUri 或 type=image）
    var isImage: Bool { type == "image" || !imageId.isEmpty }
    /// AI 回复消息（可重说/继续说）
    var isAIMessage: Bool { type == "ai_json" && side == .incoming }
    /// 未送达消息（可重试；引擎仅接受 send_failed / gift_reply_failed）
    var isSendFailed: Bool { type == "send_failed" || type == "gift_reply_failed" }
    /// 识图/发送失败的图片消息（可重发；shared 侧 image JSON status=failed）
    var isFailedImage: Bool { type == "image" && (text.contains("失败") || text.contains("failed")) }
}

// MARK: - 2,000 条压力数据（T12 形状：短文本/长文本/系统提示，确定性 LCG）

enum TimelineStress {
    static let isEnabled: Bool = ProcessInfo.processInfo.arguments.contains("--stress-2000")
    static let defaultCount = 2_000

    private static let shortTexts = [
        "晚上好，今天也辛苦啦。",
        "刚到家，今天的实验报告总算写完了。",
        "那正好——我调了一杯「午夜港湾」，柠檬海盐味，要尝尝吗？",
        "好呀，海盐味听起来不错。",
        "（推来一杯泛着橙光的饮料）小心，杯壁有点凉。",
        "博士，今天的训练数据我整理好了。",
        "走廊的灯又坏了，我已经报修。",
    ]

    private static let longBody = "关于今天的巡逻记录，我重新核对了一遍：三层甲板的温控在 22:40 出现过一次波动，二十分钟后恢复；医疗区的药品清点没有缺漏；另外在仓库角落发现了一只走失的小型无人机，已经送回工程部。以上，随时可以向你汇报细节。"

    /// 确定性生成（固定种子 LCG）：每次运行数据集完全一致，便于截图对比
    static func makeMessages(count: Int = defaultCount) -> [ChatMessage] {
        var seed: UInt64 = 0x5EED_2026
        func next() -> UInt64 {
            seed = seed &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407
            return seed >> 33
        }

        var messages: [ChatMessage] = []
        messages.reserveCapacity(count)
        for index in 0..<count {
            let roll = Int(next() % 100)
            let id = Int64(index + 1)
            switch roll {
            case 0..<20:
                messages.append(ChatMessage(id: id, side: .incoming, text: shortTexts[Int(next() % UInt64(shortTexts.count))]))
            case 20..<40:
                let repeatCount = 1 + Int(next() % 3)
                messages.append(ChatMessage(id: id, side: .incoming, text: Array(repeating: longBody, count: repeatCount).joined(separator: "\n\n")))
            case 40..<60:
                messages.append(ChatMessage(id: id, side: .outgoing, text: shortTexts[Int(next() % UInt64(shortTexts.count))]))
            case 60..<80:
                messages.append(ChatMessage(id: id, side: .incoming, text: "（图片占位 \(id) · 16:9 · 渲染基线）"))
            case 80..<90:
                messages.append(ChatMessage(id: id, side: .system, text: "第 \(id) 条 · 系统提示：加密通道已确认"))
            default:
                messages.append(ChatMessage(id: id, side: .system, text: "第 \(id) 条 · 系统提示：巡逻记录已归档"))
            }
        }
        return messages
    }
}


// MARK: - T59 显示项（一条 ai_json 消息 → 多个气泡）

/// 显示项类型（对齐 Android `ChatUiMessage` 的 narration / dialogue / system / image 语义）
enum ChatDisplayType: String {
    case dialogue
    case narration
    case text
    case image
    case system
}

/// 渲染层派生项：`messages` 是数据层（一条 DB 消息 = 一个 ChatMessage）；
/// `displayItems` 是展示层（ai_json 按 segments 展开成多段，旁白/台词交错各占一条）。
struct ChatDisplayItem: Identifiable, Hashable {
    /// 稳定 id = messageId * 1000 + segmentIndex（对齐 Android `msg.id * 1000 + segIdx`）
    let id: Int64
    /// 所属 DB 消息 id（长按菜单/撤回/删除等操作按此回落到整条消息）
    let messageId: Int64
    /// 段在原始 segments 数组中的下标（非分段消息为 0）
    let segmentIndex: Int
    let type: ChatDisplayType
    let text: String
    let isStreaming: Bool
    let isRecalled: Bool
    let timestamp: Date
    /// shared 侧格式化的展示时间（今天 HH:mm / 更早 M/d HH:mm）
    let timeText: String
    let isMe: Bool
    let side: ChatMessage.Side
    /// 图片消息的本地 imageUri（点击全屏用；非图片为空）
    let imageId: String
    /// 时间分隔条只在该消息的首段显示（微信/QQ 同款 ≥5 分钟规则）
    let showsTimeSeparator: Bool
}

/// 纯函数派生器：`[ChatMessage]` → `[ChatDisplayItem]`（可单测/自检直接断言）。
enum ChatDisplayItemBuilder {
    /// 段 id 基数（与 Android 一致；单条消息段数上限 1000，实际协议 ≤ 8 段）
    static let segmentIdBase: Int64 = 1000
    /// 时间分隔阈值：与上一条消息间隔 ≥5 分钟才显示
    static let timeSeparatorInterval: TimeInterval = 5 * 60

    static func itemID(messageId: Int64, segmentIndex: Int) -> Int64 {
        messageId * segmentIdBase + Int64(segmentIndex)
    }

    /// ai_json 的可见段：线上模式不展示旁白（Android `MessageParser.parsePrivateAiJson` 同口径）；
    /// 整条撤回 → 不展开（由调用方回落到「已撤回」占位气泡）。
    static func visibleSegments(for message: ChatMessage) -> [MessageSegmentItem] {
        guard !message.isRecalled, !message.segments.isEmpty else { return [] }
        let segments = message.segments
        if message.mode.lowercased() == "online" {
            return segments.filter { $0.type != "narration" }
        }
        return segments
    }

    static func build(messages: [ChatMessage]) -> [ChatDisplayItem] {
        var result: [ChatDisplayItem] = []
        result.reserveCapacity(messages.count + 4)
        for (index, message) in messages.enumerated() {
            let showTime: Bool
            if message.timeText.isEmpty {
                showTime = false
            } else if index == 0 {
                showTime = true
            } else {
                showTime = message.createdAt.timeIntervalSince(messages[index - 1].createdAt) >= timeSeparatorInterval
            }
            let segments = visibleSegments(for: message)
            if segments.isEmpty {
                let type: ChatDisplayType
                if message.isImage {
                    type = .image
                } else if message.side == .system {
                    type = .system
                } else if message.side == .outgoing {
                    type = .text
                } else {
                    type = .dialogue
                }
                result.append(
                    ChatDisplayItem(
                        id: itemID(messageId: message.id, segmentIndex: 0),
                        messageId: message.id,
                        segmentIndex: 0,
                        type: type,
                        text: message.text,
                        isStreaming: message.isStreaming,
                        isRecalled: message.isRecalled,
                        timestamp: message.createdAt,
                        timeText: message.timeText,
                        isMe: message.side == .outgoing,
                        side: message.side,
                        imageId: message.imageId,
                        showsTimeSeparator: showTime
                    )
                )
                continue
            }

            // 流式：按已到达字符数给每段分配前缀（前面的段已完整，最后一段随 delta 增长）；
            // 取消生成时 typedCharacters 保留部分进度，未到达的段不出现。
            let totalCharacters = segments.reduce(0) { $0 + $1.text.count }
            let fullyTyped = message.typedCharacters == Int.max
            let typed = fullyTyped ? totalCharacters : min(max(message.typedCharacters, 0), totalCharacters)
            var offset = 0
            var pending: [ChatDisplayItem] = []
            for segment in segments {
                let length = segment.text.count
                let available = max(0, min(length, typed - offset))
                offset += length
                guard fullyTyped || available > 0 else { continue }
                pending.append(
                    ChatDisplayItem(
                        id: itemID(messageId: message.id, segmentIndex: segment.index),
                        messageId: message.id,
                        segmentIndex: segment.index,
                        type: segment.type == "narration" ? .narration : .dialogue,
                        text: available >= length ? segment.text : String(segment.text.prefix(available)),
                        isStreaming: false,
                        isRecalled: false,
                        timestamp: message.createdAt,
                        timeText: message.timeText,
                        isMe: message.side == .outgoing,
                        side: message.side,
                        imageId: "",
                        showsTimeSeparator: false
                    )
                )
            }
            if pending.isEmpty, message.isStreaming, let first = segments.first {
                // 段已解析但第一个字符还没到达：保留一个「…」占位气泡，避免时间线闪空。
                pending.append(
                    ChatDisplayItem(
                        id: itemID(messageId: message.id, segmentIndex: first.index),
                        messageId: message.id,
                        segmentIndex: first.index,
                        type: first.type == "narration" ? .narration : .dialogue,
                        text: "",
                        isStreaming: true,
                        isRecalled: false,
                        timestamp: message.createdAt,
                        timeText: message.timeText,
                        isMe: message.side == .outgoing,
                        side: message.side,
                        imageId: "",
                        showsTimeSeparator: showTime
                    )
                )
            }
            if !pending.isEmpty {
                // 只有最后一条可见段处于「正在输入」态（打字机光标）；时间分隔只在首段。
                for itemIndex in pending.indices {
                    let isLast = itemIndex == pending.count - 1
                    let base = pending[itemIndex]
                    pending[itemIndex] = ChatDisplayItem(
                        id: base.id,
                        messageId: base.messageId,
                        segmentIndex: base.segmentIndex,
                        type: base.type,
                        text: base.text,
                        isStreaming: message.isStreaming && isLast,
                        isRecalled: false,
                        timestamp: base.timestamp,
                        timeText: base.timeText,
                        isMe: base.isMe,
                        side: base.side,
                        imageId: "",
                        showsTimeSeparator: itemIndex == 0 ? showTime : false
                    )
                }
            }
            result.append(contentsOf: pending)
        }
        return result
    }
}
