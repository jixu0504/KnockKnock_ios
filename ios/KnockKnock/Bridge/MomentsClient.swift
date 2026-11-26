//
//  MomentsClient.swift
//  Swift 侧动态广场包装（T50）：
//  - Kotlin `MomentsBridge` / DTO 只在本文件出现，SwiftUI 只接触本文件的 struct/method；
//  - 真实 SQLite（moments / moment_likes / moment_comments），无 mock；
//  - 图片走媒体 ID（`media/<uuid>.<ext>`）→ shared resolve 出本机绝对路径 → UIImage 渲染。
//

import Foundation
import Shared

// MARK: - Swift 侧数据模型（与 Shared MomentsBridge DTO 一一对应）

struct MomentItem: Identifiable, Hashable {
    let id: Int64
    let operatorId: String
    let authorName: String
    /// operators.avatarUri 原样（"1".."147" 内置 Bundle 头像）；用户动态 = user_avatar_uri（可空）。
    let authorAvatarId: String
    /// 正文（图片标记已由 shared 剥离）。
    let text: String
    /// 图片媒体 ID（`media/<uuid>.<ext>`）；纯文本动态为空串。
    let imageId: String
    let isUserPost: Bool
    let isMine: Bool
    let likeCount: Int
    let commentCount: Int
    /// 当前用户（operatorId="user"）是否已点赞。
    let isLiked: Bool
    let createdAt: Int64

    var hasImage: Bool { !imageId.isEmpty }

    /// 相对时间（对齐 Android MomentsScreen.formatTime）
    var createdAtText: String {
        guard createdAt > 0 else { return "" }
        let millis = createdAt > 10_000_000_000 ? createdAt : createdAt * 1000
        let diff = Int64(Date().timeIntervalSince1970 * 1000) - millis
        switch diff {
        case ..<60_000: return "刚刚"
        case ..<3_600_000: return "\(max(1, diff / 60_000))分钟前"
        case ..<86_400_000: return "\(max(1, diff / 3_600_000))小时前"
        default: return "\(max(1, diff / 86_400_000))天前"
        }
    }

    init(dto: MomentDto) {
        id = dto.id
        operatorId = dto.operatorId
        authorName = dto.authorName
        authorAvatarId = dto.authorAvatarId
        text = dto.text
        imageId = dto.imageId
        isUserPost = dto.isUserPost
        isMine = dto.isMine
        likeCount = Int(dto.likeCount)
        commentCount = Int(dto.commentCount)
        isLiked = dto.isLiked
        createdAt = dto.createdAt
    }
}

struct MomentPageItem {
    let items: [MomentItem]
    let hasMore: Bool
    let offset: Int
}

struct MomentCommentItem: Identifiable, Hashable {
    let id: Int64
    let momentId: Int64
    let operatorId: String
    let authorName: String
    let text: String
    let parentCommentId: Int64
    let replyToName: String
    let createdAt: Int64
    let isMine: Bool

    var createdAtText: String {
        guard createdAt > 0 else { return "" }
        let millis = createdAt > 10_000_000_000 ? createdAt : createdAt * 1000
        let diff = Int64(Date().timeIntervalSince1970 * 1000) - millis
        switch diff {
        case ..<60_000: return "刚刚"
        case ..<3_600_000: return "\(max(1, diff / 60_000))分钟前"
        case ..<86_400_000: return "\(max(1, diff / 3_600_000))小时前"
        default: return "\(max(1, diff / 86_400_000))天前"
        }
    }

    init(dto: MomentCommentDto) {
        id = dto.id
        momentId = dto.momentId
        operatorId = dto.operatorId
        authorName = dto.authorName
        text = dto.text
        parentCommentId = dto.parentCommentId
        replyToName = dto.replyToName
        createdAt = dto.createdAt
        isMine = dto.isMine
    }
}

struct MomentLikeResultItem {
    let liked: Bool
    let likeCount: Int

    init(dto: MomentLikeResultDto) {
        liked = dto.liked
        likeCount = Int(dto.likeCount)
    }
}

struct MomentBadgeItem {
    let unreadMoments: Int
    let unreadComments: Int

    init(dto: MomentBadgeDto) {
        unreadMoments = Int(dto.unreadMoments)
        unreadComments = Int(dto.unreadComments)
    }
}

// MARK: - AppGraphClient 扩展（动态广场）

