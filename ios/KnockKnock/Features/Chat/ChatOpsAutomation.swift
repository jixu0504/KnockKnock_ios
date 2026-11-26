//
//  ChatOpsAutomation.swift
//  T42 聊天消息操作自检（仅启动参数 `--chat-ops-selftest` 启用，对用户路径零影响）：
//  走**真实** ChatEngineBridge / ChatEngine / 真实 DB 执行：
//    撤回（整条 + 分段）→ 重试未送达 → 重说 → 继续说 → 图片消息（入库 + 发送 + 全屏数据） → 本地删除
//  每一步记录前后消息数、消息 id、关键字段（type / text / imageId），并用 shared 分页查询复核 DB。
//
//  前置：`--selftest --chat-ops-selftest --open-url rhodes://chat/<sessionId>`
//  （--selftest 提供 Documents/ios-shell-selftest.log 落盘；本自动化在 SelfTestDriver 完成后运行，
//   结束时追加 summary 再 flush 一次，保证证据完整）。
//
//  证据：NSLog + `xcrun simctl spawn booted log show` + Documents/ios-shell-selftest.log。
//

import Foundation
import UIKit

@MainActor
enum ChatOpsAutomation {
    static let isEnabled = ProcessInfo.processInfo.arguments.contains("--chat-ops-selftest")
    private static var didRun = false

    static func runIfNeeded(model: ChatViewModel) async {
        guard isEnabled, !didRun else { return }
        didRun = true
        SelfTestLog.add("=== chat-ops selftest start ===")
        let bridge = AppGraphClient.shared
        let sessionId = model.sessionIdForAutomation

        // 等待 SelfTestDriver 的整壳自检收尾（避免两条自动化同时向同一会话发消息互相干扰）
        if ProcessInfo.processInfo.arguments.contains("--selftest") {
            let deadline = Date().addingTimeInterval(60)
            while Date() < deadline {
                let done = SelfTestLog.snapshot().contains { $0.contains("=== selftest done ===") }
                if done { break }
                try? await Task.sleep(nanoseconds: 200_000_000)
            }
        }
        let streamDeadline = Date().addingTimeInterval(30)
        while model.isStreaming, Date() < streamDeadline {
            try? await Task.sleep(nanoseconds: 200_000_000)
        }
        SelfTestLog.add("chat-ops provider=\(bridge.providerStatus().isDemo ? "demo" : "real") session=\(sessionId)")

        await run(bridge: bridge, sessionId: sessionId, model: model)

        // 刷新时间线（证据截图展示真实 DB 结果），并展示引用条 + 可选全屏图
        await model.refresh()
        if let incoming = model.messages.last(where: { $0.side == .incoming && !$0.text.isEmpty }) {
            model.quotedMessage = incoming
            SelfTestLog.add("chat-ops quote placeholder shown for messageId=\(incoming.id)")
        }
        if ProcessInfo.processInfo.arguments.contains("--chat-ops-fullscreen"),
           let imageMessage = model.messages.last(where: { $0.isImage && model.imageURL(for: $0) != nil }) {
            model.fullscreenImage = imageMessage
            SelfTestLog.add("chat-ops fullscreen image opened messageId=\(imageMessage.id) imageId=\(imageMessage.imageId)")
            try? await Task.sleep(nanoseconds: 1_500_000_000)
        }
        SelfTestLog.add("=== chat-ops selftest done ===")
        SelfTestLog.flushToDisk(summary: "T42 chat-ops selftest（撤回/重试/重说/继续说/图片/本地删除）")
    }

    // MARK: - 主流程

