//
//  RelationsClient.swift
//  Swift 侧关系网包装（T57）：
//  - Kotlin `RelationBridge` / DTO 只在本文件出现，SwiftUI 只接触本文件的 struct/method；
//  - 真实 SQLite（relationships 表，单向语义 + oracle 互补关系映射），无 mock；
//  - 对方头像 = operators.avatarUri（内置 "1".."147" / 上传媒体 ID），由 RoleAvatar 渲染。
//

import Foundation
import Shared
import SwiftUI

// MARK: - Swift 侧数据模型（与 Shared RelationBridge DTO 一一对应）

struct RelationEntryItem: Identifiable, Hashable {
    let id: Int64
    let sourceOperatorId: String
    let targetOperatorId: String
    let queriedOperatorId: String
    let relatedOperatorId: String
    let relatedOperatorName: String
    let relatedAvatarId: String
    let type: String
    let typeLabel: String
    let reverseType: String
    let reverseTypeLabel: String
    let relationDescription: String
    let intimacy: Int
    let note: String
    /// "out" = 当前角色对对方；"in" = 对方对当前角色。
    let direction: String

    var isOutgoing: Bool { direction == "out" }
    var directionLabel: String { isOutgoing ? "我对 TA" : "TA 对我" }
    /// 反向标签（无互补类型时为空）。
    var reverseLabelText: String {
        reverseTypeLabel.isEmpty ? "无互补关系" : "反向：\(reverseTypeLabel)"
    }

    init(dto: RelationEntryDto) {
        id = dto.id
        sourceOperatorId = dto.sourceOperatorId
        targetOperatorId = dto.targetOperatorId
        queriedOperatorId = dto.queriedOperatorId
        relatedOperatorId = dto.relatedOperatorId
        relatedOperatorName = dto.relatedOperatorName
        relatedAvatarId = dto.relatedAvatarId
        type = dto.type
        typeLabel = dto.typeLabel
        reverseType = dto.reverseType
        reverseTypeLabel = dto.reverseTypeLabel
        relationDescription = dto.relationDescription
        intimacy = Int(dto.intimacy)
        note = dto.note
        direction = dto.direction
    }
}

struct RelationOperatorItem: Identifiable, Hashable {
    let id: String
    let name: String
    let title: String
    let avatarId: String

    init(dto: RelationOperatorDto) {
        id = dto.id
        name = dto.name
        title = dto.title
        avatarId = dto.avatarId
    }
}

struct RelationTypeOptionItem: Identifiable, Hashable {
    let code: String
    let label: String
    let reverseCode: String
    let reverseLabel: String
    let isFamily: Bool

    var id: String { code }
    var hasReverse: Bool { !reverseCode.isEmpty }

    init(dto: RelationTypeOptionDto) {
        code = dto.code
        label = dto.label
        reverseCode = dto.reverseCode
        reverseLabel = dto.reverseLabel
        isFamily = dto.isFamily
    }

    /// oracle RelationshipUiMapper.color 同族配色（SwiftUI Color）。
    var tint: Color {
        switch code {
        case "BIG_SISTER", "LITTLE_SISTER", "BIG_BROTHER", "LITTLE_BROTHER",
             "MOTHER", "FATHER", "DAUGHTER", "SON", "GUARDIAN", "FAMILY":
            return Color(hex: 0xE53935)
        case "CLOSE_FRIEND", "FRIEND": return Color(hex: 0x00BCD4)
        case "BOSS", "CAPTAIN": return Color(hex: 0xFFC107)
        case "SUBORDINATE", "MEMBER": return Color(hex: 0xFFB74D)
        case "MENTOR", "STUDENT": return Color(hex: 0x8B5CF6)
        case "COMRADE", "TEAMMATE": return Color(hex: 0x9E9E9E)
        case "RIVAL": return Color(hex: 0xEF4444)
        case "LOVE_RIVAL": return Color(hex: 0xD81B60)
        case "CRUSH": return Color(hex: 0xF48FB1)
        case "LOVER": return Color(hex: 0xE91E63)
        default: return Color(hex: 0xBDBDBD)
        }
    }
}

struct RelationMutationItem: Hashable {
    let operatorName: String
    let targetOperatorName: String
    let type: String
    let typeLabel: String
    let intimacy: Int
    let note: String
    let reciprocalCreated: Bool
    let reciprocalType: String
    let reciprocalTypeLabel: String

