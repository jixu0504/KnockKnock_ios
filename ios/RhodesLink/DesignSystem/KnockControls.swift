//
//  KnockControls.swift
//  「敲敲」控件层：主/次/危险按钮、输入框、角标、Chip、分隔线、胶囊图标按钮。
//
//  设计依据：docs/敲敲UI改版计划书-v1.1.md 第 2.1 节：
//  - `KnockPrimaryButtonStyle`（黄胶囊黑字，主 CTA）/ `KnockSecondaryButtonStyle`（描边）/
//    `KnockDangerButtonStyle`（危险底 #BF2207）；
//  - `KnockTextFieldStyle`（行底 `#171717` + 1.5pt 描边胶囊，输入区域）；
//  - `KnockBadge` / `KnockChip` / `KnockDivider`；
//  - 黄色只做「主 CTA」，一屏最多一个黄色主按钮；蓝色不用于按钮（聊天语义除外）。
//
//  可访问性：
//  - 按钮按下态有实色变化 + 轻微缩放（减弱动态效果时取消缩放）；
//  - 最小点击高度 46pt，Dynamic Type 大字号下 `minHeight` 自动增高；
//  - 增强对比度时描边加深 / 危险色保持；
//  - 所有可交互控件都有 `accessibilityLabel`。
//

import SwiftUI

// MARK: - 主按钮（黄胶囊黑字 · 主 CTA）

struct KnockPrimaryButtonStyle: ButtonStyle {
    var fullWidth: Bool
    var height: CGFloat

    init(fullWidth: Bool = true, height: CGFloat = KnockTokens.buttonHeight) {
        self.fullWidth = fullWidth
        self.height = height
    }

    func makeBody(configuration: Configuration) -> some View {
        KnockPrimaryButtonBody(configuration: configuration, fullWidth: fullWidth, height: height)
    }
}

private struct KnockPrimaryButtonBody: View {
    let configuration: ButtonStyleConfiguration
    let fullWidth: Bool
    let height: CGFloat

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        configuration.label
            .font(KnockTypography.font(.rowTitle, dynamicTypeSize: dynamicTypeSize))
            .foregroundStyle(KnockTokens.onElectric)
            .padding(.horizontal, KnockTokens.spacingXL)
            .frame(maxWidth: fullWidth ? CGFloat.infinity : nil, minHeight: height)
            .background {
                Capsule(style: .continuous)
                    .fill(configuration.isPressed ? KnockTokens.electricPressed : KnockTokens.electric)
            }
            .scaleEffect(reduceMotion ? 1 : (configuration.isPressed ? 0.985 : 1))
            .animation(reduceMotion ? nil : .easeOut(duration: 0.12), value: configuration.isPressed)
            .contentShape(Capsule(style: .continuous))
    }
}

// MARK: - 次按钮（描边）

struct KnockSecondaryButtonStyle: ButtonStyle {
    var fullWidth: Bool
    var height: CGFloat

    init(fullWidth: Bool = true, height: CGFloat = KnockTokens.buttonHeight) {
        self.fullWidth = fullWidth
        self.height = height
    }

    func makeBody(configuration: Configuration) -> some View {
        KnockSecondaryButtonBody(configuration: configuration, fullWidth: fullWidth, height: height)
    }
}

private struct KnockSecondaryButtonBody: View {
    let configuration: ButtonStyleConfiguration
    let fullWidth: Bool
    let height: CGFloat

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorSchemeContrast) private var colorSchemeContrast

    private var strokeColor: Color {
        colorSchemeContrast == .increased ? KnockTokens.strokeContrast : KnockTokens.strokeSecondary
    }

    var body: some View {
        configuration.label
            .font(KnockTypography.font(.rowTitle, dynamicTypeSize: dynamicTypeSize))
            .foregroundStyle(KnockTokens.textPrimary)
            .padding(.horizontal, KnockTokens.spacingXL)
            .frame(maxWidth: fullWidth ? CGFloat.infinity : nil, minHeight: height)
            .background {
                Capsule(style: .continuous)
                    .fill(configuration.isPressed ? KnockTokens.rowPressed : KnockTokens.panel)
            }
            .overlay {
                Capsule(style: .continuous)
                    .strokeBorder(strokeColor, lineWidth: KnockTokens.strokeWidth)
            }
            .scaleEffect(reduceMotion ? 1 : (configuration.isPressed ? 0.985 : 1))
            .animation(reduceMotion ? nil : .easeOut(duration: 0.12), value: configuration.isPressed)
            .contentShape(Capsule(style: .continuous))
    }
}

// MARK: - 危险按钮（#BF2207 底 · 白字）

struct KnockDangerButtonStyle: ButtonStyle {
    var fullWidth: Bool
    var height: CGFloat

    init(fullWidth: Bool = true, height: CGFloat = KnockTokens.buttonHeight) {
        self.fullWidth = fullWidth
        self.height = height
    }

    func makeBody(configuration: Configuration) -> some View {
        KnockDangerButtonBody(configuration: configuration, fullWidth: fullWidth, height: height)
    }
}

