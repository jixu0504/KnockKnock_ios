//
//  ImpressionsClient.swift
//  Swift 侧「大家的印象」包装（T54）：
//  - Kotlin `ImpressionsBridge` / DTO 只在本文件出现，SwiftUI 只接触本文件的 struct / async 方法；
//  - 真实 SQLite：memory_items（记忆管线 L1/L2/L3）+ 旧版 memories 长期印象只读兜底，无 mock；
//  - 过滤/排序/级别标签口径对齐 Android oracle：MainViewModel.kt:1527-1551、ImpressionsScreen.kt:59。
//

import Foundation
import Shared

// MARK: - Swift 侧数据模型（与 Shared ImpressionsBridge DTO 一一对应）

struct ImpressionEntryItem: Identifiable, Hashable {
    /// 列表 key：旧版 memories.id 与 memory_items.id 不同域，使用下标前缀保证唯一。
    let id: String
    let operatorId: String
    let operatorName: String
    /// operators.avatarUri 原样；空 = 首字占位。
    let avatarId: String
    let level: String
    /// 长期印象 / 持续了解 / 互动印象。
    let levelLabel: String
    let memoryType: String
    let memoryTypeLabel: String
    let content: String
    /// memory_items.eventTime（「发生于」文本）；空串 = 无。
    let eventTime: String
    let createdAt: Int64
    let updatedAt: Int64
    let sourceLabel: String
    let importance: Int
    let isLegacy: Bool

    init(dto: ImpressionEntryDto, index: Int) {
        id = "\(index)-\(dto.id)-\(dto.isLegacy ? "legacy" : "item")"
        operatorId = dto.operatorId
        operatorName = dto.operatorName
        avatarId = dto.avatarId
        level = dto.level
        levelLabel = dto.levelLabel
        memoryType = dto.memoryType
        memoryTypeLabel = dto.memoryTypeLabel
        content = dto.content
        eventTime = dto.eventTime
        createdAt = dto.createdAt
        updatedAt = dto.updatedAt
        sourceLabel = dto.sourceLabel
        importance = Int(dto.importance)
        isLegacy = dto.isLegacy
    }

    /// oracle `ImpressionsScreen.impressionDate`：优先「发生于 eventTime」，否则「记录于 MM/dd HH:mm」（北京时间）。
    var dateText: String {
        let trimmed = eventTime.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty { return "发生于 \(trimmed)" }
        guard createdAt > 0 else { return "" }
        let millis = createdAt > 10_000_000_000 ? createdAt : createdAt * 1000
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.timeZone = TimeZone(identifier: "Asia/Shanghai")
        formatter.dateFormat = "MM/dd HH:mm"
        return "记录于 \(formatter.string(from: Date(timeIntervalSince1970: Double(millis) / 1000)))"
    }
}

struct ImpressionsSnapshotItem {
    let entries: [ImpressionEntryItem]
    let totalCount: Int
    let longTermCount: Int
    let sustainedCount: Int
    let interactionCount: Int
    let legacyCount: Int
    let generatedAt: Int64
    let note: String

    init(dto: ImpressionsSnapshotDto) {
        entries = dto.entries.enumerated().map { index, item in
            ImpressionEntryItem(dto: item, index: index)
        }
        totalCount = Int(dto.totalCount)
        longTermCount = Int(dto.longTermCount)
        sustainedCount = Int(dto.sustainedCount)
        interactionCount = Int(dto.interactionCount)
        legacyCount = Int(dto.legacyCount)
        generatedAt = dto.generatedAt
        note = dto.note
    }

    /// 三段分组（长期印象 / 持续了解 / 互动印象；空组不展示）。
    var sections: [ImpressionSection] {
        [
            ImpressionSection(level: "L3", title: "长期印象", subtitle: "反复互动后形成的稳定了解", entries: entries.filter { $0.level == "L3" }),
            ImpressionSection(level: "L2", title: "持续了解", subtitle: "经多次互动合并出的偏好、约定和关心点", entries: entries.filter { $0.level == "L2" }),
            ImpressionSection(level: "L1", title: "互动印象", subtitle: "偏好、情绪和身体状态会按记录日期保留", entries: entries.filter { $0.level == "L1" }),
        ].filter { !$0.entries.isEmpty }
    }
}

/// 印象三段分组（oracle ImpressionsScreen 三个 section header）。
struct ImpressionSection: Identifiable {
    let level: String
    let title: String
    let subtitle: String
    let entries: [ImpressionEntryItem]

    var id: String { level }
}

// MARK: - AppGraphClient 扩展（大家的印象）

extension AppGraphClient {
    /// 印象快照（oracle 排序：L3 优先 → updatedAt 降序 → importance 降序）。
    func loadImpressions() async -> Result<ImpressionsSnapshotItem, BridgeError> {
        await withCheckedContinuation { continuation in
            impressionsBridge.list { dto, error in
                assertMainQueue("impressions.list")
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else if let dto {
                    continuation.resume(returning: .success(ImpressionsSnapshotItem(dto: dto)))
                } else {
                    continuation.resume(returning: .failure(BridgeError(message: "印象数据为空")))
                }
            }
        }
    }

    /// 桥层端到端自检（夹具 / 过滤 / 分组 / 排序 / DB 核对）。
    func runImpressionsSelfTest() async -> (report: String, error: BridgeError?) {
        await withCheckedContinuation { continuation in
            impressionsBridge.debugRunSelfTest { report in
                assertMainQueue("impressions.selftest")
                let failed = report.contains("FAIL")
                continuation.resume(returning: (report, failed ? BridgeError(message: "自检失败") : nil))
            }
        }
    }
}
