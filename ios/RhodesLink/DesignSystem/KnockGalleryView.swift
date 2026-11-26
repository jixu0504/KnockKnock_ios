//
//  KnockGalleryView.swift
//  「敲敲」组件画廊（P0 验收用）：一屏浏览全部 token 色板 / 字体层级 / 条形栏 / 面板行 /
//  虚线按钮 / E M P T Y 空态 / 弹窗 / 头像占位，供模拟器或真机截图核对。
//
//  设计依据：docs/敲敲UI改版计划书-v1.1.md 第 2.1 节与第 7 章验收：
//  - 启动参数 `--knock-gallery` 直接进入本页（不经过 4 Tab 与 KMP 桥，冷启动即可截图）；
//  - 可选 `--knock-appearance light|dark` 强制外观（深浅两套截图用；不传跟随设置/系统）；
//  - `--knock-chrome-proof`：顶栏/TabBar 专项验收页（标题居中 + 四槽等距 + 红 X 键），
//    供 `screenshots/knock-p0-bars-fix*.png` 截图；
//  - `--knock-exit-key`：红色 X 结束键专项页，供 `screenshots/knock-p0-exit-key*.png` 截图；
//  - 顶部两个胶囊开关：纹理总开关（`knock_texture_enabled`）与大标题微补偿
//    （`knock_stroke_compensation`：只作用于 ≥20pt 标题的 ≤0.3pt 同色微 shadow，可全局关闭）；
//  - 本页只组合 DesignSystem 组件，不含业务数据，故不依赖 `AppEnvironment` / `AppRouter`。
//
//  截图示例：
//  ```bash
//  xcrun simctl launch booted com.xsbcjx.knockknock --knock-gallery --knock-appearance dark
//  xcrun simctl launch booted com.xsbcjx.knockknock --knock-gallery --knock-chrome-proof --knock-appearance light
//  ```
//

import SwiftUI

// MARK: - 启动参数

/// 画廊启动参数解析（RhodesLinkApp 与截图脚本共用）。
enum KnockLaunchOptions {
    /// `--knock-gallery`：跳过 4 Tab，直接打开设计系统画廊。
    static var showsGallery: Bool {
        ProcessInfo.processInfo.arguments.contains("--knock-gallery")
    }

    /// `--knock-appearance light|dark`：强制外观（nil = 跟随设置/系统）。
    static var galleryColorScheme: ColorScheme? {
        let arguments = ProcessInfo.processInfo.arguments
        guard let index = arguments.firstIndex(of: "--knock-appearance"),
              index + 1 < arguments.count else { return nil }
        switch arguments[index + 1].lowercased() {
        case "light": return .light
        case "dark": return .dark
        default: return nil
        }
    }

    /// `--knock-chrome-proof`：只渲染「顶栏居中 / TabBar 等距 / 红 X 键」专项验收页。
    static var showsChromeProof: Bool {
        ProcessInfo.processInfo.arguments.contains("--knock-chrome-proof")
    }

    /// `--knock-exit-key`：只渲染红色 X 结束键专项验收页。
    static var showsExitKeySpec: Bool {
        ProcessInfo.processInfo.arguments.contains("--knock-exit-key")
    }

    /// `--knock-typography-proof`：只渲染「字重梯度 / 假加粗对照」专项验收页
    /// （一屏展示角色 → pt → weight，并给出标题 / 气泡正文样张，供 2× 放大裁切核对边缘清晰度）。
    static var showsTypographyProof: Bool {
        ProcessInfo.processInfo.arguments.contains("--knock-typography-proof")
    }
}

// MARK: - 画廊

struct KnockGalleryView: View {
    // 分段栏 / 搜索 / 筛选演示状态
    @State private var selectedTab = "chat"
    @State private var selectedFilter = "全部"
    @State private var searchText = ""
    @State private var chipState = true
    @State private var inputText = ""
    @State private var showsDialog = false
    @State private var showsDangerDialog = false

    // 全局开关（演示「可全局关闭」）
    @State private var textureEnabled = KnockPreferences.textureEnabled
    @State private var strokeCompensation = KnockPreferences.strokeCompensationEnabled

    var body: some View {
        Group {
            if KnockLaunchOptions.showsChromeProof {
                KnockChromeProofView()
            } else if KnockLaunchOptions.showsExitKeySpec {
                KnockExitKeySpecView()
            } else if KnockLaunchOptions.showsTypographyProof {
                KnockTypographyProofView()
            } else {
                galleryBody
            }
        }
        .preferredColorScheme(KnockLaunchOptions.galleryColorScheme)
    }

    // MARK: 画廊主体

