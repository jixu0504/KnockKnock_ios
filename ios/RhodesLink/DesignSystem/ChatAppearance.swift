//
//  ChatAppearance.swift
//  T53 外观设置 — 聊天背景枚举与渲染（纯色 / 内置渐变，参考微信/Telegram 低对比）。
//
//  - 存储键：`ios_chat_background`（SettingsRepository / NSUserDefaults，默认 classic）；
//  - 读取：聊天页背景 token `GlassTokens.chatBackground` 在渲染时调用
//    `ChatBackgroundStyle.stored()`；因此设置页改完背景后进入聊天页即生效（无需重启）；
//  - 本文件只提供枚举 + 视图，不修改 Chat/GroupChat 功能页（T53 纪律）。
//

import SwiftUI

/// 聊天背景样式（纯色 4 档 + 内置渐变 2 档，全部深浅色自适应）。
enum ChatBackgroundStyle: String, CaseIterable, Identifiable {
    case classic
    case paper
    case mint
    case sky
    case dawn
    case dusk

    var id: String { rawValue }

    /// 设置键（与 SettingsStore.chatBackground / 自检共用）。
    static let settingsKey = "ios_chat_background"

    var label: String {
        switch self {
        case .classic: return "经典灰"
        case .paper: return "纯色 · 米白"
        case .mint: return "纯色 · 薄荷"
        case .sky: return "纯色 · 天青"
        case .dawn: return "渐变 · 晨曦"
        case .dusk: return "渐变 · 暮色"
        }
    }

    var shortLabel: String {
        switch self {
        case .classic: return "经典"
        case .paper: return "米白"
        case .mint: return "薄荷"
        case .sky: return "天青"
        case .dawn: return "晨曦"
        case .dusk: return "暮色"
        }
    }

    var isGradient: Bool { self == .dawn || self == .dusk }

    var kindLabel: String { isGradient ? "渐变" : "纯色" }

    /// 当前落盘值（聊天页渲染时调用；未知/未设置回退 classic）。
    static func stored() -> ChatBackgroundStyle {
        guard let raw = UserDefaults.standard.string(forKey: settingsKey) else { return .classic }
        return ChatBackgroundStyle(rawValue: raw) ?? .classic
    }
}

/// 聊天背景渲染视图（聊天页与设置页预览共用）。
struct ChatBackgroundView: View {
    let style: ChatBackgroundStyle

    var body: some View {
        switch style {
        case .classic:
            Color.rhodesHex(0xEDEDED, 0x111111)
        case .paper:
            Color.rhodesHex(0xF7F1E6, 0x1B1A18)
        case .mint:
            Color.rhodesHex(0xE8F3EC, 0x12201A)
        case .sky:
            Color.rhodesHex(0xE9F0F7, 0x101823)
        case .dawn:
            LinearGradient(
                colors: [Color.rhodesHex(0xFFE8D2, 0x2A1B14), Color.rhodesHex(0xF3DDE9, 0x1E1520)],
                startPoint: .top,
                endPoint: .bottom
            )
        case .dusk:
            LinearGradient(
                colors: [Color.rhodesHex(0xE6E9F7, 0x141A2E), Color.rhodesHex(0xE9F1F4, 0x101E24)],
                startPoint: .top,
                endPoint: .bottom
            )
        }
    }
}
