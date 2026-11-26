//
//  GlassTokens.swift
//  「敲敲」旧 token 兼容层（文件名保留，内部全部映射到 `KnockTokens`）。
//
//  背景：本文件原为 T33「玻璃壳」设计 token；敲敲改版（计划书 2.2 节）要求
//  「保留旧名做映射」以降低 30 个页面的调用面改动，同时**彻底移除玻璃材质语义**：
//  - `brandOrange` / `accent` → 电光黄 `#F2FF00`（品牌 / 选中 / 主 CTA）；
//  - `bubbleIncoming` → 白 `#FFFFFF`、`bubbleOutgoing` → 聊天蓝 `#3364E7`；
//  - `ambient*` / `vignette` → 黑 / 暖白纯色底（渐变、光斑、暗角全部退化为透明，不再绘制）；
//  - `surface*` / `glassStroke*` → 实色面板 / 实色描边；
//  - `glassRegular` / `glassClear` / `glassTint`（Material、玻璃着色）**已删除** ——
//    代码内不再有任何毛玻璃引用，新代码请直接使用 `KnockTokens`。
//
//  新代码纪律：只引用 `KnockTokens` / `KnockTypography`；本文件的旧名只为兼容存量页面，
//  随各 Feature 批次迁移完成后逐步下线（见计划书 P1–P5）。
//

import SwiftUI
import UIKit

enum GlassTokens {
    // MARK: - 品牌强调（旧名 → 电光黄；黄 = App 交互语言）

    /// 旧「品牌橙」→ 电光黄 `#F2FF00`（仅选中 / 主 CTA / 品牌 / Tab 选中，面积 ≤5%）。
    static let brandOrange = KnockTokens.electric
    /// 旧「品牌橙深色」→ 黄按压/暗部 `#D8E600`。
    static let brandOrangeDeep = KnockTokens.electricPressed
    /// 语义别名（旧代码常用）：同 `brandOrange`。
    static let accent = brandOrange
    static let accentDeep = brandOrangeDeep

    // MARK: - 环境背景（旧名 → 纯色底；渐变/光斑/暗角已删除）

    /// 页面底（浅 `#F4F4F2` / 深 `#000000`）。旧渐变顶色 → 单色底。
    static let ambientTop = KnockTokens.canvas
    /// 页面底（同 `ambientTop`；保留旧名只为兼容）。
    static let ambientBottom = KnockTokens.canvas
    /// 旧光斑 —— 计划书禁止渐变光斑，恒为透明。
    static let ambientGlowOrange = Color.clear
    /// 旧光斑 —— 恒为透明。
    static let ambientGlowBlue = Color.clear
    /// 旧暗角 —— 恒为透明（黑白层次交给纹理与描边）。
    static let vignette = Color.clear

    // MARK: - 内容表面（旧半透明 → 实色）

    /// 内容卡片底 → 面板实色（浅 `#FFFFFF` / 深 `#0A0A0A`）。
    static let surface = KnockTokens.panel
    /// 强调卡片底 → 面板实色（不再有半透明白）。
    static let surfaceStrong = KnockTokens.panel
    /// 细描边 → 主描边 1.5pt（浅 `#E2E2DD` / 深 `#2A2A2E`）。
    static let strokeSubtle = KnockTokens.stroke

    // MARK: - 消息气泡（黄蓝分工：接收白 / 发送聊天蓝）

    /// 接收气泡：白 `#FFFFFF`（深浅一致）。
    static let bubbleIncoming = KnockTokens.bubbleIncoming
    /// 接收气泡文字：`#0B0B0C`。
    static let bubbleIncomingText = KnockTokens.bubbleIncomingText
    /// 发送气泡：聊天蓝 `#3364E7`（仅聊天语义）。
    static let bubbleOutgoing = KnockTokens.chatBlue
    /// 发送气泡文字：白。
    static let bubbleOutgoingText = KnockTokens.bubbleOutgoingText
    /// 系统/时间胶囊底：实色灰阶（浅 `#ECECE9` / 深 `#1A1A1A`）。
    static let bubbleSystem = KnockTokens.bubbleSystem

    // MARK: - 聊天页 / 列表页常用语义色

    /// 聊天页背景：固定敲敲底（纯色 + 斜切迷彩）。
    /// 计划书决策 6：删除 6 档聊天背景，改为固定风格；`ChatAppearance.swift` 暂留供设置页回滚。
    static var chatBackground: some View {
        KnockCamoBackground()
    }
    /// 未读角标：危险红 `#BF2207`（红 <1% 面积）。
    static let unreadBadge = KnockTokens.danger
    /// 在线状态点：品牌黄（不再用微信绿）。
    static let onlineDot = KnockTokens.electric

    // MARK: - 旧玻璃参数（语义移除，保留常量名兼容）

    /// 面板描边（旧玻璃边缘高光）→ 主描边实色。
    static let glassStroke = KnockTokens.stroke
    /// 增强对比度描边（旧）→ 对比度描边实色。
    static let glassStrokeStrong = KnockTokens.strokeContrast
    /// 面板圆角 20pt（计划书 1.2）。
    static let glassRadius: CGFloat = KnockTokens.panelRadius
    /// 小圆角 16pt（行内块）。
    static let glassRadiusSmall: CGFloat = 16
    /// 大圆角 24pt（弹窗 / 大容器）。
    static let glassRadiusLarge: CGFloat = 24
    /// 旧玻璃投影 → 透明（实色面板不投影）。
    static let glassShadow = Color.clear
    /// 卡片间距 12pt（沿用）。
    static let glassCardSpacing: CGFloat = 12
    /// 旧「一屏玻璃元素上限」——玻璃已移除，保留常量仅为兼容旧自检引用。
    static let maxGlassPerScreen = 5
    /// 旧「玻璃上文字最小字号」——面板已实色，保留常量仅为兼容旧自检引用。
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