    private var galleryBody: some View {
        VStack(spacing: 0) {
            header
            ScrollView(showsIndicators: false) {
                VStack(alignment: .leading, spacing: KnockTokens.spacingXXL) {
                    paletteSection
                    typographySection
                    barsSection
                    surfacesSection
                    controlsSection
                    chromeSection
                    emptySection
                    dialogSection
                    avatarSection
                    textureSection
                    footer
                }
                .padding(.horizontal, KnockTokens.screenPadding)
                .padding(.vertical, KnockTokens.spacingXL)
            }
            KnockTabBar(items: tabItems, selection: $selectedTab)
        }
        .background(KnockCamoBackground())
        .environment(\.knockTextureEnabled, textureEnabled)
        .environment(\.knockStrokeCompensation, strokeCompensation)
        .knockConfirmDialog(
            isPresented: $showsDialog,
            title: "保存这次修改？",
            message: "修改会立即写入本机设置，可随时在设置页改回。",
            confirmTitle: "保存",
            onConfirm: {}
        )
        .knockConfirmDialog(
            isPresented: $showsDangerDialog,
            title: "删除这条记忆？",
            message: "删除后无法恢复，相关聊天记录不会被删除。",
            confirmTitle: "删除",
            isDestructive: true,
            onConfirm: {}
        )
    }

    // MARK: 数据

    private var tabItems: [KnockSegmentItem<String>] {
        [
            KnockSegmentItem(value: "chat", title: "聊天", systemImage: "bubble.left.and.bubble.right"),
            KnockSegmentItem(value: "roles", title: "角色", systemImage: "person.2"),
            KnockSegmentItem(value: "features", title: "功能", systemImage: "square.grid.2x2"),
            KnockSegmentItem(value: "settings", title: "设置", systemImage: "gearshape"),
        ]
    }

    private var filterItems: [KnockSegmentItem<String>] {
        [
            KnockSegmentItem(value: "全部", title: "全部"),
            KnockSegmentItem(value: "最近", title: "最近"),
            KnockSegmentItem(value: "收藏", title: "收藏"),
        ]
    }

    // MARK: 顶栏（标题居中 + underTitle 开关槽 + 红色 X 结束键）

    private var header: some View {
        KnockHeader(
            title: "敲敲设计系统",
            subtitle: "P0 基座 · Design System",
            showsBrand: true,
            sideReserve: 104,
            underTitle: {
                // underTitle 插槽示例：聊天页放「模式切换」小按钮，此处放两个全局开关
                HStack(spacing: KnockTokens.spacingS) {
                    KnockChip(title: "纹理", isSelected: textureEnabled) {
                        textureEnabled.toggle()
                        KnockPreferences.setTextureEnabled(textureEnabled)
                    }
                    KnockChip(title: "标题微补偿", systemImage: "bold", isSelected: strokeCompensation) {
                        strokeCompensation.toggle()
                        KnockPreferences.setStrokeCompensationEnabled(strokeCompensation)
                    }
                }
            }
        ) {
            // 红色 X 结束键（KnockCloseButton 内部已升级为 KnockExitKey）
            KnockCloseButton(label: "关闭画廊") {}
        }
    }

    // MARK: 01 色板

    private var paletteSection: some View {
        section("01 · 色板 TOKENS（深 / 浅自适应）") {
            KnockPanel {
                LazyVGrid(
                    columns: [GridItem(.adaptive(minimum: 96), spacing: KnockTokens.spacingM)],
                    spacing: KnockTokens.spacingM
                ) {
                    ForEach(KnockTokens.swatches) { swatch in
                        swatchCell(swatch)
                    }
                }
            }
        }
    }

