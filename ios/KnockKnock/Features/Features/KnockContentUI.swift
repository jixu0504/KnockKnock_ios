//
//  KnockContentUI.swift
//  「敲敲」内容域（P3）共用视觉件：页面外壳 / 胶囊卡片 / 状态面板 / 胶囊动作键 /
//  流式布局 / Toast / 自绘输入弹窗。
//
//  设计依据：docs/敲敲UI改版计划书-v1.1.md
//  - 第 1.2 节：胶囊行（行底 `#171717` + 1.5pt 次描边）、面板（圆角 20pt + 实色 + 1.5pt 描边）、
//    黄 = 选中 / 主 CTA、E M P T Y 空态；
//  - 第 2.3 节 + 第五章：全部二级页面隐藏系统导航栏 → `KnockHeader`；
//    iOS 26+ 关闭滚动边缘玻璃效果（`.scrollEdgeEffectHidden()`）；
//  - 自绘弹窗替代系统 alert / confirmationDialog（避开 iOS 26 玻璃背景）。
//
//  纪律：
//  - 只引用 `KnockTokens` / `KnockTypography` / 设计系统组件，不自造颜色；
//  - 黄色只出现在「选中态 / 主操作 / 当前态」，出现面积远小于 5%；
//  - 页面视觉改动不触碰业务逻辑与 `accessibilityIdentifier`。
//
//  可访问性：
//  - 所有可交互控件都是真实 Button（最小命中 44pt），有中文 `accessibilityLabel`；
//  - Dynamic Type：容器一律 `minHeight`，不固定高度裁切；
//  - 减弱动态效果时弹窗/Toast 不做动画；增强对比度描边交给设计系统。
//

import SwiftUI

// MARK: - 页面外壳：隐藏系统导航栏 / 关闭滚动边缘玻璃效果

extension View {
    /// 隐藏系统 NavigationBar（页面改用 `KnockHeader` 自绘顶栏）。
    func knockHiddenNavigationBar() -> some View {
        toolbar(.hidden, for: .navigationBar)
    }

    /// 关闭滚动边缘效果（iOS 26+）：避免系统在导航栏/安全区边缘叠加玻璃模糊。
    @ViewBuilder
    func knockScrollEdgeHidden() -> some View {
        if #available(iOS 26.0, *) {
            scrollEdgeEffectHidden(true)
        } else {
            self
        }
    }

    /// iOS 26+ 固定纯色工具条背景（兜底：自定义顶栏下不出现系统玻璃）。
    @ViewBuilder
    func knockToolbarBackgroundHidden() -> some View {
        if #available(iOS 26.0, *) {
            toolbarBackgroundVisibility(.hidden, for: .navigationBar)
        } else {
            self
        }
    }
}

// MARK: - 流式布局（Chip 自动换行）

/// 流式布局：子视图按行排布、放不下自动换行（印象标签 / 动态标签等用）。
struct KnockFlowLayout: Layout {
    var spacing: CGFloat
    var lineSpacing: CGFloat

    init(spacing: CGFloat = KnockTokens.spacingXS, lineSpacing: CGFloat = KnockTokens.spacingXS) {
        self.spacing = spacing
        self.lineSpacing = lineSpacing
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout Void) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        var rowWidth: CGFloat = 0
        var rowHeight: CGFloat = 0
        var totalHeight: CGFloat = 0
        var maxRowWidth: CGFloat = 0

        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if rowWidth > 0, rowWidth + spacing + size.width > maxWidth {
                maxRowWidth = max(maxRowWidth, rowWidth)
                totalHeight += rowHeight + lineSpacing
                rowWidth = size.width
                rowHeight = size.height
            } else {
                rowWidth += (rowWidth > 0 ? spacing : 0) + size.width
                rowHeight = max(rowHeight, size.height)
            }
        }
        maxRowWidth = max(maxRowWidth, rowWidth)
        totalHeight += rowHeight
        return CGSize(width: min(maxRowWidth, maxWidth), height: totalHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout Void) {
        var x = bounds.minX
        var y = bounds.minY
        var rowHeight: CGFloat = 0

        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x > bounds.minX, x + size.width > bounds.maxX {
                x = bounds.minX
                y += rowHeight + lineSpacing
                rowHeight = 0
            }
            subview.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
    }
}

// MARK: - 胶囊卡片（内容型行：动态卡片 / 记忆行 / 印象行 / 切块）

/// 内容型胶囊卡片：行底 + 1.5pt 次描边 + 极淡棋盘格。
/// 用于「一行里放多行内容」的列表项（简单单行请直接用 `KnockListRow`）。
struct KnockCapsuleCard<Content: View>: View {
    var cornerRadius: CGFloat
    var padding: CGFloat
    var showsChecker: Bool
    private let content: () -> Content

    @Environment(\.colorSchemeContrast) private var colorSchemeContrast

    init(
        cornerRadius: CGFloat = 22,
        padding: CGFloat = KnockTokens.spacingM,
        showsChecker: Bool = true,
        @ViewBuilder content: @escaping () -> Content
    ) {
        self.cornerRadius = cornerRadius
        self.padding = padding
        self.showsChecker = showsChecker
        self.content = content
    }

