//
//  KnockSurfaces.swift
//  「敲敲」容器与行组件：面板 / 胶囊行 / 虚线按钮 / 分组标题。
//
//  设计依据：docs/敲敲UI改版计划书-v1.1.md 第 1.2 节：
//  - 面板：圆角 20pt，底 `#0A0A0A`，描边 1.5pt `#2A2A2E`，内边距 16pt，内含极淡棋盘格；
//  - 列表行：**胶囊行**（radius=半高，高 64–72pt），底 `#171717`，描边 1.5pt `#3A3A3E`；
//    左侧 44pt 圆形图标/头像，主标题 15pt heavy 白、副标题 13pt `#9A9A9A`；
//  - 分组标题：14pt heavy 白，右侧操作链接「+ 新建」14pt heavy 白/黄；
//  - 虚线按钮：胶囊，虚线 1.5pt `#3A3A3E`，居中 14pt heavy 文字 + 图标；
//    禁止毛玻璃 / 半透明白 —— 面板与行一律实色底。
//
//  可访问性：
//  - 行高用 `minHeight`，Dynamic Type 大字号自动增高；
//  - 可点击行是真实 Button，VoiceOver 可聚焦；不可点击行不加 Button 包装；
//  - 增强对比度时描边加深；「降低透明度」由 KnockCheckerOverlay 内部关闭纹理。
//

import SwiftUI

// MARK: - 面板

/// 敲敲内容面板：圆角 20pt + 实色底 + 1.5pt 描边 + 极淡棋盘格 + 16pt 内边距。
struct KnockPanel<Content: View>: View {
    var cornerRadius: CGFloat
    var padding: CGFloat
    var showsChecker: Bool
    var showsStroke: Bool

    @Environment(\.colorSchemeContrast) private var colorSchemeContrast
    private let content: () -> Content

    init(
        cornerRadius: CGFloat = KnockTokens.panelRadius,
        padding: CGFloat = KnockTokens.panelPadding,
        showsChecker: Bool = true,
        showsStroke: Bool = true,
        @ViewBuilder content: @escaping () -> Content
    ) {
        self.cornerRadius = cornerRadius
        self.padding = padding
        self.showsChecker = showsChecker
        self.showsStroke = showsStroke
        self.content = content
    }

    private var strokeColor: Color {
        colorSchemeContrast == .increased ? KnockTokens.strokeContrast : KnockTokens.stroke
    }

    var body: some View {
        content()
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background {
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .fill(KnockTokens.panel)
                    .overlay {
                        if showsChecker {
                            KnockCheckerOverlay(cornerRadius: cornerRadius)
                        }
                    }
            }
            .overlay {
                if showsStroke {
                    RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                        .strokeBorder(strokeColor, lineWidth: KnockTokens.strokeWidth)
                }
            }
    }
}

// MARK: - 行内前置元素

/// 行内 44pt 圆形图标（底比行亮一档，图标单色 heavy）。
struct KnockRowGlyph: View {
    var systemImage: String
    var tint: Color = KnockTokens.textPrimary
    var size: CGFloat = KnockTokens.rowIconSize

