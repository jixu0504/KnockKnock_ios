//
//  RolesKnockSupport.swift
//  「敲敲」角色域 / 关系域共用视觉组件（P2 · T4）。
//
//  设计依据：docs/敲敲UI改版计划书-v1.1.md
//  - 第 1.2 节条形栏规格：胶囊行（行底 `#171717` + 1.5pt 次描边）、面板（圆角 20pt +
//    `#0A0A0A` + 1.5pt 主描边）、分组标题、E M P T Y 空态；
//  - 第 2.3 节：全部页面隐藏系统导航栏 → 44pt 自绘顶栏；iOS 26+ 关闭滚动边缘玻璃
//    （`.scrollEdgeEffectHidden()`，第五章 Liquid Glass 对策）；
//  - 第 1.2 节配色纪律：黄只做选中 / 主 CTA / 品牌，蓝只做聊天语义（进度），
//    变量一律走 `KnockTokens`，本文件不新增裸色值；
//  - 第 1.4 节：所有文字走 `KnockTypography` / `KnockText`（PingFang 无 Black 的描边补偿）。
//
//  本文件只提供「视觉层」组件，不含任何业务逻辑；角色 / 关系页共用。
//
//  可访问性：
//  - 自绘开关保持 Toggle 语义（Button + 值播报「已开启/已关闭」）；
//  - 顶栏返回 / 操作胶囊均有 accessibilityLabel；
//  - 多行输入框使用 TextEditor + 显式 accessibilityLabel；
//  - 输入控件高度 ≥44pt 命中区，Dynamic Type 大字号用 minHeight 不裁切。
//

import SwiftUI

// MARK: - 隐藏系统导航栏 + 关闭滚动边缘玻璃（iOS 26+）

/// iOS 26+ 的滚动边缘玻璃效果隐藏（低版本直接透传）。
private struct KnockScrollEdgeHiddenModifier: ViewModifier {
    @ViewBuilder
    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content.scrollEdgeEffectHidden()
        } else {
            content
        }
    }
}

extension View {
    /// 敲敲页面统一外壳：隐藏系统导航栏（自绘顶栏替代）+ iOS 26+ 关闭滚动边缘玻璃。
    ///
    /// 用法（放在页面根视图链末尾）：
    /// ```swift
    /// ScrollView { … }
    ///     .knockHideSystemChrome()
    /// ```
    func knockHideSystemChrome() -> some View {
        self
            .toolbar(.hidden, for: .navigationBar)
            .modifier(KnockScrollEdgeHiddenModifier())
    }
}

// MARK: - 角色域顶栏（返回胶囊 + 左对齐 heavy 标题 + 右侧胶囊）

/// 44pt 自绘顶栏：左侧返回胶囊（或留空）+ 左对齐 heavy 标题 + 右侧操作区。
/// 与 `KnockHeader` 同视觉；差异是返回用紧凑图标胶囊（二级页空间留给标题与操作）。
struct KnockRoleHeader<Trailing: View>: View {
    var title: String
    var subtitle: String?
    var showsBack: Bool
    var onBack: (() -> Void)?
    private let trailing: () -> Trailing

    init(
        title: String,
        subtitle: String? = nil,
        showsBack: Bool = false,
        onBack: (() -> Void)? = nil,
        @ViewBuilder trailing: @escaping () -> Trailing
    ) {
        self.title = title
        self.subtitle = subtitle
        self.showsBack = showsBack
        self.onBack = onBack
        self.trailing = trailing
    }

    var body: some View {
        HStack(spacing: KnockTokens.spacingM) {
            if showsBack {
                Button {
                    onBack?()
                } label: {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 13, weight: .heavy))
                        .foregroundStyle(KnockTokens.textPrimary)
                        .frame(width: 34, height: 26)
                        .background(Capsule(style: .continuous).fill(KnockTokens.panel))
                        .overlay {
                            Capsule(style: .continuous)
                                .strokeBorder(KnockTokens.stroke, lineWidth: KnockTokens.strokeWidth)
                        }
                        // 视觉 26pt，命中区补到 44pt（HIG）
                        .frame(minWidth: 44, minHeight: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("返回")
            }

            VStack(alignment: .leading, spacing: 0) {
                KnockText(title, role: .pageTitle, lineLimit: 1)
                if let subtitle, !subtitle.isEmpty {
                    KnockText(subtitle, role: .caption, color: KnockTokens.textSecondary, lineLimit: 1)
                }
            }

            Spacer(minLength: KnockTokens.spacingS)

            trailing()
        }
        .padding(.horizontal, KnockTokens.screenPadding)
        .padding(.vertical, 2)
        .frame(minHeight: KnockTokens.headerHeight)
        .background(KnockTokens.canvas)
    }
}

extension KnockRoleHeader where Trailing == EmptyView {
    init(
        title: String,
        subtitle: String? = nil,
        showsBack: Bool = false,
        onBack: (() -> Void)? = nil
    ) {
        self.init(title: title, subtitle: subtitle, showsBack: showsBack, onBack: onBack) {
            EmptyView()
        }
    }
}