extension AppGraphClient {
    /// 分页读取（offset/limit，倒序）。返回 nil = 失败（error 非空）。
    func loadMoments(offset: Int, limit: Int) async -> Result<MomentPageItem, BridgeError> {
        await withCheckedContinuation { continuation in
            momentsBridge.list(offset: Int32(offset), limit: Int32(limit)) { page, error in
                assertMainQueue("moments.list")
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else {
                    continuation.resume(returning: .success(MomentPageItem(
                        items: page.items.map(MomentItem.init(dto:)),
                        hasMore: page.hasMore,
                        offset: Int(page.offset)
                    )))
                }
            }
        }
    }

    /// 发布（文本 + 可选媒体 ID）。成功返回新动态 id。
    func publishMoment(text: String, imageId: String) async -> Result<Int64, BridgeError> {
        await withCheckedContinuation { continuation in
            momentsBridge.publish(text: text, imageId: imageId) { momentId, error in
                assertMainQueue("moments.publish")
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else {
                    continuation.resume(returning: .success(momentId.int64Value))
                }
            }
        }
    }

    /// 点赞/取消（切换）。失败返回 error。
    func toggleMomentLike(momentId: Int64) async -> Result<MomentLikeResultItem, BridgeError> {
        await withCheckedContinuation { continuation in
            momentsBridge.toggleLike(momentId: momentId) { result, error in
                assertMainQueue("moments.toggleLike")
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else {
                    continuation.resume(returning: .success(MomentLikeResultItem(dto: result)))
                }
            }
        }
    }

    /// 评论列表（createdAt ASC）。
    func loadMomentComments(momentId: Int64) async -> Result<[MomentCommentItem], BridgeError> {
        await withCheckedContinuation { continuation in
            momentsBridge.comments(momentId: momentId) { dtos, error in
                assertMainQueue("moments.comments")
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else {
                    continuation.resume(returning: .success(dtos.map(MomentCommentItem.init(dto:))))
                }
            }
        }
    }

    /// 发表评论（parentCommentId > 0 = 回复）。成功返回新评论 id。
    func addMomentComment(
        momentId: Int64,
        text: String,
        parentCommentId: Int64 = 0,
        replyToName: String = ""
    ) async -> Result<Int64, BridgeError> {
        await withCheckedContinuation { continuation in
            momentsBridge.addComment(
                momentId: momentId,
                text: text,
                parentCommentId: parentCommentId,
                replyToName: replyToName
            ) { commentId, error in
                assertMainQueue("moments.addComment")
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else {
                    continuation.resume(returning: .success(commentId.int64Value))
                }
            }
        }
    }

    /// 删除本人动态；返回 nil = 成功（非本人/不存在返回错误文案）。
    func deleteMoment(momentId: Int64) async -> String? {
        await withCheckedContinuation { continuation in
            momentsBridge.delete(momentId: momentId) { error in
                assertMainQueue("moments.delete")
                continuation.resume(returning: error)
            }
        }
    }

    func loadMomentBadges() async -> MomentBadgeItem? {
        await withCheckedContinuation { continuation in
            momentsBridge.badges { badge, error in
                assertMainQueue("moments.badges")
                continuation.resume(returning: error == nil ? MomentBadgeItem(dto: badge) : nil)
            }
        }
    }

    /// 打开广场：新动态角标清零（last_seen_moment_id 推进）。
    func markMomentsSeen() async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            momentsBridge.markMomentsSeen { _ in
                assertMainQueue("moments.markSeen")
                continuation.resume()
            }
        }
    }

    /// 评论收件箱全部已读。
    func markMomentCommentsRead() async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            momentsBridge.markCommentsRead { _ in
                assertMainQueue("moments.markCommentsRead")
                continuation.resume()
            }
        }
    }

    /// PhotosPicker 本地文件 → 媒体 ID。
    func ingestMomentImage(sourcePath: String) async -> Result<String, BridgeError> {
        await withCheckedContinuation { continuation in
            momentsBridge.ingestImage(sourcePath: sourcePath) { mediaId, error in
                assertMainQueue("moments.ingestImage")
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else {
                    continuation.resume(returning: .success(mediaId))
                }
            }
        }
    }

    /// 媒体 ID → 本机绝对文件路径（缺失返回 ""）。
    func momentImagePath(imageId: String) -> String {
        momentsBridge.imagePath(imageId: imageId)
    }

    func momentUserName() -> String { momentsBridge.userName() }

    func momentUserAvatarId() -> String { momentsBridge.userAvatarId() }
}
