//
//  KnockContextMenu.swift
//  「敲敲」自绘长按操作菜单（替代系统 `.contextMenu` / `Menu` 的 iOS 26 液态玻璃弹层）。
//
//  设计依据：docs/敲敲UI改版计划书-v1.1.md
//  - 第五章 Liquid Glass 风险：系统菜单（contextMenu / Menu / alert）的面板由系统接管
//    （iOS 26 为玻璃 + 圆角弹簧），无法还原敲敲的实色面板 → 全部自绘；
//  - 第 1.2 节面板规格：圆角 20pt、实色底（深 `#0A0A0A` 系 / 浅白）、1pt 描边、
//    可选面板棋盘格；配色纪律：黑 ≈85%、白 ≈10%、黄 ≤5%、红 <1%；
//  - 本组件不铺黄色（菜单无「选中 / 主 CTA」语义），危险项用红（删除 / 撤回），
//    其余一律 `textPrimary`；
//  - 第 1.4 节：文字字号 15pt / 图标 15pt，**字重固定 `.semibold`**（字重收口由
//    `KnockTypography` 负责，本文件不写 `.heavy`，也不依赖 `KnockText` 的档位）。
//
//  交互（与系统 contextMenu 对齐）：
//  - 长按 0.35s 触发；拖动超过 22pt 长按失败 → 让位给列表滚动（不劫持滚动手势）；
//  - 点遮罩 / Esc / 选择任一项关闭；面板 scale 0.96→1.0 + opacity 淡入；
//  - 触觉反馈 `UIImpactFeedbackGenerator(style: .light)`；
//  - 菜单打开期间锁定所在 `UIScrollView` 滚动（`.scrollDisabled` 无法从行内改祖先，
//    用零尺寸 UIKit 探针找到最近滚动视图临时关闭 `isScrollEnabled`，关闭即恢复）；
//  - 减弱动态效果（reduceMotion）时不缩放、不淡入；增强对比度时描边用 `strokeContrast`。
//
//  可访问性（VoiceOver）：
//  - 触发器通过 `.accessibilityAction(named:)` 暴露与面板完全相同的动作集合
//    （disabled 项不暴露），长按不再是唯一的操作入口；
//  - 遮罩 `.accessibilityHidden(true)`，面板带 `.isModal` trait 并隐藏背后内容
//    （宿主把 content 一并 `accessibilityHidden`），VoiceOver 只在面板内漫游；
//  - 行高 44pt、图标与文字均单色描边，危险项红字（浅色 `#BF2207` / 深色 `#FF3B30`，
//    两档均满足 WCAG 4.5:1）。
//
//  架构：菜单状态放在 `KnockMenuCenter`（单例），面板由 App 根部 `.knockMenuHost()`
//  统一渲染 —— 这样面板不会被 ScrollView / List 的裁剪边界切掉，也不再需要每个页面
//  各自持有弹层状态。
//

import SwiftUI
import UIKit

// MARK: - 菜单动作模型

/// 一条菜单动作（与系统 `Button` 菜单项一一对应）。
///
/// ```swift
/// .knockContextMenu(actions: [
///     KnockMenuAction("复制", systemImage: "doc.on.doc") { copy() },
///     KnockMenuAction("删除（本地）", systemImage: "trash", role: .destructive) { delete() },
/// ])
/// ```
struct KnockMenuAction: Identifiable {
    /// 动作语义：`destructive` 渲染为危险红（删除 / 撤回 / 移除）。
    enum Role {
        case normal
        case destructive
    }

    let id: String
    var title: String
    var systemImage: String?
    var role: Role
    var isEnabled: Bool
    var action: () -> Void

    init(
        _ title: String,
        systemImage: String? = nil,
        role: Role = .normal,
        isEnabled: Bool = true,
        action: @escaping () -> Void
    ) {
        self.id = UUID().uuidString
        self.title = title
        self.systemImage = systemImage
        self.role = role
        self.isEnabled = isEnabled
        self.action = action
    }
}

// MARK: - 菜单内容（一次弹出）

/// 一次菜单弹出：标题（可选）+ 动作列表 + 是否叠面板棋盘格。
struct KnockMenuPresentation: Identifiable {
    let id = UUID()
    var title: String?
    var showsChecker: Bool
    var actions: [KnockMenuAction]

    init(title: String? = nil, showsChecker: Bool = false, actions: [KnockMenuAction]) {
        self.title = title
        self.showsChecker = showsChecker
        self.actions = actions
    }
}

// MARK: - 菜单中心（全局单例，App 根部唯一宿主渲染）

