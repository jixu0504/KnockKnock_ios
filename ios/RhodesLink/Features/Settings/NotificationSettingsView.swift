//
//  NotificationSettingsView.swift
//  T53 通知设置页（设置 → 通知）：
//  - 授权状态：真实 UNUserNotificationCenter 状态（未决定/已拒绝/已授权/静默 provisional/ephemeral）；
//  - 请求授权：完整授权（真机弹系统框）/ 静默授权（provisional，不弹窗）/ 打开系统设置；
//  - 提醒开关：私聊回复 / 群聊回复 / 动态 / 日记 → settings 键
//    notify_private_reply / notify_group_reply / notify_moments / notify_diary（默认 true，落盘持久）；
//  - 说明文案：iOS 前台通知 vs 后台通知（本地通知）差异与系统限制（依据 T13 实验结论）。
//
//  验证钩子：`--settings-extra-selftest`（见 SettingsExtraAutomation.swift）。
//

import SwiftUI

struct NotificationSettingsView: View {
    @EnvironmentObject private var environment: AppEnvironment

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

            ScrollView {
                VStack(spacing: 14) {
                    permissionCard
                    togglesCard
                    explanationCard
                    diagnosticsCard
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .padding(.bottom, 24)
            }
            .scrollIndicators(.hidden)
            .onScrollGeometryChange(for: CGFloat.self) { geometry in
                geometry.contentOffset.y
            } action: { _, newValue in
                scrollOffset = newValue
            }
        }
        .navigationTitle("通知")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            loadPrefs()
            await loadPermission()
        }
    }

    // MARK: 数据

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

    // MARK: 权限卡

    private var permissionCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                Image(systemName: permissionIcon)
                    .foregroundStyle(permissionColor)
                Text("通知授权")
                    .font(.subheadline.weight(.semibold))
                Spacer()
                if isLoadingPermission {
                    ProgressView().scaleEffect(0.7)
                }
            }

            if let permission {
                Text(permission.label)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(permissionColor)
                    .fixedSize(horizontal: false, vertical: true)
                Text("status=\(permission.status) · granted=\(permission.granted) · provisional=\(permission.provisional)")
                    .font(.caption2.monospaced())
                    .foregroundStyle(.secondary)
            } else {
                Text("读取中…")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            HStack(spacing: 10) {
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
                    Label("请求完整授权", systemImage: "bell.badge")
                        .font(.footnote.weight(.semibold))
                }
                .buttonStyle(.borderedProminent)
                .tint(GlassTokens.brandOrange)

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
                    Label("静默授权", systemImage: "bell.slash")
                        .font(.footnote.weight(.semibold))
                }
                .buttonStyle(.bordered)
                .tint(GlassTokens.brandOrange)
            }

            Button {
                client.openNotificationSystemSettings()
                SelfTestLog.add("notifications open system settings")
            } label: {
                Label("打开系统通知设置", systemImage: "arrow.up.forward.app")
                    .font(.footnote)
            }
            .buttonStyle(.bordered)
            .tint(GlassTokens.brandOrange)

            Text("首次进入不会弹窗打扰（静默授权进入通知中心）；「请求完整授权」在真机会弹系统授权框。权限被拒后可用「打开系统通知设置」恢复。")
                .font(.caption2)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(cornerRadius: 22)
    }

    private var permissionIcon: String {
        guard let permission else { return "bell" }
        if permission.granted { return permission.provisional ? "bell.badge.slash" : "bell.badge.fill" }
        return permission.status == 1 ? "bell.slash.fill" : "bell"
    }

    private var permissionColor: Color {
        guard let permission else { return .secondary }
        if permission.granted { return permission.provisional ? .orange : .green }
        return permission.status == 1 ? .red : .secondary
    }

    // MARK: 开关卡

    private var togglesCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionTitle(text: "提醒开关", systemImage: "switch.2")

            VStack(spacing: 0) {
                ForEach(Self.toggles) { toggle in
                    if toggle.id != Self.toggles.first?.id {
                        Divider().padding(.leading, 46).opacity(0.4)
                    }
                    HStack(alignment: .top, spacing: 12) {
                        Image(systemName: toggle.systemImage)
                            .frame(width: 24)
                            .foregroundStyle(GlassTokens.brandOrange)
                            .padding(.top, 3)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(toggle.title)
                                .font(.body)
                            Text(toggle.detail)
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                            Text(toggle.key)
                                .font(.system(size: 9, design: .monospaced))
                                .foregroundStyle(.tertiary)
                        }
                        Spacer()
                        Toggle("", isOn: Binding(
                            get: { values[toggle.id] ?? true },
                            set: { setPref(toggle, enabled: $0) }
                        ))
                        .labelsHidden()
                        .tint(GlassTokens.brandOrange)
                        .accessibilityLabel(toggle.title)
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 12)
                }
            }
            .cardSurface(cornerRadius: 18)

            if !statusMessage.isEmpty {
                Text(statusMessage)
                    .font(.caption2.monospaced())
                    .foregroundStyle(statusIsError ? .red : .green)
                    .fixedSize(horizontal: false, vertical: true)
            }

            Text("开关保存即写入 NSUserDefaults（默认全开），冷启动保持。iOS 尚无「按开关发通知」的事件源接线（聊天/日记管线侧后续批次），本页先保证偏好键真实可读写。")
                .font(.caption2)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    // MARK: 说明卡（前台 vs 后台）

    private var explanationCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionTitle(text: "前台通知 vs 后台通知", systemImage: "questionmark.circle")

            VStack(alignment: .leading, spacing: 12) {
                explainRow(
                    icon: "iphone",
                    title: "前台（App 正在使用）",
                    detail: "新消息由 App 内实时展示（聊天页 / 列表角标 / Toast），不经过系统通知中心；iOS 不会为前台 App 弹系统横幅。"
                )
                explainRow(
                    icon: "moon.zzz",
                    title: "后台（App 切到后台）",
                    detail: "由 App 调度的本地通知（UNUserNotificationCenter）送达通知中心。后台执行时机由 iOS 机会式调度决定，不承诺准点；打开 App 时会立即补算。"
                )
                explainRow(
                    icon: "exclamationmark.triangle",
                    title: "系统限制",
                    detail: "权限关闭后无法送达；iOS 模拟器上完整授权升级弹窗不出现（T13 实测），真机才会弹系统授权框。"
                )
            }
            .cardSurface(cornerRadius: 18)
        }
    }

    private func explainRow(icon: String, title: String, detail: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: icon)
                .frame(width: 22)
                .foregroundStyle(GlassTokens.brandOrange)
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.footnote.weight(.semibold))
                Text(detail)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: 诊断卡

    private var diagnosticsCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            SectionTitle(text: "诊断", systemImage: "stethoscope")

            VStack(alignment: .leading, spacing: 6) {
                infoLine("偏好快照", client.notificationPrefsSnapshot(), monospaced: true)
                infoLine("设置键", (prefs?.settingsKeys ?? []).joined(separator: " · "), monospaced: true)
                infoLine("权限状态", permission?.label ?? "读取中…")
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .cardSurface(cornerRadius: 18)
        }
    }

    private func infoLine(_ title: String, _ value: String, monospaced: Bool = false) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.system(size: 10))
                .foregroundStyle(.secondary)
            Text(value)
                .font(monospaced ? .caption2.monospaced() : .caption2)
                .foregroundStyle(.primary)
                .lineLimit(3)
                .textSelection(.enabled)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