    private static func run(bridge: AppGraphClient, sessionId: String, model: ChatViewModel) async {
        // 0) 基线
        guard let baseline = await page(bridge, sessionId) else {
            SelfTestLog.add("chat-ops baseline FAILED (no history page)")
            return
        }
        let baseCount = baseline.totalCount
        SelfTestLog.add("chat-ops baseline total=\(baseCount) newestId=\(baseline.items.first?.id ?? 0)")

        // 1) 发一条文本 → 拿到 user id + AI 回复 id
        let marker = "T42自检：你好，这是一条消息操作测试。"
        _ = await sendText(bridge, sessionId: sessionId, text: marker)
        guard let afterSend = await page(bridge, sessionId),
              let userMessage = afterSend.items.first(where: { $0.isMe && $0.text.contains("T42自检") }),
              let replyMessage = afterSend.items.first(where: { !$0.isMe && $0.type == "ai_json" }) else {
            SelfTestLog.add("chat-ops send FAILED (missing message/reply)")
            return
        }
        let replyId = replyMessage.id
        let replyTextBefore = replyMessage.text
        SelfTestLog.add(
            "chat-ops send ok total=\(afterSend.totalCount) user=\(userMessage.id) reply=\(replyId) replyChars=\(replyTextBefore.count)"
        )

        // 2) 撤回自己的消息（真实 recallMessage：turn 终态 + 删行 + 会话预览刷新）
        let recallError = await bridge.recallMessage(sessionId: sessionId, messageId: userMessage.id)
        let afterRecall = await page(bridge, sessionId)
        let recalledGone = afterRecall?.items.contains(where: { $0.id == userMessage.id }) == false
        SelfTestLog.add(
            "chat-ops recall messageId=\(userMessage.id) error=\(recallError ?? "-") gone=\(recalledGone) " +
            "total=\(afterRecall?.totalCount ?? -1) (before=\(afterSend.totalCount)) PASS=\(recallError == nil && recalledGone)"
        )

        // 3) 重试未送达消息（自检夹具构造 send_failed → 真实 retryFailedMessage 重发）
        let seedResult = await bridge.debugSeedFailedMessage(sessionId: sessionId, text: "T42自检：未送达消息，等待重试。")
        switch seedResult {
        case .failure(let seedError):
            SelfTestLog.add("chat-ops retry SKIPPED (seed failed: \(seedError.message))")
        case .success(let failedId):
            let retryError = await bridge.retryMessage(sessionId: sessionId, messageId: failedId)
            let afterRetry = await page(bridge, sessionId)
            let retried = afterRetry?.items.first(where: { $0.id == failedId })
            let retryOk = retryError == nil && retried != nil && retried?.type != "send_failed"
            SelfTestLog.add(
                "chat-ops retry failedId=\(failedId) error=\(retryError ?? "-") type=\(retried?.type ?? "-") " +
                "total=\(afterRetry?.totalCount ?? -1) PASS=\(retryOk)"
            )
            // 3.5) 本地删除（只删本机 DB 行；对重试后的普通消息执行，保留后面图片消息给截图证据）
            let deleteError = await bridge.deleteMessageLocal(sessionId: sessionId, messageId: failedId)
            let afterDelete = await page(bridge, sessionId)
            let deleted = afterDelete?.items.contains(where: { $0.id == failedId }) == false
            SelfTestLog.add(
                "chat-ops deleteLocal messageId=\(failedId) error=\(deleteError ?? "-") gone=\(deleted) " +
                "total=\(afterDelete?.totalCount ?? -1) PASS=\(deleteError == nil && deleted)"
            )
        }

        // 4) 重说 AI 回复（真实 regenerateAiMessage：乐观锁替换；失败保留原回复）
        let regenError = await bridge.regenerateMessage(sessionId: sessionId, messageId: replyId)
        let regenerated = await bridge.findMessage(id: replyId)
        let regenChanged = (regenerated?.text ?? replyTextBefore) != replyTextBefore
        SelfTestLog.add(
            "chat-ops regenerate messageId=\(replyId) error=\(regenError ?? "-") changed=\(regenChanged) " +
            "newChars=\((regenerated?.text ?? "").count) PASS=\(regenError == nil && regenChanged)"
        )

        // 5) 继续说（追加一条新 ai_json 消息）
        let countBeforeContinue = (await page(bridge, sessionId))?.totalCount ?? 0
        let continueError = await bridge.continueMessage(sessionId: sessionId, messageId: replyId)
        let afterContinue = await page(bridge, sessionId)
        let continueOk = continueError == nil && (afterContinue?.totalCount ?? 0) > countBeforeContinue
        SelfTestLog.add(
            "chat-ops continue messageId=\(replyId) error=\(continueError ?? "-") total=\(countBeforeContinue)->\(afterContinue?.totalCount ?? -1) PASS=\(continueOk)"
        )

        // 6) 图片消息：本地 PNG 夹具 → shared 媒体入库 → 真实 sendImageMessage（占位落库/识图/角色回复）
        if let fixturePath = writeImageFixture() {
            let ingestResult = await bridge.ingestChatImage(sourcePath: fixturePath)
            try? FileManager.default.removeItem(atPath: fixturePath)
            switch ingestResult {
            case .failure(let error):
                SelfTestLog.add("chat-ops image ingest FAILED error=\(error.message)")
            case .success(let imageId):
                let countBeforeImage = (await page(bridge, sessionId))?.totalCount ?? 0
                let imageResult = await sendImage(bridge, sessionId: sessionId, imageId: imageId)
                let afterImage = await page(bridge, sessionId)
                let imageMessage = afterImage?.items.first(where: { $0.type == "image" && $0.imageId == imageId })
                let imageOk = imageResult.ok && imageMessage != nil && (afterImage?.totalCount ?? 0) > countBeforeImage
                SelfTestLog.add(
                    "chat-ops image id=\(imageId) ok=\(imageResult.ok) demo=\(imageResult.isDemo) " +
                    "messageId=\(imageMessage?.id ?? 0) total=\(countBeforeImage)->\(afterImage?.totalCount ?? -1) " +
                    "error=\(imageResult.error) PASS=\(imageOk)"
                )
                // 6.5) 图片全屏数据可达（URL 解析成功 = 渲染层可显示）
                if let path = imageMessage.map({ bridge.imagePath(for: $0.imageId) }), !path.isEmpty {
                    SelfTestLog.add("chat-ops image resolve path=\(path.suffix(40)) PASS=true")
                } else {
                    SelfTestLog.add("chat-ops image resolve FAILED")
                }
                // 6.6) 图片消息保留在时间线（截图证据：图片气泡 + 角色回复）
                SelfTestLog.add("chat-ops image kept for screenshot messageId=\(imageMessage?.id ?? 0)")
            }
        } else {
            SelfTestLog.add("chat-ops image SKIPPED (fixture write failed)")
        }

        // 7) 分段撤回（真实 recallMessageSegment；全部段撤回时引擎删整条）
        let segmentCountAfter = (await page(bridge, sessionId))?.totalCount ?? 0
        let segmentError = await bridge.recallMessage(
            sessionId: sessionId,
            messageId: replyId,
            segmentIndex: 0
        )
        let afterSegment = await page(bridge, sessionId)
        let segmentGone = afterSegment?.items.contains(where: { $0.id == replyId }) == false
        let segmentOk = segmentError == nil
        SelfTestLog.add(
            "chat-ops recallSegment messageId=\(replyId) index=0 error=\(segmentError ?? "-") " +
            "removedWholeMessage=\(segmentGone) total=\(segmentCountAfter)->\(afterSegment?.totalCount ?? -1) PASS=\(segmentOk)"
        )

        // 8) 引用（占位，不发送）：记录引擎不支持说明，UI 层引用条状态由 model 展示
        SelfTestLog.add("chat-ops quote placeholder：引擎无 replyTo 字段，引用条仅 UI 展示，发送按普通消息（记录一次）")
        _ = model
    }