    private var strokeColor: Color {
        colorSchemeContrast == .increased ? KnockTokens.strokeContrast : KnockTokens.strokeSecondary
    }

    var body: some View {
        content()
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background {
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .fill(KnockTokens.row)
                    .overlay {
                        if showsChecker {
                            KnockCheckerOverlay(cornerRadius: cornerRadius)
                        }
                    }
            }
            .overlay {
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .strokeBorder(strokeColor, lineWidth: KnockTokens.strokeWidth)
            }
            .contentShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
    }
}

// MARK: - 状态面板（加载 / 错误）

/// 加载面板：面板 + 菊花 + 灰色说明（替代系统 ProgressView 裸放）。
struct KnockLoadingPanel: View {
    var text: String

    init(_ text: String = "读取中…") {
        self.text = text
    }

    var body: some View {
        KnockPanel {
            HStack(spacing: KnockTokens.spacingM) {
                ProgressView()
                    .tint(KnockTokens.textSecondary)
                KnockText(text, role: .caption, color: KnockTokens.textSecondary)
                Spacer(minLength: 0)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(text)
    }
}

/// 错误面板：面板 + 危险色标题 + 说明 + 可选「重试」次按钮。
struct KnockErrorPanel: View {
    var title: String
    var message: String
    var retryTitle: String?
    var retry: (() -> Void)?

    init(
        title: String,
        message: String,
        retryTitle: String? = "重试",
        retry: (() -> Void)? = nil
    ) {
        self.title = title
        self.message = message
        self.retryTitle = retryTitle
        self.retry = retry
    }

    var body: some View {
        KnockPanel {
            VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                HStack(spacing: KnockTokens.spacingS) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.system(size: 14, weight: .heavy))
                        .foregroundStyle(KnockTokens.dangerBright)
                        .accessibilityHidden(true)
                    KnockText(title, role: .rowTitle, color: KnockTokens.dangerBright)
                    Spacer(minLength: 0)
                }
                KnockText(message, role: .caption, color: KnockTokens.textSecondary, lineSpacing: 3)
                if let retryTitle, let retry {
                    Button(retryTitle, action: retry)
                        .buttonStyle(KnockSecondaryButtonStyle(fullWidth: false, height: 34))
                }
            }
        }
    }
}

// MARK: - 胶囊动作键（点赞 / 评论 / 回复等行内小操作）

/// 行内胶囊动作键：未激活 = 行底 + 次描边 + 主文字；激活（已选中）= 黄底 + 浅蓝外圈 + 黑字。
/// 黄色面积仅限一个小胶囊，符合「黄 = 当前选中」纪律。
struct KnockPillActionButton: View {
    var title: String
    var systemImage: String
    var isActive: Bool
    var action: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    init(title: String, systemImage: String, isActive: Bool = false, action: @escaping () -> Void) {
        self.title = title
        self.systemImage = systemImage
        self.isActive = isActive
        self.action = action
    }

    var body: some View {
        Button {
            let animation: Animation? = reduceMotion ? nil : .easeOut(duration: 0.12)
            withAnimation(animation) { action() }
        } label: {
            HStack(spacing: 5) {
                Image(systemName: systemImage)
                    .font(.system(size: 12, weight: .heavy))
                KnockText(
                    title,
                    role: .captionStrong,
                    color: isActive ? KnockTokens.onElectric : KnockTokens.textPrimary,
                    lineLimit: 1
                )
            }
            .padding(.horizontal, 12)
            .frame(minHeight: 32)
            .background {
                Capsule(style: .continuous)
                    .fill(isActive ? KnockTokens.electric : KnockTokens.row)
                    .overlay {
                        if isActive {
                            Capsule(style: .continuous)
                                .strokeBorder(KnockTokens.electricRing, lineWidth: KnockTokens.strokeWidth)
                        }
                    }
            }
            .overlay {
                if !isActive {
                    Capsule(style: .continuous)
                        .strokeBorder(KnockTokens.strokeSecondary, lineWidth: KnockTokens.strokeWidth)
                }
            }
            // 视觉 32pt、命中区补齐到 44pt
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title)
        .accessibilityAddTraits(isActive ? [.isSelected] : [])
    }
}

// MARK: - Toast（黑底白字胶囊，实色非玻璃）

/// 轻提示：面板实色胶囊 + 白字，顶部出现（替代半透明黑胶囊）。
struct KnockToast: View {
    var text: String

    var body: some View {
        KnockText(text, role: .captionStrong, color: KnockTokens.textOnDark, lineLimit: 2)
            .padding(.horizontal, KnockTokens.spacingL)
            .padding(.vertical, KnockTokens.spacingS)
            .background {
                Capsule(style: .continuous).fill(KnockTokens.danger)
            }
            .accessibilityLabel(text)
    }
}

// MARK: - 自绘输入弹窗（重命名等；替代系统带 TextField 的 alert）

