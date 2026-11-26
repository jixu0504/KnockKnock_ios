//
//  OperatorClient.swift
//  角色人设编辑桥接（T49）：Swift 包装 `OperatorBridge`（shared/iosMain，真实 SQLite：
//  operators + settings 提示词槽位 + MediaRepository 头像入库）。
//
//  与 Android 原版对齐（file:line 见 ios-port/specs/persona-edit-spec.md）：
//  - 新建：OperatorViewModel.saveOperator（OperatorViewModel.kt:27-160）→ OperatorBridge.create
//  - 编辑：同 saveOperator 更新路径 → OperatorBridge.update
//  - 头像：内置数字串 1..147（T16 裁决）/ 相册裁剪方形 ≤1024 → 临时文件 → MediaRepository PICKER
//  - 提示词槽位：`operator_prompt_slot_<id>_{private,group}_{1..3}`
//  - 删除：deleteOperatorWithPrivateData + settings 清理（Android deleteOperators 语义）
//
//  SwiftUI 只接触本文件的 struct/async 方法，不向上暴露 Kotlin 类型。
//

import Foundation
import Shared

// MARK: - Swift 侧数据模型（与 Shared OperatorEditDto 一一对应）

/// 编辑表单数据（创建/编辑共用）。
struct OperatorEditItem: Identifiable, Hashable {
    let id: String
    let name: String
    /// 标签（逗号分隔）↔ operators.title（Android 原版「身份」字段）
    let tags: String
    /// 私聊人设（当前生效槽位内容）↔ operators.privatePrompt
    let persona: String
    /// 简介 ↔ operators.description
    let intro: String
    /// operators.avatarUri 原样："1".."147" 内置头像 / `media/<uuid>.<ext>` 上传头像
    let avatarId: String
    let gender: String
    /// 初始/当前关系 ↔ operators.userRelation
    let relationType: String
    let groupPersona: String
    let privateSlots: [String]
    let groupSlots: [String]
    let privateActiveSlot: Int
    let groupActiveSlot: Int

    init(dto: OperatorEditDto) {
        id = dto.id
        name = dto.name
        tags = dto.tags
        persona = dto.persona
        intro = dto.intro
        avatarId = dto.avatarId
        gender = dto.gender
        relationType = dto.relationType
        groupPersona = dto.groupPersona
        privateSlots = [dto.privateSlot1, dto.privateSlot2, dto.privateSlot3]
        groupSlots = [dto.groupSlot1, dto.groupSlot2, dto.groupSlot3]
        privateActiveSlot = Int(dto.privateActiveSlot)
        groupActiveSlot = Int(dto.groupActiveSlot)
    }

    /// 内置头像编号（1...147），非内置返回 nil。
    var builtinAvatarNumber: Int? {
        let trimmed = avatarId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let number = Int(trimmed), (1...147).contains(number) else { return nil }
        return number
    }

    /// 上传头像媒体 ID（`media/<uuid>.<ext>`）。
    var isUploadedAvatar: Bool {
        avatarId.hasPrefix("media/")
    }
}

/// 保存草稿（Swift → Kotlin OperatorDraftDto）。
struct OperatorDraft {
    var id: String = ""
    var name: String = ""
    var tags: String = ""
    var persona: String = ""
    var intro: String = ""
    var avatarId: String = ""
    var gender: String = ""
    var relationType: String = ""
    var groupPersona: String = ""
    var privateSlots: [String] = ["", "", ""]
    var groupSlots: [String] = ["", "", ""]
    var privateActiveSlot: Int = 1
    var groupActiveSlot: Int = 1

    func toDto() -> OperatorDraftDto {
        let priv = privateSlots + Array(repeating: "", count: max(0, 3 - privateSlots.count))
        let group = groupSlots + Array(repeating: "", count: max(0, 3 - groupSlots.count))
        return OperatorDraftDto(
            id: id,
            name: name,
            tags: tags,
            persona: persona,
            intro: intro,
            avatarId: avatarId,
            gender: gender,
            relationType: relationType,
            groupPersona: groupPersona,
            privateSlot1: priv[0],
            privateSlot2: priv[1],
            privateSlot3: priv[2],
            groupSlot1: group[0],
            groupSlot2: group[1],
            groupSlot3: group[2],
            privateActiveSlot: Int32(privateActiveSlot),
            groupActiveSlot: Int32(groupActiveSlot)
        )
    }
}

