//
//  BubbleSplitAutomation.swift
//  T59 聊天气泡按段拆分自检（仅启动参数 `--bubble-split-selftest` 启用，对用户路径零影响）：
//
//  覆盖链路：真实 DB `chat_messages`（ai_json）→ shared MessageHistoryBridge（segments DTO）
//  → Swift ChatMessage → ChatDisplayItemBuilder（旁白/台词交错展开）→ ChatView 一段一气泡。
//
//  断言：
//    1) 预置一条「旁白+台词+旁白+台词」ai_json 消息 → displayItems 恰好 4 项；
//    2) 顺序/类型/文本与原始 segments 一致（旁白/台词交错，不合并）；
//    3) id = messageId * 1000 + 原始段索引，且多次派生稳定；
//    4) 时间分隔只在首段（人工构造 20 分钟间隔消息严格断言；真实数据断言非首段必为 false）；
//    5) 流式增量：部分字符已到达时前面的段完整、最后一段为前缀且仅末段 isStreaming；
//    6) 线上模式过滤旁白（与 Android MessageParser 同口径）。
//
//  用法：`--selftest --bubble-split-selftest --open-url rhodes://chat/session_amiya`
//  证据：Documents/ios-shell-selftest.log + NSLog + 模拟器截图（ios-port/handoffs/evidence/t59-*）。
//

import Foundation

@MainActor
enum BubbleSplitAutomation {
    static let isEnabled = ProcessInfo.processInfo.arguments.contains("--bubble-split-selftest")
    private static var didRun = false

    /// 自检夹具：旁白 + 台词 + 旁白 + 台词（offline 模式才展示旁白段）。
    static let fixtureJSON = #"{"emotion":"平静","state":"坐在窗边","location":"宿舍","continuity":"继续","segments":[{"type":"narration","content":"（夜色落在舷窗上，她合上终端。）"},{"type":"dialogue","content":"博士，这边。"},{"type":"narration","content":"（她把杯子推过来，杯壁还冒着热气。）"},{"type":"dialogue","content":"小心烫。"}]}"#

