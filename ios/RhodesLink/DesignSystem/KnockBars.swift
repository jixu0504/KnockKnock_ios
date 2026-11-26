//
//  KnockBars.swift
//  「敲敲」条形栏组件：搜索条 / 一二级分段栏 / 下拉胶囊。
//
//  设计依据：docs/敲敲UI改版计划书-v1.1.md 第 1.2 节「条形栏规格」（图 B/C/D 实测）：
//  - 搜索条：胶囊（radius=半高，高 44pt），底 `#1C1C1C`，描边 1.5pt `#303030`；
//    左侧文字灰色加粗，右侧 1pt 竖分隔线 + 放大镜图标；
//  - 一级分段栏（Tab 替代）：胶囊容器（高 44pt，底 `#0A0A0A`，描边 1.5pt `#2A2A2E`），
//    内部 3–4 等分项；**选中项 = 黄色胶囊（内缩 4pt）+ 浅蓝 1.5pt 外圈 + 黑色 heavy 图标/文字**；
//    未选中 = 白色（浅色模式为黑）heavy 图标/文字；
//  - 二级筛选栏：同款但更小（高 36pt）；末尾可接「全部 ⌄」下拉胶囊（同款描边）。
//
//  可访问性：
//  - 分段项是真实 Button，带 `.isSelected` trait 与文字标签（VoiceOver 可朗读「已选中」）；
//  - 高度一律 `minHeight`（Dynamic Type 大字号下自动增高，不裁切）；
//  - 减弱动态效果（reduceMotion）时选中切换不做动画；
//  - 增强对比度（colorSchemeContrast）时描边加深。
//
//  配色纪律：黄色只做「选中」，面积远小于 5%；本文件除选中胶囊外不出现黄色。
//

import SwiftUI

// MARK: - 分段项（分段栏 / Tab 栏 / 下拉共用）

/// 一个分段项：`value` 为业务标识，`title` 为文案，`systemImage` 为可选 SF Symbol（单色 heavy）。
struct KnockSegmentItem<Value: Hashable>: Identifiable {
    let value: Value
    let title: String
    let systemImage: String?

    init(value: Value, title: String, systemImage: String? = nil) {
        self.value = value
        self.title = title
        self.systemImage = systemImage
    }

    var id: Value { value }
}

// MARK: - 搜索条

/// 敲敲搜索条（图 B）：胶囊 + 1.5pt 描边 + 右侧竖分隔线 + 放大镜。
struct KnockSearchBar: View {
    var placeholder: String
    @Binding var text: String
    /// 点击放大镜 / 键盘「搜索」时回调（调用方决定是否过滤）。
    var onSubmit: (() -> Void)?

    @FocusState private var isFocused: Bool
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.colorSchemeContrast) private var colorSchemeContrast

    init(
        placeholder: String = "搜索",
        text: Binding<String>,
        onSubmit: (() -> Void)? = nil
    ) {
        self.placeholder = placeholder
        self._text = text
        self.onSubmit = onSubmit
    }

    var body: some View {
        HStack(spacing: KnockTokens.spacingM) {
            TextField("", text: $text, prompt: promptText)
                .textFieldStyle(.plain)
                .font(KnockTypography.font(.rowTitle, dynamicTypeSize: dynamicTypeSize))
                .foregroundStyle(KnockTokens.textPrimary)
                .tint(KnockTokens.electric)
                .submitLabel(.search)
                .focused($isFocused)
                .onSubmit { onSubmit?() }
                .accessibilityLabel(placeholder)

            // 1pt 竖分隔线（搜索条款式细节）
            Rectangle()
                .fill(KnockTokens.searchDivider)
                .frame(width: KnockTokens.hairline, height: 18)
                .accessibilityHidden(true)

            Button {
                isFocused = false
                onSubmit?()
            } label: {
                Image(systemName: "magnifyingglass")
                    .font(.system(size: 15, weight: .heavy))
                    .foregroundStyle(KnockTokens.textPrimary)
                    .frame(width: 28, height: 28)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("搜索")
        }
        .padding(.horizontal, KnockTokens.spacingL)
        .frame(minHeight: KnockTokens.searchHeight)
        .background(Capsule(style: .continuous).fill(KnockTokens.searchField))
        .overlay(
            Capsule(style: .continuous)
                .strokeBorder(strokeColor, lineWidth: KnockTokens.strokeWidth)
        )
    }

    /// 占位符：灰色加粗（计划书 1.2：左侧文字灰色加粗）。
    private var promptText: Text {
        Text(placeholder)
            .font(KnockTypography.font(.rowTitle, dynamicTypeSize: dynamicTypeSize))
            .foregroundStyle(KnockTokens.textSecondary)
    }

    private var strokeColor: Color {
        colorSchemeContrast == .increased ? KnockTokens.strokeContrast : KnockTokens.searchStroke
    }
}