// MARK: - 自绘开关（黄 = 开；行底 + 次描边 = 关）

/// 敲敲开关：`Toggle(...).toggleStyle(KnockSwitchToggleStyle())`。
/// - 开：电光黄胶囊 + 浅蓝外圈 + 黑色圆钮（与选中黄胶囊同一视觉语言）；
/// - 关：行底胶囊 + 次描边 + 灰色圆钮；
/// - 命中区整行可点（≥44pt），VoiceOver 朗读「已开启 / 已关闭」。
struct KnockSwitchToggleStyle: ToggleStyle {
    func makeBody(configuration: Configuration) -> some View {
        KnockSwitchBody(configuration: configuration)
    }
}

private struct KnockSwitchBody: View {
    let configuration: ToggleStyle.Configuration

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorSchemeContrast) private var colorSchemeContrast

    private var isOn: Bool { configuration.isOn }

    private var trackStroke: Color {
        if isOn { return KnockTokens.electricRing }
        return colorSchemeContrast == .increased ? KnockTokens.strokeContrast : KnockTokens.strokeSecondary
    }

    var body: some View {
        Button {
            let animation: Animation? = reduceMotion ? nil : .easeOut(duration: 0.16)
            withAnimation(animation) {
                configuration.isOn.toggle()
            }
        } label: {
            HStack(spacing: KnockTokens.spacingM) {
                configuration.label
                Spacer(minLength: KnockTokens.spacingS)
                switchTrack
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityValue(isOn ? "已开启" : "已关闭")
        .accessibilityHint("双击切换")
    }

    private var switchTrack: some View {
        HStack(spacing: 0) {
            if isOn { Spacer(minLength: 0) }
            Circle()
                .fill(isOn ? KnockTokens.onElectric : KnockTokens.textTertiary)
                .frame(width: 22, height: 22)
            if !isOn { Spacer(minLength: 0) }
        }
        .padding(3)
        .frame(width: 46, height: 28)
        .background {
            Capsule(style: .continuous)
                .fill(isOn ? KnockTokens.electric : KnockTokens.row)
        }
        .overlay {
            Capsule(style: .continuous)
                .strokeBorder(trackStroke, lineWidth: KnockTokens.strokeWidth)
        }
        .accessibilityHidden(true)
    }
}

// MARK: - 表单区块（面板 + 分组标题 + 可选右侧角标）

/// 敲敲表单卡片：`KnockPanel` 内「14pt heavy 分组标题 + 可选角标 + 内容」。
struct KnockFormCard<Content: View>: View {
    var title: String
    var systemImage: String?
    var accessory: String?
    private let content: () -> Content

    init(
        title: String,
        systemImage: String? = nil,
        accessory: String? = nil,
        @ViewBuilder content: @escaping () -> Content
    ) {
        self.title = title
        self.systemImage = systemImage
        self.accessory = accessory
        self.content = content
    }

    var body: some View {
        KnockPanel {
            VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                HStack(spacing: KnockTokens.spacingS) {
                    if let systemImage {
                        Image(systemName: systemImage)
                            .font(.system(size: 13, weight: .heavy))
                            .foregroundStyle(KnockTokens.textSecondary)
                            .accessibilityHidden(true)
                    }
                    KnockText(title, role: .sectionTitle, lineLimit: 1)
                    Spacer(minLength: KnockTokens.spacingS)
                    if let accessory, !accessory.isEmpty {
                        KnockBadge(text: accessory, variant: .accent)
                    }
                }
                .accessibilityAddTraits(.isHeader)

                content()
            }
        }
    }
}

// MARK: - 多行输入框（TextEditor 套行底胶囊）

/// 敲敲多行输入：行底 `#171717`（浅 `#F7F7F5`）+ 1.5pt 次描边 + 圆角 14pt。
/// 占位符在空文本时叠加显示；文字走 `KnockTypography` 的 body 层级。
struct KnockTextArea: View {
    var placeholder: String
    @Binding var text: String
    var minHeight: CGFloat
    var accessibilityLabelText: String?

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.colorSchemeContrast) private var colorSchemeContrast

    init(
        _ placeholder: String,
        text: Binding<String>,
        minHeight: CGFloat = 110,
        accessibilityLabel: String? = nil
    ) {
        self.placeholder = placeholder
        self._text = text
        self.minHeight = minHeight
        self.accessibilityLabelText = accessibilityLabel
    }

    private var strokeColor: Color {
        colorSchemeContrast == .increased ? KnockTokens.strokeContrast : KnockTokens.strokeSecondary
    }

    var body: some View {
        ZStack(alignment: .topLeading) {
            if text.isEmpty {
                KnockText(placeholder, role: .body, color: KnockTokens.textTertiary, lineLimit: 2)
                    .padding(.horizontal, KnockTokens.chipHeight / 2 + KnockTokens.spacingXXS)
                    .padding(.vertical, KnockTokens.spacingM)
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
            }

            TextEditor(text: $text)
                .font(KnockTypography.font(.body, dynamicTypeSize: dynamicTypeSize))
                .foregroundStyle(KnockTokens.textPrimary)
                .tint(KnockTokens.electric)
                .scrollContentBackground(.hidden)
                .background(Color.clear)
                .padding(.horizontal, KnockTokens.spacingM - 4)
                .padding(.vertical, KnockTokens.spacingXS)
                .frame(minHeight: minHeight)
                .accessibilityLabel(accessibilityLabelText ?? placeholder)
        }
        .background {
            RoundedRectangle(cornerRadius: KnockTokens.panelRadiusSmall, style: .continuous)
                .fill(KnockTokens.row)
        }
        .overlay {
            RoundedRectangle(cornerRadius: KnockTokens.panelRadiusSmall, style: .continuous)
                .strokeBorder(strokeColor, lineWidth: KnockTokens.strokeWidth)
        }
    }
}