/// 单输入框弹窗：标题 + 胶囊输入 + 取消/确认。
struct KnockTextPromptDialog: View {
    var title: String
    var message: String?
    var placeholder: String
    @Binding var text: String
    var confirmTitle: String
    var onConfirm: () -> Void
    var onCancel: () -> Void

    var body: some View {
        KnockDialogPanel(title: title) {
            VStack(alignment: .leading, spacing: KnockTokens.spacingXL) {
                if let message, !message.isEmpty {
                    KnockText(message, role: .caption, color: KnockTokens.textSecondary, lineSpacing: 3)
                }
                TextField(placeholder, text: $text)
                    .textFieldStyle(KnockTextFieldStyle())
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .accessibilityLabel(placeholder)
                HStack(spacing: KnockTokens.spacingM) {
                    Button("取消", action: onCancel)
                        .buttonStyle(KnockSecondaryButtonStyle())
                    Button(confirmTitle, action: onConfirm)
                        .buttonStyle(KnockPrimaryButtonStyle())
                        .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }
}

// MARK: - 自绘提示弹窗（操作失败 / 结果说明；替代系统 alert）

/// 单按钮提示弹窗：标题 + 说明 + 「知道了」黄按钮。
struct KnockNoticeDialog: View {
    var title: String
    var message: String
    var confirmTitle: String = "知道了"
    var onConfirm: () -> Void

    var body: some View {
        KnockDialogPanel(title: title) {
            VStack(alignment: .leading, spacing: KnockTokens.spacingXL) {
                KnockText(message, role: .caption, color: KnockTokens.textSecondary, lineSpacing: 3)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Button(confirmTitle, action: onConfirm)
                    .buttonStyle(KnockPrimaryButtonStyle())
            }
        }
    }
}

// MARK: - 弹窗挂载修饰符

private struct KnockTextPromptDialogModifier: ViewModifier {
    @Binding var isPresented: Bool
    let title: String
    let message: String?
    let placeholder: String
    @Binding var text: String
    let confirmTitle: String
    let onConfirm: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content.overlay {
            if isPresented {
                ZStack {
                    Color.black.opacity(0.62)
                        .ignoresSafeArea()
                        .onTapGesture { isPresented = false }
                        .accessibilityHidden(true)
                    KnockTextPromptDialog(
                        title: title,
                        message: message,
                        placeholder: placeholder,
                        text: $text,
                        confirmTitle: confirmTitle,
                        onConfirm: { isPresented = false; onConfirm() },
                        onCancel: { isPresented = false }
                    )
                    .padding(.horizontal, KnockTokens.spacingXXL)
                    .accessibilityAddTraits(.isModal)
                }
                .transition(.opacity)
            }
        }
        .animation(reduceMotion ? nil : .easeOut(duration: 0.18), value: isPresented)
    }
}

private struct KnockNoticeDialogModifier: ViewModifier {
    @Binding var isPresented: Bool
    let title: String
    let message: String

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content.overlay {
            if isPresented {
                ZStack {
                    Color.black.opacity(0.62)
                        .ignoresSafeArea()
                        .onTapGesture { isPresented = false }
                        .accessibilityHidden(true)
                    KnockNoticeDialog(title: title, message: message) {
                        isPresented = false
                    }
                    .padding(.horizontal, KnockTokens.spacingXXL)
                    .accessibilityAddTraits(.isModal)
                }
                .transition(.opacity)
            }
        }
        .animation(reduceMotion ? nil : .easeOut(duration: 0.18), value: isPresented)
    }
}

extension View {
    /// 挂载单输入框自绘弹窗（重命名等）。
    func knockTextPromptDialog(
        isPresented: Binding<Bool>,
        title: String,
        message: String? = nil,
        placeholder: String,
        text: Binding<String>,
        confirmTitle: String = "保存",
        onConfirm: @escaping () -> Void
    ) -> some View {
        modifier(
            KnockTextPromptDialogModifier(
                isPresented: isPresented,
                title: title,
                message: message,
                placeholder: placeholder,
                text: text,
                confirmTitle: confirmTitle,
                onConfirm: onConfirm
            )
        )
    }

    /// 挂载提示自绘弹窗（操作失败 / 结果说明）。
    func knockNoticeDialog(
        isPresented: Binding<Bool>,
        title: String,
        message: String
    ) -> some View {
        modifier(
            KnockNoticeDialogModifier(
                isPresented: isPresented,
                title: title,
                message: message
            )
        )
    }
}

// MARK: - 段落统计胶囊（计数条：灰阶胶囊，不占黄色额度）

/// 中性计数胶囊：`标题 + 数值`（排行榜合计、印象各段计数等）。
struct KnockCountPill: View {
    var title: String
    var value: String

    var body: some View {
        HStack(spacing: KnockTokens.spacingXS) {
            KnockText(title, role: .caption, color: KnockTokens.textSecondary, lineLimit: 1)
            KnockText(value, role: .captionStrong, lineLimit: 1)
        }
        .padding(.horizontal, KnockTokens.spacingS)
        .frame(minHeight: 26)
        .background(Capsule(style: .continuous).fill(KnockTokens.rowIconWell))
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(title) \(value)")
    }
}