//
//  ChatOpsClient.swift
//  T42 聊天消息操作桥的 Swift 包装（独立文件，遵循 ios-bridge-spec 纪律：
//  Kotlin 类型只在本文件内出现，SwiftUI 只接触 struct / async 方法 / ChatSendHandle）。
//
//  对应 shared 侧 `ChatEngineBridge`（T42 扩展）：
//  - 撤回（整条 / 分段）、重试失败消息、重新生成（重说）、继续生成、本地删除、图片重发；
//  - 图片入库（PhotosPicker → 媒体 ID）与图片消息发送；
//  - 图片 ID → 本机文件路径（渲染层用，同步无 IO 异常）；
//  - 自检夹具（仅 --chat-ops-selftest 使用）。
//
//  回调语义：`async -> String?`，nil = 成功，非 nil = 面向用户的中文错误原因。
//

import Foundation
import Shared

@MainActor
extension AppGraphClient {
    // MARK: - 消息操作（对齐 Android ChatViewModel 同名入口）

    /// 撤回整条消息；segmentIndex 非 nil 时走引擎 recallMessageSegment（全段撤回 → 引擎删整条）。
    func recallMessage(sessionId: String, messageId: Int64, segmentIndex: Int32? = nil) async -> String? {
        await withCheckedContinuation { continuation in
            let finish: (String?) -> Void = { error in
                assertMainQueue("recallMessage")
                continuation.resume(returning: error)
            }
            if let segmentIndex {
                chatEngineBridge.recallMessageSegment(
                    sessionId: sessionId,
                    messageId: messageId,
                    segmentIndex: segmentIndex,
                    onDone: finish
                )
            } else {
                chatEngineBridge.recallMessage(
                    sessionId: sessionId,
                    messageId: messageId,
                    onDone: finish
                )
            }
        }
    }

    /// 重试未送达消息（引擎仅接受 send_failed / gift_reply_failed）。
    func retryMessage(sessionId: String, messageId: Int64) async -> String? {
        await withCheckedContinuation { continuation in
            chatEngineBridge.retryMessage(
                sessionId: sessionId,
                messageId: messageId,
                onDone: { error in
                    assertMainQueue("retryMessage")
                    continuation.resume(returning: error)
                }
            )
        }
    }

    /// 重新生成 AI 回复（引擎乐观锁替换；失败保留原回复）。
    func regenerateMessage(sessionId: String, messageId: Int64) async -> String? {
        await withCheckedContinuation { continuation in
            chatEngineBridge.regenerateMessage(
                sessionId: sessionId,
                messageId: messageId,
                onDone: { error in
                    assertMainQueue("regenerateMessage")
                    continuation.resume(returning: error)
                }
            )
        }
    }

    /// 继续生成（追加一条新 ai_json 消息）。
    func continueMessage(sessionId: String, messageId: Int64) async -> String? {
        await withCheckedContinuation { continuation in
            chatEngineBridge.continueMessage(
                sessionId: sessionId,
                messageId: messageId,
                onDone: { error in
                    assertMainQueue("continueMessage")
                    continuation.resume(returning: error)
                }
            )
        }
    }

    /// 图片消息重发（仅识别失败/发送失败的 image 消息）。
    func retryImageMessage(sessionId: String, messageId: Int64) async -> String? {
        await withCheckedContinuation { continuation in
            chatEngineBridge.retryImageMessage(
                sessionId: sessionId,
                messageId: messageId,
                onDone: { error in
                    assertMainQueue("retryImageMessage")
                    continuation.resume(returning: error)
                }
            )
        }
    }

    /// 本地删除消息（只删本机 DB 行 + 刷新会话预览，不产生撤回语义）。
    func deleteMessageLocal(sessionId: String, messageId: Int64) async -> String? {
        await withCheckedContinuation { continuation in
            chatEngineBridge.deleteMessageLocal(
                sessionId: sessionId,
                messageId: messageId,
                onDone: { error in
                    assertMainQueue("deleteMessageLocal")
                    continuation.resume(returning: error)
                }
            )
        }
    }

    // MARK: - 图片

    /// 图片入库：本地文件 → shared 媒体 ID（`media/<uuid>.<ext>`）。
    func ingestChatImage(sourcePath: String) async -> Result<String, BridgeError> {
        await withCheckedContinuation { continuation in
            chatEngineBridge.ingestImage(
                sourcePath: sourcePath,
                onDone: { mediaId, error in
                    assertMainQueue("ingestImage")
                    if let error {
                        continuation.resume(returning: .failure(BridgeError(message: error)))
                    } else {
                        continuation.resume(returning: .success(mediaId))
                    }
                }
            )
        }
    }

