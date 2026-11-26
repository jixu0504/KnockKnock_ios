//
//  AppearanceSettingsView.swift
//  T53 外观设置页（设置 → 外观，敲敲视觉 P4）：
//  - 主题：跟随系统 / 浅色 / 深色 → `ios_shell_appearance` + shared `dark_mode`；
//    由 `KnockKnockApp` 的 `preferredColorScheme` 实时生效（原有逻辑），
//    页面用 `KnockSegmentedBar` 呈现（选中黄胶囊 + 浅蓝外圈 + 黑字）；
//  - 字体大小：小 / 标准 / 大 → `ios_shell_font_size`；`RootTabView` 用 `.dynamicTypeSize`
//    全局缩放（聊天与列表字体一并生效；标准档 = 跟随系统）——Dynamic Type 缩放逻辑保留；
//  - **聊天背景 6 档已按决策 6 删除**：聊天页固定敲敲底（`GlassTokens.chatBackground`），
//    旧设置键 `ios_chat_background` 仍保留在 SettingsStore / shared 侧（仅不展示，不破坏读写）；
//  - 落盘读回：展示主题 / 字号键的真实读回值（自检证据）。
//
//  验证钩子：`--settings-extra-selftest` / `-verify`（见 SettingsExtraAutomation.swift）。
//

import SwiftUI