    // MARK: - 桥接辅助

    private static func page(_ bridge: AppGraphClient, _ sessionId: String) async -> MessagePage? {
        switch await bridge.loadMessagePage(sessionId: sessionId, beforeId: 0, limit: 50) {
        case .success(let page): return page
        case .failure(let error):
            SelfTestLog.add("chat-ops history FAILED error=\(error.message)")
            return nil
        }
    }

    private static func sendText(_ bridge: AppGraphClient, sessionId: String, text: String) async -> ChatRoundResult {
        await withCheckedContinuation { continuation in
            _ = bridge.sendChat(
                sessionId: sessionId,
                text: text,
                onDelta: { _ in },
                onDone: { result in continuation.resume(returning: result) }
            )
        }
    }

    private static func sendImage(_ bridge: AppGraphClient, sessionId: String, imageId: String) async -> ChatRoundResult {
        await withCheckedContinuation { continuation in
            _ = bridge.sendImageChat(
                sessionId: sessionId,
                imageId: imageId,
                caption: "T42自检图片",
                onDelta: { _ in },
                onDone: { result in continuation.resume(returning: result) }
            )
        }
    }

    /// PNG 夹具（UIGraphicsImageRenderer 现场绘制 320×200 渐变 + 文案，无需相册权限；
    /// 验证媒体入库/识图/落库链路，同时给截图一个可见的图片气泡）。
    private static func writeImageFixture() -> String? {
        let size = CGSize(width: 320, height: 200)
        let renderer = UIGraphicsImageRenderer(size: size)
        let image = renderer.image { context in
            let colors = [UIColor(red: 0.98, green: 0.45, blue: 0.19, alpha: 1).cgColor,
                          UIColor(red: 0.55, green: 0.25, blue: 0.75, alpha: 1).cgColor] as CFArray
            if let gradient = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colors, locations: [0, 1]) {
                context.cgContext.drawLinearGradient(
                    gradient,
                    start: CGPoint(x: 0, y: 0),
                    end: CGPoint(x: size.width, y: size.height),
                    options: []
                )
            }
            let text = "T42 图片自检"
            let attributes: [NSAttributedString.Key: Any] = [
                .font: UIFont.systemFont(ofSize: 26, weight: .bold),
                .foregroundColor: UIColor.white,
            ]
            let textSize = text.size(withAttributes: attributes)
            text.draw(
                at: CGPoint(x: (size.width - textSize.width) / 2, y: (size.height - textSize.height) / 2),
                withAttributes: attributes
            )
        }
        guard let data = image.pngData() else { return nil }
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("rhodes-t42-fixture-\(UUID().uuidString).png")
        do {
            try data.write(to: url, options: .atomic)
            return url.path
        } catch {
            return nil
        }
    }
}

extension Notification.Name {
    /// T42 自检完成后请求聊天页刷新（保留给外部钩子使用）
    static let chatOpsSelftestRefresh = Notification.Name("rhodeslink.chatops.selftest.refresh")
}