// MARK: - 胶囊选择行（敲敲自绘菜单；关系类型 / 候选角色等）

/// 敲敲选择器行：胶囊底 + 1.5pt 次描边 + 左侧图标 + 当前值 + 上下箭头。
/// 选项走 `KnockContextMenu` 自绘面板（计划书第五章：不用系统 `Menu`，避免 iOS 26 液态玻璃）；
/// 选中态由调用方在 `KnockMenuAction.systemImage` 里给 `checkmark`（与原系统菜单勾选一致）。
struct KnockMenuRow: View {
    var systemImage: String
    var valueText: String
    var accessibilityLabelText: String?
    private let actions: [KnockMenuAction]

    @Environment(\.colorSchemeContrast) private var colorSchemeContrast

    init(
        systemImage: String,
        valueText: String,
        accessibilityLabel: String? = nil,
        actions: [KnockMenuAction]
    ) {
        self.systemImage = systemImage
        self.valueText = valueText
        self.accessibilityLabelText = accessibilityLabel
        self.actions = actions
    }

    private var strokeColor: Color {
        colorSchemeContrast == .increased ? KnockTokens.strokeContrast : KnockTokens.strokeSecondary
    }

    var body: some View {
        Button {
            KnockMenuCenter.shared.present(KnockMenuPresentation(actions: actions))
        } label: {
            HStack(spacing: KnockTokens.spacingS) {
                Image(systemName: systemImage)
                    .font(.system(size: 13, weight: .heavy))
                    .foregroundStyle(KnockTokens.textSecondary)
                    .accessibilityHidden(true)
                KnockText(valueText, role: .rowTitle, lineLimit: 1)
                Spacer(minLength: KnockTokens.spacingS)
                Image(systemName: "chevron.up.chevron.down")
                    .font(.system(size: 10, weight: .heavy))
                    .foregroundStyle(KnockTokens.textSecondary)
                    .accessibilityHidden(true)
            }
            .padding(.horizontal, KnockTokens.spacingL)
            .frame(minHeight: KnockTokens.buttonHeight)
            .background(Capsule(style: .continuous).fill(KnockTokens.row))
            .overlay {
                Capsule(style: .continuous)
                    .strokeBorder(strokeColor, lineWidth: KnockTokens.strokeWidth)
            }
            .contentShape(Capsule(style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(accessibilityLabelText ?? valueText)
        .accessibilityHint("打开选项，共 \(actions.count) 项")
    }
}

// MARK: - 细进度条（亲密度 / 等级；蓝 = 进度语义，面积 <3%）

/// 敲敲细进度条：轨道用行内亮底，进度用聊天蓝 `#3364E7`（计划书配色逻辑「蓝 = 进度」）。
struct KnockProgressBar: View {
    /// 0...1
    var progress: Double
    var height: CGFloat
    var tint: Color

    init(progress: Double, height: CGFloat = 5, tint: Color = KnockTokens.chatBlue) {
        self.progress = progress
        self.height = height
        self.tint = tint
    }

    private var clamped: Double {
        guard progress.isFinite else { return 0 }
        return min(max(progress, 0), 1)
    }

    var body: some View {
        GeometryReader { geometry in
            ZStack(alignment: .leading) {
                Capsule(style: .continuous)
                    .fill(KnockTokens.bubbleSystem)
                Capsule(style: .continuous)
                    .fill(tint)
                    .frame(width: max(0, geometry.size.width * clamped))
            }
        }
        .frame(height: height)
        .accessibilityHidden(true)
    }
}

// MARK: - 信息小格（位置 / 状态 / 心情）

/// 面板内的三格信息块：暗底圆角 + 12pt 说明 + 主文字值。
struct KnockInfoCell: View {
    var title: String
    var value: String

    var body: some View {
        VStack(spacing: KnockTokens.spacingXXS) {
            KnockText(title, role: .badge, color: KnockTokens.textTertiary, lineLimit: 1)
            KnockText(
                value.isEmpty ? "—" : value,
                role: .captionStrong,
                lineLimit: 1,
                minimumScaleFactor: 0.7
            )
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, KnockTokens.spacingS)
        .background {
            RoundedRectangle(cornerRadius: KnockTokens.panelRadiusSmall, style: .continuous)
                .fill(KnockTokens.row)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(title)：\(value.isEmpty ? "未设定" : value)")
    }
}