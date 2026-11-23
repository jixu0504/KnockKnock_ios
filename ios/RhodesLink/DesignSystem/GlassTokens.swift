//
//  GlassTokens.swift
//  RhodesLink 正式壳设计 token（T33，自 T8/T12 Demo 迁移）。
//
//  与 Demo 的差异（Phase 3）：
//  - 颜色全部改为 **深浅色自适应**（`Color.rhodesHex(light:dark:)`），App 跟随系统外观；
//  - `glassTint` 不再默认套在所有面板上（Demo 全橙 tint 在真实页面上过重）；
//  - 新增内容层 surface / stroke / 气泡 system 色，供「非玻璃」内容层使用。
//
//  纪律：页面层只引用 token，iOS 26 玻璃 API 只在 DesignSystem 内部出现。
//

import SwiftUI
import UIKit

enum GlassTokens {
    // MARK: - 玻璃材质（iOS 18 兜底值；iOS 26 真玻璃分支见 GlassPanel）
    /// glassRegular —— 标准玻璃：iOS 26 `Glass.regular`，iOS 18 `.ultraThinMaterial`
    static let glassRegular: Material = .ultraThinMaterial
    /// glassClear —— 轻玻璃：iOS 26 `Glass.clear`，iOS 18 `.thinMaterial`
    static let glassClear: Material = .thinMaterial

    // MARK: - 玻璃着色 / 描边 / 圆角 / 阴影
    /// 品牌橙，玻璃折射色调（仅用于主操作玻璃，不默认套全局面板）
    static let glassTint = Color(hex: 0xFF9420)
    /// 玻璃边缘 1px 高光描边（浅色加深、深色提亮，保证两种外观都可辨识）
    static let glassStroke = Color.rhodesHex(0x1D2A44, 0xFFFFFF, lightAlpha: 0.20, darkAlpha: 0.18)
    /// 增强对比度模式下的描边（更实）
    static let glassStrokeStrong = Color.rhodesHex(0x1D2A44, 0xFFFFFF, lightAlpha: 0.40, darkAlpha: 0.34)
    /// 圆角：默认 24；允许 20 / 24 / 28 三档
    static let glassRadius: CGFloat = 24
    static let glassRadiusSmall: CGFloat = 20
    static let glassRadiusLarge: CGFloat = 28
    /// 玻璃投影（「降低透明度」模式下禁用）
    static let glassShadow = Color.rhodesHex(0x0B1020, 0x000000, lightAlpha: 0.10, darkAlpha: 0.30)

    // MARK: - 主强调色（Telegram 风格蓝；旧命名保留以兼容现有引用）
    static let brandOrange = Color.rhodesHex(0x007AFF, 0x0A84FF)
    static let brandOrangeDeep = Color.rhodesHex(0x0063CC, 0x0B6FD6)
    /// 语义别名（新代码建议使用）
    static let accent = brandOrange
    static let accentDeep = brandOrangeDeep

    // MARK: - 环境背景（参考 Telegram/微信：浅色中性灰、深色近黑，不再是橙黑/白橙）
    static let ambientTop = Color.rhodesHex(0xF7F7F7, 0x0E1621)
    static let ambientBottom = Color.rhodesHex(0xEDEDED, 0x17212B)
    static let ambientGlowOrange = Color.rhodesHex(0x007AFF, 0x0A84FF, lightAlpha: 0.05, darkAlpha: 0.08)
    static let ambientGlowBlue = Color.rhodesHex(0x007AFF, 0x0A84FF, lightAlpha: 0.03, darkAlpha: 0.06)
    /// 暗角（浅色很轻，深色加深）
    static let vignette = Color.rhodesHex(0x000000, 0x000000, lightAlpha: 0.02, darkAlpha: 0.16)

    // MARK: - 非玻璃内容层表面（卡片 / 行）
    static let surface = Color.rhodesHex(0xFFFFFF, 0xFFFFFF, lightAlpha: 0.88, darkAlpha: 0.08)
    static let surfaceStrong = Color.rhodesHex(0xFFFFFF, 0xFFFFFF, lightAlpha: 0.97, darkAlpha: 0.14)
    static let strokeSubtle = Color.rhodesHex(0x000000, 0xFFFFFF, lightAlpha: 0.08, darkAlpha: 0.10)

    // MARK: - 消息气泡（微信配色逻辑：接收白/深灰，发送微信绿；浅色黑字、深色白字）
    static let bubbleIncoming = Color.rhodesHex(0xFFFFFF, 0x2C2C2E)
    static let bubbleIncomingText = Color.rhodesHex(0x111111, 0xF2F2F7)
    static let bubbleOutgoing = Color.rhodesHex(0x95EC69, 0x3EB575)
    static let bubbleOutgoingText = Color.rhodesHex(0x111111, 0xFFFFFF)
    static let bubbleSystem = Color.rhodesHex(0x000000, 0xFFFFFF, lightAlpha: 0.05, darkAlpha: 0.08)

    // MARK: - 聊天页/列表页常用语义色
    /// 聊天页背景（T53：由外观设置「聊天背景」选择决定；默认经典灰 #EDEDED / 深色 #111）。
    /// ChatView 渲染 body 时读取 `ChatBackgroundStyle.stored()`，改设置后重新进入聊天页即生效。
    static var chatBackground: some View {
        ChatBackgroundView(style: ChatBackgroundStyle.stored())
    }
    /// 未读角标（微信红）
    static let unreadBadge = Color(hex: 0xFA5151)
    /// 在线状态点（微信绿）
    static let onlineDot = Color(hex: 0x07C160)

    // MARK: - 版式 / 布局 / 设计原则常量
    /// 玻璃卡片间距：12pt（避免玻璃叠玻璃）
    static let glassCardSpacing: CGFloat = 12
    /// 一屏玻璃元素上限（设计原则 2）
    static let maxGlassPerScreen = 5
    /// 玻璃上文字最小字号（设计原则 3，.body = 17pt）
    static let textOnGlassMinPointSize: CGFloat = 17
}

// MARK: - 颜色工具

extension Color {
    /// 0xRRGGBB → Color（示意代码用，避免资产依赖）
    init(hex: UInt32, alpha: Double = 1.0) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255.0,
            green: Double((hex >> 8) & 0xFF) / 255.0,
            blue: Double(hex & 0xFF) / 255.0,
            opacity: alpha
        )
    }

    /// 深浅色自适应颜色：同一 token 在 light/dark 下取不同值（随系统外观实时切换）。
    static func rhodesHex(
        _ light: UInt32,
        _ dark: UInt32,
        lightAlpha: Double = 1.0,
        darkAlpha: Double = 1.0
    ) -> Color {
        Color(uiColor: UIColor { trait in
            let isDark = trait.userInterfaceStyle == .dark
            return UIColor(hexValue: isDark ? dark : light, alpha: isDark ? darkAlpha : lightAlpha)
        })
    }
}

extension UIColor {
    convenience init(hexValue: UInt32, alpha: Double = 1.0) {
        self.init(
            red: CGFloat((hexValue >> 16) & 0xFF) / 255.0,
            green: CGFloat((hexValue >> 8) & 0xFF) / 255.0,
            blue: CGFloat(hexValue & 0xFF) / 255.0,
            alpha: CGFloat(alpha)
        )
    }
}