/// 自绘菜单的状态中心：任意位置 `present` / `dismiss`，由 `.knockMenuHost()` 渲染。
/// 同一时刻只保留一个菜单（重复触发以最新一次为准，不叠层）。
@MainActor
final class KnockMenuCenter: ObservableObject {
    static let shared = KnockMenuCenter()

    @Published private(set) var presentation: KnockMenuPresentation?

    private init() {}

    func present(_ presentation: KnockMenuPresentation) {
        self.presentation = presentation
    }

    func dismiss() {
        presentation = nil
    }
}

// MARK: - 菜单配色（仅本文件使用，色值仍出自 KnockTokens 语义）

private enum KnockMenuPalette {
    /// 危险项前景：浅色 `#BF2207`（白底 ≈6.1:1）/ 深色 `#FF3B30`（黑底 ≈5.9:1）。
    /// 两档都落在 `KnockTokens.danger` / `dangerBright` 上，且都满足 4.5:1。
    static let destructive = Color.rhodesHex(0xBF2207, 0xFF3B30)

    /// 面板描边：增强对比度时用 `strokeContrast`。
    static func stroke(increasedContrast: Bool) -> Color {
        increasedContrast ? KnockTokens.strokeContrast : KnockTokens.stroke
    }
}

// MARK: - 菜单面板（实色 + 1pt 描边 + 44pt 行）

/// 敲敲菜单面板：深/浅双主题实色底（`KnockTokens.panel`）、圆角 20pt、1pt 描边、
/// 44pt 高行、15pt semibold 文案、危险项红字；可选 `KnockCheckerOverlay` 棋盘格。
struct KnockMenuPanel: View {
    var title: String?
    var actions: [KnockMenuAction]
    var showsChecker: Bool
    var onSelect: (KnockMenuAction) -> Void

    /// 字号 / 图标随 Dynamic Type 放大（字重固定 `.semibold`，不跟随字重收口）。
    @ScaledMetric(relativeTo: .body) private var rowFontSize: CGFloat = 15
    @ScaledMetric(relativeTo: .body) private var rowIconSize: CGFloat = 15
    @Environment(\.colorSchemeContrast) private var colorSchemeContrast

    /// 屏宽 - 两侧 28pt：iPhone 竖屏下约 272pt（与 `ChatListView` 顶栏溢出面板同宽）。
    private static let panelWidth: CGFloat = 272

    var body: some View {
        VStack(spacing: 0) {
            if let title, !title.isEmpty {
                Text(title)
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(KnockTokens.textSecondary)
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, KnockTokens.spacingL)
                    .padding(.top, KnockTokens.spacingS)
                    .padding(.bottom, KnockTokens.spacingXS)
                    .accessibilityHidden(true)
            }

            ForEach(Array(actions.enumerated()), id: \.element.id) { index, action in
                if needsSeparator(at: index) {
                    separator
                }
                row(action)
            }
        }
        .padding(.vertical, 6)
        .frame(width: Self.panelWidth)
        .background {
            RoundedRectangle(cornerRadius: KnockTokens.panelRadius, style: .continuous)
                .fill(KnockTokens.panel)
                .overlay {
                    if showsChecker {
                        KnockCheckerOverlay(cornerRadius: KnockTokens.panelRadius)
                    }
                }
        }
        .clipShape(RoundedRectangle(cornerRadius: KnockTokens.panelRadius, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: KnockTokens.panelRadius, style: .continuous)
                .strokeBorder(
                    KnockMenuPalette.stroke(increasedContrast: colorSchemeContrast == .increased),
                    lineWidth: KnockTokens.hairline
                )
        }
        .accessibilityElement(children: .contain)
        .accessibilityAddTraits(.isModal)
        .accessibilityLabel(title?.isEmpty == false ? title! : "操作菜单")
    }

    // MARK: 行

    private func row(_ action: KnockMenuAction) -> some View {
        Button {
            onSelect(action)
        } label: {
            HStack(spacing: KnockTokens.spacingM) {
                Group {
                    if let systemImage = action.systemImage {
                        Image(systemName: systemImage)
                            .font(.system(size: rowIconSize, weight: .semibold))
                    } else {
                        Color.clear
                    }
                }
                .frame(width: 20, height: 20)
                .foregroundStyle(foreground(action))
                .accessibilityHidden(true)

                Text(action.title)
                    .font(.system(size: rowFontSize, weight: .semibold))
                    .foregroundStyle(foreground(action))
                    .lineLimit(1)
                    .minimumScaleFactor(0.85)

                Spacer(minLength: 0)
            }
            .padding(.horizontal, KnockTokens.spacingL)
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(KnockMenuRowButtonStyle())
        .disabled(!action.isEnabled)
        .accessibilityLabel(action.title)
    }

    private func foreground(_ action: KnockMenuAction) -> Color {
        guard action.isEnabled else { return KnockTokens.textTertiary }
        return action.role == .destructive ? KnockMenuPalette.destructive : KnockTokens.textPrimary
    }

    // MARK: 分隔线（只出现在危险动作分组的边界，与系统菜单一致）

    private func needsSeparator(at index: Int) -> Bool {
        guard index > 0, index < actions.count else { return false }
        return actions[index].role == .destructive || actions[index - 1].role == .destructive
    }

    private var separator: some View {
        Rectangle()
            .fill(KnockTokens.stroke)
            .frame(height: KnockTokens.hairline)
            .padding(.horizontal, KnockTokens.spacingL)
            .accessibilityHidden(true)
    }
}