    private func swatchCell(_ swatch: KnockSwatch) -> some View {
        VStack(alignment: .leading, spacing: KnockTokens.spacingXS) {
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(swatch.color)
                .frame(height: 40)
                .overlay {
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .strokeBorder(KnockTokens.stroke, lineWidth: KnockTokens.hairline)
                }
                .accessibilityHidden(true)
            KnockText(swatch.name, role: .captionStrong, lineLimit: 1)
            KnockText(swatch.hexLabel, role: .mono, color: KnockTokens.textSecondary, lineLimit: 1)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(swatch.name)，\(swatch.hexLabel)")
    }

    // MARK: 02 字体层级

    private var typographySection: some View {
        section("02 · 字体层级 TYPOGRAPHY（真字重 · 大标题轻补偿）") {
            KnockPanel {
                VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                    ForEach(KnockTypeRole.allCases) { role in
                        VStack(alignment: .leading, spacing: KnockTokens.spacingXXS) {
                            KnockText(
                                role == .emptyPill ? "EMPTY" : "敲敲 Knock Aa",
                                role: role,
                                lineLimit: 1
                            )
                            KnockText(
                                "\(role.displayName) · \(Int(role.baseSize))pt · weight \(role.weightName)",
                                role: .mono,
                                color: KnockTokens.textSecondary,
                                lineLimit: 1
                            )
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            }
        }
    }

    // MARK: 03 条形栏

    private var barsSection: some View {
        section("03 · 条形栏 BARS（搜索 44 / 一级 44 / 二级 36）") {
            VStack(spacing: KnockTokens.spacingM) {
                KnockSearchBar(placeholder: "搜索会话、角色或记忆", text: $searchText)

                KnockSegmentedBar(items: tabItems, selection: $selectedTab)

                KnockFilterBar(items: filterItems, selection: $selectedFilter) {
                    KnockDropdown(items: filterItems, selection: $selectedFilter)
                }
            }
        }
    }

    // MARK: 04 面板 / 胶囊行

    private var surfacesSection: some View {
        section("04 · 面板 PANEL / 胶囊行 ROW / 虚线按钮") {
            KnockPanel {
                VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                    KnockGroupTitle(title: "绳网", systemImage: "network", actionTitle: "+ 新建", action: {})

                    KnockListRow(
                        title: "灰烬",
                        subtitle: "罗德岛 · 近卫 · 已信赖",
                        avatarId: "knock:ash",
                        avatarName: "灰烬",
                        action: {}
                    )
                    KnockListRow(
                        title: "阿米娅",
                        subtitle: "罗德岛 · 领袖",
                        systemImage: "crown.fill",
                        tint: KnockTokens.electric
                    )
                    KnockListRow(
                        title: "消息通知",
                        subtitle: "私聊 / 群聊 / 动态 / 日记",
                        systemImage: "bell.fill",
                        trailingText: "已开启",
                        action: {}
                    )

                    KnockDashedButton(title: "新建角色", systemImage: "plus", emphasized: true) {}
                }
            }
        }
    }

    // MARK: 05 控件

    private var controlsSection: some View {
        section("05 · 控件 CONTROLS（按钮 / 输入 / Chip / Badge）") {
            KnockPanel {
                VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                    Button("主按钮 · 黄胶囊 CTA") {}
                        .buttonStyle(KnockPrimaryButtonStyle(fullWidth: false))

                    HStack(spacing: KnockTokens.spacingM) {
                        Button("次按钮") {}
                            .buttonStyle(KnockSecondaryButtonStyle())
                        Button("删除") {}
                            .buttonStyle(KnockDangerButtonStyle())
                    }

                    KnockInputField(
                        title: "显示名称",
                        placeholder: "输入角色名或昵称",
                        text: $inputText,
                        footnote: "仅本机可见，不会同步到其他设备。"
                    )

                    HStack(spacing: KnockTokens.spacingS) {
                        KnockChip(title: "全部", isSelected: chipState) { chipState.toggle() }
                        KnockChip(title: "未读", systemImage: "envelope.badge") {}
                        KnockChip(title: "已归档", systemImage: "archivebox") {}
                    }

                    HStack(spacing: KnockTokens.spacingS) {
                        KnockBadge(text: "3")
                        KnockBadge(text: "NEW", variant: .accent)
                        KnockBadge(text: "未读 2", variant: .chat)
                        KnockBadge(text: "错误", variant: .danger)
                    }

                    KnockDivider(label: "一分钟前")
                }
            }
        }
    }

    // MARK: 06 外壳（顶栏 / 红 X 键 / TabBar）

    private var chromeSection: some View {
        section("06 · 外壳 CHROME（顶栏居中 / underTitle / 红色 X 结束键 / TabBar）") {
            VStack(spacing: KnockTokens.spacingM) {
                // 聊天页形态：返回 + 标题/副标题居中 + underTitle 模式胶囊 + 右侧红 X 键
                KnockHeader(
                    title: "阿米娅",
                    subtitle: "私聊 · 演示",
                    showsBack: true,
                    onBack: {},
                    sideReserve: 92,
                    underTitle: {
                        KnockChip(title: "线上", systemImage: "chevron.down") {}
                    }
                ) {
                    KnockCloseButton(label: "关闭私聊") {}
                }
                .clipShape(RoundedRectangle(cornerRadius: KnockTokens.panelRadius, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: KnockTokens.panelRadius, style: .continuous)
                        .strokeBorder(KnockTokens.stroke, lineWidth: KnockTokens.strokeWidth)
                }

                // 一级页形态：品牌 logo + 居中标题 + 右侧黄胶囊
                KnockHeader(
                    title: "敲敲",
                    subtitle: "一级页面顶栏",
                    showsBrand: true,
                    sideReserve: 104,
                    trailing: {
                        KnockCapsuleIconButton(
                            systemImage: "plus",
                            variant: .accent,
                            accessibilityLabel: "新建会话"
                        ) {}
                    }
                )
                .clipShape(RoundedRectangle(cornerRadius: KnockTokens.panelRadius, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: KnockTokens.panelRadius, style: .continuous)
                        .strokeBorder(KnockTokens.stroke, lineWidth: KnockTokens.strokeWidth)
                }

                exitKeyRow

                KnockTabBar(items: tabItems, selection: $selectedTab)
            }
        }
    }

    /// 红色 X 结束键展示行（不同尺寸 + 与 chevron 返回键并置对照）。
    private var exitKeyRow: some View {
        HStack(spacing: KnockTokens.spacingXL) {
            VStack(spacing: KnockTokens.spacingXS) {
                KnockExitKey {}
                KnockText("46×30", role: .mono, color: KnockTokens.textSecondary)
            }
            VStack(spacing: KnockTokens.spacingXS) {
                KnockExitKey(size: CGSize(width: 69, height: 45)) {}
                KnockText("×1.5", role: .mono, color: KnockTokens.textSecondary)
            }
            VStack(alignment: .leading, spacing: KnockTokens.spacingXS) {
                KnockText("KnockExitKey", role: .captionStrong)
                KnockText(
                    "胶囊样式：-3° 旋转 · 圆角 30% · 左下尖角尾巴 · 红 #C02207 + 黑外描边 2.5pt + 内缩 3pt 暗红 1.6pt · 左上浅红高光 · 黑粗 X · 轻微暗投影（无发光/渐变）",
                    role: .caption,
                    color: KnockTokens.textSecondary,
                    lineSpacing: 3
                )
            }
            Spacer(minLength: 0)
        }
        .padding(KnockTokens.spacingL)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background {
            RoundedRectangle(cornerRadius: KnockTokens.panelRadius, style: .continuous)
                .fill(KnockTokens.panel)
        }
        .overlay {
            RoundedRectangle(cornerRadius: KnockTokens.panelRadius, style: .continuous)
                .strokeBorder(KnockTokens.stroke, lineWidth: KnockTokens.strokeWidth)
        }
    }

    // MARK: 07 空态

    private var emptySection: some View {
        section("07 · 空态 E M P T Y") {
            KnockEmptyState(
                title: "这里还没有内容",
                message: "创建第一条会话后，敲敲会在这里显示最近消息。",
                systemImage: "tray",
                actionTitle: "新建会话"
            ) {}
        }
    }

    // MARK: 08 弹窗

    private var dialogSection: some View {
        section("08 · 弹窗 DIALOG（自绘 · 无系统玻璃）") {
            HStack(spacing: KnockTokens.spacingM) {
                Button("普通确认弹窗") { showsDialog = true }
                    .buttonStyle(KnockSecondaryButtonStyle())
                Button("危险确认弹窗") { showsDangerDialog = true }
                    .buttonStyle(KnockDangerButtonStyle())
            }
            .padding(KnockTokens.spacingL)
            .frame(maxWidth: .infinity)
            .background {
                RoundedRectangle(cornerRadius: KnockTokens.panelRadius, style: .continuous)
                    .fill(KnockTokens.panel)
            }
            .overlay {
                RoundedRectangle(cornerRadius: KnockTokens.panelRadius, style: .continuous)
                    .strokeBorder(KnockTokens.stroke, lineWidth: KnockTokens.strokeWidth)
            }
        }
    }

    // MARK: 09 头像

    private var avatarSection: some View {
        section("09 · 头像 AVATAR（黑底 + 1.5pt 蓝圈 + 白 heavy 首字）") {
            KnockPanel {
                VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                    HStack(alignment: .bottom, spacing: KnockTokens.spacingL) {
                        KnockAvatar(name: "灰烬", size: 36)
                        KnockAvatar(name: "阿米娅", size: 44)
                        KnockAvatar(name: "凯尔希", size: 52)
                        KnockAvatar(name: "博士", size: 72)
                    }
                    KnockDivider()
                    HStack(spacing: KnockTokens.spacingM) {
                        // knock: 素材缺失 → 首字占位（用户丢图即生效）
                        RoleAvatar(avatarId: "knock:ash", name: "灰烬", size: 44)
                        // 旧内置数字 1..147 → 默认不再展示（走首字占位）
                        RoleAvatar(avatarId: "12", name: "旧头像", size: 44)
                        // 空值 / 非法值 → 首字占位
                        RoleAvatar(avatarId: "", name: "未命名", size: 44)
                        KnockText(
                            "knock:ash → knock_ash.png；缺失回退首字；数字 1..147 默认停用",
                            role: .caption,
                            color: KnockTokens.textSecondary,
                            lineSpacing: 3
                        )
                    }
                }
            }
        }
    }