// MARK: - 分段栏（一级 Tab / 二级筛选共用）

/// 胶囊分段栏：整条胶囊容器 + 选中黄胶囊（内缩 4pt）+ 浅蓝外圈 + 黑字。
/// - `height == 44` → 一级（Tab 替代）；`height == 36` → 二级筛选（更小字号/图标）；
/// - `showsIcons` 控制是否渲染 SF Symbol（单色 heavy）。
struct KnockSegmentedBar<Value: Hashable>: View {
    let items: [KnockSegmentItem<Value>]
    @Binding var selection: Value
    var height: CGFloat
    var showsIcons: Bool

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorSchemeContrast) private var colorSchemeContrast

    init(
        items: [KnockSegmentItem<Value>],
        selection: Binding<Value>,
        height: CGFloat = KnockTokens.segmentedHeight,
        showsIcons: Bool = true
    ) {
        self.items = items
        self._selection = selection
        self.height = height
        self.showsIcons = showsIcons
    }

    /// 36pt（二级）用小字号，44pt（一级）用 Tab 字号。
    private var isSmall: Bool { height <= KnockTokens.segmentedSmallHeight }

    private var innerMinHeight: CGFloat {
        max(26, height - KnockTokens.segmentedInset * 2)
    }

    private var containerStroke: Color {
        colorSchemeContrast == .increased ? KnockTokens.strokeContrast : KnockTokens.stroke
    }

    var body: some View {
        HStack(spacing: KnockTokens.spacingXS) {
            ForEach(items) { item in
                segment(item)
            }
        }
        // 整条胶囊撑满可用宽度：各槽等宽（frame(maxWidth: .infinity)），首尾与容器内边距对称
        .frame(maxWidth: .infinity)
        .padding(KnockTokens.segmentedInset)
        .frame(minHeight: height)
        .background(Capsule(style: .continuous).fill(KnockTokens.panel))
        .overlay(
            Capsule(style: .continuous)
                .strokeBorder(containerStroke, lineWidth: KnockTokens.strokeWidth)
        )
    }

    private func segment(_ item: KnockSegmentItem<Value>) -> some View {
        let isSelected = item.value == selection
        return Button {
            guard !isSelected else { return }
            let animation: Animation? = reduceMotion ? nil : .easeOut(duration: 0.16)
            withAnimation(animation) {
                selection = item.value
            }
        } label: {
            // 槽位等宽、内容（图标+文字）槽内水平垂直居中；
            // 选中黄胶囊撑满所在槽位（等分长度，与一级 TabBar 一致）。
            segmentContent(item, isSelected: isSelected)
                .frame(maxWidth: .infinity, minHeight: innerMinHeight)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(item.title)
        .accessibilityAddTraits(isSelected ? [.isSelected] : [])
    }

    /// 单个分段的内容：图标 + 文案 + 胶囊背景（选中时撑满槽位，等分长度）。
    private func segmentContent(_ item: KnockSegmentItem<Value>, isSelected: Bool) -> some View {
        HStack(spacing: isSmall ? 4 : 6) {
            if showsIcons, let systemImage = item.systemImage {
                Image(systemName: systemImage)
                    .font(.system(size: isSmall ? 12 : 14, weight: .heavy))
                    .foregroundStyle(isSelected ? KnockTokens.onElectric : KnockTokens.textPrimary)
            }
            KnockText(
                item.title,
                role: isSmall ? .captionStrong : .tabLabel,
                color: isSelected ? KnockTokens.onElectric : KnockTokens.textPrimary,
                lineLimit: 1,
                minimumScaleFactor: 0.85
            )
        }
        // 选中胶囊「等分槽位」：长度随槽位撑满（与一级 TabBar 完全一致），不再随内容自然宽
        .padding(.horizontal, isSmall ? 4 : 6)
        .frame(maxWidth: .infinity, minHeight: innerMinHeight)
        .background {
            if isSelected {
                // 选中黄胶囊 + 浅蓝外圈（计划书 1.2 关键细节）
                Capsule(style: .continuous)
                    .fill(KnockTokens.electric)
                    .overlay {
                        Capsule(style: .continuous)
                            .strokeBorder(KnockTokens.electricRing, lineWidth: KnockTokens.strokeWidth)
                    }
            }
        }
        .contentShape(Capsule(style: .continuous))
    }
}

