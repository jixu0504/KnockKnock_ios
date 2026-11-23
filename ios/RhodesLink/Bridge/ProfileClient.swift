//
//  ProfileClient.swift
//  Swift 侧用户身份包装（T57）：
//  - Kotlin `IdentityBridge` / DTO 只在本文件出现，SwiftUI 只接触本文件的 struct；
//  - 真实 NSUserDefaults（user_name / user_gender / user_signature）+ 真实 SQLite 统计；
//  - 身份值同时进入 T45 私聊提示词（USER_NAME / USER_GENDER / USER_BIO 占位符）。
//

import Foundation
import Shared

// MARK: - Swift 侧数据模型（与 Shared IdentityBridge DTO 一一对应）

struct UserProfileItem: Hashable {
    let nickname: String
    let gender: String
    let signature: String
    let avatarId: String
    let messageCount: Int
    let memoryCount: Int
    let sessionCount: Int
    let operatorCount: Int
    let relationCount: Int

    init(dto: IdentityProfileDto) {
        nickname = dto.nickname
        gender = dto.gender
        signature = dto.signature
        avatarId = dto.avatarId
        messageCount = Int(dto.messageCount)
        memoryCount = Int(dto.memoryCount)
        sessionCount = Int(dto.sessionCount)
        operatorCount = Int(dto.operatorCount)
        relationCount = Int(dto.relationCount)
    }

    /// 身份卡统计（Android 1.14.27 无用户 level/exp 字段 → 按任务口径展示真实统计）。
    var statRows: [UserProfileStat] {
        [
            UserProfileStat(title: "消息", value: messageCount, systemImage: "bubble.left.and.bubble.right"),
            UserProfileStat(title: "记忆", value: memoryCount, systemImage: "brain.head.profile"),
            UserProfileStat(title: "会话", value: sessionCount, systemImage: "tray.full"),
            UserProfileStat(title: "角色", value: operatorCount, systemImage: "person.2"),
            UserProfileStat(title: "关系", value: relationCount, systemImage: "person.3.sequence"),
        ]
    }
}

/// 身份统计格（编辑页统计卡一行）。
struct UserProfileStat: Identifiable, Hashable {
    let title: String
    let value: Int
    let systemImage: String
    var id: String { title }
}

// MARK: - AppGraphClient 用户身份扩展（Kotlin 回调 → async/await）

extension AppGraphClient {
    /// 读取用户身份 + 统计。
    func loadUserProfile() async -> Result<UserProfileItem, BridgeError> {
        await withCheckedContinuation { continuation in
            identityBridge.getProfile { dto, error in
                assertMainQueue("identity.getProfile")
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else if let dto {
                    continuation.resume(returning: .success(UserProfileItem(dto: dto)))
                } else {
                    continuation.resume(returning: .failure(BridgeError(message: "读取用户身份失败")))
                }
            }
        }
    }

    /// 保存昵称 / 性别 / 签名（昵称 trim 后必填；写 settings 并同步落盘）。
    func saveUserProfile(nickname: String, gender: String, signature: String) async -> Result<UserProfileItem, BridgeError> {
        await withCheckedContinuation { continuation in
            identityBridge.save(nickname: nickname, gender: gender, signature: signature) { dto, error in
                assertMainQueue("identity.save")
                if let error {
                    continuation.resume(returning: .failure(BridgeError(message: error)))
                } else if let dto {
                    continuation.resume(returning: .success(UserProfileItem(dto: dto)))
                } else {
                    continuation.resume(returning: .failure(BridgeError(message: "保存用户身份失败")))
                }
            }
        }
    }
}