    // MARK: 10 纹理

    private var textureSection: some View {
        section("10 · 纹理 TEXTURE（斜切迷彩 / 2pt 棋盘格 · 可全局关闭）") {
            HStack(spacing: KnockTokens.spacingM) {
                textureSample(kind: .camo)
                textureSample(kind: .checker)
            }
        }
    }

    private func textureSample(kind: KnockTextureKind) -> some View {
        VStack(alignment: .leading, spacing: KnockTokens.spacingXS) {
            ZStack {
                KnockTokens.canvas
                KnockTexture(kind: kind)
            }
            .frame(maxWidth: .infinity)
            .frame(height: 88)
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .strokeBorder(KnockTokens.stroke, lineWidth: KnockTokens.strokeWidth)
            }
            KnockText(kind.displayName, role: .caption, color: KnockTokens.textSecondary, lineLimit: 1)
        }
    }

    // MARK: 页脚

    private var footer: some View {
        VStack(alignment: .leading, spacing: KnockTokens.spacingXS) {
            KnockDivider()
            KnockText(
                "设计依据 docs/敲敲UI改版计划书-v1.1.md · 黄 ≤5% / 蓝 ≤3% / 红 <1% · 禁止毛玻璃与半透明白",
                role: .caption,
                color: KnockTokens.textSecondary,
                lineSpacing: 3
            )
            KnockText(
                "启动参数：--knock-gallery [--knock-appearance dark|light] [--knock-chrome-proof] [--knock-exit-key]",
                role: .mono,
                color: KnockTokens.textTertiary
            )
        }
    }

    // MARK: 通用分组

    private func section<Content: View>(
        _ title: String,
        @ViewBuilder content: () -> Content
    ) -> some View {
        VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
            KnockGroupTitle(title: title)
            content()
        }
    }
}