/// 关系类型选项（code = Android RelationshipType 枚举名，label = 中文标签）。
struct OperatorRelationType: Identifiable, Hashable {
    let code: String
    let label: String

    var id: String { code }

    init(dto: RelationTypeDto) {
        code = dto.code
        label = dto.label
    }
}

// MARK: - AppGraphClient 角色编辑扩展（Kotlin 回调 → async/await）

extension AppGraphClient {

    /// 读取编辑表单（真实 operators 行 + 提示词槽位）。
    func loadOperatorEdit(id: String) async -> Result<OperatorEditItem, BridgeError> {
        await withCheckedContinuation { continuation in
            operatorBridge.load(operatorId: id) { dto, error in
                assertMainQueue()
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else if let dto {
                    continuation.resume(returning: .success(OperatorEditItem(dto: dto)))
                } else {
                    continuation.resume(returning: .failure(BridgeError(message: "角色不存在")))
                }
            }
        }
    }

    /// 新建角色（桥生成 `custom_<uuid>` id）；返回保存后表单。
    func createOperator(_ draft: OperatorDraft) async -> Result<OperatorEditItem, BridgeError> {
        await withCheckedContinuation { continuation in
            operatorBridge.create(draft: draft.toDto()) { dto, error in
                assertMainQueue()
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else if let dto {
                    continuation.resume(returning: .success(OperatorEditItem(dto: dto)))
                } else {
                    continuation.resume(returning: .failure(BridgeError(message: "新建角色失败")))
                }
            }
        }
    }

    /// 更新已有角色（id 必填）；返回保存后表单。
    func updateOperator(_ draft: OperatorDraft) async -> Result<OperatorEditItem, BridgeError> {
        await withCheckedContinuation { continuation in
            operatorBridge.update(draft: draft.toDto()) { dto, error in
                assertMainQueue()
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else if let dto {
                    continuation.resume(returning: .success(OperatorEditItem(dto: dto)))
                } else {
                    continuation.resume(returning: .failure(BridgeError(message: "保存角色失败")))
                }
            }
        }
    }

    /// 删除角色（含私聊会话/记忆/关系/知识库关联 + settings 槽位清理）。
    func deleteOperator(id: String) async -> Result<String, BridgeError> {
        await withCheckedContinuation { continuation in
            operatorBridge.delete(operatorId: id) { deletedId, error in
                assertMainQueue()
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else if let deletedId {
                    continuation.resume(returning: .success(deletedId))
                } else {
                    continuation.resume(returning: .failure(BridgeError(message: "删除角色失败")))
                }
            }
        }
    }

    /// 选中内置头像（1...147）→ operators.avatarUri 数字串。
    func setOperatorAvatarBuiltin(id: String, number: Int) async -> Result<String, BridgeError> {
        await withCheckedContinuation { continuation in
            operatorBridge.setAvatarBuiltin(operatorId: id, avatarNumber: Int32(number)) { value, error in
                assertMainQueue()
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else if let value {
                    continuation.resume(returning: .success(value))
                } else {
                    continuation.resume(returning: .failure(BridgeError(message: "设置头像失败")))
                }
            }
        }
    }

    /// 相册头像：Swift 已方形裁剪 ≤1024 并写临时文件；桥内 MediaRepository(PICKER) 入库。
    func setOperatorAvatarFromFile(id: String, path: String) async -> Result<String, BridgeError> {
        await withCheckedContinuation { continuation in
            operatorBridge.setAvatarFromFile(operatorId: id, sourcePath: path) { mediaId, error in
                assertMainQueue()
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else if let mediaId {
                    continuation.resume(returning: .success(mediaId))
                } else {
                    continuation.resume(returning: .failure(BridgeError(message: "头像入库失败")))
                }
            }
        }
    }

    /// 关系类型选项（24 种，值域与 Android RelationshipType 枚举一致）。
    func operatorRelationTypes() -> [OperatorRelationType] {
        operatorBridge.relationTypes().map(OperatorRelationType.init(dto:))
    }

    /// 媒体 ID → 本地 file:// URL（头像预览）；非媒体 ID / 不存在返回 ""。
    func operatorMediaPath(mediaId: String) -> String {
        operatorBridge.mediaPath(mediaId: mediaId)
    }
}
