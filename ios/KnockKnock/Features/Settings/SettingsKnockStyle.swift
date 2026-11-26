//
//  SettingsKnockStyle.swift
//  设置域「敲敲」视觉辅助件（仅 Features/Settings 使用，不进入 DesignSystem，避免跨域耦合）。
//
//  设计依据：docs/敲敲UI改版计划书-v1.1.md 第 1.2 / 1.3 / 2.1 节：
//  - 自绘胶囊开关：胶囊轨道；开 = 电光黄底 + 浅蓝 1.5pt 外圈 + 黑滑块（选中断言），
//    关 = 行底 + 1.5pt 次描边 + 灰滑块；黄色只做「开」态，面积远小于 5%；
//  - 滑块：行底轨道 + 1.5pt 描边 + 黄色已选段 + 黄底浅蓝圈拇指（与选中胶囊同语言）；
//  - 进度条：黄色已选段（备份/恢复/导入导出用）；无确定进度时用缓动循环段，
//    减弱动态效果（reduceMotion）时静态显示，不做循环动画；
//  - 状态提示：成功 = 黄描边、失败 = 红描边（计划书配色纪律：红仅错误，黄仅主 CTA/选中）；
//  - 表单输入：行底胶囊 + 1.5pt 描边 + 等宽字体（API Key / 端点 / 模型名）；
//  - 诊断行：等宽 12pt + 可选中（保留旧页面的 textSelection 行为）。
//
//  可访问性：
//  - 开关/滑块均为真实可操作元素（滑块支持 VoiceOver 上下滑动调整）；
//  - 所有动态字体经 `KnockText` / `KnockTypography`，大字号下 `minHeight` 自动增高、不裁切；
//  - 减弱动态效果时取消弹簧/循环动画；
//  - 增强对比度时描边加深（`colorSchemeContrast == .increased`）。
//

import SwiftUI
import UIKit

// MARK: - 系统导航栏 / 滚动边缘隐藏

extension View {
    /// 隐藏系统导航栏（设置域页面统一改用自绘标题 / `KnockHeader`）。
    func settingsKnockNavigationChrome() -> some View {
        self
            .toolbar(.hidden, for: .navigationBar)
            .settingsKnockScrollEdgeHidden()
    }

    /// iOS 26+ 关闭滚动边缘玻璃效果；旧系统为 no-op（计划书第五章 Liquid Glass 对策）。
    @ViewBuilder
    func settingsKnockScrollEdgeHidden() -> some View {
        if #available(iOS 26.0, *) {
            self.scrollEdgeEffectHidden(true)
        } else {
            self
        }
    }
}

// MARK: - 页面结构

/// 设置页分组：`KnockGroupTitle` 分组标题 + 内容（面板/胶囊行）。
struct SettingsKnockGroup<Content: View>: View {
    var title: String
    var systemImage: String?
    var footer: String?
    private let content: () -> Content

    init(
        _ title: String,
        systemImage: String? = nil,
        footer: String? = nil,
        @ViewBuilder content: @escaping () -> Content
    ) {
        self.title = title
        self.systemImage = systemImage
        self.footer = footer
        self.content = content
    }