// MARK: - 顶栏 / TabBar 专项验收页（--knock-chrome-proof）

/// 顶栏居中 + TabBar 四槽等距 + 红 X 键的专项截图页：
/// 中央画一条浅蓝参考线（#8CC4FF，仅验收页使用），方便肉眼/像素核对标题与槽位是否居中。
struct KnockChromeProofView: View {
    @State private var selectedTab = "chat"
    @State private var selectedFilter = "全部"

    private var tabItems: [KnockSegmentItem<String>] {
        [
            KnockSegmentItem(value: "chat", title: "聊天", systemImage: "bubble.left.and.bubble.right"),
            KnockSegmentItem(value: "roles", title: "角色", systemImage: "person.2"),
            KnockSegmentItem(value: "features", title: "功能", systemImage: "square.grid.2x2"),
            KnockSegmentItem(value: "settings", title: "设置", systemImage: "gearshape"),
        ]
    }

    private var filterItems: [KnockSegmentItem<String>] {
        [
            KnockSegmentItem(value: "全部", title: "全部"),
            KnockSegmentItem(value: "最近", title: "最近"),
            KnockSegmentItem(value: "收藏", title: "收藏"),
        ]
    }

    var body: some View {
        ZStack(alignment: .top) {
            KnockCamoBackground()
            centerGuide

            VStack(spacing: 0) {
                // 聊天页形态（左返回 + 右红 X，两侧不等宽 → 标题仍需绝对居中）
                KnockHeader(
                    title: "阿米娅",
                    subtitle: "私聊 · 演示",
                    showsBack: true,
                    onBack: {},
                    sideReserve: 92,
                    underTitle: {
                        KnockChip(title: "线上", systemImage: "chevron.down") {}
                    }
                ) {
                    KnockCloseButton(label: "关闭私聊") {}
                }
                .overlay(alignment: .bottom) {
                    Rectangle()
                        .fill(KnockTokens.stroke)
                        .frame(height: KnockTokens.hairline)
                }

                // 短标题 + 左右按钮宽度不等（2 字标题仍需绝对居中）
                KnockHeader(
                    title: "功能",
                    showsBack: true,
                    onBack: {},
                    sideReserve: 92,
                    trailing: {
                        KnockCloseButton(label: "关闭") {}
                    }
                )
                .overlay(alignment: .bottom) {
                    Rectangle()
                        .fill(KnockTokens.stroke)
                        .frame(height: KnockTokens.hairline)
                }

                ScrollView(showsIndicators: false) {
                    VStack(alignment: .leading, spacing: KnockTokens.spacingXL) {
                        proofSection("A · 一级 TabBar 44pt（四槽等宽 · 黄胶囊等分槽位）") {
                            KnockSegmentedBar(items: tabItems, selection: $selectedTab)
                        }

                        proofSection("B · 二级筛选 36pt + 下拉（槽宽相等 · 黄胶囊等分）") {
                            KnockFilterBar(items: filterItems, selection: $selectedFilter) {
                                KnockDropdown(items: filterItems, selection: $selectedFilter)
                            }
                            KnockSegmentedBar(
                                items: filterItems,
                                selection: $selectedFilter,
                                height: KnockTokens.segmentedSmallHeight,
                                showsIcons: false
                            )
                        }

                        proofSection("C · 槽位中心与屏幕中心对齐（浅蓝参考线）") {
                            HStack(spacing: 0) {
                                ForEach(tabItems) { item in
                                    VStack(spacing: KnockTokens.spacingXS) {
                                        Rectangle()
                                            .fill(KnockTokens.electricRing)
                                            .frame(width: 1, height: 18)
                                        KnockText(item.title, role: .caption, color: KnockTokens.textSecondary, lineLimit: 1)
                                    }
                                    .frame(maxWidth: .infinity)
                                }
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, KnockTokens.spacingS)
                            .background {
                                RoundedRectangle(cornerRadius: 14, style: .continuous)
                                    .fill(KnockTokens.panel)
                            }
                            .overlay {
                                RoundedRectangle(cornerRadius: 14, style: .continuous)
                                    .strokeBorder(KnockTokens.stroke, lineWidth: KnockTokens.strokeWidth)
                            }
                        }

                        proofSection("D · 红色 X 结束键（46×30 / ×1.5）") {
                            HStack(spacing: KnockTokens.spacingXL) {
                                KnockExitKey {}
                                KnockExitKey(size: CGSize(width: 69, height: 45)) {}
                                KnockCloseButton(label: "关闭") {}
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(KnockTokens.spacingL)
                            .background {
                                RoundedRectangle(cornerRadius: KnockTokens.panelRadius, style: .continuous)
                                    .fill(KnockTokens.panel)
                            }
                            .overlay {
                                RoundedRectangle(cornerRadius: KnockTokens.panelRadius, style: .continuous)
                                    .strokeBorder(KnockTokens.stroke, lineWidth: KnockTokens.strokeWidth)
                            }
                        }

                        proofSection("E · 多行文本真字重渲染（无重影）") {
                            VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
                                KnockText(
                                    "第一行：敲敲设计系统\n第二行：多行文本不再重影\n第三行：行距 / 居中对齐一致",
                                    role: .bubble,
                                    lineSpacing: 4
                                )
                                KnockDivider()
                                KnockText("单行对照：真字重渲染边缘清晰", role: .bubble)
                            }
                            .padding(KnockTokens.spacingL)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background {
                                RoundedRectangle(cornerRadius: KnockTokens.panelRadius, style: .continuous)
                                    .fill(KnockTokens.panel)
                            }
                            .overlay {
                                RoundedRectangle(cornerRadius: KnockTokens.panelRadius, style: .continuous)
                                    .strokeBorder(KnockTokens.stroke, lineWidth: KnockTokens.strokeWidth)
                            }
                        }
                    }
                    .padding(.horizontal, KnockTokens.screenPadding)
                    .padding(.vertical, KnockTokens.spacingXL)
                }
            }
        }
        .environment(\.knockTextureEnabled, KnockPreferences.textureEnabled)
        .environment(\.knockStrokeCompensation, KnockPreferences.strokeCompensationEnabled)
    }