    var body: some View {
        ZStack {
            Circle().fill(KnockTokens.rowIconWell)
            Image(systemName: systemImage)
                .font(.system(size: size * 0.38, weight: .heavy))
                .foregroundStyle(tint)
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

/// 行内头像位（走 `KnockAvatar`：黑底 + 1.5pt 蓝圈 + 白色 heavy 首字）。
struct KnockRowAvatar: View {
    var avatarId: String
    var name: String
    var size: CGFloat = KnockTokens.rowIconSize

    var body: some View {
        KnockAvatar(avatarId: avatarId, name: name, size: size)
            .accessibilityHidden(true)
    }
}

/// 行尾 chevron（单色 heavy，不可朗读）。
struct KnockChevron: View {
    var body: some View {
        Image(systemName: "chevron.right")
            .font(.system(size: 13, weight: .heavy))
            .foregroundStyle(KnockTokens.textTertiary)
            .accessibilityHidden(true)
    }
}

/// 行尾辅助文字（13pt 次文字）。
struct KnockRowTrailingText: View {
    var text: String

    var body: some View {
        KnockText(text, role: .caption, color: KnockTokens.textSecondary, lineLimit: 1)
    }
}

// MARK: - 胶囊列表行

/// 敲敲胶囊列表行：底 `#171717` + 1.5pt 次描边 + 极淡棋盘格 + 左 44pt 图标/头像。
/// 高度 64–72pt（默认 68）；有 `action` 时渲染为 Button。
struct KnockListRow<Leading: View, Trailing: View>: View {
    private let title: String
    private let subtitle: String?
    private let height: CGFloat
    private let action: (() -> Void)?
    private let leading: () -> Leading
    private let trailing: () -> Trailing

    @Environment(\.colorSchemeContrast) private var colorSchemeContrast

    init(
        title: String,
        subtitle: String? = nil,
        height: CGFloat = KnockTokens.rowHeight,
        action: (() -> Void)? = nil,
        @ViewBuilder leading: @escaping () -> Leading,
        @ViewBuilder trailing: @escaping () -> Trailing
    ) {
        self.title = title
        self.subtitle = subtitle
        self.height = height
        self.action = action
        self.leading = leading
        self.trailing = trailing
    }

    private var strokeColor: Color {
        colorSchemeContrast == .increased ? KnockTokens.strokeContrast : KnockTokens.strokeSecondary
    }

    var body: some View {
        Group {
            if let action {
                Button(action: action) { rowContent }
                    .buttonStyle(.plain)
            } else {
                rowContent
            }
        }
    }

    private var rowContent: some View {
        HStack(spacing: KnockTokens.spacingM) {
            leading()
            VStack(alignment: .leading, spacing: KnockTokens.spacingXXS) {
                KnockText(title, role: .rowTitle, lineLimit: 2)
                if let subtitle, !subtitle.isEmpty {
                    KnockText(subtitle, role: .caption, color: KnockTokens.textSecondary, lineLimit: 2)
                }
            }
            Spacer(minLength: KnockTokens.spacingS)
            trailing()
        }
        .padding(.leading, KnockTokens.rowPaddingLeading)
        .padding(.trailing, KnockTokens.rowPaddingTrailing)
        .padding(.vertical, KnockTokens.spacingS)
        .frame(minHeight: height)
        .background {
            Capsule(style: .continuous)
                .fill(KnockTokens.row)
                .overlay(KnockCheckerOverlay())
                .clipShape(Capsule(style: .continuous))
        }
        .overlay {
            Capsule(style: .continuous)
                .strokeBorder(strokeColor, lineWidth: KnockTokens.strokeWidth)
        }
        .contentShape(Capsule(style: .continuous))
    }
}

// 便捷构造：左侧圆形图标 + 右侧 chevron（最常见形态）
extension KnockListRow where Leading == KnockRowGlyph, Trailing == KnockChevron {
    init(
        title: String,
        subtitle: String? = nil,
        systemImage: String,
        tint: Color = KnockTokens.textPrimary,
        height: CGFloat = KnockTokens.rowHeight,
        action: (() -> Void)? = nil
    ) {
        self.init(title: title, subtitle: subtitle, height: height, action: action) {
            KnockRowGlyph(systemImage: systemImage, tint: tint)
        } trailing: {
            KnockChevron()
        }
    }
}

// 便捷构造：左侧头像 + 右侧 chevron（角色 / 会话 / 群成员）
extension KnockListRow where Leading == KnockRowAvatar, Trailing == KnockChevron {
    init(
        title: String,
        subtitle: String? = nil,
        avatarId: String,
        avatarName: String,
        height: CGFloat = KnockTokens.rowHeight,
        action: (() -> Void)? = nil
    ) {
        self.init(title: title, subtitle: subtitle, height: height, action: action) {
            KnockRowAvatar(avatarId: avatarId, name: avatarName)
        } trailing: {
            KnockChevron()
        }
    }
}

// 便捷构造：左侧圆形图标 + 右侧辅助文字（设置项「值 + chevron」）
extension KnockListRow where Leading == KnockRowGlyph, Trailing == KnockRowTrailingText {
    init(
        title: String,
        subtitle: String? = nil,
        systemImage: String,
        trailingText: String,
        height: CGFloat = KnockTokens.rowHeight,
        action: (() -> Void)? = nil
    ) {
        self.init(title: title, subtitle: subtitle, height: height, action: action) {
            KnockRowGlyph(systemImage: systemImage)
        } trailing: {
            KnockRowTrailingText(text: trailingText)
        }
    }
}

// MARK: - 虚线按钮

/// 虚线胶囊按钮：「+ 新建角色」这类弱操作；虚线 1.5pt，文字 14pt heavy，可黄可白。
struct KnockDashedButton: View {
    var title: String
    var systemImage: String?
    var emphasized: Bool
    var height: CGFloat
    var action: () -> Void

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    init(
        title: String,
        systemImage: String? = nil,
        emphasized: Bool = false,
        height: CGFloat = KnockTokens.dashedButtonHeight,
        action: @escaping () -> Void
    ) {
        self.title = title
        self.systemImage = systemImage
        self.emphasized = emphasized
        self.height = height
        self.action = action
    }

    private var foreground: Color {
        emphasized ? KnockTokens.electric : KnockTokens.textPrimary
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: KnockTokens.spacingS) {
                if let systemImage {
                    Image(systemName: systemImage)
                        .font(.system(size: 14, weight: .heavy))
                        .foregroundStyle(foreground)
                }
                KnockText(title, role: .sectionTitle, color: foreground, lineLimit: 1)
            }
            .frame(maxWidth: .infinity, minHeight: height)
            .background {
                Capsule(style: .continuous)
                    .strokeBorder(
                        KnockTokens.strokeDashed,
                        style: StrokeStyle(lineWidth: KnockTokens.strokeWidth, dash: [5, 4])
                    )
            }
            .contentShape(Capsule(style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title)
    }
}

// MARK: - 分组标题

/// 分组标题：14pt heavy + 可选 SF Symbol + 右侧操作链接（白/黄）。
struct KnockGroupTitle: View {
    var title: String
    var systemImage: String?
    var actionTitle: String?
    var action: (() -> Void)?

    init(
        title: String,
        systemImage: String? = nil,
        actionTitle: String? = nil,
        action: (() -> Void)? = nil
    ) {
        self.title = title
        self.systemImage = systemImage
        self.actionTitle = actionTitle
        self.action = action
    }

    var body: some View {
        HStack(spacing: KnockTokens.spacingS) {
            if let systemImage {
                Image(systemName: systemImage)
                    .font(.system(size: 13, weight: .heavy))
                    .foregroundStyle(KnockTokens.textSecondary)
                    .accessibilityHidden(true)
            }
            KnockText(title, role: .sectionTitle, lineLimit: 1)
            Spacer(minLength: KnockTokens.spacingS)
            if let actionTitle, let action {
                Button(action: action) {
                    KnockText(actionTitle, role: .sectionTitle, color: KnockTokens.electric, lineLimit: 1)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(actionTitle)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityAddTraits(.isHeader)
    }
}