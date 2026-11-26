//
//  RankingClient.swift
//  Swift 侧聊天排行榜 / 消费统计包装（T54）：
//  - Kotlin `RankingBridge` / DTO 只在本文件出现，SwiftUI 只接触本文件的 struct / async 方法；
//  - 真实 SQLite（chat_messages）+ 真实 settings（token_in/out、daily_in/out、intimacy_daily_*），无 mock；
//  - 口径对齐 Android oracle：RankingScreen.kt:53 / DataViewModel.kt:91-104 / TokenStatsScreen.kt:65。
//

import Foundation
import Shared

// MARK: - 维度 / 区间

enum RankingDimension: String, CaseIterable, Identifiable {
    case messages
    case days
    case consumption

    var id: String { rawValue }

    /// 分段控件标题（消费维度明确标注「估算」）
    var label: String {
        switch self {
        case .messages: return "消息数"
        case .days: return "互动天数"
        case .consumption: return "消费（估算）"
        }
    }

    var valueColumnTitle: String {
        switch self {
        case .messages: return "消息数"
        case .days: return "互动天数"
        case .consumption: return "消费(字)"
        }
    }
}

enum RankingRange: String, CaseIterable, Identifiable {
    case yesterday
    case history

    var id: String { rawValue }
    var label: String { self == .yesterday ? "昨日" : "历史" }
}

enum TokenStatsRange: String, CaseIterable, Identifiable {
    case today
    case week
    case all

    var id: String { rawValue }
    var label: String {
        switch self {
        case .today: return "今日"
        case .week: return "本周"
        case .all: return "全部"
        }
    }
}

// MARK: - Swift 侧数据模型（与 Shared RankingBridge DTO 一一对应）

struct RankingEntryItem: Identifiable, Hashable {
    let rank: Int
    let operatorId: String
    let operatorName: String
    /// chat_messages.senderName 原值（oracle SQL 分组键）。
    let senderName: String
    /// operators.avatarUri 原样（"1".."147" 内置 Bundle 头像）；空 = 首字占位。
    let avatarId: String
    /// 单位由维度决定：条 / 天 / 字。
    let value: Int64
    let valueText: String
    let unit: String
    /// 好感列：intimacy_daily_<operatorId>（oracle 同源，带正号）。
    let secondaryText: String

    var id: String { operatorId }

    init(dto: RankingEntryDto) {
        rank = Int(dto.rank)
        operatorId = dto.operatorId
        operatorName = dto.operatorName
        senderName = dto.senderName
        avatarId = dto.avatarId
        value = dto.value
        valueText = dto.valueText
        unit = dto.unit
        secondaryText = dto.secondaryText
    }
}

struct RankingSnapshotItem {
    let dimension: String
    let dimensionLabel: String
    let range: String
    let rangeLabel: String
    let estimated: Bool
    let entries: [RankingEntryItem]
    let totalValue: Int64
    let generatedAt: Int64
    let note: String

    init(dto: RankingSnapshotDto) {
        dimension = dto.dimension
        dimensionLabel = dto.dimensionLabel
        range = dto.range
        rangeLabel = dto.rangeLabel
        estimated = dto.estimated
        entries = dto.entries.map(RankingEntryItem.init(dto:))
        totalValue = dto.totalValue
        generatedAt = dto.generatedAt
        note = dto.note
    }
}

struct TokenCategoryItem: Identifiable {
    let name: String
    let inputTokens: Int
    let outputTokens: Int
    let totalTokens: Int

    var id: String { name }

    init(dto: TokenCategoryDto) {
        name = dto.name
        inputTokens = Int(dto.inputTokens)
        outputTokens = Int(dto.outputTokens)
        totalTokens = Int(dto.totalTokens)
    }
}

struct TokenStatsItem {
    let range: String
    let rangeLabel: String
    let inputTokens: Int
    let outputTokens: Int
    let totalTokens: Int
    let categories: [TokenCategoryItem]
    let generatedAt: Int64

    init(dto: TokenStatsDto) {
        range = dto.range
        rangeLabel = dto.rangeLabel
        inputTokens = Int(dto.inputTokens)
        outputTokens = Int(dto.outputTokens)
        totalTokens = Int(dto.totalTokens)
        categories = dto.categories.map(TokenCategoryItem.init(dto:))
        generatedAt = dto.generatedAt
    }
}

/// oracle `TokenStatsScreen.formatTokens`：≥1M → `1.2M`；≥1K → `1.2K`；否则原值。
func formatRankingTokens(_ value: Int) -> String {
    if value >= 1_000_000 {
        return String(format: "%.1fM", Double(value) / 1_000_000)
    }
    if value >= 1_000 {
        return String(format: "%.1fK", Double(value) / 1_000)
    }
    return "\(value)"
}

// MARK: - AppGraphClient 扩展（排行榜 / 消费统计）

extension AppGraphClient {
    /// 排行榜快照（维度 + 区间）。返回 nil = 失败（error 非空）。
    func loadRanking(dimension: RankingDimension, range: RankingRange) async -> Result<RankingSnapshotItem, BridgeError> {
        await withCheckedContinuation { continuation in
            rankingBridge.ranking(dimension: dimension.rawValue, range: range.rawValue) { dto, error in
                assertMainQueue("ranking.ranking")
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else if let dto {
                    continuation.resume(returning: .success(RankingSnapshotItem(dto: dto)))
                } else {
                    continuation.resume(returning: .failure(BridgeError(message: "排行榜数据为空")))
                }
            }
        }
    }

    /// 消费统计（今日 / 本周 / 全部，oracle TokenStatsScreen 三分段）。
    func loadTokenStats(range: TokenStatsRange) async -> Result<TokenStatsItem, BridgeError> {
        await withCheckedContinuation { continuation in
            tokenStatsBridgeCall(range: range) { result in
                continuation.resume(returning: result)
            }
        }
    }

    private func tokenStatsBridgeCall(range: TokenStatsRange, completion: @escaping (Result<TokenStatsItem, BridgeError>) -> Void) {
        rankingBridge.tokenStats(range: range.rawValue) { dto, error in
            assertMainQueue("ranking.tokenStats")
            if let error {
                completion(.failure(BridgeError(message: error)))
            } else if let dto {
                completion(.success(TokenStatsItem(dto: dto)))
            } else {
                completion(.failure(BridgeError(message: "消费统计数据为空")))
            }
        }
    }

    /// 桥层端到端自检（夹具 / 三维度 / 昨日·历史 / DB 核对 / Token 三分段）。
    /// 返回报告文本；error 非空 = 桥调用失败。
    func runRankingSelfTest() async -> (report: String, error: BridgeError?) {
        await withCheckedContinuation { continuation in
            rankingBridge.debugRunSelfTest { report in
                assertMainQueue("ranking.selftest")
                let text = report
                let failed = text.contains("FAIL")
                continuation.resume(returning: (text, failed ? BridgeError(message: "自检失败") : nil))
            }
        }
    }
}