    /// 图片消息发送（引擎：占位落库 → 识图 → 角色回复 → 整包落库；回调风格与 sendChat 一致）。
    func sendImageChat(
        sessionId: String,
        imageId: String,
        caption: String,
        onDelta: @escaping (String) -> Void,
        onReplySegments: @escaping ([MessageSegmentItem]) -> Void = { _ in },
        onDone: @escaping (ChatRoundResult) -> Void
    ) -> ChatSendHandle {
        let cancellable = chatEngineBridge.sendImage(
            sessionId: sessionId,
            imageId: imageId,
            caption: caption,
            onDelta: { delta in
                assertMainQueue("sendImage.onDelta")
                onDelta(delta)
            },
            onReplySegments: { segments in
                assertMainQueue("sendImage.onReplySegments")
                onReplySegments(segments.map(MessageSegmentItem.init(dto:)))
            },
            onDone: { dto in
                assertMainQueue("sendImage.onDone")
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

    /// 表情目录（19 个绳网表情：id / 资源名 / 情绪 / 画面）。
    func emojiCatalog() -> [EmojiItem] {
        chatEngineBridge.emojiCatalog().map(EmojiItem.init(dto:))
    }

    /// 发送表情（仅线上模式；不调用识图模型，语义以文字送给模型）。
    func sendEmojiChat(
        sessionId: String,
        emojiId: String,
        onDelta: @escaping (String) -> Void,
        onReplySegments: @escaping ([MessageSegmentItem]) -> Void = { _ in },
        onDone: @escaping (ChatRoundResult) -> Void
    ) -> ChatSendHandle {
        let cancellable = chatEngineBridge.sendEmoji(
            sessionId: sessionId,
            emojiId: emojiId,
            onDelta: { delta in
                assertMainQueue("sendEmoji.onDelta")
                onDelta(delta)
            },
            onReplySegments: { segments in
                assertMainQueue("sendEmoji.onReplySegments")
                onReplySegments(segments.map(MessageSegmentItem.init(dto:)))
            },
            onDone: { dto in
                assertMainQueue("sendEmoji.onDone")
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

    /// 图片 ID → 本机绝对路径（http(s) 原样返回；不存在返回 ""）。
    func imagePath(for imageId: String) -> String {
        chatEngineBridge.imagePath(imageId: imageId)
    }

    // MARK: - 自检夹具（仅 --chat-ops-selftest）

    /// T59 自检夹具：写一条真实 ai_json 回复消息（含旁白+台词交错段），仅 `--bubble-split-selftest` 使用。
    func debugSeedAiJsonMessage(
        sessionId: String,
        content: String,
        mode: String,
        timestamp: Int64
    ) async -> Result<Int64, BridgeError> {
        await withCheckedContinuation { continuation in
            chatEngineBridge.debugSeedAiJsonMessage(
                sessionId: sessionId,
                content: content,
                mode: mode,
                timestamp: timestamp,
                onDone: { messageId, error in
                    assertMainQueue("debugSeedAiJsonMessage")
                    if let error {
                        continuation.resume(returning: .failure(BridgeError(message: error)))
                    } else {
                        continuation.resume(returning: .success(messageId.int64Value))
                    }
                }
            )
        }
    }

    /// 写入一条 send_failed 消息作为重试前置状态（演示通道不会自然失败）。
    func debugSeedFailedMessage(sessionId: String, text: String) async -> Result<Int64, BridgeError> {
        await withCheckedContinuation { continuation in
            chatEngineBridge.debugSeedFailedMessage(
                sessionId: sessionId,
                text: text,
                onDone: { messageId, error in
                    assertMainQueue("debugSeedFailedMessage")
                    if let error {
                        continuation.resume(returning: .failure(BridgeError(message: error)))
                    } else {
                        continuation.resume(returning: .success(messageId.int64Value))
                    }
                }
            )
        }
    }
}

/// 表情目录项（Swift 侧视图模型）。
struct EmojiItem: Identifiable, Hashable {
    let id: String
    let assetName: String
    let label: String
    let detail: String

    init(dto: EmojiDto) {
        id = dto.id
        assetName = dto.assetName
        label = dto.label
        detail = dto.description
    }
}