// MARK: - 二级筛选栏

/// 二级筛选栏：36pt 小分段栏 + 末尾自定义尾部（通常是 `KnockDropdown`「全部 ⌄」）。
struct KnockFilterBar<Value: Hashable, Trailing: View>: View {
    let items: [KnockSegmentItem<Value>]
    @Binding var selection: Value
    private let trailing: () -> Trailing

    init(
        items: [KnockSegmentItem<Value>],
        selection: Binding<Value>,
        @ViewBuilder trailing: @escaping () -> Trailing
    ) {
        self.items = items
        self._selection = selection
        self.trailing = trailing
    }

    var body: some View {
        HStack(spacing: KnockTokens.spacingS) {
            KnockSegmentedBar(
                items: items,
                selection: $selection,
                height: KnockTokens.segmentedSmallHeight
            )
            .frame(maxWidth: .infinity)

            trailing()
        }
    }
}

extension KnockFilterBar where Trailing == EmptyView {
    /// 无尾部筛选栏（只有二级分段）。
    init(items: [KnockSegmentItem<Value>], selection: Binding<Value>) {
        self.init(items: items, selection: selection) { EmptyView() }
    }
}

// MARK: - 下拉胶囊

/// 敲敲下拉胶囊（同款 1.5pt 描边胶囊 + chevron.down），点击弹出选项菜单。
/// 说明：菜单弹层当前用系统 `Menu`（P0 先保功能与尺寸；P5 收口时按计划书第五章换自绘面板）。
struct KnockDropdown<Value: Hashable>: View {
    let items: [KnockSegmentItem<Value>]
    @Binding var selection: Value
    var height: CGFloat

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    init(
        items: [KnockSegmentItem<Value>],
        selection: Binding<Value>,
        height: CGFloat = KnockTokens.segmentedSmallHeight
    ) {
        self.items = items
        self._selection = selection
        self.height = height
    }

    private var selectedTitle: String {
        items.first { $0.value == selection }?.title ?? "全部"
    }

    var body: some View {
        Menu {
            ForEach(items) { item in
                Button {
                    selection = item.value
                } label: {
                    if item.value == selection {
                        Label(item.title, systemImage: "checkmark")
                    } else {
                        Text(item.title)
                    }
                }
            }
        } label: {
            HStack(spacing: 6) {
                KnockText(selectedTitle, role: .captionStrong, color: KnockTokens.textPrimary, lineLimit: 1)
                Image(systemName: "chevron.down")
                    .font(.system(size: 10, weight: .heavy))
                    .foregroundStyle(KnockTokens.textSecondary)
            }
            .padding(.horizontal, 14)
            .frame(minHeight: height)
            .background(Capsule(style: .continuous).fill(KnockTokens.panel))
            .overlay(
                Capsule(style: .continuous)
                    .strokeBorder(KnockTokens.stroke, lineWidth: KnockTokens.strokeWidth)
            )
            .contentShape(Capsule(style: .continuous))
        }
        .accessibilityLabel("筛选：\(selectedTitle)")
    }
}