//
//  DiaryClient.swift
//  Swift 侧干员日记包装（T51）：
//  - Kotlin `DiaryBridge` / DTO 只在本文件出现，SwiftUI 只接触本文件的 struct / async 方法；
//  - 真实 SQLite（diaries 表）+ 真实 settings（diary_read_at_<operatorId>），无 mock；
//  - 未读语义对齐 Android oracle：干员最新日记 createdAt > 该干员 diary_read_at → 未读。
//

import Foundation
import Shared

// MARK: - Swift 侧数据模型（与 Shared DiaryBridge DTO 一一对应）

struct DiaryEntryItem: Identifiable, Hashable {
    let id: Int64
    let operatorId: String
    let operatorName: String
    /// operators.avatarUri 原样（"1".."147" 内置 Bundle 头像）；空 = 首字占位。
    let avatarId: String
    /// diaries.date（yyyy-MM-dd，北京时间）。
    let date: String
    /// 展示标题（「干员名 · 日期」，表无标题列，由 shared 派生）。
    let title: String
    /// 摘要（正文前 96 字）。
    let summary: String
    let createdAt: Int64
    let version: Int
    let isUnread: Bool

    init(dto: DiaryEntryDto) {
        id = dto.id
        operatorId = dto.operatorId
        operatorName = dto.operatorName
        avatarId = dto.avatarId
        date = dto.date
        title = dto.title
        summary = dto.summary
        createdAt = dto.createdAt
        version = Int(dto.version)
        isUnread = dto.isUnread
    }

    /// 列表时间：今天显示 HH:mm，否则 yyyy/M/d。
    var timeText: String {
        guard createdAt > 0 else { return date }
        let millis = createdAt > 10_000_000_000 ? createdAt : createdAt * 1000
        let dateValue = Date(timeIntervalSince1970: Double(millis) / 1000)
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        if Calendar.current.isDateInToday(dateValue) {
            formatter.dateFormat = "HH:mm"
        } else {
            formatter.dateFormat = "yyyy/M/d"
        }
        return formatter.string(from: dateValue)
    }
}

struct DiaryDetailItem: Hashable {
    let id: Int64
    let operatorId: String
    let operatorName: String
    let avatarId: String
    let date: String
    let title: String
    /// diaries.content 全文。
    let content: String
    let createdAt: Int64
    let version: Int
    let isUnread: Bool

    init(dto: DiaryDetailDto) {
        id = dto.id
        operatorId = dto.operatorId
        operatorName = dto.operatorName
        avatarId = dto.avatarId
        date = dto.date
        title = dto.title
        content = dto.content
        createdAt = dto.createdAt
        version = Int(dto.version)
        isUnread = dto.isUnread
    }
}

struct DiarySnapshotItem {
    let items: [DiaryEntryItem]
    let totalCount: Int
    /// 未读干员数（= Android getUnreadDiaryCount）。
    let unreadCount: Int
    let unreadOnly: Bool

    init(dto: DiaryListDto) {
        items = dto.items.map(DiaryEntryItem.init(dto:))
        totalCount = Int(dto.totalCount)
        unreadCount = Int(dto.unreadCount)
        unreadOnly = dto.unreadOnly
    }
}

// MARK: - AppGraphClient 扩展（干员日记）

extension AppGraphClient {
    /// 日记列表（createdAt 倒序；unreadOnly = 只返回所属干员未读的条目）。
    func loadDiaryList(unreadOnly: Bool) async -> Result<DiarySnapshotItem, BridgeError> {
        await withCheckedContinuation { continuation in
            diaryBridge.list(unreadOnly: unreadOnly) { list, error in
                assertMainQueue("diary.list")
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else {
                    continuation.resume(returning: .success(DiarySnapshotItem(dto: list)))
                }
            }
        }
    }

    /// 单篇全文。
    func loadDiaryDetail(id: Int64) async -> Result<DiaryDetailItem, BridgeError> {
        await withCheckedContinuation { continuation in
            diaryBridge.detail(id: id) { detail, error in
                assertMainQueue("diary.detail")
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else if let detail {
                    continuation.resume(returning: .success(DiaryDetailItem(dto: detail)))
                } else {
                    continuation.resume(returning: .failure(BridgeError(message: "日记不存在（可能已被删除）")))
                }
            }
        }
    }

    /// 标记某干员日记已读（写 settings diary_read_at_<operatorId> = 最新 createdAt）。
    /// 回调剩余未读干员数。
    func markDiaryRead(operatorId: String) async -> Result<Int, BridgeError> {
        await withCheckedContinuation { continuation in
            diaryBridge.markRead(operatorId: operatorId) { remaining, error in
                assertMainQueue("diary.markRead")
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else {
                    continuation.resume(returning: .success(Int(remaining)))
                }
            }
        }
    }

    /// 未读干员数。
    func diaryUnreadCount() async -> Int {
        await withCheckedContinuation { continuation in
            diaryBridge.unreadCount { count, _ in
                assertMainQueue("diary.unreadCount")
                continuation.resume(returning: Int(count))
            }
        }
    }

    /// `--diary-selftest`：桥层端到端自检（夹具/列表/未读/详情/已读/DB settings 核对）。
    func runDiarySelfTest() async -> String {
        await withCheckedContinuation { continuation in
            diaryBridge.debugRunSelfTest { report in
                assertMainQueue("diary.selftest")
                continuation.resume(returning: report)
            }
        }
    }

    /// 预置截图夹具（幂等：DB 空时写 1 条；并把已读位归零以出现未读点）。
    func ensureDiaryFixture() async -> String {
        await withCheckedContinuation { continuation in
            diaryBridge.debugEnsureFixture { line in
                assertMainQueue("diary.fixture")
                continuation.resume(returning: line)
            }
        }
    }
}
