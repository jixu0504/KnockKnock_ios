//
//  SettingsView.swift
//  设置主页（Tab 根，敲敲视觉 P4）：
//  - 页面大标题 + 分组标题（KnockGroupTitle）+ 胶囊行（KnockListRow）导航；
//  - 本机数据状态面板：本地数据就绪状态 + 会话/记忆计数；
//  - 记忆：总开关（memory_v2_enabled）/ 跨角色关系回忆（private_recall_relationship_memory）+
//    「查看记忆体检」入口 + 保留期选择（clean_days_memory_items：30 / 90 天 / 永久）；
//  - 外观与可访问性：跟随系统 / 浅色 / 深色（KnockSegmentedBar，实时生效）+
//    Dynamic Type / 降低透明度 / 增强对比度 / 减弱动态效果状态 chip + 大字号预览；
//  - 二级路由：外观 / 通知 / 调试日志 / 数据备份 / 模型供应商 / 聊天表现 / 提示词编辑器等；
//  - 关于：App / 版本 / 兼容。
//
//  视觉纪律（计划书 1.2 / 2.1）：系统导航栏隐藏，页面底 = 纯色 + 斜切纹理；
//  黄色只做「选中 / 主操作 / 品牌」，本页黄色仅出现在 Tab 选中、主 CTA 与状态图标。
//
//  保留钩子：`--settings-route-rows`（滚动到二级路由区）、`--identity-demo`（打开我的身份）。
//

import SwiftUI
import UIKit