private struct KnockDangerButtonBody: View {
    let configuration: ButtonStyleConfiguration
    let fullWidth: Bool
    let height: CGFloat

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        configuration.label
            .font(KnockTypography.font(.rowTitle, dynamicTypeSize: dynamicTypeSize))
            .foregroundStyle(KnockTokens.onDanger)
            .padding(.horizontal, KnockTokens.spacingXL)
            .frame(maxWidth: fullWidth ? CGFloat.infinity : nil, minHeight: height)
            .background {
                Capsule(style: .continuous)
                    .fill(KnockTokens.danger)
                    .opacity(configuration.isPressed ? 0.86 : 1)
            }
            .scaleEffect(reduceMotion ? 1 : (configuration.isPressed ? 0.985 : 1))
            .animation(reduceMotion ? nil : .easeOut(duration: 0.12), value: configuration.isPressed)
            .contentShape(Capsule(style: .continuous))
    }
}

// MARK: - 输入框样式（胶囊 + 1.5pt 描边 + 行底）

/// 输入框样式：`.textFieldStyle(KnockTextFieldStyle())`。
/// 底 = 行色（深 `#171717` / 浅 `#F7F7F5`），描边 1.5pt 次描边，胶囊；光标用品牌黄。
struct KnockTextFieldStyle: TextFieldStyle {
    var height: CGFloat
    var showsSearchIcon: Bool

    init(height: CGFloat = KnockTokens.searchHeight, showsSearchIcon: Bool = false) {
        self.height = height
        self.showsSearchIcon = showsSearchIcon
    }

    func _body(configuration: TextField<Self._Label>) -> some View {
        KnockTextFieldChrome(height: height, showsSearchIcon: showsSearchIcon) {
            configuration
        }
    }
}

private struct KnockTextFieldChrome<Content: View>: View {
    var height: CGFloat
    var showsSearchIcon: Bool
    @ViewBuilder var content: () -> Content

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.colorSchemeContrast) private var colorSchemeContrast

    private var strokeColor: Color {
        colorSchemeContrast == .increased ? KnockTokens.strokeContrast : KnockTokens.strokeSecondary
    }

    var body: some View {
        HStack(spacing: KnockTokens.spacingM) {
            content()
                .font(KnockTypography.font(.rowTitle, dynamicTypeSize: dynamicTypeSize))
                .foregroundStyle(KnockTokens.textPrimary)
                .tint(KnockTokens.electric)
            if showsSearchIcon {
                Image(systemName: "magnifyingglass")
                    .font(.system(size: 15, weight: .heavy))
                    .foregroundStyle(KnockTokens.textSecondary)
                    .accessibilityHidden(true)
            }
        }
        .padding(.horizontal, KnockTokens.spacingL)
        .frame(minHeight: height)
        .background(Capsule(style: .continuous).fill(KnockTokens.row))
        .overlay {
            Capsule(style: .continuous)
                .strokeBorder(strokeColor, lineWidth: KnockTokens.strokeWidth)
        }
    }
}

/// 便捷输入框（标题 + 输入区 + 可选脚注），表单/设置页用。
struct KnockInputField: View {
    var title: String?
    var placeholder: String
    @Binding var text: String
    var footnote: String?

    init(
        title: String? = nil,
        placeholder: String,
        text: Binding<String>,
        footnote: String? = nil
    ) {
        self.title = title
        self.placeholder = placeholder
        self._text = text
        self.footnote = footnote
    }

    var body: some View {
        VStack(alignment: .leading, spacing: KnockTokens.spacingXS) {
            if let title {
                KnockText(title, role: .captionStrong, color: KnockTokens.textSecondary)
            }
            TextField(placeholder, text: $text)
                .textFieldStyle(KnockTextFieldStyle())
            if let footnote {
                KnockText(footnote, role: .caption, color: KnockTokens.textTertiary)
            }
        }
    }
}

// MARK: - 角标

enum KnockBadgeVariant {
    /// 中性灰阶（默认）
    case neutral
    /// 品牌黄（仅提示「当前/新」等极少量）
    case accent
    /// 聊天蓝（未读等聊天语义）
    case chat
    /// 危险红
    case danger

    var background: Color {
        switch self {
        case .neutral: return KnockTokens.rowIconWell
        case .accent: return KnockTokens.electric
        case .chat: return KnockTokens.chatBlue
        case .danger: return KnockTokens.danger
        }
    }

    var foreground: Color {
        switch self {
        case .neutral: return KnockTokens.textSecondary
        case .accent: return KnockTokens.onElectric
        case .chat, .danger: return KnockTokens.textOnDark
        }
    }
}

/// 角标：胶囊，11pt heavy，最小高 18pt。
struct KnockBadge: View {
    var text: String
    var variant: KnockBadgeVariant

    init(text: String, variant: KnockBadgeVariant = .neutral) {
        self.text = text
        self.variant = variant
    }

    var body: some View {
        KnockText(text, role: .badge, color: variant.foreground, lineLimit: 1)
            .padding(.horizontal, 7)
            .frame(minWidth: KnockTokens.badgeHeight, minHeight: KnockTokens.badgeHeight)
            .background(Capsule(style: .continuous).fill(variant.background))
            .accessibilityLabel(text)
    }
}