/// 菜单行按压态：实色 `rowPressed`（非半透明覆盖），无缩放（菜单本身已有入场缩放）。
private struct KnockMenuRowButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(configuration.isPressed ? KnockTokens.rowPressed : Color.clear)
    }
}

// MARK: - 菜单遮罩 + 面板（淡入 / 缩放 / 点遮罩关闭 / Esc 关闭）

struct KnockMenuOverlay: View {
    let presentation: KnockMenuPresentation

    @ObservedObject private var center = KnockMenuCenter.shared
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var appeared = false

    var body: some View {
        ZStack {
            // 纯黑实色遮罩（非毛玻璃）：点遮罩 = 关闭
            Color.black.opacity(0.62)
                .ignoresSafeArea()
                .contentShape(Rectangle())
                .onTapGesture { center.dismiss() }
                .accessibilityHidden(true)

            KnockMenuPanel(
                title: presentation.title,
                actions: presentation.actions,
                showsChecker: presentation.showsChecker
            ) { action in
                center.dismiss()
                action.action()
            }
            // 轻微缩放 0.96 → 1.0 + 淡入；减弱动态效果时直接显示
            .scaleEffect(appeared ? 1 : 0.96)
            .opacity(appeared ? 1 : 0)
            .padding(.horizontal, KnockTokens.spacingXXL)
            .accessibilityAddTraits(.isModal)
        }
        .onAppear {
            guard !appeared else { return }
            if reduceMotion {
                appeared = true
            } else {
                withAnimation(.spring(response: 0.26, dampingFraction: 0.88)) {
                    appeared = true
                }
            }
        }
        // Esc（硬件键盘）关闭：focusable + onKeyPress + cancelAction 双保险
        .focusable()
        .focusEffectDisabled()
        .onKeyPress(.escape) {
            center.dismiss()
            return .handled
        }
        .background {
            Button("关闭菜单") { center.dismiss() }
                .keyboardShortcut(.cancelAction)
                .frame(width: 0, height: 0)
                .opacity(0)
                .accessibilityHidden(true)
        }
    }
}

// MARK: - 长按触发器修饰符

private struct KnockContextMenuModifier: ViewModifier {
    var title: String?
    var showsChecker: Bool
    var actions: [KnockMenuAction]

    @ObservedObject private var center = KnockMenuCenter.shared
    /// 本次弹出对应的 presentation id：只有自己弹出的菜单才锁定所在列表滚动。
    @State private var presentationID: UUID?

    private var isMenuPresented: Bool {
        guard let presentationID else { return false }
        return center.presentation?.id == presentationID
    }

    func body(content: Content) -> some View {
        content
            // 菜单打开期间锁定最近滚动视图（长按判定前不锁，避免列表发黏）
            .background {
                KnockScrollLockView(locked: isMenuPresented)
                    .frame(width: 0, height: 0)
                    .accessibilityHidden(true)
            }
            // 高优先级长按：不劫持快速点击；拖动 > 22pt 时长按失败，滚动照常
            .highPriorityGesture(
                LongPressGesture(minimumDuration: 0.35, maximumDistance: 22)
                    .onEnded { _ in presentMenu() }
            )
            .modifier(KnockAccessibilityMenuActions(actions: actions.filter(\.isEnabled)))
    }

    private func presentMenu() {
        let generator = UIImpactFeedbackGenerator(style: .light)
        generator.prepare()
        generator.impactOccurred()

        let presentation = KnockMenuPresentation(
            title: title,
            showsChecker: showsChecker,
            actions: actions
        )
        presentationID = presentation.id
        center.present(presentation)
    }
}