struct SettingsView: View {
    @EnvironmentObject private var environment: AppEnvironment
    @EnvironmentObject private var settings: SettingsStore
    @EnvironmentObject private var router: AppRouter

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorSchemeContrast) private var colorSchemeContrast
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.openURL) private var openURL

    @State private var scrollOffset: CGFloat = 0
    /// T57：我的身份页（设置主页入口；`--identity-demo` 截图钩子自动推入）
    @State private var showProfile = false
    /// 记忆设置（settings 键：`memory_v2_enabled` / `private_recall_relationship_memory`）
    @State private var memoryV2Enabled = true
    @State private var relationshipMemoryRecall = true
    /// 记忆保留期（settings 键：`clean_days_memory_items`；`-1` = 永久，shared 哨兵）
    @State private var memoryRetentionDays = -1

    var body: some View {
        ZStack {
            AmbientBackground(parallax: scrollOffset, showsStandee: false)

            ScrollViewReader { proxy in
                ScrollView {
                    VStack(spacing: KnockTokens.spacingXL) {
                        SettingsKnockPageTitle(
                            title: "设置",
                            subtitle: "敲敲 · 本机离线可用 · 当前\(colorScheme == .dark ? "深色" : "浅色")"
                        )
                        .padding(.horizontal, KnockTokens.spacingXS)

                        environmentGroup
                        memoryGroup
                        appearanceGroup
                        navigationGroup
                            .id(Self.routeRowsAnchor)
                        aboutGroup
                    }
                    .padding(.horizontal, KnockTokens.screenPadding)
                    .padding(.top, KnockTokens.spacingM)
                    .padding(.bottom, KnockTokens.spacingXXL)
                }
                .scrollIndicators(.hidden)
                .task {
                    // 截图证据钩子：`--settings-route-rows` 滚动到二级路由区（含「数据与备份」行）
                    guard ProcessInfo.processInfo.arguments.contains("--settings-route-rows") else { return }
                    try? await Task.sleep(nanoseconds: 600_000_000)
                    withAnimation(.none) { proxy.scrollTo(Self.routeRowsAnchor, anchor: .top) }
                }
                .onScrollGeometryChange(for: CGFloat.self) { geometry in
                    geometry.contentOffset.y
                } action: { _, newValue in
                    scrollOffset = newValue
                }
            }
        }
        .navigationTitle("设置")
        .settingsKnockNavigationChrome()
        .navigationDestination(isPresented: $showProfile) {
            UserProfileView()
        }
        .task {
            await environment.refreshCounts()
            settings.refreshSnapshot()
            loadMemorySettings()
        }
        .task {
            // T57 截图钩子：`--identity-demo` 自动打开「我的身份」页（仅模拟器自动化使用）
            guard ProcessInfo.processInfo.arguments.contains("--identity-demo") else { return }
            try? await Task.sleep(nanoseconds: 700_000_000)
            showProfile = true
        }
        .task {
            // 截图/自动化钩子：`--settings-route <section>` 在设置 Tab 就绪后稳定推入二级页
            // （深链 `rhodes://settings/*` 在冷启动瞬间可能早于 NavigationStack 绑定而丢失，
            //   截图脚本改用本钩子；仅模拟器自动化使用，对用户路径零影响）。
            await pushLaunchRouteIfNeeded()
        }
    }

    /// `--settings-route` 支持的分段（与 AppRouter 深链名对齐）。
    private static let launchRouteNames: [String: Route] = [
        "appearance": .settingsAppearance,
        "accessibility": .settingsAccessibility,
        "providers": .settingsProviders,
        "provider": .settingsProviders,
        "backup": .settingsBackup,
        "backups": .settingsBackup,
        "chat-performance": .settingsChatPerformance,
        "performance": .settingsChatPerformance,
        "prompt": .settingsPromptEditor,
        "prompts": .settingsPromptEditor,
        "prompt-editor": .settingsPromptEditor,
        "notifications": .settingsNotifications,
        "notification": .settingsNotifications,
        "debug-logs": .settingsDebugLogs,
        "logs": .settingsDebugLogs,
    ]

    private func pushLaunchRouteIfNeeded() async {
        let arguments = ProcessInfo.processInfo.arguments
        guard let index = arguments.firstIndex(of: "--settings-route"), index + 1 < arguments.count else { return }
        let raw = arguments[index + 1].lowercased()
        guard let route = Self.launchRouteNames[raw] else { return }
        // 等设置根页 / NavigationStack 绑定就绪后再推入，避免冷启动瞬间的路径丢失。
        try? await Task.sleep(nanoseconds: 800_000_000)
        guard router.settingsPath.isEmpty else { return }
        router.settingsPath.append(route)
    }

    /// 设置页二级路由行锚点（`SettingsView.routeRows` 截图钩子用）
    static let routeRowsAnchor = "settings-route-rows"

    // MARK: 本机数据

    private var environmentGroup: some View {
        SettingsKnockGroup("本机数据", systemImage: "internaldrive") {
            KnockPanel {
                VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
                    HStack(spacing: KnockTokens.spacingM) {
                        KnockRowGlyph(systemImage: "checkmark.seal.fill", tint: KnockTokens.electric, size: 36)
                        VStack(alignment: .leading, spacing: KnockTokens.spacingXXS) {
                            KnockText("本地数据已就绪", role: .rowTitle, lineLimit: 1)
                            KnockText(
                                "\(environment.sessionCount) 个会话 · \(environment.memoryCount) 条记忆",
                                role: .caption,
                                color: KnockTokens.textSecondary,
                                lineLimit: 1
                            )
                        }
                        Spacer(minLength: KnockTokens.spacingS)
                        KnockBadge(text: "离线", variant: .neutral)
                    }

                    KnockDivider()

                    KnockText(
                        "会话、记忆与设置均保存在本机，离线可用；导入的备份在重启后换入。",
                        role: .caption,
                        color: KnockTokens.textSecondary,
                        lineSpacing: 2
                    )
                }
            }
        }
    }

    // MARK: 记忆

    private var memoryGroup: some View {
        SettingsKnockGroup("记忆", systemImage: "brain.head.profile") {
            KnockPanel {
                VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                    SettingsKnockToggleRow(
                        title: "记忆系统",
                        detail: "关闭后停止生成新记忆，也不再向对话注入记忆；已有记忆仍可在「记忆管理」中查看、编辑或删除。",
                        isOn: Binding(
                            get: { memoryV2Enabled },
                            set: { newValue in
                                environment.bridge.setMemoryPrivacyFlag(key: "memory_v2_enabled", value: newValue)
                                memoryV2Enabled = environment.bridge.memoryPrivacySettings().memoryV2Enabled
                                SelfTestLog.add("settings memory_v2_enabled=\(memoryV2Enabled)")
                            }
                        )
                    )

                    KnockDivider()

                    SettingsKnockToggleRow(
                        title: "跨角色关系回忆",
                        detail: "开启后，你与某个角色的关系记忆可能被其他角色的对话引用；介意角色之间串味或需要角色隔离时请关闭。",
                        isOn: Binding(
                            get: { relationshipMemoryRecall },
                            set: { newValue in
                                environment.bridge.setRelationshipMemoryRecallEnabled(newValue)
                                relationshipMemoryRecall = environment.bridge.relationshipMemoryRecallEnabled()
                                SelfTestLog.add("settings private_recall_relationship_memory=\(relationshipMemoryRecall)")
                            }
                        )
                    )

                    KnockDivider()

                    KnockListRow(
                        title: "记忆维护",
                        subtitle: "整理记忆索引与长期印象",
                        systemImage: "sparkles"
                    ) {
                        router.settingsPath.append(Route.memoryList)
                    }

                    KnockDivider()

                    memoryRetentionBlock

                    KnockText(
                        "保留期按记忆写入时的设置计算，已有条目不会因切换而改写；不同记忆层级与来源仍按各自策略决定是否提前过期。",
                        role: .caption,
                        color: KnockTokens.textTertiary,
                        lineSpacing: 2
                    )
                }
            }
        }
    }

    /// 记忆保留期（30 / 90 天 / 永久）—— 写入 `clean_days_memory_items`，不新建键。
    private var memoryRetentionBlock: some View {
        VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
            HStack(spacing: KnockTokens.spacingS) {
                KnockText("记忆保留期", role: .captionStrong)
                Spacer(minLength: KnockTokens.spacingS)
                KnockText(memoryRetentionLabel, role: .caption, color: KnockTokens.textSecondary, lineLimit: 1)
            }

            KnockSegmentedBar(
                items: memoryRetentionItems,
                selection: Binding(
                    get: { memoryRetentionDays },
                    set: { newValue in
                        environment.bridge.putIntSetting(key: Self.memoryRetentionKey, value: newValue)
                        memoryRetentionDays = environment.bridge.getIntSetting(
                            key: Self.memoryRetentionKey,
                            default: Self.memoryRetentionPermanent
                        )
                        SelfTestLog.add("settings clean_days_memory_items=\(memoryRetentionDays)")
                    }
                ),
                height: KnockTokens.segmentedSmallHeight,
                showsIcons: false
            )
            .accessibilityLabel("记忆保留期")

            KnockText(
                "过期记忆先软删，仍可在「记忆管理」中查看与恢复；宽限期结束后由本机清理任务物理删除，不承诺固定清理时刻。",
                role: .caption,
                color: KnockTokens.textSecondary,
                lineSpacing: 2
            )
            KnockText(
                "建议 90 天：兼顾回忆密度与本机体积；默认永久保留，不替你做修改。",
                role: .caption,
                color: KnockTokens.textTertiary,
                lineSpacing: 2
            )
        }
    }

    private static let memoryRetentionKey = "clean_days_memory_items"
    private static let memoryRetentionPermanent = -1
    private static let memoryRetentionOptions: [(days: Int, title: String)] = [
        (30, "30 天"),
        (90, "90 天（建议）"),
        (-1, "永久"),
    ]

    private var memoryRetentionItems: [KnockSegmentItem<Int>] {
        Self.memoryRetentionOptions.map { KnockSegmentItem(value: $0.days, title: $0.title, systemImage: nil) }
    }

    private var memoryRetentionLabel: String {
        switch memoryRetentionDays {
        case 30: return "当前：30 天"
        case 90: return "当前：90 天"
        case -1: return "当前：永久"
        default: return "当前：\(memoryRetentionDays) 天"
        }
    }

    private func loadMemorySettings() {
        memoryV2Enabled = environment.bridge.memoryPrivacySettings().memoryV2Enabled
        relationshipMemoryRecall = environment.bridge.relationshipMemoryRecallEnabled()
        memoryRetentionDays = environment.bridge.getIntSetting(
            key: Self.memoryRetentionKey,
            default: Self.memoryRetentionPermanent
        )
        SelfTestLog.add(
            "settings memory load v2=\(memoryV2Enabled) relationshipRecall=\(relationshipMemoryRecall) retention=\(memoryRetentionDays)"
        )
    }

    // MARK: 外观与可访问性

    private var appearanceGroup: some View {
        SettingsKnockGroup("外观与可访问性", systemImage: "circle.lefthalf.filled") {
            KnockPanel {
                VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                    HStack(spacing: KnockTokens.spacingS) {
                        KnockText("外观模式", role: .captionStrong)
                        Spacer(minLength: KnockTokens.spacingS)
                        KnockText("当前：\(settings.appearance.label)", role: .caption, color: KnockTokens.textSecondary)
                    }

                    KnockSegmentedBar(
                        items: appearanceItems,
                        selection: Binding(
                            get: { settings.appearance },
                            set: { settings.setAppearance($0) }
                        ),
                        height: KnockTokens.segmentedSmallHeight,
                        showsIcons: false
                    )
                    .accessibilityLabel("外观模式")

                    Text("大字号预览：绳匠，今晚的巡逻记录已经整理好了。ABC abc 123")
                        .font(.body)
                        .foregroundStyle(KnockTokens.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)

                    LazyVGrid(
                        columns: [GridItem(.adaptive(minimum: 108), spacing: KnockTokens.spacingS)],
                        alignment: .leading,
                        spacing: KnockTokens.spacingS
                    ) {
                        stateChip("Dynamic Type", dynamicTypeText)
                        stateChip("降低透明度", reduceTransparency ? "开" : "关")
                        stateChip("增强对比度", colorSchemeContrast == .increased ? "开" : "关")
                        stateChip("减弱动态", reduceMotion ? "开" : "关")
                    }

                    Button {
                        if let url = URL(string: UIApplication.openSettingsURLString) {
                            openURL(url)
                        }
                    } label: {
                        HStack(spacing: KnockTokens.spacingS) {
                            Image(systemName: "arrow.up.forward.app")
                                .font(.system(size: 13, weight: .heavy))
                            KnockText("打开系统设置（字号 / 对比度）", role: .captionStrong, lineLimit: 1)
                            Spacer(minLength: 0)
                        }
                    }
                    .buttonStyle(KnockSecondaryButtonStyle())

                    KnockText(
                        "浅色 / 深色立即生效；「跟随系统」随系统外观自动切换。字体大小档位在「外观设置」中调整。",
                        role: .caption,
                        color: KnockTokens.textTertiary,
                        lineSpacing: 2
                    )
                }
            }
        }
    }

    private var appearanceItems: [KnockSegmentItem<SettingsStore.Appearance>] {
        SettingsStore.Appearance.allCases.map {
            KnockSegmentItem(value: $0, title: $0.label, systemImage: nil)
        }
    }

    private var dynamicTypeText: String {
        switch dynamicTypeSize {
        case .xSmall: return "xS"
        case .small: return "S"
        case .medium: return "M"
        case .large: return "L"
        case .xLarge: return "xL"
        case .xxLarge: return "xxL"
        case .xxxLarge: return "xxxL"
        case .accessibility1: return "AX1"
        case .accessibility2: return "AX2"
        case .accessibility3: return "AX3"
        case .accessibility4: return "AX4"
        case .accessibility5: return "AX5"
        @unknown default: return "?"
        }
    }

    private func stateChip(_ title: String, _ value: String) -> some View {
        HStack(spacing: KnockTokens.spacingXS) {
            KnockText(title, role: .caption, color: KnockTokens.textSecondary, lineLimit: 1)
            KnockText(value, role: .badge, color: KnockTokens.textPrimary, lineLimit: 1)
        }
        .padding(.horizontal, KnockTokens.spacingM)
        .frame(minHeight: KnockTokens.chipHeight)
        .background(Capsule(style: .continuous).fill(KnockTokens.row))
        .overlay {
            Capsule(style: .continuous)
                .strokeBorder(KnockTokens.strokeSecondary, lineWidth: KnockTokens.strokeWidth)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(title) \(value)")
    }

    // MARK: 二级路由（胶囊行）

    private var navigationGroup: some View {
        VStack(alignment: .leading, spacing: KnockTokens.spacingXL) {
            SettingsKnockGroup("个性化", systemImage: "person.crop.circle") {
                VStack(spacing: KnockTokens.spacingS) {
                    KnockListRow(
                        title: "我的身份",
                        subtitle: "昵称与角色卡对外展示",
                        systemImage: "person.crop.circle"
                    ) {
                        showProfile = true
                    }
                    KnockListRow(
                        title: "外观设置",
                        subtitle: "主题 · 字体大小 · 落盘读回",
                        systemImage: "paintpalette"
                    ) {
                        router.settingsPath.append(Route.settingsAppearance)
                    }
                    KnockListRow(
                        title: "通知设置",
                        subtitle: "授权状态 · 4 类提醒开关",
                        systemImage: "bell.badge"
                    ) {
                        router.settingsPath.append(Route.settingsNotifications)
                    }
                    KnockListRow(
                        title: "可访问性子页",
                        subtitle: "降低透明度 / 增强对比度 / 减弱动态",
                        systemImage: "figure.arms.open"
                    ) {
                        router.settingsPath.append(Route.settingsAccessibility)
                    }
                }
            }

            SettingsKnockGroup("模型与提示词", systemImage: "server.rack") {
                VStack(spacing: KnockTokens.spacingS) {
                    KnockListRow(
                        title: "模型供应商",
                        subtitle: "11 家供应商 · 自定义端点 · 连接测试",
                        systemImage: "server.rack"
                    ) {
                        router.settingsPath.append(Route.settingsProviders)
                    }
                    KnockListRow(
                        title: "聊天表现",
                        subtitle: "温度 · 深度思考 · 上下文 · 输出预算",
                        systemImage: "slider.horizontal.3"
                    ) {
                        router.settingsPath.append(Route.settingsChatPerformance)
                    }
                    KnockListRow(
                        title: "提示词编辑器",
                        subtitle: "模板目录 · 保存 / 恢复默认 / 禁用",
                        systemImage: "text.book.closed"
                    ) {
                        router.settingsPath.append(Route.settingsPromptEditor)
                    }
                }
            }

            SettingsKnockGroup("数据", systemImage: "externaldrive") {
                KnockListRow(
                    title: "数据与备份",
                    subtitle: "完整备份 · 角色卡 · 聊天记录导入导出",
                    systemImage: "externaldrive"
                ) {
                    router.settingsPath.append(Route.settingsBackup)
                }
            }
        }
    }

    // MARK: 关于

    private var aboutGroup: some View {
        SettingsKnockGroup("关于", systemImage: "info.circle") {
            KnockPanel {
                VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                    SettingsKnockInfoLine("App", "敲敲")
                    SettingsKnockInfoLine(
                        "版本",
                        "\(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "-") (\(Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "-"))"
                    )
                    SettingsKnockInfoLine("兼容", "iPhone · iOS 18.0 及以上")
                    SettingsKnockInfoLine("界面风格", "敲敲 · 深色 / 浅色 / 跟随系统")
                }
            }
        }
    }
}