// MARK: - Chip（可选中的小胶囊）

/// Chip：未选中 = 行底 + 次描边 + 主文字；选中 = 黄底 + 浅蓝外圈 + 黑字。
struct KnockChip: View {
    var title: String
    var systemImage: String?
    var isSelected: Bool
    var action: (() -> Void)?

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    init(
        title: String,
        systemImage: String? = nil,
        isSelected: Bool = false,
        action: (() -> Void)? = nil
    ) {
        self.title = title
        self.systemImage = systemImage
        self.isSelected = isSelected
        self.action = action
    }

    @ViewBuilder
    var body: some View {
        if let action {
            Button {
                let animation: Animation? = reduceMotion ? nil : .easeOut(duration: 0.14)
                withAnimation(animation) { action() }
            } label: {
                chipLabel
            }
            .buttonStyle(.plain)
            .accessibilityLabel(title)
            .accessibilityAddTraits(isSelected ? [.isSelected] : [])
        } else {
            chipLabel
                .accessibilityLabel(title)
        }
    }

    private var chipLabel: some View {
        HStack(spacing: 6) {
            if let systemImage {
                Image(systemName: systemImage)
                    .font(.system(size: 11, weight: .heavy))
                    .foregroundStyle(isSelected ? KnockTokens.onElectric : KnockTokens.textSecondary)
            }
            KnockText(
                title,
                role: .captionStrong,
                color: isSelected ? KnockTokens.onElectric : KnockTokens.textPrimary,
                lineLimit: 1
            )
        }
        .padding(.horizontal, 12)
        .frame(minHeight: KnockTokens.chipHeight)
        .background {
            Capsule(style: .continuous)
                .fill(isSelected ? KnockTokens.electric : KnockTokens.row)
                .overlay {
                    if isSelected {
                        Capsule(style: .continuous)
                            .strokeBorder(KnockTokens.electricRing, lineWidth: KnockTokens.strokeWidth)
                    }
                }
        }
        .overlay {
            if !isSelected {
                Capsule(style: .continuous)
                    .strokeBorder(KnockTokens.strokeSecondary, lineWidth: KnockTokens.strokeWidth)
            }
        }
        .contentShape(Capsule(style: .continuous))
    }
}

// MARK: - 分隔线

/// 分隔线：1pt 实色；`label` 可选（时间分隔样式 "- 一分钟前 -" 由聊天组实现，此处是通用版）。
struct KnockDivider: View {
    var label: String?

    init(label: String? = nil) {
        self.label = label
    }

    var body: some View {
        if let label {
            HStack(spacing: KnockTokens.spacingS) {
                line
                KnockText(label, role: .caption, color: KnockTokens.textSecondary, lineLimit: 1)
                line
            }
            .accessibilityHidden(true)
        } else {
            line.accessibilityHidden(true)
        }
    }

    private var line: some View {
        Rectangle()
            .fill(KnockTokens.stroke)
            .frame(height: KnockTokens.hairline)
    }
}

// MARK: - 胶囊图标按钮（顶栏 / 行尾用）

enum KnockCapsuleIconVariant {
    /// 中性：面板底 + 描边 + 主文字
    case neutral
    /// 品牌黄：黄底黑图标（主操作）
    case accent
    /// 危险红：`#BF2207` 底 + `#FF3B30` 图标（关闭 / 删除）
    case danger
}

/// 小胶囊图标按钮：22pt 高，图标 12–13pt heavy。
struct KnockCapsuleIconButton: View {
    var systemImage: String
    var variant: KnockCapsuleIconVariant
    var accessibilityLabelText: String
    var action: () -> Void

    init(
        systemImage: String,
        variant: KnockCapsuleIconVariant = .neutral,
        accessibilityLabel: String,
        action: @escaping () -> Void
    ) {
        self.systemImage = systemImage
        self.variant = variant
        self.accessibilityLabelText = accessibilityLabel
        self.action = action
    }

    private var background: Color {
        switch variant {
        case .neutral: return KnockTokens.panel
        case .accent: return KnockTokens.electric
        case .danger: return KnockTokens.danger
        }
    }

    private var iconColor: Color {
        switch variant {
        case .neutral: return KnockTokens.textPrimary
        case .accent: return KnockTokens.onElectric
        case .danger: return KnockTokens.dangerBright
        }
    }

    private var borderColor: Color {
        switch variant {
        case .neutral: return KnockTokens.stroke
        case .accent: return KnockTokens.electricRing
        case .danger: return KnockTokens.danger
        }
    }

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 12, weight: .heavy))
                .foregroundStyle(iconColor)
                .padding(.horizontal, 12)
                .frame(minHeight: 26)
                .background(Capsule(style: .continuous).fill(background))
                .overlay {
                    Capsule(style: .continuous)
                        .strokeBorder(borderColor, lineWidth: KnockTokens.strokeWidth)
                }
                // 视觉 26pt、命中区补齐到 44pt（Apple 最小点击高度）
                .frame(minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(accessibilityLabelText)
    }
}