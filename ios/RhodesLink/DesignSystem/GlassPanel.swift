//
//  GlassPanel.swift
//  统一玻璃表面组件（T33 正式壳）：iOS 26 用 .glassEffect，iOS 18 用 Material 兜底。
//  所有玻璃 UI 一律经由此组件，保证 token 一致 + 可访问性响应一致：
//  - 降低透明度：退为近实色材质/底色；
//  - 增强对比度：描边加强；
//  - 粗体文字：描边加粗（1 → 1.5pt）；
//  - 玻璃只做背景层：内容（文字等）由调用方负责，不参与玻璃采样。
//
//  与 Demo 差异：tint 变为可选（默认 nil = 系统玻璃原色），避免全屏橙色玻璃。
//

import SwiftUI

/// 玻璃面板样式
enum GlassStyle {
    case regular
    case clear
}

struct GlassPanel<Content: View>: View {
    var style: GlassStyle
    var cornerRadius: CGFloat
    var tint: Color?
    var interactive: Bool

    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
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

    var body: some View {
        if #available(iOS 26.0, *) {
            panel26
        } else {
            panel18
        }
    }

    // MARK: iOS 26：真玻璃
    @available(iOS 26.0, *)
    private var panel26: some View {
        Group {
            if reduceTransparency {
                // 降低透明度：退为系统实色材质（系统也会自动处理，这里显式兜底）
                content()
                    .background(.regularMaterial, in: roundedRect)
            } else {
                content()
                    .glassEffect(effectiveGlass, in: .rect(cornerRadius: cornerRadius))
            }
        }
        .overlay(strokeOverlay)
        .shadow(color: shadowColor, radius: 12, y: 6)
    }

    // MARK: iOS 18：Material 兜底
    private var panel18: some View {
        Group {
            if reduceTransparency {
                content()
                    .background(GlassTokens.surfaceStrong, in: roundedRect)
            } else {
                content()
                    .background(material18, in: roundedRect)
            }
        }
        .overlay(strokeOverlay)
        .shadow(color: shadowColor, radius: 12, y: 6)
    }

    // MARK: 派生值
    private var roundedRect: RoundedRectangle {
        RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
    }

    private var material18: Material {
        style == .regular ? GlassTokens.glassRegular : GlassTokens.glassClear
    }

    @available(iOS 26.0, *)
    private var effectiveGlass: Glass {
        let base: Glass = (style == .regular) ? .regular : .clear
        var glass = base
        if let tint {
            glass = glass.tint(tint)
        }
        if interactive {
            glass = glass.interactive()
        }
        return glass
    }

    private var shadowColor: Color {
        reduceTransparency ? .clear : GlassTokens.glassShadow
    }

    private var strokeOverlay: some View {
        roundedRect
            .strokeBorder(strokeColor, lineWidth: legibilityWeight == .bold ? 1.5 : 1)
    }

    private var strokeColor: Color {
        contrast == .increased ? GlassTokens.glassStrokeStrong : GlassTokens.glassStroke
    }
}
