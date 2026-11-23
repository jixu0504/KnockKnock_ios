//
//  SettingsView.swift
//  设置主页（Tab 根）：
//  - 数据状态卡：本地数据就绪状态 + 会话/记忆计数；
//  - 外观：跟随系统 / 浅色 / 深色（实时生效）；
//  - 字体与可访问性：Dynamic Type 实时状态 + 降低透明度 / 增强对比度 / 减弱动态效果；
//  - 二级路由：外观 / 通知 / 调试日志 / 数据备份 / 模型供应商 / 提示词编辑器等。
//
//  玻璃元素计数：执行环境卡 1 + 外观卡 1 = 2（设计原则一屏 ≤5）。
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

    var body: some View {
        ZStack {
            AmbientBackground(parallax: scrollOffset, showsStandee: false)

            ScrollViewReader { proxy in
            ScrollView {
                VStack(spacing: 14) {
                    environmentCard
                    appearanceCard
                    dynamicTypeCard
                    routeRowsCard
                        .id(Self.routeRowsAnchor)
                    aboutCard
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .padding(.bottom, 24)
            }
            .scrollIndicators(.hidden)
            .task {
                // 截图证据钩子：`--settings-route-rows` 滚动到二级路由卡（含「数据与备份」行）
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
        .navigationDestination(isPresented: $showProfile) {
            UserProfileView()
        }
        .task {
            await environment.refreshCounts()
            settings.refreshSnapshot()
        }
        .task {
            // T57 截图钩子：`--identity-demo` 自动打开「我的身份」页（仅模拟器自动化使用）
            guard ProcessInfo.processInfo.arguments.contains("--identity-demo") else { return }
            try? await Task.sleep(nanoseconds: 700_000_000)
            showProfile = true
        }
    }

    /// 设置页二级路由行锚点（`SettingsView.routeRows` 截图钩子用）
    static let routeRowsAnchor = "settings-route-rows"

    // MARK: 执行环境（玻璃 1）

    private var environmentCard: some View {
        GlassPanel(style: .clear, cornerRadius: 22) {
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 8) {
                    Image(systemName: "checkmark.seal.fill")
                        .foregroundStyle(.green)
                    Text("本地数据已就绪")
                        .font(.subheadline.weight(.semibold))
                    Spacer()
                    Text("\(environment.sessionCount) 个会话 · \(environment.memoryCount) 条记忆")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                Text("会话、记忆与设置均保存在本机，离线可用。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .padding(16)
        }
    }

    private func infoLine(_ title: String, _ value: String, monospaced: Bool = false) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title)
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
            Text(value)
                .font(monospaced ? .caption.monospaced() : .caption)
                .foregroundStyle(.primary)
                .lineLimit(3)
                .truncationMode(.middle)
                .textSelection(.enabled)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: 外观（玻璃 2）

    private var appearanceCard: some View {
        GlassPanel(style: .clear, cornerRadius: 22) {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 8) {
                    Image(systemName: "circle.lefthalf.filled")
                        .foregroundStyle(GlassTokens.brandOrange)
                    Text("外观")
                        .font(.subheadline.weight(.semibold))
                    Spacer()
                    Text("当前：\(colorScheme == .dark ? "深色" : "浅色")")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }

                Picker("外观模式", selection: Binding(
                    get: { settings.appearance },
                    set: { settings.setAppearance($0) }
                )) {
                    ForEach(SettingsStore.Appearance.allCases) { mode in
                        Text(mode.label).tag(mode)
                    }
                }
                .pickerStyle(.segmented)
                .accessibilityLabel("外观模式")

                Text("浅色 / 深色立即生效；选择「跟随系统」时随系统外观自动切换。")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(16)
        }
    }

    // MARK: Dynamic Type / 可访问性

    private var dynamicTypeCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionTitle(text: "字体与可访问性", systemImage: "textformat.size")

            VStack(alignment: .leading, spacing: 10) {
                Text("大字号预览：博士，今晚的巡逻记录已经整理好了。ABC abc 123")
                    .font(.body)
                    .foregroundStyle(.primary)
                    .fixedSize(horizontal: false, vertical: true)

                HStack(spacing: 8) {
                    stateChip("Dynamic Type", dynamicTypeText)
                    stateChip("降低透明度", reduceTransparency ? "开" : "关")
                    stateChip("增强对比度", colorSchemeContrast == .increased ? "开" : "关")
                    stateChip("减弱动态", reduceMotion ? "开" : "关")
                }

                Text("字体大小与对比度会随系统辅助功能设置自动适配。")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .cardSurface(cornerRadius: 18)

            Button {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    openURL(url)
                }
            } label: {
                Label("打开系统设置（可调整字号 / 对比度）", systemImage: "arrow.up.forward.app")
                    .font(.footnote)
            }
            .buttonStyle(.bordered)
            .tint(GlassTokens.brandOrange)
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
        VStack(spacing: 1) {
            Text(title)
                .font(.system(size: 9))
                .foregroundStyle(.secondary)
            Text(value)
                .font(.caption2.weight(.semibold))
                .foregroundStyle(GlassTokens.brandOrange)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 5)
        .background(GlassTokens.bubbleSystem, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
    }

    // MARK: 二级路由

    private var routeRowsCard: some View {
        VStack(spacing: 0) {
            routeRow("我的身份", systemImage: "person.crop.circle") {
                showProfile = true
            }
            Divider().padding(.leading, 44).opacity(0.4)
            routeRow("外观设置", systemImage: "paintpalette") {
                router.settingsPath.append(Route.settingsAppearance)
            }
            Divider().padding(.leading, 44).opacity(0.4)
            routeRow("通知设置", systemImage: "bell.badge") {
                router.settingsPath.append(Route.settingsNotifications)
            }
            Divider().padding(.leading, 44).opacity(0.4)
            routeRow("调试日志", systemImage: "doc.text.magnifyingglass") {
                router.settingsPath.append(Route.settingsDebugLogs)
            }
            Divider().padding(.leading, 44).opacity(0.4)
            routeRow("可访问性子页", systemImage: "figure.arms.open") {
                router.settingsPath.append(Route.settingsAccessibility)
            }
            Divider().padding(.leading, 44).opacity(0.4)
            routeRow("模型供应商", systemImage: "server.rack") {
                router.settingsPath.append(Route.settingsProviders)
            }
            Divider().padding(.leading, 44).opacity(0.4)
            routeRow("聊天表现", systemImage: "slider.horizontal.3") {
                router.settingsPath.append(Route.settingsChatPerformance)
            }
            Divider().padding(.leading, 44).opacity(0.4)
            routeRow("提示词编辑器", systemImage: "text.book.closed") {
                router.settingsPath.append(Route.settingsPromptEditor)
            }
            Divider().padding(.leading, 44).opacity(0.4)
            routeRow("数据与备份", systemImage: "externaldrive") {
                router.settingsPath.append(Route.settingsBackup)
            }
        }
        .cardSurface(cornerRadius: 20)
    }

    private func routeRow(_ title: String, systemImage: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: systemImage)
                    .frame(width: 24)
                    .foregroundStyle(.secondary)
                Text(title)
                    .font(.body)
                    .foregroundStyle(.primary)
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 13)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    // MARK: 关于

    private var aboutCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            SectionTitle(text: "关于", systemImage: "info.circle")
            VStack(alignment: .leading, spacing: 6) {
                infoLine("App", "RhodesLink iOS")
                infoLine("版本", "\(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "-") (\(Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "-"))")
                infoLine("兼容", "iPhone · iOS 18.0 及以上")
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .cardSurface(cornerRadius: 18)
        }
    }
}