    var body: some View {
        VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
            KnockGroupTitle(title: title, systemImage: systemImage)
                .padding(.horizontal, KnockTokens.spacingXS)
            content()
            if let footer, !footer.isEmpty {
                KnockText(footer, role: .caption, color: KnockTokens.textTertiary, lineSpacing: 2)
                    .padding(.horizontal, KnockTokens.spacingXS)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// 页面大标题（设置根 / 列表页）：34pt heavy + 可选副标题。
struct SettingsKnockPageTitle: View {
    var title: String
    var subtitle: String?

    var body: some View {
        VStack(alignment: .leading, spacing: KnockTokens.spacingXXS) {
            KnockText(title, role: .display, lineLimit: 1)
            if let subtitle, !subtitle.isEmpty {
                KnockText(subtitle, role: .caption, color: KnockTokens.textSecondary, lineLimit: 2)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: - 自绘胶囊开关

/// 自绘胶囊开关样式：`Toggle(...).toggleStyle(SettingsKnockSwitchStyle())`。
/// 开 = 黄底 + 浅蓝外圈 + 黑滑块；关 = 行底 + 次描边 + 灰滑块。
struct SettingsKnockSwitchStyle: ToggleStyle {
    var scale: CGFloat

    init(scale: CGFloat = 1) {
        self.scale = scale
    }

    func makeBody(configuration: Configuration) -> some View {
        SettingsKnockSwitchBody(configuration: configuration, scale: scale)
    }
}

private struct SettingsKnockSwitchBody: View {
    let configuration: ToggleStyleConfiguration
    let scale: CGFloat

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorSchemeContrast) private var colorSchemeContrast

    private var isOn: Bool { configuration.isOn }
    private let trackWidth: CGFloat = 52
    private let trackHeight: CGFloat = 30

    private var offStroke: Color {
        colorSchemeContrast == .increased ? KnockTokens.strokeContrast : KnockTokens.strokeSecondary
    }

    var body: some View {
        Button {
            let animation: Animation? = reduceMotion ? nil : .easeOut(duration: 0.16)
            withAnimation(animation) { configuration.isOn.toggle() }
        } label: {
            HStack(spacing: KnockTokens.spacingL) {
                configuration.label
                Spacer(minLength: KnockTokens.spacingM)
                track
            }
            .contentShape(Rectangle())
            // 命中区补齐 44pt（Apple 最小点击高度）
            .frame(minHeight: 44)
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityValue(isOn ? "开" : "关")
        .accessibilityHint("双击切换")
    }

    private var track: some View {
        ZStack(alignment: isOn ? .trailing : .leading) {
            Capsule(style: .continuous)
                .fill(isOn ? KnockTokens.electric : KnockTokens.searchField)
                .overlay {
                    if isOn {
                        Capsule(style: .continuous)
                            .strokeBorder(KnockTokens.electricRing, lineWidth: KnockTokens.strokeWidth)
                    } else {
                        Capsule(style: .continuous)
                            .strokeBorder(offStroke, lineWidth: KnockTokens.strokeWidth)
                    }
                }

            Circle()
                .fill(isOn ? KnockTokens.onElectric : KnockTokens.textSecondary)
                .padding(3)
        }
        .frame(width: trackWidth * scale, height: trackHeight * scale)
        .animation(reduceMotion ? nil : .easeOut(duration: 0.16), value: isOn)
        .accessibilityHidden(true)
    }
}

/// 开关行（标题 + 说明 + 自绘开关）；点击整行即可切换（由 ToggleStyle 提供）。
struct SettingsKnockToggleRow: View {
    var title: String
    var detail: String?
    var footnote: String?
    @Binding var isOn: Bool

    var body: some View {
        Toggle(isOn: $isOn) {
            VStack(alignment: .leading, spacing: KnockTokens.spacingXXS) {
                KnockText(title, role: .rowTitle)
                if let detail, !detail.isEmpty {
                    KnockText(detail, role: .caption, color: KnockTokens.textSecondary, lineSpacing: 2)
                }
                if let footnote, !footnote.isEmpty {
                    KnockText(footnote, role: .mono, color: KnockTokens.textTertiary, lineLimit: 1)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .multilineTextAlignment(.leading)
        }
        .toggleStyle(SettingsKnockSwitchStyle())
        .accessibilityLabel(title)
    }
}

// MARK: - 自绘滑块

/// 自绘滑块：行底轨道 + 1.5pt 描边 + 黄色已选段 + 黄底浅蓝圈拇指。
struct SettingsKnockSlider: View {
    @Binding var value: Double
    var range: ClosedRange<Double>
    var step: Double
    var accessibilityLabelText: String

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorSchemeContrast) private var colorSchemeContrast

    private let trackHeight: CGFloat = 8
    private let thumbSize: CGFloat = 22

    private var fraction: CGFloat {
        let span = range.upperBound - range.lowerBound
        guard span > 0 else { return 0 }
        return CGFloat((value - range.lowerBound) / span)
    }

    private var strokeColor: Color {
        colorSchemeContrast == .increased ? KnockTokens.strokeContrast : KnockTokens.strokeSecondary
    }

    var body: some View {
        GeometryReader { geometry in
            let width = max(geometry.size.width, thumbSize)
            let travel = width - thumbSize
            let thumbX = travel * min(max(fraction, 0), 1)

            ZStack(alignment: .leading) {
                Capsule(style: .continuous)
                    .fill(KnockTokens.searchField)
                    .overlay {
                        Capsule(style: .continuous)
                            .strokeBorder(strokeColor, lineWidth: KnockTokens.strokeWidth)
                    }
                    .frame(height: trackHeight)
                    .frame(maxHeight: .infinity)

                Capsule(style: .continuous)
                    .fill(KnockTokens.electric)
                    .frame(width: max(trackHeight, thumbX + thumbSize / 2), height: trackHeight)
                    .frame(maxHeight: .infinity, alignment: .center)

                Circle()
                    .fill(KnockTokens.electric)
                    .overlay {
                        Circle().strokeBorder(KnockTokens.electricRing, lineWidth: KnockTokens.strokeWidth)
                    }
                    .frame(width: thumbSize, height: thumbSize)
                    .offset(x: thumbX)
            }
            .frame(height: 32)
            .frame(maxHeight: .infinity)
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { gesture in
                        let x = min(max(gesture.location.x - thumbSize / 2, 0), travel)
                        let raw = travel > 0 ? Double(x / travel) : 0
                        setValue(range.lowerBound + raw * (range.upperBound - range.lowerBound))
                    }
            )
        }
        .frame(height: 32)
        .accessibilityElement()
        .accessibilityLabel(accessibilityLabelText)
        .accessibilityValue(formattedValue)
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment:
                setValue(value + step)
            case .decrement:
                setValue(value - step)
            @unknown default:
                break
            }
        }
    }

    private var formattedValue: String {
        if step >= 1 {
            return "\(Int(value.rounded()))"
        }
        return String(format: "%.2f", value)
    }

    private func setValue(_ raw: Double) {
        let clamped = min(max(raw, range.lowerBound), range.upperBound)
        let stepped: Double
        if step > 0 {
            let steps = ((clamped - range.lowerBound) / step).rounded()
            stepped = range.lowerBound + steps * step
        } else {
            stepped = clamped
        }
        let final = min(max(stepped, range.lowerBound), range.upperBound)
        if abs(final - value) > .ulpOfOne {
            value = final
        }
    }
}

/// 滑块行：标题 + 数值 + 滑块 + 说明。
struct SettingsKnockSliderRow: View {
    var title: String
    var valueText: String
    @Binding var value: Double
    var range: ClosedRange<Double>
    var step: Double
    var hint: String?

    var body: some View {
        VStack(alignment: .leading, spacing: KnockTokens.spacingXS) {
            HStack(spacing: KnockTokens.spacingS) {
                KnockText(title, role: .captionStrong)
                Spacer(minLength: KnockTokens.spacingS)
                KnockText(valueText, role: .mono, color: KnockTokens.electric, lineLimit: 1)
            }
            SettingsKnockSlider(
                value: $value,
                range: range,
                step: step,
                accessibilityLabelText: title
            )
            if let hint, !hint.isEmpty {
                KnockText(hint, role: .caption, color: KnockTokens.textTertiary, lineSpacing: 2)
            }
        }
    }
}

/// 双滑块行（最小值–最大值），用于聊天表现的段数/字数区间。
struct SettingsKnockRangeRow: View {
    var title: String
    @Binding var minValue: Int
    @Binding var maxValue: Int
    var range: ClosedRange<Int>
    var step: Int
    var hint: String

    private var minBinding: Binding<Double> {
        Binding(
            get: { Double(minValue) },
            set: { newValue in
                let v = Int(newValue.rounded())
                minValue = min(v, maxValue)
            }
        )
    }

    private var maxBinding: Binding<Double> {
        Binding(
            get: { Double(maxValue) },
            set: { newValue in
                let v = Int(newValue.rounded())
                maxValue = max(v, minValue)
            }
        )
    }

    var body: some View {
        VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
            HStack(spacing: KnockTokens.spacingS) {
                KnockText(title, role: .captionStrong)
                Spacer(minLength: KnockTokens.spacingS)
                KnockText("\(minValue) – \(maxValue)", role: .mono, color: KnockTokens.electric, lineLimit: 1)
            }

            VStack(alignment: .leading, spacing: KnockTokens.spacingXXS) {
                KnockText("最少", role: .caption, color: KnockTokens.textSecondary, lineLimit: 1)
                SettingsKnockSlider(
                    value: minBinding,
                    range: Double(range.lowerBound)...Double(range.upperBound),
                    step: Double(step),
                    accessibilityLabelText: "\(title)最少"
                )
            }

            VStack(alignment: .leading, spacing: KnockTokens.spacingXXS) {
                KnockText("最多", role: .caption, color: KnockTokens.textSecondary, lineLimit: 1)
                SettingsKnockSlider(
                    value: maxBinding,
                    range: Double(range.lowerBound)...Double(range.upperBound),
                    step: Double(step),
                    accessibilityLabelText: "\(title)最多"
                )
            }

            KnockText(hint, role: .caption, color: KnockTokens.textTertiary, lineSpacing: 2)
        }
    }
}

// MARK: - 黄色进度条

/// 黄色进度条：`progress` 为 nil 时按循环段显示（备份/导入进行中），否则按比例显示。
struct SettingsKnockProgressBar: View {
    var progress: Double?
    var height: CGFloat

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var animate = false

    init(progress: Double? = nil, height: CGFloat = 8) {
        self.progress = progress
        self.height = height
    }

    var body: some View {
        GeometryReader { geometry in
            let width = max(geometry.size.width, 1)
            let ratio = CGFloat(min(max(progress ?? 0, 0), 1))

            ZStack(alignment: .leading) {
                Capsule(style: .continuous)
                    .fill(KnockTokens.searchField)
                    .overlay {
                        Capsule(style: .continuous)
                            .strokeBorder(KnockTokens.strokeSecondary, lineWidth: KnockTokens.strokeWidth)
                    }

                if let progress {
                    Capsule(style: .continuous)
                        .fill(KnockTokens.electric)
                        .frame(width: max(height, width * ratio))
                } else {
                    Capsule(style: .continuous)
                        .fill(KnockTokens.electric)
                        .frame(width: width * 0.34)
                        .offset(x: animate && !reduceMotion ? width * 0.66 : width * 0.22)
                        .animation(
                            reduceMotion ? nil : .easeInOut(duration: 1.05).repeatForever(autoreverses: true),
                            value: animate
                        )
                }
            }
            .clipShape(Capsule(style: .continuous))
            .onAppear {
                guard progress == nil, !reduceMotion else { return }
                animate = true
            }
        }
        .frame(height: height)
        .accessibilityElement()
        .accessibilityLabel("进度")
        .accessibilityValue(progress.map { "\(Int($0 * 100))%" } ?? "进行中")
    }
}

// MARK: - 状态提示条

/// 状态提示类型：成功（黄描边）/ 失败（红描边）/ 中性（灰描边）。
enum SettingsKnockStatusKind {
    case success
    case failure
    case info

    var stroke: Color {
        switch self {
        case .success: return KnockTokens.electric
        case .failure: return KnockTokens.dangerBright
        case .info: return KnockTokens.strokeSecondary
        }
    }

    var icon: String {
        switch self {
        case .success: return "checkmark.circle.fill"
        case .failure: return "exclamationmark.triangle.fill"
        case .info: return "info.circle"
        }
    }

    var iconColor: Color {
        switch self {
        case .success: return KnockTokens.electric
        case .failure: return KnockTokens.dangerBright
        case .info: return KnockTokens.textSecondary
        }
    }

    var accessibilityWord: String {
        switch self {
        case .success: return "成功"
        case .failure: return "失败"
        case .info: return "提示"
        }
    }
}

/// 状态提示面板：成功/失败用黄/红 1.5pt 描边（计划书要求）。
struct SettingsKnockStatusBanner: View {
    var kind: SettingsKnockStatusKind
    var title: String
    var detail: String?
    var monospacedDetail: Bool

    init(
        kind: SettingsKnockStatusKind,
        title: String,
        detail: String? = nil,
        monospacedDetail: Bool = false
    ) {
        self.kind = kind
        self.title = title
        self.detail = detail
        self.monospacedDetail = monospacedDetail
    }

    var body: some View {
        VStack(alignment: .leading, spacing: KnockTokens.spacingXS) {
            HStack(spacing: KnockTokens.spacingS) {
                Image(systemName: kind.icon)
                    .font(.system(size: 13, weight: .heavy))
                    .foregroundStyle(kind.iconColor)
                    .accessibilityHidden(true)
                KnockText(title, role: .captionStrong, lineLimit: 3)
                Spacer(minLength: 0)
            }
            if let detail, !detail.isEmpty {
                if monospacedDetail {
                    Text(detail)
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundStyle(KnockTokens.textSecondary)
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                } else {
                    KnockText(detail, role: .caption, color: KnockTokens.textSecondary, lineSpacing: 2)
                }
            }
        }
        .padding(.horizontal, KnockTokens.spacingM)
        .padding(.vertical, KnockTokens.spacingS)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background {
            RoundedRectangle(cornerRadius: KnockTokens.panelRadiusSmall, style: .continuous)
                .fill(KnockTokens.panelInset)
        }
        .overlay {
            RoundedRectangle(cornerRadius: KnockTokens.panelRadiusSmall, style: .continuous)
                .strokeBorder(kind.stroke, lineWidth: KnockTokens.strokeWidth)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(kind.accessibilityWord)：\(title)")
    }
}

// MARK: - 等宽表单输入

/// 表单输入框（等宽字体）：标签 + 胶囊描边输入区 + 脚注。
struct SettingsKnockField: View {
    var label: String
    @Binding var text: String
    var placeholder: String
    var isSecure: Bool
    var footnote: String?
    var keyboardType: UIKeyboardType

    init(
        _ label: String,
        text: Binding<String>,
        placeholder: String,
        isSecure: Bool = false,
        footnote: String? = nil,
        keyboardType: UIKeyboardType = .default
    ) {
        self.label = label
        self._text = text
        self.placeholder = placeholder
        self.isSecure = isSecure
        self.footnote = footnote
        self.keyboardType = keyboardType
    }

    @Environment(\.colorSchemeContrast) private var colorSchemeContrast

    private var strokeColor: Color {
        colorSchemeContrast == .increased ? KnockTokens.strokeContrast : KnockTokens.strokeSecondary
    }

    var body: some View {
        VStack(alignment: .leading, spacing: KnockTokens.spacingXS) {
            KnockText(label, role: .captionStrong, color: KnockTokens.textSecondary, lineLimit: 1)
            Group {
                if isSecure {
                    SecureField(placeholder, text: $text)
                } else {
                    TextField(placeholder, text: $text)
                }
            }
            .font(.system(size: 14, weight: .medium, design: .monospaced))
            .foregroundStyle(KnockTokens.textPrimary)
            .tint(KnockTokens.electric)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .keyboardType(keyboardType)
            .padding(.horizontal, KnockTokens.spacingM)
            .frame(minHeight: KnockTokens.searchHeight)
            .background(Capsule(style: .continuous).fill(KnockTokens.row))
            .overlay {
                Capsule(style: .continuous)
                    .strokeBorder(strokeColor, lineWidth: KnockTokens.strokeWidth)
            }
            if let footnote, !footnote.isEmpty {
                KnockText(footnote, role: .caption, color: KnockTokens.textTertiary, lineSpacing: 2)
            }
        }
    }
}

// MARK: - 诊断行（等宽）

/// 诊断键值行：10pt 次文字标题 + 等宽值（可长按选择）。
struct SettingsKnockInfoLine: View {
    var title: String
    var value: String
    var monospaced: Bool

    init(_ title: String, _ value: String, monospaced: Bool = false) {
        self.title = title
        self.value = value
        self.monospaced = monospaced
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            KnockText(title, role: .caption, color: KnockTokens.textTertiary, lineLimit: 1)
            Text(value)
                .font(monospaced
                      ? .system(size: 11, design: .monospaced)
                      : .system(size: 12))
                .foregroundStyle(KnockTokens.textPrimary)
                .lineLimit(3)
                .truncationMode(.middle)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

// MARK: - 说明条目（• 项目符号）

/// 说明条目：黄色项目符号 + 次文字正文（设置页说明卡用）。
struct SettingsKnockBullet: View {
    var text: String

    init(_ text: String) {
        self.text = text
    }

    var body: some View {
        HStack(alignment: .top, spacing: KnockTokens.spacingS) {
            Image(systemName: "square.fill")
                .font(.system(size: 6, weight: .heavy))
                .foregroundStyle(KnockTokens.electric)
                .padding(.top, 5)
                .accessibilityHidden(true)
            KnockText(text, role: .caption, color: KnockTokens.textSecondary, lineSpacing: 2)
        }
    }
}