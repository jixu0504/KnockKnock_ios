//
//  NotificationSettingsView.swift
//  T53 通知设置页（设置 → 通知），P4 敲敲视觉改造：4 键自绘胶囊开关 + 描边表单 / 状态面板。
//
//  - 授权状态：真实 UNUserNotificationCenter 状态（未决定/已拒绝/已授权/静默 provisional/ephemeral）；
//  - 请求授权：完整授权（真机弹系统框）/ 静默授权（provisional，不弹窗）/ 打开系统设置；
//  - 提醒开关：私聊回复 / 群聊回复 / 动态 / 日记 → settings 键
//    notify_private_reply / notify_group_reply / notify_moments / notify_diary（默认 true，落盘持久）；
//  - 说明文案：iOS 前台通知 vs 后台通知（本地通知）差异与系统限制（依据 T13 实验结论）。
//
//  业务逻辑 / 自检文案零改动；验证钩子：`--settings-extra-selftest`（见 SettingsExtraAutomation.swift）。
//

import SwiftUI

struct NotificationSettingsView: View {
    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.dismiss) private var dismiss

    struct PrefToggle: Identifiable {
        let id: String
        /// 与 shared NotificationBridge.savePref(scope:) 对齐
        let scope: String
        let title: String
        let detail: String
        let systemImage: String
        let key: String
    }

    static let toggles: [PrefToggle] = [
        PrefToggle(
            id: "private_reply",
            scope: "private_reply",
            title: "私聊回复提醒",
            detail: "干员回复完成时提醒我（含主动消息）",
            systemImage: "bubble.left.fill",
            key: "notify_private_reply"
        ),
        PrefToggle(
            id: "group_reply",
            scope: "group_reply",
            title: "群聊回复提醒",
            detail: "群聊中有新消息或干员互动时提醒我",
            systemImage: "person.3.fill",
            key: "notify_group_reply"
        ),
        PrefToggle(
            id: "moments",
            scope: "moments",
            title: "动态提醒",
            detail: "动态广场发布 / 评论 / 点赞等事件",
            systemImage: "sparkles",
            key: "notify_moments"
        ),
        PrefToggle(
            id: "diary",
            scope: "diary",
            title: "干员日记提醒",
            detail: "干员写下新日记时提醒我",
            systemImage: "book.closed.fill",
            key: "notify_diary"
        ),
    ]

    @State private var permission: NotificationPermissionItem?
    @State private var isLoadingPermission = false
    @State private var prefs: NotificationPrefsItem?
    @State private var values: [String: Bool] = [:]
    @State private var statusMessage = ""
    @State private var statusIsError = false
    @State private var scrollOffset: CGFloat = 0

    private var client: SettingsExtraClient { SettingsExtraClient.shared }

    var body: some View {
        ZStack {
            AmbientBackground(parallax: scrollOffset, showsStandee: false)

            VStack(spacing: 0) {
                KnockHeader(title: "通知", showsBack: true, onBack: { dismiss() })

                ScrollView {
                    VStack(spacing: KnockTokens.spacingXL) {
                        permissionGroup
                        togglesGroup
                        explanationGroup
                        diagnosticsGroup
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
        .navigationTitle("通知")
        .settingsKnockNavigationChrome()
        .task {
            loadPrefs()
            await loadPermission()
        }
    }

    // MARK: 数据（逻辑零改动）

    private func loadPrefs() {
        let prefs = client.notificationPrefs()
        self.prefs = prefs
        values = [
            "private_reply": prefs.privateReply,
            "group_reply": prefs.groupReply,
            "moments": prefs.moments,
            "diary": prefs.diary,
        ]
        SelfTestLog.add("notifications prefs load snapshot=\(client.notificationPrefsSnapshot()) keys=\(prefs.settingsKeys.joined(separator: ","))")
    }

    private func loadPermission() async {
        isLoadingPermission = true
        permission = await client.notificationAuthorizationStatus()
        isLoadingPermission = false
        if let permission {
            SelfTestLog.add("notifications permission status=\(permission.status)/\(permission.statusName) granted=\(permission.granted) provisional=\(permission.provisional) error='\(permission.error)'")
        }
    }

    private func setPref(_ toggle: PrefToggle, enabled: Bool) {
        let result = client.saveNotificationPref(scope: toggle.scope, enabled: enabled)
        let readBack = client.notificationPrefs()
        values = [
            "private_reply": readBack.privateReply,
            "group_reply": readBack.groupReply,
            "moments": readBack.moments,
            "diary": readBack.diary,
        ]
        prefs = readBack
        statusIsError = !result.isEmpty
        statusMessage = result.isEmpty
            ? "已保存 \(toggle.key)=\(enabled)（读回：\(client.notificationPrefsSnapshot())）"
            : result
        SelfTestLog.add("notifications save key=\(toggle.key) enabled=\(enabled) result='\(result)' readBack=\(client.notificationPrefsSnapshot())")
    }

    // MARK: 权限

    private var permissionGroup: some View {
        SettingsKnockGroup("通知授权", systemImage: "bell.badge") {
            KnockPanel {
                VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                    HStack(spacing: KnockTokens.spacingM) {
                        KnockRowGlyph(systemImage: permissionIcon, tint: permissionColor, size: 36)
                        VStack(alignment: .leading, spacing: KnockTokens.spacingXXS) {
                            KnockText(permission?.label ?? (isLoadingPermission ? "读取中…" : "未知状态"), role: .rowTitle, lineLimit: 2)
                            if let permission {
                                KnockText(
                                    "status=\(permission.status) · granted=\(permission.granted) · provisional=\(permission.provisional)",
                                    role: .mono,
                                    color: KnockTokens.textTertiary,
                                    lineLimit: 2
                                )
                            }
                        }
                        Spacer(minLength: KnockTokens.spacingS)
                        if isLoadingPermission {
                            ProgressView().controlSize(.small)
                        }
                    }

                    Button {
                        Task {
                            isLoadingPermission = true
                            permission = await client.requestFullNotificationAuthorization()
                            isLoadingPermission = false
                            if let permission {
                                SelfTestLog.add("notifications request full status=\(permission.status)/\(permission.statusName) granted=\(permission.granted)")
                            }
                        }
                    } label: {
                        HStack(spacing: KnockTokens.spacingS) {
                            Image(systemName: "bell.badge")
                                .font(.system(size: 14, weight: .heavy))
                            KnockText("请求完整授权", role: .rowTitle, color: KnockTokens.onElectric, lineLimit: 1)
                        }
                        .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(KnockPrimaryButtonStyle())

                    HStack(spacing: KnockTokens.spacingM) {
                        Button {
                            Task {
                                isLoadingPermission = true
                                permission = await client.requestProvisionalNotificationAuthorization()
                                isLoadingPermission = false
                                if let permission {
                                    SelfTestLog.add("notifications request provisional status=\(permission.status)/\(permission.statusName) granted=\(permission.granted)")
                                }
                            }
                        } label: {
                            HStack(spacing: KnockTokens.spacingS) {
                                Image(systemName: "bell.slash")
                                    .font(.system(size: 13, weight: .heavy))
                                KnockText("静默授权", role: .rowTitle, lineLimit: 1)
                            }
                            .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(KnockSecondaryButtonStyle())

                        Button {
                            client.openNotificationSystemSettings()
                            SelfTestLog.add("notifications open system settings")
                        } label: {
                            HStack(spacing: KnockTokens.spacingS) {
                                Image(systemName: "arrow.up.forward.app")
                                    .font(.system(size: 13, weight: .heavy))
                                KnockText("系统通知设置", role: .rowTitle, lineLimit: 2)
                            }
                            .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(KnockSecondaryButtonStyle())
                    }

                    KnockText(
                        "首次进入不会弹窗打扰（静默授权进入通知中心）；「请求完整授权」在真机会弹系统授权框。权限被拒后可用「打开系统通知设置」恢复。",
                        role: .caption,
                        color: KnockTokens.textTertiary,
                        lineSpacing: 2
                    )
                }
            }
        }
    }

    private var permissionIcon: String {
        guard let permission else { return "bell" }
        if permission.granted { return permission.provisional ? "bell.badge.slash" : "bell.badge.fill" }
        return permission.status == 1 ? "bell.slash.fill" : "bell"
    }

    private var permissionColor: Color {
        guard let permission else { return KnockTokens.textSecondary }
        if permission.granted { return KnockTokens.electric }
        return permission.status == 1 ? KnockTokens.dangerBright : KnockTokens.textSecondary
    }

    // MARK: 4 键开关

    private var togglesGroup: some View {
        SettingsKnockGroup("提醒开关", systemImage: "switch.2") {
            KnockPanel {
                VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                    ForEach(Array(Self.toggles.enumerated()), id: \.element.id) { index, toggle in
                        if index > 0 {
                            KnockDivider()
                        }
                        SettingsKnockToggleRow(
                            title: toggle.title,
                            detail: toggle.detail,
                            footnote: toggle.key,
                            isOn: Binding(
                                get: { values[toggle.id] ?? true },
                                set: { setPref(toggle, enabled: $0) }
                            )
                        )
                    }

                    if !statusMessage.isEmpty {
                        SettingsKnockStatusBanner(
                            kind: statusIsError ? .failure : .success,
                            title: statusMessage,
                            monospacedDetail: true
                        )
                    }

                    KnockText(
                        "开关保存即写入 NSUserDefaults（默认全开），冷启动保持。iOS 尚无「按开关发通知」的事件源接线（聊天/日记管线侧后续批次），本页先保证偏好键真实可读写。",
                        role: .caption,
                        color: KnockTokens.textTertiary,
                        lineSpacing: 2
                    )
                }
            }
        }
    }

    // MARK: 说明（前台 vs 后台）

    private var explanationGroup: some View {
        SettingsKnockGroup("前台通知 vs 后台通知", systemImage: "questionmark.circle") {
            KnockPanel {
                VStack(alignment: .leading, spacing: KnockTokens.spacingL) {
                    explainRow(
                        icon: "iphone",
                        title: "前台（App 正在使用）",
                        detail: "新消息由 App 内实时展示（聊天页 / 列表角标 / Toast），不经过系统通知中心；iOS 不会为前台 App 弹系统横幅。"
                    )
                    KnockDivider()
                    explainRow(
                        icon: "moon.zzz",
                        title: "后台（App 切到后台）",
                        detail: "由 App 调度的本地通知（UNUserNotificationCenter）送达通知中心。后台执行时机由 iOS 机会式调度决定，不承诺准点；打开 App 时会立即补算。"
                    )
                    KnockDivider()
                    explainRow(
                        icon: "exclamationmark.triangle",
                        title: "系统限制",
                        detail: "权限关闭后无法送达；iOS 模拟器上完整授权升级弹窗不出现（T13 实测），真机才会弹系统授权框。"
                    )
                }
            }
        }
    }

    private func explainRow(icon: String, title: String, detail: String) -> some View {
        HStack(alignment: .top, spacing: KnockTokens.spacingM) {
            KnockingIcon(systemImage: icon)
            VStack(alignment: .leading, spacing: KnockTokens.spacingXXS) {
                KnockText(title, role: .captionStrong, lineLimit: 2)
                KnockText(detail, role: .caption, color: KnockTokens.textSecondary, lineSpacing: 2)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: 诊断

    private var diagnosticsGroup: some View {
        SettingsKnockGroup("诊断", systemImage: "stethoscope") {
            KnockPanel {
                VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                    SettingsKnockInfoLine("偏好快照", client.notificationPrefsSnapshot(), monospaced: true)
                    SettingsKnockInfoLine("设置键", (prefs?.settingsKeys ?? []).joined(separator: " · "), monospaced: true)
                    SettingsKnockInfoLine("权限状态", permission?.label ?? "读取中…")
                }
            }
        }
    }
}

/// 说明行左侧的小图标（单色 heavy，无圆底，与面板层级区分）。
private struct KnockingIcon: View {
    var systemImage: String

    var body: some View {
        Image(systemName: systemImage)
            .font(.system(size: 14, weight: .heavy))
            .foregroundStyle(KnockTokens.textSecondary)
            .frame(width: 22, alignment: .center)
            .accessibilityHidden(true)
    }
}