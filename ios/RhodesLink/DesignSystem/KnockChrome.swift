//
//  KnockChrome.swift
//  「敲敲」外壳组件：App 顶栏 / TabBar（一级分段栏形态）/ 品牌 logo / 红色 X 结束键。
//
//  设计依据：docs/敲敲UI改版计划书-v1.1.md
//  - 第 1.2 节「顶栏（App 内）」：左黄手绘 logo（敲敲）+ 白色 heavy 品牌字；
//    右红色胶囊关闭/返回键；二级页面用 44pt 自绘顶栏（返回 + 标题 + 右侧胶囊）；
//  - 第 2.3 节：`RootTabView` 隐藏系统 TabView tab bar → 自绘 `KnockTabBar`
//    （**图 B 胶囊分段栏形态**：整体一条胶囊 + 选中黄胶囊 + 浅蓝外圈 + 黑字）；
//  - 第 7.1-9 / 7.2-1 验收：系统 TabBar 必须隐藏且无玻璃边缘。
//
//  v1.2 修正（用户实测反馈）：
//  1. `KnockHeader` 标题块**真水平居中**：标题层两侧使用**对称预留槽**（`sideReserve`），
//     标题/副标题左对齐、整块居中；标题块本身按内容宽度居中（不参与按钮挤压）。
//     左右按钮只在与标题同高的 44pt 行内垂直居中，基线不低于两侧胶囊；
//  2. 新增可选 `underTitle` ViewBuilder 插槽（默认 EmptyView）：聊天页把「模式切换」小胶囊
//     放在标题**正下方**（独立一行、居中），不挤压标题居中；
//  3. `KnockTabBar` 每槽 `frame(maxWidth: .infinity)` 等宽，图标+文字槽内居中，
//     选中黄胶囊**按内容自然宽度**（左右对称内边距）而非撑满槽位；
//  4. 新增 `KnockExitKey`：参考图红色 X 实体按键（#C02207 填充 + 黑 X + 双重描边 +
//     squircle 圆角 30% + 轻微暗投影，无发光/渐变），`KnockCloseButton` 内部改用它。
//
//  可访问性：
//  - TabBar 每项是 Button + `.isSelected` trait，VoiceOver 朗读「聊天，已选中」；
//  - 顶栏返回 / 关闭按钮均有 `accessibilityLabel`，命中区 ≥44pt；
//  - 顶栏高 44pt 起，Dynamic Type 大字号下自动增高；实体底（无毛玻璃）。
//

import SwiftUI

// MARK: - 内部测量（标题真居中用）

/// `KnockHeader` 标题文本宽度实测：只用于把标题自身对齐到屏幕中心（不参与对外 API）。
private struct KnockHeaderTitleWidthKey: PreferenceKey {
    static let defaultValue: CGFloat = 0

    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = max(value, nextValue())
    }
}

// MARK: - 品牌标识

/// 敲敲品牌标识：黄底手绘「敲」形图标 + 白色 heavy 品牌字。
struct KnockBrandMark: View {
    var text: String

    init(text: String = "敲敲") {
        self.text = text
    }

    var body: some View {
        HStack(spacing: KnockTokens.spacingS) {
            ZStack {
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .fill(KnockTokens.electric)
                Image(systemName: "hand.tap.fill")
                    .font(.system(size: 14, weight: .heavy))
                    .foregroundStyle(KnockTokens.onElectric)
            }
            .frame(width: 26, height: 26)
            .accessibilityHidden(true)

            KnockText(text, role: .brand, lineLimit: 1)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("敲敲 RhodesLink")
    }
}

// MARK: - 红色 X 结束键（参考图实测样式 · 胶囊样式）

/// 红色 X「结束/关闭」键（用户参考图实测还原）：
/// - **胶囊**（与全局按钮同一语言）：圆角 = 半高；
/// - 结构：红色填充 `#C02207` + 一圈内缩细描边 `#7A1404`（1.2pt），浅色模式额外 1pt 中性描边；
/// - 黑色粗 X（≈5pt 圆头，长度约 60% 宽）居中；
/// - 尺寸 ≈46×30pt（胶囊），命名命中区 ≥44pt；
/// - 轻微暗色投影；**无发光、无渐变**。
struct KnockExitKey: View {
    /// 键帽尺寸（默认 46×30pt，宽高比 ≈1.53）。
    var size: CGSize
    var accessibilityLabelText: String
    var action: () -> Void

    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    // 参考图实测色值
    private static let fillColor = Color(hex: 0xC02207)
    private static let innerStrokeColor = Color(hex: 0x7A1404)
    private static let outerStrokeColor = Color(hex: 0x0B0B0C)
    private static let crossColor = Color(hex: 0x0B0B0C)
    private static let highlightColor = Color(hex: 0xFF7A66)