    static func runIfNeeded(model: ChatViewModel) async {
        guard isEnabled, !didRun else { return }
        didRun = true
        SelfTestLog.add("=== bubble-split selftest start ===")

        // 等历史加载完成 & 没有在飞轮次（避免乐观占位干扰断言）
        let loadDeadline = Date().addingTimeInterval(30)
        while (!model.hasLoaded || model.isStreaming), Date() < loadDeadline {
            try? await Task.sleep(nanoseconds: 200_000_000)
        }

        let bridge = AppGraphClient.shared
        let sessionId = model.sessionIdForAutomation
        var failures = 0

        func check(_ name: String, _ condition: Bool, _ detail: String) {
            if condition {
                SelfTestLog.add("bubble-split PASS \(name) \(detail)")
            } else {
                failures += 1
                SelfTestLog.add("bubble-split FAIL \(name) \(detail)")
            }
        }

        // ---- 纯派生层断言（不依赖 DB 时序，先跑，保证失败也有证据） ----
        let syntheticOlder = ChatMessage(
            id: 900_001,
            side: .incoming,
            text: "旧消息",
            timeText: "10:00",
            mode: "online",
            createdAt: Date(timeIntervalSinceNow: -20 * 60)
        )
        var syntheticSegments: [MessageSegmentItem] = [
            MessageSegmentItem(index: 0, type: "narration", text: "（旁白一）"),
            MessageSegmentItem(index: 1, type: "dialogue", text: "台词一"),
            MessageSegmentItem(index: 2, type: "narration", text: "（旁白二）"),
            MessageSegmentItem(index: 3, type: "dialogue", text: "台词二"),
        ]
        let syntheticNewer = ChatMessage(
            id: 900_002,
            side: .incoming,
            text: "台词一台词二",
            isStreaming: false,
            timeText: "10:20",
            mode: "offline",
            segments: syntheticSegments,
            createdAt: Date()
        )
        let syntheticItems = ChatDisplayItemBuilder.build(messages: [syntheticOlder, syntheticNewer])
        let syntheticNewerItems = syntheticItems.filter { $0.messageId == syntheticNewer.id }
        check("synthetic.count", syntheticNewerItems.count == 4, "count=\(syntheticNewerItems.count)")
        check(
            "synthetic.types",
            syntheticNewerItems.map(\.type) == [.narration, .dialogue, .narration, .dialogue],
            "types=\(syntheticNewerItems.map(\.type.rawValue))"
        )
        check(
            "synthetic.timeSeparator.firstOnly",
            syntheticNewerItems.first?.showsTimeSeparator == true
                && syntheticNewerItems.dropFirst().allSatisfy { !$0.showsTimeSeparator },
            "separators=\(syntheticNewerItems.map(\.showsTimeSeparator))"
        )
        let syntheticIds = syntheticNewerItems.map(\.id)
        let expectedIds = syntheticSegments.map { ChatDisplayItemBuilder.itemID(messageId: syntheticNewer.id, segmentIndex: $0.index) }
        check("synthetic.ids", syntheticIds == expectedIds, "ids=\(syntheticIds) expected=\(expectedIds)")
        let syntheticIdsAgain = ChatDisplayItemBuilder.build(messages: [syntheticOlder, syntheticNewer])
            .filter { $0.messageId == syntheticNewer.id }.map(\.id)
        check("synthetic.idsStable", syntheticIds == syntheticIdsAgain, "again=\(syntheticIdsAgain)")

        // 流式增量：已到达 6 个字符（旁白一 5 字 + 台词一第 1 字），前面段完整、末段为前缀且仅末段
        // isStreaming（每段独立出现，最后一段随流增长）。
        let streamingSegments: [MessageSegmentItem] = [
            MessageSegmentItem(index: 0, type: "narration", text: "（旁白一）"),
            MessageSegmentItem(index: 1, type: "dialogue", text: "台词一"),
            MessageSegmentItem(index: 2, type: "narration", text: "（旁白二）"),
        ]
        let streamingMessage = ChatMessage(
            id: -7,
            side: .incoming,
            text: "",
            isStreaming: true,
            timeText: "10:21",
            mode: "offline",
            segments: streamingSegments,
            typedCharacters: 7
        )
        let streamingItems = ChatDisplayItemBuilder.build(messages: [streamingMessage])
        check("stream.count", streamingItems.count == 2, "count=\(streamingItems.count) text=\(streamingItems.map(\.text))")
        check("stream.firstFull", streamingItems.first?.text == "（旁白一）", "text=\(streamingItems.first?.text ?? "-")")
        check(
            "stream.lastPrefix",
            streamingItems.last?.text == "台词",
            "text=\(streamingItems.last?.text ?? "-")"
        )
        check(
            "stream.lastOnlyStreaming",
            streamingItems.last?.isStreaming == true && streamingItems.dropLast().allSatisfy { !$0.isStreaming },
            "streaming=\(streamingItems.map(\.isStreaming))"
        )

        // 线上模式：旁白段不展示（Android MessageParser 同口径）
        let onlineMessage = ChatMessage(
            id: 900_003,
            side: .incoming,
            text: "台词一",
            timeText: "10:22",
            mode: "online",
            segments: syntheticSegments,
            createdAt: Date()
        )
        let onlineItems = ChatDisplayItemBuilder.build(messages: [onlineMessage])
        check(
            "online.narrationHidden",
            onlineItems.count == 2 && onlineItems.allSatisfy { $0.type == .dialogue },
            "count=\(onlineItems.count) types=\(onlineItems.map(\.type.rawValue))"
        )

        // ---- 流式链路：真实 send → onReplySegments → 逐段展开（demo 通道 1 段） ----
        let lastIdBeforeSend = model.messages.last?.id ?? 0
        model.send("T59流式自检：请回我一句台词。")
        let streamDeadline = Date().addingTimeInterval(45)
        while model.isStreaming || !model.messages.contains(where: { $0.id > lastIdBeforeSend && $0.type == "ai_json" }),
              Date() < streamDeadline {
            try? await Task.sleep(nanoseconds: 200_000_000)
        }
        let streamMarker = SelfTestLog.snapshot().contains { $0.contains("chat stream segments messageId=") }
        check("stream.bridgeSegmentsCallback", streamMarker, "marker=\(streamMarker)")
        if let reply = model.messages.last(where: { $0.id > lastIdBeforeSend && $0.type == "ai_json" && $0.id > 0 }) {
            let replyItems = model.displayItems.filter { $0.messageId == reply.id }
            let visibleCount = ChatDisplayItemBuilder.visibleSegments(for: reply).count
            check(
                "stream.displayExpanded",
                visibleCount > 0 && replyItems.count == visibleCount && reply.isStreaming == false,
                "segments=\(reply.segments.count) visible=\(visibleCount) items=\(replyItems.count) types=\(replyItems.map(\.type.rawValue))"
            )
        } else {
            check("stream.displayExpanded", false, "no ai_json reply after send")
        }

        // ---- 端到端：真实 DB ai_json → 历史桥 → displayItems ----
        let seed = await bridge.debugSeedAiJsonMessage(
            sessionId: sessionId,
            content: fixtureJSON,
            mode: "offline",
            timestamp: 0
        )
        guard case .success(let messageId) = seed, messageId > 0 else {
            SelfTestLog.add("bubble-split FAIL seed (debugSeedAiJsonMessage)")
            SelfTestLog.add("=== bubble-split selftest done PASS=false failures=1 ===")
            SelfTestLog.flushToDisk(summary: "T59 bubble-split selftest：seed FAILED")
            return
        }
        SelfTestLog.add("bubble-split seed messageId=\(messageId) session=\(sessionId)")
        await model.refresh()

        func fixtureItems() -> [ChatDisplayItem] {
            model.displayItems.filter { $0.messageId == messageId }
        }
        var liveItems = fixtureItems()
        if liveItems.isEmpty {
            // refresh 与插入可能存在一拍竞态：再等一次刷新
            try? await Task.sleep(nanoseconds: 400_000_000)
            await model.refresh()
            liveItems = fixtureItems()
        }
        check("db.count", liveItems.count == 4, "count=\(liveItems.count)")
        check(
            "db.types",
            liveItems.map(\.type) == [.narration, .dialogue, .narration, .dialogue],
            "types=\(liveItems.map(\.type.rawValue))"
        )
        check(
            "db.texts",
            liveItems.map(\.text) == ["（夜色落在舷窗上，她合上终端。）", "博士，这边。", "（她把杯子推过来，杯壁还冒着热气。）", "小心烫。"],
            "texts=\(liveItems.map(\.text))"
        )
        let liveIds = liveItems.map(\.id)
        let liveExpected = (0..<4).map { ChatDisplayItemBuilder.itemID(messageId: messageId, segmentIndex: $0) }
        check("db.ids", liveIds == liveExpected, "ids=\(liveIds) expected=\(liveExpected)")
        let liveIdsAgain = fixtureItems().map(\.id)
        check("db.idsStable", liveIds == liveIdsAgain, "again=\(liveIdsAgain)")
        check(
            "db.timeSeparator.firstOnly",
            liveItems.dropFirst().allSatisfy { !$0.showsTimeSeparator },
            "separators=\(liveItems.map(\.showsTimeSeparator))"
        )
        check(
            "db.interleaved",
            liveItems[0].isMe == false && liveItems[1].side == .incoming,
            "side=\(liveItems.map(\.side))"
        )
        SelfTestLog.add("bubble-split displayItems total=\(model.displayItems.count) fixture=\(liveItems.count) messages=\(model.messages.count)")
        SelfTestLog.add("=== bubble-split selftest done PASS=\(failures == 0) failures=\(failures) ===")
        SelfTestLog.flushToDisk(
            summary: "T59 bubble-split selftest（旁白+台词+旁白+台词 → 4 条独立气泡 / id 稳定 / 时间分隔仅首段 / 流式逐段）PASS=\(failures == 0)"
        )
    }
}