struct AppearanceSettingsView: View {
    @EnvironmentObject private var environment: AppEnvironment
    @EnvironmentObject private var settings: SettingsStore
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.dismiss) private var dismiss

    @State private var readBackMessage = ""
    @State private var scrollOffset: CGFloat = 0

    var body: some View {
        ZStack {
            AmbientBackground(parallax: scrollOffset, showsStandee: false)

            VStack(spacing: 0) {
                KnockHeader(title: "外观", showsBack: true, onBack: { dismiss() })

                ScrollView {
                    VStack(spacing: KnockTokens.spacingXL) {
                        themeGroup
                        fontSizeGroup
                        readBackGroup
                    }
                    .padding(.horizontal, KnockTokens.screenPadding)
                    .padding(.top, KnockTokens.spacingM)
                    .padding(.bottom, KnockTokens.spacingXXL)
                }
                .scrollIndicators(.hidden)
                .settingsKnockScrollEdgeHidden()
                .onScrollGeometryChange(for: CGFloat.self) { geometry in
                    geometry.contentOffset.y
                } action: { _, newValue in
                    scrollOffset = newValue
                }
            }
        }
        .navigationTitle("外观")
        .settingsKnockNavigationChrome()
        .task { refreshReadBack() }
    }

    // MARK: 主题

    private var themeGroup: some View {
        SettingsKnockGroup("主题", systemImage: "circle.lefthalf.filled") {
            KnockPanel {
                VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                    HStack(spacing: KnockTokens.spacingS) {
                        KnockText("跟随系统 / 浅色 / 深色", role: .captionStrong)
                        Spacer(minLength: KnockTokens.spacingS)
                        KnockText("当前：\(colorScheme == .dark ? "深色" : "浅色")", role: .caption, color: KnockTokens.textSecondary)
                    }

                    KnockSegmentedBar(
                        items: appearanceItems,
                        selection: Binding(
                            get: { settings.appearance },
                            set: { settings.setAppearance($0) }
                        ),
                        height: KnockTokens.segmentedHeight,
                        showsIcons: true
                    )
                    .accessibilityLabel("主题模式")

                    SettingsKnockInfoLine(
                        "设置键",
                        "\(SettingsStore.appearanceKey)（shared 同步键 dark_mode）",
                        monospaced: true
                    )

                    KnockText(
                        "跟随系统时 preferredColorScheme 为 nil；浅色/深色写入 shared SettingsBridge，实时切换全局外观（无需重启）。",
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
            KnockSegmentItem(
                value: $0,
                title: $0.label,
                systemImage: $0 == .system ? "circle.lefthalf.filled" : ($0 == .light ? "sun.max" : "moon")
            )
        }
    }

    // MARK: 字体大小（Dynamic Type 缩放保留）

    private var fontSizeGroup: some View {
        SettingsKnockGroup(
            "字体大小",
            systemImage: "textformat.size",
            footer: "聊天背景已固定为敲敲风格（原 6 档背景选项按改版决策删除，旧设置键保留兼容，不再提供入口）。"
        ) {
            KnockPanel {
                VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                    HStack(spacing: KnockTokens.spacingS) {
                        KnockText("字号三档", role: .captionStrong)
                        Spacer(minLength: KnockTokens.spacingS)
                        KnockText(settings.fontSize.label, role: .caption, color: KnockTokens.textSecondary)
                    }

                    KnockSegmentedBar(
                        items: fontSizeItems,
                        selection: Binding(
                            get: { settings.fontSize },
                            set: { settings.setFontSize($0) }
                        ),
                        height: KnockTokens.segmentedSmallHeight,
                        showsIcons: false
                    )
                    .accessibilityLabel("字体大小")

                    VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
                        KnockText("大字号预览", role: .caption, color: KnockTokens.textTertiary)
                        Text("绳匠，今晚的巡逻记录已经整理好了。ABC abc 123")
                            .font(.body)
                            .foregroundStyle(KnockTokens.textPrimary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .padding(KnockTokens.spacingM)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background {
                        RoundedRectangle(cornerRadius: KnockTokens.panelRadiusSmall, style: .continuous)
                            .fill(KnockTokens.panelInset)
                    }
                    .overlay {
                        RoundedRectangle(cornerRadius: KnockTokens.panelRadiusSmall, style: .continuous)
                            .strokeBorder(KnockTokens.strokeSecondary, lineWidth: KnockTokens.strokeWidth)
                    }

                    KnockText(
                        "会话列表 / 聊天页 / 设置页同时缩放；标准档跟随系统 Dynamic Type（可在系统设置中调字号）。",
                        role: .caption,
                        color: KnockTokens.textSecondary,
                        lineSpacing: 2
                    )

                    SettingsKnockInfoLine("设置键", SettingsStore.fontSizeKey, monospaced: true)
                    SettingsKnockInfoLine("当前档位", settings.fontSize.scaleDescription)
                }
            }
        }
    }

    private var fontSizeItems: [KnockSegmentItem<SettingsStore.FontSize>] {
        SettingsStore.FontSize.allCases.map {
            KnockSegmentItem(value: $0, title: $0.label, systemImage: nil)
        }
    }

    // MARK: 读回校验

    private var readBackGroup: some View {
        SettingsKnockGroup("落盘读回", systemImage: "arrow.triangle.2.circlepath") {
            KnockPanel {
                VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                    Button {
                        refreshReadBack()
                        SelfTestLog.add("appearance read-back \(readBackMessage)")
                    } label: {
                        HStack(spacing: KnockTokens.spacingS) {
                            Image(systemName: "arrow.clockwise")
                                .font(.system(size: 13, weight: .heavy))
                            KnockText("重新读回设置键", role: .captionStrong, lineLimit: 1)
                            Spacer(minLength: 0)
                        }
                    }
                    .buttonStyle(KnockSecondaryButtonStyle())

                    SettingsKnockInfoLine(
                        "主题键",
                        "\(SettingsStore.appearanceKey)=\(environment.bridge.settingsString(key: SettingsStore.appearanceKey))",
                        monospaced: true
                    )
                    SettingsKnockInfoLine(
                        "字号键",
                        "\(SettingsStore.fontSizeKey)=\(environment.bridge.settingsString(key: SettingsStore.fontSizeKey))",
                        monospaced: true
                    )
                    SettingsKnockInfoLine("并集", readBackMessage, monospaced: true)
                }
            }
        }
    }

    private func refreshReadBack() {
        let appearance = environment.bridge.settingsString(key: SettingsStore.appearanceKey)
        let fontSize = environment.bridge.settingsString(key: SettingsStore.fontSizeKey)
        readBackMessage = "appearance=\(appearance.isEmpty ? settings.appearance.rawValue : appearance) · " +
            "font=\(fontSize.isEmpty ? settings.fontSize.rawValue : fontSize)"
    }
}