    /// 浅蓝参考线：验证标题块与 TabBar 槽位中心是否落在屏幕中心。
    private var centerGuide: some View {
        GeometryReader { proxy in
            Rectangle()
                .fill(KnockTokens.electricRing.opacity(0.55))
                .frame(width: 1)
                .frame(maxHeight: .infinity)
                .position(x: proxy.size.width / 2, y: proxy.size.height / 2)
                .allowsHitTesting(false)
                .accessibilityHidden(true)
        }
        .ignoresSafeArea()
    }

    private func proofSection<Content: View>(
        _ title: String,
        @ViewBuilder content: () -> Content
    ) -> some View {
        VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
            KnockGroupTitle(title: title)
            content()
        }
    }
}

// MARK: - 红色 X 结束键专项验收页（--knock-exit-key）

/// 红 X 键专项截图页：黑/白主题、不同尺寸、面板底与画布底对照。
struct KnockExitKeySpecView: View {
    var body: some View {
        VStack(spacing: 0) {
            KnockHeader(
                title: "红色 X 结束键",
                subtitle: "KnockExitKey · 胶囊样式",
                showsBrand: true,
                sideReserve: 104,
                trailing: {
                    KnockExitKey {}
                }
            )

            ScrollView(showsIndicators: false) {
                VStack(alignment: .leading, spacing: KnockTokens.spacingXL) {
                    specSection("1 · 默认 46 × 30pt（画布底 / 面板底）") {
                        HStack(spacing: KnockTokens.spacingXL) {
                            KnockExitKey {}
                            KnockExitKey(size: CGSize(width: 92, height: 60)) {}
                            Spacer(minLength: 0)
                        }
                    }

                    specSection("2 · 三重对照：1x / 1.5x / 2x") {
                        HStack(spacing: KnockTokens.spacingXL) {
                            keyColumn(size: CGSize(width: 46, height: 30), label: "46×30")
                            keyColumn(size: CGSize(width: 69, height: 45), label: "×1.5")
                            keyColumn(size: CGSize(width: 92, height: 60), label: "×2")
                            Spacer(minLength: 0)
                        }
                    }

                    specSection("3 · 顶栏右上角实际用法（KnockHeader + KnockCloseButton）") {
                        KnockHeader(
                            title: "阿米娅",
                            subtitle: "私聊 · 演示",
                            showsBack: true,
                            onBack: {},
                            sideReserve: 92
                        ) {
                            KnockCloseButton(label: "关闭私聊") {}
                        }
                        .clipShape(RoundedRectangle(cornerRadius: KnockTokens.panelRadius, style: .continuous))
                        .overlay {
                            RoundedRectangle(cornerRadius: KnockTokens.panelRadius, style: .continuous)
                                .strokeBorder(KnockTokens.stroke, lineWidth: KnockTokens.strokeWidth)
                        }
                    }

                    specSection("4 · 规格（胶囊样式）") {
                        VStack(alignment: .leading, spacing: KnockTokens.spacingXS) {
                            specLine("填充", "#C02207（红胶囊）")
                            specLine("X 图标", "#0B0B0C（黑 · 粗 5pt · 圆头 · 长≈60% 宽）")
                            specLine("形状", "胶囊：圆角 = 半高（与全局按钮同一语言）")
                            specLine("内描边", "#7A1404 1.2pt（贴边收边）")
                            specLine("投影", "黑色 2pt / y+1.5（浅色 18%、深色 35%）· 无发光 · 无渐变")
                            specLine("浅色模式", "额外 1pt 中性描边（#E2E2DD）防糊底")
                            specLine("尺寸", "46×30pt 胶囊 · 命中 ≥44pt")
                        }
                        .padding(KnockTokens.spacingL)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background {
                            RoundedRectangle(cornerRadius: KnockTokens.panelRadius, style: .continuous)
                                .fill(KnockTokens.panel)
                        }
                        .overlay {
                            RoundedRectangle(cornerRadius: KnockTokens.panelRadius, style: .continuous)
                                .strokeBorder(KnockTokens.stroke, lineWidth: KnockTokens.strokeWidth)
                        }
                    }
                }
                .padding(.horizontal, KnockTokens.screenPadding)
                .padding(.vertical, KnockTokens.spacingXL)
            }
        }
        .background(KnockCamoBackground())
        .environment(\.knockTextureEnabled, KnockPreferences.textureEnabled)
    }