    init(
        size: CGSize = CGSize(width: 46, height: 30),
        accessibilityLabel: String = "关闭",
        action: @escaping () -> Void
    ) {
        self.size = size
        self.accessibilityLabelText = accessibilityLabel
        self.action = action
    }


    var body: some View {
        Button(action: action) {
            capsuleArtwork
                .frame(width: size.width, height: size.height)
                // 视觉 46×30，命中区补齐到 ≥44pt（Apple 最小点击高度）
                .frame(minWidth: 44, minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(KeyPressStyle(reduceMotion: reduceMotion))
        .accessibilityLabel(accessibilityLabelText)
        .accessibilityAddTraits(.isButton)
    }

    private var capsuleArtwork: some View {
        ZStack {
            // 红色胶囊本体（与全局按钮同一「胶囊」语言）
            Capsule(style: .continuous)
                .fill(Self.fillColor)
            // 内圈暗红细描边：只做一圈收边，不改变胶囊形状
            Capsule(style: .continuous)
                .strokeBorder(Self.innerStrokeColor, lineWidth: 1.2)
            // 浅色主题：最外补 1pt 中性描边，避免红键糊在浅底
            if colorScheme == .light {
                Capsule(style: .continuous)
                    .strokeBorder(KnockTokens.stroke, lineWidth: 1)
                    .allowsHitTesting(false)
            }
            // 黑色粗 X（居中）
            crossShape
        }
        .shadow(
            color: Color.black.opacity(colorScheme == .light ? 0.18 : 0.35),
            radius: 2,
            x: 0,
            y: 1.5
        )
        .accessibilityHidden(true)
    }

    /// 黑色粗 X：≈5pt 线宽、圆头、长度约 60% 宽（自定义 Path，不依赖 SF Symbol 字形）。
    private var crossShape: some View {
        GeometryReader { proxy in
            let width = proxy.size.width
            let height = proxy.size.height
            let xLength = width * 0.55
            let yLength = height * 0.60
            let lineWidth = max(3.5, height * 0.165)
            let startX = (width - xLength) / 2
            let endX = startX + xLength
            let startY = (height - yLength) / 2
            let endY = startY + yLength

            Path { path in
                path.move(to: CGPoint(x: startX, y: startY))
                path.addLine(to: CGPoint(x: endX, y: endY))
                path.move(to: CGPoint(x: endX, y: startY))
                path.addLine(to: CGPoint(x: startX, y: endY))
            }
            .stroke(
                Self.crossColor,
                style: StrokeStyle(lineWidth: lineWidth, lineCap: .round, lineJoin: .round)
            )
        }
    }

    /// 按下反馈：轻微缩小 + 压暗（减弱动态效果时只压暗，不缩放）。
    private struct KeyPressStyle: ButtonStyle {
        let reduceMotion: Bool

        func makeBody(configuration: Configuration) -> some View {
            configuration.label
                .scaleEffect(reduceMotion ? 1 : (configuration.isPressed ? 0.96 : 1))
                .opacity(configuration.isPressed ? 0.88 : 1)
                .animation(reduceMotion ? nil : .easeOut(duration: 0.10), value: configuration.isPressed)
        }
    }
}

// MARK: - 关闭 / 返回键（保留旧 API，内部升级为红色 X 键）

/// 关闭键：纯图标 × 场景统一升级为 `KnockExitKey`（参考图红 X 实体键）。
/// - 保持 `init(systemImage:label:action:)` 兼容（`systemImage` 仅为旧调用面保留，红 X 键固定 ×）；
/// - 有文字标签的场景（弹窗「取消 / 确认」等）请用文字按钮，不要套本组件。
struct KnockCloseButton: View {
    var systemImage: String
    var label: String
    var size: CGSize
    var action: () -> Void

    init(
        systemImage: String = "xmark",
        label: String = "关闭",
        size: CGSize = CGSize(width: 46, height: 30),
        action: @escaping () -> Void
    ) {
        self.systemImage = systemImage
        self.label = label
        self.size = size
        self.action = action
    }

    var body: some View {
        KnockExitKey(size: size, accessibilityLabel: label, action: action)
    }
}

// MARK: - App 顶栏（44pt 自绘 · 标题真水平居中）

/// 敲敲顶栏：左「返回 / 品牌 logo」+ **真水平居中**的标题块 + 右自定义胶囊区。
/// - 二级页面：`showsBack: true` + `onBack` → 左侧返回胶囊；
/// - 一级页面：`showsBrand: true` → 左侧黄色手绘 logo + 品牌字；
/// - `underTitle`：标题**正下方**的独立插槽（聊天页模式切换小按钮），默认空；
/// - `trailing`：右侧胶囊（`KnockCloseButton` / `KnockCapsuleIconButton`）。
///
/// 居中原理：标题层左右各留 `sideReserve`（默认 92pt）**对称**槽位，标题块按内容宽居中；
/// 两侧按钮只占据与标题同高的 44pt 行（不参与标题宽度计算），因此两侧按钮宽度不等也不会推歪标题。
struct KnockHeader<Trailing: View, UnderTitle: View>: View {
    var title: String
    var subtitle: String?
    var showsBrand: Bool
    var showsBack: Bool
    var onBack: (() -> Void)?
    /// 标题层左右对称预留宽度（给两侧按钮让位；默认 92pt，宽尾部可调大）。
    var sideReserve: CGFloat

    /// 实测标题文本宽度：让标题**自身**（而非「标题/副标题块」）落在屏幕水平中心，
    /// 副标题仍与标题左边对齐；副标题更宽时向右自然溢出，不把标题推偏。
    @State private var measuredTitleWidth: CGFloat = 0

    private let underTitle: () -> UnderTitle
    private let trailing: () -> Trailing

    init(
        title: String,
        subtitle: String? = nil,
        showsBrand: Bool = false,
        showsBack: Bool = false,
        onBack: (() -> Void)? = nil,
        sideReserve: CGFloat = 92,
        @ViewBuilder underTitle: @escaping () -> UnderTitle,
        @ViewBuilder trailing: @escaping () -> Trailing
    ) {
        self.title = title
        self.subtitle = subtitle
        self.showsBrand = showsBrand
        self.showsBack = showsBack
        self.onBack = onBack
        self.sideReserve = sideReserve
        self.underTitle = underTitle
        self.trailing = trailing
    }

    /// `underTitle` 是否为空：EmptyView 时不渲染第二行（不额外占高）。
    private var hasUnderTitle: Bool {
        ObjectIdentifier(UnderTitle.self) != ObjectIdentifier(EmptyView.self)
    }

    var body: some View {
        VStack(spacing: 0) {
            mainRow

            if hasUnderTitle {
                underTitle()
                    .frame(maxWidth: .infinity, alignment: .center)
                    .padding(.horizontal, KnockTokens.screenPadding)
                    .padding(.bottom, KnockTokens.spacingS)
            }
        }
        .background(KnockTokens.canvas)
    }

    // MARK: 主行（44pt 高；标题块与两侧按钮同高、垂直居中对齐）

    private var mainRow: some View {
        ZStack(alignment: .center) {
            titleBlock
            sideChrome
        }
        .frame(maxWidth: .infinity, minHeight: KnockTokens.headerHeight)
        .padding(.horizontal, KnockTokens.screenPadding)
    }

    /// 标题块：以**标题文本宽度**为居中基准（标题自身精确居中）；
    /// 副标题与标题左边对齐、按自然宽度向右溢出（不被标题宽度截断）。
    private var titleBlock: some View {
        VStack(alignment: .leading, spacing: 1) {
            KnockText(
                title,
                role: .pageTitle,
                lineLimit: 1,
                minimumScaleFactor: 0.7
            )
            .background {
                GeometryReader { proxy in
                    Color.clear.preference(
                        key: KnockHeaderTitleWidthKey.self,
                        value: proxy.size.width
                    )
                }
            }
            if let subtitle, !subtitle.isEmpty {
                KnockText(
                    subtitle,
                    role: .caption,
                    color: KnockTokens.textSecondary,
                    lineLimit: 1,
                    minimumScaleFactor: 0.8
                )
                // 副标题不参与「居中基准宽」：保持与标题左对齐、自己按内容宽铺开
                .fixedSize(horizontal: true, vertical: false)
            }
        }
        // 布局宽 = 标题宽（未知时先按自然宽）；副标题超出部分向右溢出
        .frame(width: measuredTitleWidth > 0 ? measuredTitleWidth : nil, alignment: .leading)
        .onPreferenceChange(KnockHeaderTitleWidthKey.self) { width in
            if width > 0, abs(width - measuredTitleWidth) > 0.5 {
                measuredTitleWidth = width
            }
        }
        // 再把「标题宽度的块」整体居中；左右对称预留保证两侧按钮宽度不等也不推歪标题
        .frame(maxWidth: .infinity, alignment: .center)
        .padding(.horizontal, sideReserve)
    }

    /// 两侧操作区：只占据 44pt 行内空间，不参与标题宽度计算。
    private var sideChrome: some View {
        HStack(spacing: KnockTokens.spacingS) {
            leadingSlot
            Spacer(minLength: 0)
            trailing()
        }
    }

    @ViewBuilder
    private var leadingSlot: some View {
        if showsBack {
            backButton
        } else if showsBrand {
            KnockBrandMark()
        }
    }

    /// 返回胶囊（左侧操作槽；不参与标题居中计算）。
    private var backButton: some View {
        Button {
            onBack?()
        } label: {
            HStack(spacing: 4) {
                Image(systemName: "chevron.left")
                    .font(.system(size: 13, weight: .heavy))
                KnockText("返回", role: .captionStrong, lineLimit: 1)
            }
            .foregroundStyle(KnockTokens.textPrimary)
            .padding(.horizontal, 12)
            .frame(minHeight: 30)
            .background(Capsule(style: .continuous).fill(KnockTokens.panel))
            .overlay {
                Capsule(style: .continuous)
                    .strokeBorder(KnockTokens.stroke, lineWidth: KnockTokens.strokeWidth)
            }
            // 视觉 30pt、命中区补齐到 44pt
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("返回")
    }
}

// MARK: - KnockHeader 便利构造（保持旧调用面兼容）

// 旧调用：只有右侧 trailing（标题 + 副标题 + 可选品牌/返回），无 underTitle。
extension KnockHeader where UnderTitle == EmptyView {
    init(
        title: String,
        subtitle: String? = nil,
        showsBrand: Bool = false,
        showsBack: Bool = false,
        onBack: (() -> Void)? = nil,
        sideReserve: CGFloat = 92,
        @ViewBuilder trailing: @escaping () -> Trailing
    ) {
        self.init(
            title: title,
            subtitle: subtitle,
            showsBrand: showsBrand,
            showsBack: showsBack,
            onBack: onBack,
            sideReserve: sideReserve
        ) {
            EmptyView()
        } trailing: {
            trailing()
        }
    }
}

// 聊天页：标题 + underTitle（模式切换小按钮），无右侧 trailing。
//
// 注意：`underTitle` 之后刻意保留一个**非闭包的默认参数** `_ emptyTrailing`，
// 作用是让「最后一个参数不是闭包」，从而单 trailing 闭包写法 `KnockHeader(title:) { … }`
// 不可能匹配到本 init（否则与「只有 trailing 的便利 init」产生 ambiguous init 报错）。
extension KnockHeader where Trailing == EmptyView {
    init(
        title: String,
        subtitle: String? = nil,
        showsBrand: Bool = false,
        showsBack: Bool = false,
        onBack: (() -> Void)? = nil,
        sideReserve: CGFloat = 92,
        @ViewBuilder underTitle: @escaping () -> UnderTitle,
        _ emptyTrailing: EmptyView = EmptyView()
    ) {
        self.init(
            title: title,
            subtitle: subtitle,
            showsBrand: showsBrand,
            showsBack: showsBack,
            onBack: onBack,
            sideReserve: sideReserve,
            underTitle: underTitle
        ) {
            EmptyView()
        }
    }
}

// 无 trailing / 无 underTitle：纯标题顶栏（旧调用兼容）。
extension KnockHeader where Trailing == EmptyView, UnderTitle == EmptyView {
    init(
        title: String,
        subtitle: String? = nil,
        showsBrand: Bool = false,
        showsBack: Bool = false,
        onBack: (() -> Void)? = nil,
        sideReserve: CGFloat = 92
    ) {
        self.init(
            title: title,
            subtitle: subtitle,
            showsBrand: showsBrand,
            showsBack: showsBack,
            onBack: onBack,
            sideReserve: sideReserve,
            underTitle: { EmptyView() },
            trailing: { EmptyView() }
        )
    }
}

// MARK: - TabBar（一级分段栏形态 · 胶囊容器 + 选中黄胶囊）

/// 敲敲自绘 TabBar：整条胶囊容器（高 44pt）+ 选中黄胶囊（内容自然宽 + 浅蓝外圈 + 黑字）。
/// 与 `KnockSegmentedBar` 同款视觉，但作为根容器吸附在安全区底部（实色底，非玻璃）。
/// 每个 Tab 槽位等宽（`KnockSegmentedBar` 内 `frame(maxWidth: .infinity)`），图标+文字槽内居中。
struct KnockTabBar<Value: Hashable>: View {
    let items: [KnockSegmentItem<Value>]
    @Binding var selection: Value

    init(items: [KnockSegmentItem<Value>], selection: Binding<Value>) {
        self.items = items
        self._selection = selection
    }

    var body: some View {
        KnockSegmentedBar(
            items: items,
            selection: $selection,
            height: KnockTokens.segmentedHeight
        )
        // 整条容器撑满可用宽度：四槽等宽、左右内边距一致
        .frame(maxWidth: .infinity)
        .padding(.horizontal, KnockTokens.screenPadding)
        .padding(.top, KnockTokens.spacingS)
        .padding(.bottom, KnockTokens.spacingXS)
        .background {
            // 实色底并延伸到 Home Indicator 区域（禁止玻璃/半透明）
            KnockTokens.canvas.ignoresSafeArea(edges: .bottom)
        }
    }
}