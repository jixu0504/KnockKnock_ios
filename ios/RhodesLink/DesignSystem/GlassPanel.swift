//
//  GlassPanel.swift
//  「敲敲」面板兼容组件：原 iOS 26 `.glassEffect` 玻璃分支已删除，退化为 `KnockPanel` 实色面板。
//
//  背景：T33 时期本组件是统一玻璃表面；敲敲改版（计划书 1.2 / 2.2）要求
//  **禁止毛玻璃 / 模糊 / 半透明白**，所有面板 = 黑（浅色为白）实色底 + 1.5pt 描边 + 圆角 20pt
//  + 极淡棋盘格。因此本文件内部改为 `KnockTokens` 实色渲染，但**保留对外的
//  `GlassPanel(style:cornerRadius:tint:interactive:)` 初始化签名**，30 个存量页面无需改动。
//
//  - `style`：旧 `.regular` / `.clear` 两档语义已合并（同一种实色面板），仅为调用面兼容；
//  - `tint` / `interactive`：旧玻璃着色与交互高光，实色面板下不再生效（参数保留，不 crash）；
//  - 可访问性：增强对比度 → 描边加深；降低透明度 → 本来就全是实色，无需降级；
//    粗体文字（legibilityWeight）→ 描边加粗到 2pt。
//
//  新代码请直接使用 `KnockPanel { ... }`（KnockSurfaces.swift）。
//

import SwiftUI

/// 面板样式（旧玻璃两档，语义已合并为同一种实色面板）。
enum GlassStyle {
    case regular
    case clear
}

struct GlassPanel<Content: View>: View {
    var style: GlassStyle
    var cornerRadius: CGFloat
    var tint: Color?
    var interactive: Bool

    @Environment(\.colorSchemeContrast) private var contrast
    @Environment(\.legibilityWeight) private var legibilityWeight

    private let content: () -> Content

    init(
        style: GlassStyle = .regular,
        cornerRadius: CGFloat = GlassTokens.glassRadius,
        tint: Color? = nil,
        interactive: Bool = false,
        @ViewBuilder content: @escaping () -> Content
    ) {
        self.style = style
        self.cornerRadius = cornerRadius
        self.tint = tint
        self.interactive = interactive
        self.content = content
    }

    private var roundedRect: RoundedRectangle {
        RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
    }

    private var strokeColor: Color {
        contrast == .increased ? KnockTokens.strokeContrast : KnockTokens.stroke
    }

    /// 粗体文字模式 → 描边加粗（保留 T33 的可访问性约定）。
    private var strokeWidth: CGFloat {
        legibilityWeight == .bold ? KnockTokens.strokeWidthStrong : KnockTokens.strokeWidth
    }

    var body: some View {
        content()
            .background {
                roundedRect
                    .fill(KnockTokens.panel)
                    .overlay {
                        // 面板内极淡 2pt 棋盘格（降低透明度时内部自动关闭）
                        KnockCheckerOverlay(cornerRadius: cornerRadius)
                    }
            }
            .overlay {
                roundedRect.strokeBorder(strokeColor, lineWidth: strokeWidth)
            }
    }
}