    private func keyColumn(size: CGSize, label: String) -> some View {
        VStack(spacing: KnockTokens.spacingS) {
            KnockExitKey(size: size) {}
            KnockText(label, role: .mono, color: KnockTokens.textSecondary)
        }
    }

    private func specLine(_ key: String, _ value: String) -> some View {
        HStack(alignment: .top, spacing: KnockTokens.spacingS) {
            KnockText(key, role: .captionStrong, color: KnockTokens.textSecondary, lineLimit: 1)
                .frame(width: 76, alignment: .leading)
            KnockText(value, role: .caption, color: KnockTokens.textSecondary, lineSpacing: 3)
        }
    }

    private func specSection<Content: View>(
        _ title: String,
        @ViewBuilder content: () -> Content
    ) -> some View {
        VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
            KnockGroupTitle(title: title)
            content()
                .padding(KnockTokens.spacingL)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background {
                    RoundedRectangle(cornerRadius: KnockTokens.panelRadius, style: .continuous)
                        .fill(KnockTokens.panel)
                }
                .overlay {
                    RoundedRectangle(cornerRadius: KnockTokens.panelRadius, style: .continuous)
                        .strokeBorder(KnockTokens.stroke, lineWidth: KnockTokens.strokeWidth)
                }
        }
    }
}