/// 把菜单动作逐条挂成 VoiceOver 自定义动作（长按之外的等价入口）。
private struct KnockAccessibilityMenuActions: ViewModifier {
    let actions: [KnockMenuAction]

    func body(content: Content) -> some View {
        annotated(AnyView(content), from: 0)
    }

    private func annotated(_ view: AnyView, from index: Int) -> AnyView {
        guard index < actions.count else { return view }
        let action = actions[index]
        let annotatedView = AnyView(
            view.accessibilityAction(named: Text(action.title)) {
                action.action()
            }
        )
        return annotated(annotatedView, from: index + 1)
    }
}

// MARK: - 滚动锁定探针（行内无法改祖先 ScrollView，用零尺寸 UIView 向上找最近滚动视图）

private struct KnockScrollLockView: UIViewRepresentable {
    var locked: Bool

    func makeUIView(context: Context) -> KnockScrollLockUIView {
        KnockScrollLockUIView()
    }

    func updateUIView(_ uiView: KnockScrollLockUIView, context: Context) {
        uiView.setLocked(locked)
    }
}

/// 零尺寸探针：锁定时关闭最近 `UIScrollView` 的 `isScrollEnabled`，解除时恢复原值。
final class KnockScrollLockUIView: UIView {
    private var isLocked = false
    private weak var lockedScrollView: UIScrollView?
    private var previousScrollEnabled = true

    func setLocked(_ locked: Bool) {
        guard locked != isLocked else { return }
        isLocked = locked
        if locked {
            guard let scrollView = enclosingScrollView() else { return }
            lockedScrollView = scrollView
            previousScrollEnabled = scrollView.isScrollEnabled
            scrollView.isScrollEnabled = false
        } else {
            lockedScrollView?.isScrollEnabled = previousScrollEnabled
            lockedScrollView = nil
        }
    }

    /// 视图离开窗口（页面 pop / 列表重建）时兜底恢复，避免列表被永久锁死。
    override func willMove(toWindow newWindow: UIWindow?) {
        if newWindow == nil, isLocked {
            setLocked(false)
        }
        super.willMove(toWindow: newWindow)
    }

    private func enclosingScrollView() -> UIScrollView? {
        var ancestor = superview
        while let view = ancestor {
            if let scrollView = view as? UIScrollView {
                return scrollView
            }
            ancestor = view.superview
        }
        return nil
    }
}

// MARK: - 菜单宿主（App 根部挂一次，浮在全部内容之上）

private struct KnockMenuHostModifier: ViewModifier {
    @ObservedObject private var center = KnockMenuCenter.shared
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content
            // 菜单打开时把背后内容整体隐藏给 VoiceOver（面板自身带 isModal）
            .accessibilityHidden(center.presentation != nil)
            .overlay {
                if let presentation = center.presentation {
                    KnockMenuOverlay(presentation: presentation)
                        .transition(.opacity)
                }
            }
            .animation(reduceMotion ? nil : .easeOut(duration: 0.16), value: center.presentation?.id)
    }
}

// MARK: - 对外修饰符

extension View {
    /// 敲敲自绘长按菜单（替代系统 `.contextMenu`；长按 0.35s 弹出）。
    ///
    /// - Parameters:
    ///   - title: 面板顶部小标题（默认无，与系统 contextMenu 一致）。
    ///   - showsChecker: 是否叠极淡棋盘格纹理（默认关，菜单面积小、保持干净）。
    ///   - actions: 动作列表（顺序 = 面板顺序；`role: .destructive` 红字 + 前置分隔线）。
    ///
    /// ```swift
    /// messageRow
    ///     .knockContextMenu(actions: [
    ///         KnockMenuAction("复制", systemImage: "doc.on.doc") { copy() },
    ///         KnockMenuAction("删除（本地）", systemImage: "trash", role: .destructive) { delete() },
    ///     ])
    /// ```
    func knockContextMenu(
        title: String? = nil,
        showsChecker: Bool = false,
        actions: [KnockMenuAction]
    ) -> some View {
        modifier(
            KnockContextMenuModifier(
                title: title,
                showsChecker: showsChecker,
                actions: actions
            )
        )
    }

    /// 菜单宿主：挂在 App 根视图一次（`KnockKnockApp`），全站菜单共用这一个弹层。
    /// 不挂宿主时菜单只更新状态、不显示面板。
    func knockMenuHost() -> some View {
        modifier(KnockMenuHostModifier())
    }
}