    init(dto: RelationMutationDto) {
        operatorName = dto.operatorName
        targetOperatorName = dto.targetOperatorName
        type = dto.type
        typeLabel = dto.typeLabel
        intimacy = Int(dto.intimacy)
        note = dto.note
        reciprocalCreated = dto.reciprocalCreated
        reciprocalType = dto.reciprocalType
        reciprocalTypeLabel = dto.reciprocalTypeLabel
    }

    var summary: String {
        var text = "已保存：\(operatorName) 对 \(targetOperatorName) 的【\(typeLabel)】"
        if reciprocalCreated {
            text += "，并创建了反向【\(reciprocalTypeLabel)】"
        }
        return text
    }
}

// MARK: - AppGraphClient 关系网扩展（Kotlin 回调 → async/await）

extension AppGraphClient {
    /// 关系网列表（出向 + 入向；空 = 无关系）。
    func loadRelationEntries(operatorId: String) async -> Result<[RelationEntryItem], BridgeError> {
        await withCheckedContinuation { continuation in
            relationBridge.list(operatorId: operatorId) { dtos, error in
                assertMainQueue("relations.list")
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else {
                    continuation.resume(returning: .success(dtos.map(RelationEntryItem.init(dto:))))
                }
            }
        }
    }

    /// 可添加为关系对象的角色（operators 全表，name ASC）。
    func loadRelationCandidates() async -> Result<[RelationOperatorItem], BridgeError> {
        await withCheckedContinuation { continuation in
            relationBridge.operators { dtos, error in
                assertMainQueue("relations.operators")
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else {
                    continuation.resume(returning: .success(dtos.map(RelationOperatorItem.init(dto:))))
                }
            }
        }
    }

    /// 24 种关系类型目录（含双向互补映射，与 Android RelationshipType 枚举一致）。
    func relationTypeOptions() -> [RelationTypeOptionItem] {
        relationBridge.typeOptions().map(RelationTypeOptionItem.init(dto:))
    }

    /// 添加关系（可选同时创建互补双向关系）。返回保存结果；失败 error 非空。
    func addRelation(
        operatorId: String,
        targetOperatorId: String,
        relationType: String,
        intimacy: Int,
        note: String,
        createReciprocal: Bool
    ) async -> Result<RelationMutationItem, BridgeError> {
        await withCheckedContinuation { continuation in
            relationBridge.add(
                operatorId: operatorId,
                targetOperatorId: targetOperatorId,
                relationType: relationType,
                intimacy: Int32(intimacy),
                note: note,
                createReciprocal: createReciprocal
            ) { dto, error in
                assertMainQueue("relations.add")
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else if let dto {
                    continuation.resume(returning: .success(RelationMutationItem(dto: dto)))
                } else {
                    continuation.resume(returning: .failure(BridgeError(message: "添加关系失败")))
                }
            }
        }
    }

    /// 更新已有关系的类型 / 亲密度 / 备注。
    func updateRelation(
        operatorId: String,
        targetOperatorId: String,
        relationType: String,
        intimacy: Int,
        note: String
    ) async -> Result<RelationMutationItem, BridgeError> {
        await withCheckedContinuation { continuation in
            relationBridge.update(
                operatorId: operatorId,
                targetOperatorId: targetOperatorId,
                relationType: relationType,
                intimacy: Int32(intimacy),
                note: note
            ) { dto, error in
                assertMainQueue("relations.update")
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else if let dto {
                    continuation.resume(returning: .success(RelationMutationItem(dto: dto)))
                } else {
                    continuation.resume(returning: .failure(BridgeError(message: "更新关系失败")))
                }
            }
        }
    }

    /// 删除单向关系（按 source→target 精确删一行，不连带反向关系）。
    @discardableResult
    func removeRelation(sourceOperatorId: String, targetOperatorId: String) async -> Result<Int, BridgeError> {
        await withCheckedContinuation { continuation in
            relationBridge.remove(operatorId: sourceOperatorId, targetOperatorId: targetOperatorId) { count, error in
                assertMainQueue("relations.remove")
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else {
                    continuation.resume(returning: .success(count?.intValue ?? 0))
                }
            }
        }
    }

    /// 清理 `--relations-demo` 写入的演示关系（截图后清理用）。
    @discardableResult
    func cleanRelationDemoFixtures() async -> Result<Int, BridgeError> {
        await withCheckedContinuation { continuation in
            relationBridge.debugCleanDemonstrationFixtures { removed, error in
                assertMainQueue("relations.fixtureClean")
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else {
                    continuation.resume(returning: .success(Int(removed.intValue)))
                }
            }
        }
    }
}