// MARK: - 字重梯度专项验收页（--knock-typography-proof）

/// 字重梯度专项截图页（真机字重优化验收）：
/// - 上半屏：全部 `KnockTypeRole` 一行样张 + 「角色 · pt · weight」标注（与画廊 02 一致）；
/// - 中部：顶栏 / 页面标题大字样张（「联系人」「设置」「敲敲」）；
/// - 底部：气泡正文「描边补偿开 / 关」并排样张，用于 2× 放大裁切核对字形边缘
///   （重影 / 合成加粗毛边 / 灰边）。
///
/// 启动（配合 `--knock-appearance dark|light` 出深浅两套）：
/// ```
/// xcrun simctl launch booted com.sxbcjx.knockknock \
///   --knock-gallery --knock-typography-proof --knock-appearance dark
/// ```
struct KnockTypographyProofView: View {
    var body: some View {
        VStack(spacing: 0) {
            KnockHeader(
                title: "字重梯度",
                subtitle: "Typography · 真字重渲染",
                showsBrand: true,
                sideReserve: 104
            )

            ScrollView(showsIndicators: false) {
                VStack(alignment: .leading, spacing: KnockTokens.spacingXL) {
                    sampleSection("1 · 角色 → pt → weight（当前实现）") {
                        VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                            ForEach(KnockTypeRole.allCases) { role in
                                VStack(alignment: .leading, spacing: KnockTokens.spacingXXS) {
                                    KnockText(
                                        role == .emptyPill ? "EMPTY" : "敲敲 Knock Aa",
                                        role: role,
                                        lineLimit: 1
                                    )
                                    KnockText(
                                        "\(role.displayName) · \(Int(role.baseSize))pt · weight \(role.weightName)",
                                        role: .mono,
                                        color: KnockTokens.textSecondary,
                                        lineLimit: 1
                                    )
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                            }
                        }
                    }

                    sampleSection("2 · 标题大字样张（2× 裁切核对边缘）") {
                        VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
                            KnockText("联系人", role: .pageTitle, lineLimit: 1)
                            KnockText("设置", role: .pageTitle, lineLimit: 1)
                            KnockText("敲敲", role: .brand, lineLimit: 1)
                        }
                    }

                    sampleSection("3 · 气泡正文样张（假加粗开 / 关对照）") {
                        VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                            bubbleSample(title: "标题微补偿开（默认设置；气泡不受影响）")
                            bubbleSample(title: "标题微补偿关（纯真字重）")
                                .environment(\.knockStrokeCompensation, false)
                        }
                    }
                }
                .padding(.horizontal, KnockTokens.screenPadding)
                .padding(.vertical, KnockTokens.spacingXL)
            }
        }
        .background(KnockCamoBackground())
        .environment(\.knockTextureEnabled, KnockPreferences.textureEnabled)
        .environment(\.knockStrokeCompensation, KnockPreferences.strokeCompensationEnabled)
    }

    private func bubbleSample(title: String) -> some View {
        VStack(alignment: .leading, spacing: KnockTokens.spacingXS) {
            KnockText(title, role: .mono, color: KnockTokens.textSecondary, lineLimit: 1)
            KnockText("今天也想和你聊聊天，敲敲。", role: .bubble, lineLimit: 2)
                .padding(.horizontal, 10)
                .padding(.vertical, 8)
                .background {
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .fill(KnockTokens.bubbleIncoming)
                }
            KnockText(
                "第一行：多行气泡正文不再重影\n第二行：真字重渲染边缘清晰",
                role: .bubble,
                lineSpacing: 4
            )
            .padding(.horizontal, 10)
            .padding(.vertical, 8)
            .background {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(KnockTokens.bubbleIncoming)
            }
        }
    }

    private func sampleSection<Content: View>(
        _ title: String,
        @ViewBuilder content: () -> Content
    ) -> some View {
        VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
            KnockGroupTitle(title: title)
            content()
                .padding(KnockTokens.spacingL)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background {
                    RoundedRectangle(cornerRadius: KnockTokens.panelRadius, style: .continuous)
                        .fill(KnockTokens.panel)
                }
                .overlay {
                    RoundedRectangle(cornerRadius: KnockTokens.panelRadius, style: .continuous)
                        .strokeBorder(KnockTokens.stroke, lineWidth: KnockTokens.strokeWidth)
                }
        }
    }
}

// MARK: - 预览

#if DEBUG
#Preview("Knock Gallery · Dark") {
    KnockGalleryView()
        .preferredColorScheme(.dark)
}

#Preview("Knock Gallery · Light") {
    KnockGalleryView()
        .preferredColorScheme(.light)
}
#endif